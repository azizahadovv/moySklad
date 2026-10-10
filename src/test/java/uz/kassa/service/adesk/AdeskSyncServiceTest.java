package uz.kassa.service.adesk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.kassa.domain.AdeskLink;
import uz.kassa.repo.AdeskLinkRepo;
import uz.kassa.service.adesk.AdeskClient.*;
import uz.kassa.service.adesk.AdeskMsReader.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 📒 MoySklad → Adesk xaritalash mantig'i (docs/ADESK.md §4): tashqi API'lar mock. */
class AdeskSyncServiceTest {

    static final String ORG = "org-1", ORG2 = "org-2", ACC = "acc-1";
    static final LocalDate START = LocalDate.of(2026, 9, 1);

    AdeskConfig cfg;
    AdeskClient ad;
    AdeskMsReader msr;
    AdeskLinkRepo repo;
    AdeskReverseService reverse;
    AdeskSyncService svc;
    List<AdeskLink> saved;

    @BeforeEach
    void setUp() {
        cfg = mock(AdeskConfig.class);
        ad = mock(AdeskClient.class);
        msr = mock(AdeskMsReader.class);
        repo = mock(AdeskLinkRepo.class);
        reverse = mock(AdeskReverseService.class);
        when(cfg.start()).thenReturn(START);
        when(cfg.effectiveEnd()).thenReturn(LocalDate.of(2026, 9, 30));
        when(cfg.today()).thenReturn(LocalDate.of(2026, 10, 5));
        when(cfg.openingDate()).thenReturn(START.minusDays(1));
        when(cfg.catIncome()).thenReturn("Выручка");
        when(cfg.catTransfer()).thenReturn("Перемещение");
        when(cfg.currency()).thenReturn("UZS");
        when(cfg.get(anyString())).thenReturn(Optional.empty());
        when(cfg.stockOrg()).thenReturn("");
        saved = new ArrayList<>();
        when(repo.save(any())).thenAnswer(inv -> { AdeskLink l = inv.getArgument(0); saved.add(l); return l; });
        when(repo.findByKind(anyString())).thenReturn(List.of());
        svc = new AdeskSyncService(cfg, ad, msr, repo, reverse);
    }

    AdeskRun run(boolean full) {
        AdeskRun r = new AdeskRun(full, LocalDateTime.of(2026, 10, 5, 12, 0), new AtomicBoolean(false));
        r.orgs = List.of(new MsOrg(ORG, "001 NSB New Star Bukhara МЧЖ 302037932", "", "302037932", "001 NSB"),
                new MsOrg(ORG2, "003 ITT INTER TECHNO TRUST MCHJ", "", "", "003 ITT"));
        r.expenseItems = Map.of("ei-zp", "Зарплата", "ei-tr", "Перемещение");
        r.account.put(ORG + ":CASH", 501L);
        r.account.put(ORG + ":" + ACC, 502L);
        r.catIn.put("Выручка", 11L);
        r.catIn.put("Перемещение", 12L);
        r.catOut.put("Зарплата", 21L);
        r.catOut.put("Перемещение", 22L);
        r.catOut.put("Прочие расходы", 23L);
        return r;
    }

    static MsMoneyDoc doc(String id, String entity, long tiyin, String acc, String agentType, String agentId, String expense, String purpose, boolean applicable) {
        return new MsMoneyDoc(id, entity, "00" + id, LocalDate.of(2026, 9, 5), LocalDateTime.of(2026, 9, 5, 10, 0),
                tiyin, tiyin, "", 1, ORG, ORG + ":" + acc, agentId, agentType, expense, "izoh " + id, purpose, applicable);
    }

    /** Avvalgi yurishlarda yaratilgan bog'lanish. */
    static AdeskLink link(String kind, String key, long adeskId) {
        return AdeskLink.builder().kind(kind).msKey(key).adeskId(adeskId).status(AdeskLink.OK)
                .updatedAt(java.time.Instant.now().minusSeconds(3600)).build();
    }

    @Test
    void moneyDocsBecomeAdeskTransactionsWithCategoryContractorAndCheckbox() {
        when(repo.findByKind(AdeskLink.CONTRACTOR)).thenReturn(List.of(link(AdeskLink.CONTRACTOR, "cp-1", 900)));
        when(msr.moneyDocs(eq(START), any(), isNull(), any())).thenReturn(List.of(
                doc("1", "cashin", 150_000_00, "CASH", "counterparty", "cp-1", "", "", true),             // xaridordan naqd
                doc("2", "paymentout", 2_500_000_00, ACC, "organization", ORG, "ei-zp", "", true),     // agent = o'zi → kontragentsiz
                doc("3", "paymentin", 1_000_00, ACC, "organization", ORG, "", "Перемещение собственных средств 001", true),
                doc("4", "cashout", 5_00, "CASH", "counterparty", "cp-1", "ei-tr", "", true)));
        when(ad.createTransactions(anyList())).thenAnswer(inv -> {
            List<ObjectNode> l = inv.getArgument(0);
            Map<String, Long> m = new LinkedHashMap<>();
            long id = 7000;
            for (ObjectNode n : l) m.put(n.path("importedId").asText(), id++);
            return m;
        });
        when(ad.transactions(any(), any())).thenReturn(List.of());

        AdeskRun r = run(true);
        svc.money(r);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ObjectNode>> cap = ArgumentCaptor.forClass(List.class);
        verify(ad).createTransactions(cap.capture());
        List<ObjectNode> txs = cap.getValue();
        assertEquals(4, txs.size());

        ObjectNode a = txs.get(0);
        assertEquals("income", a.path("type").asText());
        assertEquals(501, a.path("bankAccountId").asLong());
        assertEquals("150000.00", a.path("amount").asText());
        assertEquals("2026-09-05", a.path("date").asText());
        assertEquals(11, a.path("categoryId").asLong());
        assertEquals(900, a.path("contractorId").asLong());
        assertTrue(a.path("isCommitment").asBoolean(), "kontragent bor — galochka qo'yiladi");
        assertEquals("ms:1", a.path("importedId").asText());
        assertTrue(a.path("description").asText().contains("izoh 1"));
        assertTrue(a.path("description").asText().contains("Приходный ордер №001"));

        ObjectNode b = txs.get(1);
        assertEquals("outcome", b.path("type").asText());
        assertEquals(502, b.path("bankAccountId").asLong());
        assertEquals(21, b.path("categoryId").asLong(), "statya = MoySklad «Статья расходов»");
        assertTrue(b.path("contractorId").isNull(), "agent tashkilotning o'zi — kontragent yo'q");
        assertFalse(b.path("isCommitment").asBoolean(), "kontragent yo'q — galochka yo'q");

        ObjectNode c = txs.get(2);
        assertEquals(12, c.path("categoryId").asLong(), "kirim o'tkazma «Перемещение» statyasiga");
        assertTrue(c.path("contractorId").isNull());

        ObjectNode d = txs.get(3);
        assertEquals(23, d.path("categoryId").asLong(), "«Перемещение», lekin qabul qiluvchi kontragent — o'tkazma emas");
        assertEquals(900, d.path("contractorId").asLong());

        assertEquals(4, r.get("tx.created"));
        assertEquals(4, saved.stream().filter(l -> l.getKind().equals(AdeskLink.MONEY) && l.ok()).map(AdeskLink::getMsKey).distinct().count());
        verify(reverse).handle(same(r), eq(List.of()));
        assertEquals(0, r.get("tx.restored"), "shu yurishda yaratilganlar Adesk ro'yxatida ko'rinmasa ham qayta yaratilmaydi");
    }

    @Test
    void transactionDeletedInAdeskIsRecreatedOnNextFullRun() {
        MsMoneyDoc d = doc("1", "cashout", 100_00, "CASH", "organization", ORG, "ei-zp", "", true);
        when(msr.moneyDocs(eq(START), any(), isNull(), any())).thenReturn(List.of(d));
        when(ad.createTransactions(anyList())).thenReturn(Map.of("ms:1", 7001L));
        when(ad.transactions(any(), any())).thenReturn(List.of());
        svc.money(run(true));
        AdeskLink l = link(AdeskLink.MONEY, "1", 7001);
        l.setHash(saved.get(saved.size() - 1).getHash());
        when(repo.findByKind(AdeskLink.MONEY)).thenReturn(List.of(l));
        when(ad.createTransactions(anyList())).thenReturn(Map.of("ms:1", 7002L));
        AdeskRun r = run(true);
        svc.money(r);
        assertEquals(1, r.get("tx.restored"));
        assertEquals(7002L, l.getAdeskId());
    }

    @Test
    void changedDocIsUpdatedAndDeletedDocIsRemoved() {
        MsMoneyDoc changed = doc("1", "cashin", 200_000_00, "CASH", "organization", ORG, "", "", true);
        AdeskLink l1 = link(AdeskLink.MONEY, "1", 7001);
        l1.setHash("old");
        AdeskLink l2 = link(AdeskLink.MONEY, "2", 7002);   // MoySklad'da endi yo'q
        when(repo.findByKind(AdeskLink.MONEY)).thenReturn(List.of(l1, l2));
        when(msr.moneyDocs(eq(START), any(), isNull(), any())).thenReturn(List.of(changed));
        when(ad.transactions(any(), any())).thenReturn(List.of(
                new AdTx(7001, 1, new BigDecimal("200000.00"), LocalDate.of(2026, 9, 5), 501L, 11L, null, "", false, false, "", "", "", null, "NSB р/с"),
                new AdTx(8888, 2, new BigDecimal("50.00"), LocalDate.of(2026, 9, 6), 501L, 21L, null, "qo'lda", false, false, "", "Зарплата", "", null, "NSB р/с")));

        AdeskRun r = run(true);
        svc.money(r);

        verify(ad).removeTransactions(List.of(7002L));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ObjectNode>> cap = ArgumentCaptor.forClass(List.class);
        verify(ad).updateTransactions(cap.capture());
        ObjectNode u = cap.getValue().get(0);
        assertEquals(7001, u.path("id").asLong());
        assertEquals("200000.00", u.path("amount").asText());
        assertFalse(u.has("importedId"));
        assertEquals(AdeskLink.DELETED, l2.getStatus());
        verify(ad, never()).createTransactions(anyList());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AdTx>> manual = ArgumentCaptor.forClass(List.class);
        verify(reverse).handle(same(r), manual.capture());
        assertEquals(List.of(8888L), manual.getValue().stream().map(AdTx::id).toList(), "bog'lanmagan Adesk operatsiyasi — qo'lda kiritilgan");
    }

    @Test
    void adeskSideChangeIsRestoredFromMoySklad() {
        MsMoneyDoc d = doc("1", "cashout", 100_00, "CASH", "organization", ORG, "ei-zp", "", true);
        AdeskRun r0 = run(true);
        when(msr.moneyDocs(eq(START), any(), isNull(), any())).thenReturn(List.of(d));
        when(ad.createTransactions(anyList())).thenReturn(Map.of("ms:1", 7001L));
        when(ad.transactions(any(), any())).thenReturn(List.of());
        svc.money(r0);
        String hash = saved.get(saved.size() - 1).getHash();

        AdeskLink l = link(AdeskLink.MONEY, "1", 7001);
        l.setHash(hash);
        when(repo.findByKind(AdeskLink.MONEY)).thenReturn(List.of(l));
        // Adesk'da kimdir summani o'zgartirgan
        when(ad.transactions(any(), any())).thenReturn(List.of(
                new AdTx(7001, 2, new BigDecimal("999.00"), LocalDate.of(2026, 9, 5), 501L, 21L, null, "", false, false, "", "", "", null, "NSB р/с")));
        AdeskRun r = run(true);
        svc.money(r);
        verify(ad, times(1)).updateTransactions(anyList());
        assertEquals(1, r.get("tx.fixed"));
    }

    @Test
    void openingBalanceIsCurrentMinusMovementSinceStart() {
        when(msr.accounts(anyList())).thenReturn(List.of(
                new MsAccount(ORG + ":CASH", ORG, null, "Касса", "", "", true, false),
                new MsAccount(ORG + ":" + ACC, ORG, ACC, "р/с", "Bank", "", false, true),
                new MsAccount(ORG2 + ":acc-2", ORG2, "acc-2", "р/с", "Bank", "", false, true)));
        when(msr.balancesTiyin()).thenReturn(Map.of(ORG + ":CASH", 1_000_00L, ORG + ":" + ACC, 500_00L));
        when(ad.bankAccounts()).thenReturn(List.of());
        when(ad.createBankAccount(anyString(), anyString(), anyLong(), anyInt(), any(), any(), any(), any()))
                .thenAnswer(inv -> new AdAccount(600 + saved.size(), inv.getArgument(0), "", null, BigDecimal.ZERO, inv.getArgument(6), inv.getArgument(7), "open", "UZS"));
        AdeskRun r = run(false);
        r.orgLe.put(ORG, 1L);
        r.orgLe.put(ORG2, 2L);
        r.balances = Map.of(ORG + ":CASH", 1_000_00L, ORG + ":" + ACC, 500_00L);
        r.allMoney = List.of(
                doc("1", "cashin", 200_00, "CASH", "counterparty", "cp", "", "", true),
                doc("2", "paymentout", 100_00, ACC, "counterparty", "cp", "ei-zp", "", true),
                doc("3", "cashin", 999_00, "CASH", "counterparty", "cp", "", "", false));   // o'tkazilmagan — hisobga olinmaydi

        svc.accounts(r);

        verify(ad).createBankAccount(eq("Касса · 001 NSB"), eq("UZS"), eq(1L), eq(1), any(), any(), eq(new BigDecimal("800.00")), eq(START.minusDays(1)));
        verify(ad).createBankAccount(eq("р/с · 001 NSB"), eq("UZS"), eq(1L), eq(2), any(), any(), eq(new BigDecimal("600.00")), any());
        verify(ad).createBankAccount(eq("р/с · 003 ITT"), eq("UZS"), eq(2L), eq(2), any(), any(), eq(new BigDecimal("0.00")), any());
        assertEquals(3, r.account.size());
    }

    @Test
    void demandBecomesOutgoingCommitmentWithProductLines() {
        when(repo.findByKind(AdeskLink.CONTRACTOR)).thenReturn(List.of(link(AdeskLink.CONTRACTOR, "cp-1", 900)));
        when(repo.findByKind(AdeskLink.PRODUCT)).thenReturn(List.of(link(AdeskLink.PRODUCT, "p-1", 31), link(AdeskLink.PRODUCT, "s-1", 32)));
        MsGoodsDoc dem = new MsGoodsDoc("d-1", "demand", "00045", LocalDate.of(2026, 9, 7), 3_150_000_00L, ORG, "cp-1", "counterparty", "", true,
                List.of(new MsPos("p-1", "product", 2, 1_500_000_00L), new MsPos("s-1", "service", 1, 150_000_00L)));
        when(msr.goodsDocs(eq("demand"), any(), any(), isNull())).thenReturn(List.of(dem));
        when(msr.goodsDocs(argThat((String e) -> !"demand".equals(e)), any(), any(), isNull())).thenReturn(List.of());
        when(ad.createCommitment(anyMap())).thenReturn(4001L);
        when(ad.commitments(any(), any())).thenReturn(List.of(new AdCommit(4001, 2, new BigDecimal("3150000.00"), null, 900L, "")));
        AdeskRun r = run(true);
        r.orgLe.put(ORG, 1L);
        r.projectId = 967986L;

        svc.commitments(r);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> cap = ArgumentCaptor.forClass(Map.class);
        verify(ad).createCommitment(cap.capture());
        Map<String, String> p = cap.getValue();
        assertEquals("967986", p.get("project"), "otgruzka ham «Asosiy» proyektida");
        assertEquals("out", p.get("type"), "otgruzka — kontragentga berdik");
        assertEquals("3150000.00", p.get("amount"));
        assertEquals("900", p.get("contractor"));
        assertEquals("1", p.get("legal_entity"));
        assertEquals("31", p.get("product-0-product_id"));
        assertEquals("1500000.00", p.get("product-0-price"));
        assertEquals("2", p.get("product-0-quantity"));
        assertEquals("32", p.get("product-1-product_id"));
        assertTrue(p.get("description").startsWith("MS Отгрузка №00045"));
        assertEquals(1, r.get("cm.created"));
        assertEquals(0, r.get("cm.restored"));

        // izoh: raqam · MoySklad statusi · kommentariya (Adesk'da status yo'q)
        MsGoodsDoc withState = new MsGoodsDoc("d-2", "demand", "14998", LocalDate.of(2026, 10, 10), 1_550_000_00L, ORG, "cp-1", "counterparty",
                "Xakim aka berildi  boxchaga", true, List.of(), "Карз перечисление");
        assertEquals("MS Отгрузка №14998 · Карз перечисление · Xakim aka berildi boxchaga", AdeskSyncService.commitDesc(withState));
        assertEquals("MS Отгрузка №00045", AdeskSyncService.commitDesc(dem), "status ham, izoh ham yo'q — faqat raqam");
        MsGoodsDoc longNote = new MsGoodsDoc("d-3", "supply", "77", LocalDate.of(2026, 10, 10), 1L, ORG, "cp-1", "counterparty",
                "x".repeat(900), true, List.of(), "Олинди");
        String ld = AdeskSyncService.commitDesc(longNote);
        assertTrue(ld.length() <= 510 && ld.startsWith("MS Приёмка №77 · Олинди · x"));

        // valyutadagi hujjat: summa so'mda, izohda asl summa va kurs (2026-10-10: 75 USD Adesk'ka 75 so'm bo'lib ketgan edi)
        MsGoodsDoc usd = new MsGoodsDoc("d-4", "purchasereturn", "00002", LocalDate.of(2026, 10, 9), 892_500_00L, ORG, "cp-1", "counterparty",
                "", true, List.of(), "", "USD", 11900, 75_00L);
        assertEquals("MS Возврат поставщику №00002 · 75 USD × 11900", AdeskSyncService.commitDesc(usd));
    }

    /** Otgruzka o'zgardi: AVVAL yangisi yaratiladi, keyin eskisi o'chiriladi; Adesk rad etsa — eskisi joyida (yo'qolmaydi). */
    @Test
    void changedDemandIsRecreatedBeforeOldIsRemovedAndKeptOnReject() {
        when(repo.findByKind(AdeskLink.CONTRACTOR)).thenReturn(List.of(link(AdeskLink.CONTRACTOR, "cp-1", 900)));
        when(repo.findByKind(AdeskLink.PRODUCT)).thenReturn(List.of(link(AdeskLink.PRODUCT, "p-1", 31)));
        AdeskLink cl = link(AdeskLink.COMMIT, "d-1", 4001);
        cl.setHash("eski");
        when(repo.findByKind(AdeskLink.COMMIT)).thenReturn(List.of(cl));
        MsGoodsDoc dem = new MsGoodsDoc("d-1", "demand", "00045", LocalDate.of(2026, 9, 7), 3_000_000_00L, ORG, "cp-1", "counterparty", "", true,
                List.of(new MsPos("p-1", "product", 2, 1_500_000_00L)));
        when(msr.goodsDocs(eq("demand"), any(), any(), any())).thenReturn(List.of(dem));
        when(msr.goodsDocs(argThat((String e) -> !"demand".equals(e)), any(), any(), any())).thenReturn(List.of());
        when(ad.commitments(any(), any())).thenReturn(List.of(new AdCommit(4002, 2, new BigDecimal("3000000.00"), null, 900L, "")));

        // Adesk rad etdi — eskisi o'chirilmaydi, bog'lanish 4001 da qoladi
        when(ad.createCommitment(anyMap())).thenThrow(new AdeskHttp.AdeskException(200, 0, "project: noto'g'ri", false));
        AdeskRun r = run(true);
        r.orgLe.put(ORG, 1L);
        svc.commitments(r);
        verify(ad, never()).removeCommitment(anyLong());
        assertEquals(4001L, cl.getAdeskId());
        assertEquals(AdeskLink.ERROR, cl.getStatus());

        // Adesk qabul qildi — yangisi 4002, keyin eski 4001 o'chiriladi
        reset(ad);
        when(ad.commitments(any(), any())).thenReturn(List.of(new AdCommit(4002, 2, new BigDecimal("3000000.00"), null, 900L, "")));
        when(ad.createCommitment(anyMap())).thenReturn(4002L);
        AdeskRun r2 = run(true);
        r2.orgLe.put(ORG, 1L);
        svc.commitments(r2);
        org.mockito.InOrder o = inOrder(ad);
        o.verify(ad).createCommitment(anyMap());
        o.verify(ad).removeCommitment(4001L);
        assertEquals(4002L, cl.getAdeskId());
        assertEquals(1, r2.get("cm.updated"));
    }

    @Test
    void firstRunStopsUntilAccountsAreLinked() {
        AdeskRun r = run(true);
        when(ad.bankAccounts()).thenReturn(List.of(new AdAccount(315644, "Shofirkon Mega Fayz", "2", 118024L, BigDecimal.ZERO, BigDecimal.ZERO, null, "open", "UZS")));
        when(msr.accounts(anyList())).thenReturn(List.of(new MsAccount(ORG + ":CASH", ORG, null, "Касса", "", "", true, false)));
        String c = svc.existingConflict(r);
        assertNotNull(c);
        assertTrue(c.contains("1 ta hisob") && c.contains("Hisoblarni bog'lash"), c);

        // bot o'zi bog'lagan hisob begona emas, lekin tasdiqsiz qolgan qo'lda ochilgan hisob — baribir kutadi
        when(repo.findByKind(AdeskLink.ACCOUNT)).thenReturn(List.of(link(AdeskLink.ACCOUNT, ORG + ":x", 999)));
        svc = new AdeskSyncService(cfg, ad, msr, repo, reverse);
        assertNotNull(svc.existingConflict(run(true)), "eski bog'lanishlar bor, ammo 315644 bog'lanmagan");
        when(repo.findByKind(AdeskLink.ACCOUNT)).thenReturn(List.of(link(AdeskLink.ACCOUNT, ORG + ":x", 315644)));
        svc = new AdeskSyncService(cfg, ad, msr, repo, reverse);
        assertNull(svc.existingConflict(run(true)), "hamma Adesk hisobi bog'langan");
        when(repo.findByKind(AdeskLink.ACCOUNT)).thenReturn(List.of());
        svc = new AdeskSyncService(cfg, ad, msr, repo, reverse);

        when(cfg.accountsConfirmed()).thenReturn(true);
        assertNull(svc.existingConflict(r), "bog'lash tasdiqlangan — davom etadi");

        when(cfg.accountsConfirmed()).thenReturn(false);
        when(ad.bankAccounts()).thenReturn(List.of(new AdAccount(1, "Касса · 001 NSB", "Cash", null, BigDecimal.ZERO, BigDecimal.ZERO, null, "open", "UZS")));
        assertNull(svc.existingConflict(r), "nomi mos — bog'lanadi, to'xtamaydi");
        assertTrue(AdeskSyncService.leMatches(new AdLegal(1, "New Star Bukhara", ""), r.orgs.get(0)));
        assertFalse(AdeskSyncService.leMatches(new AdLegal(1, "NSB", ""), r.orgs.get(1)));
    }

    @Test
    void linkedAccountIsRenamedToMoySkladNameKeepingType() {
        when(msr.accounts(anyList())).thenReturn(List.of(new MsAccount(ORG2 + ":acc-2", ORG2, "acc-2", "SHMF р/с: 2020", "", "", false, true)));
        AdeskLink l = link(AdeskLink.ACCOUNT, ORG2 + ":acc-2", 315644);
        when(repo.findByKind(AdeskLink.ACCOUNT)).thenReturn(List.of(l));
        when(ad.bankAccounts()).thenReturn(List.of(new AdAccount(315644, "Shofirkon Mega Fayz", "1", 118024L, BigDecimal.ZERO,
                new BigDecimal("43278530.06"), LocalDate.of(2026, 8, 18), "open", "UZS")));
        AdeskRun r = run(false);
        r.orgLe.put(ORG2, 2L);
        r.balances = Map.of(ORG2 + ":acc-2", 500_00L);
        r.allMoney = List.of();
        svc.accounts(r);
        verify(ad).updateBankAccount(315644L, "SHMF р/с: 2020", 2L, 1, new BigDecimal("500.00"), START.minusDays(1));
        assertEquals(1, r.get("acc.renamed"));
        assertEquals(315644L, r.account.get(ORG2 + ":acc-2"));
    }

    @Test
    void legalEntityLimitFallsBackToMainEntityAndRetriesOnFullRun() {
        when(ad.legalEntities()).thenReturn(List.of(new AdLegal(118024, "New Star Bukhara", "")));
        when(ad.createLegalEntity(anyString(), any(), any())).thenThrow(new AdeskHttp.AdeskException(200, 0, "Превышен лимит юрлиц", false));
        AdeskRun r = run(false);
        svc.orgs(r);
        assertEquals(118024L, r.orgLe.get(ORG), "001 NSB — nom ichida, bog'landi");
        assertEquals(118024L, r.orgLe.get(ORG2), "limit — asosiy yuridik shaxsga biriktirildi");
        assertEquals(1, r.get("le.fallback"));
        assertEquals(118024L, r.stockLe);
        AdeskLink fb = saved.stream().filter(l -> l.getMsKey().equals(ORG2)).reduce((a, b) -> b).orElseThrow();
        assertEquals(AdeskSyncService.LE_FALLBACK, fb.getHash());

        // to'liq sinxronda qayta uriniladi; tarif oshirilgan bo'lsa — o'z yuridik shaxsi
        when(repo.findByKind(AdeskLink.ORG)).thenReturn(List.of(link(AdeskLink.ORG, ORG, 118024), fb));
        reset(ad);
        when(ad.legalEntities()).thenReturn(List.of(new AdLegal(118024, "New Star Bukhara", "")));
        when(ad.createLegalEntity(anyString(), any(), any())).thenReturn(new AdLegal(119700, "003 ITT", ""));
        AdeskRun r2 = run(true);
        svc.orgs(r2);
        assertEquals(119700L, r2.orgLe.get(ORG2));
        AdeskRun r3 = run(false);   // oddiy yurishda qayta urinilmaydi
        AdeskLink fb3 = link(AdeskLink.ORG, ORG2, 118024);
        fb3.setHash(AdeskSyncService.LE_FALLBACK);
        when(repo.findByKind(AdeskLink.ORG)).thenReturn(List.of(link(AdeskLink.ORG, ORG, 118024), fb3));
        reset(ad);
        when(ad.legalEntities()).thenReturn(List.of(new AdLegal(118024, "New Star Bukhara", "")));
        svc = new AdeskSyncService(cfg, ad, msr, repo, reverse);
        svc.orgs(r3);
        verify(ad, never()).createLegalEntity(anyString(), any(), any());
        assertEquals(118024L, r3.orgLe.get(ORG2));
    }

    @Test
    void singleLegalEntityModeMapsEveryFirmToMainAndTxGetProject() {
        when(cfg.singleLe()).thenReturn(true);
        when(ad.legalEntities()).thenReturn(List.of(new AdLegal(118024, "New Star Bukhara", ""), new AdLegal(119661, "003 ITT INTER TECHNO TRUST MCHJ", "")));
        AdeskRun r = run(false);
        svc.orgs(r);
        assertEquals(118024L, r.orgLe.get(ORG));
        assertEquals(118024L, r.orgLe.get(ORG2), "ITT ham asosiy yuridik shaxs ostida");
        assertEquals(118024L, r.stockLe);
        verify(ad, never()).createLegalEntity(anyString(), any(), any());

        r.projectId = 967986L;
        when(msr.moneyDocs(eq(START), any(), isNull(), any())).thenReturn(List.of(doc("1", "cashin", 100_00, "CASH", "organization", ORG, "", "", true)));
        when(ad.createTransactions(anyList())).thenReturn(Map.of("ms:1", 7001L));
        when(ad.transactions(any(), any())).thenReturn(List.of());
        svc.money(r);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ObjectNode>> cap = ArgumentCaptor.forClass(List.class);
        verify(ad).createTransactions(cap.capture());
        assertEquals(967986L, cap.getValue().get(0).path("projectId").asLong());
    }

    @Test
    void menuButtonIsNotAValue() {
        assertEquals("Выручка", AdeskConfig.cleanCat("📒 Adesk", "Выручка"));
        assertEquals("Выручка", AdeskConfig.cleanCat("", "Выручка"));
        assertEquals("Продажи", AdeskConfig.cleanCat(" Продажи ", "Выручка"));
        assertTrue(uz.kassa.bot.handlers.AdeskHandler.isButton("📒 Adesk"));
        assertTrue(uz.kassa.bot.handlers.AdeskHandler.isButton("⬅️ Orqaga"));
        assertFalse(uz.kassa.bot.handlers.AdeskHandler.isButton("-1001234567890"));
        assertFalse(uz.kassa.bot.handlers.AdeskHandler.isButton("30.09.2026"));
        assertFalse(uz.kassa.bot.handlers.AdeskHandler.isButton("Выручка"));
        assertFalse(uz.kassa.bot.handlers.AdeskHandler.isButton("-"));
    }

    @Test
    void docWrittenFromAdeskIsNotCopiedBackAndBotDuplicatesAreRemoved() {
        MsMoneyDoc fromAd = new MsMoneyDoc("m1", "paymentout", "02625", LocalDate.of(2026, 9, 8), LocalDateTime.of(2026, 9, 8, 10, 0),
                1_000_000_00L, 1_000_000_00L, "", 1, ORG, ORG + ":" + ACC, ORG2, "organization", "ei-tr", "[Adesk #555] Перемещение", "", true);
        when(msr.moneyDocs(eq(START), any(), isNull(), any())).thenReturn(List.of(fromAd));
        when(ad.transactions(any(), any())).thenReturn(List.of(
                new AdTx(555, 2, new BigDecimal("1000000.00"), LocalDate.of(2026, 9, 8), 502L, null, null, "1", true, false, "", "", "", null, "NSB р/с"),
                new AdTx(777, 2, new BigDecimal("1000000.00"), LocalDate.of(2026, 9, 8), 502L, 22L, null,
                        "[Adesk #555] Перемещение · MS Исходящий платёж №02625", false, false, "", "Перемещение", "", null, "NSB р/с")));
        AdeskRun r = run(true);
        svc.money(r);
        verify(ad, never()).createTransactions(anyList());
        verify(ad, never()).updateTransactions(anyList());
        verify(ad).removeTransactions(List.of(777L));
        AdeskLink l = saved.stream().filter(x -> x.getMsKey().equals("m1")).reduce((a, b) -> b).orElseThrow();
        assertEquals(555L, l.getAdeskId());
        assertEquals(AdeskLink.FROM_AD, l.getOrigin());
        assertEquals(1, r.get("tx.dupRemoved"));

        // eski format (belgi oxirida) ham taniladi; yangi izohda foydalanuvchi matni saqlanadi
        MsMoneyDoc old = new MsMoneyDoc("m2", "cashin", "07400", LocalDate.of(2026, 10, 8), null, 100_00L, 100_00L, "", 1, ORG,
                ORG + ":CASH", ORG, "organization", "", "qo'lda yozildi [Adesk #888 · статья: Выручка]", "", true);
        assertEquals(888L, AdeskSyncService.adeskOrigin(old));
        assertEquals("[Adesk #144230678] Перемещение · 1", AdeskReverseService.msDesc(144230678L, "Перемещение", "1", null));
        assertEquals("[Adesk #5] Перемещение", AdeskReverseService.msDesc(5L, "Перемещение", " ", null));
        assertEquals("[Adesk #6] ijara · статья: Выручка", AdeskReverseService.msDesc(6L, null, "ijara", "статья: Выручка"));
    }

    static MsMoneyDoc trDoc(String id, String entity, String number, LocalDate date, long tiyin, String org, String acc, String agent,
                            String expense, String purpose) {
        return new MsMoneyDoc(id, entity, number, date, LocalDateTime.of(date, java.time.LocalTime.NOON), tiyin, tiyin, "", 1, org,
                org + ":" + acc, agent, "organization", expense, "", purpose, true);
    }

    static final LocalDate D5 = LocalDate.of(2026, 9, 5);

    @Test
    void transferLegsArePairedByFirmsAmountAndDate() {
        MsMoneyDoc outA = trDoc("oA", "paymentout", "02558", D5, 100_00, ORG, ACC, ORG2, "ei-tr", "");
        MsMoneyDoc outB = trDoc("oB", "paymentout", "02559", D5, 100_00, ORG2, "acc-2", ORG, "ei-tr", "");
        MsMoneyDoc inA = trDoc("iA", "paymentin", "04400", D5, 100_00, ORG2, "acc-2", ORG, "", "Перемещение собственных средств");
        // kirim keyingi kunda — juft qilinmaydi (Adesk perevodi bitta sanali, kunlik qoldiq siljimasin)
        MsMoneyDoc outE = trDoc("oE", "paymentout", "02562", D5, 11_00, ORG, ACC, ORG2, "ei-tr", "");
        MsMoneyDoc inE = trDoc("iE", "paymentin", "04404", D5.plusDays(1), 11_00, ORG2, "acc-2", ORG, "", "");
        MsMoneyDoc inB = trDoc("iB", "paymentin", "04401", D5, 100_00, ORG, ACC + "x", ORG2, "", "");
        MsMoneyDoc far = trDoc("iF", "paymentin", "04402", D5.plusDays(9), 100_00, ORG2, "acc-2", ORG, "", "");
        // bitta firma ichida (р/с → kassa): xuddi shu hisobga kirim juft bo'lmaydi
        MsMoneyDoc outC = trDoc("oC", "paymentout", "02560", D5, 7_00, ORG, ACC, ORG, "ei-tr", "");
        MsMoneyDoc inSame = trDoc("iS", "paymentin", "04403", D5, 7_00, ORG, ACC, ORG, "", "Перемещение собственных средств");
        MsMoneyDoc inC = trDoc("iC", "cashin", "07400", D5, 7_00, ORG, "CASH", ORG, "", "");
        // chiqimda agent firmaning o'zi (ORG2 р/с → ORG2), kirim boshqa firma kassasiga ORG2 dan
        MsMoneyDoc outD = trDoc("oD", "paymentout", "02561", D5, 9_00, ORG2, "acc-2", ORG2, "ei-tr", "");
        MsMoneyDoc inD = trDoc("iD", "cashin", "07401", D5, 9_00, ORG, "CASH", ORG2, "", "");
        MsMoneyDoc inWrong = trDoc("iW", "cashin", "07402", D5, 9_00, ORG, "CASH", ORG, "", "");   // agent boshqa firma — juft emas
        List<AdeskSyncService.TrPair> p = AdeskSyncService.pairTransfers(List.of(outB, outC, outD, outE, outA),
                List.of(far, inWrong, inSame, inB, inC, inD, inE, inA));
        assertEquals(4, p.size());
        assertTrue(p.stream().noneMatch(x -> x.out() == outE));
        assertEquals("iD", p.stream().filter(x -> x.out() == outD).findFirst().orElseThrow().in().id());
        assertEquals("iA", p.stream().filter(x -> x.out() == outA).findFirst().orElseThrow().in().id(), "ORG → ORG2: kirim ORG2 da, agent ORG");
        assertEquals("iB", p.stream().filter(x -> x.out() == outB).findFirst().orElseThrow().in().id());
        assertEquals("iC", p.stream().filter(x -> x.out() == outC).findFirst().orElseThrow().in().id());
        String desc = AdeskSyncService.trDesc(p.get(0));
        assertTrue(desc.contains("MS Исходящий платёж №02558 → Входящий платёж №04400"), desc);
        String longDesc = AdeskSyncService.trDesc(new AdeskSyncService.TrPair(
                new MsMoneyDoc("x", "paymentout", "1", D5, null, 1, 1, "", 1, ORG, ORG + ":" + ACC, ORG2, "organization", "ei-tr", "z".repeat(900), "", true), inA));
        assertTrue(longDesc.length() <= 510 && longDesc.endsWith("№04400"), "belgi kesilmaydi");
    }

    /** MoySklad perevodi (chiqim + kirim, avval ikki oddiy operatsiya) → Adesk'da bitta perevod; qayta yurishda tegilmaydi; jufti buzilsa bekor qilinadi. */
    /** Sozlama o'chiq (standart): perevod juftlari avvalgidek ikki oddiy operatsiya — Adesk'da hech narsa yaratilmaydi/o'chirilmaydi. */
    @Test
    void transferConversionIsOffByDefault() {
        MsMoneyDoc out = trDoc("o1", "paymentout", "02558", D5, 1_000_000_00, ORG, ACC, ORG2, "ei-tr", "");
        MsMoneyDoc in = trDoc("i1", "paymentin", "04400", D5, 1_000_000_00, ORG2, "acc-2", ORG, "", "Перемещение собственных средств 001");
        when(msr.moneyDocs(eq(START), any(), isNull(), any())).thenReturn(List.of(out, in));
        when(ad.createTransactions(anyList())).thenReturn(Map.of("ms:o1", 7001L, "ms:i1", 7002L));
        when(ad.transactions(any(), any())).thenReturn(List.of());
        AdeskRun r = run(true);
        r.account.put(ORG2 + ":acc-2", 503L);
        svc.money(r);
        verify(ad, never()).createTransfer(anyLong(), anyLong(), any(), any(), any());
        verify(ad, never()).removeTransactions(anyList());
        assertEquals(2, r.get("tx.created"));
    }

    @Test
    void msTransferPairBecomesSingleAdeskTransfer() {
        when(cfg.trConvert()).thenReturn(true);
        MsMoneyDoc out = trDoc("o1", "paymentout", "02558", D5, 1_000_000_00, ORG, ACC, ORG2, "ei-tr", "");
        MsMoneyDoc in = trDoc("i1", "paymentin", "04400", D5, 1_000_000_00, ORG2, "acc-2", ORG, "", "Перемещение собственных средств 001");
        AdeskLink lo = link(AdeskLink.MONEY, "o1", 7001), li = link(AdeskLink.MONEY, "i1", 7002);
        lo.setHash("old"); li.setHash("old");
        when(repo.findByKind(AdeskLink.MONEY)).thenReturn(List.of(lo, li));
        when(msr.moneyDocs(eq(START), any(), isNull(), any())).thenReturn(List.of(out, in));
        String desc = "MS Исходящий платёж №02558 → Входящий платёж №04400";
        List<AdTx> regular = List.of(
                new AdTx(7001, 2, new BigDecimal("1000000.00"), D5, 502L, 22L, null, "MS Исходящий платёж №02558", false, false, "", "", "", null, "NSB р/с"),
                new AdTx(7002, 1, new BigDecimal("1000000.00"), D5, 503L, 12L, null, "MS Входящий платёж №04400", false, false, "", "", "", null, "ITT р/с"));
        List<AdTx> legs = List.of(
                new AdTx(9001, 2, new BigDecimal("1000000.00"), D5, 502L, null, null, desc, true, false, "", "", "", null, "NSB р/с", 9002L),
                new AdTx(9002, 1, new BigDecimal("1000000.00"), D5, 503L, null, null, desc, true, false, "", "", "", null, "ITT р/с", 9001L));
        when(ad.transactions(any(), any())).thenReturn(regular, legs);

        AdeskRun r = run(true);
        r.account.put(ORG2 + ":acc-2", 503L);
        svc.money(r);

        verify(ad).createTransfer(502L, 503L, new BigDecimal("1000000.00"), D5, desc);
        verify(ad).removeTransactions(List.of(7001L, 7002L));
        verify(ad, never()).createTransactions(anyList());
        verify(ad, never()).updateTransactions(anyList());
        assertEquals(9001L, lo.getAdeskId());
        assertEquals(9002L, li.getAdeskId());
        assertTrue(AdeskSyncService.isTr(lo) && lo.getHash().equals(li.getHash()));
        assertEquals(1, r.get("tr.created"));
        verify(reverse).handle(same(r), eq(List.of()));

        // qayta to'liq yurish: perevod joyida — hech narsa yaratilmaydi/o'chirilmaydi
        when(ad.transactions(any(), any())).thenReturn(legs);
        AdeskRun r2 = run(true);
        r2.account.put(ORG2 + ":acc-2", 503L);
        svc.money(r2);
        verify(ad, times(1)).createTransfer(anyLong(), anyLong(), any(), any(), any());
        verify(ad, times(1)).removeTransactions(anyList());
        assertEquals(0, r2.get("tr.created") + r2.get("tr.dropped"));

        // kirim MoySklad'dan o'chirildi: perevod Adesk'dan o'chiriladi, chiqim oddiy operatsiya bo'lib qaytadi
        when(msr.moneyDocs(eq(START), any(), isNull(), any())).thenReturn(List.of(out));
        when(ad.transactions(any(), any())).thenReturn(legs, List.of());
        when(ad.createTransactions(anyList())).thenReturn(Map.of("ms:o1", 7100L));
        AdeskRun r3 = run(true);
        r3.account.put(ORG2 + ":acc-2", 503L);
        svc.money(r3);
        verify(ad).removeTransactions(List.of(9001L, 9002L));
        assertEquals(1, r3.get("tr.dropped"));
        assertEquals(7100L, lo.getAdeskId());
        assertFalse(AdeskSyncService.isTr(lo));
        assertEquals(AdeskLink.DELETED, li.getStatus());
    }

    @Test
    void transferLegsNotFoundAfterCreateStopsWithoutRemovingOldOps() {
        when(cfg.trConvert()).thenReturn(true);
        MsMoneyDoc out = trDoc("o1", "paymentout", "02558", D5, 500_00, ORG, ACC, ORG2, "ei-tr", "");
        MsMoneyDoc in = trDoc("i1", "paymentin", "04400", D5, 500_00, ORG2, "acc-2", ORG, "", "");
        AdeskLink lo = link(AdeskLink.MONEY, "o1", 7001), li = link(AdeskLink.MONEY, "i1", 7002);
        when(repo.findByKind(AdeskLink.MONEY)).thenReturn(List.of(lo, li));
        when(msr.moneyDocs(eq(START), any(), isNull(), any())).thenReturn(List.of(out, in));
        when(ad.transactions(any(), any())).thenReturn(List.of());
        AdeskRun r = run(true);
        r.account.put(ORG2 + ":acc-2", 503L);
        svc.money(r);
        verify(ad).createTransfer(anyLong(), anyLong(), any(), any(), any());
        verify(ad, never()).removeTransactions(anyList());
        assertEquals(AdeskLink.ERROR, lo.getStatus());
        assertEquals(7001L, lo.getAdeskId(), "eski operatsiya bog'lanishi saqlanadi");
        assertEquals(1, r.get("tr.error"));
    }

    @Test
    void helpers() {
        assertEquals(2, AdeskSyncService.kindOf("Покупка основных средств"));
        assertEquals(3, AdeskSyncService.kindOf("Погашение кредита (тело)"));
        assertEquals(1, AdeskSyncService.kindOf("Зарплата"));
        assertEquals("шт", AdeskSyncService.unitSymbol("Штука"));
        assertEquals("2.5", AdeskSyncService.qty(2.5));
        assertEquals("3", AdeskSyncService.qty(3.0));
        assertEquals(12345, AdeskSyncService.tiyin(new BigDecimal("123.45")));
        assertEquals("001 NSB", AdeskMsReader.shortName("001 NSB New Star Bukhara МЧЖ 302037932"));
        assertEquals("007 BIZNES", AdeskMsReader.shortName("007 \"BIZNES PLYUS SERVIS\" МЧЖ"));
        assertTrue(AdeskMsReader.looksCash("Kassa 1 Naqd UZS"));
        assertFalse(AdeskMsReader.looksCash("NSB click Zufar"));
        assertEquals(LocalDate.of(2018, 12, 20), AdeskClient.date("2018.12.20"));
        assertEquals(LocalDate.of(2018, 12, 20), AdeskClient.date("20.12.2018"));
        assertEquals(LocalDate.of(2026, 9, 5), AdeskClient.date("2026-09-05"));
        assertNull(AdeskClient.date("Сегодня"));
        assertEquals(AdeskSyncService.sha("a", "b"), AdeskSyncService.sha("a", "b"));
        assertNotEquals(AdeskSyncService.sha("a", "b"), AdeskSyncService.sha("ab", ""));
        MsMoneyDoc usd = new MsMoneyDoc("9", "cashout", "0009", START, null, 1_185_000_00L, 100_00L, "USD", 11850, ORG, ORG + ":CASH",
                "", "", "", "", "", true);
        assertTrue(AdeskSyncService.txDesc(usd).contains("100 USD × 11850"));
    }

    @Test
    void adeskErrorText() throws Exception {
        ObjectMapper om = new ObjectMapper();
        assertEquals("Auth required", AdeskHttp.errorText(200, om.readTree("{\"success\": false, \"message\": \"Auth required\", \"code\": 401}"), ""));
        assertEquals("name: Обязательное поле.", AdeskHttp.errorText(200, om.readTree("{\"success\": false, \"errors\": {\"name\": [\"Обязательное поле.\"]}}"), ""));
    }
}
