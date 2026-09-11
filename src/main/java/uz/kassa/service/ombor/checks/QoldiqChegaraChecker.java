package uz.kassa.service.ombor.checks;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.kassa.domain.OmborQoida;
import uz.kassa.domain.OmborTovar;
import uz.kassa.repo.OmborTovarRepo;
import uz.kassa.service.ombor.OmborChecker;
import uz.kassa.service.ombor.OmborMetrics;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static uz.kassa.bot.TextUtil.esc;

/**
 * QOLDIQ_CHEGARA — oxirgi QOLDIQ ko'rsatkichi (do'kon kesimida) chegara bilan.
 * threshold: son, yoki "min_balance" (tovar kartochkasidagi minimal qoldiq), yoki ko'rsatkich kodi (B2: BUYURTMA_NUQTA).
 */
@Component
@RequiredArgsConstructor
public class QoldiqChegaraChecker implements OmborChecker {

    private final OmborMetrics metrics;
    private final OmborTovarRepo tovarRepo;

    @Override public String code() { return "QOLDIQ_CHEGARA"; }
    @Override public String help() { return "Qoldiq chegara bilan. params: {\"op\":\"<\",\"threshold\":0} · threshold: son | \"min_balance\" | ko'rsatkich kodi"; }

    @Override
    public List<Found> run(OmborQoida rule, JsonNode p) {
        String op = p.path("op").asText("<");
        String thr = p.path("threshold").asText("0");
        List<Found> out = new ArrayList<>();
        Map<String, BigDecimal> thrByProduct = null;
        BigDecimal fixed = null;
        if (thr.equalsIgnoreCase("min_balance")) {
            thrByProduct = new java.util.HashMap<>();
            for (OmborTovar t : tovarRepo.findByArchivedFalse())
                if (t.getMinBalance().signum() > 0) thrByProduct.put(t.getMsId(), t.getMinBalance());
        } else if (thr.matches("-?\\d+(\\.\\d+)?")) fixed = new BigDecimal(thr);
        else {
            thrByProduct = new java.util.HashMap<>();
            for (Map<String, Object> m : metrics.latest(thr, "kassa_id > 0"))
                thrByProduct.put(m.get("kassa_id") + "|" + m.get("product_ms_id"), (BigDecimal) m.get("value"));
        }
        for (Map<String, Object> m : metrics.latest(OmborMetrics.QOLDIQ, "kassa_id > 0 AND product_ms_id <> ''")) {
            long kassa = ((Number) m.get("kassa_id")).longValue();
            String pid = (String) m.get("product_ms_id");
            BigDecimal v = (BigDecimal) m.get("value");
            BigDecimal t = fixed != null ? fixed
                    : thrByProduct.getOrDefault(pid, thrByProduct.get(kassa + "|" + pid));
            if (t == null) continue;
            int c = v.compareTo(t);
            boolean hit = switch (op) { case "<" -> c < 0; case "<=" -> c <= 0; case ">" -> c > 0; case ">=" -> c >= 0; default -> false; };
            if (!hit) continue;
            OmborTovar tv = tovarRepo.findById(pid).orElse(null);
            String name = tv == null ? pid : tv.getName();
            out.add(Found.of("tovar", pid + "@" + kassa, kassa,
                    name.length() > 60 ? name.substring(0, 60) : name,
                    "<b>" + esc(name) + "</b>" + (tv == null || tv.getArticle().isBlank() ? "" : " · art. " + esc(tv.getArticle()))
                    + "\nQoldiq: <b>" + v.stripTrailingZeros().toPlainString() + "</b> " + (tv == null ? "" : esc(tv.getUom()))
                    + " · chegara " + op + " " + t.stripTrailingZeros().toPlainString()));
        }
        return out;
    }
}
