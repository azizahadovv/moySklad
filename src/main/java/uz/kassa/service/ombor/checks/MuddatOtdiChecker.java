package uz.kassa.service.ombor.checks;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.kassa.domain.OmborHujjat;
import uz.kassa.domain.OmborQoida;
import uz.kassa.domain.OmborSanoq;
import uz.kassa.domain.OmborTovar;
import uz.kassa.repo.OmborHujjatRepo;
import uz.kassa.repo.OmborSanoqRepo;
import uz.kassa.repo.OmborTovarRepo;
import uz.kassa.service.ombor.OmborChecker;
import uz.kassa.service.ombor.OmborConfig;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import static uz.kassa.bot.TextUtil.esc;
import static uz.kassa.bot.TextUtil.fmtTiyin;

/**
 * MUDDAT_OTDI — obyekt holatda N kundan ortiq turibdi.
 *   subject=hujjat: type (csv), cond: unpaid (payed<sum) | any, days; «once» + «answers» dvigatel tomonidan.
 *   subject=sanoq: tasdiqlanmagan topshiriqlar (bugun until_hour dan keyin, oldingi kunlar doim) — do'kon kesimida bitta.
 *   subject=sorov / qoralama — B4/B5 (OmborFlowChecker).
 */
@Component
@RequiredArgsConstructor
public class MuddatOtdiChecker implements OmborChecker {

    private final OmborHujjatRepo repo;
    private final OmborSanoqRepo sanoqRepo;
    private final OmborTovarRepo tovarRepo;
    private final OmborConfig cfg;
    private final uz.kassa.repo.OmborSorovRepo sorovRepo;
    private final uz.kassa.repo.OmborQoralamaRepo qoralamaRepo;
    private final uz.kassa.repo.KassaRepo kassaRepo;

    @Override public String code() { return "MUDDAT_OTDI"; }
    @Override public String help() { return "Muddat o'tdi. params: {\"subject\":\"hujjat\",\"type\":\"purchasereturn\",\"cond\":\"unpaid|any\",\"days\":14,\"once\":true,\"answers\":\"A,B\"} | {\"subject\":\"sanoq\",\"until_hour\":18}"; }

    @Override
    public List<Found> run(OmborQoida rule, JsonNode p) {
        return switch (p.path("subject").asText("hujjat")) {
            case "sanoq" -> sanoq(p.path("until_hour").asInt(18));
            case "hujjat" -> hujjat(p);
            case "sorov" -> sorov(p.path("days").asInt(1));
            case "qoralama" -> qoralama(p.path("days").asInt(1));
            default -> List.of();
        };
    }

    private List<Found> hujjat(JsonNode p) {
        Set<String> types = new LinkedHashSet<>();
        for (String t : p.path("type").asText("").split(",")) if (!t.isBlank()) types.add(t.trim());
        if (types.isEmpty()) return List.of();
        String cond = p.path("cond").asText("any");
        int days = p.path("days").asInt(0);
        LocalDateTime lim = LocalDateTime.now(cfg.zone()).minusDays(days);
        List<Found> out = new ArrayList<>();
        for (OmborHujjat h : repo.findByTypeInAndDeletedFalseAndMomentAfter(types, LocalDateTime.now(cfg.zone()).minusDays(cfg.docsDays()))) {
            if (Boolean.FALSE.equals(h.getApplicable())) continue;
            if (h.getMoment() == null || h.getMoment().isAfter(lim)) continue;
            if (cond.equals("unpaid") && h.getPayedSum() >= h.getSum()) continue;
            String tt = OmborHujjat.typeTitle(h.getType());
            String detail = "<b>" + tt + " №" + esc(h.getDocNo()) + "</b> (" + h.getMoment().toLocalDate() + ")"
                    + (h.getAgentName().isBlank() ? "" : " · " + esc(h.getAgentName())) + "\n💵 " + fmtTiyin(h.getSum())
                    + (cond.equals("unpaid") ? " · to'langan " + fmtTiyin(h.getPayedSum()) + " · <b>" + days + " kundan beri kompensatsiya yo'q</b>" : "")
                    + (h.getOwnerName().isBlank() ? "" : "\n👤 " + esc(h.getOwnerName()))
                    + "\n<a href=\"" + h.url() + "\">MoySklad'da ochish</a>";
            out.add(new Found("hujjat", h.getMsId(), h.getKassaId(), h.getOwnerUserId(), tt + " №" + h.getDocNo() + " · " + fmtTiyin(h.getSum()), detail));
        }
        return out;
    }

    private List<Found> sorov(int days) {
        List<Found> out = new ArrayList<>();
        java.time.Instant lim = java.time.Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS);
        for (uz.kassa.domain.OmborSorov s : sorovRepo.findByStatusOrderByCreatedAtAsc("YANGI")) {
            if (s.getCreatedAt().isAfter(lim)) continue;
            String what = s.getProductMsId() != null ? tovarRepo.findById(s.getProductMsId()).map(OmborTovar::getName).orElse(s.getProductMsId()) : "🆕 " + s.getText();
            out.add(new Found("sorov", String.valueOf(s.getId()), s.getKassaId(), null, "So'rov #" + s.getId() + ": " + what + " ×" + s.getQty().stripTrailingZeros().toPlainString(),
                    "📝 <b>So'rov #" + s.getId() + "</b> — " + esc(what) + " × " + s.getQty().stripTrailingZeros().toPlainString() + "\n" + uz.kassa.domain.OmborSorov.reasonTitle(s.getReason())
                    + "\n" + days + " kundan beri javobsiz. 🏬 Омбор → 📝 Сўров → ko'rildi / qoralamaga / rad."));
        }
        return out;
    }

    private List<Found> qoralama(int days) {
        List<Found> out = new ArrayList<>();
        java.time.Instant lim = java.time.Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS);
        for (uz.kassa.domain.OmborQoralama q : qoralamaRepo.findByStatusInOrderByUpdatedAtDesc(List.of("QORALAMA", "ZAKUPSHIK", "ZAVSKLAD", "TASDIQ"))) {
            if (q.getUpdatedAt().isAfter(lim)) continue;
            String kassa = q.getKassaId() == null ? "" : kassaRepo.findById(q.getKassaId()).map(uz.kassa.domain.Kassa::getName).orElse("");
            out.add(new Found("qoralama", String.valueOf(q.getId()), q.getKassaId(), null, "Qoralama #" + q.getId() + " · " + kassa + " · " + fmtTiyin(q.getTotal()),
                    "🧾 <b>Qoralama #" + q.getId() + "</b> — " + esc(kassa) + " · " + esc(q.getAgentName()) + " · " + fmtTiyin(q.getTotal()) + " so'm" + "\n"
                    + "Holat: " + uz.kassa.domain.OmborQoralama.statusTitle(q.getStatus()) + " — " + days + " kundan beri harakat yo'q. 🏬 Омбор → 🧾 Қоралама."));
        }
        return out;
    }

    private List<Found> sanoq(int untilHour) {
        LocalDate today = LocalDate.now(cfg.zone());
        boolean late = LocalTime.now(cfg.zone()).isAfter(LocalTime.of(untilHour, 0));
        Map<Long, List<OmborSanoq>> byKassa = new LinkedHashMap<>();
        for (OmborSanoq s : sanoqRepo.findByStatusNotAndPlanDateLessThanEqual("TASDIQ", today)) {
            if (s.getPlanDate().equals(today) && !late) continue;
            byKassa.computeIfAbsent(s.getKassaId(), k -> new ArrayList<>()).add(s);
        }
        List<Found> out = new ArrayList<>();
        for (var e : byKassa.entrySet()) {
            StringBuilder d = new StringBuilder("Tasdiqlanmagan sanoq: <b>" + e.getValue().size() + "</b> ta tovar\n");
            int n = 0;
            for (OmborSanoq s : e.getValue()) {
                if (++n > 10) { d.append("… yana ").append(e.getValue().size() - 10).append(" ta\n"); break; }
                d.append("• ").append(esc(tovarRepo.findById(s.getProductMsId()).map(OmborTovar::getName).orElse(s.getProductMsId())))
                 .append(s.getStatus().equals("KIRITILDI") ? " (kiritilgan, tasdiqlanmagan)" : "").append("\n");
            }
            d.append("🏬 Омбор → 🔢 Санoq — fakt miqdorni kiriting va tasdiqlang.");
            out.add(new Found("sanoq", "kassa:" + e.getKey(), e.getKey(), null, "Sanoq tasdiqlanmagan · " + e.getValue().size() + " ta", d.toString()));
        }
        return out;
    }
}
