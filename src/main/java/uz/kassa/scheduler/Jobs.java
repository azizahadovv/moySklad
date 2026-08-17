package uz.kassa.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.kassa.bot.TextUtil;
import uz.kassa.domain.*;
import uz.kassa.repo.DayRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.service.DayService;
import uz.kassa.service.LedgerService;
import uz.kassa.service.NotificationService;
import uz.kassa.service.SubmissionService;
import uz.kassa.service.moysklad.MoySkladSyncService;

import java.time.LocalDate;
import java.util.List;

/** Rejalashtirilgan ishlar (TZ 12): sinxron 5 daq, kun yopilishi 00:00, eslatma 21:00. */
@Component
@RequiredArgsConstructor
@Slf4j
public class Jobs {

    private final MoySkladSyncService syncService;
    private final DayService dayService;
    private final SubmissionService submissionService;
    private final LedgerService ledger;
    private final KassaRepo kassaRepo;
    private final DayRepo dayRepo;
    private final NotificationService notify;

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT30S")
    public void moyskladSync() {
        try {
            syncService.sync();
        } catch (Exception e) {
            log.error("Sinxron xatosi: {}", e.getMessage(), e);
        }
    }

    /** 00:00 Asia/Tashkent — o'tgan kunlarni yopish (TZ 7.2). */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Tashkent")
    public void closeDays() {
        try {
            List<DayRecord> closed = dayService.closeOpenDaysBefore(ledger.today());
            if (!closed.isEmpty()) log.info("Kun yopilishi: {} ta yozuv yopildi", closed.size());
        } catch (Exception e) {
            log.error("Kun yopishda xato: {}", e.getMessage(), e);
        }
    }

    /** Har kuni app.reminder-hour (standart 21:00) — kassirlarga eslatma (TZ 7.2). */
    @Scheduled(cron = "0 0 ${app.reminder-hour:21} * * *", zone = "Asia/Tashkent")
    public void reminder() {
        LocalDate today = ledger.today();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            try {
                DayRecord d = dayRepo.findByKassaIdAndDate(k.getId(), today).orElse(null);
                List<DayRecord> pending = submissionService.submittableDays(k.getId());
                long availNaqd = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.NAQD).available();
                long availKlik = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.KLIK).available();

                boolean quiet = d == null && pending.isEmpty() && availNaqd == 0 && availKlik == 0;
                if (quiet) continue;

                StringBuilder sb = new StringBuilder("🔔 <b>Kunlik eslatma</b>\n\n");
                if (d != null) sb.append("Bugungi kirim: Naqd ").append(TextUtil.fmt(d.getPrixodNaqd()))
                        .append(" · Click ").append(TextUtil.fmt(d.getPrixodKlik()))
                        .append(" · Terminal ").append(TextUtil.fmt(d.getPrixodTerminal())).append(" so'm\n");
                if (!pending.isEmpty())
                    sb.append("Topshirilmagan kunlar: <b>").append(pending.size()).append("</b> ta\n");
                sb.append("Qo'lingizdagi qoldiq: Naqd ").append(TextUtil.fmt(availNaqd))
                        .append(" · Click ").append(TextUtil.fmt(availKlik)).append(" so'm");

                notify.toKassa(k.getId(), sb.toString(), null);
            } catch (Exception e) {
                log.warn("Eslatma ({}): {}", k.getName(), e.getMessage());
            }
        }
    }
}
