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
 * 📈 Тренд (2026-09-14): naqd sotuv (SOTUV_*) bo'yicha har tovar uchun o'sish/kamayish va narx oraliqlari, do'kon kesimida.
 * <ul>
 *   <li>Hajm: oxirgi 7 kun ↔ oldingi 7 kun, oxirgi 30 ↔ oldingi 30 (dona, o'sish %).</li>
 *   <li>Narx: haftalik o'rtacha naqd narx (tushum ÷ dona) 12 hafta; ketma-ket haftalar ±{@value #SEG_TOL}% ichida bo'lsa bitta
 *       oraliq («26.08–08.09: 700 000»), oraliq almashganda ↑/↓ va foiz; 30 kunlik min/max.</li>
 * </ul>
 * Ko'rinish: bot 🏬 Омбор → 📈 Тренд (do'kon, 7/30 kun), haftalik sanoq Excel'ida «Тренд» varag'i.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OmborTrendService {

    public static final int WEEKS = 12, SEG_TOL = 3, MIN_QTY = 3, PRICE_MIN_PCT = 5, TOP = 10;
    /** Ekran tablari: o — o'sish (+yangi), k — kamayish, n — narx, p — partiya (OmborPartiyaService). */
    public static final String TAB_UP = "o", TAB_DOWN = "k", TAB_PRICE = "n", TAB_BATCH = "p";
    public static String tabOf(String s) { return s == null || s.isBlank() ? TAB_UP : s; }
    public static String tabTitle(String t) { return switch (t) { case TAB_DOWN -> "📉 Kamayish"; case TAB_PRICE -> "💵 Narx"; case TAB_BATCH -> "📦 Partiya"; default -> "📈 O'sish"; }; }
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM");
    private static final String RULE_TOP = "━━━━━━━━━━━━━━━━━━━━";

    private final JdbcTemplate jdbc;
    private final OmborTovarRepo tovarRepo;
    private final OmborMetrics metrics;
    private final OmborConfig cfg;
    private final OmborSalesService sales;
    private final OmborChempionService chempion;
    private final ExcelReportService excel;

    /** Narx oralig'i: haftalar [fromWeek..toWeek] (0 — joriy hafta), o'rtacha narx (tiyin). */
    public record Seg(LocalDate from, LocalDate to, long price) {}

    /** Bitta tovar. Narxlar tiyin; price/prevPrice null — sotuv yo'q. */
    public record Item(String pid, String name, String article, BigDecimal q7, BigDecimal p7, BigDecimal q30, BigDecimal p30,
                       Long price, Long prevPrice, List<Seg> segs, Long min30, Long max30, BigDecimal stock) {
        public Integer growth(int win) { return pct(win == 7 ? q7 : q30, win == 7 ? p7 : p30); }
        public BigDecimal q(int win) { return win == 7 ? q7 : q30; }
        public BigDecimal p(int win) { return win == 7 ? p7 : p30; }
        /** Narx o'zgarishi % (oxirgi oraliq ↔ oldingi), null — bitta oraliq. */
        public Integer priceChange() { return price == null || prevPrice == null || prevPrice == 0 ? null : (int) Math.round(100.0 * (price - prevPrice) / prevPrice); }
        static Integer pct(BigDecimal q, BigDecimal p) {
            if (p == null || p.signum() <= 0) return null;
            return (int) Math.round(100.0 * (q.doubleValue() - p.doubleValue()) / p.doubleValue());
        }
    }

    /* ==================== hisob ==================== */

    /** Do'kon (yoki 0 — kompaniya) bo'yicha barcha faol tovarlar (84 kunda naqd sotilgan). */
    public List<Item> list(long kassaId) {
        LocalDate today = LocalDate.now(cfg.zone());
        LocalDate from = today.minusDays(WEEKS * 7L - 1);
        // pid → date → [qty, summa]
        Map<String, TreeMap<LocalDate, BigDecimal[]>> daily = new HashMap<>();
        jdbc.query("""
            SELECT product_ms_id, date, sum(value) FILTER (WHERE code = 'SOTUV_MIQDOR') q, sum(value) FILTER (WHERE code = 'SOTUV_SUMMA') s
            FROM ombor_korsatkich WHERE kassa_id = ? AND date >= ? AND product_ms_id <> '' AND code IN ('SOTUV_MIQDOR','SOTUV_SUMMA')
            GROUP BY product_ms_id, date
            """, rs -> {
            BigDecimal q = rs.getBigDecimal(3), s = rs.getBigDecimal(4);
            if (q == null || q.signum() <= 0) return;
            daily.computeIfAbsent(rs.getString(1), k -> new TreeMap<>()).put(rs.getObject(2, LocalDate.class), new BigDecimal[]{q, s == null ? BigDecimal.ZERO : s});
        }, kassaId, from);
        if (daily.isEmpty()) return List.of();
        Map<String, OmborTovar> tovar = new HashMap<>();
        for (OmborTovar t : tovarRepo.findAllById(daily.keySet())) tovar.put(t.getMsId(), t);
        Map<String, BigDecimal> stock = new HashMap<>();
        for (Map<String, Object> m : metrics.latest(OmborMetrics.QOLDIQ, "kassa_id = ? AND product_ms_id <> ''", kassaId))
            stock.put((String) m.get("product_ms_id"), (BigDecimal) m.get("value"));

        List<Item> out = new ArrayList<>();
        for (Map.Entry<String, TreeMap<LocalDate, BigDecimal[]>> e : daily.entrySet()) {
            OmborTovar t = tovar.get(e.getKey());
            if (t != null && t.isArchived()) continue;
            BigDecimal q7 = BigDecimal.ZERO, p7 = BigDecimal.ZERO, q30 = BigDecimal.ZERO, p30 = BigDecimal.ZERO;
            BigDecimal[] wq = new BigDecimal[WEEKS], ws = new BigDecimal[WEEKS];
            Arrays.fill(wq, BigDecimal.ZERO); Arrays.fill(ws, BigDecimal.ZERO);
            Long min30 = null, max30 = null;
            for (Map.Entry<LocalDate, BigDecimal[]> de : e.getValue().entrySet()) {
                long age = today.toEpochDay() - de.getKey().toEpochDay();   // 0 — bugun
                BigDecimal q = de.getValue()[0], s = de.getValue()[1];
                if (age < 7) q7 = q7.add(q); else if (age < 14) p7 = p7.add(q);
                if (age < 30) {
                    q30 = q30.add(q);
                    long unit = s.divide(q, 0, RoundingMode.HALF_UP).longValue();
                    if (unit > 0) { min30 = min30 == null ? unit : Math.min(min30, unit); max30 = max30 == null ? unit : Math.max(max30, unit); }
                } else if (age < 60) p30 = p30.add(q);
                int w = (int) (age / 7);
                if (w < WEEKS) { wq[w] = wq[w].add(q); ws[w] = ws[w].add(s); }
            }
            List<Seg> segs = segments(today, wq, ws);
            Long price = segs.isEmpty() ? null : segs.get(segs.size() - 1).price();
            Long prev = segs.size() < 2 ? null : segs.get(segs.size() - 2).price();
            out.add(new Item(e.getKey(), t == null ? e.getKey() : t.getName(), t == null ? "" : t.getArticle(), q7, p7, q30, p30,
                    price, prev, segs, min30, max30, stock.getOrDefault(e.getKey(), BigDecimal.ZERO)));
        }
        return out;
    }

    /** Haftalik o'rtacha narxlar → oraliqlar (eskidan yangiga). Bo'sh haftalar tashlab ketiladi, ±SEG_TOL% ichidagi haftalar birlashadi. */
    static List<Seg> segments(LocalDate today, BigDecimal[] wq, BigDecimal[] ws) {
        List<Seg> out = new ArrayList<>();
        long segQty = 0; BigDecimal segSum = BigDecimal.ZERO; LocalDate segFrom = null, segTo = null; long segPrice = 0;
        for (int w = wq.length - 1; w >= 0; w--) {
            if (wq[w].signum() <= 0) continue;
            long unit = ws[w].divide(wq[w], 0, RoundingMode.HALF_UP).longValue();
            LocalDate wFrom = today.minusDays(7L * w + 6), wTo = today.minusDays(7L * w);
            if (segFrom != null && Math.abs(unit - segPrice) * 100.0 / Math.max(1, segPrice) <= SEG_TOL) {
                segTo = wTo; segSum = segSum.add(ws[w]); segQty += wq[w].longValue();
                segPrice = segQty == 0 ? unit : segSum.divide(BigDecimal.valueOf(segQty), 0, RoundingMode.HALF_UP).longValue();
                continue;
            }
            if (segFrom != null) out.add(new Seg(segFrom, segTo, segPrice));
            segFrom = wFrom; segTo = wTo; segSum = ws[w]; segQty = wq[w].longValue(); segPrice = unit;
        }
        if (segFrom != null) out.add(new Seg(segFrom, segTo, segPrice));
        return out;
    }

    /* ==================== tanlovlar ==================== */

    /** 📈 O'sayotganlar: oldingi davr > 0, hozir ko'proq (kamida MIN_QTY dona); dona farqi bo'yicha kamayib (1→3 kabi shovqin oxirida), keyin %. */
    public List<Item> rising(List<Item> all, int win) {
        return all.stream().filter(i -> i.p(win).signum() > 0 && i.q(win).compareTo(i.p(win)) > 0 && i.q(win).intValue() >= MIN_QTY)
                .sorted(Comparator.comparing((Item i) -> i.q(win).subtract(i.p(win))).reversed().thenComparing(i -> i.growth(win), Comparator.reverseOrder())).toList();
    }

    /** 🆕 Yangi: oldingi davr 0, hozir MIN_QTY dan ko'p. */
    public List<Item> fresh(List<Item> all, int win) {
        return all.stream().filter(i -> i.p(win).signum() == 0 && i.q(win).intValue() >= MIN_QTY)
                .sorted(Comparator.comparing((Item i) -> i.q(win)).reversed()).toList();
    }

    /** 📉 So'nayotganlar: oldingi davr ≥ MIN_QTY, hozir kam; dona farqi bo'yicha kamayib, keyin kamayish % (to'xtaganlar oldinda). */
    public List<Item> falling(List<Item> all, int win) {
        return all.stream().filter(i -> i.p(win).intValue() >= MIN_QTY && i.q(win).compareTo(i.p(win)) < 0)
                .sorted(Comparator.comparing((Item i) -> i.p(win).subtract(i.q(win))).reversed().thenComparing(i -> i.growth(win))).toList();
    }

    /** 💵 Narxi o'zgarganlar: oxirgi oraliq oldingisidan ≥ PRICE_MIN_PCT % farq qiladi, |farq| bo'yicha. */
    public List<Item> priceChanged(List<Item> all) {
        return all.stream().filter(i -> i.priceChange() != null && Math.abs(i.priceChange()) >= PRICE_MIN_PCT)
                .sorted(Comparator.comparing((Item i) -> Math.abs(i.priceChange())).reversed().thenComparing(i -> i.q30, Comparator.reverseOrder())).toList();
    }

    /* ==================== bot matni ==================== */

    /** 📈 Тренд ekrani: do'kon, oyna 7 yoki 30 kun, tab (o/k/n; p — {@link OmborPartiyaService#storeScreen}). Tovar nomlari to'liq (2026-09-14 user talabi). */
    public String screen(long kassaId, int win, String tab) {
        tab = tabOf(tab);
        List<Item> all = list(kassaId);
        StringBuilder sb = new StringBuilder("📈 <b>Trend</b> — " + esc(chempion.storeName(kassaId)) + " · " + tabTitle(tab)
                + (tab.equals(TAB_PRICE) ? " · " + WEEKS + " hafta" : " · oxirgi <b>" + win + "</b> kun ↔ oldingi " + win) + "\n"
                + "<i>Faqat naqd savdo (" + esc(String.join(", ", cfg.naqdStates())) + ")" + chegaraText() + "</i>\n\n");
        if (all.isEmpty()) {
            sb.append(sales.naqdReady() ? "Bu oynada naqd sotuv yo'q." : "⏳ Otgruzka tarixi yuklanmoqda — birinchi hisob tugagach ko'rinadi.");
            return sb.toString();
        }
        BigDecimal q = BigDecimal.ZERO, p = BigDecimal.ZERO;
        for (Item i : all) { q = q.add(i.q(win)); p = p.add(i.p(win)); }
        Integer g = Item.pct(q, p);
        if (!tab.equals(TAB_PRICE))
            sb.append("Jami naqd: <b>").append(qty(q)).append("</b> dona (oldingi ").append(qty(p)).append(")").append(g == null ? "" : " · " + arrow(g) + " " + sign(g) + "%").append("\n");
        sb.append(RULE_TOP).append("\n");
        int n = 0;
        switch (tab) {
            case TAB_DOWN -> {
                List<Item> down = falling(all, win);
                sb.append("📉 <b>So'nayotganlar</b> (").append(down.size()).append(" ta)").append(down.isEmpty() ? " — yo'q\n" : "\n");
                for (Item i : down) { if (n++ >= TOP || full(sb)) { sb.append("… yana ").append(down.size() - n + 1).append(" ta — 📥 Excel\n"); break; } sb.append(volLine(i, win)); }
            }
            case TAB_PRICE -> {
                List<Item> pr = priceChanged(all);
                sb.append("💵 <b>Naqd narxi o'zgarganlar</b> (").append(pr.size()).append(" ta)").append(pr.isEmpty() ? " — yo'q\n" : "\n");
                for (Item i : pr) {
                    if (n++ >= TOP || full(sb)) { sb.append("… yana ").append(pr.size() - n + 1).append(" ta — 📥 Excel\n"); break; }
                    List<Seg> s = i.segs();
                    Seg last = s.get(s.size() - 1), prev = s.get(s.size() - 2);
                    sb.append(arrow(i.priceChange())).append(" <b>").append(esc(i.name())).append("</b>\n    ")
                      .append(DF.format(prev.from())).append("–").append(DF.format(prev.to())).append(": ").append(fmt(prev.price() / 100)).append(" → ")
                      .append(DF.format(last.from())).append("–").append(DF.format(last.to())).append(": <b>").append(fmt(last.price() / 100)).append("</b> so'm (")
                      .append(sign(i.priceChange())).append("%)");
                    if (i.min30() != null && i.max30() != null && !i.min30().equals(i.max30())) sb.append(" · 30 kun: ").append(fmt(i.min30() / 100)).append("–").append(fmt(i.max30() / 100));
                    sb.append("\n");
                }
            }
            default -> {
                List<Item> up = rising(all, win), fr = fresh(all, win);
                sb.append("📈 <b>O'sayotganlar</b> (").append(up.size()).append(" ta)").append(up.isEmpty() ? " — yo'q\n" : "\n");
                for (Item i : up) { if (n++ >= TOP || full(sb)) { sb.append("… yana ").append(up.size() - n + 1).append(" ta — 📥 Excel\n"); break; } sb.append(volLine(i, win)); }
                if (!fr.isEmpty()) {
                    sb.append("\n🆕 <b>Yangi sotila boshlaganlar</b> (").append(fr.size()).append(" ta)\n");
                    n = 0;
                    for (Item i : fr) { if (n++ >= 5 || full(sb)) { sb.append("… yana ").append(fr.size() - n + 1).append(" ta — 📥 Excel\n"); break; }
                        sb.append("🆕 <b>").append(esc(i.name())).append("</b>\n    ").append(qty(i.q(win))).append(" dona (oldingi ").append(win).append(" kunda sotilmagan)").append(price(i)).append(" · qoldiq ").append(qty(i.stock())).append("\n"); }
                }
            }
        }
        sb.append(RULE_TOP).append("\n<i>").append(tab.equals(TAB_PRICE)
                ? "Narx — haftalik o'rtacha naqd narx (tushum ÷ dona), ±" + SEG_TOL + "% ichida bitta oraliq; ≥" + PRICE_MIN_PCT + "% o'zgarganlar."
                : "Hajm — dona, kamida " + MIN_QTY + " dona; tartib — dona farqi bo'yicha.")
          .append(" Manba — MoySklad otgruzka hujjatlari (status va summa bo'yicha filtrlangan).</i>");
        return sb.toString();
    }

    private String volLine(Item i, int win) {
        Integer g = i.growth(win);
        return arrow(g) + " <b>" + esc(i.name()) + "</b>\n    " + qty(i.p(win)) + " → <b>" + qty(i.q(win)) + "</b> dona"
                + (g == null ? "" : " (" + sign(g) + "%)") + (i.q(win).signum() == 0 ? " · to'xtadi" : "") + price(i)
                + " · qoldiq " + qty(i.stock()) + "\n";
    }

    private static String price(Item i) { return i.price() == null ? "" : " · narx " + fmt(i.price() / 100); }

    /** 🚫 Chiqarilgan hujjatlar ekrani (30 kun): bank statusi yoki chegaradan katta. */
    public String excludedScreen(long kassaId) {
        LocalDate from = LocalDate.now(cfg.zone()).minusDays(OmborChempionService.KUN);
        long[] st = sales.excludedStats(kassaId, from);
        StringBuilder sb = new StringBuilder("🚫 <b>Tahlilga kirmagan otgruzkalar</b> — " + esc(chempion.storeName(kassaId)) + " · " + OmborChempionService.KUN + " kun\n\n");
        sb.append("✅ Naqd: <b>").append(st[0]).append("</b> ta · ").append(OmborChempionService.pul(st[3])).append("\n");
        sb.append("🏦 Bank statusi: <b>").append(st[1]).append("</b> ta · ").append(OmborChempionService.pul(st[4])).append("\n");
        sb.append("📏 Chegaradan katta: <b>").append(st[2]).append("</b> ta · ").append(OmborChempionService.pul(st[5])).append("\n");
        List<OmborSalesService.Excluded> list = sales.excluded(kassaId, from, 15);
        if (!list.isEmpty()) {
            sb.append(RULE_TOP).append("\n");
            for (OmborSalesService.Excluded x : list)
                sb.append(x.reason().equals("bank") ? "🏦 " : "📏 ").append(x.moment() == null ? "" : DF.format(x.moment())).append(" №").append(esc(x.docNo())).append(" · ")
                  .append(esc(x.agentName())).append(" · <b>").append(OmborChempionService.pul(x.sum())).append("</b> · ").append(esc(x.state())).append("\n");
        }
        sb.append(RULE_TOP).append("\n<i>Naqd statuslar: ").append(esc(String.join(", ", cfg.naqdStates()))).append(chegaraText())
          .append(". Sozlash: ⚙️ Настройка → 🔗 MoySklad → 🏬 Омбор назорати.</i>");
        return sb.toString();
    }

    private String chegaraText() {
        long m = cfg.sotuvMaxHujjat();
        return m == 0 ? "" : "; bitta hujjat ≤ " + OmborChempionService.pul(m * 100) + " so'm";
    }

    /* ==================== Excel ==================== */

    /** «Тренд» varag'i: barcha do'konlar, 30 kunda faol tovarlar, 30 kunlik hajm bo'yicha kamayib. */
    public List<ExcelReportService.SheetDef> excelSheets() {
        List<Object[]> rows = new ArrayList<>();
        for (Kassa k : chempion.stores()) {
            String sn = chempion.storeName(k.getId());
            List<Item> all = list(k.getId()).stream().filter(i -> i.q30.add(i.p30).signum() > 0)
                    .sorted(Comparator.comparing((Item i) -> i.q30).reversed()).toList();
            for (Item i : all) {
                StringBuilder segs = new StringBuilder();
                for (Seg s : i.segs()) { if (segs.length() > 0) segs.append(" · "); segs.append(DF.format(s.from())).append("–").append(DF.format(s.to())).append(": ").append(fmt(s.price() / 100)); }
                rows.add(new Object[]{rows.size() + 1, sn, i.name(), i.article(), i.q7, i.p7, i.growth(7), i.q30, i.p30, i.growth(30),
                        i.price() == null ? null : i.price() / 100.0, i.prevPrice() == null ? null : i.prevPrice() / 100.0, i.priceChange(),
                        segs.toString(), i.min30() == null ? null : i.min30() / 100.0, i.max30() == null ? null : i.max30() / 100.0, i.stock()});
            }
        }
        return List.of(new ExcelReportService.SheetDef("Тренд", new String[]{"№", "Склад (MoySklad)", "Товар", "Артикул", "7 кун", "Олдинги 7", "Ўсиш 7 %",
                "30 кун", "Олдинги 30", "Ўсиш 30 %", "Нақд нарх (сўм)", "Олдинги нарх (сўм)", "Нарх ўзгариши %", "Нарх оралиқлари", "Мин 30 (сўм)", "Макс 30 (сўм)", "Қолдиқ"}, rows));
    }

    public byte[] excel() { return excel.buildSheets(excelSheets()); }

    /* ==================== yordamchi ==================== */

    /** Telegram 4096 chegarasi: qator qo'shishdan oldin tekshiriladi (teg o'rtasidan kesilmasin). */
    private static boolean full(StringBuilder sb) { return sb.length() > 3400; }
    private static String arrow(Integer g) { return g == null ? "🆕" : g > 0 ? "📈" : g < 0 ? "📉" : "➖"; }
    private static String sign(int g) { return (g > 0 ? "+" : g < 0 ? "−" : "") + Math.abs(g); }
    private static String qty(BigDecimal v) { return v == null ? "0" : v.stripTrailingZeros().toPlainString(); }
    private static String cut(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n - 1) + "…" : s; }
}
