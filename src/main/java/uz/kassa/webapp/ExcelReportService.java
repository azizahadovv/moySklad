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
            summarySheet(wb, head, money, bold, ops, only);
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

    /* ---------------- 1: UMUMIY ---------------- */

    private void summarySheet(Workbook wb, CellStyle head, CellStyle money, CellStyle bold,
                              List<Operation> ops, Kassa only) {
        Sheet sh = wb.createSheet("Umumiy");
        String[] cols = {"Kassa", "Kirim Naqd", "Kirim Klik", "Kirim Terminal",
                "Chiqim Naqd", "Chiqim Klik", "Farq",
                "Joriy balans Naqd", "Joriy balans Klik"};
        row(sh, 0, head, (Object[]) cols);

        Map<String, long[]> agg = new LinkedHashMap<>();   // nom -> [kn,kk,kt,cn,ck]
        if (only != null) agg.put(only.getName(), new long[5]);
        else {
            for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
                if (!k.isCashless()) agg.put(k.getName(), new long[5]);
            agg.put("Отдел Основной", new long[5]);
        }

        for (Operation o : ops) {
            // Rad etilgan / yo'ldagi operatsiyalar pul emas — svodga kirmaydi
            if (o.getStatus() != uz.kassa.domain.OpStatus.TASDIQLANGAN) continue;
            boolean in = o.getType() == OpType.PRIXOD || o.getType() == OpType.BOSHLANGICH;
            boolean out = o.getType() == OpType.RASXOD || o.getType() == OpType.VOZVRAT;
            if (in && o.getToOwnerType() != null) {
                long[] a = agg.get(ownerName(o.getToOwnerType(), o.getToOwnerId()));
                if (a != null) a[switch (o.getMoneyType()) {
                    case NAQD -> 0; case KLIK -> 1; case TERMINAL -> 2;
                }] += o.getAmount();
            }
            if (out && o.getFromOwnerType() != null) {
                long[] a = agg.get(ownerName(o.getFromOwnerType(), o.getFromOwnerId()));
                if (a != null) a[o.getMoneyType() == MoneyType.KLIK ? 4 : 3] += o.getAmount();
            }
        }

        int r = 1; long[] tot = new long[5]; long totBn = 0, totBk = 0;
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            long[] a = e.getValue();
            for (int i = 0; i < 5; i++) tot[i] += a[i];
            boolean bux = e.getKey().equals("Отдел Основной");
            OwnerType ot = bux ? OwnerType.BUXGALTERIYA : OwnerType.KASSA;
            Long oid = bux ? LedgerService.BUX_ID :
                    kassaRepo.findByActiveTrueOrderByIdAsc().stream()
                            .filter(k -> k.getName().equals(e.getKey()))
                            .findFirst().map(Kassa::getId).orElse(null);
            long bn = oid == null ? 0 : ledger.view(ot, oid, MoneyType.NAQD).getAmount();
            long bk = oid == null ? 0 : ledger.view(ot, oid, MoneyType.KLIK).getAmount();
            totBn += bn; totBk += bk;
            Row row = sh.createRow(r++);
            cell(row, 0, e.getKey(), null);
            num(row, 1, a[0], money); num(row, 2, a[1], money); num(row, 3, a[2], money);
            num(row, 4, a[3], money); num(row, 5, a[4], money);
            num(row, 6, a[0] + a[1] + a[2] - a[3] - a[4], money);
            num(row, 7, bn, money); num(row, 8, bk, money);
        }
        Row t = sh.createRow(r);
        cell(t, 0, "JAMI", bold);
        num(t, 1, tot[0], bold); num(t, 2, tot[1], bold); num(t, 3, tot[2], bold);
        num(t, 4, tot[3], bold); num(t, 5, tot[4], bold);
        num(t, 6, tot[0] + tot[1] + tot[2] - tot[3] - tot[4], bold);
        num(t, 7, totBn, bold); num(t, 8, totBk, bold);
        autos(sh, cols.length);
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
}
