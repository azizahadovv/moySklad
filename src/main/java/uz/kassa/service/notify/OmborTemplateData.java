package uz.kassa.service.notify;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.kassa.bot.TextUtil;
import uz.kassa.domain.Kassa;
import uz.kassa.domain.OmborKamchilik;
import uz.kassa.domain.OmborQoida;
import uz.kassa.repo.KassaRepo;
import uz.kassa.repo.OmborKamchilikRepo;
import uz.kassa.repo.OmborQoidaRepo;
import uz.kassa.repo.OmborSanoqRepo;
import uz.kassa.repo.OmborTovarRepo;
import uz.kassa.service.ombor.OmborCalcService;
import uz.kassa.service.ombor.OmborConfig;
import uz.kassa.service.ombor.OmborMetrics;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 🏬 {ombor.*} o'rinbosarlari (docs/OMBOR-TZ.md §11): kamchilik_soni, kamchilik_muhim, manfiy_soni, nelikvid_soni,
 * fill_rate, sanoq_kutmoqda, sanoq_bajarildi_foiz, hamkor_qarz_summa, buyurtma_nomzod, kochirish_soni, tovar_soni,
 * royxat (10 tagacha muhim kamchilik matni), dokon (har do'kon bir qator). Modifikator: :kassa=ID.
 */
@Component
@RequiredArgsConstructor
public class OmborTemplateData {

    private final OmborKamchilikRepo kRepo;
    private final OmborQoidaRepo qRepo;
    private final OmborSanoqRepo sanoqRepo;
    private final OmborTovarRepo tovarRepo;
    private final OmborMetrics metrics;
    private final KassaRepo kassaRepo;
    private final OmborConfig cfg;

    public Object field(String field, String[] mods) {
        Long kassa = null;
        for (String m : mods) if (m.startsWith("kassa=")) try { kassa = Long.parseLong(m.substring(6).trim()); } catch (NumberFormatException ignored) { }
        Map<String, OmborQoida> rules = new HashMap<>();
        for (OmborQoida r : qRepo.findAll()) rules.put(r.getCode(), r);
        List<OmborKamchilik> open = new ArrayList<>();
        for (OmborKamchilik k : kRepo.findByResolvedAtIsNullOrderBySinceDesc())
            if (kassa == null || kassa.equals(k.getKassaId())) open.add(k);
        LocalDate today = LocalDate.now(cfg.zone());
        switch (field) {
            case "kamchilik_soni": return (long) open.stream().filter(k -> !silent(rules, k)).count();
            case "kamchilik_muhim": return (long) open.stream().filter(k -> sev(rules, k).equals("MUHIM")).count();
            case "manfiy_soni": return (long) open.stream().filter(k -> k.getRuleCode().equals("QOLDIQ_MANFIY")).count();
            case "nelikvid_soni": return (long) open.stream().filter(k -> k.getRuleCode().equals("NELIKVID")).count();
            case "buyurtma_nomzod": return (long) open.stream().filter(k -> k.getRuleCode().equals("BUYURTMA_NUQTA")).count();
            case "kochirish_soni": return (long) open.stream().filter(k -> k.getRuleCode().equals("KOCHIRISH")).count();
            case "dublikat_soni": return (long) open.stream().filter(k -> k.getRuleCode().equals("TOVAR_DUBLIKAT")).count();
            case "otkazilmagan_soni": return (long) open.stream().filter(k -> k.getRuleCode().equals("HARAKAT_OTKAZILMAGAN")).count();
            case "tovar_soni": return tovarRepo.countByArchivedFalse();
            case "fill_rate": {
                var l = metrics.latest(OmborCalcService.FILL_RATE_30, kassa == null ? "kassa_id = 0 AND product_ms_id = ''" : "kassa_id = ? AND product_ms_id = ''", kassa == null ? new Object[0] : new Object[]{kassa});
                return l.isEmpty() ? "—" : ((BigDecimal) l.get(0).get("value")).stripTrailingZeros().toPlainString() + "%";
            }
            case "sanoq_kutmoqda": return kassa == null ? sanoqRepo.countByStatusNotAndPlanDateLessThanEqual("TASDIQ", today)
                    : sanoqRepo.countByKassaIdAndStatusNotAndPlanDateLessThanEqual(kassa, "TASDIQ", today);
            case "sanoq_bajarildi_foiz": {
                long done = sanoqRepo.countByStatusAndPlanDateGreaterThanEqual("TASDIQ", today.minusDays(7));
                long all = done + sanoqRepo.countByStatusNotAndPlanDateLessThanEqual("TASDIQ", today);
                return all == 0 ? "—" : Math.round(100.0 * done / all) + "%";
            }
            case "hamkor_qarz_summa": {
                long s = 0;
                for (Map<String, Object> m : metrics.latest("HAMKOR_QARZ", "kassa_id = 0")) s += ((BigDecimal) m.get("value")).max(BigDecimal.ZERO).longValue();
                return TextUtil.fmt(s);
            }
            case "royxat": {
                StringBuilder sb = new StringBuilder();
                int n = 0;
                for (OmborKamchilik k : open) {
                    if (silent(rules, k)) continue;
                    if (++n > 10) { sb.append("… yana ").append(open.size() - 10).append(" ta"); break; }
                    OmborQoida r = rules.get(k.getRuleCode());
                    sb.append(r == null ? "•" : r.emoji()).append(" ").append(TextUtil.esc(k.getTitle()))
                      .append(k.getKassaId() == null ? "" : " · " + TextUtil.esc(kassaName(k.getKassaId()))).append("\n");
                }
                return sb.length() == 0 ? "✅ Ochiq kamchilik yo'q" : sb.toString().trim();
            }
            case "dokon": {
                StringBuilder sb = new StringBuilder();
                for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
                    if (k.isCashless() || k.getMoyskladWarehouseId() == null) continue;
                    long[] st = metrics.stockStats(k.getId());
                    long oc = open.stream().filter(x -> k.getId().equals(x.getKassaId()) && !silent(rules, x)).count();
                    var fr = metrics.latest(OmborCalcService.FILL_RATE_30, "kassa_id = ? AND product_ms_id = ''", k.getId());
                    sb.append("🏪 <b>").append(TextUtil.esc(k.getName())).append("</b>: kamchilik ").append(oc)
                      .append(st[1] > 0 ? " · 🔴 manfiy " + st[1] : "")
                      .append(fr.isEmpty() ? "" : " · fill " + ((BigDecimal) fr.get(0).get("value")).stripTrailingZeros().toPlainString() + "%")
                      .append(" · sanoq ").append(sanoqRepo.countByKassaIdAndStatusNotAndPlanDateLessThanEqual(k.getId(), "TASDIQ", today)).append("\n");
                }
                return sb.toString().trim();
            }
            default: return null;
        }
    }

    private static boolean silent(Map<String, OmborQoida> rules, OmborKamchilik k) { OmborQoida r = rules.get(k.getRuleCode()); return r != null && r.silent(); }
    private static String sev(Map<String, OmborQoida> rules, OmborKamchilik k) { OmborQoida r = rules.get(k.getRuleCode()); return r == null ? "" : r.getSeverity(); }
    private String kassaName(long id) { return kassaRepo.findById(id).map(Kassa::getName).orElse("#" + id); }
}
