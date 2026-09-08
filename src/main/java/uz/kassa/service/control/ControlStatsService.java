package uz.kassa.service.control;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uz.kassa.domain.AgentCheck;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Role;
import uz.kassa.domain.Shipment;
import uz.kassa.repo.AgentCheckRepo;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.AuditRepo;
import uz.kassa.repo.ShipmentRepo;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 📊 Nazorat statistikasi — xodim kesimida: nechta xato topildi, nechtasi tuzatildi, nechtasi ochiq.
 *   🏢 kontragent xatolari — agent_checks (topilgan sana bo'yicha davr);
 *   📦 otgruzka kamchiliklari — ochiq: shipments.issues, tuzatilgan: audit OTG_KAMCHILIK_TUZATILDI (davr);
 *   🧾 qarz — ochiq: QARZ, yopilgan: YOPILDI closed_at (davr).
 * Botga bog'lanmagan xodimlar (MoySklad nomi bilan) alohida qator.
 */
@Service
@RequiredArgsConstructor
public class ControlStatsService {

    private final AgentCheckRepo agentRepo;
    private final ShipmentRepo shipRepo;
    private final AuditRepo auditRepo;
    private final AppUserRepo userRepo;
    private final ControlNotifier notifier;

    public static final class Row {
        public String key;
        public Long userId;
        public String name = "";
        public Long kassaId;
        public boolean linked;
        public long kgFound, kgFixed, kgOpen;
        public long otgFixed, otgOpen;
        public long qarzOpen, qarzOpenSum, qarzClosed;

        public long otgFound() { return otgFixed + otgOpen; }
        public long openTotal() { return kgOpen + otgOpen; }
        public boolean any() { return kgFound + otgFound() + qarzOpen + qarzClosed > 0; }
    }

    /** days: 0 — butun davr. Ko'rinish: kassir — o'zi; rahbar — otdeli; bux/SA — hammasi. */
    public List<Row> rows(AppUser viewer, int days) {
        Instant from = days <= 0 ? Instant.EPOCH : Instant.now().minus(days, ChronoUnit.DAYS);
        Map<String, Row> acc = new LinkedHashMap<>();

        for (AgentCheck ac : agentRepo.findAll()) {
            if (ac.getStatus() == AgentCheck.Status.OK) continue;
            if (ac.getCreatedAt() != null && ac.getCreatedAt().isBefore(from)) continue;
            Row r = row(acc, ac.getCreatorUserId(), ac.getCreatedUid(), ac.getKassaId());
            r.kgFound++;
            if (ac.getStatus() == AgentCheck.Status.TUZATILDI) r.kgFixed++;
            else if (ac.getStatus() == AgentCheck.Status.OCHIQ) r.kgOpen++;
        }
        for (Shipment s : shipRepo.findByControlStatusOrderByDueAtAscMomentAsc(Shipment.Status.QARZ)) {
            Row r = row(acc, s.getOwnerUserId(), s.getOwnerName(), s.getKassaId());
            r.qarzOpen++;
            r.qarzOpenSum += s.remain();
            if (!s.getIssues().isEmpty()) r.otgOpen++;
        }
        for (Shipment s : shipRepo.findByControlStatusAndClosedAtAfter(Shipment.Status.YOPILDI, from)) {
            Row r = row(acc, s.getOwnerUserId(), s.getOwnerName(), s.getKassaId());
            r.qarzClosed++;
        }
        for (Object[] o : auditRepo.countByUserAndAction(List.of("OTG_KAMCHILIK_TUZATILDI"), from)) {
            Long uid = (Long) o[0];
            if (uid == null) continue;
            row(acc, uid, null, null).otgFixed += ((Number) o[2]).longValue();
        }

        // nom / otdel to'ldirish
        Map<Long, AppUser> users = new HashMap<>();
        for (AppUser u : userRepo.findAll()) users.put(u.getId(), u);
        for (Row r : acc.values()) {
            if (r.userId != null) {
                AppUser u = users.get(r.userId);
                if (u != null) { r.name = u.getFullName(); if (r.kassaId == null) r.kassaId = u.getKassaId(); }
                else r.name = "#" + r.userId;
            }
        }

        List<Row> out = new ArrayList<>();
        Set<Long> heads = notifier.headOf(viewer);
        for (Row r : acc.values()) {
            if (!r.any()) continue;
            boolean visible = viewer.getRole() == Role.SUPERADMIN || viewer.getRole() == Role.BUXGALTER
                    || (r.userId != null && r.userId.equals(viewer.getId()))
                    || (r.kassaId != null && heads.contains(r.kassaId));
            if (visible) out.add(r);
        }
        out.sort(Comparator.comparingLong(Row::openTotal).reversed().thenComparing(r -> r.name));
        return out;
    }

    private Row row(Map<String, Row> acc, Long userId, String fallbackName, Long kassaId) {
        String key = userId != null ? "u:" + userId : "n:" + (fallbackName == null ? "" : fallbackName.trim());
        Row r = acc.get(key);
        if (r == null) {
            r = new Row();
            r.key = key;
            r.userId = userId;
            r.linked = userId != null;
            r.name = userId != null ? "" : (fallbackName == null || fallbackName.isBlank() ? "noma'lum" : fallbackName.trim());
            r.kassaId = kassaId;
            acc.put(key, r);
        } else if (r.kassaId == null && kassaId != null) r.kassaId = kassaId;
        return r;
    }

    public String kassaName(Long id) { return id == null ? "" : notifier.kassaName(id); }
}
