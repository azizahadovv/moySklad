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
 * SHEETS → BOT: Kassalar, Foydalanuvchilar, Shablon varaqlaridagi operator tahrirlarini bazaga qo'llash.
 * (SheetsSyncService dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SheetsPullService {

    private final GoogleSheetsClient gs;
    private final KassaRepo kassaRepo;
    private final AppUserRepo userRepo;
    private final MoySkladClient msClient;
    private final GuestRepo guestRepo;
    private final NotifyRepo notifyRepo;

    /** Qayta ishlanmagan (chala) satrlar — push paytida SAQLAB qolinadi, o'chirilmaydi. */
    volatile List<List<Object>> pendingUsers = List.of();
    volatile List<List<Object>> pendingKassas = List.of();
    volatile boolean usersPullOk = false, kassaPullOk = false;
    private final SheetsState st;


    /** Kassalar varag'i: [ID, Nomi, Otdel ID, Otdel nomi, Faol]. Chala satrlar saqlanadi. */
    void pullKassalar() {
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
                String id = st.cell(r, 0), nomi = st.cell(r, 1), otdel = st.cell(r, 2), otdelNomi = st.cell(r, 3);
                boolean faol = st.bool(st.cell(r, 4), true);
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
                    if (!newGroup.isBlank() && st.groupTaken(newGroup, null)) {
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
                String kSnap = st.kassaSnap.get(Long.parseLong(id));
                if (kSnap == null) continue;
                String[] kv = kSnap.split("\\|", -1);   // [nomi, groupId, faol]
                if (kv.length < 3) continue;
                final String gFinal = groupId;
                final String nomiN = st.norm(nomi);
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
                        if (st.groupTaken(gFinal, k.getId()))
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
    void pullUsers() {
        List<List<Object>> pending = new ArrayList<>();
        usersPullOk = false;
        try {
            List<List<String>> rows = gs.get("Foydalanuvchilar!A2:I300");
            for (List<String> r : rows) {
                if (r.stream().allMatch(c -> c == null || c.isBlank())) continue;
                String id = st.cell(r, 0), tg = st.cell(r, 1), tel = st.cell(r, 2), ism = st.cell(r, 3),
                        rolS = st.cell(r, 4), kassaIdS = st.cell(r, 5), kassaNomi = st.cell(r, 6);
                boolean faol = st.bool(st.cell(r, 7), true);

                if (id.isBlank()) {
                    Role role = st.parseRole(rolS);
                    Long tgId = null;
                    if (!tg.isBlank())
                        try { tgId = Long.parseLong(tg.replaceAll("\\D", "")); }
                        catch (NumberFormatException ignored) { }
                    if (tgId == null) tgId = st.guestByPhone(tel);
                    Kassa kassa = st.resolveKassa(kassaIdS, kassaNomi);

                    String xato = null;
                    if (ism.isBlank()) xato = "⚠️ Ism yozing";
                    else if (role == null) xato = "⚠️ Rol: KASSIR / BUXGALTER / SUPERADMIN";
                    else if (role == Role.SUPERADMIN)
                            // G2 himoyasi: jadvalga yozish huquqi tizim adminligiga
                            // aylanmasin — SUPERADMIN faqat bot ichidan tayinlanadi.
                            xato = "⚠️ SUPERADMIN faqat bot ichidan tayinlanadi";
                    else if (tgId != null && userRepo.findByTelegramId(tgId).isPresent())
                            xato = "⚠️ Bu TelegramID allaqachon tizimda";
                    else if (role == Role.KASSIR && kassa == null)
                            xato = "⚠️ KassaID yoki KassaNomi kiriting";
                    else if (!tel.isBlank() && userRepo.findAll().stream().anyMatch(
                            uX -> uX.getPhone() != null
                                    && uz.kassa.bot.TextUtil.phoneEq(uX.getPhone(), tel)))
                            xato = "⚠️ Bu telefon boshqa foydalanuvchida bor";

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
                String snap = st.userSnap.get(uid);
                String tgN = st.digits(tg), telN = st.digits(tel), ismN = st.norm(ism);
                Role role = st.parseRole(rolS);
                Kassa kassa = st.resolveKassa(kassaIdS, kassaNomi);
                String faolN = faol ? "TRUE" : "FALSE";

                if (snap == null) {
                    userRepo.findById(uid).ifPresent(x -> {
                        if (x.getTelegramId() == null) {
                            Long link = st.guestByPhone(!telN.isEmpty() ? telN : x.getPhone());
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
                        Long link = st.guestByPhone(!telN.isEmpty() ? telN : x.getPhone());
                        if (link != null && userRepo.findByTelegramId(link).isEmpty()) {
                            x.setTelegramId(link); ch = true;
                            log.info("Sheets: {} Telegram bilan bog'landi ({})", x.getFullName(), link);
                        }
                    }
                    // ROL — operator o'zgartirgan bo'lsa (oxirgi SuperAdmin va
                    // asosiy/yaratuvchi SuperAdmin himoyalangan)
                    if (role != null && !role.name().equals(pv[3]) && role != x.getRole()) {
                        if (role == Role.SUPERADMIN) {
                            // G2 himoyasi: Sheets orqali SUPERADMIN'ga KO'TARISH TAQIQ —
                            // jadval tahriri tizim adminligiga aylanmasin. Faqat bot ichidan.
                            log.warn("Sheets: {} ni SUPERADMIN qilishga urinish RAD etildi",
                                    x.getFullName());
                        } else if (!(st.isCreatorRow(x) && role != Role.SUPERADMIN)
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
                        if (!(st.isCreatorRow(x) && !faol)
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


    /** Shablon varag'i: [ID, Nomi, Kimga, Jadval, HaftaKunlari, AvtoOchirish, Faol, Shablon, OxirgiYuborilgan, Xato]. */
    void pullShablon() {
        try {
            List<List<String>> rows = gs.get("Shablon!A2:L200");
            for (List<String> r : rows) {
                if (r.stream().allMatch(c -> c == null || c.isBlank())) continue;
                String id = st.cell(r, 0), nomi = st.cell(r, 1), kimga = st.cell(r, 2), jadval = st.cell(r, 3),
                        kunlar = st.cell(r, 4), avto = st.cell(r, 5), faolS = st.cell(r, 6);
                String shablon = r.size() > 7 && r.get(7) != null ? r.get(7).trim() : "";
                // 🔘 menyu tugmasi: matn yaroqsiz bo'lsa (mavjud tugma bilan bir xil va h.k.) — tugmasiz
                String tugmaRaw = st.cell(r, 10);
                final String tugma = uz.kassa.service.notify.NotifyService.buttonLabelProblem(tugmaRaw) != null
                        ? "" : tugmaRaw.trim();
                String tugmaRol = uz.kassa.service.notify.NotifyService.parseButtonRolesText(st.cell(r, 11));
                if (id.isBlank()) {
                    if (nomi.isBlank()) continue;
                    if (notifyRepo.findAll().stream().anyMatch(n -> n.getName().equalsIgnoreCase(nomi))) continue;
                    Notify n = Notify.builder().name(nomi).template(shablon).buttonLabel(tugma).buttonRoles(tugmaRol)
                            .recipients(uz.kassa.service.notify.NotifyService.parseRecipientsText(kimga))
                            .schedule(uz.kassa.service.notify.NotifyService.parseScheduleText(jadval))
                            .weekdays(uz.kassa.service.notify.NotifyService.parseWeekdaysText(kunlar))
                            .autoDeleteMin(SheetsState.parseIntSafe(avto, 0))
                            .active(st.bool(faolS, true)).build();
                    notifyRepo.save(n);
                    log.info("Sheets: yangi bildirishnoma yaratildi — {}", nomi);
                    continue;
                }
                long nid;
                try { nid = Long.parseLong(id); } catch (NumberFormatException e) { continue; }
                String snap = st.notifySnap.get(nid);
                if (snap == null) continue;   // bot yozmagan satr — DB'ga tegilmaydi
                String h = SheetsState.notifyHash(nomi, kimga, jadval, kunlar, avto, faolS.toUpperCase(), shablon, tugma, tugmaRol);
                if (h.equals(snap)) continue;   // operator tahrirlamagan
                notifyRepo.findById(nid).ifPresent(n -> {
                    if (!nomi.isBlank()) n.setName(nomi);
                    n.setRecipients(uz.kassa.service.notify.NotifyService.parseRecipientsText(kimga));
                    n.setSchedule(uz.kassa.service.notify.NotifyService.parseScheduleText(jadval));
                    n.setWeekdays(uz.kassa.service.notify.NotifyService.parseWeekdaysText(kunlar));
                    n.setAutoDeleteMin(SheetsState.parseIntSafe(avto, n.getAutoDeleteMin()));
                    n.setActive(st.bool(faolS, n.isActive()));
                    n.setTemplate(shablon);
                    n.setButtonLabel(tugma);
                    n.setButtonRoles(tugmaRol);
                    notifyRepo.save(n);
                    log.info("Sheets: bildirishnoma #{} yangilandi (operator tahriri)", nid);
                });
            }
        } catch (Exception e) {
            log.warn("Sheets Shablon o'qish: {}", e.getMessage());
        }
    }

}
