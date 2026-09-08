package uz.kassa.service.control;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.bot.TextUtil;
import uz.kassa.domain.AppUser;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.service.moysklad.MoySkladClient;
import java.util.*;

/**
 * MoySklad xodimi ↔ bot foydalanuvchisi bog'lanishi.
 * Tartib: users.ms_employee_id → users.ms_uid → telefon (normPhone) → ism (token mosligi,
 * kirill/lotin farqisiz). Topilsa bog'lanish saqlanadi (keyingi safar to'g'ridan-to'g'ri).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeLinkService {

    private final AppUserRepo userRepo;
    private final MoySkladClient msClient;
    private final uz.kassa.repo.KassaRepo kassaRepo;
    private final uz.kassa.repo.KassaHeadRepo headRepo;
    private final uz.kassa.service.AuditService audit;

    private volatile List<MoySkladClient.MsEmployeeFull> empCache = List.of();
    private volatile long empCacheAt = 0;

    /** MoySklad xodimlari (10 daqiqa kesh). */
    public List<MoySkladClient.MsEmployeeFull> employees() {
        if (System.currentTimeMillis() - empCacheAt > 600_000L || empCache.isEmpty()) {
            List<MoySkladClient.MsEmployeeFull> l = msClient.fetchEmployeesFull();
            if (!l.isEmpty()) { empCache = l; empCacheAt = System.currentTimeMillis(); }
        }
        return empCache;
    }

    public Optional<MoySkladClient.MsEmployeeFull> employeeByUid(String uid) {
        if (uid == null || uid.isBlank()) return Optional.empty();
        return employees().stream().filter(e -> uid.equalsIgnoreCase(e.uid())).findFirst();
    }

    public Optional<MoySkladClient.MsEmployeeFull> employeeById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return employees().stream().filter(e -> id.equals(e.id())).findFirst();
    }

    /** Login (audit uid) bo'yicha: xodim ro'yxatidan telefon/nomini olib, resolve. */
    public Optional<AppUser> byUid(String uid) {
        if (uid == null || uid.isBlank()) return Optional.empty();
        Optional<AppUser> direct = userRepo.findFirstByMsUidAndActiveTrue(uid);
        if (direct.isPresent()) return direct;
        Optional<MoySkladClient.MsEmployeeFull> e = employeeByUid(uid);
        return e.map(x -> resolve(x.id(), x.uid(), x.name(), x.phone()).orElse(null))
                .or(() -> resolve("", uid, "", ""));
    }

    /**
     * MoySklad xodimi → bot foydalanuvchisi. employeeId/uid bo'yicha aniq; bo'lmasa telefon,
     * so'ng ism. Topilsa users.ms_employee_id / ms_uid to'ldiriladi.
     */
    public Optional<AppUser> resolve(String employeeId, String uid, String name, String phone) {
        if (notBlank(employeeId)) {
            Optional<AppUser> o = userRepo.findFirstByMsEmployeeIdAndActiveTrue(employeeId);
            if (o.isPresent()) return o;
        }
        if (notBlank(uid)) {
            Optional<AppUser> o = userRepo.findFirstByMsUidAndActiveTrue(uid);
            if (o.isPresent()) { link(o.get(), employeeId, uid); return o; }
        }
        // telefon/nom xodim ro'yxatidan to'ldiriladi (faqat id/uid berilgan bo'lsa)
        if (!notBlank(phone) && !notBlank(name)) {
            Optional<MoySkladClient.MsEmployeeFull> e = notBlank(employeeId) ? employeeById(employeeId)
                    : employeeByUid(uid);
            if (e.isPresent()) { phone = e.get().phone(); name = e.get().name(); }
        }
        List<AppUser> all = userRepo.findByActiveTrueOrderByRoleAscIdAsc();
        if (notBlank(phone)) {
            String np = TextUtil.normPhone(phone);
            if (!np.isEmpty())
                for (AppUser u : all)
                    if (TextUtil.phoneEq(u.getPhone(), np)) { link(u, employeeId, uid); return Optional.of(u); }
        }
        if (notBlank(name)) {
            AppUser best = null;
            int bestScore = 0;
            for (AppUser u : all) {
                int sc = nameScore(name, u.getFullName());
                if (sc > bestScore) { bestScore = sc; best = u; }
            }
            if (best != null && bestScore >= 2) { link(best, employeeId, uid); return Optional.of(best); }
        }
        return Optional.empty();
    }

    /** «Масъул» maydoni (ism) → bot foydalanuvchisi (bog'lanish saqlanmaydi). */
    public Optional<AppUser> byName(String name) {
        if (!notBlank(name)) return Optional.empty();
        AppUser best = null;
        int bestScore = 0;
        for (AppUser u : userRepo.findByActiveTrueOrderByRoleAscIdAsc()) {
            int sc = nameScore(name, u.getFullName());
            if (sc > bestScore) { bestScore = sc; best = u; }
        }
        return bestScore >= 2 ? Optional.ofNullable(best) : Optional.empty();
    }

    /** Qo'lda/avtomatik bog'lash — bo'sh bo'lmagan qiymatlar yoziladi. */
    public void link(AppUser u, String employeeId, String uid) {
        boolean ch = false;
        if (notBlank(employeeId) && !employeeId.equals(u.getMsEmployeeId())) { u.setMsEmployeeId(employeeId); ch = true; }
        if (notBlank(uid) && !uid.equals(u.getMsUid())) { u.setMsUid(uid); ch = true; }
        if (ch) userRepo.save(u);
    }

    public void unlink(AppUser u) {
        u.setMsEmployeeId(null);
        u.setMsUid(null);
        userRepo.save(u);
    }

    /* ---------------- 🔄 MoySklad bilan sinxron: otdel, yangi xodim, rahbar ---------------- */

    public record SyncResult(int linked, int created, int kassaSet, int headsSet, List<String> notes) {}

    /** Lavozim/nomda rahbar belgisi: РОП, рахбар/raxbar, руководитель, начальник, бошлиқ. */
    public static boolean isHead(MoySkladClient.MsEmployeeFull e) {
        String t = ((e.position() == null ? "" : e.position()) + " " + e.name()).toLowerCase();
        return t.contains("роп") || t.contains("рахбар") || t.contains("raxbar") || t.contains("rahbar")
                || t.contains("руковод") || t.contains("начальник") || t.contains("бошлиқ") || t.contains("boshliq");
    }

    /** «110 Ахадов Азизбек», «112 Зулхумор 01,06,2026», «103 Зуфар Сохибов РОП» → toza ism. */
    public static String cleanName(String raw) {
        String s = raw == null ? "" : raw.trim();
        s = s.replaceFirst("^\\d{2,4}\\s+", "");
        s = s.replaceAll("\\b\\d{2},\\d{2},\\d{4}\\b", "");
        s = s.replaceAll("(?iu)\\b(РОП|Сотув|Сотрудник|Директор|Бухгалтер|Брокер|Склад)\\b", "");
        s = s.replaceAll("\\s{2,}", " ").trim();
        return s.isEmpty() ? raw.trim() : s;
    }

    /**
     * MoySklad xodimlari → bot: (1) mavjud foydalanuvchini bog'laydi (uid/telefon/ism), (2) KASSIR'larning
     * otdelini MoySklad otdeliga (kassa.moysklad_group_id) ko'ra qo'yadi, (3) botda yo'q xodimni yaratadi
     * (KASSIR, Telegram ulanmagan — kontakt yuborgach ulanadi), (4) РОП/рахбар lavozimlilarni otdel rahbari qiladi.
     * SuperAdmin/buxgalter otdeli o'zgartirilmaydi. Faol bo'lmagan kassaga biriktirilmaydi.
     */
    public SyncResult syncEmployees(boolean createMissing, Long actorId) {
        Map<String, Long> groupToKassa = new HashMap<>();
        for (uz.kassa.domain.Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            if (k.getMoyskladGroupId() != null && !k.getMoyskladGroupId().isBlank()) groupToKassa.put(k.getMoyskladGroupId(), k.getId());
        int linked = 0, created = 0, kassaSet = 0, heads = 0;
        List<String> notes = new ArrayList<>();
        for (MoySkladClient.MsEmployeeFull e : employees()) {
            if (e.archived() || e.name().isBlank()) continue;
            Long kassaId = groupToKassa.get(e.groupId());
            Optional<AppUser> ou = resolve(e.id(), e.uid(), e.name(), e.phone());
            AppUser u;
            if (ou.isPresent()) {
                u = ou.get();
                if (u.getMsEmployeeId() == null || !u.getMsEmployeeId().equals(e.id())) { link(u, e.id(), e.uid()); linked++; }
            } else if (createMissing) {
                u = userRepo.save(AppUser.builder().fullName(cleanName(e.name())).phone(e.phone())
                        .role(uz.kassa.domain.Role.KASSIR).kassaId(kassaId).active(true)
                        .msEmployeeId(e.id()).msUid(e.uid()).build());
                created++;
                notes.add("➕ " + u.getFullName() + (kassaId == null ? "" : " → " + kassaName(kassaId)));
                audit.log(actorId, "XODIM_AVTO_YARATILDI", "user", u.getId(), e.name() + " · " + e.groupName());
            } else continue;
            if (u.getRole() == uz.kassa.domain.Role.KASSIR && kassaId != null && !kassaId.equals(u.getKassaId())) {
                String old = u.getKassaId() == null ? "—" : kassaName(u.getKassaId());
                u.setKassaId(kassaId);
                userRepo.save(u);
                kassaSet++;
                notes.add("🏪 " + u.getFullName() + ": " + old + " → " + kassaName(kassaId));
                audit.log(actorId, "OTDEL_AVTO", "user", u.getId(), old + " -> " + kassaName(kassaId) + " (MoySklad: " + e.groupName() + ")");
            }
            if (isHead(e) && kassaId != null && headRepo.findByKassaIdAndUserId(kassaId, u.getId()).isEmpty()) {
                headRepo.save(uz.kassa.domain.KassaHead.builder().kassaId(kassaId).userId(u.getId()).build());
                heads++;
                notes.add("👔 " + u.getFullName() + " — " + kassaName(kassaId) + " rahbari");
                audit.log(actorId, "OTDEL_RAHBAR_AVTO", "kassa", kassaId, u.getFullName() + " (" + e.position() + ")");
            }
        }
        if (linked + created + kassaSet + heads > 0)
            log.info("Xodimlar sinxroni: bog'landi {}, yaratildi {}, otdel {}, rahbar {}", linked, created, kassaSet, heads);
        return new SyncResult(linked, created, kassaSet, heads, notes);
    }

    private String kassaName(Long id) {
        return kassaRepo.findById(id).map(uz.kassa.domain.Kassa::getName).orElse("#" + id);
    }

    /** MoySklad otdeli (group UUID) → faol kassa. */
    private Map<String, Long> groupToKassa() {
        Map<String, Long> m = new HashMap<>();
        for (uz.kassa.domain.Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            if (k.getMoyskladGroupId() != null && !k.getMoyskladGroupId().isBlank()) m.put(k.getMoyskladGroupId(), k.getId());
        return m;
    }

    /** Bitta foydalanuvchiga MoySklad otdeli (KASSIR) va rahbarligi (РОП) qo'llanadi. true — o'zgardi. */
    public boolean applyDepartment(AppUser u, MoySkladClient.MsEmployeeFull e, Long actorId) {
        Long kassaId = groupToKassa().get(e.groupId());
        boolean changed = false;
        if (u.getRole() == uz.kassa.domain.Role.KASSIR && kassaId != null && !kassaId.equals(u.getKassaId())) {
            String old = u.getKassaId() == null ? "—" : kassaName(u.getKassaId());
            u.setKassaId(kassaId);
            userRepo.save(u);
            audit.log(actorId, "OTDEL_AVTO", "user", u.getId(), old + " -> " + kassaName(kassaId) + " (MoySklad: " + e.groupName() + ")");
            changed = true;
        }
        if (isHead(e) && kassaId != null && headRepo.findByKassaIdAndUserId(kassaId, u.getId()).isEmpty()) {
            headRepo.save(uz.kassa.domain.KassaHead.builder().kassaId(kassaId).userId(u.getId()).build());
            audit.log(actorId, "OTDEL_RAHBAR_AVTO", "kassa", kassaId, u.getFullName() + " (" + e.position() + ")");
            changed = true;
        }
        return changed;
    }

    /** Foydalanuvchiga bog'langan MoySklad xodimi bo'yicha otdel/rahbarlik (bog'lanmagan bo'lsa — hech narsa). */
    public void applyDepartment(AppUser u, Long actorId) {
        Optional<MoySkladClient.MsEmployeeFull> e = employeeById(u.getMsEmployeeId());
        if (e.isEmpty()) e = employeeByUid(u.getMsUid());
        e.ifPresent(x -> applyDepartment(u, x, actorId));
    }

    /**
     * Kontakt yuborgan odam MoySklad xodimlari ro'yxatida bormi (telefon AYNAN mos)?
     * Bor bo'lsa — bot foydalanuvchisi (mavjud yoki yangi KASSIR, MoySklad otdeli, РОП → rahbar).
     * Faolsizlantirilgan foydalanuvchi ham qaytariladi (chaqiruvchi tekshiradi).
     */
    public Optional<AppUser> registerByPhone(String phone, Long actorId) {
        String np = TextUtil.normPhone(phone);
        if (np.isEmpty()) return Optional.empty();
        for (MoySkladClient.MsEmployeeFull e : employees()) {
            if (e.archived() || !TextUtil.phoneEq(e.phone(), np)) continue;
            AppUser u = resolve(e.id(), e.uid(), e.name(), e.phone()).orElse(null);
            if (u == null)
                for (AppUser any : userRepo.findAll())   // faolsizlantirilgan bo'lsa — dublikat yaratmaymiz
                    if (!any.isActive() && (TextUtil.phoneEq(any.getPhone(), np)
                            || e.id().equals(any.getMsEmployeeId()) || (e.uid() != null && e.uid().equalsIgnoreCase(any.getMsUid()))))
                        return Optional.of(any);
            if (u == null) {
                u = userRepo.save(AppUser.builder().fullName(cleanName(e.name())).phone(e.phone())
                        .role(uz.kassa.domain.Role.KASSIR).kassaId(groupToKassa().get(e.groupId())).active(true)
                        .msEmployeeId(e.id()).msUid(e.uid()).build());
                audit.log(actorId, "XODIM_AVTO_YARATILDI", "user", u.getId(), e.name() + " · " + e.groupName() + " (kontakt)");
            }
            applyDepartment(u, e, actorId);
            return Optional.of(u);
        }
        return Optional.empty();
    }


    /* ---------------- ism solishtirish ---------------- */

    /**
     * Mos tokenlar soni (familiya+ism = 2). Kirill → lotin, raqam/lavozim so'zlari tashlanadi,
     * «y» va qo'sh harflar tekislanadi (Хайруллаев = Xayrullayev). Bir tomonda faqat familiya
     * bo'lsa («114 Рахмонов О.») — 5+ harfli familiya mosligi yetarli.
     */
    public static int nameScore(String a, String b) {
        Set<String> ta = tokens(a), tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0;
        int n = 0;
        String matched = "";
        for (String t : ta) if (tb.contains(t)) { n++; matched = t; }
        if (n == 1 && (ta.size() == 1 || tb.size() == 1) && matched.length() >= 5) return 2;
        // bitta so'zli ismlar (masalan «Али») — ikkalasi ham bitta bo'lsa va teng bo'lsa
        if (n == 1 && ta.size() == 1 && tb.size() == 1) return 2;
        return n;
    }

    private static final Set<String> STOP = Set.of("sotrudnik", "sotuv", "direktor", "rop", "sklad",
            "apa", "aka", "склад", "сотрудник", "директор");

    static Set<String> tokens(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null) return out;
        for (String p : translit(s).toLowerCase().split("[^a-z]+")) {
            if (p.length() < 3 || STOP.contains(p)) continue;
            out.add(fold(p));
        }
        return out;
    }

    private static final String CYR = "абвгдеёжзийклмнопрстуфхцчшщъыьэюяўқғҳ";
    private static final String[] LAT = {"a","b","v","g","d","e","yo","j","z","i","y","k","l","m","n","o","p","r","s","t",
            "u","f","x","ts","ch","sh","sh","","i","","e","yu","ya","o","q","g","x"};

    /** Kirill → lotin (taqqoslash uchun); h/kh → x, apostroflar tashlanadi. */
    public static String translit(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase().toCharArray()) {
            int i = CYR.indexOf(c);
            if (i >= 0) sb.append(LAT[i]);
            else if (c == '\'' || c == '’' || c == '`' || c == 'ʻ') { /* tashlab yuboriladi */ }
            else sb.append(c);
        }
        return sb.toString().replace("kh", "x").replace("h", "x");
    }

    /** Imlo farqlarini tekislash: «y» tashlanadi (Xayrullayev/Xayrullaev), qo'sh harflar bittaga. */
    static String fold(String t) {
        StringBuilder sb = new StringBuilder();
        char prev = 0;
        for (char c : t.toCharArray()) {
            if (c == 'y') continue;
            if (c == prev) continue;
            sb.append(c);
            prev = c;
        }
        return sb.toString();
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
