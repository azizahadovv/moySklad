package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import uz.kassa.bot.NameService;
import uz.kassa.domain.*;
import uz.kassa.repo.CategoryRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.repo.OperationRepo;
import uz.kassa.service.LedgerService;
import uz.kassa.service.moysklad.MoySkladClient;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 📊 Admin Excel hisoboti (davr filtri bilan, MoySklad filtriga o'xshash):
 *   1-varaq «Umumiy»          — kassa kesimida kirim/chiqim/balans
 *   2-varaq «Tranzaksiyalar»  — tizimdagi barcha operatsiyalar
 *   3-varaq «MoySklad»        — pulga aloqador BARCHA hujjatlar jonli API'dan:
 *                               Приходный/Расходный ордер, Входящий/Исходящий платеж
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExcelReportService {

    private final uz.kassa.config.AppProps props;
    private final LedgerService ledger;
    private final KassaRepo kassaRepo;
    private final OperationRepo opRepo;
    private final CategoryRepo categoryRepo;
    private final NameService names;
    private final MoySkladClient ms;

    public byte[] build(LocalDate from, LocalDate to) { return build(from, to, null); }

    /** only != null bo'lsa — faqat shu kassa (otdel) kesimida. */
    public byte[] build(LocalDate from, LocalDate to, Kassa only) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            CellStyle head = wb.createCellStyle();
            Font hf = wb.createFont(); hf.setBold(true); head.setFont(hf);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0"));
            CellStyle bold = wb.createCellStyle();
            Font bf = wb.createFont(); bf.setBold(true); bold.setFont(bf);
            bold.setDataFormat(wb.createDataFormat().getFormat("#,##0"));

            List<Operation> ops = opRepo.byPeriod(from, to);
            if (only != null) {
                Long kid = only.getId();
                ops = ops.stream().filter(o ->
                        (o.getFromOwnerType() == OwnerType.KASSA && kid.equals(o.getFromOwnerId()))
                     || (o.getToOwnerType() == OwnerType.KASSA && kid.equals(o.getToOwnerId()))).toList();
            }
            summarySheet(wb, head, money, bold, ops, only, to);
            operationsSheet(wb, head, money, ops);
            moyskladSheet(wb, head, money, from, to, only);

            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            log.error("Excel hisobot xatosi: {}", e.getMessage());
            throw new RuntimeException("Excel tayyorlashda xato: " + e.getMessage());
        }
    }

    /** 📋 Audit jurnali Excel: kim, qachon, nima qildi. */
    public byte[] buildAudit(List<AuditLog> logs,
                             java.util.function.Function<Long, String> userName,
                             java.time.ZoneId zone) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            CellStyle head = wb.createCellStyle();
            Font hf = wb.createFont(); hf.setBold(true); head.setFont(hf);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Sheet sh = wb.createSheet("Audit");
            String[] cols = {"Sana", "Vaqt", "Foydalanuvchi", "Amal", "Obyekt", "Obyekt ID", "Tafsilot"};
            Row hr = sh.createRow(0);
            for (int i = 0; i < cols.length; i++) {
                Cell c = hr.createCell(i); c.setCellValue(cols[i]); c.setCellStyle(head);
            }
            java.time.format.DateTimeFormatter df =
                    java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(zone);
            java.time.format.DateTimeFormatter tf =
                    java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone);
            int r = 1;
            for (AuditLog a : logs) {
                Row row = sh.createRow(r++);
                row.createCell(0).setCellValue(df.format(a.getCreatedAt()));
                row.createCell(1).setCellValue(tf.format(a.getCreatedAt()));
                row.createCell(2).setCellValue(a.getUserId() == null ? "tizim" : userName.apply(a.getUserId()));
                row.createCell(3).setCellValue(a.getAction());
                row.createCell(4).setCellValue(a.getEntity() == null ? "" : a.getEntity());
                if (a.getEntityId() != null) row.createCell(5).setCellValue(a.getEntityId());
                row.createCell(6).setCellValue(a.getPayload() == null ? "" : a.getPayload());
            }
            for (int i = 0; i < cols.length; i++) sh.autoSizeColumn(i);
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            log.error("Audit Excel xatosi: {}", e.getMessage());
            throw new RuntimeException("Excel tayyorlashda xato: " + e.getMessage());
        }
    }

    /* ---------------- 🕵️ NAZORAT: qarzdorlar / kontragent xatolari ---------------- */

    /** 🧾 Qarzdor otgruzkalar ro'yxati (docs/KONTRAGENT-NAZORAT.md). */
    public byte[] buildDebts(List<uz.kassa.domain.Shipment> list,
                             java.util.function.Function<Long, String> userName,
                             java.util.function.Function<Long, String> kassaName,
                             java.time.ZoneId zone) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            CellStyle head = wb.createCellStyle();
            Font hf = wb.createFont(); hf.setBold(true); head.setFont(hf);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0"));

            Sheet sh = wb.createSheet("Qarzdorlar");
            String[] cols = {"№ otgruzka", "Sana", "Otdel", "Xodim", "Klient", "Telefon", "Summa", "To'langan",
                    "Qoldiq", "Kontragent balansi", "Status (MS)", "To'lov muddati", "Holat", "Масъул",
                    "Qarzda (kun)", "Komentariya", "Kamchilik"};
            Row hr = sh.createRow(0);
            for (int i = 0; i < cols.length; i++) {
                Cell c = hr.createCell(i); c.setCellValue(cols[i]); c.setCellStyle(head);
            }
            java.time.format.DateTimeFormatter df = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
            java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
            LocalDate today = LocalDate.now(zone);
            int r = 1;
            for (uz.kassa.domain.Shipment s : list) {
                Row row = sh.createRow(r++);
                row.createCell(0).setCellValue(s.getDocNo());
                row.createCell(1).setCellValue(s.getMoment() == null ? "" : s.getMoment().format(dtf));
                row.createCell(2).setCellValue(s.getKassaId() == null ? "" : kassaName.apply(s.getKassaId()));
                row.createCell(3).setCellValue(s.getOwnerUserId() == null ? s.getOwnerName() : userName.apply(s.getOwnerUserId()));
                row.createCell(4).setCellValue(s.getAgentName());
                row.createCell(5).setCellValue(s.getAgentPhone());
                Cell c6 = row.createCell(6); c6.setCellValue(s.getSum()); c6.setCellStyle(money);
                Cell c7 = row.createCell(7); c7.setCellValue(s.getPayedSum()); c7.setCellStyle(money);
                Cell c8 = row.createCell(8); c8.setCellValue(s.remain()); c8.setCellStyle(money);
                if (s.getAgentBalance() != null) { Cell c9 = row.createCell(9); c9.setCellValue(s.getAgentBalance()); c9.setCellStyle(money); }
                row.createCell(10).setCellValue(s.getState());
                row.createCell(11).setCellValue(s.getDueAt() == null ? "" : s.getDueAt().format(df));
                row.createCell(12).setCellValue(s.getDueAt() == null ? "muddat yo'q"
                        : s.getDueAt().isBefore(today) ? "o'tgan" : s.getDueAt().equals(today) ? "bugun" : "kutilmoqda");
                row.createCell(13).setCellValue(s.getMasul());
                if (s.getDebtSince() != null)
                    row.createCell(14).setCellValue(java.time.temporal.ChronoUnit.DAYS.between(s.getDebtSince(), java.time.Instant.now()));
                row.createCell(15).setCellValue(s.getComment() == null ? "" : s.getComment());
                row.createCell(16).setCellValue(uz.kassa.service.control.ShipmentControlService.issueLabels(s.issueList()));
            }
            for (int i = 0; i < cols.length; i++) sh.autoSizeColumn(i);
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            log.error("Qarzdorlar Excel xatosi: {}", e.getMessage());
            throw new RuntimeException("Excel tayyorlashda xato: " + e.getMessage());
        }
    }


    /** ⚠️ Tuzatilmagan kontragentlar ro'yxati. */
    public byte[] buildAgentErrors(List<uz.kassa.domain.AgentCheck> list,
                                   java.util.function.Function<Long, String> userName,
                                   java.util.function.Function<Long, String> kassaName,
                                   java.util.function.Function<String, String> ruleTitle) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            CellStyle head = wb.createCellStyle();
            Font hf = wb.createFont(); hf.setBold(true); head.setFont(hf);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Sheet sh = wb.createSheet("Kontragent xatolari");
            String[] cols = {"Kontragent", "Yaratilgan", "Xodim (bot)", "MoySklad login", "Otdel", "Xatolar", "Holat", "Xabar berilgan"};
            Row hr = sh.createRow(0);
            for (int i = 0; i < cols.length; i++) {
                Cell c = hr.createCell(i); c.setCellValue(cols[i]); c.setCellStyle(head);
            }
            java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
            int r = 1;
            for (uz.kassa.domain.AgentCheck a : list) {
                Row row = sh.createRow(r++);
                row.createCell(0).setCellValue(a.getAgentName());
                row.createCell(1).setCellValue(a.getMsCreatedAt() == null ? "" : a.getMsCreatedAt().format(dtf));
                row.createCell(2).setCellValue(a.getCreatorUserId() == null ? "" : userName.apply(a.getCreatorUserId()));
                row.createCell(3).setCellValue(a.getCreatedUid() == null ? "" : a.getCreatedUid());
                row.createCell(4).setCellValue(a.getKassaId() == null ? "" : kassaName.apply(a.getKassaId()));
                row.createCell(5).setCellValue(String.join("; ", a.violationList().stream().map(ruleTitle).toList()));
                row.createCell(6).setCellValue(a.getStatus().name());
                row.createCell(7).setCellValue(a.getNotifiedAt() == null ? "" : dtf.format(a.getNotifiedAt().atZone(props.zoneId())));
            }
            for (int i = 0; i < cols.length; i++) sh.autoSizeColumn(i);
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            log.error("Kontragent xatolari Excel xatosi: {}", e.getMessage());
            throw new RuntimeException("Excel tayyorlashda xato: " + e.getMessage());
        }
    }

    /* ---------------- 1: UMUMIY ---------------- */

    /** Agregat kaliti — (tur, id). Nom faqat chiqarishda (H4: bir xil nomli kassalar qo'shilib ketmasin). */
    private record OwnerKey(OwnerType ot, Long oid) { }

    private void summarySheet(Workbook wb, CellStyle head, CellStyle money, CellStyle bold,
                              List<Operation> ops, Kassa only, LocalDate to) {
        Sheet sh = wb.createSheet("Umumiy");
        boolean asOfToday = !to.isBefore(ledger.today());
        String balLabel = asOfToday ? "Joriy balans" : "Balans " + to.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM"));
        String[] cols = {"Kassa", "Kirim Naqd", "Kirim Klik", "Kirim Terminal",
                "Chiqim Naqd", "Chiqim Klik", "Chiqim Terminal", "Farq (naqd+klik)",
                balLabel + " Naqd", balLabel + " Klik"};
        row(sh, 0, head, (Object[]) cols);

        // [kn,kk,kt,cn,ck,ct] — terminal alohida (H5: balansga kirmaydi, Farq'da yo'q)
        Map<OwnerKey, long[]> agg = new LinkedHashMap<>();
        if (only != null) agg.put(new OwnerKey(OwnerType.KASSA, only.getId()), new long[6]);
        else {
            for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
                if (!k.isCashless()) agg.put(new OwnerKey(OwnerType.KASSA, k.getId()), new long[6]);
            agg.put(new OwnerKey(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID), new long[6]);
        }

        for (Operation o : ops) {
            // Rad etilgan / yo'ldagi operatsiyalar pul emas — svodga kirmaydi
            if (o.getStatus() != uz.kassa.domain.OpStatus.TASDIQLANGAN) continue;
            boolean in = o.getType() == OpType.PRIXOD || o.getType() == OpType.BOSHLANGICH;
            boolean out = o.getType() == OpType.RASXOD || o.getType() == OpType.VOZVRAT;
            int mtIdx = switch (o.getMoneyType()) { case NAQD -> 0; case KLIK -> 1; case TERMINAL -> 2; };
            if (in && o.getToOwnerType() != null) {
                // H3: uchragan har ega qo'shiladi (nofaol/cashless kassa, Click hisob ham) — 1-varaq JAMI = 2-varaq yig'indisi
                agg.computeIfAbsent(new OwnerKey(o.getToOwnerType(), o.getToOwnerId()), x -> new long[6])[mtIdx] += o.getAmount();
            }
            if (out && o.getFromOwnerType() != null) {
                agg.computeIfAbsent(new OwnerKey(o.getFromOwnerType(), o.getFromOwnerId()), x -> new long[6])[3 + mtIdx] += o.getAmount();
            }
        }

        int r = 1; long[] tot = new long[6]; long totBn = 0, totBk = 0;
        for (Map.Entry<OwnerKey, long[]> e : agg.entrySet()) {
            OwnerKey k = e.getKey(); long[] a = e.getValue();
            for (int i = 0; i < 6; i++) tot[i] += a[i];
            // H6: davr hisobotida balans davr OXIRIDAGI holat, bugungi emas
            long bn = asOfToday ? ledger.view(k.ot(), k.oid(), MoneyType.NAQD).getAmount()
                                : ledger.balanceAsOf(k.ot(), k.oid(), MoneyType.NAQD, to);
            long bk = asOfToday ? ledger.view(k.ot(), k.oid(), MoneyType.KLIK).getAmount()
                                : ledger.balanceAsOf(k.ot(), k.oid(), MoneyType.KLIK, to);
            totBn += bn; totBk += bk;
            Row row = sh.createRow(r++);
            cell(row, 0, ownerLabel(k), null);
            num(row, 1, a[0], money); num(row, 2, a[1], money); num(row, 3, a[2], money);
            num(row, 4, a[3], money); num(row, 5, a[4], money); num(row, 6, a[5], money);
            num(row, 7, a[0] + a[1] - a[3] - a[4], money);
            num(row, 8, bn, money); num(row, 9, bk, money);
        }
        Row t = sh.createRow(r);
        cell(t, 0, "JAMI", bold);
        num(t, 1, tot[0], bold); num(t, 2, tot[1], bold); num(t, 3, tot[2], bold);
        num(t, 4, tot[3], bold); num(t, 5, tot[4], bold); num(t, 6, tot[5], bold);
        num(t, 7, tot[0] + tot[1] - tot[3] - tot[4], bold);
        num(t, 8, totBn, bold); num(t, 9, totBk, bold);
        autos(sh, cols.length);
    }

    /** Qator nomi: nofaol / cashless kassalar belgi bilan (UX-6). */
    private String ownerLabel(OwnerKey k) {
        String name = ownerName(k.ot(), k.oid());
        if (k.ot() != OwnerType.KASSA) return name;
        Kassa kassa = kassaRepo.findById(k.oid()).orElse(null);
        if (kassa == null) return name;
        if (!kassa.isActive()) return name + " (nofaol)";
        if (kassa.isCashless()) return name + " (cashless)";
        return name;
    }

    /* ---------------- 2: TRANZAKSIYALAR ---------------- */

    private void operationsSheet(Workbook wb, CellStyle head, CellStyle money, List<Operation> ops) {
        Sheet sh = wb.createSheet("Tranzaksiyalar");
        String[] cols = {"Sana", "Turi", "Pul turi", "Summa", "Kimdan", "Kimga",
                "Status", "Kategoriya", "Izoh", "MoySklad ID"};
        row(sh, 0, head, (Object[]) cols);
        int r = 1;
        for (Operation o : ops) {
            Row row = sh.createRow(r++);
            cell(row, 0, o.getOpDate().toString(), null);
            cell(row, 1, o.getType().name(), null);
            cell(row, 2, o.getMoneyType().name(), null);
            num(row, 3, o.getAmount(), money);
            cell(row, 4, o.getFromOwnerType() == null ? "" :
                    ownerName(o.getFromOwnerType(), o.getFromOwnerId()), null);
            cell(row, 5, o.getToOwnerType() == null ? "" :
                    ownerName(o.getToOwnerType(), o.getToOwnerId()), null);
            cell(row, 6, o.getStatus().name(), null);
            cell(row, 7, o.getCategoryId() == null ? "" :
                    categoryRepo.findById(o.getCategoryId()).map(Category::getName).orElse(""), null);
            cell(row, 8, o.getComment() == null ? "" : o.getComment(), null);
            cell(row, 9, o.getMoyskladId() == null ? "" : o.getMoyskladId(), null);
        }
        autos(sh, cols.length);
    }

    /* ---------------- 3: MOYSKLAD (jonli API) ---------------- */

    private void moyskladSheet(Workbook wb, CellStyle head, CellStyle money,
                               LocalDate from, LocalDate to, Kassa only) {
        Sheet sh = wb.createSheet("MoySklad");
        String[] cols = {"Hujjat", "№", "Sana", "Otdel", "Kontragent", "Status",
                "Statya", "Summa", "Izoh"};
        row(sh, 0, head, (Object[]) cols);
        Map<String, String> groupNames = ms.fetchGroups();
        String onlyGroup = only == null ? null : only.getMoyskladGroupId();
        int r = 1;
        r = msRows(sh, r, "Приходный ордер", filt(ms.fetchDocsByMoment("cashin", from, to), onlyGroup), groupNames, money);
        r = msRows(sh, r, "Расходный ордер", filt(ms.fetchDocsByMoment("cashout", from, to), onlyGroup), groupNames, money);
        r = msRows(sh, r, "Входящий платеж", filt(ms.fetchDocsByMoment("paymentin", from, to), onlyGroup), groupNames, money);
        msRows(sh, r, "Исходящий платеж", filt(ms.fetchDocsByMoment("paymentout", from, to), onlyGroup), groupNames, money);
        autos(sh, cols.length);
    }

    private List<MoySkladClient.MsExpense> filt(List<MoySkladClient.MsExpense> docs, String group) {
        if (group == null) return docs;
        return docs.stream().filter(e -> group.equals(e.groupId())).toList();
    }

    private int msRows(Sheet sh, int r, String docType, List<MoySkladClient.MsExpense> docs,
                       Map<String, String> groupNames, CellStyle money) {
        for (MoySkladClient.MsExpense e : docs) {
            Row row = sh.createRow(r++);
            cell(row, 0, docType, null);
            cell(row, 1, e.docNo(), null);
            cell(row, 2, e.date().toString(), null);
            cell(row, 3, groupNames.getOrDefault(e.groupId(), ""), null);
            cell(row, 4, e.agent(), null);
            cell(row, 5, e.state(), null);
            cell(row, 6, e.expenseItem(), null);
            num(row, 7, e.sumTiyin() / 100, money);
            cell(row, 8, e.description(), null);
        }
        return r;
    }

    /* ---------------- yordamchi ---------------- */

    private String ownerName(OwnerType ot, Long oid) {
        return ot == OwnerType.BUXGALTERIYA ? "Отдел Основной" : names.owner(ot, oid);
    }
    private void row(Sheet sh, int idx, CellStyle st, Object... vals) {
        Row r = sh.createRow(idx);
        for (int i = 0; i < vals.length; i++) cell(r, i, String.valueOf(vals[i]), st);
    }
    private void cell(Row r, int i, String v, CellStyle st) {
        Cell c = r.createCell(i); c.setCellValue(v);
        if (st != null) c.setCellStyle(st);
    }
    private void num(Row r, int i, long v, CellStyle st) {
        Cell c = r.createCell(i); c.setCellValue(v);
        if (st != null) c.setCellStyle(st);
    }
    private void autos(Sheet sh, int n) {
        for (int i = 0; i < n; i++) sh.autoSizeColumn(i);
    }

    /** Umumiy jadval (🏬 Ombor va boshqalar): sarlavha qatori + qatorlar (Number → son, qolgani matn). */
    public byte[] buildTable(String sheetName, String[] cols, List<Object[]> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            CellStyle head = wb.createCellStyle();
            Font hf = wb.createFont(); hf.setBold(true); head.setFont(hf);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Sheet sh = wb.createSheet(sheetName.length() > 30 ? sheetName.substring(0, 30) : sheetName);
            Row hr = sh.createRow(0);
            for (int i = 0; i < cols.length; i++) { Cell c = hr.createCell(i); c.setCellValue(cols[i]); c.setCellStyle(head); }
            int r = 1;
            for (Object[] row : rows) {
                Row x = sh.createRow(r++);
                for (int i = 0; i < row.length; i++) {
                    Object v = row[i];
                    if (v instanceof Number n) x.createCell(i).setCellValue(n.doubleValue());
                    else x.createCell(i).setCellValue(v == null ? "" : String.valueOf(v));
                }
            }
            for (int i = 0; i < cols.length; i++) sh.setColumnWidth(i, Math.min(60, Math.max(12, cols[i].length() + 6)) * 256);
            wb.write(bos);
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Excel: " + e.getMessage(), e);
        }
    }

    /** Bir nechta varaq (🏬 Ombor kamchiliklari): har varaq sarlavha + qatorlar. */
    public record SheetDef(String name, String[] cols, List<Object[]> rows) {}

    public byte[] buildSheets(List<SheetDef> sheets) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            CellStyle head = wb.createCellStyle();
            Font hf = wb.createFont(); hf.setBold(true); head.setFont(hf);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            java.util.Set<String> used = new java.util.HashSet<>();
            for (SheetDef d : sheets) {
                String name = d.name().replaceAll("[\\\\/?*\\[\\]:]", " ");
                name = name.length() > 28 ? name.substring(0, 28) : name;
                while (!used.add(name)) name = name + "_";
                Sheet sh = wb.createSheet(name);
                Row hr = sh.createRow(0);
                for (int i = 0; i < d.cols().length; i++) { Cell c = hr.createCell(i); c.setCellValue(d.cols()[i]); c.setCellStyle(head); }
                int r = 1;
                for (Object[] row : d.rows()) {
                    Row x = sh.createRow(r++);
                    for (int i = 0; i < row.length; i++) {
                        Object v = row[i];
                        if (v instanceof Number n) x.createCell(i).setCellValue(n.doubleValue());
                        else x.createCell(i).setCellValue(v == null ? "" : String.valueOf(v));
                    }
                }
                for (int i = 0; i < d.cols().length; i++) sh.setColumnWidth(i, Math.min(60, Math.max(10, d.cols()[i].length() + 6)) * 256);
                sh.createFreezePane(0, 1);
                if (!d.rows().isEmpty()) sh.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, d.rows().size(), 0, d.cols().length - 1));
            }
            wb.write(bos);
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Excel: " + e.getMessage(), e);
        }
    }
}
