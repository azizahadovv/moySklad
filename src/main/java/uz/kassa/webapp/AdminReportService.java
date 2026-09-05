package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.bot.Sender;
import uz.kassa.bot.TextUtil;
import uz.kassa.domain.*;
import uz.kassa.repo.DayRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.service.AuditService;
import uz.kassa.service.BusinessException;
import uz.kassa.service.DailyReportService;
import uz.kassa.service.LedgerService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static uz.kassa.webapp.AdminApiService.mapOf;

/**
 * 📊 Ҳисоботлар (Mini App): kunlik solishtirish, bugungi tushum, Excel.
 * Hisob-kitob botdagi bilan bir xil servislardan (DailyReportService, ExcelReportService);
 * fayllar Telegram WebView'da yuklab olinmaydi, shuning uchun foydalanuvchining
 * shaxsiy chatiga yuboriladi (bot avvaldan shunday qiladi).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminReportService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Tashkent");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final DailyReportService dailyReport;
    private final ExcelReportService excel;
    private final KassaRepo kassaRepo;
    private final DayRepo dayRepo;
    private final LedgerService ledger;
    private final Sender sender;
    private final AuditService audit;

    /* ---------------- 📋 Kunlik solishtirish ---------------- */

    public Map<String, Object> daily(LocalDate d) {
        List<DailyReportService.Row> rows = dailyReport.rows(d);
        DailyReportConfirm c = dailyReport.confirmRepoView(d);
        List<Map<String, Object>> out = new ArrayList<>();
        long ms = 0, bot = 0, top = 0;
        for (DailyReportService.Row r : rows) {
            ms += r.msSavdo(); bot += r.botSavdo(); top += r.naqdTopshirilgan();
            out.add(mapOf("kassaId", r.kassaId(), "nuqta", r.nuqta(), "kassir", r.kassir(),
                    "msSavdo", r.msSavdo(), "botSavdo", r.botSavdo(), "farq", r.farq(),
                    "naqdTopshirilgan", r.naqdTopshirilgan(),
                    "p2pTiyin", r.p2pQoldiqTiyin() < 0 ? null : r.p2pQoldiqTiyin(), "p2pKnown", r.p2pKnown(),
                    "msKnown", r.msKnown()));
        }
        return mapOf("date", d.toString(), "time", dailyReport.time(),
                "msKnown", rows.stream().allMatch(DailyReportService.Row::msKnown),
                "rows", out, "jamiMs", ms, "jamiBot", bot, "jamiFarq", ms - bot, "jamiTopshirilgan", top,
                "confirm", c == null ? null : mapOf("userName", c.getUserName(),
                        "at", c.getConfirmedAt() == null ? "" : DT.format(c.getConfirmedAt().atZone(ZONE))));
    }

    /** ✔ Moliya menejeri tasdig'i (bot dr:ok bilan bir xil). */
    public boolean confirmDaily(LocalDate d, AppUser by) {
        boolean fresh = dailyReport.confirm(d, by);
        if (fresh) audit.log(by.getId(), "KUNLIK_TASDIQ", "daily", null,
                by.getFullName() + " " + d + " kunlik hisobotni tasdiqladi (web)");
        return fresh;
    }

    /** Rasm + Excel + izohni foydalanuvchining chatiga yuborish (fonda). */
    public void sendDaily(LocalDate d, AppUser u) {
        if (u.getTelegramId() == null) throw new BusinessException("Telegram уланмаган");
        long chat = u.getTelegramId();
        new Thread(() -> {
            try { dailyReport.sendTo(chat, d); }
            catch (Exception e) {
                log.warn("Web: kunlik hisobot yuborilmadi ({}): {}", chat, e.getMessage());
                sender.send(chat, "⚠️ Кунлик ҳисобот юборилмади: " + TextUtil.esc(e.getMessage()));
            }
        }, "web-daily-send").start();
    }

    /* ---------------- 💰 Bugungi tushum ---------------- */

    public Map<String, Object> tushum(LocalDate d) {
        List<Map<String, Object>> out = new ArrayList<>();
        long tn = 0, tk = 0, tt = 0;
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;
            DayRecord r = dayRepo.findByKassaIdAndDate(k.getId(), d).orElse(null);
            long n = r == null ? 0 : r.getPrixodNaqd(), kl = r == null ? 0 : r.getPrixodKlik(),
                    t = r == null ? 0 : r.getPrixodTerminal();
            tn += n; tk += kl; tt += t;
            out.add(mapOf("kassaId", k.getId(), "name", k.getName(), "naqd", n, "klik", kl, "terminal", t,
                    "jami", n + kl + t,
                    "vozvrat", r == null ? 0 : r.getVozvratNaqd() + r.getVozvratKlik(),
                    "rasxod", r == null ? 0 : r.getRasxodNaqd() + r.getRasxodKlik()));
        }
        return mapOf("date", d.toString(), "rows", out, "naqd", tn, "klik", tk, "terminal", tt, "jami", tn + tk + tt);
    }

    /* ---------------- 📊 Excel ---------------- */

    /** Excel'ni fonda tayyorlab foydalanuvchi chatiga yuboradi; javob darhol qaytadi. */
    public String sendExcel(LocalDate from, LocalDate to, Long kassaId, AppUser u) {
        if (u.getTelegramId() == null) throw new BusinessException("Telegram уланмаган");
        if (from.isAfter(to)) { LocalDate x = from; from = to; to = x; }
        if (from.isBefore(to.minusDays(370))) throw new BusinessException("Давр 1 йилдан ошмасин");
        Kassa only = kassaId == null || kassaId == 0 ? null
                : kassaRepo.findById(kassaId).orElseThrow(() -> new BusinessException("Касса топилмади"));
        String label = (only == null ? "Умумий" : only.getName()) + " · " + from + " — " + to;
        long chat = u.getTelegramId();
        LocalDate f = from, t = to;
        new Thread(() -> {
            try {
                byte[] xlsx = excel.build(f, t, only);
                sender.sendDocument(chat, xlsx,
                        "hisobot_" + (only == null ? "umumiy" : "kassa" + only.getId()) + "_" + f + "_" + t + ".xlsx",
                        "📊 Excel: <b>" + TextUtil.esc(label) + "</b>");
            } catch (Exception e) {
                log.warn("Web: Excel yuborilmadi ({}): {}", chat, e.getMessage());
                sender.send(chat, "⚠️ Excel хатоси: " + TextUtil.esc(e.getMessage()));
            }
        }, "web-excel").start();
        audit.log(u.getId(), "EXCEL", "report", null, u.getFullName() + " web: " + label);
        return label;
    }

    public LocalDate today() { return ledger.today(); }
}
