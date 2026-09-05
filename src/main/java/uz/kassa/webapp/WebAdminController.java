package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Role;
import uz.kassa.service.BusinessException;

import java.util.Map;

/**
 * 🌐 Админ панел (Mini App) REST API — faqat Buxgalter/SuperAdmin.
 * Route qoidasi: resurs + HTTP metod, ID yo'lda, filtr so'rovda.
 *   GET /api/admin/dashboard        — 🏠 Бугун
 *   GET /api/admin/kassa/{id}       — 🏪 kassa kartasi
 *   GET /api/admin/pending/{id}     — 📥 kutilayotgan hisobot (qaror: POST /api/decide)
 *   GET /api/admin/cards            — 💳 Click kartalari
 * Autentifikatsiya WebAppController bilan bir xil: X-Telegram-Init-Data imzosi.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class WebAdminController {

    private final TelegramWebAppAuth auth;
    private final AdminApiService api;
    private final AdminReportService reports;
    private final AdminActionService actions;
    private final AdminMoneyReportService moneyReport;

    private AppUser admin(String initData) {
        AppUser u = auth.authenticate(initData);
        if (u == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Ruxsat yo'q");
        if (u.getRole() == Role.KASSIR)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Админ панел фақат бухгалтер ва админ учун");
        return u;
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> business(BusinessException e) {
        return Map.of("error", e.getMessage());
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestHeader("X-Telegram-Init-Data") String init) {
        admin(init);
        return api.dashboard();
    }

    @GetMapping("/kassa/{id}")
    public Map<String, Object> kassa(@RequestHeader("X-Telegram-Init-Data") String init,
                                     @PathVariable long id) {
        admin(init);
        return api.kassa(id);
    }

    @GetMapping("/pending/{id}")
    public Map<String, Object> pending(@RequestHeader("X-Telegram-Init-Data") String init,
                                       @PathVariable long id) {
        admin(init);
        return api.pending(id);
    }

    @GetMapping("/cards")
    public Map<String, Object> cards(@RequestHeader("X-Telegram-Init-Data") String init) {
        admin(init);
        return api.cardsPage();
    }

    /* ---------------- 🏪 Kassa amallari ---------------- */

    @PostMapping("/kassa/{id}/collect")
    public Map<String, Object> collect(@RequestHeader("X-Telegram-Init-Data") String init, @PathVariable long id,
                                       @RequestBody AdminActionService.CollectReq r) {
        return actions.collect(admin(init), id, r);
    }

    @PostMapping("/kassa/{id}/adjust")
    public Map<String, Object> adjust(@RequestHeader("X-Telegram-Init-Data") String init, @PathVariable long id,
                                      @RequestBody AdminActionService.AdjustReq r) {
        return actions.adjust(admin(init), id, r);
    }

    /* ---------------- 📊 Ҳисоботлар ---------------- */

    public record DateReq(String date) {}
    public record ExcelReq(String from, String to, Long kassaId) {}

    private java.time.LocalDate date(String s) {
        try { return s == null || s.isBlank() ? reports.today() : java.time.LocalDate.parse(s); }
        catch (Exception e) { throw new BusinessException("Сана нотўғри: " + s); }
    }

    @GetMapping("/report/daily")
    public Map<String, Object> daily(@RequestHeader("X-Telegram-Init-Data") String init,
                                     @RequestParam(required = false) String date) {
        admin(init);
        return reports.daily(date(date));
    }

    @PostMapping("/report/daily/confirm")
    public Map<String, Object> dailyConfirm(@RequestHeader("X-Telegram-Init-Data") String init,
                                            @RequestBody DateReq r) {
        AppUser u = admin(init);
        return Map.of("fresh", reports.confirmDaily(date(r.date()), u));
    }

    @PostMapping("/report/daily/send")
    public Map<String, Object> dailySend(@RequestHeader("X-Telegram-Init-Data") String init,
                                         @RequestBody DateReq r) {
        AppUser u = admin(init);
        reports.sendDaily(date(r.date()), u);
        return Map.of("ok", true);
    }

    @GetMapping("/report/tushum")
    public Map<String, Object> tushum(@RequestHeader("X-Telegram-Init-Data") String init,
                                      @RequestParam(required = false) String date) {
        admin(init);
        return reports.tushum(date(date));
    }

    @GetMapping("/report/money")
    public Map<String, Object> money(@RequestHeader("X-Telegram-Init-Data") String init,
                                     @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                     @RequestParam(required = false, defaultValue = "0") long kassaId) {
        admin(init);
        return moneyReport.money(date(from), date(to), kassaId);
    }

    @PostMapping("/report/money/excel")
    public Map<String, Object> moneyExcel(@RequestHeader("X-Telegram-Init-Data") String init, @RequestBody ExcelReq r) {
        moneyReport.sendExcel(admin(init), date(r.from()), date(r.to()), r.kassaId());
        return Map.of("ok", true);
    }

    @PostMapping("/report/excel")
    public Map<String, Object> excel(@RequestHeader("X-Telegram-Init-Data") String init,
                                     @RequestBody ExcelReq r) {
        AppUser u = admin(init);
        return Map.of("label", reports.sendExcel(date(r.from()), date(r.to()), r.kassaId(), u));
    }
}
