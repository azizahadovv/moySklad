package uz.kassa.service.ombor;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * 🏬 Bitta raqamlar jadvali (ombor_korsatkich) — yozish/o'qish. Kodlar: QOLDIQ, REZERV, YOLDA, AYLANMA_KUN …
 * kassaId 0 — kompaniya, productMsId '' — jami.
 */
@Repository
@RequiredArgsConstructor
public class OmborMetrics {

    public static final String QOLDIQ = "QOLDIQ", REZERV = "REZERV", YOLDA = "YOLDA", AYLANMA_KUN = "AYLANMA_KUN";

    public record Row(LocalDate date, long kassaId, String productMsId, String code, BigDecimal value) {}

    private final JdbcTemplate jdbc;

    /** Upsert, 500 talik paketlarda. */
    public int upsert(List<Row> rows) {
        String sql = "INSERT INTO ombor_korsatkich(date, kassa_id, product_ms_id, code, value) VALUES (?,?,?,?,?) "
                + "ON CONFLICT (date, kassa_id, product_ms_id, code) DO UPDATE SET value = EXCLUDED.value";
        int n = 0;
        for (int i = 0; i < rows.size(); i += 500) {
            List<Row> chunk = rows.subList(i, Math.min(rows.size(), i + 500));
            jdbc.batchUpdate(sql, chunk, chunk.size(), (PreparedStatement ps, Row r) -> {
                ps.setObject(1, r.date());
                ps.setLong(2, r.kassaId());
                ps.setString(3, r.productMsId() == null ? "" : r.productMsId());
                ps.setString(4, r.code());
                ps.setBigDecimal(5, r.value());
            });
            n += chunk.size();
        }
        return n;
    }

    /** Shu kun + kod bo'yicha eski qatorlarni o'chirish (to'liq qayta yozishdan oldin). */
    public int clear(LocalDate date, String code) {
        return jdbc.update("DELETE FROM ombor_korsatkich WHERE date = ? AND code = ?", date, code);
    }

    /** Kod bo'yicha oxirgi sana (hech narsa bo'lmasa null). */
    public LocalDate lastDate(String code) {
        List<LocalDate> l = jdbc.query("SELECT max(date) FROM ombor_korsatkich WHERE code = ?",
                (rs, i) -> rs.getObject(1, LocalDate.class), code);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Oxirgi sanadagi qiymatlar (kod bo'yicha), ixtiyoriy filtr: kassa>0 va shart. */
    public List<Map<String, Object>> latest(String code, String whereExtra, Object... args) {
        LocalDate d = lastDate(code);
        if (d == null) return List.of();
        List<Object> a = new ArrayList<>();
        a.add(d); a.add(code);
        for (Object o : args) a.add(o);
        return jdbc.queryForList("SELECT kassa_id, product_ms_id, value FROM ombor_korsatkich WHERE date = ? AND code = ? "
                + (whereExtra == null ? "" : " AND " + whereExtra), a.toArray());
    }

    /** Do'kon bo'yicha `from` dan beri eng ko'p sotilgan tovarlar (SOTUV_MIQDOR yig'indisi, kamayish tartibida), ko'pi bilan limit. */
    public List<String> topSold(long kassaId, LocalDate from, int limit) {
        return jdbc.queryForList("SELECT product_ms_id FROM ombor_korsatkich WHERE code = 'SOTUV_MIQDOR' AND kassa_id = ? AND date >= ? "
                + "AND product_ms_id <> '' GROUP BY product_ms_id HAVING sum(value) > 0 ORDER BY sum(value) DESC, product_ms_id LIMIT ?",
                String.class, kassaId, from, limit);
    }

    /**
     * Chempionlar: do'kon (0 — kompaniya) bo'yicha `from` dan beri eng ko'p sotilganlar —
     * [product_ms_id, miqdor, tushum(tiyin), tannarx(tiyin), foyda(tiyin), qaytarish miqdor].
     * Foyda = FOYDA (MoySklad profit, qaytarish chegirilgan); u yo'q kunlar uchun tushum − tannarx.
     */
    public List<Object[]> topSoldRows(long kassaId, LocalDate from, int limit, String orderBy) {
        String ord = switch (orderBy == null ? "" : orderBy) { case "qty" -> "qty"; case "summa" -> "summa"; default -> "foyda"; };
        return jdbc.query("""
            WITH d AS (SELECT product_ms_id, date,
                              COALESCE(sum(value) FILTER (WHERE code = 'SOTUV_MIQDOR'), 0) qty,
                              COALESCE(sum(value) FILTER (WHERE code = 'SOTUV_SUMMA'), 0) summa,
                              COALESCE(sum(value) FILTER (WHERE code = 'TANNARX_SUMMA'), 0) tannarx,
                              sum(value) FILTER (WHERE code = 'FOYDA') foyda,
                              COALESCE(sum(value) FILTER (WHERE code = 'QAYTARISH_MIQDOR'), 0) qaytdi
                       FROM ombor_korsatkich
                       WHERE kassa_id = ? AND date >= ? AND product_ms_id <> ''
                         AND code IN ('SOTUV_MIQDOR','SOTUV_SUMMA','TANNARX_SUMMA','FOYDA','QAYTARISH_MIQDOR')
                       GROUP BY product_ms_id, date),
                 p AS (SELECT product_ms_id, sum(qty) qty, sum(summa) summa, sum(tannarx) tannarx,
                              sum(COALESCE(foyda, summa - tannarx)) foyda, sum(qaytdi) qaytdi   -- kun kesimida: FOYDA yo'q kun uchun tushum − tannarx
                       FROM d GROUP BY product_ms_id)
            SELECT product_ms_id, qty, summa, tannarx, foyda, qaytdi
            FROM p WHERE qty > 0 ORDER BY {ord} DESC, product_ms_id LIMIT ?
            """.replace("{ord}", ord), (rs, i) -> new Object[]{rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6)},
                kassaId, from, limit);
    }

    /** `from` dan beri kelgan miqdor (tovar → dona): do'kon uchun priyomka + shu do'konga ko'chirish; kompaniya (0) — barcha priyomkalar. */
    public Map<String, BigDecimal> arrivedSince(long kassaId, LocalDate from) {
        Map<String, BigDecimal> out = new java.util.HashMap<>();
        jdbc.query("""
            SELECT p.product_ms_id, sum(p.qty) FROM ombor_pozitsiya p JOIN ombor_hujjat h ON h.id = p.hujjat_id
            WHERE h.deleted = false AND COALESCE(h.applicable, true) AND h.moment >= ?
              AND ((h.type = 'supply' AND (? = 0 OR h.kassa_id = ?)) OR (h.type = 'move' AND ? > 0 AND h.target_kassa_id = ?))
            GROUP BY p.product_ms_id
            """, rs -> { out.put(rs.getString(1), rs.getBigDecimal(2)); }, from.atStartOfDay(), kassaId, kassaId, kassaId, kassaId);
        return out;
    }

    /**
     * `from` dan beri (shu kun ham) net qoldiq harakati (tovar → dona): kirim (supply, do'konga ko'chirish, salesreturn, enter)
     * minus chiqim (demand barcha statuslar, do'kondan ko'chirish, loss, purchasereturn). Kompaniya (0) — ko'chirishlarsiz.
     * Boshlang'ich qoldiq = hozirgi qoldiq − net (2026-09-14: 🏆 qatorida «30 kun oldin N + keldi − sotildi = qoldiq»).
     */
    public Map<String, BigDecimal> netFlowSince(long kassaId, LocalDate from) {
        Map<String, BigDecimal> out = new java.util.HashMap<>();
        jdbc.query("""
            SELECT p.product_ms_id, sum(CASE
                WHEN h.type IN ('supply','salesreturn','retailsalesreturn','enter') THEN p.qty
                WHEN h.type = 'move' THEN CASE WHEN ? = 0 OR h.target_kassa_id = h.kassa_id THEN 0 WHEN h.target_kassa_id = ? THEN p.qty ELSE -p.qty END
                ELSE -p.qty END)
            FROM ombor_pozitsiya p JOIN ombor_hujjat h ON h.id = p.hujjat_id
            WHERE h.deleted = false AND COALESCE(h.applicable, true) AND h.moment >= ?
              AND h.type IN ('supply','move','salesreturn','retailsalesreturn','enter','demand','loss','purchasereturn')
              AND (? = 0 OR h.kassa_id = ? OR h.target_kassa_id = ?)
            GROUP BY p.product_ms_id
            """, rs -> { out.put(rs.getString(1), rs.getBigDecimal(2)); }, kassaId, kassaId, from.atStartOfDay(), kassaId, kassaId, kassaId);
        return out;
    }

    /** Kod bo'yicha `from` dan beri kunlik qiymatlar o'rtachasi (kassa, product ''), null — yozuv yo'q. */
    public Integer avgSince(String code, long kassaId, LocalDate from) {
        BigDecimal v = jdbc.queryForObject("SELECT avg(value) FROM ombor_korsatkich WHERE code = ? AND kassa_id = ? AND product_ms_id = '' AND date >= ?",
                BigDecimal.class, code, kassaId, from);
        return v == null ? null : (int) Math.round(v.doubleValue());
    }

    /** Do'kon bo'yicha `from` dan beri kamida bir marta sotilgan tovarlar to'plami. */
    public Set<String> soldSince(long kassaId, LocalDate from) {
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT product_ms_id FROM ombor_korsatkich WHERE code = 'SOTUV_MIQDOR' AND kassa_id = ? "
                + "AND date >= ? AND value > 0 AND product_ms_id <> ''", String.class, kassaId, from));
    }

    /** Tovar bo'yicha oxirgi qoldiqlar: kassa → qiymat (0 — kompaniya). */
    public Map<Long, BigDecimal> stockOf(String productMsId) {
        LocalDate d = lastDate(QOLDIQ);
        Map<Long, BigDecimal> out = new java.util.LinkedHashMap<>();
        if (d == null) return out;
        for (Map<String, Object> m : jdbc.queryForList("SELECT kassa_id, value FROM ombor_korsatkich WHERE date = ? AND code = ? AND product_ms_id = ? ORDER BY kassa_id",
                d, QOLDIQ, productMsId))
            out.put(((Number) m.get("kassa_id")).longValue(), (BigDecimal) m.get("value"));
        return out;
    }

    /** Oxirgi kun bo'yicha umumiy sonlar: [tovar soni (qoldiqli), manfiy soni]. */
    public long[] stockStats(long kassaId) {
        LocalDate d = lastDate(QOLDIQ);
        if (d == null) return new long[]{0, 0};
        Map<String, Object> m = jdbc.queryForMap("SELECT count(*) AS n, count(*) FILTER (WHERE value < 0) AS neg FROM ombor_korsatkich "
                + "WHERE date = ? AND code = ? AND kassa_id = ? AND product_ms_id <> ''", d, QOLDIQ, kassaId);
        return new long[]{((Number) m.get("n")).longValue(), ((Number) m.get("neg")).longValue()};
    }

    /** 400 kundan eski kunlik qatorlarni o'chirish (hajm nazorati; B2 da oylik jamlamaga o'tadi). */
    public int prune(int keepDays) {
        return jdbc.update("DELETE FROM ombor_korsatkich WHERE date < ?", LocalDate.now().minusDays(keepDays));
    }
}
