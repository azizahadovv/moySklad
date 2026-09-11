package uz.kassa.service.ombor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Kassa;
import uz.kassa.domain.OmborSanoq;
import uz.kassa.repo.KassaRepo;
import uz.kassa.repo.OmborSanoqRepo;
import uz.kassa.service.AuditService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * 🏬 Rotatsion sanoq (docs/OMBOR-TZ.md §7.1): tunlik reja — A har abc_a_days, B abc_b_days, C abc_c_days kunda bir;
 * eng eski sanalganlar birinchi; kladovchi fakt kiritadi va tasdiqlaydi; farq — SANOQ_FARQ qoidasi;
 * tasdiqlanmagan — kun yopilmaydi (ombor.sanoq_majburiy).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OmborSanoqService {

    private final OmborSanoqRepo repo;
    private final OmborMetrics metrics;
    private final KassaRepo kassaRepo;
    private final OmborConfig cfg;
    private final AuditService audit;

    /** Bugungi reja (kuniga bir; allaqachon bo'lsa o'tkaziladi). */
    public int planToday() {
        if (!cfg.enabled()) return 0;
        LocalDate today = LocalDate.now(cfg.zone());
        int total = 0;
        Map<String, Integer> abc = new HashMap<>();
        for (Map<String, Object> m : metrics.latest(OmborCalcService.ABC, "kassa_id = 0"))
            abc.put((String) m.get("product_ms_id"), ((BigDecimal) m.get("value")).intValue());
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless() || k.getMoyskladWarehouseId() == null) continue;
            if (repo.countByKassaIdAndPlanDate(k.getId(), today) > 0) continue;
            Map<String, BigDecimal> stock = new HashMap<>();
            for (Map<String, Object> m : metrics.latest(OmborMetrics.QOLDIQ, "kassa_id = ? AND product_ms_id <> ''", k.getId()))
                stock.put((String) m.get("product_ms_id"), (BigDecimal) m.get("value"));
            if (stock.isEmpty()) continue;
            Map<String, LocalDate> last = new HashMap<>();
            for (Object[] o : repo.lastCounted(k.getId())) last.put((String) o[0], (LocalDate) o[1]);
            int nA = 0, nB = 0, nC = 0;
            for (String pid : stock.keySet()) { int c = abc.getOrDefault(pid, 3); if (c == 1) nA++; else if (c == 2) nB++; else nC++; }
            int quota = ceil(nA, cfg.abcDays('A')) + ceil(nB, cfg.abcDays('B')) + ceil(nC, cfg.abcDays('C'));
            List<String> pids = new ArrayList<>(stock.keySet());
            // navbat: hech sanalmagan → eng eski; keyin A, B, C
            pids.sort(Comparator.comparing((String p) -> last.getOrDefault(p, LocalDate.MIN)).thenComparing(p -> abc.getOrDefault(p, 3)));
            List<OmborSanoq> plan = new ArrayList<>();
            for (String pid : pids) {
                if (plan.size() >= quota) break;
                LocalDate l = last.get(pid);
                int cls = abc.getOrDefault(pid, 3);
                if (l != null && l.plusDays(cfg.abcDays(cls == 1 ? 'A' : cls == 2 ? 'B' : 'C')).isAfter(today)) continue;   // hali navbati kelmagan
                plan.add(OmborSanoq.builder().kassaId(k.getId()).productMsId(pid).planDate(today)
                        .abc(cls == 1 ? "A" : cls == 2 ? "B" : "C").systemQty(stock.get(pid)).build());
            }
            repo.saveAll(plan);
            total += plan.size();
        }
        if (total > 0) log.info("Ombor sanoq rejasi: {} ta tovar ({})", total, today);
        return total;
    }

    private static int ceil(int n, int d) { return d <= 0 ? n : (n + d - 1) / d; }

    /** Do'kon bo'yicha ochiq (tasdiqlanmagan) topshiriqlar, bugungacha. */
    public List<OmborSanoq> open(Long kassaId) {
        return repo.findByKassaIdAndStatusNotAndPlanDateLessThanEqualOrderByPlanDateAscIdAsc(kassaId, "TASDIQ", LocalDate.now(cfg.zone()));
    }

    public long openCount(Long kassaId) {
        return repo.countByKassaIdAndStatusNotAndPlanDateLessThanEqual(kassaId, "TASDIQ", LocalDate.now(cfg.zone()));
    }

    public OmborSanoq enterFact(long id, BigDecimal fact, AppUser by) {
        OmborSanoq s = repo.findById(id).orElse(null);
        if (s == null) return null;
        s.setFactQty(fact); s.setStatus("KIRITILDI"); s.setByUserId(by.getId()); s.setAt(Instant.now());
        return repo.save(s);
    }

    /** Fakt kiritilganlarni tasdiqlash (do'kon bo'yicha). Qaytadi: tasdiqlangan soni. */
    public int confirm(Long kassaId, AppUser by) {
        int n = 0;
        for (OmborSanoq s : open(kassaId)) {
            if (s.getFactQty() == null) continue;
            s.setStatus("TASDIQ"); s.setByUserId(by.getId()); s.setAt(Instant.now());
            repo.save(s); n++;
        }
        if (n > 0) audit.log(by.getId(), "OMBOR_SANOQ_TASDIQ", "kassa", kassaId, n + " ta tovar");
        return n;
    }

    /** Kun yopilishini to'sadigan sabab (null — to'siq yo'q). ombor.sanoq_majburiy=1 bo'lsa. */
    public String blockReason(LocalDate day) {
        if (!cfg.enabled() || !cfg.sanoqMajburiy()) return null;
        long n = repo.countByStatusNotAndPlanDateLessThanEqual("TASDIQ", day);
        if (n == 0) return null;
        List<String> parts = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            long c = repo.countByKassaIdAndStatusNotAndPlanDateLessThanEqual(k.getId(), "TASDIQ", day);
            if (c > 0) parts.add(k.getName() + " " + c);
        }
        return "🔢 Sanoq tasdiqlanmagan: " + n + " ta (" + String.join(", ", parts) + "). Kladovchi 🏬 Омбор → 🔢 Санoq da tasdiqlasin.";
    }

    public OmborSanoqRepo repo() { return repo; }
}
