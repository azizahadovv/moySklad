package uz.kassa.service.ombor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import uz.kassa.domain.OmborDavr;
import uz.kassa.repo.OmborDavrRepo;
import java.time.LocalDate;

/**
 * 🏬 Tunlik hisoblar → ombor_korsatkich (docs/OMBOR-TZ.md §8), hammasi SQL bilan:
 *   SOTUV_KUN_ORTACHA_30/_90 (AKSIYA kunlari chiqarilgan) · ABC (kompaniya, 90 kun summa 80/95) ·
 *   BUYURTMA_NUQTA (ORTACHA_90 × (lead + xavfsizlik)) · QOPLASH_KUN · FILL_DAY (A tovar, do'kon) · FILL_RATE_30 ·
 *   MAVSUM_KOEF (MAVSUM davrlari, o'tgan yil) · SOVISH davrini avtomatik ochish.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OmborCalcService {

    public static final String ORTACHA_30 = "SOTUV_KUN_ORTACHA_30", ORTACHA_90 = "SOTUV_KUN_ORTACHA_90", ABC = "ABC",
            BUYURTMA_NUQTA = "BUYURTMA_NUQTA", QOPLASH_KUN = "QOPLASH_KUN", FILL_DAY = "FILL_DAY", FILL_RATE_30 = "FILL_RATE_30",
            MAVSUM_KOEF = "MAVSUM_KOEF";

    private final JdbcTemplate jdbc;
    private final OmborDavrRepo davrRepo;
    private final OmborConfig cfg;

    public void nightly() {
        LocalDate d = LocalDate.now(cfg.zone());
        try { sovish(d); } catch (Exception e) { log.warn("SOVISH: {}", e.getMessage()); }
        try { ortacha(d, 30, ORTACHA_30); ortacha(d, 90, ORTACHA_90); } catch (Exception e) { log.warn("ORTACHA: {}", e.getMessage()); }
        try { abc(d); } catch (Exception e) { log.warn("ABC: {}", e.getMessage()); }
        try { reorder(d); } catch (Exception e) { log.warn("BUYURTMA_NUQTA: {}", e.getMessage()); }
        try { fill(d); } catch (Exception e) { log.warn("FILL: {}", e.getMessage()); }
        try { mavsum(d); } catch (Exception e) { log.warn("MAVSUM: {}", e.getMessage()); }
        log.info("Ombor tunlik hisoblar bajarildi ({})", d);
    }

    /** Aksiya tugagach «sovish» davri: shu davrda tovar qoralamaga tushmaydi. */
    private void sovish(LocalDate today) {
        int days = cfg.sovishKun();
        for (OmborDavr a : davrRepo.findByKindAndToDateBefore("AKSIYA", today)) {
            LocalDate from = a.getToDate().plusDays(1);
            if (from.plusDays(days).isBefore(today)) continue;   // eskirgan
            boolean exists = a.getProductMsId() != null ? davrRepo.existsByKindAndProductMsIdAndFromDate("SOVISH", a.getProductMsId(), from)
                    : davrRepo.existsByKindAndFolderNameAndFromDate("SOVISH", a.getFolderName(), from);
            if (exists) continue;
            davrRepo.save(OmborDavr.builder().kind("SOVISH").productMsId(a.getProductMsId()).folderName(a.getFolderName())
                    .code(a.getCode()).fromDate(from).toDate(from.plusDays(days - 1)).note("avto: aksiya " + a.getCode() + " dan keyin").build());
        }
    }

    /** O'rtacha kunlik sotuv (do'kon va kompaniya), AKSIYA kunlari chiqariladi. */
    private void ortacha(LocalDate d, int win, String code) {
        jdbc.update("DELETE FROM ombor_korsatkich WHERE date = ? AND code = ?", d, code);
        jdbc.update("""
            INSERT INTO ombor_korsatkich(date, kassa_id, product_ms_id, code, value)
            SELECT ?, s.kassa_id, s.product_ms_id, ?, round(sum(s.value) / ?, 3)
            FROM ombor_korsatkich s
            WHERE s.code = 'SOTUV_MIQDOR' AND s.date >= ? AND s.date < ?
              AND NOT EXISTS (SELECT 1 FROM ombor_davr v LEFT JOIN ombor_tovar t ON t.ms_id = s.product_ms_id
                              WHERE v.kind = 'AKSIYA' AND s.date BETWEEN v.from_date AND v.to_date
                                AND (v.product_ms_id = s.product_ms_id OR (v.folder_name IS NOT NULL AND v.folder_name = t.folder_name)))
            GROUP BY s.kassa_id, s.product_ms_id
            """, d, code, win, d.minusDays(win), d);
    }

    /** ABC (kompaniya): 90 kunlik SOTUV_SUMMA bo'yicha kumulyativ 80% → A(1), 95% → B(2), qolgan C(3). */
    private void abc(LocalDate d) {
        jdbc.update("DELETE FROM ombor_korsatkich WHERE date = ? AND code = ?", d, ABC);
        jdbc.update("""
            INSERT INTO ombor_korsatkich(date, kassa_id, product_ms_id, code, value)
            SELECT ?, 0, product_ms_id, ?, CASE WHEN cum <= 0.80 THEN 1 WHEN cum <= 0.95 THEN 2 ELSE 3 END
            FROM (SELECT product_ms_id, s, sum(s) OVER (ORDER BY s DESC, product_ms_id) / NULLIF(sum(s) OVER (), 0) AS cum
                  FROM (SELECT product_ms_id, sum(value) AS s FROM ombor_korsatkich
                        WHERE code = 'SOTUV_SUMMA' AND kassa_id = 0 AND date >= ? AND date < ? GROUP BY product_ms_id) x) y
            WHERE s > 0
            """, d, ABC, d.minusDays(90), d);
        // 90 kunda sotilmagan, lekin qoldig'i bor tovarlar — C (aks holda ABC bo'sh qolardi)
        LocalDate sd = lastDate("QOLDIQ");
        if (sd != null) jdbc.update("""
            INSERT INTO ombor_korsatkich(date, kassa_id, product_ms_id, code, value)
            SELECT DISTINCT ?, 0, q.product_ms_id, ?, 3 FROM ombor_korsatkich q
            WHERE q.code = 'QOLDIQ' AND q.date = ? AND q.product_ms_id <> '' AND q.value <> 0
              AND NOT EXISTS (SELECT 1 FROM ombor_korsatkich a WHERE a.code = ? AND a.date = ? AND a.kassa_id = 0 AND a.product_ms_id = q.product_ms_id)
            """, d, ABC, sd, ABC, d);
    }

    /** Buyurtma nuqtasi va qoplash kuni (do'kon kesimida). lead = ombor.lead_days, xavfsizlik = ombor.xavfsizlik_kun. */
    private void reorder(LocalDate d) {
        int lead = cfg.leadDays(), safety = cfg.safetyDays();
        jdbc.update("DELETE FROM ombor_korsatkich WHERE date = ? AND code IN (?, ?)", d, BUYURTMA_NUQTA, QOPLASH_KUN);
        jdbc.update("""
            INSERT INTO ombor_korsatkich(date, kassa_id, product_ms_id, code, value)
            SELECT ?, o.kassa_id, o.product_ms_id, ?, round(o.value * ?, 3)
            FROM ombor_korsatkich o WHERE o.code = ? AND o.date = ? AND o.kassa_id > 0 AND o.value > 0
            """, d, BUYURTMA_NUQTA, lead + safety, ORTACHA_90, d);
        LocalDate sd = lastDate("QOLDIQ");
        if (sd == null) return;
        jdbc.update("""
            INSERT INTO ombor_korsatkich(date, kassa_id, product_ms_id, code, value)
            SELECT ?, q.kassa_id, q.product_ms_id, ?, LEAST(999, round(q.value / o.value, 1))
            FROM ombor_korsatkich q JOIN ombor_korsatkich o ON o.code = ? AND o.date = ? AND o.kassa_id = q.kassa_id AND o.product_ms_id = q.product_ms_id
            WHERE q.code = 'QOLDIQ' AND q.date = ? AND q.kassa_id > 0 AND q.value > 0 AND o.value > 0
            """, d, QOPLASH_KUN, ORTACHA_90, d, sd);
    }

    /** FILL_DAY (A tovar × do'kon: qoldiq > 0 → 1, aks holda 0) va FILL_RATE_30 (do'kon va kompaniya, SOTUV_SUMMA bilan og'irlangan). */
    private void fill(LocalDate d) {
        LocalDate sd = lastDate("QOLDIQ");
        if (sd == null) return;
        jdbc.update("DELETE FROM ombor_korsatkich WHERE date = ? AND code IN (?, ?)", d, FILL_DAY, FILL_RATE_30);
        // A tovarlar × bog'langan do'konlar; qoldiq qatori yo'q = 0
        jdbc.update("""
            INSERT INTO ombor_korsatkich(date, kassa_id, product_ms_id, code, value)
            SELECT ?, k.id, a.product_ms_id, ?, CASE WHEN COALESCE(q.value, 0) > 0 THEN 1 ELSE 0 END
            FROM ombor_korsatkich a
            CROSS JOIN kassa k
            LEFT JOIN ombor_korsatkich q ON q.code = 'QOLDIQ' AND q.date = ? AND q.kassa_id = k.id AND q.product_ms_id = a.product_ms_id
            WHERE a.code = 'ABC' AND a.date = ? AND a.value = 1 AND k.active AND k.moysklad_warehouse_id IS NOT NULL
            """, d, FILL_DAY, sd, d);
        // FILL_RATE_30: do'kon kesimida
        jdbc.update("""
            INSERT INTO ombor_korsatkich(date, kassa_id, product_ms_id, code, value)
            SELECT ?, f.kassa_id, '', ?, round(100.0 * sum(f.value * COALESCE(w.s, 1)) / NULLIF(sum(COALESCE(w.s, 1)), 0), 1)
            FROM ombor_korsatkich f
            LEFT JOIN (SELECT product_ms_id, sum(value) AS s FROM ombor_korsatkich WHERE code = 'SOTUV_SUMMA' AND kassa_id = 0 AND date >= ? GROUP BY product_ms_id) w
              ON w.product_ms_id = f.product_ms_id
            WHERE f.code = ? AND f.date > ? AND f.date <= ?
            GROUP BY f.kassa_id
            """, d, FILL_RATE_30, d.minusDays(90), FILL_DAY, d.minusDays(30), d);
        // kompaniya
        jdbc.update("""
            INSERT INTO ombor_korsatkich(date, kassa_id, product_ms_id, code, value)
            SELECT ?, 0, '', ?, round(avg(value), 1) FROM ombor_korsatkich WHERE code = ? AND date = ? AND product_ms_id = '' AND kassa_id > 0
            HAVING count(*) > 0
            """, d, FILL_RATE_30, FILL_RATE_30, d);
    }

    /** MAVSUM_KOEF: MAVSUM davri belgilangan tovar/guruh uchun o'tgan yil shu oy / o'tgan yil oylik o'rtacha (0.5–2.0). */
    private void mavsum(LocalDate d) {
        jdbc.update("DELETE FROM ombor_korsatkich WHERE date = ? AND code = ?", d, MAVSUM_KOEF);
        LocalDate ly = d.minusYears(1);
        jdbc.update("""
            INSERT INTO ombor_korsatkich(date, kassa_id, product_ms_id, code, value)
            SELECT ?, 0, m.product_ms_id, ?, LEAST(2.0, GREATEST(0.5, round(m.month_qty / NULLIF(y.avg_qty, 0), 2)))
            FROM (SELECT s.product_ms_id, sum(s.value) AS month_qty FROM ombor_korsatkich s
                  WHERE s.code = 'SOTUV_MIQDOR' AND s.kassa_id = 0 AND s.date >= ? AND s.date < ? GROUP BY s.product_ms_id) m
            JOIN (SELECT s.product_ms_id, sum(s.value) / 12.0 AS avg_qty FROM ombor_korsatkich s
                  WHERE s.code = 'SOTUV_MIQDOR' AND s.kassa_id = 0 AND s.date >= ? AND s.date < ? GROUP BY s.product_ms_id) y
              ON y.product_ms_id = m.product_ms_id
            JOIN ombor_tovar t ON t.ms_id = m.product_ms_id
            WHERE y.avg_qty > 0 AND EXISTS (SELECT 1 FROM ombor_davr v WHERE v.kind = 'MAVSUM'
                    AND (v.product_ms_id = m.product_ms_id OR (v.folder_name IS NOT NULL AND v.folder_name = t.folder_name)))
            """, d, MAVSUM_KOEF, ly.withDayOfMonth(1), ly.withDayOfMonth(1).plusMonths(1), ly.minusMonths(6).withDayOfMonth(1), ly.plusMonths(6).withDayOfMonth(1));
    }

    private LocalDate lastDate(String code) {
        var l = jdbc.query("SELECT max(date) FROM ombor_korsatkich WHERE code = ?", (rs, i) -> rs.getObject(1, LocalDate.class), code);
        return l.isEmpty() ? null : l.get(0);
    }
}
