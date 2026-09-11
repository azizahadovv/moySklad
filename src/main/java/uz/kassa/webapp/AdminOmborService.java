package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uz.kassa.bot.Sender;
import uz.kassa.config.AppProps;
import uz.kassa.domain.*;
import uz.kassa.repo.*;
import uz.kassa.service.BusinessException;
import uz.kassa.service.ombor.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static uz.kassa.webapp.AdminApiService.mapOf;

/**
 * 🌐 Админ панел → Ҳисоботлар → 🏬 Омбор (docs/OMBOR-TZ.md §10): dashboard, kamchiliklar, qoralamalar, sanoq, so'rovlar, Excel.
 * Bot bilan bir manba: OmborRuleEngine / OmborDraftService / OmborSanoqService.
 */
@Service
@RequiredArgsConstructor
public class AdminOmborService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final int PAGE = 50;

    private final OmborRuleEngine engine;
    private final OmborRecipients rec;
    private final OmborSyncService sync;
    private final OmborSalesService sales;
    private final OmborCalcService calc;
    private final OmborSanoqService sanoq;
    private final OmborDraftService draft;
    private final OmborSorovService sorov;
    private final OmborMetrics metrics;
    private final OmborConfig cfg;
    private final OmborKamchilikRepo kRepo;
    private final OmborTovarRepo tovarRepo;
    private final KassaRepo kassaRepo;
    private final ExcelReportService excel;
    private final Sender sender;
    private final AppProps props;
    private final OmborExcelService omborExcel;

    private ZoneId zone() { return props.zoneId(); }

    public Map<String, Object> dashboard() {
        Map<String, OmborQoida> rules = engine.rulesMap();
        List<Map<String, Object>> ruleRows = new ArrayList<>();
        for (OmborQoida r : rules.values().stream().sorted(Comparator.comparingInt(OmborQoida::getSort)).toList())
            ruleRows.add(mapOf("code", r.getCode(), "title", r.getTitle(), "severity", r.getSeverity(), "enabled", r.isEnabled(), "open", engine.openByRule(r.getCode())));
        List<Map<String, Object>> kassalar = new ArrayList<>();
        LocalDate today = LocalDate.now(zone());
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;
            long[] st = metrics.stockStats(k.getId());
            var fr = metrics.latest(OmborCalcService.FILL_RATE_30, "kassa_id = ? AND product_ms_id = ''", k.getId());
            kassalar.add(mapOf("id", k.getId(), "name", k.getName(), "bound", k.getMoyskladWarehouseId() != null, "tovar", st[0], "manfiy", st[1],
                    "fill", fr.isEmpty() ? null : ((BigDecimal) fr.get(0).get("value")).doubleValue(),
                    "open", kRepo.countByResolvedAtIsNullAndKassaId(k.getId()),
                    "sanoq", sanoq.repo().countByKassaIdAndStatusNotAndPlanDateLessThanEqual(k.getId(), "TASDIQ", today)));
        }
        List<Map<String, Object>> syncRows = new ArrayList<>();
        for (OmborSinxron s : sync.status())
            syncRows.add(mapOf("entity", s.getEntity(), "ok", s.getLastError() == null, "at", s.getLastOkAt() == null ? "" : DTF.format(s.getLastOkAt().atZone(zone())), "rows", s.getRowsN(), "error", s.getLastError() == null ? "" : s.getLastError()));
        var fr = metrics.latest(OmborCalcService.FILL_RATE_30, "kassa_id = 0 AND product_ms_id = ''");
        return mapOf("asOf", DTF.format(Instant.now().atZone(zone())), "enabled", cfg.enabled(),
                "open", engine.openCount(), "tovar", tovarRepo.countByArchivedFalse(),
                "stockDate", String.valueOf(metrics.lastDate(OmborMetrics.QOLDIQ)),
                "salesDone", sales.backfillDone(), "salesCursor", cfg.get(OmborConfig.SALES_CURSOR).orElse(""),
                "fill", fr.isEmpty() ? null : ((BigDecimal) fr.get(0).get("value")).doubleValue(),
                "drafts", draft.repo().countByStatusIn(List.of("QORALAMA", "ZAKUPSHIK", "ZAVSKLAD", "DIREKTOR", "TASDIQ")),
                "sorov", sorov.repo().countByStatus("YANGI"), "cash", draft.cashAvailableSom(),
                "rules", ruleRows, "kassalar", kassalar, "sync", syncRows);
    }

    public Map<String, Object> issues(AppUser u, Long kassa, String rule, int page) {
        Map<String, OmborQoida> rules = engine.rulesMap();
        List<OmborKamchilik> all = new ArrayList<>();
        for (OmborKamchilik k : kRepo.findByResolvedAtIsNullOrderBySinceDesc()) {
            if (!rec.canSee(u, k)) continue;
            if (kassa != null && kassa > 0 && !kassa.equals(k.getKassaId())) continue;
            if (rule != null && !rule.isBlank() && !rule.equals(k.getRuleCode())) continue;
            all.add(k);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int from = Math.max(0, page) * PAGE;
        for (int i = from; i < Math.min(all.size(), from + PAGE); i++) rows.add(issueRow(all.get(i), rules));
        Map<String, Integer> byRule = new LinkedHashMap<>();
        for (OmborKamchilik k : kRepo.findByResolvedAtIsNullOrderBySinceDesc()) if (rec.canSee(u, k)) byRule.merge(k.getRuleCode(), 1, Integer::sum);
        List<Map<String, Object>> ruleRows = new ArrayList<>();
        for (var e : byRule.entrySet()) { OmborQoida r = rules.get(e.getKey()); ruleRows.add(mapOf("code", e.getKey(), "title", r == null ? e.getKey() : r.getTitle(), "severity", r == null ? "" : r.getSeverity(), "count", e.getValue())); }
        List<Map<String, Object>> kassalar = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) if (!k.isCashless()) kassalar.add(mapOf("id", k.getId(), "name", k.getName()));
        return mapOf("rows", rows, "total", all.size(), "page", page, "pages", (all.size() + PAGE - 1) / PAGE, "rules", ruleRows, "kassalar", kassalar);
    }

    private Map<String, Object> issueRow(OmborKamchilik k, Map<String, OmborQoida> rules) {
        OmborQoida r = rules.get(k.getRuleCode());
        return mapOf("id", k.getId(), "rule", k.getRuleCode(), "ruleTitle", r == null ? k.getRuleCode() : r.getTitle(), "severity", r == null ? "" : r.getSeverity(),
                "title", k.getTitle(), "detail", k.getDetail(), "kassa", k.getKassaId() == null ? "" : rec.notifier().kassaName(k.getKassaId()),
                "owner", k.getOwnerUserId() == null ? "" : rec.notifier().userName(k.getOwnerUserId()),
                "since", DTF.format(k.getSince().atZone(zone())), "esc1", k.getEsc1At() != null, "esc2", k.getEsc2At() != null,
                "answers", engine.answers(r), "open", k.open());
    }

    public Map<String, Object> issue(AppUser u, long id) {
        OmborKamchilik k = kRepo.findById(id).orElseThrow(() -> new BusinessException("Топилмади"));
        if (!rec.canSee(u, k)) throw new BusinessException("Рухсат йўқ");
        Map<String, Object> m = issueRow(k, engine.rulesMap());
        if (!k.open()) { m.put("resolvedAt", DTF.format(k.getResolvedAt().atZone(zone()))); m.put("resolvedBy", k.getResolvedBy() == null ? "avto" : rec.notifier().userName(k.getResolvedBy())); m.put("answer", String.valueOf(k.getAnswer())); }
        return m;
    }

    public Map<String, Object> resolve(AppUser u, long id, String answer) {
        OmborKamchilik k = kRepo.findById(id).orElseThrow(() -> new BusinessException("Топилмади"));
        if (!rec.canSee(u, k)) throw new BusinessException("Рухсат йўқ");
        engine.resolve(id, u, answer == null || answer.isBlank() ? OmborRuleEngine.ANSWER_FIXED : answer);
        return mapOf("ok", true);
    }

    public Map<String, Object> drafts(AppUser u, boolean all) {
        List<OmborQoralama> list = all ? draft.repo().findTop30ByOrderByUpdatedAtDesc()
                : draft.repo().findByStatusInOrderByUpdatedAtDesc(List.of("QORALAMA", "ZAKUPSHIK", "ZAVSKLAD", "DIREKTOR", "TASDIQ"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OmborQoralama q : list) rows.add(draftRow(q));
        return mapOf("rows", rows, "cash", draft.cashAvailableSom(), "katta", cfg.kattaSumma());
    }

    private Map<String, Object> draftRow(OmborQoralama q) {
        return mapOf("id", q.getId(), "kassa", q.getKassaId() == null ? "" : rec.notifier().kassaName(q.getKassaId()), "agent", q.getAgentName(), "status", q.getStatus(),
                "statusTitle", OmborQoralama.statusTitle(q.getStatus()), "total", q.getTotal(), "cash", q.getCashAvailable(), "note", q.getNote(),
                "updatedAt", DTF.format(q.getUpdatedAt().atZone(zone())), "director", draft.needsDirector(q), "next", String.valueOf(draft.nextRole(q)), "open", q.open());
    }

    public Map<String, Object> draftCard(long id) {
        OmborQoralama q = draft.repo().findById(id).orElseThrow(() -> new BusinessException("Топилмади"));
        Map<String, Object> m = draftRow(q);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (OmborQoralamaQator l : draft.lines().findByQoralamaIdOrderByIdAsc(id))
            lines.add(mapOf("id", l.getId(), "name", tovarRepo.findById(l.getProductMsId()).map(OmborTovar::getName).orElse(l.getProductMsId()),
                    "qty", l.getQty(), "price", l.getPrice(), "landed", l.getLandedPrice(), "total", l.lineTotal(), "basis", l.getBasis(), "flags", l.getFlags()));
        m.put("lines", lines);
        return m;
    }

    public Map<String, Object> draftAdvance(AppUser u, long id) {
        OmborQoralama q = draft.repo().findById(id).orElseThrow(() -> new BusinessException("Топилмади"));
        String next = draft.nextRole(q);
        if (next == null) throw new BusinessException("Қоралама ёпиқ");
        boolean ok = switch (next) { case "ZAKUPSHIK" -> u.getRole() != Role.KASSIR || cfg.hasRole(u.getId(), "ZAKUPSHIK");
            case "ZAVSKLAD" -> u.getRole() != Role.KASSIR || cfg.hasRole(u.getId(), "ZAVSKLAD");
            case "DIREKTOR" -> u.getRole() == Role.SUPERADMIN || cfg.hasRole(u.getId(), "DIREKTOR"); default -> false; };
        if (!ok) throw new BusinessException("Бу босқич сизга тегишли эмас: " + OmborConfig.roleTitle(next));
        String st = draft.advance(id, u, next);
        return mapOf("ok", st != null, "status", st);
    }

    public Map<String, Object> draftCancel(AppUser u, long id, String reason) {
        draft.cancel(id, u, reason == null || reason.isBlank() ? "web" : reason);
        return mapOf("ok", true);
    }

    public Map<String, Object> sanoqSummary() {
        LocalDate today = LocalDate.now(zone());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless() || k.getMoyskladWarehouseId() == null) continue;
            List<OmborSanoq> open = sanoq.open(k.getId());
            List<Map<String, Object>> items = new ArrayList<>();
            for (OmborSanoq s : open) items.add(mapOf("id", s.getId(), "name", tovarRepo.findById(s.getProductMsId()).map(OmborTovar::getName).orElse(s.getProductMsId()),
                    "abc", s.getAbc(), "system", s.getSystemQty(), "fact", s.getFactQty(), "status", s.getStatus(), "date", s.getPlanDate().toString()));
            rows.add(mapOf("id", k.getId(), "name", k.getName(), "open", open.size(), "today", sanoq.repo().countByKassaIdAndPlanDate(k.getId(), today), "items", items));
        }
        return mapOf("rows", rows, "majburiy", cfg.sanoqMajburiy(), "abc", List.of(cfg.abcDays('A'), cfg.abcDays('B'), cfg.abcDays('C')));
    }

    public Map<String, Object> sorovlar() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OmborSorov s : sorov.repo().findByStatusInOrderByCreatedAtDesc(List.of("YANGI", "KORILDI", "QORALAMADA")))
            rows.add(mapOf("id", s.getId(), "kassa", s.getKassaId() == null ? "" : rec.notifier().kassaName(s.getKassaId()),
                    "what", s.getProductMsId() != null ? tovarRepo.findById(s.getProductMsId()).map(OmborTovar::getName).orElse(s.getProductMsId()) : "🆕 " + s.getText(),
                    "qty", s.getQty(), "reason", OmborSorov.reasonTitle(s.getReason()), "status", s.getStatus(), "statusTitle", OmborSorov.statusTitle(s.getStatus()),
                    "by", rec.notifier().userName(s.getByUserId()), "at", DTF.format(s.getCreatedAt().atZone(zone())), "answer", s.getAnswer()));
        return mapOf("rows", rows);
    }

    public Map<String, Object> sorovAnswer(AppUser u, long id, String status, String note) {
        if (u.getRole() == Role.KASSIR && !cfg.hasRole(u.getId(), "ZAKUPSHIK")) throw new BusinessException("Фақат закупщик");
        if (!List.of("KORILDI", "QORALAMADA", "RAD").contains(status)) throw new BusinessException("Ҳолат нотўғри");
        sorov.answer(id, u, status, note);
        return mapOf("ok", true);
    }

    /** Ochiq kamchiliklar Excel (har qoida turi o'z faktli varag'ida) → foydalanuvchi chatiga. */
    public Map<String, Object> issuesExcel(AppUser u, Long kassa, String rule) {
        if (u.getTelegramId() == null) throw new BusinessException("Telegram уланмаган");
        Map<String, OmborQoida> rules = engine.rulesMap();
        List<OmborKamchilik> list = new ArrayList<>();
        for (OmborKamchilik k : kRepo.findByResolvedAtIsNullOrderBySinceDesc()) {
            if (!rec.canSee(u, k) || (kassa != null && kassa > 0 && !kassa.equals(k.getKassaId()))) continue;
            if (rule != null && !rule.isBlank() && !rule.equals(k.getRuleCode())) continue;
            list.add(k);
        }
        byte[] data = omborExcel.build(list, rules);
        sender.sendDocument(u.getTelegramId(), data, "ombor-kamchiliklar-" + LocalDate.now(zone()) + ".xlsx", "🏬 Ombor kamchiliklari: " + list.size() + " ta (varaqlar qoida turi bo'yicha)");
        return mapOf("ok", true, "count", list.size());
    }

    public Map<String, Object> refresh(AppUser u) {
        if (u.getRole() == Role.KASSIR) throw new BusinessException("Рухсат йўқ");
        new Thread(() -> { try { sync.syncAll(); sales.refreshRecent(); calc.nightly(); sanoq.planToday(); engine.tick(); } catch (Exception ignored) { } }, "ombor-web-refresh").start();
        return mapOf("ok", true);
    }
}
