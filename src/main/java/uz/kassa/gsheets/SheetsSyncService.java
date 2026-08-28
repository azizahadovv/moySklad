package uz.kassa.gsheets;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.bot.NameService;
import uz.kassa.domain.*;
import uz.kassa.repo.*;
import uz.kassa.service.LedgerService;
import uz.kassa.service.moysklad.MoySkladClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Google Sheets bilan IKKI TOMONLAMA sinxron (har 5 daqiqada):
 *   BOT -> SHEETS: Operatsiyalar, Balanslar, Kunlar (jarayonning to'liq ko'rinishi)
 *   SHEETS -> BOT: Kassalar va Foydalanuvchilar varaqlaridagi tahrirlar
 *                  (nom, otdel, rol, kassa, faol) botga qo'llanadi — НАСТРОЙКА jadvaldan.
 * Yangi satr (ID bo'sh) -> yangi kassa / foydalanuvchi yaratiladi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SheetsSyncService {

    private final GoogleSheetsClient gs;
    private final KassaRepo kassaRepo;
    private final AppUserRepo userRepo;
    private final OperationRepo opRepo;
    private final DayRepo dayRepo;
    private final LedgerService ledger;
    private final NameService names;
    private final MoySkladClient msClient;
    private final GuestRepo guestRepo;
    private final ClickAccountRepo clickRepo;

    private volatile boolean tabsReady = false;

    /* ==================================================================
     * DB — YAGONA HAQIQAT MANBAI. Jadval unga ergashadi, aksincha emas.
     *
     * SNAPSHOT usuli: har push'da varaqqa YOZILGAN qiymatlar eslab qolinadi
     * (settings jadvalida — restartdan keyin ham saqlanadi). Pull paytida
     * varaqdagi qiymat faqat SNAPSHOT'DAN FARQ QILSA (ya'ni operator katakni
     * haqiqatan tahrirlagan bo'lsa) DB'ga qo'llanadi. Farq qilmasa — bu shunchaki
     * eski nusxa: bot tomonida qilingan o'zgarish (rol, kassa, ism, faol,
     * Telegram ulanishi) varaq tomonidan QAYTARIB YUBORILMAYDI, keyingi push
     * varaqni DB holatiga tekislaydi. Snapshot'i yo'q satr (bot o'chiq paytda
     * paydo bo'lgan va h.k.) DB'ga ta'sir qilmaydi.
     * ================================================================== */
    private final java.util.Map<Long, String> userSnap =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Long, String> kassaSnap =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean snapsLoaded = false;

    private final uz.kassa.service.SettingsService settings;
    private final uz.kassa.config.AppProps props;

    /** Asosiy (yaratuvchi) SuperAdmin (.env SUPERADMIN_TELEGRAM_ID) — varaq orqali ham
     *  roli pasaytirilmaydi/faolsizlantirilmaydi (tizim qulflanib qolmasin). */
    private boolean isCreatorRow(AppUser x) {
        Long t = props.getSuperadmin().getTelegramId();
        return t != null && t > 0 && t.equals(x.getTelegramId());
    }

    private void loadSnaps() {
        if (snapsLoaded) return;
        parseSnap(settings.get("sheets.snap.users").orElse(""), userSnap);
        parseSnap(settings.get("sheets.snap.kassa").orElse(""), kassaSnap);
        snapsLoaded = true;
    }

    private void parseSnap(String raw, java.util.Map<Long, String> into) {
        for (String line : raw.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            try { into.put(Long.parseLong(line.substring(0, eq)), line.substring(eq + 1)); }
            catch (NumberFormatException ignored) { }
        }
    }

    private void saveSnap(String key, java.util.Map<Long, String> m) {
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        settings.set(key, sb.toString());
    }

    private String norm(String s) {
        return s == null ? "" : s.trim().replace("|", "/").replace("\n", " ");
    }

    private String digits(String s) { return s == null ? "" : s.replaceAll("\\D", ""); }

    /** Qayta ishlanmagan (chala) satrlar — push paytida SAQLAB qolinadi, o'chirilmaydi. */
    private volatile List<List<Object>> pendingUsers = List.of();
    private volatile List<List<Object>> pendingKassas = List.of();
    private volatile boolean usersPullOk = false, kassaPullOk = false;

    public void sync() {
        if (!gs.configured()) return;
        try {
            loadSnaps();
            ensureTabs();
            pullKassalar();
            pullUsers();
            pushOperatsiyalar();
            pushBalanslar();
            pushKunlar();
            pushKassalar();
            pushUsers();
            pushSozlamalar();
        } catch (Exception e) {
            log.warn("Google Sheets sinxron xatosi: {}", e.getMessage());
        }
    }

    /** Tez sikl (har 1 daqiqa): faqat НАСТРОЙКА varaqlari — foydalanuvchi/kassa
     *  tahrirlari uzoq kutmasin. Og'ir push'lar to'liq siklda (5 daq) qoladi. */
    public void syncNastroyka() {
        if (!gs.configured()) return;
        try {
            loadSnaps();
            ensureTabs();
            pullKassalar();
            pullUsers();
            pushKassalar();
            pushUsers();
        } catch (Exception e) {
            log.warn("Google Sheets tez sinxron xatosi: {}", e.getMessage());
        }
    }

    private void ensureTabs() throws Exception {
        if (tabsReady) return;
        gs.ensureTabs(List.of("Operatsiyalar", "Balanslar", "Kunlar",
                "Kassalar", "Foydalanuvchilar", "Sozlamalar"));
        tabsReady = true;
        log.info("Google Sheets ulandi");
    }

    /* ==================== SHEETS -> BOT (НАСТРОЙКА) ==================== */

    private boolean bool(String v, boolean def) {
        if (v == null || v.isBlank()) return def;
        String s = v.trim().toUpperCase();
        return s.equals("TRUE") || s.equals("HA") || s.equals("ХА") || s.equals("1") || s.equals("+");
    }

    private String cell(List<String> row, int i) {
        return i < row.size() ? row.get(i).trim() : "";
    }

    /** Rol nomini yumshoq o'qish: kassir/Kassir/KASSIR, admin -> SUPERADMIN. */
    private Role parseRole(String s) {
        String v = s == null ? "" : s.trim().toUpperCase();
        if (v.startsWith("KASSIR") || v.startsWith("КАССИР")) return Role.KASSIR;
        if (v.startsWith("BUX") || v.startsWith("БУХ")) return Role.BUXGALTER;
        if (v.contains("ADMIN") || v.contains("АДМИН") || v.startsWith("SUPER")) return Role.SUPERADMIN;
        return null;
    }

    /** Kassani ID yoki NOM bo'yicha topish. */
    private Kassa resolveKassa(String idS, String nomi) {
        if (idS != null && !idS.isBlank())
            try { return kassaRepo.findById(Long.parseLong(idS.trim())).orElse(null); }
            catch (NumberFormatException ignored) { }
        if (nomi != null && !nomi.isBlank())
            return kassaRepo.findAll().stream()
                    .filter(k -> k.getName().equalsIgnoreCase(nomi.trim())).findFirst().orElse(null);
        return null;
    }

    /** Telefon raqami bo'yicha mehmon (kontakt yuborganlar) Telegram ID sini topish. */
    private Long guestByPhone(String tel) {
        String d = tel == null ? "" : tel.replaceAll("\\D", "");
        if (d.length() < 7) return null;
        for (Guest g : guestRepo.findAll()) {
            String gp = g.getPhone() == null ? "" : g.getPhone().replaceAll("\\D", "");
            if (!gp.isEmpty() && (gp.endsWith(d) || d.endsWith(gp))) return g.getTelegramId();
        }
        return null;
    }

    /** Otdel (group) boshqa FAOL kassaga biriktirilganmi (o'zidan tashqari). */
    private boolean groupTaken(String groupId, Long exceptKassaId) {
        return kassaRepo.findAll().stream().anyMatch(o -> o.isActive()
                && groupId.equals(o.getMoyskladGroupId())
                && (exceptKassaId == null || !o.getId().equals(exceptKassaId)));
    }

    /** Kassalar varag'i: [ID, Nomi, Otdel ID, Otdel nomi, Faol]. Chala satrlar saqlanadi. */
    private void pullKassalar() {
        List<List<Object>> pending = new ArrayList<>();
        kassaPullOk = false;
        try {
            Map<String, String> groups;
            try { groups = msClient.fetchGroups(); } catch (Exception e) { groups = Map.of(); }
            Map<String, String> groupByName = new java.util.HashMap<>();
            groups.forEach((id, name) -> groupByName.put(name.trim().toLowerCase(), id));

            List<List<String>> rows = gs.get("Kassalar!A2:F100");
            for (List<String> r : rows) {
                if (r.stream().allMatch(c -> c == null || c.isBlank())) continue;
                String id = cell(r, 0), nomi = cell(r, 1), otdel = cell(r, 2), otdelNomi = cell(r, 3);
                boolean faol = bool(cell(r, 4), true);
                // Otdel: ID yo'q bo'lsa nomi bo'yicha topiladi (masalan "Отдел Шохрух")
                String groupId = !otdel.isBlank() ? otdel
                        : groupByName.getOrDefault(otdelNomi.trim().toLowerCase(), "");

                if (id.isBlank()) {
                    if (nomi.isBlank()) {
                        pending.add(List.of("", nomi, otdel, otdelNomi, faol ? "TRUE" : "FALSE",
                                "⚠️ Nomi yo'q — kassa nomini yozing"));
                        continue;
                    }
                    if (kassaRepo.findAll().stream().anyMatch(k -> k.getName().equalsIgnoreCase(nomi)))
                        continue;   // allaqachon bor
                    // Otdel boshqa faol kassada band bo'lsa — otdel'siz yaratiladi (dublikat taqiqlanadi)
                    String newGroup = groupId;
                    if (!newGroup.isBlank() && groupTaken(newGroup, null)) {
                        log.warn("Sheets: «{}» uchun otdel {} boshqa faol kassada band — otdel'siz yaratildi",
                                nomi, newGroup);
                        newGroup = "";
                    }
                    kassaRepo.save(Kassa.builder().name(nomi)
                            .moyskladGroupId(newGroup.isBlank() ? null : newGroup)
                            .active(faol).build());
                    log.info("Sheets: yangi kassa yaratildi — {}", nomi);
                    continue;
                }
                // DB USTUVOR: faqat operator haqiqatan tahrirlagan kataklar qo'llanadi
                // (snapshot bilan solishtirib). Snapshot yo'q — DB'ga tegilmaydi.
                String kSnap = kassaSnap.get(Long.parseLong(id));
                if (kSnap == null) continue;
                String[] kv = kSnap.split("\\|", -1);   // [nomi, groupId, faol]
                if (kv.length < 3) continue;
                final String gFinal = groupId;
                final String nomiN = norm(nomi);
                final String faolN = faol ? "TRUE" : "FALSE";
                kassaRepo.findById(Long.parseLong(id)).ifPresent(k -> {
                    boolean ch = false;
                    if (!nomiN.isBlank() && !nomiN.equals(kv[0]) && !nomiN.equals(k.getName())) {
                        k.setName(nomiN); ch = true;
                    }
                    String cur = k.getMoyskladGroupId() == null ? "" : k.getMoyskladGroupId();
                    if (!gFinal.isBlank() && !gFinal.equals(kv[1]) && !gFinal.equals(cur)) {
                        // Bitta otdel FAQAT bitta faol kassada bo'lishi mumkin — aks holda
                        // hujjatlar kassalar orasida ko'chib, xabar toshqini bo'ladi
                        if (groupTaken(gFinal, k.getId()))
                            log.warn("Sheets: kassa #{} uchun otdel {} boshqa faol kassada band — qo'llanmadi",
                                    k.getId(), gFinal);
                        else { k.setMoyskladGroupId(gFinal); ch = true; }
                    }
                    if (!faolN.equals(kv[2]) && faol != k.isActive()) { k.setActive(faol); ch = true; }
                    if (ch) {
                        kassaRepo.save(k);
                        log.info("Sheets: kassa #{} yangilandi (operator tahriri)", k.getId());
                    }
                });
            }
            kassaPullOk = true;
        } catch (Exception e) {
            log.warn("Sheets Kassalar o'qish: {}", e.getMessage());
        }
        pendingKassas = pending;
    }

    /** Foydalanuvchilar: [ID, TelegramID, Telefon, Ism, Rol, KassaID, KassaNomi, Faol].
     *  Chala satrlar o'chirilmaydi — «Holat» ustunida sabab ko'rsatiladi. */
    private void pullUsers() {
        List<List<Object>> pending = new ArrayList<>();
        usersPullOk = false;
        try {
            List<List<String>> rows = gs.get("Foydalanuvchilar!A2:I300");
            for (List<String> r : rows) {
                if (r.stream().allMatch(c -> c == null || c.isBlank())) continue;
                String id = cell(r, 0), tg = cell(r, 1), tel = cell(r, 2), ism = cell(r, 3),
                        rolS = cell(r, 4), kassaIdS = cell(r, 5), kassaNomi = cell(r, 6);
                boolean faol = bool(cell(r, 7), true);

                if (id.isBlank()) {
                    Role role = parseRole(rolS);
                    Long tgId = null;
                    if (!tg.isBlank())
                        try { tgId = Long.parseLong(tg.replaceAll("\\D", "")); }
                        catch (NumberFormatException ignored) { }
                    if (tgId == null) tgId = guestByPhone(tel);
                    Kassa kassa = resolveKassa(kassaIdS, kassaNomi);

                    String xato = null;
                    if (ism.isBlank()) xato = "⚠️ Ism yozing";
                    else if (role == null) xato = "⚠️ Rol: KASSIR / BUXGALTER / SUPERADMIN";
                    else if (tgId != null && userRepo.findByTelegramId(tgId).isPresent())
                            xato = "⚠️ Bu TelegramID allaqachon tizimda";
                    else if (role == Role.KASSIR && kassa == null)
                            xato = "⚠️ KassaID yoki KassaNomi kiriting";

                    if (xato == null) {
                        // Telegram'siz ham yaratiladi — ro'yxatlarda darhol ko'rinadi,
                        // kontakt yuborilganda telefon orqali avtomatik ulanadi.
                        // Takror yaratmaslik: shu ISMLI foydalanuvchi (tg bor-yo'qligidan
                        // qat'i nazar) mavjud bo'lsa — o'tkazamiz. Aks holda ID'siz satr
                        // har siklda yangi nusxa yaratib tashlaydi.
                        boolean dup = userRepo.findAll().stream()
                                .anyMatch(e -> e.getFullName().equalsIgnoreCase(ism));
                        if (!dup) {
                            String phone = tel.replaceAll("\\D", "");
                            userRepo.save(AppUser.builder()
                                    .telegramId(tgId).fullName(ism).role(role)
                                    .phone(phone.isEmpty() ? null : phone)
                                    .kassaId(role == Role.KASSIR ? kassa.getId() : null)
                                    .active(faol).build());
                            log.info("Sheets: yangi foydalanuvchi — {}", ism);
                        }
                    } else {
                        pending.add(List.of("", tg, tel, ism, rolS, kassaIdS, kassaNomi,
                                faol ? "TRUE" : "FALSE", xato));
                    }
                    continue;
                }

                // DB USTUVOR: varaqdagi qiymat faqat SNAPSHOT'dan farq qilsa (operator
                // katakni haqiqatan tahrirlagan bo'lsa) qo'llanadi. Snapshot yo'q bo'lsa —
                // varaq holati noma'lum, DB'ga tegilmaydi (push tekislaydi); faqat
                // zararsiz avto-ulash (guest telefoni) qilinadi.
                long uid = Long.parseLong(id);
                String snap = userSnap.get(uid);
                String tgN = digits(tg), telN = digits(tel), ismN = norm(ism);
                Role role = parseRole(rolS);
                Kassa kassa = resolveKassa(kassaIdS, kassaNomi);
                String faolN = faol ? "TRUE" : "FALSE";

                if (snap == null) {
                    userRepo.findById(uid).ifPresent(x -> {
                        if (x.getTelegramId() == null) {
                            Long link = guestByPhone(!telN.isEmpty() ? telN : x.getPhone());
                            if (link != null && userRepo.findByTelegramId(link).isEmpty()) {
                                x.setTelegramId(link);
                                userRepo.save(x);
                                log.info("Sheets: {} Telegram bilan bog'landi ({})",
                                        x.getFullName(), link);
                            }
                        }
                    });
                    continue;
                }
                String[] pv = snap.split("\\|", -1);   // [tg, tel, ism, rol, kassa, faol]
                if (pv.length < 6) continue;

                userRepo.findById(uid).ifPresent(x -> {
                    boolean ch = false;
                    // ISM — operator o'zgartirgan bo'lsa
                    if (!ismN.isBlank() && !ismN.equals(pv[2]) && !ismN.equals(x.getFullName())) {
                        x.setFullName(ismN); ch = true;
                    }
                    // TELEFON — operator o'zgartirgan bo'lsa (o'chirish ham)
                    if (!telN.equals(pv[1])) {
                        if (!telN.isEmpty() && !telN.equals(x.getPhone())) { x.setPhone(telN); ch = true; }
                        else if (telN.isEmpty() && x.getPhone() != null) { x.setPhone(null); ch = true; }
                    }
                    // TELEGRAM — operator o'zgartirgan bo'lsa: yozilsa ulash, o'chirilsa uzish
                    if (!tgN.equals(pv[0])) {
                        if (!tgN.isEmpty()) {
                            try {
                                Long tgNew = Long.parseLong(tgN);
                                if (!tgNew.equals(x.getTelegramId())
                                        && userRepo.findByTelegramId(tgNew).isEmpty()) {
                                    x.setTelegramId(tgNew); ch = true;
                                    log.info("Sheets: {} Telegram bilan bog'landi ({})",
                                            x.getFullName(), tgNew);
                                }
                            } catch (NumberFormatException ignored) { }
                        } else if (x.getTelegramId() != null && x.getRole() != Role.SUPERADMIN) {
                            guestRepo.findById(x.getTelegramId()).ifPresent(guestRepo::delete);
                            x.setTelegramId(null); ch = true;
                            log.info("Sheets: {} Telegram uzildi (operator o'chirdi)", x.getFullName());
                        }
                    } else if (x.getTelegramId() == null) {
                        // Hali ulanmagan: guest telefoni bo'yicha avto-ulash — har doim zararsiz
                        Long link = guestByPhone(!telN.isEmpty() ? telN : x.getPhone());
                        if (link != null && userRepo.findByTelegramId(link).isEmpty()) {
                            x.setTelegramId(link); ch = true;
                            log.info("Sheets: {} Telegram bilan bog'landi ({})", x.getFullName(), link);
                        }
                    }
                    // ROL — operator o'zgartirgan bo'lsa (oxirgi SuperAdmin va
                    // asosiy/yaratuvchi SuperAdmin himoyalangan)
                    if (role != null && !role.name().equals(pv[3]) && role != x.getRole()) {
                        if (!(isCreatorRow(x) && role != Role.SUPERADMIN)
                                && !(x.getRole() == Role.SUPERADMIN
                                && userRepo.findByRoleAndActiveTrue(Role.SUPERADMIN).size() <= 1)) {
                            x.setRole(role);
                            if (role != Role.KASSIR) x.setKassaId(null);
                            ch = true;
                        }
                    }
                    // KASSA — operator o'zgartirgan bo'lsa (faqat kassirga)
                    if (x.getRole() == Role.KASSIR && kassa != null
                            && !String.valueOf(kassa.getId()).equals(pv[4])
                            && !kassa.getId().equals(x.getKassaId())) {
                        x.setKassaId(kassa.getId()); ch = true;
                    }
                    // FAOL — operator o'zgartirgan bo'lsa (oxirgi va asosiy SuperAdmin himoyalangan)
                    if (!faolN.equals(pv[5]) && faol != x.isActive()) {
                        if (!(isCreatorRow(x) && !faol)
                                && !(x.getRole() == Role.SUPERADMIN && !faol
                                && userRepo.findByRoleAndActiveTrue(Role.SUPERADMIN).size() <= 1)) {
                            x.setActive(faol); ch = true;
                        }
                    }
                    if (ch) {
                        userRepo.save(x);
                        log.info("Sheets: foydalanuvchi #{} yangilandi (operator tahriri)", x.getId());
                    }
                });
            }
            usersPullOk = true;
        } catch (Exception e) {
            log.warn("Sheets Foydalanuvchilar o'qish: {}", e.getMessage());
        }
        pendingUsers = pending;
    }

    /* ==================== BOT -> SHEETS ==================== */

    private String owner(OwnerType t, Long id) {
        if (t == null) return "";
        return t == OwnerType.BUXGALTERIYA ? "Отдел Основной" : names.owner(t, id);
    }

    private void pushOperatsiyalar() throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("ID", "Sana", "Turi", "Pul turi", "Summa",
                "Kimdan", "Kimga", "Status", "Izoh", "MoySklad"));
        List<Operation> ops = opRepo.byPeriod(ledger.today().minusDays(60), ledger.today());
        int n = 0;
        for (Operation o : ops) {
            if (n++ >= 3000) break;
            rows.add(List.of(o.getId(), o.getOpDate().toString(), o.getType().name(),
                    o.getMoneyType().name(), o.getAmount(),
                    owner(o.getFromOwnerType(), o.getFromOwnerId()),
                    owner(o.getToOwnerType(), o.getToOwnerId()),
                    o.getStatus().name(),
                    o.getComment() == null ? "" : o.getComment(),
                    o.getMoyskladId() == null ? "" : o.getMoyskladId()));
        }
        gs.overwrite("Operatsiyalar", rows);
    }

    private void pushBalanslar() throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Kassa", "Naqd", "Band naqd", "Click", "Band click",
                "Terminal (bugun)", "JAMI"));
        long tn = 0, tk = 0, tt = 0;
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;
            var n = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.NAQD);
            var kl = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.KLIK);
            long term = dayRepo.findByKassaIdAndDate(k.getId(), ledger.today())
                    .map(DayRecord::getPrixodTerminal).orElse(0L);
            tn += n.getAmount(); tk += kl.getAmount(); tt += term;
            rows.add(List.of(k.getName(), n.getAmount(), n.getReserved(),
                    kl.getAmount(), kl.getReserved(), term,
                    n.getAmount() + kl.getAmount() + term));
        }
        var bn = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD);
        var bk = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.KLIK);
        rows.add(List.of("Отдел Основной", bn.getAmount(), bn.getReserved(),
                bk.getAmount(), bk.getReserved(), 0, bn.getAmount() + bk.getAmount()));
        long ck = 0;
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
            var cb = ledger.view(OwnerType.CLICK, c.getId(), MoneyType.KLIK);
            ck += cb.getAmount();
            rows.add(List.of("📲 " + c.getName(), "", "",
                    cb.getAmount(), cb.getReserved(), 0, cb.getAmount()));
        }
        rows.add(List.of("JAMI", tn + bn.getAmount(), "", tk + bk.getAmount() + ck, "", tt,
                tn + bn.getAmount() + tk + bk.getAmount() + ck + tt));
        gs.overwrite("Balanslar", rows);
    }

    private void pushKunlar() throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Sana", "Kassa", "Kirim naqd", "Kirim click", "Terminal",
                "Rasxod naqd", "Rasxod click", "Qoplangan naqd", "Qoplangan click", "Status"));
        LocalDate from = ledger.today().minusDays(31);
        for (DayRecord d : dayRepo.findAll()) {
            if (d.getDate().isBefore(from)) continue;
            rows.add(List.of(d.getDate().toString(),
                    names.owner(OwnerType.KASSA, d.getKassaId()),
                    d.getPrixodNaqd(), d.getPrixodKlik(), d.getPrixodTerminal(),
                    d.getRasxodNaqd(), d.getRasxodKlik(),
                    d.getCoveredNaqd(), d.getCoveredKlik(), d.getStatus().name()));
        }
        gs.overwrite("Kunlar", rows);
    }

    private void pushKassalar() throws Exception {
        if (!kassaPullOk) return;   // o'qish muvaffaqiyatsiz — foydalanuvchi tahririni yo'qotmaymiz
        Map<String, String> groups;
        try { groups = msClient.fetchGroups(); } catch (Exception e) { groups = Map.of(); }
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("ID", "Nomi", "Otdel ID", "Otdel nomi", "Faol", "Holat"));
        java.util.Map<Long, String> snap = new java.util.HashMap<>();
        for (Kassa k : kassaRepo.findAll()) {
            String g = k.getMoyskladGroupId() == null ? "" : k.getMoyskladGroupId();
            String faolS = k.isActive() ? "TRUE" : "FALSE";
            snap.put(k.getId(), norm(k.getName()) + "|" + g + "|" + faolS);
            rows.add(List.of(k.getId(), k.getName(), g,
                    groups.getOrDefault(g, ""), faolS, ""));
        }
        rows.addAll(pendingKassas);   // chala satrlar sabab bilan saqlanadi
        gs.overwrite("Kassalar", rows);
        kassaSnap.clear();
        kassaSnap.putAll(snap);
        saveSnap("sheets.snap.kassa", kassaSnap);
    }

    private void pushUsers() throws Exception {
        if (!usersPullOk) return;   // o'qish muvaffaqiyatsiz — foydalanuvchi tahririni yo'qotmaymiz
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("ID", "TelegramID", "Telefon", "Ism", "Rol",
                "KassaID", "KassaNomi", "Faol", "Holat"));
        java.util.Map<Long, String> snap = new java.util.HashMap<>();
        for (AppUser x : userRepo.findAll()) {
            String tgS = x.getTelegramId() == null ? "" : String.valueOf(x.getTelegramId());
            String telS = digits(x.getPhone());
            String kasS = x.getKassaId() == null ? "" : String.valueOf(x.getKassaId());
            String faolS = x.isActive() ? "TRUE" : "FALSE";
            // Varaqqa yozilayotgan holat snapshot'ga: keyingi pull'da faqat shundan
            // FARQ QILGAN kataklar operator tahriri deb qabul qilinadi.
            snap.put(x.getId(), tgS + "|" + telS + "|" + norm(x.getFullName()) + "|"
                    + x.getRole().name() + "|" + kasS + "|" + faolS);
            rows.add(List.of(x.getId(), tgS,
                    x.getPhone() == null ? "" : x.getPhone(),
                    x.getFullName(), x.getRole().name(),
                    x.getKassaId() == null ? "" : x.getKassaId(),
                    x.getKassaId() == null ? "" : names.owner(OwnerType.KASSA, x.getKassaId()),
                    faolS,
                    x.getTelegramId() == null
                            ? "⏳ Telegram ulanmagan — Telefon ustunini to'ldiring va odam botga kirib «📱 Telefon raqamni yuborish»ni bossin"
                            : ""));
        }
        rows.addAll(pendingUsers);   // chala satrlar sabab bilan saqlanadi
        gs.overwrite("Foydalanuvchilar", rows);
        userSnap.clear();
        userSnap.putAll(snap);
        saveSnap("sheets.snap.users", userSnap);
    }

    private void pushSozlamalar() throws Exception {
        gs.overwrite("Sozlamalar", List.of(
                List.of("Ko'rsatma", "Qiymat"),
                List.of("Oxirgi sinxron", LocalDateTime.now().withNano(0).toString()),
                List.of("Tahrir qilinadigan varaqlar", "Kassalar, Foydalanuvchilar"),
                List.of("Kassalar", "Nomi/Otdel (ID yoki nomi)/Faol; yangi satr (ID bo'sh) = yangi kassa"),
                List.of("Foydalanuvchilar", "Ism + Rol (kassir/buxgalter/admin) + TelegramID YOKI Telefon + Kassa (ID yoki nomi); yangi satr (ID bo'sh) = yangi foydalanuvchi"),
                List.of("Holat ustuni", "Satr qabul qilinmasa sabab shu ustunda chiqadi — satr O'CHMAYDI, to'ldirsangiz keyingi siklda qabul qilinadi"),
                List.of("Ustuvorlik", "BOT BAZASI — asosiy manba. Jadvaldagi katak faqat SIZ o'zgartirganingizda botga qo'llanadi; bot tomonida qilingan o'zgarishlarni jadval eski nusxasi qaytarib yubormaydi"),
                List.of("Qolgan varaqlar", "Bot tomonidan avtomatik yoziladi — tahrir 5 daqiqada ustidan yozib yuboriladi"),
                List.of("Davr", "Operatsiyalar: oxirgi 60 kun · Kunlar: oxirgi 31 kun")));
    }
}
