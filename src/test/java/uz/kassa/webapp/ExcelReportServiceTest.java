package uz.kassa.webapp;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import uz.kassa.bot.NameService;
import uz.kassa.config.AppProps;
import uz.kassa.domain.Kassa;
import uz.kassa.repo.CategoryRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.repo.OperationRepo;
import uz.kassa.service.LedgerService;
import uz.kassa.service.moysklad.MoySkladClient;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Kassa Excel (01.11.2026): MoySklad otdeliga bog'lanmagan kassa uchun boshqa otdellar hujjatlari chiqmaydi; «Izoh» varag'i bor. */
class ExcelReportServiceTest {

    static final LocalDate D = LocalDate.of(2026, 10, 31);

    @Test
    void unboundKassaDoesNotLeakOtherOtdelDocsAndHasHelpSheet() throws Exception {
        LedgerService ledger = mock(LedgerService.class, RETURNS_DEEP_STUBS);
        when(ledger.today()).thenReturn(D);
        OperationRepo opRepo = mock(OperationRepo.class);
        when(opRepo.byPeriod(any(), any())).thenReturn(List.of());
        MoySkladClient ms = mock(MoySkladClient.class);
        when(ms.fetchGroups()).thenReturn(new java.util.LinkedHashMap<>());
        when(ms.fetchDocsByMoment(anyString(), any(), any())).thenReturn(List.of(new MoySkladClient.MsExpense(
                "id-1", "00777", D, 50_000_00, "", "boshqa-otdel", "", "", "Begona kontragent", "", "", 1.0, true, "")));
        ExcelReportService svc = new ExcelReportService(new AppProps(), ledger, mock(KassaRepo.class), opRepo,
                mock(CategoryRepo.class), mock(NameService.class), ms);

        Kassa k = new Kassa();
        k.setId(7L); k.setName("Сервис 2");   // moyskladGroupId yo'q

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(svc.build(D, D, k)))) {
            Sheet msSheet = wb.getSheet("MoySklad");
            StringBuilder all = new StringBuilder();
            for (Row r : msSheet) r.forEach(c -> all.append(c.toString()).append(' '));
            assertFalse(all.toString().contains("00777"), "boshqa otdel hujjati chiqmasligi kerak: " + all);
            assertTrue(all.toString().contains("bog'lanmagan"), all.toString());
            assertNotNull(wb.getSheet("Izoh"));
        }
    }
}
