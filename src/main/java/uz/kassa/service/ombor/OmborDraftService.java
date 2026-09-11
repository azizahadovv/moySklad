package uz.kassa.service.ombor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.domain.*;
import uz.kassa.repo.*;
import uz.kassa.service.AuditService;
import uz.kassa.service.LedgerService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.util.*;

/**
 * 🏬 B5: buyurtma qoralamasi (docs/OMBOR-TZ.md §7.3). Bot maslahatchi — MoySklad'ga yozmaydi.
 * Nomzodlar: BUYURTMA_NUQTA ochiq kamchiliklari + QORALAMADA so'rovlar. Miqdor = qoplash_kun × o'rtacha_90 − (qoldiq + yo'lda − rezerv).
 * Yetkazuvchi: landed narx (narx × koef) so'ng muddat; tarixi kam bo'lsa EHTIYOT (miqdor ×0.5). Narx o'zgarishi flag.
 * Pul: kassalar naqd + klik mavjud; total > katta_summa → DIREKTOR bosqichi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OmborDraftService {

    private final OmborQoralamaRepo repo;
    private final OmborQoralamaQatorRepo lineRepo;
    private final OmborKamchilikRepo kRepo;
    private final OmborTovarRepo tovarRepo;
    private final OmborYetkazuvchiRepo supRepo;
    private final OmborNarxService narx;
    private final OmborSorovService sorov;
    private final OmborMetrics metrics;
    private final KassaRepo kassaRepo;
    private final LedgerService ledger;
    private final OmborConfig cfg;
    private final AuditService audit;

    /** Hamma do'kon uchun qayta tuzish (faqat QORALAMA holatidagilar almashtiriladi). Qaytadi: qoralamalar soni. */
    public int buildAll() {
        int n = 0;
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            if (!k.isCashless() && k.getMoyskladWarehouseId() != null) n += build(k.getId());
        return n;
    }

    public int build(long kassaId) {
        for (OmborQoralama q : repo.findByStatusAndKassaId("QORALAMA", kassaId)) repo.delete(q);
        // nomzodlar: tovar → (miqdor, asos, sorovId)
        Map<String, BigDecimal> stock = latest(OmborMetrics.QOLDIQ, kassaId), transit = latest(OmborMetrics.YOLDA, kassaId),
                reserve = latest(OmborMetrics.REZERV, kassaId), avg = latest(OmborCalcService.ORTACHA_90, kassaId), rop = latest(OmborCalcService.BUYURTMA_NUQTA, kassaId);
        Map<String, Object[]> cand = new LinkedHashMap<>();   // pid → {qty, basis, sorovId}
        for (OmborKamchilik k : kRepo.findByRuleCodeAndResolvedAtIsNull("BUYURTMA_NUQTA")) {
            if (!Long.valueOf(kassaId).equals(k.getKassaId())) continue;
            String pid = k.getSubjectKey().substring(0, k.getSubjectKey().indexOf('@'));
            BigDecimal a = avg.getOrDefault(pid, BigDecimal.ZERO), s = stock.getOrDefault(pid, BigDecimal.ZERO);
            BigDecimal need = a.multiply(BigDecimal.valueOf(cfg.coverDays())).subtract(s).subtract(transit.getOrDefault(pid, BigDecimal.ZERO)).add(reserve.getOrDefault(pid, BigDecimal.ZERO));
            if (need.signum() <= 0) continue;
            String basis = "ROP " + str(rop.getOrDefault(pid, BigDecimal.ZERO)) + ", qoldiq " + str(s) + ", 90 kun o'rtacha " + str(a) + "/kun, " + cfg.coverDays() + " kunga";
            cand.put(pid, new Object[]{need.setScale(0, RoundingMode.CEILING).max(BigDecimal.ONE), basis, null});
        }
        for (OmborSorov so : sorov.forDraft()) {
            if (so.getProductMsId() == null || !Long.valueOf(kassaId).equals(so.getKassaId())) continue;
            Object[] c = cand.get(so.getProductMsId());
            if (c == null) cand.put(so.getProductMsId(), new Object[]{so.getQty().max(BigDecimal.ONE), "do'kon so'rovi #" + so.getId() + " (" + OmborSorov.reasonTitle(so.getReason()) + ")", so.getId()});
            else { c[0] = ((BigDecimal) c[0]).max(so.getQty()); c[1] = c[1] + " + so'rov #" + so.getId(); c[2] = so.getId(); }
        }
        if (cand.isEmpty()) return 0;
        int hour = LocalTime.now(cfg.zone()).getHour();
        long cash = cashAvailableSom();
        // yetkazuvchi bo'yicha guruhlash
        Map<String, List<OmborQoralamaQator>> bySup = new LinkedHashMap<>();
        Map<String, String> supName = new HashMap<>();
        for (var e : cand.entrySet()) {
            String pid = e.getKey();
            BigDecimal qty = (BigDecimal) e.getValue()[0];
            String basis = (String) e.getValue()[1];
            Long sorovId = (Long) e.getValue()[2];
            Map<String, OmborNarx> prices = narx.lastPrices(pid);
            String bestSup = null; long bestLanded = Long.MAX_VALUE; int bestLead = 999; OmborNarx bestN = null; OmborYetkazuvchi bestS = null;
            for (var pe : prices.entrySet()) {
                OmborYetkazuvchi s = supRepo.findById(pe.getKey()).orElse(null);
                if (s != null && !s.isActive()) continue;
                BigDecimal coef = s == null ? BigDecimal.ONE : s.getLandedCoef();
                long landed = BigDecimal.valueOf(pe.getValue().getPrice()).multiply(coef).longValue();
                int lead = pe.getValue().getLeadDays() != null ? pe.getValue().getLeadDays() : (s == null ? cfg.leadDays() : s.leadDays(hour));
                if (landed < bestLanded || (landed == bestLanded && lead < bestLead)) { bestSup = pe.getKey(); bestLanded = landed; bestLead = lead; bestN = pe.getValue(); bestS = s; }
            }
            List<String> flags = new ArrayList<>();
            if (sorovId != null) flags.add("SOROV");
            String sb = basis;
            if (bestSup == null) { flags.add("YETKAZUVCHI_YOQ"); bestSup = ""; }
            else {
                sb += ", " + (bestS == null ? "yetkazuvchi" : bestS.getName()) + " " + str(BigDecimal.valueOf(bestN.getPrice()).movePointLeft(2)) + " (" + bestN.getAtDate() + ", " + bestN.getSource().toLowerCase() + "), muddat " + bestLead + " kun";
                if (bestS != null && !bestS.trusted()) { flags.add("EHTIYOT"); qty = qty.multiply(new BigDecimal("0.5")).setScale(0, RoundingMode.CEILING).max(BigDecimal.ONE); sb += ", yangi manba — miqdor ×0.5"; }
                OmborNarx[] two = narx.lastTwo(bestSup, pid);
                if (two[1] != null) {
                    long d = two[0].getPrice() - two[1].getPrice();
                    if (d > 0 && d * 100 / two[1].getPrice() >= 3) flags.add("NARX_OSHDI");
                    if (d < 0 && -d * 100 / two[1].getPrice() >= 3) flags.add("NARX_TUSHDI");
                }
                if (prices.size() > 1) sb += ", " + prices.size() + " yetkazuvchidan eng arzoni";
                if (bestS != null) supName.put(bestSup, bestS.getName());
            }
            bySup.computeIfAbsent(bestSup, k -> new ArrayList<>()).add(OmborQoralamaQator.builder().productMsId(pid).qty(qty)
                    .price(bestN == null ? 0 : bestN.getPrice()).landedPrice(bestSup.isBlank() ? 0 : bestLanded).basis(cut(sb, 390)).flags(String.join(",", flags)).sorovId(sorovId).build());
        }
        int n = 0;
        for (var e : bySup.entrySet()) {
            long total = 0;
            for (OmborQoralamaQator l : e.getValue()) total += l.lineTotal();
            OmborQoralama q = repo.save(OmborQoralama.builder().kassaId(kassaId).agentMsId(e.getKey().isBlank() ? null : e.getKey())
                    .agentName(e.getKey().isBlank() ? "yetkazuvchi tanlanmagan" : supName.getOrDefault(e.getKey(), e.getKey())).total(total).cashAvailable(cash)
                    .note(total / 100 > cash ? "⚠️ Summa mavjud puldan katta" : "").build());
            for (OmborQoralamaQator l : e.getValue()) { l.setQoralamaId(q.getId()); lineRepo.save(l); }
            n++;
        }
        log.info("Ombor qoralama: kassa {} — {} ta ({} qator)", kassaId, n, cand.size());
        return n;
    }

    /* ==================== tasdiq zanjiri ==================== */

    /** Keyingi bosqich (rol tekshiruvi chaqiruvchida). Qaytadi: yangi status yoki null. */
    public String advance(long id, AppUser by, String role) {
        OmborQoralama q = repo.findById(id).orElse(null);
        if (q == null || !q.open()) return null;
        String next = switch (q.getStatus()) {
            case "QORALAMA" -> role.equals("ZAKUPSHIK") ? "ZAKUPSHIK" : null;
            case "ZAKUPSHIK" -> role.equals("ZAVSKLAD") ? (needsDirector(q) ? "ZAVSKLAD" : "TASDIQ") : null;
            case "ZAVSKLAD" -> role.equals("DIREKTOR") ? "TASDIQ" : null;
            case "TASDIQ" -> role.equals("ZAKUPSHIK") ? "YUBORILDI" : null;
            default -> null;
        };
        if (next == null) return null;
        switch (role) { case "ZAKUPSHIK" -> { if (q.getStatus().equals("TASDIQ")) { q.setSentBy(by.getId()); q.setSentAt(Instant.now()); } else q.setZakupshikBy(by.getId()); }
            case "ZAVSKLAD" -> q.setZavskladBy(by.getId()); case "DIREKTOR" -> q.setDirektorBy(by.getId()); default -> { } }
        q.setStatus(next); q.setUpdatedAt(Instant.now()); repo.save(q);
        if (next.equals("YUBORILDI")) for (OmborQoralamaQator l : lineRepo.findByQoralamaIdOrderByIdAsc(id)) if (l.getSorovId() != null) sorov.answer(l.getSorovId(), by, "BAJARILDI", "qoralama #" + id + " yuborildi");
        audit.log(by.getId(), "OMBOR_QORALAMA_" + next, "ombor_qoralama", id, role);
        return next;
    }

    public void cancel(long id, AppUser by, String reason) {
        repo.findById(id).ifPresent(q -> { q.setStatus("BEKOR"); q.setNote((q.getNote() + "\n❌ " + reason).trim()); q.setUpdatedAt(Instant.now()); repo.save(q);
            audit.log(by.getId(), "OMBOR_QORALAMA_BEKOR", "ombor_qoralama", id, reason); });
    }

    public void setQty(long lineId, BigDecimal qty, AppUser by) {
        lineRepo.findById(lineId).ifPresent(l -> {
            l.setQty(qty); lineRepo.save(l);
            repo.findById(l.getQoralamaId()).ifPresent(q -> { long t = 0; for (OmborQoralamaQator x : lineRepo.findByQoralamaIdOrderByIdAsc(q.getId())) t += x.lineTotal(); q.setTotal(t); q.setUpdatedAt(Instant.now()); repo.save(q); });
            audit.log(by.getId(), "OMBOR_QORALAMA_QATOR", "ombor_qoralama_qator", lineId, "qty=" + qty);
        });
    }

    public boolean needsDirector(OmborQoralama q) { return q.getTotal() / 100 > cfg.kattaSumma(); }

    /** Keyingi tasdiqlovchi roli (xabar uchun). */
    public String nextRole(OmborQoralama q) {
        return switch (q.getStatus()) { case "QORALAMA" -> "ZAKUPSHIK"; case "ZAKUPSHIK" -> "ZAVSKLAD"; case "ZAVSKLAD" -> "DIREKTOR"; case "TASDIQ" -> "ZAKUPSHIK"; default -> null; };
    }

    public long cashAvailableSom() {
        long s = 0;
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;
            s += ledger.view(OwnerType.KASSA, k.getId(), MoneyType.NAQD).available() + ledger.view(OwnerType.KASSA, k.getId(), MoneyType.KLIK).available();
        }
        s += ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD).available() + ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.KLIK).available();
        return s;
    }

    public OmborQoralamaRepo repo() { return repo; }
    public OmborQoralamaQatorRepo lines() { return lineRepo; }

    private Map<String, BigDecimal> latest(String code, long kassa) {
        Map<String, BigDecimal> m = new HashMap<>();
        for (Map<String, Object> r : metrics.latest(code, "kassa_id = ? AND product_ms_id <> ''", kassa)) m.put((String) r.get("product_ms_id"), (BigDecimal) r.get("value"));
        return m;
    }

    private static String str(BigDecimal v) { return v.stripTrailingZeros().toPlainString(); }
    private static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }
}
