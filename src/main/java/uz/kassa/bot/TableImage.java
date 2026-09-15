package uz.kassa.bot;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Generik PNG jadval renderer (push-hisobotlar uchun: qarzdorlar, kontragent xatolari va h.k.).
 * Telegram matnida ustunlar proportsional shrift bilan tekislanmaydi — shuning uchun ro'yxatlar
 * rasm qilib chiziladi (+ to'liq ma'lumot alohida Excel'da). Uslub {@code DailyReportService}dagi
 * "Kunlik kassa solishtirish" jadvali bilan bir xil ("Ledger" — maroon/rose), lekin ustunlar va
 * kengliklar har hisobot uchun {@link Spec} orqali beriladi, qo'lda piksel hisoblanmaydi.
 */
public final class TableImage {

    private TableImage() { }

    public enum Align { LEFT, RIGHT }

    /** Bitta ustun: sarlavha, tekislash, avtomatik hisoblanadigan kenglik shu oraliqda ([min, max]). */
    public record Col(String title, Align align, int minPx, int maxPx) {
        public static Col of(String title) { return new Col(title, Align.LEFT, 70, 340); }
        public static Col right(String title) { return new Col(title, Align.RIGHT, 70, 200); }
    }

    /**
     * Bitta qator. Oddiy qator — {@code cells.length == cols.length}, {@code colors} — har katakcha
     * rangi (null — hammasi standart). Guruh sarlavhasi ({@code groupHeader=true}) — bitta
     * {@code cells[0]}, jadval TO'LIQ kengligida band sifatida chiziladi (masalan otdel/xodim nomi).
     */
    public record Row(String[] cells, Color[] colors, boolean groupHeader) {
        public static Row of(String... cells) { return new Row(cells, null, false); }
        public static Row colored(Color[] colors, String... cells) { return new Row(cells, colors, false); }
        public static Row group(String label) { return new Row(new String[]{label}, null, true); }
    }

    /** Butun jadval. {@code accent} — sarlavha barining rangi (null — standart maroon). */
    public record Spec(String title, Color accent, Col[] cols, List<Row> rows, String footer) {
        public Spec(String title, Col[] cols, List<Row> rows, String footer) { this(title, null, cols, rows, footer); }
    }

    private static final Color DEF_ACCENT = new Color(0x7B, 0x1D, 0x2E);
    private static final Color BG = Color.WHITE;
    private static final Color HEAD_BG = new Color(0xF4, 0xDF, 0xE3);
    private static final Color LINE = new Color(0xE6, 0xCC, 0xD2);
    private static final Color ALT = new Color(0xFB, 0xF3, 0xF5);
    private static final Color GROUP_BG = new Color(0xEE, 0xE3, 0xE0);
    private static final Color GREY = new Color(0x55, 0x55, 0x55);
    /** Qatorlarda holat rangi (chaqiruvchi servis tanlaydi): qizil — ogohlantirish, yashil — yaxshi. */
    public static final Color WARN = new Color(0xB0, 0x1E, 0x1E);
    public static final Color OK = new Color(0x1E, 0x7B, 0x34);

    private static final int PAD = 24, TITLE_H = 64, HEAD_H = 56, ROW_H = 46, GROUP_H = 38, CELL_PAD = 12;

    public static byte[] render(Spec spec) {
        System.setProperty("java.awt.headless", "true");
        Font fb = font(Font.BOLD, 22), fh = font(Font.BOLD, 16), fr = font(Font.PLAIN, 17),
                fg = font(Font.BOLD, 15), fs = font(Font.PLAIN, 14);

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(fh);
        int[] w = new int[spec.cols().length];
        for (int i = 0; i < spec.cols().length; i++) {
            Col c = spec.cols()[i];
            int need = maxLineWidth(pg, c.title()) + CELL_PAD * 2;
            pg.setFont(fr);
            for (Row r : spec.rows()) {
                if (r.groupHeader() || i >= r.cells().length) continue;
                need = Math.max(need, pg.getFontMetrics().stringWidth(nullToEmpty(r.cells()[i])) + CELL_PAD * 2);
            }
            pg.setFont(fh);
            w[i] = Math.max(c.minPx(), Math.min(c.maxPx(), need));
        }
        pg.dispose();

        int tableW = 0; for (int cw : w) tableW += cw;
        int width = PAD * 2 + tableW;
        int footerLines = spec.footer() == null || spec.footer().isBlank() ? 0 : spec.footer().split("\n").length;
        int rowsH = 0;
        for (Row r : spec.rows()) rowsH += r.groupHeader() ? GROUP_H : ROW_H;
        int height = PAD * 2 + TITLE_H + 12 + HEAD_H + Math.max(ROW_H, rowsH)
                + (footerLines > 0 ? 14 + footerLines * 18 : 0);

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(BG); g.fillRect(0, 0, width, height);

        Color accent = spec.accent() == null ? DEF_ACCENT : spec.accent();
        g.setColor(accent); g.fillRoundRect(PAD, PAD, width - PAD * 2, TITLE_H, 10, 10);
        g.setColor(Color.WHITE); g.setFont(fb);
        g.drawString(spec.title(), PAD + 18, PAD + 41);

        int y = PAD + TITLE_H + 12;
        g.setColor(HEAD_BG); g.fillRect(PAD, y, width - PAD * 2, HEAD_H);
        g.setColor(accent); g.setFont(fh);
        int x = PAD;
        for (int i = 0; i < spec.cols().length; i++) {
            drawCell(g, spec.cols()[i].title(), x, y, w[i], HEAD_H, spec.cols()[i].align(), y + HEAD_H / 2 + 6);
            x += w[i];
        }
        y += HEAD_H;

        int dataIdx = 0;
        for (Row r : spec.rows()) {
            if (r.groupHeader()) {
                g.setColor(GROUP_BG); g.fillRect(PAD, y, width - PAD * 2, GROUP_H);
                g.setColor(accent); g.setFont(fg);
                g.drawString(clip(g, nullToEmpty(r.cells().length > 0 ? r.cells()[0] : ""), width - PAD * 2 - CELL_PAD * 2),
                        PAD + CELL_PAD, y + GROUP_H - 12);
                y += GROUP_H;
                continue;
            }
            if (dataIdx % 2 == 0) { g.setColor(ALT); g.fillRect(PAD, y, width - PAD * 2, ROW_H); }
            g.setColor(LINE); g.drawLine(PAD, y, width - PAD, y);
            x = PAD;
            for (int i = 0; i < spec.cols().length; i++) {
                String v = i < r.cells().length ? nullToEmpty(r.cells()[i]) : "";
                g.setColor(r.colors() != null && i < r.colors().length && r.colors()[i] != null ? r.colors()[i] : Color.DARK_GRAY);
                g.setFont(fr);
                drawCell(g, v, x, y, w[i], ROW_H, spec.cols()[i].align(), y + ROW_H / 2 + 6);
                x += w[i];
            }
            y += ROW_H;
            dataIdx++;
        }
        g.setColor(LINE); g.drawLine(PAD, y, width - PAD, y);

        if (footerLines > 0) {
            g.setColor(GREY); g.setFont(fs);
            int fy = y + 20;
            for (String line : spec.footer().split("\n")) { g.drawString(line, PAD, fy); fy += 18; }
        }
        g.dispose();
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void drawCell(Graphics2D g, String text, int cellX, int cellY, int cellW, int cellH, Align align, int baselineY) {
        String v = clip(g, text, cellW - CELL_PAD * 2);
        int tw = g.getFontMetrics().stringWidth(v);
        int tx = align == Align.RIGHT ? cellX + cellW - CELL_PAD - tw : cellX + CELL_PAD;
        g.drawString(v, tx, baselineY);
    }

    private static int maxLineWidth(Graphics2D g, String s) {
        int w = 0;
        for (String line : s.split("\n")) w = Math.max(w, g.getFontMetrics().stringWidth(line));
        return w;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    public static Font font(int style, int size) {
        for (String n : new String[]{"DejaVu Sans", "Liberation Sans", "Arial", "SansSerif"}) {
            Font f = new Font(n, style, size);
            if (!f.getFamily().equalsIgnoreCase("Dialog") || n.equals("SansSerif")) return f;
        }
        return new Font("SansSerif", style, size);
    }

    public static String clip(Graphics2D g, String s, int maxW) {
        if (s == null) return "";
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && fm.stringWidth(t + "…") > maxW) t = t.substring(0, t.length() - 1);
        return t + "…";
    }
}
