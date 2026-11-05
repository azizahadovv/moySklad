package uz.kassa.service.adesk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import uz.kassa.domain.AdeskLink;
import uz.kassa.repo.AdeskLinkRepo;
import uz.kassa.service.adesk.AdeskClient.AdCommit;
import uz.kassa.service.adesk.AdeskMsReader.MsGoodsDoc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-11-04: ikkinchi bot nusxasi Adesk'da qoldirgan bog'lanmagan начислениялар. O'chirish faqat bog'lanishlar jadvali haqiqiy bo'lganda,
 * faqat otgruzka/priyomka formatidagi yozuvlarga va ko'rsatilgan sondan oshmaganda bajariladi.
 */
class AdeskCleanupServiceTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 15);

    AdeskConfig cfg;
    AdeskClient ad;
    AdeskMsReader msr;
    AdeskLinkRepo repo;
    AdeskSyncService sync;
    AdeskCleanupService svc;

    @BeforeEach
    void setUp() {
        cfg = mock(AdeskConfig.class);
        ad = mock(AdeskClient.class);
        msr = mock(AdeskMsReader.class);
        repo = mock(AdeskLinkRepo.class);
        sync = mock(AdeskSyncService.class);
        when(cfg.start()).thenReturn(LocalDate.of(2026, 9, 1));
        when(cfg.today()).thenReturn(LocalDate.of(2026, 11, 4));
        when(msr.goodsDocs(anyString(), any(), any(), isNull())).thenReturn(List.of());
        svc = new AdeskCleanupService(cfg, ad, msr, repo, sync);
    }

    static AdCommit commit(long id, int type, String amount, String desc, boolean shipment) {
        return new AdCommit(id, type, new BigDecimal(amount), DAY, 900L, desc, shipment, 967986L);
    }

    static AdeskLink linked(long adeskId) {
        return AdeskLink.builder().kind(AdeskLink.COMMIT).msKey("ms-" + adeskId).adeskId(adeskId).status(AdeskLink.OK).build();
    }

    static MsGoodsDoc demand(String number) {
        return new MsGoodsDoc("d-" + number, "demand", number, DAY, 100_00L, "org", "cp", "counterparty", "", true, List.of());
    }

    /** Hujjat egasi bor (bog'langan juft) va MoySklad'da yo'q yozuv o'chiriladi; bog'lanmagan, lekin MoySklad'da bori tegilmaydi. */
    @Test
    void planRemovesDuplicatesAndGoneDocsButKeepsUnlinkedLiveDocAndPayments() {
        when(repo.findByKind(AdeskLink.COMMIT)).thenReturn(List.of(linked(1)));
        when(ad.commitments(any(), any())).thenReturn(List.of(
                commit(1, 2, "100.00", "MS Отгрузка №15444 · Олинди", true),
                commit(2, 2, "100.00", "MS Отгрузка №15444 · Олинди", true),
                commit(3, 2, "300.00", "MS Отгрузка №15748", true),
                commit(4, 2, "400.00", "MS Отгрузка №16000", true),
                commit(5, 1, "500.00", "MS Приходный ордер №07326", false),
                commit(6, 2, "600.00", "qo'lda kiritilgan yozuv", true)));
        when(msr.goodsDocs(eq("demand"), any(), any(), isNull())).thenReturn(List.of(demand("15444"), demand("16000")));

        AdeskCleanupService.Plan p = svc.plan();

        assertNull(p.blocked());
        assertEquals(List.of(2L, 3L), p.remove().stream().map(i -> i.commit().id()).sorted().toList());
        assertEquals(List.of(4L), p.keep().stream().map(i -> i.commit().id()).toList());
    }

    /** Eskirgan bazada hamma boshqa nusxa yozuvi «ortiqcha» bo'lib ko'rinadi — bunday bazadan o'chirish taqiqlanadi. */
    @Test
    void planIsBlockedWhenLinkTableIsStale() {
        when(sync.staleInstance()).thenReturn("baza orqada");
        AdeskCleanupService.Plan p = svc.plan();
        assertEquals("baza orqada", p.blocked());
        assertTrue(p.remove().isEmpty());
        verify(ad, never()).commitments(any(), any());
    }

    /** MoySklad bo'sh qaytsa hamma yozuv «MoySklad'da yo'q» bo'lib qoladi — o'chirishga asos emas. */
    @Test
    void planIsBlockedWhenMoySkladDocsAreEmpty() {
        when(repo.findByKind(AdeskLink.COMMIT)).thenReturn(List.of(linked(1)));
        when(ad.commitments(any(), any())).thenReturn(List.of(commit(1, 2, "100.00", "MS Отгрузка №1", true), commit(2, 2, "100.00", "MS Отгрузка №2", true)));
        AdeskCleanupService.Plan p = svc.plan();
        assertNotNull(p.blocked());
        assertTrue(p.remove().isEmpty());
    }

    @Test
    void executeRemovesShipmentsBeforeReceiptsSoBatchesAreFree() {
        when(repo.findByKind(AdeskLink.COMMIT)).thenReturn(List.of(linked(1)));
        when(ad.commitments(any(), any())).thenReturn(List.of(
                commit(10, 1, "100.00", "MS Приёмка №7", true),
                commit(11, 2, "100.00", "MS Отгрузка №8", true),
                commit(1, 2, "100.00", "MS Отгрузка №9", true)));
        when(msr.goodsDocs(eq("demand"), any(), any(), isNull())).thenReturn(List.of(demand("9")));

        AdeskCleanupService.Outcome o = svc.execute(2, () -> false, x -> { });

        InOrder io = inOrder(ad);
        io.verify(ad).removeCommitment(11);
        io.verify(ad).removeCommitment(10);
        verify(ad, never()).removeCommitment(1);
        assertEquals(2, o.removed());
        assertFalse(o.aborted());
    }

    /** Tasdiqdan keyin ro'yxat kattalashgan bo'lsa — hech narsa o'chirilmaydi, SuperAdmin ko'rmagan yozuv o'chmaydi. */
    @Test
    void executeAbortsWhenPlanGrewAfterConfirmation() {
        when(repo.findByKind(AdeskLink.COMMIT)).thenReturn(List.of(linked(1)));
        when(ad.commitments(any(), any())).thenReturn(List.of(commit(10, 2, "100.00", "MS Отгрузка №7", true), commit(11, 2, "100.00", "MS Отгрузка №8", true)));
        when(msr.goodsDocs(eq("demand"), any(), any(), isNull())).thenReturn(List.of(demand("9")));

        AdeskCleanupService.Outcome o = svc.execute(1, () -> false, x -> { });

        assertTrue(o.aborted());
        verify(ad, never()).removeCommitment(anyLong());
    }

    @Test
    void executeCountsAlreadyGoneAsRemovedAndKeepsGoingAfterFailure() {
        when(repo.findByKind(AdeskLink.COMMIT)).thenReturn(List.of(linked(1)));
        when(ad.commitments(any(), any())).thenReturn(List.of(
                commit(10, 2, "100.00", "MS Отгрузка №7", true),
                commit(11, 2, "100.00", "MS Отгрузка №8", true),
                commit(12, 2, "100.00", "MS Отгрузка №10", true)));
        when(msr.goodsDocs(eq("demand"), any(), any(), isNull())).thenReturn(List.of(demand("9")));
        doThrow(new AdeskHttp.AdeskException(200, 0, "Не удается найти запрашиваемый объект", false)).when(ad).removeCommitment(10);
        doThrow(new AdeskHttp.AdeskException(200, 0, "партия использована", false)).when(ad).removeCommitment(11);

        AdeskCleanupService.Outcome o = svc.execute(3, () -> false, x -> { });

        assertEquals(2, o.removed());
        assertEquals(1, o.failed());
        assertTrue(o.errors().get(0).contains("партия использована"));
    }

    /** Solishtirish hisobotidagi ortiqcha yozuvlar: faqat otgruzka formatidagi va bog'lanmaganlar. */
    @Test
    void orphansAreOnlyUnlinkedGoodsCommitments() {
        List<AdCommit> all = List.of(
                commit(1, 2, "100.00", "MS Отгрузка №1", true),
                commit(2, 2, "100.00", "MS Отгрузка №1", true),
                commit(3, 1, "100.00", "MS Входящий платёж №5", false),
                commit(4, 1, "100.00", "MS Приёмка №6", false));
        assertEquals(List.of(2L), AdeskCleanupService.orphans(all, Set.of(1L)).stream().map(AdCommit::id).toList());
    }
}
