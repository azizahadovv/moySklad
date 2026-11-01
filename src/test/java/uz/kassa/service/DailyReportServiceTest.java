package uz.kassa.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import uz.kassa.config.AppProps;
import uz.kassa.domain.ClickAccount;
import uz.kassa.repo.*;
import uz.kassa.bot.Sender;
import uz.kassa.service.moysklad.MoySkladAuditService;
import uz.kassa.service.moysklad.MoySkladAuditService.DayDoc;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Kunlik solishtirish Excel'i (01.11.2026): Bot savdosi/Farq ustunlari yo'q, «Hujjatlar» jami = kirim, «Izoh» varag'i bor. */
class DailyReportServiceTest {

    static final LocalDate D = LocalDate.of(2026, 10, 31);

    DailyReportService svc(ClickAccountRepo clickRepo) {
        return new DailyReportService(new AppProps(), mock(NotifySwitches.class), mock(KassaRepo.class), mock(DayRepo.class),
                mock(OperationRepo.class), clickRepo, mock(AppUserRepo.class), mock(LedgerService.class),
                mock(SettingsService.class), mock(DailyReportConfirmRepo.class), mock(Sender.class), mock(MoySkladAuditService.class));
    }

    @Test
    void excelHasDocsAndHelpSheetsAndNoFarqColumn() throws Exception {
        ClickAccountRepo clickRepo = mock(ClickAccountRepo.class);
        ClickAccount ca = new ClickAccount();
        ca.setId(5L); ca.setName("Klik Jasur"); ca.setKassaId(1L);
        ca.setCardBalance(1613912475L); ca.setCardBalanceBy("HUMOcardbot"); ca.setCardBalanceAt(Instant.parse("2026-10-31T16:00:00Z"));
        when(clickRepo.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(ca));
        DailyReportService s = svc(clickRepo);

        List<DayDoc> docs = List.of(
                new DayDoc(1L, "Приходный ордер (нақд)", "00101", "Ali", "", 297_000),
                new DayDoc(1L, "Входящий платёж · Клик", "00102", "Vali", "", 22_000_000),
                new DayDoc(2L, "Розничная продажа", "3 та чек", "", "нақд 460 000 · нақдсиз 0", 460_000),
                new DayDoc(-1L, "Приходный ордер (нақд)", "00999", "", "", 50_000));
        List<DailyReportService.Row> rows = List.of(
                new DailyReportService.Row("Компьютер дукон", "Жасур", 22_297_000, true, 22_297_000, 0, 1613912475L, true, 0, 1L),
                new DailyReportService.Row("Сервис 1", "Zulxumor", 460_000, true, 400_000, 185_000, -1, false, 60_000, 2L));

        byte[] xlsx = s.renderXlsx(D, rows, null, docs);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertEquals(3, wb.getNumberOfSheets());
            Sheet main = wb.getSheetAt(0);
            String header = rowText(main.getRow(2));
            assertFalse(header.contains("Bot"), header);
            assertFalse(header.contains("Farq"), header);
            assertTrue(header.contains("MoySklad kirimi"), header);
            assertTrue(main.getRow(4).getCell(1).getStringCellValue().startsWith("(!)"), "farq ≠ 0 bo'lgan nuqta belgilanadi");
            assertFalse(main.getRow(3).getCell(1).getStringCellValue().startsWith("(!)"));

            Sheet hs = wb.getSheet("Hujjatlar");
            assertNotNull(hs);
            assertEquals(22_297_000, jami(hs, "Jami — Компьютер дукон"));
            assertEquals(460_000, jami(hs, "Jami — Сервис 1"));
            assertEquals(50_000, jami(hs, "Jami — Otdelga bog'lanmagan (hech qaysi nuqtaga kirmaydi)"));

            Sheet is = wb.getSheet("Izoh");
            assertNotNull(is);
            StringBuilder all = new StringBuilder();
            for (Row r : is) all.append(rowText(r)).append('\n');
            assertTrue(all.toString().contains("HUMOcardbot"), "karta qoldig'i manbasi ko'rsatiladi");
            assertTrue(all.toString().contains("Naqd topshirilgan"));
        }
        byte[] png = s.render(D, rows, null);
        assertTrue(png.length > 0);
        // ko'z bilan tekshirish uchun namuna (build chiqishi, commit qilinmaydi)
        java.nio.file.Files.write(java.nio.file.Path.of("target", "daily-sample.png"), png);
        java.nio.file.Files.write(java.nio.file.Path.of("target", "daily-sample.xlsx"), xlsx);
    }

    private static long jami(Sheet sh, String label) {
        for (Row r : sh) if (r.getCell(0) != null && label.equals(r.getCell(0).getStringCellValue()))
            return (long) r.getCell(5).getNumericCellValue();
        throw new AssertionError("qator yo'q: " + label);
    }

    private static String rowText(Row r) {
        StringBuilder sb = new StringBuilder();
        if (r != null) r.forEach(c -> sb.append(c.toString()).append(" | "));
        return sb.toString();
    }
}
