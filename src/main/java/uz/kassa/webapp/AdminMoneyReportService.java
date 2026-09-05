package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import uz.kassa.bot.NameService;
import uz.kassa.bot.Sender;
import uz.kassa.bot.TextUtil;
import uz.kassa.config.AppProps;
import uz.kassa.domain.*;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.repo.OperationRepo;
import uz.kassa.repo.SubmissionRepo;
import uz.kassa.service.AuditService;
import uz.kassa.service.BusinessException;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static uz.kassa.webapp.AdminApiService.mapOf;

/**
 * 💰 Пул ҳаракати ҳисоботи (Mini App): davr + kassa bo'yicha
 *   1) kassirlar TOPSHIRGAN hisobotlar (Submission: kutmoqda / qabul / qisman / rad),
 *   2) buxgalteriya QABUL QILGAN pullar (TOPSHIRIQ operatsiyalari: hisobot orqali yoki bevosita).
 * Excel — ikki varaq, foydalanuvchi chatiga yuboriladi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminMoneyReportService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final SubmissionRepo subRepo;
    private final OperationRepo opRepo;
    private final KassaRepo kassaRepo;
    private final AppUserRepo userRepo;
    private final NameService names;
    private final Sender sender;
    private final AuditService audit;
    private final AppProps props;

    private final Map<Long, String> userNames = new HashMap<>();

    private String user(Long id) {
        if (id == null) return "";
        return userNames.computeIfAbsent(id, i -> userRepo.findById(i).map(AppUser::getFullName).orElse("#" + i));
    }

    private String kassa(Long id) { return id == null ? "" : names.owner(OwnerType.KASSA, id); }

    private static String status(SubmissionStatus s) {
        return switch (s) { case KUTILMOQDA -> "кутмоқда"; case QABUL -> "қабул"; case QISMAN_QABUL -> "қисман"; case RAD -> "рад"; };
    }

    /* ---------------- ma'lumot ---------------- */

    public Map<String, Object> money(LocalDate from, LocalDate to, Long kassaId) {
        if (from.isAfter(to)) { LocalDate x = from; from = to; to = x; }
        if (from.isBefore(to.minusDays(370))) throw new BusinessException("Давр 1 йилдан ошмасин");
        ZoneId z = props.zoneId();
        Instant f = from.atStartOfDay(z).toInstant(), t = to.plusDays(1).atStartOfDay(z).toInstant();
        boolean all = kassaId == null || kassaId == 0;

        // 1) topshirilgan hisobotlar (yaratilgan sanasi davrda)
        List<Map<String, Object>> subs = new ArrayList<>();
        long sNaqd = 0, sKlik = 0, sAccNaqd = 0, sAccKlik = 0; int pending = 0;
        List<Submission> subList = subRepo.findAll().stream()
                .filter(s -> s.getCreatedAt() != null && !s.getCreatedAt().isBefore(f) && s.getCreatedAt().isBefore(t))
                .filter(s -> all || kassaId.equals(s.getKassaId()))
                .sorted(Comparator.comparing(Submission::getId).reversed()).toList();
        for (Submission s : subList) {
            long an = s.getAcceptedNaqd() == null ? 0 : s.getAcceptedNaqd(), ak = s.getAcceptedKlik() == null ? 0 : s.getAcceptedKlik();
            sNaqd += s.getNaqd(); sKlik += s.getKlik(); sAccNaqd += an; sAccKlik += ak;
            if (s.getStatus() == SubmissionStatus.KUTILMOQDA) pending++;
            subs.add(mapOf("id", s.getId(), "kassaId", s.getKassaId(), "kassa", kassa(s.getKassaId()),
                    "kassir", user(s.getSubmittedBy()), "days", s.getDayIds().size(),
                    "naqd", s.getNaqd(), "klik", s.getKlik(), "status", s.getStatus().name(), "statusText", status(s.getStatus()),
                    "accNaqd", an, "accKlik", ak, "by", user(s.getDecidedBy()),
                    "createdAt", DT.format(s.getCreatedAt().atZone(z)),
                    "decidedAt", s.getDecidedAt() == null ? "" : DT.format(s.getDecidedAt().atZone(z)),
                    "comment", s.getComment() == null ? "" : s.getComment()));
        }

        // 2) qabul qilingan pullar (TOPSHIRIQ operatsiyalari, op sanasi davrda)
        List<Map<String, Object>> cols = new ArrayList<>();
        long cNaqd = 0, cTerm = 0; int viaSub = 0, direct = 0;
        Map<Long, long[]> byKassa = new LinkedHashMap<>();
        for (Operation o : opRepo.byPeriod(from, to)) {
            if (o.getType() != OpType.TOPSHIRIQ || o.getStatus() != OpStatus.TASDIQLANGAN) continue;
            if (o.getFromOwnerType() != OwnerType.KASSA) continue;
            if (!all && !kassaId.equals(o.getFromOwnerId())) continue;
            boolean fromSub = o.getSubmissionId() != null;
            if (fromSub) viaSub++; else direct++;
            if (o.getMoneyType() == MoneyType.NAQD) cNaqd += o.getAmount(); else if (o.getMoneyType() == MoneyType.TERMINAL) cTerm += o.getAmount();
            long[] agg = byKassa.computeIfAbsent(o.getFromOwnerId(), k -> new long[3]);
            if (o.getMoneyType() == MoneyType.NAQD) agg[0] += o.getAmount(); else if (o.getMoneyType() == MoneyType.TERMINAL) agg[1] += o.getAmount();
            agg[2]++;
            String who = o.getComment() != null && o.getComment().startsWith("Topshirdi: ") ? o.getComment().substring(11)
                    : user(o.getCreatedBy());
            cols.add(mapOf("id", o.getId(), "date", o.getOpDate().toString(), "kassaId", o.getFromOwnerId(),
                    "kassa", kassa(o.getFromOwnerId()), "mt", o.getMoneyType().name(), "amount", o.getAmount(),
                    "source", fromSub ? "ҳисобот #" + o.getSubmissionId() : "бевосита",
                    "topshirdi", who, "qabulQildi", user(o.getDecidedBy()),
                    "at", o.getDecidedAt() == null ? "" : DT.format(o.getDecidedAt().atZone(z))));
        }
        List<Map<String, Object>> perKassa = new ArrayList<>();
        for (var e : byKassa.entrySet())
            perKassa.add(mapOf("kassaId", e.getKey(), "kassa", kassa(e.getKey()), "naqd", e.getValue()[0], "terminal", e.getValue()[1], "soni", e.getValue()[2]));

        return mapOf("from", from.toString(), "to", to.toString(), "kassaId", all ? 0 : kassaId,
                "submissions", subs, "subTotals", mapOf("soni", subs.size(), "pending", pending, "naqd", sNaqd, "klik", sKlik, "accNaqd", sAccNaqd, "accKlik", sAccKlik),
                "collections", cols, "colTotals", mapOf("soni", cols.size(), "naqd", cNaqd, "terminal", cTerm, "viaSub", viaSub, "direct", direct),
                "perKassa", perKassa);
    }

    /* ---------------- Excel ---------------- */

    public void sendExcel(AppUser u, LocalDate from, LocalDate to, Long kassaId) {
        if (u.getTelegramId() == null) throw new BusinessException("Telegram уланмаган");
        long chat = u.getTelegramId();
        Map<String, Object> data = money(from, to, kassaId);
        String label = (kassaId == null || kassaId == 0 ? "Барча кассалар" : kassa(kassaId)) + " · " + from.format(DF) + " — " + to.format(DF);
        new Thread(() -> {
            try {
                byte[] xlsx = build(data, label);
                sender.sendDocument(chat, xlsx, "pul-harakati_" + from + "_" + to + ".xlsx",
                        "💰 Топширилган ва қабул қилинган пуллар: <b>" + TextUtil.esc(label) + "</b>\nВарақлар: Ҳисоботлар · Қабул қилинган · Касса кесимида");
            } catch (Exception e) {
                log.warn("Pul harakati Excel ({}): {}", chat, e.getMessage());
                sender.send(chat, "⚠️ Excel хатоси: " + TextUtil.esc(e.getMessage()));
            }
        }, "web-money-excel").start();
        audit.log(u.getId(), "EXCEL", "report", null, u.getFullName() + " web: pul harakati " + label);
    }

    @SuppressWarnings("unchecked")
    private byte[] build(Map<String, Object> d, String label) throws Exception {
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

            // 1-varaq: topshirilgan hisobotlar
            Sheet s1 = wb.createSheet("Ҳисоботлар");
            title(s1, 0, "Кассирлар топширган ҳисоботлар — " + label, bold);
            String[] h1 = {"№", "Касса", "Кассир", "Кунлар", "Нақд", "Click", "Ҳолат", "Қабул нақд", "Қабул click", "Ким қабул қилди", "Топширилди", "Қарор", "Изоҳ"};
            header(s1, 2, h1, head);
            int r = 3;
            for (Map<String, Object> s : (List<Map<String, Object>>) d.get("submissions")) {
                Row row = s1.createRow(r++);
                cell(row, 0, String.valueOf(s.get("id"))); cell(row, 1, str(s.get("kassa"))); cell(row, 2, str(s.get("kassir")));
                num(row, 3, ((Number) s.get("days")).longValue(), null);
                num(row, 4, ((Number) s.get("naqd")).longValue(), money); num(row, 5, ((Number) s.get("klik")).longValue(), money);
                cell(row, 6, str(s.get("statusText")));
                num(row, 7, ((Number) s.get("accNaqd")).longValue(), money); num(row, 8, ((Number) s.get("accKlik")).longValue(), money);
                cell(row, 9, str(s.get("by"))); cell(row, 10, str(s.get("createdAt"))); cell(row, 11, str(s.get("decidedAt"))); cell(row, 12, str(s.get("comment")));
            }
            Map<String, Object> st = (Map<String, Object>) d.get("subTotals");
            Row tr = s1.createRow(r + 1);
            cell(tr, 1, "Жами", bold); num(tr, 4, ((Number) st.get("naqd")).longValue(), bold); num(tr, 5, ((Number) st.get("klik")).longValue(), bold);
            num(tr, 7, ((Number) st.get("accNaqd")).longValue(), bold); num(tr, 8, ((Number) st.get("accKlik")).longValue(), bold);
            autos(s1, h1.length);

            // 2-varaq: qabul qilingan pullar
            Sheet s2 = wb.createSheet("Қабул қилинган");
            title(s2, 0, "Бухгалтерия қабул қилган пуллар — " + label, bold);
            String[] h2 = {"Сана", "Касса", "Тур", "Сумма", "Манба", "Топширди", "Қабул қилди", "Вақт"};
            header(s2, 2, h2, head);
            r = 3;
            for (Map<String, Object> c : (List<Map<String, Object>>) d.get("collections")) {
                Row row = s2.createRow(r++);
                cell(row, 0, str(c.get("date"))); cell(row, 1, str(c.get("kassa")));
                cell(row, 2, "NAQD".equals(c.get("mt")) ? "Нақд" : "TERMINAL".equals(c.get("mt")) ? "Терминал" : str(c.get("mt")));
                num(row, 3, ((Number) c.get("amount")).longValue(), money);
                cell(row, 4, str(c.get("source"))); cell(row, 5, str(c.get("topshirdi"))); cell(row, 6, str(c.get("qabulQildi"))); cell(row, 7, str(c.get("at")));
            }
            Map<String, Object> ct = (Map<String, Object>) d.get("colTotals");
            Row tr2 = s2.createRow(r + 1);
            cell(tr2, 1, "Жами нақд", bold); num(tr2, 3, ((Number) ct.get("naqd")).longValue(), bold);
            Row tr3 = s2.createRow(r + 2);
            cell(tr3, 1, "Жами терминал", bold); num(tr3, 3, ((Number) ct.get("terminal")).longValue(), bold);
            autos(s2, h2.length);

            // 3-varaq: kassa kesimida
            Sheet s3 = wb.createSheet("Касса кесимида");
            header(s3, 0, new String[]{"Касса", "Қабул қилинган нақд", "Терминал", "Амаллар сони"}, head);
            r = 1;
            for (Map<String, Object> k : (List<Map<String, Object>>) d.get("perKassa")) {
                Row row = s3.createRow(r++);
                cell(row, 0, str(k.get("kassa"))); num(row, 1, ((Number) k.get("naqd")).longValue(), money);
                num(row, 2, ((Number) k.get("terminal")).longValue(), money); num(row, 3, ((Number) k.get("soni")).longValue(), null);
            }
            autos(s3, 4);
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static void title(Sheet sh, int idx, String text, CellStyle st) { Row r = sh.createRow(idx); Cell c = r.createCell(0); c.setCellValue(text); c.setCellStyle(st); }
    private static void header(Sheet sh, int idx, String[] cols, CellStyle st) { Row r = sh.createRow(idx); for (int i = 0; i < cols.length; i++) { Cell c = r.createCell(i); c.setCellValue(cols[i]); c.setCellStyle(st); } }
    private static void cell(Row r, int i, String v) { r.createCell(i).setCellValue(v); }
    private static void cell(Row r, int i, String v, CellStyle st) { Cell c = r.createCell(i); c.setCellValue(v); c.setCellStyle(st); }
    private static void num(Row r, int i, long v, CellStyle st) { Cell c = r.createCell(i); c.setCellValue(v); if (st != null) c.setCellStyle(st); }
    private static void autos(Sheet sh, int n) { for (int i = 0; i < n; i++) sh.autoSizeColumn(i); }
}
