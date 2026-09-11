package uz.kassa.service.ombor;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.domain.Kassa;
import uz.kassa.repo.KassaRepo;
import uz.kassa.service.moysklad.MoySkladClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 🏬 Sotuv tarixi → ombor_korsatkich (SOTUV_MIQDOR, SOTUV_SUMMA, TANNARX_SUMMA, QAYTARISH_MIQDOR) — kun · do'kon · tovar.
 * Manba: report/profit/byproduct (momentFrom/To, store filtri). Kompaniya darajasi (kassa 0) filtrsiz.
 * Birinchi yuklash: ombor.sales_days (365) kun orqaga, kursor ombor.sales_cursor bilan bo'lib-bo'lib (har tick 20 kun).
 * Har kun: kecha va bugun qayta yoziladi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OmborSalesService {

    public static final String SOTUV_MIQDOR = "SOTUV_MIQDOR", SOTUV_SUMMA = "SOTUV_SUMMA", TANNARX_SUMMA = "TANNARX_SUMMA", QAYTARISH_MIQDOR = "QAYTARISH_MIQDOR";
    private static final int CHUNK_DAYS = 20;

    private final MoySkladClient ms;
    private final OmborMetrics metrics;
    private final KassaRepo kassaRepo;
    private final OmborConfig cfg;
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    /** Orqaga yuklash: kursor tugamaguncha har chaqiruvda CHUNK_DAYS kun. Qaytadi: yozilgan kunlar. */
    public int backfillTick() {
        if (!cfg.enabled() || !lock.tryLock()) return 0;
        try {
            LocalDate today = LocalDate.now(cfg.zone());
            LocalDate cursor = cfg.get(OmborConfig.SALES_CURSOR).map(LocalDate::parse).orElse(today.minusDays(cfg.salesDays()));
            if (!cursor.isBefore(today)) return 0;
            LocalDate end = cursor.plusDays(CHUNK_DAYS).isAfter(today.minusDays(1)) ? today.minusDays(1) : cursor.plusDays(CHUNK_DAYS);
            int n = 0;
            for (LocalDate d = cursor; !d.isAfter(end); d = d.plusDays(1)) { loadDay(d); n++; }
            cfg.set(OmborConfig.SALES_CURSOR, end.plusDays(1).toString());
            log.info("Ombor sotuv tarixi: {} — {} yuklandi ({} kun)", cursor, end, n);
            return n;
        } finally { lock.unlock(); }
    }

    /** Kecha va bugun (tunlik/qo'lda). */
    public void refreshRecent() {
        if (!lock.tryLock()) return;
        try {
            LocalDate today = LocalDate.now(cfg.zone());
            loadDay(today.minusDays(1));
            loadDay(today);
        } finally { lock.unlock(); }
    }

    public boolean backfillDone() {
        LocalDate today = LocalDate.now(cfg.zone());
        return cfg.get(OmborConfig.SALES_CURSOR).map(LocalDate::parse).map(c -> !c.isBefore(today)).orElse(false);
    }

    private void loadDay(LocalDate d) {
        List<OmborMetrics.Row> rows = new ArrayList<>();
        String period = "momentFrom=" + URLEncoder.encode(ms.filterTime(d.atStartOfDay()), StandardCharsets.UTF_8)
                + "&momentTo=" + URLEncoder.encode(ms.filterTime(d.plusDays(1).atStartOfDay()), StandardCharsets.UTF_8);
        collect(rows, d, 0, "report/profit/byproduct?limit=1000&" + period);
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.getMoyskladWarehouseId() == null || k.getMoyskladWarehouseId().isBlank()) continue;
            String f = URLEncoder.encode("store=https://api.moysklad.ru/api/remap/1.2/entity/store/" + k.getMoyskladWarehouseId(), StandardCharsets.UTF_8);
            collect(rows, d, k.getId(), "report/profit/byproduct?limit=1000&" + period + "&filter=" + f);
        }
        for (String c : List.of(SOTUV_MIQDOR, SOTUV_SUMMA, TANNARX_SUMMA, QAYTARISH_MIQDOR)) metrics.clear(d, c);
        metrics.upsert(rows);
    }

    private void collect(List<OmborMetrics.Row> rows, LocalDate d, long kassa, String q) {
        for (JsonNode r : ms.listAll(q, 20)) {
            String pid = ms.idOf(r.path("assortment"));
            if (pid.isBlank()) continue;
            double sq = r.path("sellQuantity").asDouble(0), rq = r.path("returnQuantity").asDouble(0);
            if (sq != 0) rows.add(new OmborMetrics.Row(d, kassa, pid, SOTUV_MIQDOR, BigDecimal.valueOf(sq)));
            if (sq != 0) rows.add(new OmborMetrics.Row(d, kassa, pid, SOTUV_SUMMA, BigDecimal.valueOf(r.path("sellSum").asDouble(0))));
            if (sq != 0) rows.add(new OmborMetrics.Row(d, kassa, pid, TANNARX_SUMMA, BigDecimal.valueOf(r.path("sellCostSum").asDouble(0))));
            if (rq != 0) rows.add(new OmborMetrics.Row(d, kassa, pid, QAYTARISH_MIQDOR, BigDecimal.valueOf(rq)));
        }
    }
}
