package uz.kassa.service.ombor.checks;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.kassa.domain.OmborQoida;
import uz.kassa.domain.OmborTovar;
import uz.kassa.repo.KassaRepo;
import uz.kassa.repo.OmborTovarRepo;
import uz.kassa.service.ombor.OmborCalcService;
import uz.kassa.service.ombor.OmborChecker;
import uz.kassa.service.ombor.OmborMetrics;
import java.math.BigDecimal;
import java.util.*;
import static uz.kassa.bot.TextUtil.esc;

/**
 * KOCHIRISH_TAKLIF — A do'konda qoplash kuni > cover_days_from, B do'konda qoldiq ≤ buyurtma nuqtasi va B da 90 kunlik sotuv bor
 * → «A dan B ga ko'chiring» taklifi (INFO). Miqdor: B ning ROP − qoldiq, A ortiqchasidan oshmaydi.
 */
@Component
@RequiredArgsConstructor
public class KochirishChecker implements OmborChecker {

    private final OmborMetrics metrics;
    private final OmborTovarRepo tovarRepo;
    private final KassaRepo kassaRepo;
    private final KorsatkichChegaraChecker abcSrc;

    @Override public String code() { return "KOCHIRISH_TAKLIF"; }
    @Override public String help() { return "Do'konlar orasida ko'chirish. params: {\"cover_days_from\":60,\"abc_in\":\"A,B\"}"; }

    @Override
    public List<Found> run(OmborQoida rule, JsonNode p) {
        double coverFrom = p.path("cover_days_from").asDouble(60);
        Set<Integer> abcIn = KorsatkichChegaraChecker.abcSet(p.path("abc_in").asText("A,B"));
        Map<String, Integer> abc = abcIn.isEmpty() ? Map.of() : abcSrc.abcMap();
        Map<String, Map<Long, BigDecimal>> stock = byProduct(OmborMetrics.QOLDIQ), cover = byProduct(OmborCalcService.QOPLASH_KUN),
                rop = byProduct(OmborCalcService.BUYURTMA_NUQTA), avg = byProduct(OmborCalcService.ORTACHA_90);
        List<Found> out = new ArrayList<>();
        for (var e : rop.entrySet()) {
            String pid = e.getKey();
            if (!abcIn.isEmpty() && !abcIn.contains(abc.getOrDefault(pid, 3))) continue;
            Map<Long, BigDecimal> st = stock.getOrDefault(pid, Map.of());
            for (var need : e.getValue().entrySet()) {   // B do'kon
                long b = need.getKey();
                BigDecimal have = st.getOrDefault(b, BigDecimal.ZERO);
                if (have.compareTo(need.getValue()) > 0) continue;
                if (avg.getOrDefault(pid, Map.of()).getOrDefault(b, BigDecimal.ZERO).signum() <= 0) continue;
                // A do'kon: eng katta qoplash
                long best = -1; double bestCover = coverFrom;
                for (var c : cover.getOrDefault(pid, Map.of()).entrySet())
                    if (c.getKey() != b && c.getValue().doubleValue() >= bestCover) { best = c.getKey(); bestCover = c.getValue().doubleValue(); }
                if (best < 0) continue;
                BigDecimal qty = need.getValue().subtract(have).max(BigDecimal.ONE).setScale(0, java.math.RoundingMode.CEILING);
                BigDecimal spare = st.getOrDefault(best, BigDecimal.ZERO).subtract(rop.get(pid).getOrDefault(best, BigDecimal.ZERO)).max(BigDecimal.ZERO);
                if (spare.signum() <= 0) continue;
                qty = qty.min(spare.setScale(0, java.math.RoundingMode.FLOOR)).max(BigDecimal.ONE);
                String name = tovarRepo.findById(pid).map(OmborTovar::getName).orElse(pid);
                out.add(Found.of("tovar", "kochirish:" + pid + "@" + b, b,
                        (name.length() > 40 ? name.substring(0, 40) : name) + ": " + kassaName(best) + " → " + kassaName(b) + " " + qty,
                        "<b>" + esc(name) + "</b>\n" + esc(kassaName(best)) + " (qoldiq " + st.get(best).stripTrailingZeros().toPlainString() + ", " + Math.round(bestCover) + " kunga yetadi) → "
                        + esc(kassaName(b)) + " (qoldiq " + have.stripTrailingZeros().toPlainString() + ", buyurtma nuqtasi " + need.getValue().stripTrailingZeros().toPlainString() + ")\n"
                        + "Taklif: <b>" + qty.toPlainString() + "</b> dona ko'chiring (MoySklad «Перемещение»). Bu taklif, qaror sizniki."));
            }
        }
        return out;
    }

    private Map<String, Map<Long, BigDecimal>> byProduct(String code) {
        Map<String, Map<Long, BigDecimal>> out = new HashMap<>();
        for (Map<String, Object> m : metrics.latest(code, "kassa_id > 0 AND product_ms_id <> ''"))
            out.computeIfAbsent((String) m.get("product_ms_id"), k -> new HashMap<>()).put(((Number) m.get("kassa_id")).longValue(), (BigDecimal) m.get("value"));
        return out;
    }

    private String kassaName(long id) { return kassaRepo.findById(id).map(k -> k.getName()).orElse("#" + id); }
}
