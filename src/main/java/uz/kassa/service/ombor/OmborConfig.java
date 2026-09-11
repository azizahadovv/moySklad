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
    public static final String HAMKOR_TAG = "ombor.hamkor_tag";       // MoySklad kontragent tegi (guruhi) — hamkor do'konlar
    // B2
    public static final String SALES_DAYS = "ombor.sales_days";       // sotuv tarixi chuqurligi (kun)
    public static final String SALES_CURSOR = "ombor.sales_cursor";   // orqaga yuklash kursori
    public static final String LEAD_DAYS = "ombor.lead_days";         // standart yetkazib berish muddati
    public static final String SAFETY_DAYS = "ombor.xavfsizlik_kun";  // xavfsizlik zaxirasi (kun)
    public static final String COVER_DAYS = "ombor.qoplash_kun";      // qoralama miqdori: necha kunga
    public static final String SOVISH_KUN = "ombor.sovish_kun";       // aksiyadan keyingi sovish davri
    // B5
    public static final String KATTA_SUMMA = "ombor.katta_summa";     // direktor tasdig'i chegarasi (so'm)

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
    public String hamkorTag() { return settings.get(HAMKOR_TAG).orElse("").trim(); }
    public int salesDays() { return intOf(SALES_DAYS, 365, 30, 800); }
    public int leadDays() { return intOf(LEAD_DAYS, 3, 0, 120); }
    public int safetyDays() { return intOf(SAFETY_DAYS, 3, 0, 120); }
    public int coverDays() { return intOf(COVER_DAYS, 14, 1, 365); }
    public int sovishKun() { return intOf(SOVISH_KUN, 14, 1, 120); }
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
