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

    /** "1 200 000" / "1.200.000" / "1200000" -> 1200000; xato bo'lsa -1. */
    public static long parseAmount(String t) {
        if (t == null) return -1;
        String d = t.replaceAll("[^0-9]", "");
        if (d.isEmpty() || d.length() > 15) return -1;
        try { return Long.parseLong(d); } catch (NumberFormatException e) { return -1; }
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
