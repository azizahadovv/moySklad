package uz.kassa.service.adesk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import uz.kassa.config.AppProps;
import uz.kassa.repo.SettingRepo;
import uz.kassa.service.SettingsService;
import uz.kassa.service.adesk.AdeskMsReader.*;
import uz.kassa.service.moysklad.MoySkladClient;
import uz.kassa.service.moysklad.MoySkladHttp;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 📒 Haqiqiy MoySklad bilan FAQAT O'QISH tekshiruvi (yozish yo'q). Faqat MOYSKLAD_TOKEN berilganda ishlaydi:
 * davr boshidagi qoldiqlar yig'indisi MoySklad «Деньги — график» hisobotidagi qiymatga tiyinigacha teng bo'lishi kerak.
 */
@EnabledIfEnvironmentVariable(named = "MOYSKLAD_TOKEN", matches = ".+")
class AdeskMsReaderLiveTest {

    @Test
    void readerMatchesMoySkladReports() {
        AppProps props = new AppProps();
        props.getMoysklad().setToken(System.getenv("MOYSKLAD_TOKEN"));
        SettingsService settings = new SettingsService(mock(SettingRepo.class));
        MoySkladClient ms = new MoySkladClient(props, new MoySkladHttp(props, settings));
        AdeskMsReader msr = new AdeskMsReader(ms);
        LocalDate start = LocalDate.of(2026, 9, 1), end = LocalDate.of(2026, 9, 30);
        LocalDate today = LocalDate.now(props.zoneId());

        List<MsOrg> orgs = msr.orgs();
        List<MsAccount> accs = msr.accounts(orgs);
        Map<String, Long> bal = msr.balancesTiyin();
        Set<String> keys = new HashSet<>();
        accs.forEach(a -> keys.add(a.key()));
        assertTrue(keys.containsAll(bal.keySet()), "har qoldiq qatori bitta hisobga tushadi: " + bal.keySet());

        Map<String, String> cur = msr.currencies();
        List<MsMoneyDoc> all = msr.moneyDocs(start, today, null, cur);
        Map<String, Long> opening = new HashMap<>(bal);
        for (MsMoneyDoc d : all) if (d.applicable()) opening.merge(d.accountKey(), -d.signedTiyin(), Long::sum);
        long openSum = opening.values().stream().mapToLong(Long::longValue).sum();

        String q = "report/money/plotseries?momentFrom=" + URLEncoder.encode("2015-01-01 00:00:00", StandardCharsets.UTF_8)
                + "&momentTo=" + URLEncoder.encode(ms.filterTime(start.minusDays(1).atTime(23, 59, 59)), StandardCharsets.UTF_8) + "&interval=month";
        long chart = 0;
        for (var x : ms.fetchJson(q).path("series")) chart += Math.round(x.path("balance").asDouble());
        System.out.printf("orgs=%d accounts=%d docs(since start)=%d openingSum=%d chart=%d%n", orgs.size(), accs.size(), all.size(), openSum, chart);
        assertEquals(chart, openSum, "davr boshidagi jami qoldiq MoySklad grafigi bilan teng");

        long sept = all.stream().filter(d -> !d.date().isAfter(end)).count();
        assertEquals(sept, msr.moneyDocs(start, end, null, cur).size(), "davr filtri (Toshkent kunlari) to'g'ri");
        System.out.println("sentabr pul hujjatlari: " + sept);

        List<MsGoodsDoc> sup = msr.goodsDocs("supply", start, end, null);
        assertEquals(msr.countDocs("supply", start, end), sup.stream().filter(MsGoodsDoc::applicable).count());
        long withPos = sup.stream().filter(d -> !d.positions().isEmpty()).count();
        System.out.println("sentabr priyomkalar: " + sup.size() + " (pozitsiyali " + withPos + ")");
        assertTrue(withPos > 0);
        MsGoodsDoc g = sup.stream().filter(d -> !d.positions().isEmpty()).findFirst().orElseThrow();
        long linesSum = g.positions().stream().mapToLong(p -> Math.round(p.priceTiyin() * p.quantity())).sum();
        System.out.println("misol: " + g.number() + " sum=" + g.sumTiyin() + " pozitsiyalar=" + linesSum);

        int[] neg = new int[1];
        List<MsStock> stock = msr.stockAt(start, neg);
        System.out.println("davr boshidagi zaxira: " + stock.size() + " tovar, manfiy " + neg[0]);
        assertFalse(stock.isEmpty());
        System.out.println("kontragentlar: " + msr.count("entity/counterparty") + " · tovar: " + msr.count("entity/product") + " · xizmat: " + msr.count("entity/service"));
    }
}
