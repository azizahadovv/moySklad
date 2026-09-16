package uz.kassa.service.jarima;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.kassa.config.AppProps;
import uz.kassa.domain.Jarima;
import uz.kassa.service.SettingsService;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ⚖️ Жарималар sozlamalari (settings jadvali, kod default beradi; docs/JARIMA.md §4).
 * Hamma summalar SO'MDA.
 */
@Component
@RequiredArgsConstructor
public class JarimaConfig {

    public static final String ENABLED    = "jarima.enabled";
    /** Kontragent xatosi uchun hisoblash asosi (bazaviy summa), so'm. */
    public static final String BAZAVIY    = "jarima.bazaviy";
    public static final String FOIZ_KARTA = "jarima.foiz_karta";
    public static final String FOIZ_KG    = "jarima.foiz_kg";
    public static final String FOIZ_OT    = "jarima.foiz_ot";
    /** Nechta holat ogohlantirish bilan o'tadi (standart 1 — birinchisi). */
    public static final String OGOH_SONI  = "jarima.ogoh_soni";
    /** Kontragent/otgruzka xatosi qachon jarima: TUZATILMADI (admin eskalatsiyasida) yoki TOPILDI (darhol). */
    public static final String XATO_PAYT  = "jarima.xato_payt";
    /** Kunlik jamlama vaqti (HH:mm). */
    public static final String KUN_VAQT   = "jarima.kun_vaqt";
    /** Kunlik jamlama yuborilgan sana (guard). */
    public static final String KUN_SENT   = "jarima.kun_sent";
    /** Click hisobot guruhlari — KARTA jarimalari kunlik jamlamasi shu chatlarga (Jobs.CLICK_GROUP_KEY bilan bir kalit). */
    public static final String GROUP_KEY  = "notify.clickGroupChatId";

    public static final String PAYT_TUZATILMADI = "TUZATILMADI", PAYT_TOPILDI = "TOPILDI";

    private final SettingsService settings;
    private final AppProps props;

    public ZoneId zone() { return props.zoneId(); }

    public boolean enabled() { return !"0".equals(settings.get(ENABLED).orElse("1").trim()); }
    public void setEnabled(boolean on) { settings.set(ENABLED, on ? "1" : "0"); }

    public long bazaviy() { return longOf(BAZAVIY, 1_000_000L, 0, 1_000_000_000_000L); }

    public double foiz(Jarima.Tur tur) {
        String key = switch (tur) { case KARTA -> FOIZ_KARTA; case KONTRAGENT -> FOIZ_KG; case OTGRUZKA -> FOIZ_OT; };
        try {
            double v = Double.parseDouble(settings.get(key).orElse("1").trim().replace(',', '.'));
            return Math.max(0, Math.min(100, v));
        } catch (NumberFormatException e) { return 1; }
    }

    public int ogohSoni() { return (int) longOf(OGOH_SONI, 1, 0, 10); }

    public boolean paytTuzatilmadi() { return !PAYT_TOPILDI.equals(settings.get(XATO_PAYT).orElse(PAYT_TUZATILMADI).trim()); }
    public void setPaytTuzatilmadi(boolean v) { settings.set(XATO_PAYT, v ? PAYT_TUZATILMADI : PAYT_TOPILDI); }

    public LocalTime kunVaqt() {
        try { return LocalTime.parse(settings.get(KUN_VAQT).orElse("21:30").trim()); }
        catch (Exception e) { return LocalTime.of(21, 30); }
    }

    public Optional<String> get(String key) { return settings.get(key).filter(v -> !v.isBlank()); }
    public void set(String key, String value) { settings.set(key, value == null ? "" : value); }

    /** Click hisobot guruh/kanallari (vergulli ro'yxat). */
    public List<Long> groupChatIds() {
        List<Long> out = new ArrayList<>();
        for (String p : settings.get(GROUP_KEY).orElse("").split(",")) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            try { long v = Long.parseLong(t); if (!out.contains(v)) out.add(v); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** Foizni ko'rsatish: 1 → "1", 0.5 → "0.5". */
    public static String foizText(double f) {
        return f == Math.rint(f) ? String.valueOf((long) f) : String.valueOf(f);
    }

    private long longOf(String key, long def, long min, long max) {
        try {
            long v = Long.parseLong(settings.get(key).orElse(String.valueOf(def)).trim().replaceAll("\\D", ""));
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) { return def; }
    }
}
