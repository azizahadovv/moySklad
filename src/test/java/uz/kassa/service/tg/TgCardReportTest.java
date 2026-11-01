package uz.kassa.service.tg;

import org.junit.jupiter.api.Test;
import uz.kassa.bot.Sender;
import uz.kassa.domain.TgCard;
import uz.kassa.repo.ClickAccountRepo;
import uz.kassa.repo.TgCardRepo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 💳 Karta qoldiqlari matni (01.11.2026): eskirgan karta ⏰, yo'nalishsiz xabar «•», shaxsiy karta chiqmaydi. */
class TgCardReportTest {

    @Test
    void marksStaleCardsAndUnknownDirectionAndSkipsHidden() {
        TgCardRepo repo = mock(TgCardRepo.class);
        TgCard old = TgCard.builder().mask("1234").name("HUMO Ali").balance(1_234_550).lastDir(null).lastAmount(10_000_00L)
                .lastTxnAt(LocalDateTime.of(2026, 10, 28, 12, 0)).updatedAt(Instant.now().minus(3, ChronoUnit.DAYS)).build();
        TgCard personal = TgCard.builder().mask("9999").name("Shaxsiy").balance(99_000_000_00L).hidden(true).build();
        when(repo.findAllByOrderByNameAscMaskAsc()).thenReturn(List.of(old, personal));
        TgCardReport r = new TgCardReport(repo, mock(ClickAccountRepo.class), mock(TgReaderConfig.class), mock(Sender.class));

        String t = r.text();
        assertTrue(t.contains("охирги •"), t);
        assertTrue(t.contains("⏰"), t);
        assertTrue(t.contains("12 345.5"), t);
        assertFalse(t.contains("9999"), "shaxsiy karta chiqmasligi kerak: " + t);
    }
}
