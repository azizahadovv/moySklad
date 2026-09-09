package uz.kassa.service.control;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.kassa.config.AppProps;
import uz.kassa.service.SettingsService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 🕵️ Nazorat sozlamalari (settings jadvali, «control.*» kalitlari).
 * Standartlar — docs/KONTRAGENT-NAZORAT.md §6: 2 soat kutish, 20 daqiqa balans tekshiruvi,
 * 09:00 kunlik jamlama, 24 soat eskalatsiya, eski qarzlar 2026-01-01 dan.
 */
@Component
@RequiredArgsConstructor
public class ControlConfig {

    public static final String ENABLED = "control.enabled";
    public static final String SINCE = "control.since";
    public static final String GRACE_MIN = "control.grace_min";
    public static final String CHECK_MIN = "control.check_min";
    public static final String DAILY_TIME = "control.daily_time";
    public static final String ESCALATE_H = "control.escalate_hours";   // eski (ishlatilmaydi)
    /** 1-bosqich: xodimga xabardan necha daqiqadan keyin otdel rahbariga (standart 35). */
    public static final String ESC1_MIN = "control.escalate1_min";
    /** 2-bosqich: necha daqiqadan keyin «tuzatilmadi» — admin + rahbar (standart 60). */
    public static final String ESC2_MIN = "control.escalate2_min";
    public static final String RECIPIENTS = "control.recipients";
    public static final String RULES_OFF = "control.rules_off";
    public static final String LAST_AGENT_SYNC = "control.last_agent_sync";
    public static final String LAST_DEMAND_SYNC = "control.last_demand_sync";
    public static final String AGENTS_INDEXED = "control.agents_indexed";
    public static final String AGENTS_SINCE = "control.agents_since";
    public static final String LOADED_SINCE = "control.loaded_since";
    public static final String DAILY_SENT = "control.daily_sent";
    public static final String AGENT_DAILY_SENT = "control.agent_daily_sent";

    public static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final List<String> ALL_RULES = List.of("K1", "K2", "K3", "K4", "K5", "K6", "K7", "K8");
    /** Otgruzka kamchiliklari (qarzdagi otgruzkalar uchun). */
    public static final List<String> SHIP_RULES = List.of("O1", "O2", "O3", "O4", "O5");
    public static final String SHIP_RULES_OFF = "control.ship_rules_off";
    public static final String ISSUES_BACKFILLED = "control.issues_backfilled";
    /** Jim statuslar: qarz ro'yxatiga tushadi, lekin XABAR YO'Q (bank orqali to'lanadigan byudjet tashkilotlari). */
    public static final String QUIET_STATES = "control.quiet_states";
    /** Qarzdor eslatmalari: muddatdan necha kun OLDIN (CSV, "3,1"), muddatdan KEYIN har necha kunda takror (0 — yo'q), vaqti. */
    public static final String REMIND_BEFORE = "control.remind_before";
    public static final String REMIND_REPEAT = "control.remind_repeat_days";
    public static final String REMIND_TIME = "control.remind_time";
    public static final String REMIND_SENT = "control.remind_sent";
    public static final String QUIET_DEFAULT = "Перечисление,Карз перечисление";

    private final SettingsService settings;
    private final AppProps props;

    public ZoneId zone() { return props.zoneId(); }

    public boolean enabled() { return !"0".equals(settings.get(ENABLED).orElse("1").trim()); }

    public void setEnabled(boolean on) { settings.set(ENABLED, on ? "1" : "0"); }

    /** Eski qarzlar yuklanadigan boshlanish sanasi (hujjat sanasi bo'yicha). */
    public LocalDate since() {
        try { return LocalDate.parse(settings.get(SINCE).orElse("2026-01-01").trim()); }
        catch (Exception e) { return LocalDate.of(2026, 1, 1); }
    }

    public int graceMin() { return intOf(GRACE_MIN, 120, 5, 24 * 60 * 30); }
    public int checkMin() { return intOf(CHECK_MIN, 20, 2, 24 * 60); }
    public int esc1Min() { return intOf(ESC1_MIN, 35, 1, 24 * 60 * 30); }
    public int esc2Min() { return Math.max(esc1Min() + 1, intOf(ESC2_MIN, 60, 1, 24 * 60 * 30)); }

    /** Muddatdan necha kun oldin eslatiladi (muddat kuni har doim). Bo'sh — faqat muddat kuni. */
    public Set<Integer> remindBefore() {
        Set<Integer> out = new java.util.TreeSet<>();
        for (String p : settings.get(REMIND_BEFORE).orElse("3,1").split("[,;\\s]+"))
            if (!p.isBlank()) try { int v = Integer.parseInt(p.trim()); if (v > 0 && v <= 90) out.add(v); } catch (NumberFormatException ignored) { }
        return out;
    }

    /** Muddat o'tgach har necha kunda takror eslatiladi; 0 — takrorlanmaydi. */
    public int remindRepeatDays() { return intOf(REMIND_REPEAT, 3, 0, 90); }

    public LocalTime remindTime() {
        try { return LocalTime.parse(settings.get(REMIND_TIME).orElse("10:00").trim()); }
        catch (Exception e) { return LocalTime.of(10, 0); }
    }

    public LocalTime dailyTime() {
        try { return LocalTime.parse(settings.get(DAILY_TIME).orElse("09:00").trim()); }
        catch (Exception e) { return LocalTime.of(9, 0); }
    }

    /** SuperAdmin belgilagan qo'shimcha oluvchilar (users.id). */
    public Set<Long> recipientIds() {
        Set<Long> out = new LinkedHashSet<>();
        for (String p : settings.get(RECIPIENTS).orElse("").split(","))
            if (!p.isBlank()) try { out.add(Long.parseLong(p.trim())); } catch (NumberFormatException ignored) { }
        return out;
    }

    public void toggleRecipient(long userId) {
        Set<Long> ids = recipientIds();
        if (!ids.remove(userId)) ids.add(userId);
        settings.set(RECIPIENTS, String.join(",", ids.stream().map(String::valueOf).toList()));
    }

    /** O'chirilgan qoidalar (standart: K7). */
    public Set<String> rulesOff() {
        Set<String> out = new LinkedHashSet<>();
        for (String p : settings.get(RULES_OFF).orElse("K7").split(","))
            if (!p.isBlank()) out.add(p.trim().toUpperCase());
        return out;
    }

    /** O'chirilgan otgruzka qoidalari (standart: O2 Масъул, O4 izoh — deyarli hamma joyda bo'sh). */
    public Set<String> shipRulesOff() {
        Set<String> out = new LinkedHashSet<>();
        for (String p : settings.get(SHIP_RULES_OFF).orElse("O2,O4").split(","))
            if (!p.isBlank()) out.add(p.trim().toUpperCase());
        return out;
    }

    public boolean ruleOn(String code) {
        return code.startsWith("O") ? !shipRulesOff().contains(code) : !rulesOff().contains(code);
    }

    public void toggleRule(String code) {
        boolean ship = code.startsWith("O");
        Set<String> off = ship ? shipRulesOff() : rulesOff();
        if (!off.remove(code)) off.add(code);
        settings.set(ship ? SHIP_RULES_OFF : RULES_OFF, String.join(",", off));
    }

    /** Jim statuslar (normallashgan: kichik harf, ў→у). */
    public Set<String> quietStates() {
        Set<String> out = new LinkedHashSet<>();
        for (String p : settings.get(QUIET_STATES).orElse(QUIET_DEFAULT).split(","))
            if (!p.isBlank()) out.add(uz.kassa.service.moysklad.MoySkladClient.normAttr(p));
        return out;
    }

    public boolean isQuietState(String state) {
        return state != null && !state.isBlank() && quietStates().contains(uz.kassa.service.moysklad.MoySkladClient.normAttr(state));
    }

    /** Statusni jim ro'yxatiga qo'shish/olib tashlash (asl nomi saqlanadi). */
    public void toggleQuietState(String state) {
        String key = uz.kassa.service.moysklad.MoySkladClient.normAttr(state);
        java.util.LinkedHashMap<String, String> cur = new java.util.LinkedHashMap<>();
        for (String p : settings.get(QUIET_STATES).orElse(QUIET_DEFAULT).split(","))
            if (!p.isBlank()) cur.put(uz.kassa.service.moysklad.MoySkladClient.normAttr(p), p.trim());
        if (cur.remove(key) == null) cur.put(key, state.trim());
        settings.set(QUIET_STATES, String.join(",", cur.values()));
    }

    public Optional<LocalDateTime> lastAgentSync() { return dtOf(LAST_AGENT_SYNC); }
    public Optional<LocalDateTime> lastDemandSync() { return dtOf(LAST_DEMAND_SYNC); }
    public Optional<LocalDateTime> agentsSince() { return dtOf(AGENTS_SINCE); }

    public Optional<String> get(String key) { return settings.get(key).filter(v -> !v.isBlank()); }
    public void set(String key, String value) { settings.set(key, value == null ? "" : value); }
    public void set(String key, LocalDateTime v) { settings.set(key, v.format(FMT)); }

    private Optional<LocalDateTime> dtOf(String key) {
        return settings.get(key).filter(v -> !v.isBlank()).map(v -> {
            try { return LocalDateTime.parse(v.trim(), FMT); } catch (Exception e) { return null; }
        });
    }

    private int intOf(String key, int def, int min, int max) {
        try {
            int v = Integer.parseInt(settings.get(key).orElse(String.valueOf(def)).trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) { return def; }
    }

    public static String ruleTitle(String code) {
        return switch (code) {
            case "K1" -> "Наименование to'g'ri (≥3 belgi, faqat raqam emas)";
            case "K2" -> "Группы — kamida bittasi tanlangan";
            case "K3" -> "Телефон — +998 formatida";
            case "K4" -> "ИНН — Юр. лицо / ИП / ташкилот uchun";
            case "K5" -> "Dublikat (telefon yoki nom) — xato";
            case "K6" -> "Полное наименование + Юр. адрес (Юр. лицо)";
            case "K7" -> "«ким орқали келди» / «Рекламный канал» (клиент)";
            case "K8" -> "Guruh ↔ Тип контрагента mosligi";
            case "O1" -> "«Тўлов муддати» kiritilmagan (qarzdagi otgruzka)";
            case "O2" -> "«Масъул» tanlanmagan";
            case "O3" -> "Status (Накд/Клик/Карз…) qo'yilmagan";
            case "O4" -> "Komentariya yo'q";
            case "O5" -> "Kontragent telefoni yo'q";
            default -> code;
        };
    }
}
