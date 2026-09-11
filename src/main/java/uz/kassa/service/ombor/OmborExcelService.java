package uz.kassa.service.ombor;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uz.kassa.domain.*;
import uz.kassa.repo.*;
import uz.kassa.service.ombor.checks.DublikatChecker;
import uz.kassa.service.ombor.checks.KorsatkichChegaraChecker;
import uz.kassa.webapp.ExcelReportService;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 🏬 Kamchiliklar Excel'i — har qoida turi o'z varag'ida, ANIQ FAKTLAR bilan (qiyoslash uchun):
 *   Хулоса · Товарлар (qoldiq, chegara, o'rtacha sotuv, qoplash) · Нарх аномалияси (tannarx ↔ sotuv) · Ҳужжатлар (№, sana, summa,
 *   to'langan, o'tkazilgan, pozitsiyalar) · Санoq (hisob ↔ fakt) · Етказувчи нархи (eski ↔ yangi, %) · Ҳамкорлар · Сўровлар · Қоралама ·
 *   Дубликатлар (juftlik) · Бошқа (umumiy).
 * Summalar so'mda (tiyin / 100).
 */
@Service
@RequiredArgsConstructor
public class OmborExcelService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final ExcelReportService excel;
    private final OmborMetrics metrics;
    private final OmborTovarRepo tovarRepo;
    private final OmborHujjatRepo hujjatRepo;
    private final OmborSanoqRepo sanoqRepo;
    private final OmborSorovRepo sorovRepo;
    private final OmborQoralamaRepo qoralamaRepo;
    private final OmborNarxService narx;
    private final OmborYetkazuvchiRepo supRepo;
    private final OmborRecipients rec;
    private final OmborConfig cfg;
    private final DublikatChecker dublikat;

    public byte[] build(List<OmborKamchilik> issues, Map<String, OmborQoida> rules) {
        ZoneId z = cfg.zone();
        Map<String, List<OmborKamchilik>> byRule = new LinkedHashMap<>();
        for (OmborKamchilik k : issues) byRule.computeIfAbsent(k.getRuleCode(), x -> new ArrayList<>()).add(k);

        List<ExcelReportService.SheetDef> sheets = new ArrayList<>();
        // 0. Хулоса
        List<Object[]> sum = new ArrayList<>();
        for (var e : byRule.entrySet()) { OmborQoida r = rules.get(e.getKey()); sum.add(new Object[]{r == null ? e.getKey() : r.getTitle(), r == null ? "" : sev(r.getSeverity()), e.getValue().size(), sheetOf(e.getKey())}); }
        sheets.add(new ExcelReportService.SheetDef("Хулоса", new String[]{"Қоида", "Даража", "Сони", "Варақ"}, sum));

        List<Object[]> tovar = new ArrayList<>(), narxA = new ArrayList<>(), hujjat = new ArrayList<>(), sanoq = new ArrayList<>(), narxS = new ArrayList<>(),
                hamkor = new ArrayList<>(), sorov = new ArrayList<>(), qoralama = new ArrayList<>(), boshqa = new ArrayList<>();
        boolean dup = false;
        Map<String, Map<Long, BigDecimal>> stock = byProduct(OmborMetrics.QOLDIQ), avg = byProduct(OmborCalcService.ORTACHA_90), rop = byProduct(OmborCalcService.BUYURTMA_NUQTA),
                cover = byProduct(OmborCalcService.QOPLASH_KUN), days = byProduct(OmborMetrics.AYLANMA_KUN), abc = byProduct(OmborCalcService.ABC);

        for (OmborKamchilik k : issues) {
            OmborQoida r = rules.get(k.getRuleCode());
            String title = r == null ? k.getRuleCode() : r.getTitle();
            String reason = plain(k.getDetail());
            String found = DTF.format(k.getSince().atZone(z));
            String kassa = k.getKassaId() == null ? "" : rec.notifier().kassaName(k.getKassaId());
            String owner = k.getOwnerUserId() == null ? "" : rec.notifier().userName(k.getOwnerUserId());
            switch (sheetOf(k.getRuleCode())) {
                case "Дубликатлар" -> dup = true;
                case "Нарх аномалияси" -> {
                    OmborTovar t = product(k.getSubjectKey());
                    if (t == null) { boshqa.add(generic(k, title, kassa, owner, found, reason)); break; }
                    long buy = t.getBuyPrice(), sale = t.getSalePrice();
                    narxA.add(new Object[]{narxA.size() + 1, t.getName(), t.getCode(), t.getArticle(), t.getFolderName(), som(buy), som(sale),
                            buy > 0 ? Math.round((sale - buy) * 1000.0 / buy) / 10.0 : null, sale < buy ? "цена ниже себестоимости" : "наценка выше лимита", found});
                }
                case "Товарлар" -> {
                    OmborTovar t = product(k.getSubjectKey());
                    long ks = k.getKassaId() == null ? 0 : k.getKassaId();
                    String pid = t == null ? "" : t.getMsId();
                    BigDecimal a90 = num(avg, pid, ks == 0 ? 0L : ks);
                    Object stockCol = ks == 0 ? storeBreakdown(stock.get(pid)) : num(stock, pid, ks);
                    tovar.add(new Object[]{tovar.size() + 1, title, ks == 0 ? "барча" : kassa, t == null ? k.getTitle() : t.getName(), t == null ? "" : t.getCode(), t == null ? "" : t.getArticle(), t == null ? "" : t.getFolderName(),
                            stockCol, num(stock, pid, 0L), threshold(k.getRuleCode(), r, t, rop, pid, ks), a90 == null ? 0 : a90,
                            a90 == null || a90.signum() == 0 ? "∞ (сотув йўқ)" : num(cover, pid, ks), num(days, pid, 0L),
                            abcOf(abc, pid), t == null || t.getMinBalance().signum() == 0 ? "—" : t.getMinBalance(), t == null ? null : som(t.getBuyPrice()), t == null ? null : som(t.getSalePrice()), reason, found});
                }
                case "Ҳужжатлар" -> {
                    OmborHujjat h = hujjatRepo.findByMsId(k.getSubjectKey()).orElse(null);
                    if (h == null) { boshqa.add(generic(k, title, kassa, owner, found, reason)); break; }
                    hujjat.add(new Object[]{hujjat.size() + 1, title, OmborHujjat.typeTitle(h.getType()), h.getDocNo(), h.getMoment() == null ? "" : h.getMoment().toLocalDate().toString(),
                            kassa, h.getAgentName(), som(h.getSum()), som(h.getPayedSum()), h.getApplicable() == null ? "—" : (h.getApplicable() ? "да" : "НЕТ"),
                            h.getPositionsN(), h.getCorrectionsN(), h.getOwnerName().isBlank() ? owner : h.getOwnerName(), h.getState(), ageDays(h), reason, found, h.url()});
                }
                case "Санoq" -> {
                    if (k.getSubjectKey().startsWith("kassa:")) {
                        long ks = Long.parseLong(k.getSubjectKey().substring(6));
                        for (OmborSanoq s : sanoqRepo.findByKassaIdAndStatusNotAndPlanDateLessThanEqualOrderByPlanDateAscIdAsc(ks, "TASDIQ", java.time.LocalDate.now(z)))
                            sanoq.add(sanoqRow(sanoq.size() + 1, title, s));
                    } else sanoqRepo.findById(Long.parseLong(k.getSubjectKey())).ifPresent(s -> sanoq.add(sanoqRow(sanoq.size() + 1, title, s)));
                }
                case "Етказувчи нархи" -> {
                    String[] p = k.getSubjectKey().split("\\|");
                    OmborTovar t = p.length > 1 ? tovarRepo.findById(p[1]).orElse(null) : null;
                    OmborNarx[] two = p.length > 1 ? narx.lastTwo(p[0], p[1]) : new OmborNarx[2];
                    String sup = supRepo.findById(p[0]).map(OmborYetkazuvchi::getName).orElse(p[0]);
                    Long chg = two[0] != null && two[1] != null && two[1].getPrice() > 0 ? Math.round((two[0].getPrice() - two[1].getPrice()) * 1000.0 / two[1].getPrice()) : null;
                    narxS.add(new Object[]{narxS.size() + 1, t == null ? k.getTitle() : t.getName(), t == null ? "" : t.getCode(), t == null ? "" : t.getArticle(), sup,
                            two[1] == null ? null : som(two[1].getPrice()), two[1] == null ? "" : two[1].getAtDate().toString(), two[0] == null ? null : som(two[0].getPrice()),
                            two[0] == null ? "" : two[0].getAtDate().toString(), two[0] == null ? "" : two[0].getSource().toLowerCase(), chg == null ? null : chg / 10.0,
                            k.getRuleCode().equals("NARX_OSHDI") ? "↑ ошди" : "↓ тушди", found});
                }
                case "Ҳамкорлар" -> {
                    String key = k.getSubjectKey();
                    String agent = key.contains("|") ? key.substring(0, key.indexOf('|')) : key, pid = key.contains("|") ? key.substring(key.indexOf('|') + 1) : "";
                    OmborTovar t = pid.isBlank() ? null : tovarRepo.findById(pid).orElse(null);
                    BigDecimal debt = firstVal("HAMKOR_QARZ", agent), interval = pid.isBlank() ? null : firstVal("HAMKOR_INTERVAL", key);
                    hamkor.add(new Object[]{hamkor.size() + 1, title, KorsatkichChegaraChecker.hamkorNames.getOrDefault(agent, agent), t == null ? "" : t.getName(), t == null ? "" : t.getCode(),
                            debt, interval, reason, found});
                }
                case "Сўровлар" -> sorovRepo.findById(Long.parseLong(k.getSubjectKey())).ifPresent(s -> sorov.add(new Object[]{sorov.size() + 1, s.getId(),
                        s.getKassaId() == null ? "" : rec.notifier().kassaName(s.getKassaId()),
                        s.getProductMsId() != null ? tovarRepo.findById(s.getProductMsId()).map(OmborTovar::getName).orElse(s.getProductMsId()) : "🆕 " + s.getText(),
                        s.getQty(), OmborSorov.reasonTitle(s.getReason()), OmborSorov.statusTitle(s.getStatus()), rec.notifier().userName(s.getByUserId()), DTF.format(s.getCreatedAt().atZone(z)), s.getAnswer()}));
                case "Қоралама" -> qoralamaRepo.findById(Long.parseLong(k.getSubjectKey())).ifPresent(q -> qoralama.add(new Object[]{qoralama.size() + 1, q.getId(),
                        q.getKassaId() == null ? "" : rec.notifier().kassaName(q.getKassaId()), q.getAgentName(), OmborQoralama.statusTitle(q.getStatus()), som(q.getTotal()), q.getCashAvailable(),
                        DTF.format(q.getUpdatedAt().atZone(z)), q.getNote()}));
                default -> boshqa.add(generic(k, title, kassa, owner, found, reason));
            }
        }
        if (!tovar.isEmpty()) sheets.add(new ExcelReportService.SheetDef("Товарлар", new String[]{"№", "Қоида", "Дўкон", "Товар", "Код", "Артикул", "Группа",
                "Остаток (дўкон / кесим)", "Остаток (жами)", "Чегара / ROP", "Ўртача сотув/кун (90)", "Қоплаш (кун)", "Ҳаракатсиз (кун)", "ABC", "Мин. остаток (карточка)",
                "Себестоимость (сўм)", "Цена продажи (сўм)", "Причина", "Топилди"}, tovar));
        if (!narxA.isEmpty()) sheets.add(new ExcelReportService.SheetDef("Нарх аномалияси", new String[]{"№", "Товар", "Код", "Артикул", "Группа", "Себестоимость (сўм)",
                "Цена продажи (сўм)", "Наценка %", "Причина", "Топилди"}, narxA));
        if (!hujjat.isEmpty()) sheets.add(new ExcelReportService.SheetDef("Ҳужжатлар", new String[]{"№", "Қоида", "Ҳужжат тури", "№ ҳужжат", "Сана", "Дўкон", "Контрагент",
                "Сумма (сўм)", "Тўланган (сўм)", "Проведён", "Позиций", "Фарқли позиций", "Ходим", "Статус (MoySklad)", "Ёши (кун)", "Причина", "Топилди", "MoySklad"}, hujjat));
        if (!sanoq.isEmpty()) sheets.add(new ExcelReportService.SheetDef("Санoq", new String[]{"№", "Қоида", "Дўкон", "Товар", "Код", "ABC", "Ҳисобда", "Фактда", "Фарқ",
                "Режа санаси", "Статус", "Ким"}, sanoq));
        if (!narxS.isEmpty()) sheets.add(new ExcelReportService.SheetDef("Етказувчи нархи", new String[]{"№", "Товар", "Код", "Артикул", "Етказувчи", "Олдинги нарх (сўм)",
                "Олдинги сана", "Янги нарх (сўм)", "Янги сана", "Манба", "Ўзгариш %", "Йўналиш", "Топилди"}, narxS));
        if (!hamkor.isEmpty()) sheets.add(new ExcelReportService.SheetDef("Ҳамкорлар", new String[]{"№", "Қоида", "Ҳамкор", "Товар", "Код", "Қарз (сўм)", "Интервал ўтди (кун)", "Причина", "Топилди"}, hamkor));
        if (!sorov.isEmpty()) sheets.add(new ExcelReportService.SheetDef("Сўровлар", new String[]{"№", "ID", "Дўкон", "Товар / матн", "Миқдор", "Сабаб", "Ҳолат", "Ким", "Сана", "Жавоб"}, sorov));
        if (!qoralama.isEmpty()) sheets.add(new ExcelReportService.SheetDef("Қоралама", new String[]{"№", "ID", "Дўкон", "Етказувчи", "Ҳолат", "Сумма (сўм)", "Мавжуд пул (сўм)", "Янгиланган", "Изоҳ"}, qoralama));
        if (dup) {
            List<Object[]> rows = new ArrayList<>();
            for (DublikatChecker.Pair pr : dublikat.pairs("name,barcode,article"))
                rows.add(new Object[]{rows.size() + 1, pr.a().getName(), pr.a().getCode(), pr.a().getArticle(), num(stock, pr.a().getMsId(), 0L), pr.a().getFolderName(),
                        pr.b().getName(), pr.b().getCode(), pr.b().getArticle(), num(stock, pr.b().getMsId(), 0L), pr.b().getFolderName(), pr.reason(), "Дубликат"});
            sheets.add(new ExcelReportService.SheetDef("Дубликатлар", new String[]{"№", "Название 1", "Код 1", "Артикул 1", "Остаток 1", "Группа 1",
                    "Название 2", "Код 2", "Артикул 2", "Остаток 2", "Группа 2", "Причина совпадения", "Статус"}, rows));
        }
        if (!boshqa.isEmpty()) sheets.add(new ExcelReportService.SheetDef("Бошқа", new String[]{"№", "Қоида", "Сарлавҳа", "Дўкон", "Ходим", "Топилди", "Тафсилот"}, boshqa));
        return excel.buildSheets(sheets);
    }

    /** Qoida → varaq. */
    public static String sheetOf(String rule) {
        return switch (rule) {
            case "TOVAR_DUBLIKAT" -> "Дубликатлар";
            case "NARX_ANOMAL" -> "Нарх аномалияси";
            case "QOLDIQ_MANFIY", "QOLDIQ_MIN", "BUYURTMA_NUQTA", "NELIKVID", "KOCHIRISH" -> "Товарлар";
            case "HARAKAT_OTKAZILMAGAN", "QABUL_FARQ", "INVENT_FARQ", "QAYTARISH_OSILDI", "MIJOZ_QAYTARISH", "SPISANIYA_TASDIQ" -> "Ҳужжатлар";
            case "SANOQ_FARQ", "SANOQ_TASDIQLANMAGAN" -> "Санoq";
            case "NARX_OSHDI", "NARX_TUSHDI" -> "Етказувчи нархи";
            case "HAMKOR_QARZ", "HAMKOR_INTERVAL" -> "Ҳамкорлар";
            case "SOROV_JAVOBSIZ" -> "Сўровлар";
            case "QORALAMA_KUTMOQDA" -> "Қоралама";
            default -> "Бошқа";
        };
    }

    private Object[] sanoqRow(int n, String title, OmborSanoq s) {
        OmborTovar t = tovarRepo.findById(s.getProductMsId()).orElse(null);
        return new Object[]{n, title, rec.notifier().kassaName(s.getKassaId()), t == null ? s.getProductMsId() : t.getName(), t == null ? "" : t.getCode(), s.getAbc(),
                s.getSystemQty(), s.getFactQty(), s.getFactQty() == null ? null : s.getFactQty().subtract(s.getSystemQty()), s.getPlanDate().toString(), s.getStatus(),
                s.getByUserId() == null ? "" : rec.notifier().userName(s.getByUserId())};
    }

    private static Object[] generic(OmborKamchilik k, String title, String kassa, String owner, String found, String reason) {
        return new Object[]{0, title, k.getTitle(), kassa, owner, found, reason};
    }

    private Object threshold(String rule, OmborQoida r, OmborTovar t, Map<String, Map<Long, BigDecimal>> rop, String pid, long ks) {
        return switch (rule) {
            case "QOLDIQ_MANFIY" -> 0;
            case "QOLDIQ_MIN" -> t == null ? null : t.getMinBalance();
            case "BUYURTMA_NUQTA", "KOCHIRISH" -> { BigDecimal v = num(rop, pid, ks); yield v == null ? 0 : v; }
            case "NELIKVID" -> r == null ? "90 кун" : paramOf(r, "threshold", "90") + " кун ҳаракатсиз";
            default -> null;
        };
    }

    private static String paramOf(OmborQoida r, String key, String def) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.getParams() == null ? "{}" : r.getParams()).path(key).asText(def); }
        catch (Exception e) { return def; }
    }

    /** Kompaniya darajasidagi qator uchun do'konlar kesimi: «Зуфар 2, Шохрух 3». */
    private String storeBreakdown(Map<Long, BigDecimal> byKassa) {
        if (byKassa == null) return "";
        List<String> parts = new ArrayList<>();
        for (var e : new java.util.TreeMap<>(byKassa).entrySet()) {
            if (e.getKey() == 0 || e.getValue().signum() == 0) continue;
            parts.add(rec.notifier().kassaName(e.getKey()).replace("Отдел ", "") + " " + e.getValue().stripTrailingZeros().toPlainString());
        }
        return String.join(", ", parts);
    }

    private OmborTovar product(String subjectKey) {
        Matcher m = UUID.matcher(subjectKey);
        return m.find() ? tovarRepo.findById(m.group()).orElse(null) : null;
    }

    private Map<String, Map<Long, BigDecimal>> byProduct(String code) {
        Map<String, Map<Long, BigDecimal>> out = new HashMap<>();
        for (Map<String, Object> m : metrics.latest(code, "product_ms_id <> ''"))
            out.computeIfAbsent((String) m.get("product_ms_id"), k -> new HashMap<>()).put(((Number) m.get("kassa_id")).longValue(), (BigDecimal) m.get("value"));
        return out;
    }

    private static BigDecimal num(Map<String, Map<Long, BigDecimal>> m, String pid, long kassa) { Map<Long, BigDecimal> x = m.get(pid); return x == null ? null : x.get(kassa); }
    private static String abcOf(Map<String, Map<Long, BigDecimal>> abc, String pid) { BigDecimal v = num(abc, pid, 0L); return v == null ? "C (сотув йўқ)" : v.intValue() == 1 ? "A" : v.intValue() == 2 ? "B" : "C"; }
    private BigDecimal firstVal(String code, String key) { var l = metrics.latest(code, "product_ms_id = ?", key); return l.isEmpty() ? null : (BigDecimal) l.get(0).get("value"); }
    private static Long ageDays(OmborHujjat h) { return h.getMoment() == null ? null : java.time.temporal.ChronoUnit.DAYS.between(h.getMoment().toLocalDate(), java.time.LocalDate.now()); }
    private static double som(long tiyin) { return tiyin / 100.0; }
    private static String plain(String html) { return html.replaceAll("<[^>]+>", "").replace("\n", " | "); }
    private static String sev(String s) { return switch (s) { case "MUHIM" -> "🔴 муҳим"; case "OGOH" -> "🟠 огоҳ"; case "INFO" -> "ℹ️ инфо"; default -> s; }; }
}
