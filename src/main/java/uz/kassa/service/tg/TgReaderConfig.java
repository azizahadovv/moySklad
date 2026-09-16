package uz.kassa.service.tg;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.kassa.service.SettingsService;
import java.util.ArrayList;
import java.util.List;

/**
 * 📨 Bot xabarlari nazorati sozlamalari (settings, «tgreader.*»). Manba bot va MTProto kaliti .env dan
 * (TG_SOURCE_BOT — TDLib mijozi), tekshiruv qoidalari settings dan.
 */
@Component
@RequiredArgsConstructor
public class TgReaderConfig {

    public static final String ENABLED = "tgreader.enabled";
    public static final String KEYWORDS = "tgreader.keywords";       // OGOH beradigan so'zlar (CSV, kichik harf)
    public static final String SILENCE_H = "tgreader.silence_hours"; // shu soatdan ko'p xabar/heartbeat kelmasa — ogohlantirish
    public static final String MATCH = "tgreader.match";             // 1 — summani bizdagi yozuv bilan solishtirish
    public static final String MATCH_TOL = "tgreader.match_tol";     // solishtirish farqi (so'm)
    public static final String CHAT_IDS = "tgreader.chat_ids";       // ogohlantirish yuboriladigan guruh/kanal (CSV)
    public static final String SILENCE_SENT = "tgreader.silence_sent";
    // 💳 Karta qoldiqlari guruh hisoboti (Click hisoboti uslubida)
    public static final String REPORT_EVERY_H = "tgreader.report_every_h";  // 0 — o'chiq
    public static final String REPORT_FROM = "tgreader.report_from";        // soat 0..23
    public static final String REPORT_TO = "tgreader.report_to";
    public static final String REPORT_SENT = "tgreader.report_sent";        // oxirgi yuborilgan soat belgisi
    // 🔑 TDLib ulanish sozlamalari (bot ichidan kiritiladi; .env — zaxira)
    public static final String API_ID = "tgreader.api_id";
    public static final String API_HASH = "tgreader.api_hash";
    public static final String SOURCE_BOT = "tgreader.source_bot";
    // 📡 Kengaytma (2026-09-16)
    public static final String SOURCES = "tgreader.sources";              // qo'shimcha manbalar (CSV): @bot/@odam, guruh/kanal @username yoki -100… id, «me»
    public static final String MEDIA_OCR = "tgreader.media_ocr";          // 1 — manba rasmlarini OCR qilib matnga qo'shish
    public static final String BALANCE_CMD = "tgreader.balance_cmd";      // asosiy botga yuboriladigan qadamlar («|» bilan); bo'sh — o'chiq
    public static final String BALANCE_BEFORE = "tgreader.balance_before_min"; // Click hisobotidan necha daqiqa oldin so'ralsin
    public static final String SECURITY_AT = "tgreader.security_at";      // oxirgi xavfsizlik tekshiruvi (soat belgisi)

    private final SettingsService settings;

    @Value("${TG_SOURCE_BOT:}") private String sourceBotEnv;
    @Value("${TG_API_ID:0}") private int apiIdEnv;
    @Value("${TG_API_HASH:}") private String apiHashEnv;

    public boolean enabled() { return "1".equals(settings.get(ENABLED).orElse("1").trim()); }
    public void setEnabled(boolean on) { settings.set(ENABLED, on ? "1" : "0"); }

    /** Manba bot (sozlama → .env). */
    public String sourceBot() { return get(SOURCE_BOT).orElse(sourceBotEnv == null ? "" : sourceBotEnv); }
    public void setSourceBot(String v) { set(SOURCE_BOT, v == null ? "" : v.trim().replaceFirst("^@", "")); }
    /** TDLib api_id (sozlama → .env). 0 — yo'q. */
    public int apiId() { try { return Integer.parseInt(get(API_ID).orElse(String.valueOf(apiIdEnv)).trim()); } catch (NumberFormatException e) { return 0; } }
    public String apiHash() { return get(API_HASH).orElse(apiHashEnv == null ? "" : apiHashEnv); }
    public void setApi(int id, String hash) { set(API_ID, String.valueOf(id)); set(API_HASH, hash == null ? "" : hash.trim()); }
    /** TDLib ulanish uchun kalitlar bormi. */
    public boolean hasApi() { return apiId() > 0 && !apiHash().isBlank() && !sourceBot().isBlank(); }

    public List<String> keywords() {
        List<String> out = new ArrayList<>();
        for (String s : settings.get(KEYWORDS).orElse("").split(",")) if (!s.isBlank()) out.add(s.trim().toLowerCase());
        return out;
    }
    public void setKeywords(String csv) { settings.set(KEYWORDS, csv == null ? "" : csv); }

    public int silenceHours() { return intOf(SILENCE_H, 6, 1, 168); }
    public boolean match() { return "1".equals(settings.get(MATCH).orElse("0").trim()); }
    public void toggleMatch() { settings.set(MATCH, match() ? "0" : "1"); }
    public long matchTol() { try { return Long.parseLong(settings.get(MATCH_TOL).orElse("0").trim()); } catch (NumberFormatException e) { return 0; } }

    public List<Long> chatIds() {
        List<Long> out = new ArrayList<>();
        for (String s : settings.get(CHAT_IDS).orElse("").split(",")) if (!s.isBlank()) try { out.add(Long.parseLong(s.trim())); } catch (NumberFormatException ignored) { }
        return out;
    }
    public void toggleChat(long chatId) {
        List<Long> cur = chatIds();
        if (!cur.remove(chatId)) cur.add(chatId);
        settings.set(CHAT_IDS, String.join(",", cur.stream().map(String::valueOf).toList()));
    }

    /** Karta hisoboti chatlari: o'z ro'yxati (tgreader.chat_ids); bo'sh bo'lsa Click guruhi (notify.clickGroupChatId) — «atchot doyimgi kabi». */
    public List<Long> reportChatIds() {
        List<Long> own = chatIds();
        if (!own.isEmpty()) return own;
        List<Long> out = new ArrayList<>();
        for (String p : settings.get("notify.clickGroupChatId").orElse("").split(",")) if (!p.isBlank()) try { out.add(Long.parseLong(p.trim())); } catch (NumberFormatException ignored) { }
        return out;
    }

    public int reportEveryH() { return intOf(REPORT_EVERY_H, 0, 0, 24); }
    public int reportFrom() { return intOf(REPORT_FROM, 8, 0, 23); }
    public int reportTo() { return intOf(REPORT_TO, 22, 0, 23); }

    /** 📡 Manbalar: asosiy bot + qo'shimchalar, normallashgan (@siz), dublikatsiz. FAQAT shu ro'yxat o'qiladi. */
    public List<String> sources() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (!sourceBot().isBlank()) out.add(sourceBot());
        out.addAll(extraSources());
        return new ArrayList<>(out);
    }
    public List<String> extraSources() {
        List<String> out = new ArrayList<>();
        for (String s : settings.get(SOURCES).orElse("").split("[,\\s]+")) { String n = normSource(s); if (!n.isEmpty() && !n.equals(sourceBot()) && !out.contains(n)) out.add(n); }
        return out;
    }
    public void setSources(String csv) {
        settings.set(SOURCES, csv == null ? "" : String.join(",", java.util.Arrays.stream(csv.split("[,\\s]+")).map(TgReaderConfig::normSource).filter(x -> !x.isEmpty()).distinct().toList()));
    }
    static String normSource(String s) {
        String n = s == null ? "" : s.trim();
        if (n.startsWith("https://t.me/")) n = n.substring(13);
        if (n.startsWith("t.me/")) n = n.substring(5);
        if (n.startsWith("@")) n = n.substring(1);
        return n;
    }
    public boolean mediaOcr() { return "1".equals(settings.get(MEDIA_OCR).orElse("0").trim()); }
    public void toggleMediaOcr() { settings.set(MEDIA_OCR, mediaOcr() ? "0" : "1"); }
    /** 💰 Qoldiq so'rovi qadamlari («|» bilan); bo'sh — o'chiq. */
    public List<String> balanceSteps() {
        List<String> out = new ArrayList<>();
        for (String s : settings.get(BALANCE_CMD).orElse("").split("\\|")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }
    public void setBalanceCmd(String v) { settings.set(BALANCE_CMD, v == null ? "" : v.trim()); }
    /** Click hisobotidan necha daqiqa oldin so'ralsin (5 ga karrali, 5..55; 0 — jadval bo'yicha so'ralmaydi). */
    public int balanceBeforeMin() { int v = intOf(BALANCE_BEFORE, 5, 0, 55); return v - v % 5; }

    public java.util.Optional<String> get(String k) { return settings.get(k).filter(v -> !v.isBlank()); }
    public void set(String k, String v) { settings.set(k, v == null ? "" : v); }

    private int intOf(String key, int def, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(settings.get(key).orElse(String.valueOf(def)).trim()))); }
        catch (NumberFormatException e) { return def; }
    }
}
