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
