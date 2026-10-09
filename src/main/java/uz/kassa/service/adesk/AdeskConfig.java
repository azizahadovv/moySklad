package uz.kassa.service.adesk;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.kassa.config.AppProps;
import uz.kassa.service.SettingsService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 📒 Adesk integratsiyasi sozlamalari (settings jadvali; standart qiymatlar kodda, docs/ADESK.md §6).
 * Hamma summalar Adesk'ka bazaviy valyutada (UZS) yuboriladi.
 */
@Component
@RequiredArgsConstructor
public class AdeskConfig {

    public static final String ENABLED   = "adesk.enabled";
    public static final String TOKEN     = "adesk.token";
    /** Davr boshi (yyyy-MM-dd) — shu sanadan boshlab hujjatlar o'tkaziladi, boshlang'ich qoldiqlar shu sanaga. */
    public static final String START     = "adesk.start";
    /** Davr oxiri (yyyy-MM-dd); bo'sh — bugungacha (doimiy sinxron). Birinchi bosqich: faqat sentabr. */
    public static final String END       = "adesk.end";
    public static final String CURRENCY  = "adesk.currency";
    /** Ombor boshlang'ich partiyalari qaysi yuridik shaxs balansida (MoySklad organization id). */
    public static final String STOCK_ORG = "adesk.stockOrg";
    public static final String CAT_INCOME   = "adesk.cat.income";
    public static final String CAT_TRANSFER = "adesk.cat.transfer";
    /** Adesk'da qo'lda kiritilgan operatsiyalarni MoySklad'ga yozish (1 — yoqiq). Standart: o'chiq. */
    public static final String REVERSE   = "adesk.reverse";
    /** Hisoblar bog'lash jadvali (JSON {msKey: adeskId, 0 — yangi yaratiladi}) va uning tasdiqlangani (1). */
    /** Adesk «проект» (operatsiyalar shu proyektga yoziladi; nom bo'yicha topiladi). «-» — proyektsiz. */
    public static final String PROJECT = "adesk.project";
    /** Hamma firma bitta (asosiy) yuridik shaxs ostida (standart: ha — Adesk tarifi 3 ta yuridik shaxsga ruxsat beradi). */
    public static final String SINGLE_LE = "adesk.singleLe";
    public static final String ACC_MAP = "adesk.accMap";
    public static final String ACC_CONFIRMED = "adesk.accConfirmed";
    /** Kunlik solishtirish hisoboti vaqti (HH:mm) va chatlari (vergulli; bo'sh — SuperAdmin/buxgalterlarga). */
    public static final String REPORT_TIME  = "adesk.report.time";
    public static final String REPORT_CHATS = "adesk.report.chats";
    public static final String REPORT_SENT  = "adesk.report.sent";
    /** Adesk → MoySklad tezkor tekshiruv oralig'i, daqiqa (faqat Adesk'da qo'lda kiritilganlar; standart 2). */
    public static final String REVERSE_INTERVAL = "adesk.reverseInterval";
    /** Oddiy (inkremental) sinxron oralig'i, daqiqa. */
    public static final String INTERVAL  = "adesk.interval";
    /** Adesk API'ga soniyasiga nechta so'rov. */
    public static final String RPS       = "adesk.rps";
    /** Inkremental kursorlar: adesk.cur.<tur> (bot vaqti, ISO). */
    public static final String CURSOR    = "adesk.cur.";
    /** Oxirgi ishga tushish natijasi (matn) — panelda ko'rsatiladi. */
    public static final String LAST_RUN  = "adesk.lastRun";
    /** Oxirgi solishtirish qisqa natijasi. */
    public static final String LAST_CHECK = "adesk.lastCheck";

    public static final String TRANSFER_PURPOSE = "Перемещение собственных средств";

    private final SettingsService settings;
    private final AppProps props;

    public ZoneId zone() { return props.zoneId(); }

    public LocalDate today() { return LocalDate.now(zone()); }

    public boolean enabled() { return "1".equals(settings.get(ENABLED).orElse("0").trim()); }
    public void setEnabled(boolean on) { settings.set(ENABLED, on ? "1" : "0"); }

    /** Amaldagi token: bot ichidan kiritilgani, bo'lmasa .env dagi ADESK_TOKEN. */
    public String token() {
        String t = settings.get(TOKEN).orElse("").trim();
        if (t.isBlank()) t = props.getAdesk().getToken() == null ? "" : props.getAdesk().getToken().trim();
        return t;
    }
    public boolean hasToken() { return !token().isBlank(); }

    public String baseUrl() {
        String b = props.getAdesk().getBaseUrl();
        b = b == null || b.isBlank() ? "https://api.adesk.ru" : b.trim();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    public LocalDate start() { return dateOf(START, LocalDate.of(2026, 9, 1)); }

    /** Davr oxiri; null — oxiri yo'q (bugungacha). Standart: 30.09.2026 (birinchi bosqich — faqat sentabr). */
    public LocalDate end() {
        Optional<String> v = settings.get(END);
        if (v.isEmpty()) return LocalDate.of(2026, 9, 30);
        String t = v.get().trim();
        if (t.isEmpty() || t.equals("-")) return null;
        try { return LocalDate.parse(t); } catch (Exception e) { return LocalDate.of(2026, 9, 30); }
    }

    /** Hujjatlar olinadigan oxirgi kun: davr oxiri yoki bugun (qaysi biri oldin bo'lsa). */
    public LocalDate effectiveEnd() {
        LocalDate e = end(), t = today();
        return e == null || e.isAfter(t) ? t : e;
    }

    /** Boshlang'ich qoldiq/partiya sanasi — davr boshidan bir kun oldin. */
    public LocalDate openingDate() { return start().minusDays(1); }

    public String currency() { return settings.get(CURRENCY).filter(s -> !s.isBlank()).orElse("UZS").trim().toUpperCase(); }

    public String stockOrg() { return settings.get(STOCK_ORG).orElse("").trim(); }

    public String catIncome() { return cleanCat(settings.get(CAT_INCOME).orElse(""), "Выручка"); }

    public String catTransfer() { return cleanCat(settings.get(CAT_TRANSFER).orElse(""), "Перемещение"); }

    /**
     * Statya nomi harf yoki raqam bilan boshlanadi. Emoji bilan boshlangan qiymat — kiritish paytida bosilgan menyu
     * tugmasi (2026-10-06: «📒 Adesk» kirim statyasi bo'lib saqlangan edi) — e'tiborsiz, standart nom ishlatiladi.
     */
    static String cleanCat(String v, String def) {
        String t = v == null ? "" : v.trim();
        return t.isEmpty() || !Character.isLetterOrDigit(t.codePointAt(0)) ? def : t;
    }

    public boolean reverse() { return "1".equals(settings.get(REVERSE).orElse("0").trim()); }

    /** SuperAdmin «🔗 Hisoblarni bog'lash» ekranida bog'lanishni tasdiqlaganmi (birinchi sinxron shundan keyin). */
    public String project() {
        String v = settings.get(PROJECT).orElse("").trim();
        return v.isEmpty() ? "Asosiy" : v;
    }

    public boolean singleLe() { return !"0".equals(settings.get(SINGLE_LE).orElse("1").trim()); }

    public boolean accountsConfirmed() { return "1".equals(settings.get(ACC_CONFIRMED).orElse("0").trim()); }
    public void setReverse(boolean on) { settings.set(REVERSE, on ? "1" : "0"); }

    public LocalTime reportTime() {
        try { return LocalTime.parse(settings.get(REPORT_TIME).orElse("21:00").trim()); }
        catch (Exception e) { return LocalTime.of(21, 0); }
    }

    public List<Long> reportChats() {
        List<Long> out = new ArrayList<>();
        for (String p : settings.get(REPORT_CHATS).orElse("").split(",")) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            try { long v = Long.parseLong(t); if (!out.contains(v)) out.add(v); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    public int intervalMin() { return (int) longOf(INTERVAL, 10, 2, 240); }

    public int reverseIntervalMin() { return (int) longOf(REVERSE_INTERVAL, 2, 1, 60); }

    public int rps() { return (int) longOf(RPS, 4, 1, 20); }

    public Optional<String> get(String key) { return settings.get(key).filter(v -> !v.isBlank()); }

    public void set(String key, String value) { settings.set(key, value == null ? "" : value); }

    private LocalDate dateOf(String key, LocalDate def) {
        try { return settings.get(key).filter(s -> !s.isBlank()).map(s -> LocalDate.parse(s.trim())).orElse(def); }
        catch (Exception e) { return def; }
    }

    private long longOf(String key, long def, long min, long max) {
        try {
            long v = Long.parseLong(settings.get(key).orElse(String.valueOf(def)).trim().replaceAll("\\D", ""));
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) { return def; }
    }
}
