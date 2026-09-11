package uz.kassa.service.ombor.checks;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.kassa.domain.*;
import uz.kassa.repo.OmborHujjatRepo;
import uz.kassa.repo.OmborPozitsiyaRepo;
import uz.kassa.repo.OmborSanoqRepo;
import uz.kassa.repo.OmborTovarRepo;
import uz.kassa.service.ombor.OmborChecker;
import uz.kassa.service.ombor.OmborConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static uz.kassa.bot.TextUtil.esc;

/**
 * HUJJAT_FARQ — uch rejim:
 *   link      — from hujjat pozitsiyalari ↔ bog'liq to hujjatlar yig'indisi (purchaseorder ↔ supplies)
 *   inventory — inventarizatsiyada farq bor (corrections_n>0), lekin days kundan keyin ham enter/loss bog'lanmagan
 *   sanoq     — tasdiqlangan sanoqda fakt ≠ hisob (oxirgi days kun)
 */
@Component
@RequiredArgsConstructor
public class HujjatFarqChecker implements OmborChecker {

    private final OmborHujjatRepo repo;
    private final OmborPozitsiyaRepo posRepo;
    private final OmborSanoqRepo sanoqRepo;
    private final OmborTovarRepo tovarRepo;
    private final OmborConfig cfg;

    @Override public String code() { return "HUJJAT_FARQ"; }
    @Override public String help() { return "Farqlar. params: {\"mode\":\"link\",\"from\":\"purchaseorder\",\"to\":\"supply\",\"days\":2} | {\"mode\":\"inventory\",\"days\":2} | {\"mode\":\"sanoq\",\"days\":7}"; }

    @Override
    public List<Found> run(OmborQoida rule, JsonNode p) {
        String mode = p.path("mode").asText("link");
        int days = p.path("days").asInt(2);
        return switch (mode) {
            case "inventory" -> inventory(days);
            case "sanoq" -> sanoq(days);
            default -> link(p.path("from").asText("purchaseorder"), p.path("to").asText("supply"), days);
        };
    }

    private List<Found> link(String from, String to, int days) {
        List<Found> out = new ArrayList<>();
        LocalDateTime lim = LocalDateTime.now(cfg.zone()).minusDays(days);
        for (OmborHujjat h : repo.findByTypeAndDeletedFalseAndMomentAfter(from, LocalDateTime.now(cfg.zone()).minusDays(cfg.docsDays()))) {
            if (h.getMoment() == null || h.getMoment().isAfter(lim)) continue;
            List<OmborHujjat> linked = new ArrayList<>();
            for (String l : h.linkList()) repo.findByMsId(l).filter(x -> x.getType().equals(to) && !x.isDeleted()).ifPresent(linked::add);
            if (linked.isEmpty()) linked.addAll(repo.findByTypeInAndLinksContaining(List.of(to), h.getMsId()));
            if (linked.isEmpty()) continue;   // bog'lanmagan — solishtirib bo'lmaydi
            Map<String, BigDecimal> exp = new HashMap<>(), got = new HashMap<>();
            for (OmborPozitsiya x : posRepo.findByHujjatId(h.getId())) exp.merge(x.getProductMsId(), x.getQty(), BigDecimal::add);
            for (OmborHujjat l : linked) for (OmborPozitsiya x : posRepo.findByHujjatId(l.getId())) got.merge(x.getProductMsId(), x.getQty(), BigDecimal::add);
            StringBuilder d = new StringBuilder();
            int n = 0;
            Set<String> all = new LinkedHashSet<>(exp.keySet()); all.addAll(got.keySet());
            for (String pid : all) {
                BigDecimal e = exp.getOrDefault(pid, BigDecimal.ZERO), g = got.getOrDefault(pid, BigDecimal.ZERO);
                if (e.compareTo(g) == 0) continue;
                if (++n <= 12) d.append("• ").append(esc(name(pid))).append(": buyurtma <b>").append(e.stripTrailingZeros().toPlainString())
                        .append("</b> · fakt <b>").append(g.stripTrailingZeros().toPlainString()).append("</b>\n");
            }
            if (n == 0) continue;
            if (n > 12) d.append("… yana ").append(n - 12).append(" ta\n");
            out.add(new Found("hujjat", h.getMsId(), h.getKassaId(), h.getOwnerUserId(),
                    OmborHujjat.typeTitle(from) + " №" + h.getDocNo() + " ↔ " + OmborHujjat.typeTitle(to) + " · " + n + " farq",
                    "<b>" + OmborHujjat.typeTitle(from) + " №" + esc(h.getDocNo()) + "</b>" + (h.getAgentName().isBlank() ? "" : " · " + esc(h.getAgentName()))
                    + " ↔ " + linked.size() + " ta " + OmborHujjat.typeTitle(to).toLowerCase() + "\n" + d
                    + "<a href=\"" + h.url() + "\">MoySklad'da ochish</a>"));
        }
        return out;
    }

    private List<Found> inventory(int days) {
        List<Found> out = new ArrayList<>();
        LocalDateTime lim = LocalDateTime.now(cfg.zone()).minusDays(days);
        for (OmborHujjat h : repo.findByTypeAndDeletedFalseAndMomentAfter("inventory", LocalDateTime.now(cfg.zone()).minusDays(cfg.docsDays()))) {
            if (h.getCorrectionsN() == 0 || h.getMoment() == null || h.getMoment().isAfter(lim)) continue;
            List<OmborHujjat> fix = repo.findByTypeInAndLinksContaining(List.of("enter", "loss"), h.getMsId());
            if (!fix.isEmpty()) continue;
            out.add(new Found("hujjat", h.getMsId(), h.getKassaId(), h.getOwnerUserId(),
                    "Inventarizatsiya №" + h.getDocNo() + " · " + h.getCorrectionsN() + " farq, yopilmagan",
                    "<b>Inventarizatsiya №" + esc(h.getDocNo()) + "</b> (" + (h.getMoment() == null ? "" : h.getMoment().toLocalDate()) + ")\n"
                    + "Farqli pozitsiyalar: <b>" + h.getCorrectionsN() + "</b> / " + h.getPositionsN() + ", lekin oprixodovaniye (enter) yoki spisaniya (loss) yaratilmagan.\n"
                    + "<a href=\"" + h.url() + "\">MoySklad'da ochish</a> → «Оприходование / Списание» yarating."));
        }
        return out;
    }

    private List<Found> sanoq(int days) {
        List<Found> out = new ArrayList<>();
        for (OmborSanoq s : sanoqRepo.findByStatusAndAtAfter("TASDIQ", Instant.now().minus(days, ChronoUnit.DAYS))) {
            if (!s.diff()) continue;
            String name = name(s.getProductMsId());
            out.add(new Found("sanoq", String.valueOf(s.getId()), s.getKassaId(), s.getByUserId(),
                    name + ": hisob " + s.getSystemQty().stripTrailingZeros().toPlainString() + " · fakt " + s.getFactQty().stripTrailingZeros().toPlainString(),
                    "<b>" + esc(name) + "</b> (" + s.getAbc() + ")\nHisobda <b>" + s.getSystemQty().stripTrailingZeros().toPlainString()
                    + "</b> · sanoqda <b>" + s.getFactQty().stripTrailingZeros().toPlainString() + "</b> · farq <b>"
                    + s.getFactQty().subtract(s.getSystemQty()).stripTrailingZeros().toPlainString() + "</b>\n📅 " + s.getPlanDate()
                    + "\nMoySklad'da inventarizatsiya/tuzatish hujjati bilan yoping."));
        }
        return out;
    }

    private String name(String pid) { return tovarRepo.findById(pid).map(OmborTovar::getName).orElse(pid); }
}
