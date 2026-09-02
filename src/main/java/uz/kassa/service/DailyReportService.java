package uz.kassa.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import uz.kassa.bot.Keyboards;
import uz.kassa.bot.Sender;
import uz.kassa.bot.TextUtil;
import uz.kassa.domain.*;
import uz.kassa.repo.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 📋 KUNLIK KASSA SOLISHTIRISH SHAKLI (foydalanuvchi namunasi, 02.09.2026):
 * Sana · Nuqta · Kassir · MoySklad savdosi · Bot savdosi · Naqd topshirilgan
 * · P2P qoldiq (kun oxiri) · Farq · Moliya menejeri tasdig'i.
 * Ikki formatda: PNG rasm (Telegram matnda jadval chizolmaydi) + Excel (.xlsx),
 * ostida qisqa izoh va «✅ Tasdiqlash» tugmasi.
 *
 * MoySklad savdosi — MoySklad API'dan BEVOSITA (kunlik hujjatlar otdel kesimida).
 * Bot savdosi    — botning kun yozuvi (prixod naqd+klik+terminal − vozvrat)
 *                  + otdelning Click hisoblariga shu kuni tushgan Klik kirimlar.
 * Farq           — MoySklad savdosi − Bot savdosi. Ikkala tizim bir xil bo'lsa 0;
 *                  farq bo'lsa hujjat botga tushmagan / ortiqcha yozilgan (foydalanuvchi
 *                  qarori: hisobot MoySklad bilan bot bir xilligini tekshirsin).
 * P2P qoldiq     — otdelga bog'langan Click kartasining kun oxiridagi qoldig'i
 *                  (skrinshot/qo'lda kiritilgan; kiritilmagan bo'lsa bot Click balansi, *).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DailyReportService {

    public static final String TIME_KEY = "notify.dailyTime";        // "22:00"
    public static final String LAST_SENT_KEY = "notify.dailyLastSent"; // "2026-09-02"
    private static final String CLICK_GROUPS_KEY = "notify.clickGroupChatId";
    private static final ZoneId ZONE = ZoneId.of("Asia/Tashkent");
    private static final DateTimeFormatter D_UZ = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final KassaRepo kassaRepo;
    private final DayRepo dayRepo;
    private final OperationRepo opRepo;
    private final ClickAccountRepo clickRepo;
    private final AppUserRepo userRepo;
    private final LedgerService ledger;
    private final SettingsService settings;
    private final DailyReportConfirmRepo confirmRepo;
    private final Sender sender;
    private final uz.kassa.service.moysklad.MoySkladSyncService syncService;

    public record Row(String nuqta, String kassir, long msSavdo, boolean msKnown, long botSavdo,
                      long naqdTopshirilgan, long p2pQoldiqTiyin, boolean p2pKnown, long farq) {}

    public String time() { return settings.get(TIME_KEY).filter(v -> v.matches("\\d{2}:\\d{2}")).orElse("22:00"); }

    /* ---------------- ma'lumot ---------------- */

    public List<Row> rows(LocalDate d) {
        Map<Long, Long> ms;
        boolean msKnown = true;
        try { ms = syncService.moyskladDaySales(d); }
        catch (Exception e) { log.warn("Kunlik hisobot: MoySklad o'qilmadi: {}", e.getMessage()); ms = Map.of(); msKnown = false; }

        List<Row> out = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;
            String nuqta = (k.getShopLabel() != null && !k.getShopLabel().isBlank())
                    ? k.getShopLabel() : k.getName();
            StringBuilder kb = new StringBuilder();
            for (AppUser u : userRepo.findByKassaIdAndActiveTrue(k.getId()))
                if (u.getRole() == Role.KASSIR) kb.append(kb.length() == 0 ? "" : ", ").append(u.getFullName());
            DayRecord day = dayRepo.findByKassaIdAndDate(k.getId(), d).orElse(null);
            // Bot savdosi = kun yozuvi (naqd + kassaga yozilgan klik + terminal − vozvrat)
            //             + otdelning CLICK hisoblariga tushgan Klik kirimlar (kun yozuvida yo'q)
            long bot = (day == null ? 0 : day.getPrixodNaqd() + day.getPrixodKlik() + day.getPrixodTerminal()
                    - day.getVozvratNaqd() - day.getVozvratKlik())
                    + opRepo.sumClickSalesNet(k.getId(), d);
            long msSavdo = msKnown ? ms.getOrDefault(k.getId(), 0L) : bot;
            long top = opRepo.sumTopshiriqNaqd(k.getId(), d);
            long p2p = 0; boolean known = false; boolean any = false;
            for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
                if (!k.getId().equals(c.getKassaId())) continue;
                any = true;
                if (c.getCardBalance() != null) { p2p += c.getCardBalance(); known = true; }
                else p2p += ledger.view(OwnerType.CLICK, c.getId(), MoneyType.KLIK).getAmount() * 100;
            }
            out.add(new Row(nuqta, kb.toString(), msSavdo, msKnown, bot, top, any ? p2p : -1, known, msSavdo - bot));
        }
        return out;
    }

    /* ---------------- yuborish ---------------- */

    /** Jadval (rasm) + Excel + izoh + tasdiq tugmasi — bitta chatga. */
    public Integer sendTo(long chatId, LocalDate d) {
        List<Row> rows = rows(d);
        DailyReportConfirm c = confirmRepo.findById(d).orElse(null);
        byte[] png = render(d, rows, c);
        String cap = caption(d, rows, c);
        InlineKeyboardMarkup kb = c == null ? Keyboards.inline(List.of(Keyboards.irow(
                Keyboards.btn("✅ Tasdiqlash (moliya menejeri)", "dr:ok:" + d)))) : null;
        Integer id = sender.sendPhoto(chatId, png, "kunlik-" + d + ".png", cap, kb);
        if (id == null) sender.send(chatId, cap, kb);   // rasm ketmasa — matn
        try {
            sender.sendDocument(chatId, renderXlsx(d, rows, c), "kunlik-kassa-" + d + ".xlsx",
                    "📊 Kunlik kassa solishtirish — " + d.format(D_UZ) + " (Excel)");
        } catch (Exception e) {
            log.warn("Kunlik Excel ({}): {}", chatId, e.getMessage());
        }
        return id;
    }

    /** Jadval bo'yicha: Click guruhlari + SuperAdmin + Buxgalter (shaxsiy). */
    public void sendScheduled(LocalDate d) {
        for (long chatId : clickChatIds()) {
            try { sendTo(chatId, d); } catch (Exception e) { log.warn("Kunlik hisobot ({}): {}", chatId, e.getMessage()); }
        }
        java.util.Set<Long> sent = new java.util.HashSet<>();
        for (Role r : List.of(Role.SUPERADMIN, Role.BUXGALTER))
            for (AppUser u : userRepo.findByRoleAndActiveTrue(r)) {
                if (u.getTelegramId() == null || !sent.add(u.getTelegramId())) continue;
                try { sendTo(u.getTelegramId(), d); } catch (Exception e) { log.debug("Kunlik hisobot user {}: {}", u.getTelegramId(), e.getMessage()); }
            }
        settings.set(LAST_SENT_KEY, d.toString());
        log.info("Kunlik kassa hisoboti yuborildi: {}", d);
    }

    /** Har 5 daqiqada chaqiriladi: sozlangan vaqt kelganda bugungi hisobot bir marta ketadi. */
    public void tick() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        String hhmm = now.format(DateTimeFormatter.ofPattern("HH:mm"));
        if (!hhmm.equals(time())) return;
        LocalDate today = now.toLocalDate();
        if (today.toString().equals(settings.get(LAST_SENT_KEY).orElse(""))) return;
        sendScheduled(today);
    }

    /** ✅ tasdiq: buxgalter/SuperAdmin bosdi. true — yangi tasdiq; false — allaqachon tasdiqlangan. */
    public boolean confirm(LocalDate d, AppUser by) {
        if (confirmRepo.existsById(d)) return false;
        confirmRepo.save(DailyReportConfirm.builder().reportDate(d).userId(by.getId())
                .userName(by.getFullName()).confirmedAt(Instant.now()).build());
        return true;
    }

    public DailyReportConfirm confirmRepoView(LocalDate d) { return confirmRepo.findById(d).orElse(null); }

    public String caption(LocalDate d, List<Row> rows, DailyReportConfirm c) {
        StringBuilder sb = new StringBuilder("📋 <b>Kunlik kassa solishtirish</b> — " + d.format(D_UZ) + "\n");
        for (Row r : rows)
            sb.append(r.farq() == 0 ? "✅ " : "⚠️ ").append(TextUtil.esc(r.nuqta()))
              .append(": MoySklad <b>").append(TextUtil.fmt(r.msSavdo())).append("</b> · bot <b>")
              .append(TextUtil.fmt(r.botSavdo())).append("</b>")
              .append(r.farq() == 0 ? "" : " · farq <b>" + (r.farq() > 0 ? "+" : "") + TextUtil.fmt(r.farq()) + "</b>")
              .append("\n");
        sb.append("<i>Farq = MoySklad savdosi − bot savdosi (0 — ikkala tizim bir xil)</i>\n");
        if (rows.stream().anyMatch(r -> !r.msKnown()))
            sb.append("⚠️ MoySklad o'qilmadi — MoySklad ustunida bot qiymati\n");
        if (c != null)
            sb.append("✔️ Tasdiqladi: <b>").append(TextUtil.esc(c.getUserName() == null ? "?" : c.getUserName()))
              .append("</b>, ").append(LocalDateTime.ofInstant(c.getConfirmedAt(), ZONE)
                      .format(DateTimeFormatter.ofPattern("dd.MM HH:mm")));
        else sb.append("⏳ Moliya menejeri tasdig'i kutilmoqda");
        String s = sb.toString();
        return s.length() > 1000 ? s.substring(0, 1000) : s;   // photo caption limiti 1024
    }

    private List<Long> clickChatIds() {
        List<Long> out = new ArrayList<>();
        for (String p : settings.get(CLICK_GROUPS_KEY).orElse("").split(",")) {
            try { if (!p.isBlank()) out.add(Long.parseLong(p.trim())); } catch (NumberFormatException ignore) { }
        }
        return out;
    }

    /* ---------------- rasm ---------------- */

    private static final String[] HEAD = {"Sana", "Nuqta", "Kassir", "MoySklad\nsavdosi", "Bot\nsavdosi",
            "Naqd\ntopshirilgan", "P2P qoldiq\n(kun oxiri)", "Farq", "Moliya menejeri\ntasdig'i"};
    private static final int[] W = {115, 170, 200, 160, 160, 160, 170, 140, 200};
    private static final int COL_FARQ = 7, COL_CONFIRM = 8;

    public byte[] render(LocalDate d, List<Row> rows, DailyReportConfirm c) {
        System.setProperty("java.awt.headless", "true");
        int pad = 24, titleH = 64, headH = 84, rowH = 48;
        int width = pad * 2; for (int w : W) width += w;
        int height = pad * 2 + titleH + 12 + headH + rowH * Math.max(1, rows.size()) + 24;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color bg = Color.WHITE, title = new Color(0x7B, 0x1D, 0x2E), headBg = new Color(0xF4, 0xDF, 0xE3),
              line = new Color(0xE6, 0xCC, 0xD2), alt = new Color(0xFB, 0xF3, 0xF5), warn = new Color(0xB0, 0x1E, 0x1E),
              ok = new Color(0x1E, 0x7B, 0x34), grey = new Color(0x55, 0x55, 0x55);
        g.setColor(bg); g.fillRect(0, 0, width, height);
        Font fb = font(Font.BOLD, 22), fh = font(Font.BOLD, 17), fr = font(Font.PLAIN, 18), fs = font(Font.PLAIN, 15);

        g.setColor(title); g.fillRoundRect(pad, pad, width - pad * 2, titleH, 10, 10);
        g.setColor(Color.WHITE); g.setFont(fb);
        g.drawString("Kunlik kassa solishtirish shakli — " + d.format(D_UZ), pad + 18, pad + 41);

        int y = pad + titleH + 12, x = pad;
        g.setColor(headBg); g.fillRect(x, y, width - pad * 2, headH);
        g.setColor(title); g.setFont(fh);
        for (int i = 0; i < HEAD.length; i++) {
            String[] ls = HEAD[i].split("\n");
            int ty = y + (ls.length == 1 ? 50 : 40);
            for (String l : ls) { g.drawString(l, x + 12, ty); ty += 22; }
            x += W[i];
        }
        y += headH;

        String confirmTxt = c == null ? "—" : (c.getUserName() == null ? "✔" : "✔ " + c.getUserName());
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            if (r % 2 == 0) { g.setColor(alt); g.fillRect(pad, y, width - pad * 2, rowH); }
            g.setColor(line); g.drawLine(pad, y, width - pad, y);
            x = pad;
            String[] cells = {
                    d.format(D_UZ), row.nuqta(), row.kassir(),
                    TextUtil.fmt(row.msSavdo()) + (row.msKnown() ? "" : " ?"),
                    TextUtil.fmt(row.botSavdo()),
                    TextUtil.fmt(row.naqdTopshirilgan()),
                    row.p2pQoldiqTiyin() < 0 ? "—" : TextUtil.fmtTiyin(row.p2pQoldiqTiyin()) + (row.p2pKnown() ? "" : " *"),
                    (row.farq() > 0 ? "+" : "") + TextUtil.fmt(row.farq()),
                    confirmTxt };
            for (int i = 0; i < cells.length; i++) {
                g.setColor(i == COL_FARQ ? (row.farq() == 0 ? ok : warn)
                        : (i == COL_CONFIRM && c != null ? ok : Color.DARK_GRAY));
                g.setFont(i == 2 && cells[i].length() > 20 ? fs : fr);
                g.drawString(clip(g, cells[i], W[i] - 20), x + 12, y + 31);
                x += W[i];
            }
            y += rowH;
        }
        g.setColor(line); g.drawLine(pad, y, width - pad, y);
        g.setColor(grey); g.setFont(fs);
        g.drawString("Farq = MoySklad savdosi − bot savdosi (0 — ikkala tizim bir xil).  * — karta qoldig'i kiritilmagan, bot Click balansi.",
                pad, y + 18);
        g.dispose();
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /* ---------------- Excel ---------------- */

    /** Xuddi shu jadval .xlsx: sarlavha, ustun boshlari, qatorlar, pastda formula izohi. */
    public byte[] renderXlsx(LocalDate d, List<Row> rows, DailyReportConfirm c) {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            var sh = wb.createSheet("Kunlik " + d.format(D_UZ));
            var titleF = wb.createFont(); titleF.setBold(true); titleF.setFontHeightInPoints((short) 14);
            titleF.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
            var titleSt = wb.createCellStyle(); titleSt.setFont(titleF);
            titleSt.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.DARK_RED.getIndex());
            titleSt.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            var headF = wb.createFont(); headF.setBold(true);
            headF.setColor(org.apache.poi.ss.usermodel.IndexedColors.DARK_RED.getIndex());
            var headSt = wb.createCellStyle(); headSt.setFont(headF); headSt.setWrapText(true);
            headSt.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.ROSE.getIndex());
            headSt.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            headSt.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            var money = wb.createCellStyle(); money.setDataFormat(wb.createDataFormat().getFormat("#,##0"));
            var money2 = wb.createCellStyle(); money2.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            var warnF = wb.createFont(); warnF.setBold(true);
            warnF.setColor(org.apache.poi.ss.usermodel.IndexedColors.RED.getIndex());
            var warnSt = wb.createCellStyle(); warnSt.setFont(warnF);
            warnSt.setDataFormat(wb.createDataFormat().getFormat("+#,##0;-#,##0;0"));
            var okF = wb.createFont(); okF.setBold(true);
            okF.setColor(org.apache.poi.ss.usermodel.IndexedColors.GREEN.getIndex());
            var okSt = wb.createCellStyle(); okSt.setFont(okF); okSt.setDataFormat(wb.createDataFormat().getFormat("#,##0"));

            int r = 0;
            var tr = sh.createRow(r++);
            var tc = tr.createCell(0); tc.setCellValue("Kunlik kassa solishtirish shakli — " + d.format(D_UZ)); tc.setCellStyle(titleSt);
            for (int i = 1; i < HEAD.length; i++) tr.createCell(i).setCellStyle(titleSt);
            sh.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, HEAD.length - 1));
            r++;
            var hr = sh.createRow(r++); hr.setHeightInPoints(32);
            for (int i = 0; i < HEAD.length; i++) {
                var cell = hr.createCell(i); cell.setCellValue(HEAD[i].replace('\n', ' ')); cell.setCellStyle(headSt);
            }
            String confirmTxt = c == null ? "—" : ("✔ " + (c.getUserName() == null ? "" : c.getUserName()) + " "
                    + LocalDateTime.ofInstant(c.getConfirmedAt(), ZONE).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")));
            for (Row row : rows) {
                var rr = sh.createRow(r++);
                rr.createCell(0).setCellValue(d.format(D_UZ));
                rr.createCell(1).setCellValue(row.nuqta());
                rr.createCell(2).setCellValue(row.kassir());
                var c3 = rr.createCell(3); c3.setCellValue(row.msSavdo()); c3.setCellStyle(money);
                var c4 = rr.createCell(4); c4.setCellValue(row.botSavdo()); c4.setCellStyle(money);
                var c5 = rr.createCell(5); c5.setCellValue(row.naqdTopshirilgan()); c5.setCellStyle(money);
                var c6 = rr.createCell(6);
                if (row.p2pQoldiqTiyin() < 0) c6.setCellValue("—");
                else { c6.setCellValue(row.p2pQoldiqTiyin() / 100.0); c6.setCellStyle(money2); }
                var c7 = rr.createCell(7); c7.setCellValue(row.farq()); c7.setCellStyle(row.farq() == 0 ? okSt : warnSt);
                rr.createCell(8).setCellValue(confirmTxt);
            }
            r++;
            sh.createRow(r++).createCell(0).setCellValue(
                    "Farq = MoySklad savdosi − bot savdosi (0 — ikkala tizim bir xil). P2P qoldiq — Click kartasi qoldig'i (kun oxiri).");
            int[] w = {13, 20, 30, 16, 16, 16, 18, 14, 26};
            for (int i = 0; i < w.length; i++) sh.setColumnWidth(i, w[i] * 256);
            wb.write(bos);
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Font font(int style, int size) {
        for (String n : new String[]{"DejaVu Sans", "Liberation Sans", "Arial", "SansSerif"}) {
            Font f = new Font(n, style, size);
            if (!f.getFamily().equalsIgnoreCase("Dialog") || n.equals("SansSerif")) return f;
        }
        return new Font("SansSerif", style, size);
    }

    private static String clip(Graphics2D g, String s, int maxW) {
        if (s == null) return "";
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && fm.stringWidth(t + "…") > maxW) t = t.substring(0, t.length() - 1);
        return t + "…";
    }
}
