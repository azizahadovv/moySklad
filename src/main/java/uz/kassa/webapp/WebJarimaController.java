package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Role;
import uz.kassa.service.BusinessException;
import java.util.Map;

/** 🌐 Админ панел → Ҳисоботлар → ⚖️ Жарималар (/api/admin/jarima/*). Ko'rish — bux + admin, yopish/bekor — SuperAdmin. */
@RestController
@RequestMapping("/api/admin/jarima")
@RequiredArgsConstructor
public class WebJarimaController {

    private static final String H = "X-Telegram-Init-Data";

    private final TelegramWebAppAuth auth;
    private final AdminJarimaService svc;

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

    public record Req(long id, String reason, String holat, String tur, Long user, Long kassa, String from, String to) {}

    @GetMapping
    public Map<String, Object> list(@RequestHeader(name = H, required = false) String init,
                                    @RequestParam(defaultValue = "") String holat,
                                    @RequestParam(defaultValue = "") String tur,
                                    @RequestParam(defaultValue = "0") long user,
                                    @RequestParam(defaultValue = "0") long kassa,
                                    @RequestParam(defaultValue = "") String from,
                                    @RequestParam(defaultValue = "") String to,
                                    @RequestParam(defaultValue = "0") int page) {
        return svc.list(admin(init), holat, tur, user, kassa, from, to, page);
    }

    @GetMapping("/{id}")
    public Map<String, Object> one(@RequestHeader(name = H, required = false) String init, @PathVariable long id) {
        return svc.one(admin(init), id);
    }

    @PostMapping("/close")
    public Map<String, Object> close(@RequestHeader(name = H, required = false) String init, @RequestBody Req r) {
        return svc.close(admin(init), r.id(), r.reason(), false);
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel(@RequestHeader(name = H, required = false) String init, @RequestBody Req r) {
        return svc.close(admin(init), r.id(), r.reason(), true);
    }

    @PostMapping("/excel")
    public Map<String, Object> excel(@RequestHeader(name = H, required = false) String init, @RequestBody Req r) {
        return svc.excel(admin(init), r.holat(), r.tur(), r.user() == null ? 0 : r.user(), r.kassa() == null ? 0 : r.kassa(), r.from(), r.to());
    }
}
