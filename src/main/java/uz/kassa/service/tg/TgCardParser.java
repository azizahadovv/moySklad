package uz.kassa.service.tg;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 💳 Karta bildirishnomasi (HUMOCARD kabi) shablonini o'qiydi. Namuna:
 * <pre>
 * 💸 To'lov
 * ➖ 206.000,00 UZS
 * 📍 MULTICARD MUNIS&gt;TOSH
 * 💳 HUMOCARD *1090
 * 🕓 11:42 14.09.2026
 * 💰 13.547.470,90 UZS
 * </pre>
 * Sonlar: <b>.</b> — minglik, <b>,</b> — kasr → tiyin (206.000,00 → 20 600 000). Yo'nalish ➖/➕ (yoki
 * «to'lov/rasxod» ↔ «kirim/pополнение») bilan. Balans (💰) — kartaning joriy qoldig'i.
 */
public final class TgCardParser {

    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("HH:mm dd.MM.yyyy");
    private static final Pattern CARD = Pattern.compile("💳\\s*([^\\n*]*?)\\s*\\*?\\s*(\\d{3,4})");
    private static final Pattern TIME = Pattern.compile("🕓\\s*(\\d{1,2}:\\d{2}\\s+\\d{2}\\.\\d{2}\\.\\d{4})");
    private static final Pattern BAL = Pattern.compile("💰\\s*([\\d.\\u00A0 ]*(?:,\\d{1,2})?)");
    private static final Pattern AMT = Pattern.compile("(➖|➕|-|−|\\+)\\s*(\\d[\\d.\\u00A0 ]*(?:,\\d{1,2})?)\\s*(?:UZS|сўм|су[мн]|so'?m)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MERCH = Pattern.compile("📍\\s*(.+)");

    /** dir: KIRIM/RASXOD/null; amount/balance — tiyin (null — topilmadi); cardName/mask; at. */
    public record Card(String dir, Long amount, Long balance, String cardName, String mask, String merchant, LocalDateTime at) {
        public boolean usable() { return mask != null && (balance != null || amount != null); }
    }

    private TgCardParser() {}

    /** Shablonga o'xshasa (💳 va (💰 yoki summa satri) bor) — Card, aks holda null. */
    public static Card parse(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher card = CARD.matcher(text);
        if (!card.find()) return null;   // karta satri yo'q — bizning shablon emas
        String cardName = card.group(1).trim();
        String mask = card.group(2);

        Long balance = null;
        Matcher b = BAL.matcher(text);
        if (b.find()) balance = toTiyin(b.group(1));

        Long amount = null; String dir = null;
        Matcher a = AMT.matcher(text);
        if (a.find()) {
            String sign = a.group(1);
            amount = toTiyin(a.group(2));
            dir = (sign.equals("➕") || sign.equals("+")) ? "KIRIM" : "RASXOD";
        }
        if (dir == null) dir = dirFromWords(text);

        String merchant = null;
        Matcher me = MERCH.matcher(text);
        if (me.find()) merchant = me.group(1).trim();

        LocalDateTime at = null;
        Matcher t = TIME.matcher(text);
        if (t.find()) try { at = LocalDateTime.parse(t.group(1).replaceAll("\\s+", " ").trim(), TF); } catch (Exception ignored) { }

        return new Card(dir, amount, balance, cardName, mask, merchant, at);
    }

    /** «206.000,00» / «13.547.470,90» → tiyin (long). null — o'qib bo'lmadi. */
    static Long toTiyin(String s) {
        if (s == null) return null;
        String clean = s.replaceAll("[\\s\\u00A0]", "");
        if (clean.isEmpty()) return null;
        long cents;
        int comma = clean.lastIndexOf(',');
        if (comma >= 0) {
            String frac = clean.substring(comma + 1);
            String whole = clean.substring(0, comma).replace(".", "").replaceAll("[^\\d]", "");
            if (frac.length() == 1) frac = frac + "0";
            frac = (frac + "00").substring(0, 2);
            if (whole.isEmpty()) whole = "0";
            try { cents = Long.parseLong(whole) * 100 + Long.parseLong(frac); } catch (NumberFormatException e) { return null; }
        } else {
            String whole = clean.replace(".", "").replaceAll("[^\\d]", "");
            if (whole.isEmpty()) return null;
            try { cents = Long.parseLong(whole) * 100; } catch (NumberFormatException e) { return null; }
        }
        return cents;
    }

    private static String dirFromWords(String text) {
        String low = text.toLowerCase();
        if (low.contains("попол") || low.contains("kirim") || low.contains("кирим") || low.contains("приход") || low.contains("зачисл")) return "KIRIM";
        if (low.contains("to'lov") || low.contains("tolov") || low.contains("тўлов") || low.contains("оплата") || low.contains("списан") || low.contains("rasxod") || low.contains("расход")) return "RASXOD";
        return null;
    }
}
