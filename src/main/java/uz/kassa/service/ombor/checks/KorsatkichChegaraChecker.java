package uz.kassa.service.ombor.checks;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.kassa.domain.OmborQoida;
import uz.kassa.domain.OmborTovar;
import uz.kassa.repo.KassaRepo;
import uz.kassa.repo.OmborTovarRepo;
import uz.kassa.service.ombor.OmborChecker;
import uz.kassa.service.ombor.OmborMetrics;
import java.math.BigDecimal;
import java.util.*;
import static uz.kassa.bot.TextUtil.esc;
import static uz.kassa.bot.TextUtil.fmt;

/**
 * KORSATKICH_CHEGARA — istalgan ko'rsatkich (oxirgi sana) chegara bilan.
 *   subject: tovar (standart) | hamkor (product_ms_id = kontragent) | kassa (product_ms_id = '' — do'kon darajasi)
 *   scope: store (kassa>0) | company (kassa=0); abc_in; min_stock (faqat qoldiq ≥ bo'lsa, company QOLDIQ).
 */
@Component
@RequiredArgsConstructor
public class KorsatkichChegaraChecker implements OmborChecker {

    private final OmborMetrics metrics;
    private final OmborTovarRepo tovarRepo;
    private final KassaRepo kassaRepo;

    @Override public String code() { return "KORSATKICH_CHEGARA"; }
    @Override public String help() { return "Ko'rsatkich chegara bilan. params: {\"code\":\"AYLANMA_KUN\",\"op\":\">\",\"threshold\":90,\"scope\":\"company|store\",\"subject\":\"tovar|hamkor|kassa\",\"abc_in\":\"A,B\",\"min_stock\":1}"; }

    @Override
    public List<Found> run(OmborQoida rule, JsonNode p) {
        String code = p.path("code").asText("");
        String op = p.path("op").asText(">");
        BigDecimal thr = new BigDecimal(p.path("threshold").asText("0"));
        String subject = p.path("subject").asText("tovar");
        String scope = p.path("scope").asText(subject.equals("kassa") ? "store" : "company");
        Set<Integer> abcIn = abcSet(p.path("abc_in").asText(""));
        double minStock = p.path("min_stock").asDouble(-1);
        Map<String, Integer> abc = abcIn.isEmpty() ? Map.of() : abcMap();
        Map<String, BigDecimal> stock = minStock < 0 ? Map.of() : companyStock();
        String where = subject.equals("kassa") ? "product_ms_id = '' AND kassa_id > 0" : subject.equals("hamkor_tovar") ? "kassa_id = 0 AND product_ms_id LIKE '%|%'" : (scope.equals("company") ? "kassa_id = 0" : "kassa_id > 0") + " AND product_ms_id <> ''";
        List<Found> out = new ArrayList<>();
        for (Map<String, Object> m : metrics.latest(code, where)) {
            long kassa = ((Number) m.get("kassa_id")).longValue();
            String pid = (String) m.get("product_ms_id");
            BigDecimal v = (BigDecimal) m.get("value");
            int c = v.compareTo(thr);
            boolean hit = switch (op) { case "<" -> c < 0; case "<=" -> c <= 0; case ">" -> c > 0; case ">=" -> c >= 0; default -> false; };
            if (!hit) continue;
            if (!abcIn.isEmpty() && !abcIn.contains(abc.getOrDefault(pid, 3))) continue;
            if (minStock >= 0 && stock.getOrDefault(pid, BigDecimal.ZERO).doubleValue() < minStock) continue;
            String val = v.stripTrailingZeros().toPlainString();
            switch (subject) {
                case "kassa" -> out.add(Found.of("kassa", code + ":" + kassa, kassa, kassaName(kassa) + " · " + code + " " + val,
                        "<b>" + esc(kassaName(kassa)) + "</b>: " + code + " = <b>" + val + "</b> (chegara " + op + " " + thr.stripTrailingZeros().toPlainString() + ")"));
                case "hamkor_tovar" -> {
                    String agent = pid.contains("|") ? pid.substring(0, pid.indexOf('|')) : pid, prod = pid.contains("|") ? pid.substring(pid.indexOf('|') + 1) : "";
                    String pn = tovarRepo.findById(prod).map(OmborTovar::getName).orElse(prod);
                    out.add(Found.of("hamkor", pid, null, hamkorName(agent) + " · " + (pn.length() > 40 ? pn.substring(0, 40) : pn) + " · " + val + " kun",
                            "🤝 <b>" + esc(hamkorName(agent)) + "</b> — <b>" + esc(pn) + "</b>: odatdagi xarid intervalidan <b>" + val + " kun</b> o'tdi, tugagan bo'lishi mumkin. Taklif qiling."));
                }
                case "hamkor" -> out.add(Found.of("hamkor", pid, null, hamkorName(pid) + " · " + fmt(v.longValue()) + " so'm",
                        "🤝 <b>" + esc(hamkorName(pid)) + "</b> — qarz <b>" + fmt(v.longValue()) + "</b> so'm (limit " + fmt(thr.longValue()) + ")"));
                default -> {
                    OmborTovar t = tovarRepo.findById(pid).orElse(null);
                    String name = t == null ? pid : t.getName();
                    out.add(Found.of("tovar", code + ":" + pid + "@" + kassa, kassa > 0 ? kassa : null,
                            (name.length() > 50 ? name.substring(0, 50) : name) + " · " + code + " " + val,
                            "<b>" + esc(name) + "</b>" + (t == null || t.getArticle().isBlank() ? "" : " · art. " + esc(t.getArticle()))
                            + "\n" + code + " = <b>" + val + "</b> (chegara " + op + " " + thr.stripTrailingZeros().toPlainString() + ")"
                            + (stock.containsKey(pid) ? " · qoldiq " + stock.get(pid).stripTrailingZeros().toPlainString() : "")));
                }
            }
        }
        return out;
    }

    static Set<Integer> abcSet(String csv) {
        Set<Integer> s = new HashSet<>();
        for (String x : csv.split(",")) switch (x.trim().toUpperCase()) { case "A" -> s.add(1); case "B" -> s.add(2); case "C" -> s.add(3); default -> { } }
        return s;
    }

    Map<String, Integer> abcMap() {
        Map<String, Integer> m = new HashMap<>();
        for (Map<String, Object> r : metrics.latest("ABC", "kassa_id = 0")) m.put((String) r.get("product_ms_id"), ((BigDecimal) r.get("value")).intValue());
        return m;
    }

    private Map<String, BigDecimal> companyStock() {
        Map<String, BigDecimal> m = new HashMap<>();
        for (Map<String, Object> r : metrics.latest(OmborMetrics.QOLDIQ, "kassa_id = 0 AND product_ms_id <> ''")) m.put((String) r.get("product_ms_id"), (BigDecimal) r.get("value"));
        return m;
    }

    private String kassaName(long id) { return kassaRepo.findById(id).map(k -> k.getName()).orElse("#" + id); }
    private String hamkorName(String agentId) { return hamkorNames.getOrDefault(agentId, agentId); }

    /** Sinxron paytida to'ldiriladi (OmborSyncService.hamkorlar). */
    public static final Map<String, String> hamkorNames = new java.util.concurrent.ConcurrentHashMap<>();
}
