package uz.kassa.service.ombor;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import uz.kassa.domain.Kassa;
import uz.kassa.repo.KassaRepo;
import uz.kassa.repo.OmborSinxronRepo;
import uz.kassa.repo.OmborTovarRepo;
import uz.kassa.service.moysklad.MoySkladClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 🏬 Sotuv tarixi → ombor_korsatkich, kun · do'kon (kassa 0 — kompaniya) · tovar. Ikki qatlam (2026-09-14 qarori):
 * <ul>
 *   <li><b>SOTUV_*</b> — faqat <b>naqd</b> savdo: otgruzka (ombor_hujjat type=demand) pozitsiyalaridan, statusi
 *       {@link OmborConfig#naqdStates()} ichida (Накд, Карта, Клик) va hujjat summasi {@link OmborConfig#sotuvMaxHujjat()}
 *       dan oshmagan. Перечисление / Карз перечисление (bank, tashkilotlar) va bitta katta hujjat tahlilga kirmaydi.
 *       Chempionlar, ABC, buyurtma nuqtasi, sanoq havzasi — hammasi shu qatordan o'qiydi.
 *       TANNARX_SUMMA = naqd miqdor × birlik tannarx (MoySklad hisobotining shu kun/tovar tannarxi, bo'lmasa 90 kunlik
 *       o'rtacha, bo'lmasa tovar buy_price); FOYDA = SOTUV_SUMMA − TANNARX_SUMMA. QAYTARISH_* — mijoz qaytarishlari,
 *       bog'langan otgruzkasi naqd bo'lsa (yoki bog'lanmagan bo'lsa).</li>
 *   <li><b>BOSHQA_*</b> — naqd bo'lmagan (bank statuslari + chegaradan katta hujjatlar) miqdor/summa, ma'lumot uchun.</li>
 *   <li><b>JAMI_*</b> — MoySklad report/profit/byproduct (barcha statuslar): taqqoslash va tannarx manbai.
 *       Birinchi yuklash ombor.sales_days kun orqaga, kursor ombor.sales_cursor (har tick 20 kun).</li>
 * </ul>
 * Summalar TIYINDA.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OmborSalesService {

    // naqd
    public static final String SOTUV_MIQDOR = "SOTUV_MIQDOR", SOTUV_SUMMA = "SOTUV_SUMMA", TANNARX_SUMMA = "TANNARX_SUMMA",
            FOYDA = "FOYDA", QAYTARISH_MIQDOR = "QAYTARISH_MIQDOR", QAYTARISH_SUMMA = "QAYTARISH_SUMMA";
    // naqd bo'lmagan (bank + katta hujjat)
    public static final String BOSHQA_MIQDOR = "BOSHQA_MIQDOR", BOSHQA_SUMMA = "BOSHQA_SUMMA";
    // MoySklad hisoboti, barcha statuslar
    public static final String JAMI_MIQDOR = "JAMI_MIQDOR", JAMI_SUMMA = "JAMI_SUMMA", JAMI_TANNARX = "JAMI_TANNARX", JAMI_FOYDA = "JAMI_FOYDA",
            JAMI_QAYTARISH_MIQDOR = "JAMI_QAYTARISH_MIQDOR", JAMI_QAYTARISH_SUMMA = "JAMI_QAYTARISH_SUMMA";
    static final List<String> NAQD_CODES = List.of(SOTUV_MIQDOR, SOTUV_SUMMA, TANNARX_SUMMA, FOYDA, QAYTARISH_MIQDOR, QAYTARISH_SUMMA, BOSHQA_MIQDOR, BOSHQA_SUMMA);
    static final List<String> JAMI_CODES = List.of(JAMI_MIQDOR, JAMI_SUMMA, JAMI_TANNARX, JAMI_FOYDA, JAMI_QAYTARISH_MIQDOR, JAMI_QAYTARISH_SUMMA);
    private static final int CHUNK_DAYS = 20;
    private static final int COST_AVG_DAYS = 90;

    private final MoySkladClient ms;
    private final OmborMetrics metrics;
    private final KassaRepo kassaRepo;
    private final OmborTovarRepo tovarRepo;
    private final OmborSinxronRepo syncRepo;
    private final OmborConfig cfg;
    private final JdbcTemplate jdbc;
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
    private volatile String progress = null;   // "qayta hisoblanmoqda 120/365" — admin ekrani uchun

    /* ==================== kirish nuqtalari ==================== */

    /** JAMI orqaga yuklash: kursor tugamaguncha har chaqiruvda CHUNK_DAYS kun (naqd shu kunlar uchun ham qayta yoziladi). Qaytadi: kunlar. */
    public int backfillTick() {
        if (!cfg.enabled() || !lock.tryLock()) return 0;
        try {
            LocalDate today = LocalDate.now(cfg.zone());
            LocalDate cursor = cfg.get(OmborConfig.SALES_CURSOR).map(LocalDate::parse).orElse(today.minusDays(cfg.salesDays()));
            if (!cursor.isBefore(today)) return 0;
            LocalDate end = cursor.plusDays(CHUNK_DAYS).isAfter(today.minusDays(1)) ? today.minusDays(1) : cursor.plusDays(CHUNK_DAYS);
            Map<String, BigDecimal> fallback = fallbackCost(today);
            int n = 0;
            for (LocalDate d = cursor; !d.isAfter(end); d = d.plusDays(1)) { loadReport(d); rebuildDay(d, fallback); n++; }
            cfg.set(OmborConfig.SALES_CURSOR, end.plusDays(1).toString());
            log.info("Ombor sotuv tarixi (JAMI): {} — {} yuklandi ({} kun)", cursor, end, n);
            return n;
        } finally { lock.unlock(); }
    }

    /** Kecha va bugun: hisobot + naqd (tunlik zanjir, 🔄 tugma, otgruzka sinxronidan keyin). Otgruzka tarixi birinchi marta to'liq kelganda — hammasi. */
    public void refreshRecent() {
        if (!cfg.enabled() || !lock.tryLock()) return;
        try {
            LocalDate today = LocalDate.now(cfg.zone());
            Map<String, BigDecimal> fallback = fallbackCost(today);
            loadReport(today.minusDays(1)); rebuildDay(today.minusDays(1), fallback);
            loadReport(today);             rebuildDay(today, fallback);
            if (demandLoaded() && cfg.get(OmborConfig.NAQD_REBUILT).isEmpty()) rebuildAllLocked(today);
        } finally { lock.unlock(); }
    }

    /** Hamma kunni (sales_days) naqd bo'yicha qayta hisoblash — sozlama (statuslar, chegara) o'zgarganda. Qaytadi: kunlar soni (0 — band). */
    public int rebuildAll() {
        if (!lock.tryLock()) return 0;
        try { return rebuildAllLocked(LocalDate.now(cfg.zone())); }
        finally { lock.unlock(); }
    }

    private int rebuildAllLocked(LocalDate today) {
        LocalDate from = today.minusDays(cfg.salesDays());
        Map<String, BigDecimal> fallback = fallbackCost(today);
        int n = 0, total = (int) (today.toEpochDay() - from.toEpochDay()) + 1;
        try {
            for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) { progress = (++n) + "/" + total; rebuildDay(d, fallback); }
            cfg.set(OmborConfig.NAQD_REBUILT, today.toString());
            log.info("Ombor naqd sotuv qayta hisoblandi: {} kun ({} — {})", n, from, today);
            return n;
        } finally { progress = null; }
    }

    public boolean backfillDone() {
        LocalDate today = LocalDate.now(cfg.zone());
        return cfg.get(OmborConfig.SALES_CURSOR).map(LocalDate::parse).map(c -> !c.isBefore(today)).orElse(false);
    }

    /** Otgruzka hujjatlari birinchi marta to'liq (sales_days) yuklanganmi. */
    public boolean demandLoaded() { return syncRepo.findById("demand").map(s -> s.getCursorAt() != null).orElse(false); }
    public boolean naqdReady() { return cfg.get(OmborConfig.NAQD_REBUILT).isPresent(); }
    /** «qayta hisoblanmoqda 120/365» yoki null. */
    public String progress() { return progress; }
    public boolean busy() { return lock.isLocked(); }

    /* ==================== JAMI: MoySklad hisoboti ==================== */

    private void loadReport(LocalDate d) {
        List<OmborMetrics.Row> rows = new ArrayList<>();
        String period = "momentFrom=" + URLEncoder.encode(ms.filterTime(d.atStartOfDay()), StandardCharsets.UTF_8)
                + "&momentTo=" + URLEncoder.encode(ms.filterTime(d.plusDays(1).atStartOfDay()), StandardCharsets.UTF_8);
        collect(rows, d, 0, "report/profit/byproduct?limit=1000&" + period);
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.getMoyskladWarehouseId() == null || k.getMoyskladWarehouseId().isBlank()) continue;
            String f = URLEncoder.encode("store=https://api.moysklad.ru/api/remap/1.2/entity/store/" + k.getMoyskladWarehouseId(), StandardCharsets.UTF_8);
            collect(rows, d, k.getId(), "report/profit/byproduct?limit=1000&" + period + "&filter=" + f);
        }
        for (String c : JAMI_CODES) metrics.clear(d, c);
        metrics.upsert(rows);
    }

    private void collect(List<OmborMetrics.Row> rows, LocalDate d, long kassa, String q) {
        for (JsonNode r : ms.listAll(q, 20)) {
            String pid = ms.idOf(r.path("assortment"));
            if (pid.isBlank()) continue;
            double sq = r.path("sellQuantity").asDouble(0), rq = r.path("returnQuantity").asDouble(0);
            if (sq != 0) rows.add(new OmborMetrics.Row(d, kassa, pid, JAMI_MIQDOR, BigDecimal.valueOf(sq)));
            if (sq != 0) rows.add(new OmborMetrics.Row(d, kassa, pid, JAMI_SUMMA, BigDecimal.valueOf(r.path("sellSum").asDouble(0))));
            if (sq != 0) rows.add(new OmborMetrics.Row(d, kassa, pid, JAMI_TANNARX, BigDecimal.valueOf(r.path("sellCostSum").asDouble(0))));
            if (rq != 0) rows.add(new OmborMetrics.Row(d, kassa, pid, JAMI_QAYTARISH_MIQDOR, BigDecimal.valueOf(rq)));
            if (rq != 0) rows.add(new OmborMetrics.Row(d, kassa, pid, JAMI_QAYTARISH_SUMMA, BigDecimal.valueOf(r.path("returnSum").asDouble(0))));
            if (sq != 0 || rq != 0) rows.add(new OmborMetrics.Row(d, kassa, pid, JAMI_FOYDA, BigDecimal.valueOf(r.path("profit").asDouble(0))));
        }
    }

    /* ==================== NAQD: otgruzka pozitsiyalari ==================== */

    /** Kun · do'kon · tovar jamlagichi. */
    private static final class Acc { BigDecimal qty = BigDecimal.ZERO; BigDecimal sum = BigDecimal.ZERO; }

    private static Acc acc(Map<Long, Map<String, Acc>> m, Long kassa, String pid) {
        return m.computeIfAbsent(kassa, k -> new HashMap<>()).computeIfAbsent(pid, k -> new Acc());
    }

    /** Bitta kunni naqd bo'yicha qayta yozish. fallback — tovar → birlik tannarx (90 kunlik JAMI o'rtachasi, tiyin). */
    private void rebuildDay(LocalDate d, Map<String, BigDecimal> fallback) {
        Set<String> naqd = new HashSet<>();
        for (String s : cfg.naqdStates()) naqd.add(s.toLowerCase(Locale.ROOT));
        long max = cfg.sotuvMaxHujjat() * 100;   // tiyin
        LocalDateTime from = d.atStartOfDay(), to = d.plusDays(1).atStartOfDay();
        Map<Long, Map<String, Acc>> sotuv = new HashMap<>(), boshqa = new HashMap<>();
        jdbc.query("""
            SELECT h.kassa_id, h.state, h.sum, p.product_ms_id, p.qty, p.price, p.discount
            FROM ombor_hujjat h JOIN ombor_pozitsiya p ON p.hujjat_id = h.id
            WHERE h.type = 'demand' AND h.deleted = false AND COALESCE(h.applicable, true) AND h.moment >= ? AND h.moment < ?
            """, rs -> {
            boolean ok = naqd.contains(rs.getString(2).trim().toLowerCase(Locale.ROOT)) && (max == 0 || rs.getLong(3) <= max);
            Map<Long, Map<String, Acc>> m = ok ? sotuv : boshqa;
            BigDecimal qty = rs.getBigDecimal(5);
            BigDecimal disc = rs.getBigDecimal(7) == null ? BigDecimal.ZERO : rs.getBigDecimal(7);
            BigDecimal sum = qty.multiply(BigDecimal.valueOf(rs.getLong(6)))
                    .multiply(BigDecimal.ONE.subtract(disc.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP))).setScale(0, RoundingMode.HALF_UP);
            long kassa = rs.getObject(1) == null ? -1 : rs.getLong(1);
            String pid = rs.getString(4);
            for (Long k : kassa > 0 ? List.of(kassa, 0L) : List.of(0L)) { Acc a = acc(m, k, pid); a.qty = a.qty.add(qty); a.sum = a.sum.add(sum); }
        }, from, to);

        // birlik tannarx: shu kun hisobotidan (do'kon, bo'lmasa kompaniya), bo'lmasa 90 kunlik o'rtacha, bo'lmasa buy_price
        Map<String, BigDecimal> dayCost = new HashMap<>();   // "kassa|pid" → birlik (tiyin)
        Map<String, BigDecimal> t = new HashMap<>(), q = new HashMap<>();
        for (Map<String, Object> m : jdbc.queryForList("SELECT kassa_id, product_ms_id, code, value FROM ombor_korsatkich WHERE date = ? AND code IN ('JAMI_TANNARX','JAMI_MIQDOR')", d)) {
            String key = m.get("kassa_id") + "|" + m.get("product_ms_id");
            (m.get("code").equals(JAMI_TANNARX) ? t : q).put(key, (BigDecimal) m.get("value"));
        }
        for (Map.Entry<String, BigDecimal> e : t.entrySet()) {
            BigDecimal qq = q.get(e.getKey());
            if (qq != null && qq.signum() > 0) dayCost.put(e.getKey(), e.getValue().divide(qq, 2, RoundingMode.HALF_UP));
        }

        // mijoz qaytarishlari: bog'langan otgruzka naqd bo'lsa (yoki bog'lanmagan bo'lsa)
        Map<Long, Map<String, Acc>> qayt = new HashMap<>();
        List<Object[]> ret = new ArrayList<>();   // [kassa, links, pid, qty, price, discount]
        jdbc.query("""
            SELECT h.kassa_id, h.links, p.product_ms_id, p.qty, p.price, p.discount
            FROM ombor_hujjat h JOIN ombor_pozitsiya p ON p.hujjat_id = h.id
            WHERE h.type IN ('salesreturn','retailsalesreturn') AND h.deleted = false AND COALESCE(h.applicable, true) AND h.moment >= ? AND h.moment < ?
            """, rs -> { ret.add(new Object[]{rs.getObject(1) == null ? -1L : rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getLong(5), rs.getBigDecimal(6)}); }, from, to);
        Map<String, Boolean> linkOk = new HashMap<>();   // otgruzka ms_id → naqdmi
        for (Object[] r : ret) {
            String links = r[1] == null ? "" : (String) r[1];
            boolean ok = true;
            for (String l : links.split(",")) {
                if (l.isBlank()) continue;
                ok = linkOk.computeIfAbsent(l.trim(), id -> {
                    List<Map<String, Object>> dm = jdbc.queryForList("SELECT state, sum FROM ombor_hujjat WHERE ms_id = ? AND type = 'demand'", id);
                    if (dm.isEmpty()) return true;   // bog'langan hujjat bizda yo'q — naqd deb olinadi
                    return naqd.contains(String.valueOf(dm.get(0).get("state")).trim().toLowerCase(Locale.ROOT))
                            && (max == 0 || ((Number) dm.get(0).get("sum")).longValue() <= max);
                });
            }
            if (!ok) continue;
            BigDecimal qty = (BigDecimal) r[3];
            BigDecimal disc = r[5] == null ? BigDecimal.ZERO : (BigDecimal) r[5];
            BigDecimal sum = qty.multiply(BigDecimal.valueOf((Long) r[4]))
                    .multiply(BigDecimal.ONE.subtract(disc.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP))).setScale(0, RoundingMode.HALF_UP);
            long kassa = (Long) r[0];
            String pid = (String) r[2];
            for (Long k : kassa > 0 ? List.of(kassa, 0L) : List.of(0L)) { Acc a = acc(qayt, k, pid); a.qty = a.qty.add(qty); a.sum = a.sum.add(sum); }
        }

        List<OmborMetrics.Row> rows = new ArrayList<>();
        for (Map.Entry<Long, Map<String, Acc>> ke : sotuv.entrySet()) {
            long kassa = ke.getKey();
            for (Map.Entry<String, Acc> pe : ke.getValue().entrySet()) {
                String pid = pe.getKey(); Acc a = pe.getValue();
                if (a.qty.signum() == 0) continue;
                BigDecimal unit = dayCost.get(kassa + "|" + pid);
                if (unit == null) unit = dayCost.get("0|" + pid);
                if (unit == null) unit = fallback.get(pid);
                if (unit == null) unit = buyPrice(pid, fallback);
                BigDecimal tannarx = a.qty.multiply(unit).setScale(0, RoundingMode.HALF_UP);
                rows.add(new OmborMetrics.Row(d, kassa, pid, SOTUV_MIQDOR, a.qty));
                rows.add(new OmborMetrics.Row(d, kassa, pid, SOTUV_SUMMA, a.sum));
                rows.add(new OmborMetrics.Row(d, kassa, pid, TANNARX_SUMMA, tannarx));
                rows.add(new OmborMetrics.Row(d, kassa, pid, FOYDA, a.sum.subtract(tannarx)));
            }
        }
        for (Map.Entry<Long, Map<String, Acc>> ke : boshqa.entrySet())
            for (Map.Entry<String, Acc> pe : ke.getValue().entrySet()) {
                if (pe.getValue().qty.signum() == 0) continue;
                rows.add(new OmborMetrics.Row(d, ke.getKey(), pe.getKey(), BOSHQA_MIQDOR, pe.getValue().qty));
                rows.add(new OmborMetrics.Row(d, ke.getKey(), pe.getKey(), BOSHQA_SUMMA, pe.getValue().sum));
            }
        for (Map.Entry<Long, Map<String, Acc>> ke : qayt.entrySet())
            for (Map.Entry<String, Acc> pe : ke.getValue().entrySet()) {
                if (pe.getValue().qty.signum() == 0) continue;
                rows.add(new OmborMetrics.Row(d, ke.getKey(), pe.getKey(), QAYTARISH_MIQDOR, pe.getValue().qty));
                rows.add(new OmborMetrics.Row(d, ke.getKey(), pe.getKey(), QAYTARISH_SUMMA, pe.getValue().sum));
            }
        for (String c : NAQD_CODES) metrics.clear(d, c);
        metrics.upsert(rows);
    }

    /** Tovar → 90 kunlik o'rtacha birlik tannarx (kompaniya JAMI, tiyin). */
    private Map<String, BigDecimal> fallbackCost(LocalDate today) {
        Map<String, BigDecimal> out = new HashMap<>();
        jdbc.query("""
            SELECT product_ms_id, sum(value) FILTER (WHERE code = 'JAMI_TANNARX') / NULLIF(sum(value) FILTER (WHERE code = 'JAMI_MIQDOR'), 0)
            FROM ombor_korsatkich WHERE kassa_id = 0 AND date >= ? AND code IN ('JAMI_TANNARX','JAMI_MIQDOR') GROUP BY product_ms_id
            """, rs -> { BigDecimal v = rs.getBigDecimal(2); if (v != null && v.signum() > 0) out.put(rs.getString(1), v.setScale(2, RoundingMode.HALF_UP)); },
                today.minusDays(COST_AVG_DAYS));
        return out;
    }

    private BigDecimal buyPrice(String pid, Map<String, BigDecimal> cache) {
        BigDecimal v = tovarRepo.findById(pid).map(t -> BigDecimal.valueOf(t.getBuyPrice())).orElse(BigDecimal.ZERO);
        cache.put(pid, v);
        return v;
    }

    /* ==================== ma'lumot: chiqarilgan hujjatlar ==================== */

    /** Tahlilga kirmagan otgruzka: sabab — «bank» (status naqd emas) yoki «katta» (summa chegaradan oshgan). Summalar tiyin. */
    public record Excluded(String docNo, LocalDateTime moment, String agentName, String state, long sum, String reason, String msId) {
        public String url() { return "https://online.moysklad.ru/app/#demand/edit?id=" + msId; }
    }

    /** `from` dan beri (do'kon yoki 0 — hammasi) chiqarilgan hujjatlar, summa bo'yicha kamayib, ko'pi bilan limit. */
    public List<Excluded> excluded(long kassaId, LocalDate from, int limit) {
        long max = cfg.sotuvMaxHujjat() * 100;
        List<Excluded> out = new ArrayList<>();
        jdbc.query("SELECT doc_no, moment, agent_name, state, sum, ms_id FROM ombor_hujjat WHERE type = 'demand' AND deleted = false AND COALESCE(applicable, true) "
                + "AND moment >= ? AND (? = 0 OR kassa_id = ?) ORDER BY sum DESC", rs -> {
            if (out.size() >= limit) return;
            String st = rs.getString(4);
            boolean naqd = cfg.isNaqd(st);
            long sum = rs.getLong(5);
            if (naqd && (max == 0 || sum <= max)) return;
            out.add(new Excluded(rs.getString(1), rs.getObject(2, LocalDateTime.class), rs.getString(3), st, sum, naqd ? "katta" : "bank", rs.getString(6)));
        }, from.atStartOfDay(), kassaId, kassaId);
        return out;
    }

    /** `from` dan beri: [naqd hujjat soni, bank soni, katta soni, naqd summa, bank summa, katta summa] (tiyin). */
    public long[] excludedStats(long kassaId, LocalDate from) {
        long max = cfg.sotuvMaxHujjat() * 100;
        long[] s = new long[6];
        jdbc.query("SELECT state, sum FROM ombor_hujjat WHERE type = 'demand' AND deleted = false AND COALESCE(applicable, true) "
                + "AND moment >= ? AND (? = 0 OR kassa_id = ?)", rs -> {
            boolean naqd = cfg.isNaqd(rs.getString(1));
            long sum = rs.getLong(2);
            int i = !naqd ? 1 : (max > 0 && sum > max) ? 2 : 0;
            s[i]++; s[3 + i] += sum;
        }, from.atStartOfDay(), kassaId, kassaId);
        return s;
    }

    /** Otgruzkalarda uchragan statuslar (soni bilan, ko'p → kam), sozlama ekrani uchun. */
    public LinkedHashMap<String, Long> demandStates() {
        LinkedHashMap<String, Long> out = new LinkedHashMap<>();
        jdbc.query("SELECT state, count(*) FROM ombor_hujjat WHERE type = 'demand' AND deleted = false GROUP BY state ORDER BY count(*) DESC, state",
                rs -> { String st = rs.getString(1); if (st != null && !st.isBlank()) out.put(st, rs.getLong(2)); });
        return out;
    }
}
