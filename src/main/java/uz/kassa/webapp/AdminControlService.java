package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uz.kassa.bot.Sender;
import uz.kassa.config.AppProps;
import uz.kassa.domain.AgentCheck;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Kassa;
import uz.kassa.domain.Role;
import uz.kassa.domain.Shipment;
import uz.kassa.repo.KassaRepo;
import uz.kassa.service.BusinessException;
import uz.kassa.service.control.AgentCheckService;
import uz.kassa.service.control.ControlConfig;
import uz.kassa.service.control.ControlNotifier;
import uz.kassa.service.control.ControlRefreshService;
import uz.kassa.service.control.ControlStatsService;
import uz.kassa.service.control.ShipmentControlService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static uz.kassa.webapp.AdminApiService.mapOf;

/**
 * 🌐 Админ панел → Ҳисоботлар → 🧾 Қарздорлар / ⚠️ Контрагент хатолари (docs/KONTRAGENT-NAZORAT.md).
 * Bot bo'limlari bilan bir manba: ShipmentControlService / AgentCheckService.
 */
@Service
@RequiredArgsConstructor
public class AdminControlService {

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final int PAGE = 100;

    private final ShipmentControlService ships;
    private final AgentCheckService agents;
    private final ControlStatsService stats;
    private final ControlRefreshService refreshSvc;
    private final ControlNotifier notifier;
    private final ControlConfig cfg;
    private final KassaRepo kassaRepo;
    private final ExcelReportService excel;
    private final Sender sender;
    private final AppProps props;

    private ZoneId zone() { return props.zoneId(); }


    /* ==================== 🧾 QARZDORLAR ==================== */

    public Map<String, Object> debts(AppUser u, Long kassaId, int page, String state) {
        List<Shipment> unfiltered = ships.visibleFor(u, kassaId != null && kassaId > 0 ? kassaId : null);
        List<Shipment> all = state == null || state.isBlank() ? unfiltered
                : ships.visibleFor(u, kassaId != null && kassaId > 0 ? kassaId : null, state);
        List<Map<String, Object>> states = new ArrayList<>();
        for (String st : ShipmentControlService.distinctStates(unfiltered))
            states.add(mapOf("name", st, "count", ShipmentControlService.countState(unfiltered, st), "quiet", ships.isQuietState(st)));
        LocalDate today = LocalDate.now(zone());
        long total = 0, otgan = 0, muddatsiz = 0;
        for (Shipment s : all) {
            total += s.remain();
            if (s.getDueAt() == null) muddatsiz++;
            else if (s.getDueAt().isBefore(today)) otgan++;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int from = Math.max(0, page) * PAGE;
        for (int i = from; i < Math.min(all.size(), from + PAGE); i++) rows.add(row(all.get(i), today));
        List<Map<String, Object>> kassalar = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            if (!k.isCashless()) kassalar.add(mapOf("id", k.getId(), "name", k.getName()));
        return mapOf("asOf", DTF.format(Instant.now().atZone(zone())),
                "enabled", cfg.enabled(),
                "xulosa", mapOf("soni", all.size(), "summa", total, "otgan", otgan, "muddatsiz", muddatsiz),
                "kassalar", kassalar, "rows", rows, "states", states, "state", state == null ? "" : state,
                "page", page, "pages", (all.size() + PAGE - 1) / PAGE);
    }


    public Map<String, Object> debt(AppUser u, long id) {
        Shipment s = ships.find(id).orElseThrow(() -> new BusinessException("Ёзув топилмади"));
        if (!ships.canSee(u, s)) throw new BusinessException("Рухсат йўқ");
        Map<String, Object> m = row(s, LocalDate.now(zone()));
        m.put("canClose", s.getControlStatus() == Shipment.Status.QARZ
                && (u.getRole() == Role.SUPERADMIN || u.getRole() == Role.BUXGALTER));
        m.put("superadmin", u.getRole() == Role.SUPERADMIN);
        m.put("closeReason", s.getCloseReason() == null ? "" : s.getCloseReason());
        m.put("closedAt", s.getClosedAt() == null ? "" : DTF.format(s.getClosedAt().atZone(zone())));
        m.put("closedBy", s.getClosedBy() == null ? "" : notifier.userName(s.getClosedBy()));
        m.put("reminderId", s.getReminderId() == null ? 0 : s.getReminderId());
        return m;
    }


    public Map<String, Object> refresh(AppUser u, long id) {
        Shipment s = ships.find(id).orElseThrow(() -> new BusinessException("Ёзув топилмади"));
        if (!ships.canSee(u, s)) throw new BusinessException("Рухсат йўқ");
        boolean found = ships.refresh(s);
        return mapOf("ok", true, "found", found);
    }


    /** ✅ Ёпиш: балансни MoySklad'дан қайта ўқийди; 0 бўлмаса фақат SuperAdmin сабаб билан. */
    public Map<String, Object> close(AppUser u, long id, String reason) {
        Shipment s = ships.find(id).orElseThrow(() -> new BusinessException("Ёзув топилмади"));
        if (!ships.canSee(u, s)) throw new BusinessException("Рухсат йўқ");
        if (u.getRole() != Role.SUPERADMIN && u.getRole() != Role.BUXGALTER)
            throw new BusinessException("Фақат бухгалтер ёки SuperAdmin ёпа олади");
        String res = ships.closeManual(s, u, reason);
        if (res.equals("OK") || res.equals("GONE")) return mapOf("result", res);
        String bal = res.substring(res.indexOf(':') + 1);
        return mapOf("result", res.substring(0, res.indexOf(':')), "balance", bal);
    }


    private Map<String, Object> row(Shipment s, LocalDate today) {
        String holat = s.getDueAt() == null ? "yoq" : s.getDueAt().isBefore(today) ? "otgan"
                : s.getDueAt().equals(today) ? "bugun" : "kutilmoqda";
        return mapOf("id", s.getId(), "docNo", s.getDocNo(),
                "moment", s.getMoment() == null ? "" : s.getMoment().format(DTF),
                "kassaId", s.getKassaId() == null ? 0 : s.getKassaId(),
                "kassa", s.getKassaId() == null ? "" : notifier.kassaName(s.getKassaId()),
                "xodim", ships.ownerLabel(s), "xodimLinked", s.getOwnerUserId() != null,
                "agent", s.getAgentName(), "phone", s.getAgentPhone(),
                "sum", s.getSum(), "payed", s.getPayedSum(), "remain", s.remain(),
                "balance", s.getAgentBalance() == null ? null : s.getAgentBalance(),
                "state", s.getState(), "status", s.getControlStatus().name(),
                "due", s.getDueAt() == null ? "" : s.getDueAt().format(DF), "holat", holat,
                "dueLabel", ShipmentControlService.dueLabel(s, today),
                "masul", s.getMasul(), "comment", s.getComment() == null ? "" : s.getComment(),
                "days", s.getDebtSince() == null ? 0 : ChronoUnit.DAYS.between(s.getDebtSince(), Instant.now()),
                "msUrl", ControlNotifier.MS_DEMAND_URL + s.getMsId(),
                "agentUrl", s.getAgentMsId() == null ? "" : ControlNotifier.MS_AGENT_URL + s.getAgentMsId());
    }


    public Map<String, Object> excelDebts(AppUser u, Long kassaId) {
        if (u.getTelegramId() == null) throw new BusinessException("Telegram уланмаган");
        List<Shipment> list = ships.visibleFor(u, kassaId != null && kassaId > 0 ? kassaId : null);
        byte[] xlsx = excel.buildDebts(list, notifier::userName, notifier::kassaName, zone());
        sender.sendDocument(u.getTelegramId(), xlsx, "qarzdorlar_" + LocalDate.now(zone()) + ".xlsx",
                "🧾 Qarzdorlar: " + list.size() + " ta");
        return mapOf("ok", true, "count", list.size());
    }


    /* ==================== 📊 STATISTIKA · 🔄 YANGILASH ==================== */

    public Map<String, Object> stats(AppUser u, int days) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var r : stats.rows(u, days))
            rows.add(mapOf("name", r.name, "linked", r.linked, "kassa", stats.kassaName(r.kassaId),
                    "kgFound", r.kgFound, "kgFixed", r.kgFixed, "kgOpen", r.kgOpen,
                    "otgFound", r.otgFound(), "otgFixed", r.otgFixed, "otgOpen", r.otgOpen,
                    "qarzOpen", r.qarzOpen, "qarzOpenSum", r.qarzOpenSum, "qarzClosed", r.qarzClosed));
        return mapOf("days", days, "rows", rows, "asOf", DTF.format(Instant.now().atZone(zone())),
                "lastRefresh", refreshSvc.lastResult());
    }

    /** 🔄 MoySklad'dan darhol yangilash (60 s sovish vaqti). */
    public Map<String, Object> refreshAll(AppUser u) {
        long r = refreshSvc.start(() -> { });
        return mapOf("started", r == 0, "wait", Math.max(0, r), "running", r < 0, "last", refreshSvc.lastResult());
    }


    /* ==================== ⚠️ KONTRAGENT XATOLARI ==================== */

    public Map<String, Object> errors(AppUser u) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentCheck ac : agents.visibleFor(u)) rows.add(errRow(ac, null));
        List<Map<String, Object>> shipsRows = new ArrayList<>();
        List<Shipment> issues = ships.visibleIssuesFor(u, null);
        for (int i = 0; i < Math.min(issues.size(), 300); i++) {
            Shipment s = issues.get(i);
            shipsRows.add(mapOf("id", s.getId(), "docNo", s.getDocNo(), "agent", s.getAgentName(),
                    "xodim", ships.ownerLabel(s), "kassa", s.getKassaId() == null ? "" : notifier.kassaName(s.getKassaId()),
                    "moment", s.getMoment() == null ? "" : s.getMoment().format(DTF), "remain", s.remain(),
                    "codes", s.issueList(), "labels", ShipmentControlService.issueLabels(s.issueList())));
        }
        return mapOf("asOf", DTF.format(Instant.now().atZone(zone())), "rows", rows,
                "rules", cfg.rulesOff().isEmpty() ? "K1–K8" : "o'chiq: " + String.join(",", cfg.rulesOff()),
                "ships", shipsRows, "shipsTotal", issues.size(), "shipsSummary", ShipmentControlService.issueSummary(issues));
    }


    public Map<String, Object> error(AppUser u, long id, boolean recheck) {
        AgentCheck ac = agents.find(id).orElseThrow(() -> new BusinessException("Ёзув топилмади"));
        List<AgentCheckService.Violation> v = null;
        String note = "";
        if (recheck) {
            try {
                v = agents.recheck(ac);
                ac = agents.find(id).orElse(ac);
                if (v == null) note = "Контрагент MoySklad'да топилмади (ўчирилган)";
                else if (v.isEmpty()) note = "Ҳозир хато йўқ ✅";
            } catch (Exception e) { note = "MoySklad ўқилмади: " + e.getMessage(); }
        }
        Map<String, Object> m = errRow(ac, v);
        m.put("note", note);
        m.put("superadmin", u.getRole() == Role.SUPERADMIN);
        return m;
    }


    public Map<String, Object> ignore(AppUser u, long id) {
        if (u.getRole() != Role.SUPERADMIN) throw new BusinessException("Фақат SuperAdmin");
        AgentCheck ac = agents.find(id).orElseThrow(() -> new BusinessException("Ёзув топилмади"));
        agents.ignore(ac, u);
        return mapOf("ok", true);
    }


    private Map<String, Object> errRow(AgentCheck ac, List<AgentCheckService.Violation> live) {
        List<String> titles = new ArrayList<>();
        if (live != null) for (var x : live) titles.add(x.text());
        else for (String c : ac.violationList()) titles.add(AgentCheckService.shortTitle(c) + " — " + ControlConfig.ruleTitle(c));
        return mapOf("id", ac.getId(), "agent", ac.getAgentName(),
                "created", ac.getMsCreatedAt() == null ? "" : ac.getMsCreatedAt().format(DTF),
                "xodim", agents.who(ac), "uid", ac.getCreatedUid() == null ? "" : ac.getCreatedUid(),
                "kassa", ac.getKassaId() == null ? "" : notifier.kassaName(ac.getKassaId()),
                "codes", ac.violationList(), "titles", titles, "status", ac.getStatus().name(),
                "notified", ac.getNotifiedAt() == null ? "" : DTF.format(ac.getNotifiedAt().atZone(zone())),
                "escalated", ac.getEscalatedAt() != null,
                "msUrl", ControlNotifier.MS_AGENT_URL + ac.getAgentMsId());
    }


    public Map<String, Object> excelErrors(AppUser u) {
        if (u.getTelegramId() == null) throw new BusinessException("Telegram уланмаган");
        List<AgentCheck> list = agents.visibleFor(u);
        byte[] xlsx = excel.buildAgentErrors(list, notifier::userName, notifier::kassaName, ControlConfig::ruleTitle);
        sender.sendDocument(u.getTelegramId(), xlsx, "kontragent_xatolari_" + LocalDate.now(zone()) + ".xlsx",
                "⚠️ Tuzatilmagan kontragentlar: " + list.size() + " ta");
        return mapOf("ok", true, "count", list.size());
    }
}
