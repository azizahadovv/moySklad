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
 * ⚙️ Созламалар — 🏢 Ташкилот · 💼 Молия · 🔗 MoySklad · 📋 Аудит (faqat SuperAdmin).
 *   /api/admin/org/kassa …  /org/users …  /org/cards …  /org/click …
 *   /api/admin/moliya/owners · /init · /adjust · /ledger · /zero
 *   /api/admin/ms/token · /names · /diag · /reload
 *   /api/admin/audit?user=&limit=  · POST /audit/excel
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class WebOrgController {

    private final TelegramWebAppAuth auth;
    private final AdminOrgService org;
    private final AdminFinanceService fin;
    private final AdminMoySkladService ms;

    private AppUser sa(String init) {
        AppUser u = auth.authenticate(init);
        if (u == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Ruxsat yo'q");
        if (u.getRole() != Role.SUPERADMIN) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Фақат SuperAdmin");
        return u;
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> business(BusinessException e) { return Map.of("error", e.getMessage()); }

    private static final String H = "X-Telegram-Init-Data";

    /* ---------- 🏢 kassalar ---------- */
    public record OtdelReq(String groupId, Boolean move) {}

    @GetMapping("/org/kassa") public Map<String, Object> kassalar(@RequestHeader(H) String i) { sa(i); return org.kassalar(); }
    @PostMapping("/org/kassa") public Map<String, Object> createKassa(@RequestHeader(H) String i, @RequestBody AdminOrgService.KassaReq r) { return org.createKassa(sa(i), r); }
    @PutMapping("/org/kassa/{id}") public Map<String, Object> editKassa(@RequestHeader(H) String i, @PathVariable long id, @RequestBody AdminOrgService.KassaEditReq r) { org.editKassa(sa(i), id, r); return Map.of("ok", true); }
    @PutMapping("/org/kassa/{id}/otdel") public Map<String, Object> otdel(@RequestHeader(H) String i, @PathVariable long id, @RequestBody OtdelReq r) { return org.setOtdel(sa(i), id, r.groupId(), Boolean.TRUE.equals(r.move())); }
    @DeleteMapping("/org/kassa/{id}") public Map<String, Object> delKassa(@RequestHeader(H) String i, @PathVariable long id) { org.deactivateKassa(sa(i), id); return Map.of("ok", true); }

    /* ---------- 👥 xodimlar ---------- */
    @GetMapping("/org/users") public Map<String, Object> users(@RequestHeader(H) String i) { sa(i); return org.users(); }
    @PostMapping("/org/users") public Map<String, Object> createUser(@RequestHeader(H) String i, @RequestBody AdminOrgService.UserReq r) { return org.createUser(sa(i), r); }
    @PutMapping("/org/users/{id}/role") public Map<String, Object> role(@RequestHeader(H) String i, @PathVariable long id, @RequestBody AdminOrgService.RoleReq r) { org.changeRole(sa(i), id, r); return Map.of("ok", true); }
    @DeleteMapping("/org/users/{id}") public Map<String, Object> delUser(@RequestHeader(H) String i, @PathVariable long id) { org.deactivateUser(sa(i), id); return Map.of("ok", true); }

    /* ---------- 💳 karta mas'ullari ---------- */
    public record OwnerReq(long userId) {}
    @GetMapping("/org/cards") public Map<String, Object> cards(@RequestHeader(H) String i) { sa(i); return org.cardOwners(); }
    @PutMapping("/org/cards/{id}/owner") public Map<String, Object> cardOwner(@RequestHeader(H) String i, @PathVariable long id, @RequestBody OwnerReq r) { org.setCardOwner(sa(i), id, r.userId()); return Map.of("ok", true); }

    /* ---------- 📣 guruhlar + jadval ---------- */
    public record ChatReq(long chatId) {}
    @GetMapping("/org/click") public Map<String, Object> click(@RequestHeader(H) String i) { sa(i); return org.clickGroups(); }
    @PostMapping("/org/click/chats") public Map<String, Object> addChat(@RequestHeader(H) String i, @RequestBody ChatReq r) { return org.addClickGroup(sa(i), r.chatId()); }
    @DeleteMapping("/org/click/chats/{id}") public Map<String, Object> delChat(@RequestHeader(H) String i, @PathVariable long id) { org.removeClickGroup(sa(i), id); return Map.of("ok", true); }
    @PutMapping("/org/click/schedule") public Map<String, Object> schedule(@RequestHeader(H) String i, @RequestBody AdminOrgService.ScheduleReq r) { org.saveSchedule(sa(i), r); return org.clickGroups(); }
    @PostMapping("/org/click/test") public Map<String, Object> clickTest(@RequestHeader(H) String i) { sa(i); org.clickTestSend(); return Map.of("ok", true); }

    /* ---------- 💼 moliya ---------- */
    public record DateReq(String date) {}
    public record ArgReq(String arg) {}
    @GetMapping("/moliya/owners") public Object owners(@RequestHeader(H) String i) { sa(i); return fin.owners(); }
    @PostMapping("/moliya/init") public Map<String, Object> init(@RequestHeader(H) String i, @RequestBody AdminFinanceService.InitReq r) { return fin.initBalance(sa(i), r); }
    @PostMapping("/moliya/adjust") public Map<String, Object> adjust(@RequestHeader(H) String i, @RequestBody AdminFinanceService.AdjustReq r) { return fin.adjust(sa(i), r); }
    @GetMapping("/moliya/ledger") public Map<String, Object> ledger(@RequestHeader(H) String i) { sa(i); return fin.ledgerDate(); }
    @PutMapping("/moliya/ledger") public Map<String, Object> setLedger(@RequestHeader(H) String i, @RequestBody DateReq r) { return fin.setLedgerDate(sa(i), r.date()); }
    @GetMapping("/moliya/zero") public Map<String, Object> zeroPreview(@RequestHeader(H) String i, @RequestParam(defaultValue = "all") String arg) { sa(i); return fin.zeroPreview(arg); }
    @PostMapping("/moliya/zero") public Map<String, Object> zero(@RequestHeader(H) String i, @RequestBody ArgReq r) { return fin.zeroCommit(sa(i), r.arg()); }

    /* ---------- 🔗 MoySklad ---------- */
    public record TokenReq(String token) {}
    public record RenameReq(String key, String name) {}
    @GetMapping("/ms/token") public Map<String, Object> token(@RequestHeader(H) String i) { sa(i); return ms.token(); }
    @PutMapping("/ms/token") public Map<String, Object> setToken(@RequestHeader(H) String i, @RequestBody TokenReq r) { return ms.setToken(sa(i), r.token()); }
    @GetMapping("/ms/names") public Map<String, Object> names(@RequestHeader(H) String i) { sa(i); return ms.names(); }
    @PostMapping("/ms/names/apply") public Map<String, Object> applyNames(@RequestHeader(H) String i) { return Map.of("updated", ms.applyNames(sa(i))); }
    @PutMapping("/ms/names") public Map<String, Object> rename(@RequestHeader(H) String i, @RequestBody RenameReq r) { return ms.rename(sa(i), r.key(), r.name()); }
    @GetMapping("/ms/diag") public Map<String, Object> diag(@RequestHeader(H) String i) { sa(i); return ms.diag(); }
    @GetMapping("/ms/reload") public Map<String, Object> reloadInfo(@RequestHeader(H) String i) { sa(i); return ms.reloadInfo(); }
    @PostMapping("/ms/reload") public Map<String, Object> reload(@RequestHeader(H) String i) { ms.reload(sa(i)); return Map.of("ok", true); }

    /* ---------- 📋 audit ---------- */
    public record UserIdReq(long userId) {}
    @GetMapping("/audit") public Map<String, Object> audit(@RequestHeader(H) String i, @RequestParam(defaultValue = "0") long user, @RequestParam(defaultValue = "100") int limit) { sa(i); return ms.auditList(user, limit); }
    @PostMapping("/audit/excel") public Map<String, Object> auditExcel(@RequestHeader(H) String i, @RequestBody UserIdReq r) { ms.auditExcel(sa(i), r.userId()); return Map.of("ok", true); }
}
