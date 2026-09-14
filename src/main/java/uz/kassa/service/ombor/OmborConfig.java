package uz.kassa.service.ombor;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.kassa.config.AppProps;
import uz.kassa.service.SettingsService;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 🏬 Ombor sozlamalari (settings, «ombor.*»). Rollar do'kon kesimida:
 * ombor.role.ZAVSKLAD.<kassaId> = users.id CSV (kassaId 0 — hamma do'kon).
 */
@Component
@RequiredArgsConstructor
public class OmborConfig {

    public static final String ENABLED = "ombor.enabled";
    public static final String STOCK_TIME = "ombor.stock_time";       // tunlik qoldiq vaqti (HH:mm)
    public static final String IGNORE_DAYS = "ombor.ignore_days";     // «e'tiborsiz» qayta ochilmaydigan kunlar
    public static final String DAILY_TIME = "ombor.daily_time";       // kunlik jamlama
    public static final String DAILY_SENT = "ombor.daily_sent";
    public static final String STOCK_DONE = "ombor.stock_done";       // oxirgi qoldiq kuni (yyyy-MM-dd)
    public static final String HISOBOT_IDS = "ombor.hisobot_ids";     // hisobot oluvchilar (users.id)
    // B1
    public static final String DOCS_DAYS = "ombor.docs_days";         // hujjatlar necha kun orqadan (birinchi yuklash va tekshiruv oynasi)
    public static final String SANOQ_MAJBURIY = "ombor.sanoq_majburiy"; // 1 — sanoq tasdiqlanmaguncha kun yopilmaydi
    public static final String ABC_A_DAYS = "ombor.abc_a_days", ABC_B_DAYS = "ombor.abc_b_days", ABC_C_DAYS = "ombor.abc_c_days";
    // B2
    public static final String SALES_DAYS = "ombor.sales_days";       // sotuv tarixi chuqurligi (kun)
    public static final String SALES_CURSOR = "ombor.sales_cursor";   // orqaga yuklash kursori
    public static final String LEAD_DAYS = "ombor.lead_days";         // standart yetkazib berish muddati
    public static final String SAFETY_DAYS = "ombor.xavfsizlik_kun";  // xavfsizlik zaxirasi (kun)
    public static final String COVER_DAYS = "ombor.qoplash_kun";      // qoralama miqdori: necha kunga
    public static final String SOVISH_KUN = "ombor.sovish_kun";       // aksiyadan keyingi sovish davri
    // B5
    public static final String KATTA_SUMMA = "ombor.katta_summa";     // direktor tasdig'i chegarasi (so'm)
    // Kunlik sanoq (2026-09-12): 30 kunlik top havzadan tasodifiy N ta, ertalab so'rov, kun yakuni rahbarga, haftalik Excel admin'ga
    public static final String SANOQ_SONI = "ombor.sanoq_soni";               // kuniga nechta tovar (standart 15)
    public static final String SANOQ_HAVZA = "ombor.sanoq_havza";             // 30 kunlik eng ko'p sotilgan top-N havza (standart 60)
    public static final String SANOQ_ESKI = "ombor.sanoq_eski";               // kunlik sonining nechtasi 🐢 eski (30 kunda sotilmagan, qoldig'i bor) tovar (standart 3)
    public static final String SANOQ_VAQT = "ombor.sanoq_vaqt";               // ertalabki so'rov vaqti (09:00)
    public static final String SANOQ_YOPISH_VAQT = "ombor.sanoq_yopish_vaqt"; // kun yakuni — tasdiq + rahbarga (20:00)
    public static final String SANOQ_HAFTA_KUN = "ombor.sanoq_hafta_kun";     // haftalik Excel kuni 1..7 (1 — dushanba)
    public static final String SANOQ_HAFTA_VAQT = "ombor.sanoq_hafta_vaqt";   // haftalik Excel vaqti (09:00)
    public static final String SANOQ_SOROV_SENT = "ombor.sanoq_sorov_sent", SANOQ_YOPISH_SENT = "ombor.sanoq_yopish_sent", SANOQ_HAFTA_SENT = "ombor.sanoq_hafta_sent";
    public static final String[] HAFTA_KUNLARI = {"Dushanba", "Seshanba", "Chorshanba", "Payshanba", "Juma", "Shanba", "Yakshanba"};
    // 🏆 Chempionlar (2026-09-12): do'kon kesimida 30 kunlik top-N, javonda bor % (fill rate), ertalabki xabar
    public static final String CHEMPION_SONI = "ombor.chempion_soni";   // top-N (standart 20)
    public static final String CHEMPION_VAQT = "ombor.chempion_vaqt";   // ertalabki xabar vaqti (08:30)
    public static final String CHEMPION_SENT = "ombor.chempion_sent";
    // 💵 Naqd sotuv tahlili (2026-09-14): SOTUV_* faqat shu statusdagi otgruzkalardan, hujjat summasi chegaradan oshmasa
    public static final String NAQD_STATUSLAR = "ombor.naqd_statuslar";     // CSV, standart: Накд,Карта,Клик
    public static final String SOTUV_MAX_HUJJAT = "ombor.sotuv_max_hujjat"; // so'm; undan katta bitta hujjat tahlilga kirmaydi (0 — chegarasiz)
    public static final String NAQD_REBUILT = "ombor.naqd_rebuilt";         // otgruzka tarixi yuklangach bir marta to'liq qayta hisob (yyyy-MM-dd)
    public static final String NAQD_DEFAULT = "Накд,Карта,Клик";

    public static final List<String> ROLES = List.of("ZAVSKLAD", "ZAKUPSHIK", "DIREKTOR");
    public static final List<String> SEVERITIES = List.of("INFO", "OGOH", "MUHIM");
    public static final List<String> TO_ROLES = List.of("XODIM", "ZAVSKLAD", "ZAKUPSHIK", "RAHBAR", "DIREKTOR", "ADMIN");

    private final SettingsService settings;
    private final AppProps props;

    public ZoneId zone() { return props.zoneId(); }
    public boolean enabled() { return !"0".equals(settings.get(ENABLED).orElse("1").trim()); }
    public void setEnabled(boolean on) { settings.set(ENABLED, on ? "1" : "0"); }

    public LocalTime stockTime() { return timeOf(STOCK_TIME, LocalTime.of(0, 30)); }
    public LocalTime dailyTime() { return timeOf(DAILY_TIME, LocalTime.of(9, 0)); }
    public int ignoreDays() { return intOf(IGNORE_DAYS, 30, 1, 365); }
    public int docsDays() { return intOf(DOCS_DAYS, 60, 7, 400); }
    public boolean sanoqMajburiy() { return "1".equals(settings.get(SANOQ_MAJBURIY).orElse("0").trim()); }
    public int abcDays(char cls) {
        return switch (cls) { case 'A' -> intOf(ABC_A_DAYS, 10, 1, 365); case 'B' -> intOf(ABC_B_DAYS, 30, 1, 365); default -> intOf(ABC_C_DAYS, 90, 1, 365); };
    }
    public int salesDays() { return intOf(SALES_DAYS, 365, 30, 800); }
    public int leadDays() { return intOf(LEAD_DAYS, 3, 0, 120); }
    public int safetyDays() { return intOf(SAFETY_DAYS, 3, 0, 120); }
    public int coverDays() { return intOf(COVER_DAYS, 14, 1, 365); }
    public int sovishKun() { return intOf(SOVISH_KUN, 14, 1, 120); }
    public int chempionSoni() { return intOf(CHEMPION_SONI, 20, 5, 100); }
    public LocalTime chempionVaqt() { return timeOf(CHEMPION_VAQT, LocalTime.of(8, 30)); }
    public int sanoqSoni() { return intOf(SANOQ_SONI, 15, 1, 200); }
    public int sanoqHavza() { return intOf(SANOQ_HAVZA, 60, 5, 1000); }
    public int sanoqEski() { return intOf(SANOQ_ESKI, 3, 0, 100); }
    public LocalTime sanoqVaqt() { return timeOf(SANOQ_VAQT, LocalTime.of(9, 0)); }
    public LocalTime sanoqYopishVaqt() { return timeOf(SANOQ_YOPISH_VAQT, LocalTime.of(20, 0)); }
    public int sanoqHaftaKun() { return intOf(SANOQ_HAFTA_KUN, 1, 1, 7); }
    public LocalTime sanoqHaftaVaqt() { return timeOf(SANOQ_HAFTA_VAQT, LocalTime.of(9, 0)); }
    /** Naqd hisoblanadigan otgruzka statuslari (MoySklad state nomi, kichik harfda solishtiriladi). */
    public Set<String> naqdStates() {
        Set<String> out = new LinkedHashSet<>();
        for (String p : get(NAQD_STATUSLAR).orElse(NAQD_DEFAULT).split(",")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }
    public boolean isNaqd(String state) {
        if (state == null) return false;
        for (String s : naqdStates()) if (s.equalsIgnoreCase(state.trim())) return true;
        return false;
    }
    public void toggleNaqdState(String state) {
        Set<String> cur = naqdStates();
        if (!cur.removeIf(x -> x.equalsIgnoreCase(state))) cur.add(state.trim());
        set(NAQD_STATUSLAR, String.join(",", cur));
    }
    /** Bitta otgruzka summasi chegarasi (so'm); 0 — chegara yo'q. */
    public long sotuvMaxHujjat() { try { return Math.max(0, Long.parseLong(get(SOTUV_MAX_HUJJAT).orElse("30000000").trim())); } catch (NumberFormatException e) { return 30_000_000L; } }

    public long kattaSumma() { try { return Long.parseLong(settings.get(KATTA_SUMMA).orElse("50000000").trim()); } catch (NumberFormatException e) { return 50_000_000L; } }

    /** Rol → foydalanuvchilar (do'kon kesimida + umumiy). */
    public Set<Long> roleUsers(String role, Long kassaId) {
        Set<Long> out = new LinkedHashSet<>();
        if (kassaId != null) out.addAll(ids(settings.get(roleKey(role, kassaId)).orElse("")));
        out.addAll(ids(settings.get(roleKey(role, 0L)).orElse("")));
        return out;
    }

    public Set<Long> roleUsersExact(String role, long kassaId) {
        return new LinkedHashSet<>(ids(settings.get(roleKey(role, kassaId)).orElse("")));
    }

    public void toggleRoleUser(String role, long kassaId, long userId) {
        Set<Long> cur = roleUsersExact(role, kassaId);
        if (!cur.remove(userId)) cur.add(userId);
        settings.set(roleKey(role, kassaId), String.join(",", cur.stream().map(String::valueOf).toList()));
    }

    /** Foydalanuvchi qaysi ombor rollarida (istalgan do'kon). */
    public boolean hasRole(long userId, String role) {
        if (roleUsersExact(role, 0).contains(userId)) return true;
        return roleAnyKassa(userId, role);
    }

    private boolean roleAnyKassa(long userId, String role) {
        // settings jadvalida kalitlar «ombor.role.ROLE.N» — N ni bilmaymiz; SettingsService faqat get beradi,
        // shuning uchun 1..50 oralig'i tekshiriladi (kassalar soni kichik).
        for (long k = 1; k <= 50; k++) if (roleUsersExact(role, k).contains(userId)) return true;
        return false;
    }

    public Set<Long> hisobotIds() { return new LinkedHashSet<>(ids(settings.get(HISOBOT_IDS).orElse(""))); }
    public void toggleHisobot(long userId) {
        Set<Long> cur = hisobotIds();
        if (!cur.remove(userId)) cur.add(userId);
        settings.set(HISOBOT_IDS, String.join(",", cur.stream().map(String::valueOf).toList()));
    }

    public static String roleKey(String role, long kassaId) { return "ombor.role." + role + "." + kassaId; }

    public java.util.Optional<String> get(String key) { return settings.get(key).filter(v -> !v.isBlank()); }
    public void set(String key, String value) { settings.set(key, value == null ? "" : value); }

    private static Set<Long> ids(String csv) {
        Set<Long> out = new LinkedHashSet<>();
        for (String p : csv.split(",")) if (!p.isBlank()) try { out.add(Long.parseLong(p.trim())); } catch (NumberFormatException ignored) { }
        return out;
    }

    private LocalTime timeOf(String key, LocalTime def) {
        try { return LocalTime.parse(settings.get(key).orElse(def.toString()).trim()); } catch (Exception e) { return def; }
    }

    private int intOf(String key, int def, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(settings.get(key).orElse(String.valueOf(def)).trim()))); }
        catch (NumberFormatException e) { return def; }
    }

    public static String roleTitle(String r) {
        return switch (r) {
            case "ZAVSKLAD" -> "Завсклад (kladovchi)";
            case "ZAKUPSHIK" -> "Закупщик (xaridor)";
            case "DIREKTOR" -> "Директор";
            case "RAHBAR" -> "Otdel rahbari";
            case "XODIM" -> "Hujjat egasi (xodim)";
            case "ADMIN" -> "SuperAdmin";
            default -> r;
        };
    }
}
