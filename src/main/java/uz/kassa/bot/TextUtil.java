package uz.kassa.bot;

import uz.kassa.domain.MoneyType;

public final class TextUtil {
    private TextUtil() {}

    private static final java.util.regex.Pattern HTML_TAG = java.util.regex.Pattern.compile("<(/?)([a-zA-Z]+)(\\s[^>]*)?>");

    /**
     * Telegram 4096 belgidan uzun xabarni qabul qilmaydi (2026-11-04: ⚖️ kunlik hisobot shu sabab har kuni yetmay qolardi).
     * Matn qator chegaralarida bo'linadi; ochiq qolgan teg bo'lak oxirida yopilib keyingisida qayta ochiladi, entity o'rtasidan kesilmaydi.
     */
    public static java.util.List<String> splitHtml(String text, int limit) {
        if (text.length() <= limit) return java.util.List.of(text);
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.Deque<String[]> open = new java.util.ArrayDeque<>();   // {nom, to'liq ochuvchi teg}, oxirgisi boshida
        StringBuilder cur = new StringBuilder();
        boolean hasText = false;
        for (String line : text.split("\n", -1)) {
            String rest = line;
            boolean first = true;
            while (true) {
                java.util.Deque<String[]> after = new java.util.ArrayDeque<>(open);
                track(rest, after);
                int need = (hasText && first ? 1 : 0) + rest.length() + closers(after).length();
                if (cur.length() + need <= limit) {
                    if (hasText && first) cur.append('\n');
                    cur.append(rest);
                    open = after;
                    hasText = true;
                    break;
                }
                if (hasText) {   // joriy bo'lakni yopamiz, shu qator keyingisida
                    out.add(cur + closers(open));
                    cur = new StringBuilder(openers(open));
                    hasText = false;
                    first = false;
                    continue;
                }
                // bitta qatorning o'zi sig'maydi — xavfsiz joydan kesiladi
                int reserve = closers(open).length() + (rest.indexOf('<') >= 0 ? 24 : 0);
                int cut = safeCut(rest, Math.max(1, limit - cur.length() - reserve));
                String piece = rest.substring(0, cut);
                track(piece, open);
                out.add(cur + piece + closers(open));
                cur = new StringBuilder(openers(open));
                rest = rest.substring(cut);
                first = false;
            }
        }
        if (hasText) out.add(cur + closers(open));
        return out;
    }

    private static void track(String s, java.util.Deque<String[]> open) {
        var m = HTML_TAG.matcher(s);
        while (m.find()) {
            String name = m.group(2).toLowerCase();
            if (m.group(1).isEmpty()) open.addFirst(new String[]{name, m.group()});
            else if (!open.isEmpty() && open.peekFirst()[0].equals(name)) open.removeFirst();
        }
    }

    private static String closers(java.util.Deque<String[]> open) {
        StringBuilder sb = new StringBuilder();
        for (String[] t : open) sb.append("</").append(t[0]).append('>');
        return sb.toString();
    }

    private static String openers(java.util.Deque<String[]> open) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Iterator<String[]> it = open.descendingIterator(); it.hasNext(); ) sb.append(it.next()[1]);
        return sb.toString();
    }

    /** max dan oshmaydigan kesish joyi: teg yoki «&amp;» o'rtasi emas. */
    private static int safeCut(String s, int max) {
        int cut = Math.min(max, s.length());
        int amp = s.lastIndexOf('&', cut - 1);
        if (amp >= 0 && amp > cut - 10 && s.indexOf(';', amp) >= cut) cut = amp;
        int lt = s.lastIndexOf('<', cut - 1);
        if (lt >= 0 && s.indexOf('>', lt) >= cut) cut = lt;
        return cut > 0 ? cut : Math.min(max, s.length());
    }

    /** Telegram photo caption limiti (1024) ga moslab kesish. */
    public static String capCaption(String s) {
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    /** 1234567 -> "1 234 567" */
    public static String fmt(long v) {
        boolean neg = v < 0;
        String s = String.valueOf(Math.abs(v));
        StringBuilder b = new StringBuilder();
        int c = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            b.append(s.charAt(i));
            if (++c % 3 == 0 && i > 0) b.append(' ');
        }
        if (neg) b.append('-');
        return b.reverse().toString();
    }

    /** "1 200 000" / "1.200.000" / "1200000" -> 1200000; xato bo'lsa -1.
     *  KASR RAD ETILADI: «1.5» ilgari jimgina 15 bo'lib ketardi (10x xato xavfi).
     *  Oxiri [.,] + 1-2 raqam — kasr deb qaraladi; 3 raqamli dum (1.200.000) —
     *  minglik ajratkich, ruxsat. */
    /** Tiyindagi summa: «12 235.45»; tiyin nol bo'lsa «12 235». */
    public static String fmtTiyin(long tiyin) {
        long a = Math.abs(tiyin);
        String s = fmt(a / 100);
        if (a % 100 != 0) s += "." + String.format("%02d", a % 100);
        return (tiyin < 0 ? "-" : "") + s;
    }

    /** «12235.45», «12 235,45», «12235» → tiyin; noto'g'ri bo'lsa -1. */
    public static long parseAmountTiyin(String t) {
        if (t == null) return -1;
        String s = t.trim().replace("\u00A0", " ");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.*?)(?:[.,](\\d{1,2}))?$").matcher(s);
        if (!m.matches()) return -1;
        String whole = m.group(1).replaceAll("[^0-9]", "");
        if (whole.isEmpty() || whole.length() > 13) return -1;
        String frac = m.group(2) == null ? "0" : (m.group(2).length() == 1 ? m.group(2) + "0" : m.group(2));
        try { return Long.parseLong(whole) * 100 + Long.parseLong(frac); }
        catch (NumberFormatException e) { return -1; }
    }

    public static long parseAmount(String t) {
        if (t == null) return -1;
        String s = t.trim();
        if (s.matches(".*[.,]\\d{1,2}$")) return -1;   // kasr ko'rinishi — qabul qilinmaydi
        String d = s.replaceAll("[^0-9]", "");
        if (d.isEmpty() || d.length() > 15) return -1;
        try { return Long.parseLong(d); } catch (NumberFormatException e) { return -1; }
    }

    /**
     * Telefonni kanonik ko'rinishga keltirish: faqat raqamlar, 9 xonali mahalliy
     * raqamga 998 qo'shiladi. To'liq raqam chiqmasa — bo'sh qator (taxminiy
     * suffiks-moslashtirish TAQIQLANGAN — begona odam akkauntga ulanib qolmasin).
     */
    public static String normPhone(String t) {
        if (t == null) return "";
        String d = t.replaceAll("\\D", "");
        if (d.length() == 9) d = "998" + d;
        return d.length() >= 10 && d.length() <= 15 ? d : "";
    }

    /** Ikki telefon AYNAN bir xilmi (to'liq, normallashgan holda). */
    public static boolean phoneEq(String a, String b) {
        String na = normPhone(a), nb = normPhone(b);
        return !na.isEmpty() && na.equals(nb);
    }

    public static String mtLabel(MoneyType mt) {
        return switch (mt) {
            case NAQD -> "💵 Naqd";
            case KLIK -> "📲 Click";
            case TERMINAL -> "💳 Terminal";
        };
    }

    /** HTML parse_mode uchun minimal ekranlash. */
    public static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
