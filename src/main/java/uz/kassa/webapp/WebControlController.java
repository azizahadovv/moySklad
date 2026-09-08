package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Role;
import uz.kassa.service.BusinessException;
import java.util.Map;

/** 🌐 Админ панел → 🕵️ nazorat: qarzdorlar va kontragent xatolari (/api/admin/control/*). */
@RestController
@RequestMapping("/api/admin/control")
@RequiredArgsConstructor
public class WebControlController {

    private static final String H = "X-Telegram-Init-Data";

    private final TelegramWebAppAuth auth;
    private final AdminControlService svc;

    private AppUser admin(String initData) {
        AppUser u = auth.authenticate(initData);
        if (u == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Ruxsat yo'q");
        if (u.getRole() == Role.KASSIR)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Админ панел фақат бухгалтер ва админ учун");
        return u;
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> business(BusinessException e) { return Map.of("error", e.getMessage()); }

    public record IdReq(long id, String reason, Long kassaId) {}

    @GetMapping("/debts")
    public Map<String, Object> debts(@RequestHeader(H) String init,
                                     @RequestParam(defaultValue = "0") long kassa,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "") String state) {
        return svc.debts(admin(init), kassa, page, state);
    }

    @GetMapping("/debts/{id}")
    public Map<String, Object> debt(@RequestHeader(H) String init, @PathVariable long id) {
        return svc.debt(admin(init), id);
    }

    @PostMapping("/debts/refresh")
    public Map<String, Object> refresh(@RequestHeader(H) String init, @RequestBody IdReq r) {
        return svc.refresh(admin(init), r.id());
    }

    @PostMapping("/debts/close")
    public Map<String, Object> close(@RequestHeader(H) String init, @RequestBody IdReq r) {
        return svc.close(admin(init), r.id(), r.reason());
    }

    @PostMapping("/debts/excel")
    public Map<String, Object> debtsExcel(@RequestHeader(H) String init, @RequestBody IdReq r) {
        return svc.excelDebts(admin(init), r.kassaId());
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestHeader(H) String init, @RequestParam(defaultValue = "30") int days) {
        return svc.stats(admin(init), days);
    }

    @PostMapping("/refresh-all")
    public Map<String, Object> refreshAll(@RequestHeader(H) String init) {
        return svc.refreshAll(admin(init));
    }

    @GetMapping("/errors")
    public Map<String, Object> errors(@RequestHeader(H) String init) {
        return svc.errors(admin(init));
    }

    @GetMapping("/errors/{id}")
    public Map<String, Object> error(@RequestHeader(H) String init, @PathVariable long id,
                                     @RequestParam(defaultValue = "false") boolean recheck) {
        return svc.error(admin(init), id, recheck);
    }

    @PostMapping("/errors/ignore")
    public Map<String, Object> ignore(@RequestHeader(H) String init, @RequestBody IdReq r) {
        return svc.ignore(admin(init), r.id());
    }

    @PostMapping("/errors/excel")
    public Map<String, Object> errorsExcel(@RequestHeader(H) String init) {
        return svc.excelErrors(admin(init));
    }
}
