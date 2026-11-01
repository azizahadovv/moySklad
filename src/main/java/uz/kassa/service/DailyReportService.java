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
import uz.kassa.service.moysklad.MoySkladAuditService;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 📋 KUNLIK KASSA SOLISHTIRISH SHAKLI (foydalanuvchi namunasi, 02.09.2026; 01.11.2026 qayta ishlandi):
 * Sana · Nuqta · Kassir · MoySklad kirimi · Naqd topshirilgan · Karta qoldig'i · Moliya menejeri tasdig'i.
 * Ikki formatda: PNG rasm (Telegram matnda jadval chizolmaydi) + Excel (.xlsx),
 * ostida qisqa izoh va «✅ Tasdiqlash» tugmasi. Excel'da yana 2 varaq: «Hujjatlar» (kirim qaysi MoySklad
 * hujjatlaridan yig'ilgan) va «Izoh» (har ustun qayerdan olinadi + har karta qoldig'ining manbasi).
 *
 * MoySklad kirimi — MoySklad API'dan BEVOSITA (kunlik hujjatlar otdel kesimida, MoySkladAuditService.moyskladDayDocs).
 * Bot savdosi / Farq — 01.11.2026 dan jadvalda YO'Q (user qarori): bot kun yozuvi o'sha MoySklad hujjatlaridan
 *                  sinxronlanadi, Farq deyarli doim 0 edi va hech narsa aytmasdi. Farq ≠ 0 bo'lsa (hujjat botga
 *                  tushmagan) — faqat izohda ⚠️. Row.botSavdo/farq Mini App va shablonlar uchun qoladi.
 * Karta qoldig'i  — otdelga bog'langan Click kartalarining OXIRGI kiritilgan qoldig'i (kun oxiri emas!)
 *                  (HUMOcardbot/qo'lda; kiritilmagan bo'lsa bot Click balansi, *).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DailyReportService {

    public static final String TIME_KEY = "notify.dailyTime";        // "22:00"
    public static final String LAST_SENT_KEY = "notify.dailyLastSent"; // "2026-09-02"
    private static final String CLICK_GROUPS_KEY = "notify.clickGroupChatId";
    private static final DateTimeFormatter D_UZ = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final uz.kassa.config.AppProps props;
    private final NotifySwitches sw;
    private final KassaRepo kassaRepo;
    private final DayRepo dayRepo;
    private final OperationRepo opRepo;
    private final ClickAccountRepo clickRepo;
    private final AppUserRepo userRepo;
    private final LedgerService ledger;
    private final SettingsService settings;
    private final DailyReportConfirmRepo confirmRepo;
    private final Sender sender;
    private final uz.kassa.service.moysklad.MoySkladAuditService auditSvc;

    public record Row(String nuqta, String kassir, long msSavdo, boolean msKnown, long botSavdo,
                      long naqdTopshirilgan, long p2pQoldiqTiyin, boolean p2pKnown, long farq,
                      Long kassaId) {}

    public String time() { return settings.get(TIME_KEY).filter(v -> v.matches("\\d{2}:\\d{2}")).orElse("22:00"); }

    /* ---------------- ma'lumot ---------------- */

    public List<Row> rows(LocalDate d) { return rows(d, new ArrayList<>()); }

    /** docsOut — kirimni tashkil etgan MoySklad hujjatlari («Hujjatlar» varag'i uchun) shu ro'yxatga yoziladi. */
    public List<Row> rows(LocalDate d, List<MoySkladAuditService.DayDoc> docsOut) {
        Map<Long, Long> ms;
        boolean msKnown = true;
        try {
            List<MoySkladAuditService.DayDoc> docs = auditSvc.moyskladDayDocs(d);
            docsOut.addAll(docs);
            ms = MoySkladAuditService.totals(docs);
        }
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
            out.add(new Row(nuqta, kb.toString(), msSavdo, msKnown, bot, top, any ? p2p : -1, known, msSavdo - bot,
                    k.getId()));
        }
        return out;
    }

    /* ---------------- yuborish ---------------- */

    /** Jadval (rasm) + Excel + izoh + tasdiq tugmasi — bitta chatga. */
    public Integer sendTo(long chatId, LocalDate d) {
        List<MoySkladAuditService.DayDoc> docs = new ArrayList<>();
        List<Row> rows = rows(d, docs);
        DailyReportConfirm c = confirmRepo.findById(d).orElse(null);
        byte[] png = render(d, rows, c);
        String cap = caption(d, rows, c);
        InlineKeyboardMarkup kb = c == null ? Keyboards.inline(List.of(Keyboards.irow(
                Keyboards.btn("✅ Tasdiqlash (moliya menejeri)", "dr:ok:" + d)))) : null;
        Integer id = sender.sendPhoto(chatId, png, "kunlik-" + d + ".png", cap, kb);
        if (id == null) sender.send(chatId, cap, kb);   // rasm ketmasa — matn
        try {
            sender.sendDocument(chatId, renderXlsx(d, rows, c, docs), "kunlik-kassa-" + d + ".xlsx",
                    "📊 Kunlik kassa solishtirish — " + d.format(D_UZ) + " (Excel: jadval · hujjatlar · izoh)");
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
                if (!sw.allow(NotifySwitches.KUNLIK_HISOBOT, u)) continue;   // 🔕 auditoriya
                try { sendTo(u.getTelegramId(), d); } catch (Exception e) { log.debug("Kunlik hisobot user {}: {}", u.getTelegramId(), e.getMessage()); }
            }
        settings.set(LAST_SENT_KEY, d.toString());
        log.info("Kunlik kassa hisoboti yuborildi: {}", d);
    }

    /** Har 5 daqiqada chaqiriladi: sozlangan vaqt kelganda bugungi hisobot bir marta ketadi. */
    public void tick() {
        LocalDateTime now = LocalDateTime.now(props.zoneId());
        String hhmm = now.format(DateTimeFormatter.ofPattern("HH:mm"));
        if (!hhmm.equals(time())) return;
        LocalDate today = now.toLocalDate();
        if (today.toString().equals(settings.get(LAST_SENT_KEY).orElse(""))) return;
        if (!sw.on(NotifySwitches.KUNLIK_HISOBOT)) { log.info("Kunlik hisobot: 🔕 o'chirilgan (Хабарномалар)"); return; }
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
            sb.append("• ").append(TextUtil.esc(r.nuqta()))
              .append(": kirim <b>").append(TextUtil.fmt(r.msSavdo())).append("</b> · naqd topshirildi <b>")
              .append(TextUtil.fmt(r.naqdTopshirilgan())).append("</b>\n");
        sb.append("<i>Kirim — MoySklad'dagi shu kungi kirim hujjatlari (naqd order + Klik/Terminal to'lov + chakana savdo − vozvrat). "
                + "Har summa qaysi hujjatdan — Excel «Hujjatlar» varag'ida, ustunlar izohi — «Izoh» varag'ida.</i>\n");
        for (Row r : rows)
            if (r.msKnown() && r.farq() != 0)
                sb.append("⚠️ ").append(TextUtil.esc(r.nuqta())).append(" — bot hisobida <b>")
                  .append(TextUtil.fmt(Math.abs(r.farq()))).append("</b> so'm ").append(r.farq() > 0 ? "kam" : "ortiq")
                  .append(" (hujjat botga tushmagan / o'chirilgan)\n");
        if (rows.stream().anyMatch(r -> !r.msKnown()))
            sb.append("⚠️ MoySklad o'qilmadi — kirim ustunida bot qiymati\n");
        if (c != null)
            sb.append("✔️ Tasdiqladi: <b>").append(TextUtil.esc(c.getUserName() == null ? "?" : c.getUserName()))
              .append("</b>, ").append(LocalDateTime.ofInstant(c.getConfirmedAt(), props.zoneId())
                      .format(DateTimeFormatter.ofPattern("dd.MM HH:mm")));
        else sb.append("⏳ Moliya menejeri tasdig'i kutilmoqda");
        return TextUtil.capCaption(sb.toString());
    }

    private List<Long> clickChatIds() {
        List<Long> out = new ArrayList<>();
        for (String p : settings.get(CLICK_GROUPS_KEY).orElse("").split(",")) {
            try { if (!p.isBlank()) out.add(Long.parseLong(p.trim())); } catch (NumberFormatException ignore) { }
        }
        return out;
    }

    /* ---------------- rasm ---------------- */

    private static final String[] HEAD = {"Sana", "Nuqta", "Kassir", "MoySklad\nkirimi",
            "Naqd\ntopshirilgan", "Karta qoldig'i\n(oxirgi)", "Moliya menejeri\ntasdig'i"};
    private static final int[] W = {115, 190, 300, 170, 160, 180, 200};
    private static final int COL_CONFIRM = 6;

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
            boolean syncWarn = row.msKnown() && row.farq() != 0;
            String[] cells = {
                    d.format(D_UZ), (syncWarn ? "(!) " : "") + row.nuqta(), row.kassir(),
                    TextUtil.fmt(row.msSavdo()) + (row.msKnown() ? "" : " ?"),
                    TextUtil.fmt(row.naqdTopshirilgan()),
                    row.p2pQoldiqTiyin() < 0 ? "—" : TextUtil.fmt(row.p2pQoldiqTiyin() / 100) + (row.p2pKnown() ? "" : " *"),
                    confirmTxt };
            for (int i = 0; i < cells.length; i++) {
                g.setColor(i == 1 && syncWarn ? warn : (i == COL_CONFIRM && c != null ? ok : Color.DARK_GRAY));
                g.setFont(i == 2 && cells[i].length() > 28 ? fs : fr);
                g.drawString(clip(g, cells[i], W[i] - 20), x + 12, y + 31);
                x += W[i];
            }
            y += rowH;
        }
        g.setColor(line); g.drawLine(pad, y, width - pad, y);
        g.setColor(grey); g.setFont(fs);
        g.drawString("Kirim = MoySklad: naqd order + Klik/Terminal to'lov + chakana savdo − vozvrat (hujjatlar ro'yxati — Excel'da).  "
                + "* — karta qoldig'i kiritilmagan, bot Click hisobi.  (!) — bot hisobi MoySklad'dan farq qiladi.", pad, y + 18);
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

    /** Xuddi shu jadval .xlsx + «Hujjatlar» (kirim tarkibi) + «Izoh» (ustunlar manbasi, karta qoldiqlari manbasi). */
    public byte[] renderXlsx(LocalDate d, List<Row> rows, DailyReportConfirm c, List<MoySkladAuditService.DayDoc> docs) {
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
            var warnF = wb.createFont(); warnF.setBold(true);
            warnF.setColor(org.apache.poi.ss.usermodel.IndexedColors.RED.getIndex());
            var warnSt = wb.createCellStyle(); warnSt.setFont(warnF);
            var boldF = wb.createFont(); boldF.setBold(true);
            var boldSt = wb.createCellStyle(); boldSt.setFont(boldF);
            var boldMoney = wb.createCellStyle(); boldMoney.setFont(boldF); boldMoney.setDataFormat(wb.createDataFormat().getFormat("#,##0"));
            var wrap = wb.createCellStyle(); wrap.setWrapText(true);
            wrap.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.TOP);

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
                    + LocalDateTime.ofInstant(c.getConfirmedAt(), props.zoneId()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")));
            for (Row row : rows) {
                boolean syncWarn = row.msKnown() && row.farq() != 0;
                var rr = sh.createRow(r++);
                rr.createCell(0).setCellValue(d.format(D_UZ));
                var c1 = rr.createCell(1); c1.setCellValue((syncWarn ? "(!) " : "") + row.nuqta()); if (syncWarn) c1.setCellStyle(warnSt);
                var c2 = rr.createCell(2); c2.setCellValue(row.kassir()); c2.setCellStyle(wrap);
                var c3 = rr.createCell(3); c3.setCellValue(row.msSavdo()); c3.setCellStyle(money);
                var c4 = rr.createCell(4); c4.setCellValue(row.naqdTopshirilgan()); c4.setCellStyle(money);
                var c5 = rr.createCell(5);
                if (row.p2pQoldiqTiyin() < 0) c5.setCellValue("—");
                else if (row.p2pKnown()) { c5.setCellValue(row.p2pQoldiqTiyin() / 100); c5.setCellStyle(money); }
                else c5.setCellValue(TextUtil.fmt(row.p2pQoldiqTiyin() / 100) + " *");
                rr.createCell(6).setCellValue(confirmTxt);
            }
            r++;
            sh.createRow(r++).createCell(0).setCellValue(
                    "MoySklad kirimi — qaysi hujjatlardan yig'ilgani «Hujjatlar» varag'ida. Har ustun nimani bildiradi — «Izoh» varag'ida.");
            sh.createRow(r++).createCell(0).setCellValue(
                    "* — karta qoldig'i kiritilmagan, bot Click hisobi ko'rsatildi.  (!) — bot hisobi MoySklad'dan farq qiladi (hujjat botga tushmagan).");
            int[] w = {13, 22, 42, 18, 16, 18, 26};
            for (int i = 0; i < w.length; i++) sh.setColumnWidth(i, w[i] * 256);

            /* ---- 2-varaq: Hujjatlar — kirim qaysi MoySklad hujjatlaridan ---- */
            var hs = wb.createSheet("Hujjatlar");
            int hrn = 0;
            var h0 = hs.createRow(hrn++);
            h0.createCell(0).setCellValue("MoySklad kirimi qaysi hujjatlardan yig'ilgan — " + d.format(D_UZ)); h0.getCell(0).setCellStyle(boldSt);
            hs.createRow(hrn++).createCell(0).setCellValue("Har nuqta ostida «Jami» — 1-varaqdagi «MoySklad kirimi» bilan bir xil.");
            hrn++;
            String[] dh = {"Nuqta", "Hujjat turi", "№", "Kontragent", "Izoh", "Summa"};
            var dhr = hs.createRow(hrn++);
            for (int i = 0; i < dh.length; i++) { var cell = dhr.createCell(i); cell.setCellValue(dh[i]); cell.setCellStyle(headSt); }
            Map<Long, String> names = new java.util.LinkedHashMap<>();
            for (Row row : rows) if (row.kassaId() != null) names.put(row.kassaId(), row.nuqta());
            names.put(-1L, "Otdelga bog'lanmagan (hech qaysi nuqtaga kirmaydi)");
            for (var e : names.entrySet()) {
                List<MoySkladAuditService.DayDoc> mine = docs.stream().filter(x -> x.kassaId() == e.getKey()).toList();
                if (mine.isEmpty()) continue;
                for (var x : mine) {
                    var dr = hs.createRow(hrn++);
                    dr.createCell(0).setCellValue(e.getValue());
                    dr.createCell(1).setCellValue(x.kind());
                    dr.createCell(2).setCellValue(x.docNo() == null ? "" : x.docNo());
                    dr.createCell(3).setCellValue(x.agent() == null ? "" : x.agent());
                    dr.createCell(4).setCellValue(x.note() == null ? "" : x.note());
                    var sc = dr.createCell(5); sc.setCellValue(x.sum()); sc.setCellStyle(money);
                }
                var tot = hs.createRow(hrn++);
                tot.createCell(0).setCellValue("Jami — " + e.getValue()); tot.getCell(0).setCellStyle(boldSt);
                var tc5 = tot.createCell(5); tc5.setCellValue(mine.stream().mapToLong(MoySkladAuditService.DayDoc::sum).sum()); tc5.setCellStyle(boldMoney);
                hrn++;
            }
            if (docs.isEmpty()) hs.createRow(hrn++).createCell(0).setCellValue(
                    rows.stream().allMatch(Row::msKnown) ? "Shu kuni kirim hujjati yo'q." : "MoySklad o'qilmadi — hujjatlar ro'yxati yo'q.");
            int[] dw = {26, 30, 12, 30, 40, 16};
            for (int i = 0; i < dw.length; i++) hs.setColumnWidth(i, dw[i] * 256);

            /* ---- 3-varaq: Izoh — har ustun qayerdan olinadi + karta qoldiqlari manbasi ---- */
            var is = wb.createSheet("Izoh");
            int irn = 0;
            var i0 = is.createRow(irn++); i0.createCell(0).setCellValue("Ustunlar nimani bildiradi"); i0.getCell(0).setCellStyle(boldSt);
            String[][] help = {
                    {"Nuqta", "Kassa (do'kon). MoySklad'dagi otdel shu kassaga bog'langan — hujjat qaysi otdelda bo'lsa, o'sha nuqtaga tushadi."},
                    {"Kassir", "Botda shu kassaga biriktirilgan faol kassirlar."},
                    {"MoySklad kirimi", "Shu kuni MoySklad'da shu otdel nomidan o'tgan pul kirimlari: Приходный ордер (naqd) + "
                            + "Входящий платёж (click va terminal statusli — standart «Клик» va «Картадан тулов», sozlamada o'zgartiriladi) "
                            + "+ chakana savdo (naqd+naqdsiz) − chakana vozvrat. "
                            + "Перечисление, Карз va boshqa statusli to'lovlar KIRMAYDI. Ro'yxat — «Hujjatlar» varag'ida."},
                    {"Naqd topshirilgan", "Shu kuni kassir buxgalteriyaga topshirgan NAQD pul (botda qabul qilingan topshiriqlar). "
                            + "Kirimdan kam bo'lishi normal: Klik/Terminal pul kassada naqd bo'lib turmaydi, naqd esa ertasi kuni topshirilishi mumkin."},
                    {"Karta qoldig'i (oxirgi)", "Nuqtaga bog'langan Click kartalari qoldiqlari yig'indisi — OXIRGI kiritilgan qiymat "
                            + "(kun oxiridagi emas). Har karta qachon va kim tomonidan kiritilgani — pastdagi jadvalda. "
                            + "* — karta qoldig'i hech kiritilmagan, o'rniga botdagi Click hisobi ko'rsatilgan."},
                    {"Moliya menejeri tasdig'i", "Buxgalter/SuperAdmin «✅ Tasdiqlash» tugmasini bosgan vaqt va ism."},
                    {"(!) belgisi", "Bot hisobidagi shu kungi kirim MoySklad'dagidan farq qiladi — hujjat botga tushmagan yoki MoySklad'da "
                            + "keyin o'zgartirilgan/o'chirilgan. Bot o'zi qayta sinxronlaydi; saqlanib qolsa ⚙️ → 🔗 MoySklad → 🩺 Диагностика."},
            };
            for (String[] h : help) {
                var hrw = is.createRow(irn++);
                var a = hrw.createCell(0); a.setCellValue(h[0]); a.setCellStyle(boldSt);
                var b = hrw.createCell(1); b.setCellValue(h[1]); b.setCellStyle(wrap);
            }
            irn++;
            var k0 = is.createRow(irn++); k0.createCell(0).setCellValue("Karta qoldiqlari — manba"); k0.getCell(0).setCellStyle(boldSt);
            String[] kh = {"Nuqta", "Karta / Click hisobi", "Qoldiq", "Kim kiritdi", "Qachon"};
            var khr = is.createRow(irn++);
            for (int i = 0; i < kh.length; i++) { var cell = khr.createCell(i); cell.setCellValue(kh[i]); cell.setCellStyle(headSt); }
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
            for (Row row : rows) {
                if (row.kassaId() == null) continue;
                for (ClickAccount ca : clickRepo.findByActiveTrueOrderByIdAsc()) {
                    if (!row.kassaId().equals(ca.getKassaId())) continue;
                    var kr = is.createRow(irn++);
                    kr.createCell(0).setCellValue(row.nuqta());
                    kr.createCell(1).setCellValue(ca.getName());
                    var q = kr.createCell(2);
                    if (ca.getCardBalance() != null) {
                        q.setCellValue(ca.getCardBalance() / 100); q.setCellStyle(money);
                        kr.createCell(3).setCellValue(ca.getCardBalanceBy() == null ? "" : ca.getCardBalanceBy());
                        kr.createCell(4).setCellValue(ca.getCardBalanceAt() == null ? ""
                                : LocalDateTime.ofInstant(ca.getCardBalanceAt(), props.zoneId()).format(dtf));
                    } else {
                        q.setCellValue(TextUtil.fmt(ledger.view(OwnerType.CLICK, ca.getId(), MoneyType.KLIK).getAmount()) + " *");
                        kr.createCell(3).setCellValue("kiritilmagan — bot Click hisobi");
                    }
                }
            }
            int[] iw = {26, 70, 16, 22, 18};
            for (int i = 0; i < iw.length; i++) is.setColumnWidth(i, iw[i] * 256);
            wb.write(bos);
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Font font(int style, int size) { return uz.kassa.bot.TableImage.font(style, size); }

    private static String clip(Graphics2D g, String s, int maxW) { return uz.kassa.bot.TableImage.clip(g, s, maxW); }
}
