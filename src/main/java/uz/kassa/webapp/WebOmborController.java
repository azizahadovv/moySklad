package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Role;
import uz.kassa.service.BusinessException;
import java.util.Map;

/** 🌐 Админ панел → 🏬 Омбор (/api/admin/ombor/*). */
@RestController
@RequestMapping("/api/admin/ombor")
@RequiredArgsConstructor
public class WebOmborController {

    private static final String H = "X-Telegram-Init-Data";

    private final TelegramWebAppAuth auth;
    private final AdminOmborService svc;

    private AppUser admin(String initData) {
        AppUser u = auth.authenticate(initData);
        if (u == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Ruxsat yo'q");
        if (u.getRole() == Role.KASSIR) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Админ панел фақат бухгалтер ва админ учун");
        return u;
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> business(BusinessException e) { return Map.of("error", e.getMessage()); }

    public record IdReq(long id, String answer, String reason, String status, String note, Long kassaId, String rule) {}

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestHeader(H) String init) { admin(init); return svc.dashboard(); }

    @GetMapping("/issues")
    public Map<String, Object> issues(@RequestHeader(H) String init, @RequestParam(defaultValue = "0") long kassa,
                                      @RequestParam(defaultValue = "") String rule, @RequestParam(defaultValue = "0") int page) {
        return svc.issues(admin(init), kassa, rule, page);
    }

    @GetMapping("/issues/{id}")
    public Map<String, Object> issue(@RequestHeader(H) String init, @PathVariable long id) { return svc.issue(admin(init), id); }

    @PostMapping("/issues/resolve")
    public Map<String, Object> resolve(@RequestHeader(H) String init, @RequestBody IdReq r) { return svc.resolve(admin(init), r.id(), r.answer()); }

    @PostMapping("/issues/excel")
    public Map<String, Object> issuesExcel(@RequestHeader(H) String init, @RequestBody IdReq r) { return svc.issuesExcel(admin(init), r.kassaId(), r.rule()); }

    @GetMapping("/drafts")
    public Map<String, Object> drafts(@RequestHeader(H) String init, @RequestParam(defaultValue = "false") boolean all) { return svc.drafts(admin(init), all); }

    @GetMapping("/drafts/{id}")
    public Map<String, Object> draft(@RequestHeader(H) String init, @PathVariable long id) { admin(init); return svc.draftCard(id); }

    @PostMapping("/drafts/advance")
    public Map<String, Object> draftAdvance(@RequestHeader(H) String init, @RequestBody IdReq r) { return svc.draftAdvance(admin(init), r.id()); }

    @PostMapping("/drafts/cancel")
    public Map<String, Object> draftCancel(@RequestHeader(H) String init, @RequestBody IdReq r) { return svc.draftCancel(admin(init), r.id(), r.reason()); }

    @GetMapping("/sanoq")
    public Map<String, Object> sanoq(@RequestHeader(H) String init) { admin(init); return svc.sanoqSummary(); }

    @GetMapping("/sorov")
    public Map<String, Object> sorov(@RequestHeader(H) String init) { admin(init); return svc.sorovlar(); }

    @PostMapping("/sorov/answer")
    public Map<String, Object> sorovAnswer(@RequestHeader(H) String init, @RequestBody IdReq r) { return svc.sorovAnswer(admin(init), r.id(), r.status(), r.note()); }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestHeader(H) String init) { return svc.refresh(admin(init)); }
}
