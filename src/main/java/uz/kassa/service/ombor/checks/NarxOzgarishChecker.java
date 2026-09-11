package uz.kassa.service.ombor.checks;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.kassa.domain.OmborQoida;
import uz.kassa.domain.OmborNarx;
import uz.kassa.domain.OmborTovar;
import uz.kassa.repo.OmborTovarRepo;
import uz.kassa.service.ombor.OmborChecker;
import java.util.ArrayList;
import java.util.List;
import static uz.kassa.bot.TextUtil.esc;
import static uz.kassa.bot.TextUtil.fmtTiyin;

/**
 * NARX_OZGARISH — scope SOTUV (B0): sotuv narxi tannarxdan past yoki tannarxdan max_markup_pct % dan ko'p yuqori.
 * scope YETKAZUVCHI (B3): ombor_narx bo'yicha oxirgi narx oldingisiga nisbatan ±pct.
 */
@Component
@RequiredArgsConstructor
public class NarxOzgarishChecker implements OmborChecker {

    private final OmborTovarRepo tovarRepo;
    private final uz.kassa.service.ombor.OmborNarxService narx;
    private final uz.kassa.repo.OmborYetkazuvchiRepo supRepo;

    @Override public String code() { return "NARX_OZGARISH"; }
    @Override public String help() { return "Narx anomaliyasi. params: {\"scope\":\"SOTUV\",\"max_markup_pct\":300} | {\"scope\":\"YETKAZUVCHI\",\"dir\":\"UP|DOWN\",\"pct\":3}"; }

    private static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    @Override
    public List<Found> run(OmborQoida rule, JsonNode p) {
        String scope = p.path("scope").asText("SOTUV");
        List<Found> out = new ArrayList<>();
        if (scope.equals("YETKAZUVCHI")) {
            boolean up = !p.path("dir").asText("UP").equals("DOWN");
            int pct = p.path("pct").asInt(3);
            for (Object[] pair : narx.pairs()) {
                String agent = (String) pair[0], pid = (String) pair[1];
                OmborNarx[] two = narx.lastTwo(agent, pid);
                if (two[0] == null || two[1] == null || two[1].getPrice() <= 0) continue;
                long d = two[0].getPrice() - two[1].getPrice();
                long chg = Math.abs(d) * 100 / two[1].getPrice();
                if (chg < pct || (up ? d <= 0 : d >= 0)) continue;
                String name = tovarRepo.findById(pid).map(OmborTovar::getName).orElse(pid);
                String sup = supRepo.findById(agent).map(uz.kassa.domain.OmborYetkazuvchi::getName).orElse(agent);
                out.add(Found.of("narx", agent + "|" + pid + "|" + two[0].getAtDate(), null,
                        (up ? "📈 " : "📉 ") + cut(name, 40) + " · " + sup + " " + (up ? "+" : "-") + chg + "%",
                        "<b>" + esc(name) + "</b> — " + esc(sup) + "\nOldingi " + fmtTiyin(two[1].getPrice()) + " (" + two[1].getAtDate() + ") → hozir <b>" + fmtTiyin(two[0].getPrice())
                        + "</b> (" + two[0].getAtDate() + ", " + two[0].getSource().toLowerCase() + ") · <b>" + (up ? "+" : "-") + chg + "%</b>"
                        + (up ? "\nBuyurtma berishdan OLDIN tekshiring: boshqa yetkazuvchi arzonroqmi?" : "\nImkoniyat: qoralamada shu yetkazuvchi ustun bo'ladi.")));
            }
            return out;
        }
        if (!scope.equals("SOTUV")) return out;
        int maxPct = p.path("max_markup_pct").asInt(300);
        for (OmborTovar t : tovarRepo.findByArchivedFalse()) {
            long buy = t.getBuyPrice(), sale = t.getSalePrice();
            if (buy <= 0 || sale <= 0) continue;
            String why = null;
            if (sale < buy) why = "sotuv narxi tannarxdan PAST";
            else if (sale > buy * (100 + maxPct) / 100) why = "ustama " + ((sale - buy) * 100 / buy) + "% (chegara " + maxPct + "%)";
            if (why == null) continue;
            out.add(Found.of("tovar", "narx:" + t.getMsId(), null,
                    (t.getName().length() > 60 ? t.getName().substring(0, 60) : t.getName()),
                    "<b>" + esc(t.getName()) + "</b>" + (t.getArticle().isBlank() ? "" : " · art. " + esc(t.getArticle()))
                    + "\nTannarx <b>" + fmtTiyin(buy) + "</b> · sotuv <b>" + fmtTiyin(sale) + "</b> — " + why
                    + "\nMoySklad'da tovar kartochkasini tekshiring."));
        }
        return out;
    }
}
