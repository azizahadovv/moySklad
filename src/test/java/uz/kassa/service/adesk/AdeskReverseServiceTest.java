package uz.kassa.service.adesk;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.kassa.config.AppProps;
import uz.kassa.domain.AdeskLink;
import uz.kassa.repo.AdeskLinkRepo;
import uz.kassa.service.adesk.AdeskClient.AdTx;
import uz.kassa.service.adesk.AdeskMsReader.MsOrg;
import uz.kassa.service.moysklad.MoySkladClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Adesk'da kiritilib MoySklad'ga yozilgan operatsiya Adesk'da tahrirlansa/o'chirilsa — MoySklad'da ham (2026-10-09). */
class AdeskReverseServiceTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 9);
    static final String ORG = "org-1", ORG2 = "org-2";

    AdeskConfig cfg;
    MoySkladClient ms;
    AdeskLinkRepo repo;
    AdeskClient adc;
    AdeskReverseService svc;
    AdeskRun r;
    Map<String, AdeskLink> L;

    @BeforeEach
    void setUp() {
        cfg = mock(AdeskConfig.class);
        ms = mock(MoySkladClient.class);
        repo = mock(AdeskLinkRepo.class);
        adc = mock(AdeskClient.class);
        AppProps props = mock(AppProps.class, RETURNS_DEEP_STUBS);
        when(props.getMoysklad().getBaseUrl()).thenReturn("https://ms");
        when(cfg.reverse()).thenReturn(true);
        when(cfg.today()).thenReturn(TODAY);
        when(cfg.zone()).thenReturn(java.time.ZoneId.of("Asia/Tashkent"));
        when(cfg.catTransfer()).thenReturn("Перемещение");
        when(ms.toMoscow(any())).thenAnswer(inv -> ((LocalDateTime) inv.getArgument(0)).minusHours(2));
        when(ms.putEntity(anyString(), anyString())).thenReturn(JsonNodeFactory.instance.objectNode());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findByKindAndMsKey(anyString(), anyString())).thenReturn(Optional.empty());
        svc = new AdeskReverseService(cfg, ms, repo, props, adc);

        r = new AdeskRun(false, LocalDateTime.of(2026, 10, 9, 12, 0), new AtomicBoolean(false));
        r.orgs = List.of(new MsOrg(ORG, "001 NSB New Star Bukhara МЧЖ", "", "", "001 NSB"), new MsOrg(ORG2, "003 ITT", "", "", "003 ITT"));
        r.expenseItems = Map.of("ei-tr", "Перемещение", "ei-pr", "Прочие расходы");
        r.accountByAd.put(501L, ORG + ":CASH");
        r.accountByAd.put(502L, ORG + ":acc-1");
        r.accountByAd.put(503L, ORG2 + ":acc-2");
        L = new HashMap<>();
        r.putLinks(AdeskLink.MONEY, L);
    }

    static AdTx tx(long id, int type, String amount, long acc, Long paired, boolean transfer, String desc) {
        return new AdTx(id, type, new BigDecimal(amount), TODAY, acc, null, null, desc, transfer, false, "", "", "", null, "acc", paired);
    }

    AdeskLink adLink(String msKey, long adeskId, String msType, String accKey, long tiyin, String hash) {
        AdeskLink l = AdeskLink.builder().kind(AdeskLink.MONEY).msKey(msKey).adeskId(adeskId).origin(AdeskLink.FROM_AD)
                .status(AdeskLink.OK).msType(msType).accountKey(accKey).docDate(TODAY).sumTiyin(tiyin).hash(hash).build();
        L.put(msKey, l);
        return l;
    }

    /* ---- 2026-10-27: MoySklad 412/17001 «счет контрагента не принадлежит указанному контрагенту» ---- */

    /** org-1 hisoblari (asosiy — acc-default), MoySklad POST — muvaffaqiyat; yuborilgan tanalar qaytariladi. */
    List<String> stubPosts() {
        com.fasterxml.jackson.databind.node.ObjectNode accs = JsonNodeFactory.instance.objectNode();
        accs.putArray("rows").addObject().put("id", "acc-1").put("isDefault", false);
        ((com.fasterxml.jackson.databind.node.ArrayNode) accs.get("rows")).addObject().put("id", "acc-default").put("isDefault", true);
        when(ms.fetchJson("entity/organization/" + ORG + "/accounts?limit=100")).thenReturn(accs);
        List<String> bodies = new ArrayList<>();
        when(ms.postEntity(anyString(), anyString())).thenAnswer(inv -> {
            bodies.add(inv.getArgument(0) + " " + inv.getArgument(1));
            return JsonNodeFactory.instance.objectNode().put("id", "ms-" + bodies.size());
        });
        return bodies;
    }

    static String agentAccountOf(String body) {
        int i = body.indexOf("\"agentAccount\"");
        if (i < 0) return null;
        String href = body.substring(body.indexOf("\"href\":\"", i) + 8);
        return href.substring(0, href.indexOf('"'));
    }

    @Test
    void bankToOtherFirmsCashUsesAgentFirmsOwnAccount() {
        // jonli holat (Adesk #146258513/14): «р/с 003 ITT» → «Касса 001 NSB», 30 000 000
        List<String> bodies = stubPosts();
        AdTx out = tx(146258513, 2, "30000000", 503, 146258514L, true, "");
        AdTx in = tx(146258514, 1, "30000000", 501, 146258513L, true, "");
        svc.handle(r, List.of(out, in));
        assertEquals(2, bodies.size(), "ikkala leg ham yozilishi kerak");
        String po = bodies.stream().filter(b -> b.startsWith("entity/paymentout")).findFirst().orElseThrow();
        assertEquals("https://ms/entity/organization/" + ORG + "/accounts/acc-default", agentAccountOf(po),
                "agentAccount agent firmaning (001 NSB) hisobi bo'lishi shart");
        assertTrue(po.contains("\"organizationAccount\""));
        String ci = bodies.stream().filter(b -> b.startsWith("entity/cashin")).findFirst().orElseThrow();
        assertNull(agentAccountOf(ci), "kassa hujjatida hisob maydonlari bo'lmaydi");
    }

    @Test
    void everyAgentAccountBelongsToAgentFirm() {
        // barcha kombinatsiyalar: bank/kassa × o'z firma/boshqa firma — agentAccount doim agent firmaning hisobi yoki umuman yo'q
        List<String> bodies = stubPosts();
        when(ms.fetchJson("entity/organization/" + ORG2 + "/accounts?limit=100")).thenReturn(JsonNodeFactory.instance.objectNode().set("rows",
                JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("id", "acc-2"))));
        long[][] pairs = {{503, 501}, {501, 503}, {502, 501}, {501, 502}, {503, 502}, {502, 503}};
        long id = 1000;
        for (long[] p : pairs) {
            svc.handle(r, List.of(tx(id, 2, "100", p[0], id + 1, true, ""), tx(id + 1, 1, "100", p[1], id, true, "")));
            id += 2;
        }
        assertEquals(12, bodies.size());
        for (String b : bodies) {
            String aa = agentAccountOf(b);
            if (aa == null) continue;
            String agentHref = b.substring(b.indexOf("\"agent\""));
            agentHref = agentHref.substring(agentHref.indexOf("\"href\":\"") + 8);
            agentHref = agentHref.substring(0, agentHref.indexOf('"'));
            String agentOrg = agentHref.substring(agentHref.lastIndexOf('/') + 1);
            assertTrue(aa.startsWith("https://ms/entity/organization/" + agentOrg + "/accounts/"), "agentAccount agentga tegishli emas: " + b);
        }
    }

    @Test
    void mismatch17001IsRetriedOnceWithAgentDefaultAccount() {
        List<String> bodies = stubPosts();
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(inv -> {
            String body = inv.getArgument(1);
            if (first.getAndSet(false))
                throw new IllegalStateException("MoySklad HTTP 412: {\"errors\":[{\"error\":\"Ошибка сохранения документа: счет контрагента не принадлежит указанному контрагенту\",\"code\":17001}]}");
            bodies.add(inv.getArgument(0) + " " + body);
            return JsonNodeFactory.instance.objectNode().put("id", "ms-" + bodies.size());
        }).when(ms).postEntity(anyString(), anyString());
        // o'z firmadagi bank → bank (502 → 503 emas): agent 003 ITT, hisobi eskirgan deb faraz — qayta urinishda ITT asosiy hisobi
        when(ms.fetchJson("entity/organization/" + ORG2 + "/accounts?limit=100")).thenReturn(JsonNodeFactory.instance.objectNode().set("rows",
                JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("id", "acc-2b").put("isDefault", true))));
        svc.handle(r, List.of(tx(1, 2, "100", 502, 2L, true, ""), tx(2, 1, "100", 503, 1L, true, "")));
        assertEquals(2, bodies.size(), "qayta urinish bilan ikkala leg yozilishi kerak");
        assertEquals("https://ms/entity/organization/" + ORG2 + "/accounts/acc-2b", agentAccountOf(bodies.get(0)));
    }

    @Test
    void deletedInAdeskGoesToMoySkladTrash() {
        AdeskLink l = adLink("m1", 555, "cashout", ORG + ":CASH", -10_000_00, "AD:x");
        when(adc.transaction(555)).thenReturn(Optional.empty());
        svc.syncEdits(r, List.of(), TODAY.minusDays(7), TODAY);
        verify(ms).trashEntity("cashout", "m1");
        assertEquals(AdeskLink.DELETED, l.getStatus());
        assertEquals(1, r.get("ad.deleted"));
    }

    /** Korzina endpoint'i ishlamasa: hujjat bor bo'lsa — проведение olib tashlanadi; 404 «hujjat yo'q» bilan adashtirilmaydi. */
    @Test
    void whenTrashFailsExistingDocIsUnpostedNotLeftCounted() {
        AdeskLink l = adLink("m1", 555, "cashout", ORG + ":CASH", -10_000_00, "AD:x");
        when(adc.transaction(555)).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("MoySklad HTTP 404: unknown")).when(ms).trashEntity("cashout", "m1");
        com.fasterxml.jackson.databind.node.ObjectNode doc = JsonNodeFactory.instance.objectNode();
        doc.put("description", "[Adesk #555] non");
        when(ms.fetchJson("entity/cashout/m1")).thenReturn(doc);
        svc.syncEdits(r, List.of(), TODAY.minusDays(7), TODAY);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(ms).putEntity(eq("entity/cashout/m1"), body.capture());
        assertTrue(body.getValue().contains("\"applicable\":false"), body.getValue());
        assertTrue(body.getValue().contains("❌ Adesk'da o'chirilgan · [Adesk #555] non"));
        assertEquals(AdeskLink.DELETED, l.getStatus());
        assertEquals(1, r.get("ad.unposted"));
    }

    @Test
    void missingFromListButExistingOrUnknownIsNeverDeleted() {
        AdTx t = tx(555, 2, "10000.00", 501, null, false, "x");
        AdeskLink l = adLink("m1", 555, "cashout", ORG + ":CASH", -10_000_00, AdeskReverseService.fp(t));
        when(adc.transaction(555)).thenReturn(Optional.of(t));   // sanasi davrdan chiqqan — lekin bor
        svc.syncEdits(r, List.of(), TODAY.minusDays(7), TODAY);
        when(adc.transaction(555)).thenThrow(new AdeskHttp.AdeskException(500, 0, "server xatosi", false));
        svc.syncEdits(r, List.of(), TODAY.minusDays(7), TODAY);
        verify(ms, never()).trashEntity(anyString(), anyString());
        verify(ms, never()).putEntity(anyString(), anyString());
        assertEquals(AdeskLink.OK, l.getStatus());
    }

    @Test
    void editedAmountUpdatesSameMoySkladDocument() {
        AdTx old = tx(555, 2, "10000.00", 501, null, false, "non");
        AdTx now = tx(555, 2, "25000.00", 501, null, false, "non va suv");
        AdeskLink l = adLink("m1", 555, "cashout", ORG + ":CASH", -10_000_00, AdeskReverseService.fp(old));
        svc.syncEdits(r, List.of(now), TODAY.minusDays(7), TODAY);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(ms).putEntity(eq("entity/cashout/m1"), body.capture());
        assertTrue(body.getValue().contains("\"sum\":2500000"), body.getValue());
        assertTrue(body.getValue().contains("[Adesk #555] non va suv"), "izoh va belgi yangilanadi");
        assertFalse(body.getValue().contains("\"moment\""), "sana o'zgarmagan — vaqt tegilmaydi");
        assertFalse(body.getValue().contains("\"state\""), "MoySklad statusi tegilmaydi");
        assertEquals(-25_000_00L, l.getSumTiyin());
        assertEquals(AdeskReverseService.fp(now), l.getHash());
        verify(ms, never()).trashEntity(anyString(), anyString());
    }

    @Test
    void accountTypeChangeTrashesOldDocSoItIsRewritten() {
        AdTx old = tx(555, 2, "10000.00", 501, null, false, "");
        AdTx now = tx(555, 2, "10000.00", 502, null, false, "");   // kassa → bank: Расходный ордер → Исходящий платёж
        AdeskLink l = adLink("m1", 555, "cashout", ORG + ":CASH", -10_000_00, AdeskReverseService.fp(old));
        svc.syncEdits(r, List.of(now), TODAY.minusDays(7), TODAY);
        verify(ms).trashEntity("cashout", "m1");
        verify(ms, never()).putEntity(anyString(), anyString());
        assertEquals(AdeskLink.DELETED, l.getStatus());
    }

    @Test
    void editedTransferUpdatesBothDocuments() {
        AdTx oOld = tx(601, 2, "100000.00", 502, 602L, true, "1"), iOld = tx(602, 1, "100000.00", 503, 601L, true, "1");
        AdTx oNew = tx(601, 2, "150000.00", 502, 602L, true, "1"), iNew = tx(602, 1, "150000.00", 503, 601L, true, "1");
        AdeskLink lo = adLink("mo", 601, "paymentout", ORG + ":acc-1", -100_000_00, AdeskReverseService.fp(oOld));
        AdeskLink li = adLink("mi", 602, "paymentin", ORG2 + ":acc-2", 100_000_00, AdeskReverseService.fp(iOld));
        svc.syncEdits(r, List.of(oNew, iNew), TODAY.minusDays(7), TODAY);
        ArgumentCaptor<String> bo = ArgumentCaptor.forClass(String.class), bi = ArgumentCaptor.forClass(String.class);
        verify(ms).putEntity(eq("entity/paymentout/mo"), bo.capture());
        verify(ms).putEntity(eq("entity/paymentin/mi"), bi.capture());
        assertTrue(bo.getValue().contains("\"sum\":15000000") && bi.getValue().contains("\"sum\":15000000"));
        assertTrue(bo.getValue().contains("Перемещение собственных средств 001 NSB New Star Bukhara МЧЖ от 09.10.2026"));
        assertTrue(bo.getValue().contains("org-2/accounts/acc-2"), "chiqimda qarshi hisob — qabul qiluvchi");
        assertEquals(AdeskReverseService.fp(oNew), lo.getHash());
        assertEquals(AdeskReverseService.fp(iNew), li.getHash());
        assertEquals(2, r.get("ad.edited"));
    }

    @Test
    void legacyLinkWithoutHashIsOnlyFingerprinted() {
        AdTx t = tx(555, 1, "5000.00", 501, null, false, "eski");
        AdeskLink l = adLink("m1", 555, "cashin", ORG + ":CASH", 5_000_00, null);
        svc.syncEdits(r, List.of(t), TODAY.minusDays(7), TODAY);
        verify(ms, never()).putEntity(anyString(), anyString());
        assertEquals(AdeskReverseService.fp(t), l.getHash());
    }

    @Test
    void nothingHappensWhenAdeskToMoySkladIsOff() {
        when(cfg.reverse()).thenReturn(false);
        adLink("m1", 555, "cashout", ORG + ":CASH", -10_000_00, "AD:x");
        svc.syncEdits(r, List.of(), TODAY.minusDays(7), TODAY);
        verifyNoInteractions(adc);
        verify(ms, never()).trashEntity(anyString(), anyString());
    }
}
