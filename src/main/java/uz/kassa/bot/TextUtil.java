package uz.kassa.bot;

import uz.kassa.domain.MoneyType;

public final class TextUtil {
    private TextUtil() {}

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
