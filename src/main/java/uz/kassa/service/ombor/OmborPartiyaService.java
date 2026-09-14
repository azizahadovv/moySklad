package uz.kassa.service.ombor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import uz.kassa.domain.Kassa;
import uz.kassa.domain.OmborTovar;
import uz.kassa.repo.OmborTovarRepo;
import uz.kassa.webapp.ExcelReportService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static uz.kassa.bot.TextUtil.esc;
import static uz.kassa.bot.TextUtil.fmt;

/**
 * 📦 Partiya tahlili (2026-09-14 user talabi): tovar do'konga qachon kelgan (priyomka, ko'chirish) va necha kunda sotilib
 * tugagan. Qoldiq hujjatlardan orqaga qarab tiklanadi: bugungi QOLDIQ dan boshlab har kunning oxiridagi qoldiq =
 * keyingi kun qoldig'i − keyingi kun harakati (kirim: supply, move-in, salesreturn, enter; chiqim: demand (barcha statuslar),
 * move-out, loss, purchasereturn). Partiya FIFO bilan tugaydi: kelgan kundan boshlab jami chiqim ≥ (kelishdan oldingi qoldiq + partiya)
 * bo'lgan kun — «tugadi»; oldingi qoldiq manfiy bo'lsa (MoySklad) shuncha dona kelmasdan oldin sotilgan deb olinadi.
 * Manba: ombor_hujjat + ombor_pozitsiya (bir yillik tarix, V37), ombor_korsatkich QOLDIQ.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OmborPartiyaService {

    public static final int ARRIVALS_DAYS = 60, MAX_BATCHES = 6;
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM");
    private static final String RULE_TOP = "━━━━━━━━━━━━━━━━━━━━";

    private final JdbcTemplate jdbc;
    private final OmborTovarRepo tovarRepo;
    private final OmborMetrics metrics;
    private final OmborConfig cfg;
    private final OmborRecipients rec;
    private final OmborChempionService chempion;
    private final ExcelReportService excel;

    /** Kelish: sana, dona, narx (tiyin; move — 0), tur (supply|move), hujjat №, qayerdan (yetkazuvchi yoki ombor). */
    public record Arrival(LocalDate date, BigDecimal qty, long price, String type, String docNo, String from) {}

    /** Partiya: kelish + natija. soldOut null — hali javonda; days — tugagan bo'lsa necha kunda, aks holda kelganiga necha kun; sold — shu partiyadan sotilgan dona. */
    public record Batch(Arrival a, LocalDate soldOut, int days, BigDecimal sold, BigDecimal stockNow, Integer forecastDays) {
        public boolean done() { return soldOut != null; }
        /** Kuniga o'rtacha sotuv (dona). */
        public BigDecimal perDay() { return days <= 0 ? sold : sold.divide(BigDecimal.valueOf(days), 1, RoundingMode.HALF_UP); }
        /** Shu partiyadan qolgani. */
        public BigDecimal left() { return a.qty().subtract(sold).max(BigDecimal.ZERO); }
    }

    /** Tovar × do'kon kartasi. */
    public record Card(String pid, String name, String article, long kassaId, List<Batch> batches, BigDecimal stock, Integer avgDays, int zeroDays30) {
        public Batch last() { return batches.isEmpty() ? null : batches.get(0); }
    }

    /* ==================== hisob ==================== */

    /** Bitta tovar, bitta do'kon: partiyalar (yangi → eski, MAX_BATCHES). null — kelish yo'q. */
    public Card card(long kassaId, String pid) {
        Map<String, Card> m = build(kassaId, pid, LocalDate.now(cfg.zone()).minusDays(cfg.salesDays()));
        return m.get(pid);
    }

    /** Do'kon bo'yicha oxirgi ARRIVALS_DAYS kunda kelgan tovarlar kartalari (kelish sanasi bo'yicha yangi → eski). */
    public List<Card> recent(long kassaId) {
        LocalDate since = LocalDate.now(cfg.zone()).minusDays(ARRIVALS_DAYS);
        List<Card> out = new ArrayList<>();
        for (Card c : build(kassaId, null, LocalDate.now(cfg.zone()).minusDays(cfg.salesDays())).values()) {
            List<Batch> b = c.batches().stream().filter(x -> !x.a().date().isBefore(since)).toList();
            if (b.isEmpty()) continue;
            out.add(new Card(c.pid(), c.name(), c.article(), c.kassaId(), b, c.stock(), c.avgDays(), c.zeroDays30()));
        }
        out.sort(Comparator.comparing((Card c) -> c.last().a().date()).reversed().thenComparing(Card::name));
        return out;
    }

    /** Harakat qatori. */
    private record Mv(String pid, LocalDate d, String type, BigDecimal q, long price, String docNo, String agent, Long src) {}

    private Map<String, Card> build(long kassaId, String onlyPid, LocalDate since) {
        LocalDate today = LocalDate.now(cfg.zone());
        List<Mv> mv = new ArrayList<>();
        List<Object> args = new ArrayList<>(List.of(kassaId, kassaId, kassaId, kassaId, since.atStartOfDay()));
        String pidF = onlyPid == null ? "" : " AND p.product_ms_id = ?";
        if (onlyPid != null) args.add(onlyPid);
        jdbc.query("""
            SELECT p.product_ms_id, h.moment::date, h.type, p.qty, p.price, h.doc_no, h.agent_name, h.kassa_id, h.target_kassa_id
            FROM ombor_hujjat h JOIN ombor_pozitsiya p ON p.hujjat_id = h.id
            WHERE h.deleted = false AND COALESCE(h.applicable, true)
              AND h.type IN ('supply','move','salesreturn','retailsalesreturn','enter','demand','loss','purchasereturn')
              AND ((h.type = 'move' AND (h.kassa_id = ? OR h.target_kassa_id = ?)) OR (h.type <> 'move' AND h.kassa_id = ?))
              AND NOT (h.type = 'move' AND h.kassa_id = h.target_kassa_id AND h.kassa_id = ?)
              AND h.moment >= ?""" + pidF, rs -> {
            String type = rs.getString(3);
            BigDecimal q = rs.getBigDecimal(4);
            Long src = rs.getObject(8) == null ? null : rs.getLong(8), tgt = rs.getObject(9) == null ? null : rs.getLong(9);
            BigDecimal signed = switch (type) {
                case "supply", "salesreturn", "retailsalesreturn", "enter" -> q;
                case "move" -> tgt != null && tgt == kassaId ? q : q.negate();
                default -> q.negate();
            };
            mv.add(new Mv(rs.getString(1), rs.getObject(2, LocalDate.class), type, signed, rs.getLong(5), rs.getString(6), rs.getString(7), src));
        }, args.toArray());
        if (mv.isEmpty()) return Map.of();

        Map<String, BigDecimal> stock = new HashMap<>();
        LocalDate stockDate = metrics.lastDate(OmborMetrics.QOLDIQ);
        if (stockDate == null) stockDate = today;
        for (Map<String, Object> m : metrics.latest(OmborMetrics.QOLDIQ, "kassa_id = ? AND product_ms_id <> ''", kassaId))
            stock.put((String) m.get("product_ms_id"), (BigDecimal) m.get("value"));

        Map<String, List<Mv>> byPid = new HashMap<>();
        for (Mv x : mv) byPid.computeIfAbsent(x.pid(), k -> new ArrayList<>()).add(x);
        Map<String, OmborTovar> tovar = new HashMap<>();
        for (OmborTovar t : tovarRepo.findAllById(byPid.keySet())) tovar.put(t.getMsId(), t);

        Map<String, Card> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Mv>> e : byPid.entrySet()) {
            OmborTovar t = tovar.get(e.getKey());
            if (t != null && t.isArchived()) continue;
            List<Arrival> arrivals = new ArrayList<>();
            TreeMap<LocalDate, BigDecimal> flow = new TreeMap<>();      // kun → net harakat
            TreeMap<LocalDate, BigDecimal> outQ = new TreeMap<>();     // kun → chiqim dona (sotuv, ko'chirish, spisaniya, qaytarish)
            for (Mv x : e.getValue()) {
                flow.merge(x.d(), x.q(), BigDecimal::add);
                if (x.q().signum() < 0) outQ.merge(x.d(), x.q().negate(), BigDecimal::add);
                if (x.type().equals("supply") || (x.type().equals("move") && x.q().signum() > 0))
                    arrivals.add(new Arrival(x.d(), x.q(), x.price(), x.type(), x.docNo(),
                            x.type().equals("supply") ? x.agent() : x.src() == null ? "" : rec.notifier().kassaName(x.src())));
            }
            if (arrivals.isEmpty()) continue;
            // kun oxiridagi qoldiq: stockDate dan orqaga (va oldinga)
            BigDecimal cur = stock.getOrDefault(e.getKey(), BigDecimal.ZERO);
            LocalDate first = flow.firstKey().isBefore(since) ? since : flow.firstKey();
            TreeMap<LocalDate, BigDecimal> end = new TreeMap<>();
            end.put(stockDate, cur);
            BigDecimal s = cur;
            for (LocalDate d = stockDate; d.isAfter(first); d = d.minusDays(1)) { s = s.subtract(flow.getOrDefault(d, BigDecimal.ZERO)); end.put(d.minusDays(1), s); }
            s = cur;
            for (LocalDate d = stockDate.plusDays(1); !d.isAfter(today); d = d.plusDays(1)) { s = s.add(flow.getOrDefault(d, BigDecimal.ZERO)); end.put(d, s); }
            BigDecimal stockNow = end.getOrDefault(today, cur);
            // partiyalar (bir kunda bir nechta kelish — bitta partiya)
            arrivals.sort(Comparator.comparing(Arrival::date));
            List<Arrival> merged = new ArrayList<>();
            for (Arrival a : arrivals) {
                Arrival p = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                if (p != null && p.date().equals(a.date()))
                    merged.set(merged.size() - 1, new Arrival(a.date(), p.qty().add(a.qty()), Math.max(p.price(), a.price()),
                            p.type().equals(a.type()) ? a.type() : "supply", p.docNo(), p.from().isBlank() ? a.from() : p.from()));
                else merged.add(a);
            }
            List<Batch> batches = new ArrayList<>();
            for (Arrival a : merged) {
                // FIFO: avval kelishdan oldingi qoldiq (pre) sotiladi, keyin shu partiya; pre < 0 — shuncha dona allaqachon sotilgan
                BigDecimal pre = end.getOrDefault(a.date().minusDays(1), BigDecimal.ZERO);
                BigDecimal target = a.qty().add(pre);
                LocalDate soldOut = null;
                BigDecimal cum = BigDecimal.ZERO;
                if (target.signum() <= 0) soldOut = a.date();
                else for (LocalDate d = a.date(); !d.isAfter(today); d = d.plusDays(1)) {
                    cum = cum.add(outQ.getOrDefault(d, BigDecimal.ZERO));
                    if (cum.compareTo(target) >= 0) { soldOut = d; break; }
                }
                LocalDate until = soldOut == null ? today : soldOut;
                int days = (int) (until.toEpochDay() - a.date().toEpochDay()) + 1;
                BigDecimal sold = soldOut != null ? a.qty() : cum.subtract(pre).max(BigDecimal.ZERO).min(a.qty());
                BigDecimal left = a.qty().subtract(sold);
                Integer forecast = null;
                if (soldOut == null && sold.signum() > 0 && left.signum() > 0)
                    forecast = left.multiply(BigDecimal.valueOf(days)).divide(sold, 0, RoundingMode.CEILING).intValue();
                batches.add(new Batch(a, soldOut, days, sold, soldOut == null ? left : BigDecimal.ZERO, forecast));
            }
            Collections.reverse(batches);
            if (batches.size() > MAX_BATCHES) batches = new ArrayList<>(batches.subList(0, MAX_BATCHES));
            int n = 0, sum = 0;
            for (Batch b : batches) if (b.done()) { n++; sum += b.days(); }
            int zero = 0;
            for (LocalDate d = today.minusDays(29); !d.isAfter(today); d = d.plusDays(1)) { BigDecimal v = end.get(d); if (v != null && v.signum() <= 0) zero++; }
            out.put(e.getKey(), new Card(e.getKey(), t == null ? e.getKey() : t.getName(), t == null ? "" : t.getArticle(), kassaId, batches, stockNow,
                    n == 0 ? null : Math.round((float) sum / n), zero));
        }
        return out;
    }

    /* ==================== bot matni ==================== */

    /** Bitta partiya qatori. */
    public String batchLine(Batch b) {
        Arrival a = b.a();
        StringBuilder sb = new StringBuilder("• ").append(DF.format(a.date())).append(" ")
                .append(a.type().equals("supply") ? "priyomka" : "ko'chirish").append(a.from().isBlank() ? "" : " (" + esc(a.from()) + ")")
                .append(" <b>").append(qty(a.qty())).append("</b> dona").append(a.price() >= 10_000 ? " · " + fmt(a.price() / 100) : "");   // ko'chirishda nominal narx (5 so'm) ko'rsatilmaydi
        if (b.done()) sb.append(" → ✅ tugadi ").append(DF.format(b.soldOut())).append(" — <b>").append(b.days()).append(" kun</b>, ")
                .append(b.perDay().compareTo(new BigDecimal("0.1")) < 0 ? "&lt;0.1" : qty(b.perDay())).append(" dona/kun");
        else sb.append(" → ⏳ ").append(b.days()).append(" kun: sotildi ").append(qty(b.sold())).append(", qoldi <b>").append(qty(b.stockNow())).append("</b>")
                .append(b.forecastDays() == null ? (b.sold().signum() == 0 ? " · sotilmayapti" : "") : " · ~" + b.forecastDays() + " kunga yetadi");
        return sb.append("\n").toString();
    }

    /** 📦 Tovar partiyalari ekrani (ko'rinadigan do'konlar bo'yicha). */
    public String productScreen(String pid, List<Kassa> stores) {
        OmborTovar t = tovarRepo.findById(pid).orElse(null);
        StringBuilder sb = new StringBuilder("📦 <b>Partiyalar</b> — " + esc(t == null ? pid : t.getName()) + "\n<i>Kelgan tovar necha kunda sotilib tugagan (barcha statuslar); qoldiq hujjatlardan tiklanadi.</i>\n");
        boolean any = false;
        for (Kassa k : stores) {
            Card c = card(k.getId(), pid);
            if (c == null) continue;
            any = true;
            sb.append("\n<b>").append(esc(chempion.storeName(k.getId()))).append("</b> · qoldiq <b>").append(qty(c.stock())).append("</b>")
              .append(c.avgDays() == null ? "" : " · o'rtacha tugash " + c.avgDays() + " kun").append("\n");
            for (Batch b : c.batches()) { if (full(sb)) break; sb.append(batchLine(b)); }
            if (c.zeroDays30() > 0) sb.append("🔴 So'nggi 30 kunda ").append(c.zeroDays30()).append(" kun javonda yo'q edi\n");
        }
        if (!any) sb.append("\nBir yil ichida priyomka yoki ko'chirish bilan kelmagan.");
        return sb.toString();
    }

    /** 📦 Do'kon bo'yicha oxirgi kelganlar (Trend ekrani tabi). */
    public String storeScreen(long kassaId) {
        List<Card> list = recent(kassaId);
        StringBuilder sb = new StringBuilder("📦 <b>Partiyalar</b> — " + esc(chempion.storeName(kassaId)) + " · oxirgi " + ARRIVALS_DAYS + " kunda kelganlar\n"
                + "<i>Kelish → tugash: necha kunda sotilib tugagan, hali borlari qancha kunga yetadi.</i>\n\n");
        if (list.isEmpty()) { sb.append("Bu oynada kelgan tovar yo'q."); return sb.toString(); }
        int done = 0, sumDays = 0, out = 0;
        for (Card c : list) { Batch b = c.last(); if (b.done()) { done++; sumDays += b.days(); } if (c.stock().signum() <= 0) out++; }
        sb.append("Tovarlar: <b>").append(list.size()).append("</b> · tugagan <b>").append(done).append("</b>")
          .append(done > 0 ? " (o'rtacha " + Math.round((float) sumDays / done) + " kunda)" : "").append(" · hozir javonda yo'q <b>").append(out).append("</b>\n")
          .append(RULE_TOP).append("\n");
        int n = 0;
        for (Card c : list) {
            if (++n > 12 || full(sb)) { sb.append("… yana ").append(list.size() - n + 1).append(" ta — 📥 Excel\n"); break; }
            sb.append("<b>").append(esc(c.name())).append("</b>\n").append(batchLine(c.last()).replaceFirst("^• ", "    "));
        }
        sb.append(RULE_TOP).append("\n<i>Tovar kartasidan (📦 Қолдиқ → tovar → 📦 Partiyalar) hamma partiyalar ko'rinadi.</i>");
        return sb.toString();
    }

    /* ==================== Excel ==================== */

    public List<ExcelReportService.SheetDef> excelSheets() {
        List<Object[]> rows = new ArrayList<>();
        for (Kassa k : chempion.stores()) {
            String sn = chempion.storeName(k.getId());
            for (Card c : recent(k.getId()))
                for (Batch b : c.batches())
                    rows.add(new Object[]{rows.size() + 1, sn, c.name(), c.article(), b.a().date(), b.a().type().equals("supply") ? "приёмка" : "перемещение", b.a().docNo(), b.a().from(),
                            b.a().qty(), b.a().price() > 0 ? b.a().price() / 100.0 : null, b.soldOut(), b.days(), b.sold(), b.perDay(), b.done() ? null : b.stockNow(), b.forecastDays(), c.zeroDays30()});
        }
        return List.of(new ExcelReportService.SheetDef("Партиялар", new String[]{"№", "Склад (MoySklad)", "Товар", "Артикул", "Келди", "Тури", "Ҳужжат", "Қаердан",
                "Миқдор", "Нарх (сўм)", "Тугади", "Кун", "Сотилди", "Дона/кун", "Қолдиқ", "Прогноз (кун)", "30 кунда йўқ (кун)"}, rows));
    }

    public byte[] excel() { return excel.buildSheets(excelSheets()); }

    private static boolean full(StringBuilder sb) { return sb.length() > 3400; }
    private static String qty(BigDecimal v) { return v == null ? "0" : v.stripTrailingZeros().toPlainString(); }
}
