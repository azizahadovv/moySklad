package uz.kassa.service.ombor;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.OmborSorov;
import uz.kassa.repo.OmborSorovRepo;
import uz.kassa.service.AuditService;
import uz.kassa.service.moysklad.MoySkladClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * 🏬 B4: do'kon so'rovlari (sabab kodi bilan) va 🤝 hamkor do'konlar (tuman ustalari):
 * hamkorning oxirgi 180 kundagi xaridlari orasidagi o'rtacha interval o'tgan tovarlar → HAMKOR_INTERVAL ko'rsatkichi
 * (kassa 0, product = "agent|tovar", qiymat = necha kun o'tib ketgan).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OmborSorovService {

    private final OmborSorovRepo repo;
    private final MoySkladClient ms;
    private final OmborMetrics metrics;
    private final OmborConfig cfg;
    private final AuditService audit;

    public OmborSorov create(AppUser by, Long kassaId, String productMsId, String text, BigDecimal qty, String reason) {
        OmborSorov s = repo.save(OmborSorov.builder().kassaId(kassaId).productMsId(productMsId).text(text == null ? "" : text)
                .qty(qty == null ? BigDecimal.ONE : qty).reason(reason).byUserId(by.getId()).build());
        audit.log(by.getId(), "OMBOR_SOROV", "ombor_sorov", s.getId(), reason + " " + (productMsId == null ? text : productMsId));
        return s;
    }

    public OmborSorov answer(long id, AppUser by, String status, String note) {
        OmborSorov s = repo.findById(id).orElse(null);
        if (s == null) return null;
        s.setStatus(status); s.setAnsweredBy(by.getId()); s.setAnsweredAt(Instant.now());
        if (note != null) s.setAnswer(note);
        audit.log(by.getId(), "OMBOR_SOROV_" + status, "ombor_sorov", id, "");
        return repo.save(s);
    }

    public List<OmborSorov> open() { return repo.findByStatusInOrderByCreatedAtDesc(List.of("YANGI", "KORILDI")); }
    public List<OmborSorov> forDraft() { return repo.findByStatusOrderByCreatedAtAsc("QORALAMADA"); }
    public OmborSorovRepo repo() { return repo; }

    /* ==================== 🤝 hamkorlar: xarid intervali ==================== */

    /** Tunlik: har hamkor uchun demand pozitsiyalari (180 kun) → tovar bo'yicha interval → o'tib ketgan kunlar. */
    public int hamkorIntervals(Collection<String> agentIds) {
        LocalDate today = LocalDate.now(cfg.zone());
        List<OmborMetrics.Row> rows = new ArrayList<>();
        int n = 0;
        for (String agent : agentIds) {
            try {
                String f = URLEncoder.encode("agent=https://api.moysklad.ru/api/remap/1.2/entity/counterparty/" + agent
                        + ";moment>=" + ms.filterTime(today.minusDays(180).atStartOfDay()), StandardCharsets.UTF_8);
                Map<String, TreeSet<LocalDate>> dates = new HashMap<>();
                for (JsonNode d : ms.listAll("entity/demand?limit=100&expand=positions&filter=" + f, 30)) {
                    if (!d.path("applicable").asBoolean(true)) continue;
                    var m = ms.dtOf(d, "moment");
                    if (m == null) continue;
                    for (JsonNode p : d.path("positions").path("rows")) {
                        String pid = ms.idOf(p.path("assortment"));
                        if (!pid.isBlank()) dates.computeIfAbsent(pid, k -> new TreeSet<>()).add(m.toLocalDate());
                    }
                }
                for (var e : dates.entrySet()) {
                    TreeSet<LocalDate> ds = e.getValue();
                    if (ds.size() < 3) continue;   // kamida 3 xarid — interval ishonchli
                    long span = java.time.temporal.ChronoUnit.DAYS.between(ds.first(), ds.last());
                    double interval = (double) span / (ds.size() - 1);
                    if (interval < 3) continue;
                    long since = java.time.temporal.ChronoUnit.DAYS.between(ds.last(), today);
                    double overdue = since - interval;
                    if (overdue > 0) rows.add(new OmborMetrics.Row(today, 0, agent + "|" + e.getKey(), "HAMKOR_INTERVAL", BigDecimal.valueOf(Math.round(overdue))));
                }
                n++;
            } catch (Exception ex) { log.warn("Hamkor {} intervali: {}", agent, ex.getMessage()); }
        }
        metrics.clear(today, "HAMKOR_INTERVAL");
        metrics.upsert(rows);
        return n;
    }
}
