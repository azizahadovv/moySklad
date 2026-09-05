package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Role;
import uz.kassa.service.BusinessException;

import java.util.List;
import java.util.Map;

/**
 * ⚙️ Созламалар REST API — faqat SuperAdmin.
 *   GET  /api/admin/settings/menu · PUT /menu/{key} {order,cols} · POST /menu/{key}/reset
 *   GET  /api/admin/settings/labels · PUT /labels {canonical,name,hidden}
 *   GET  /api/admin/settings/perm · GET /perm/{subj}/{id} · PUT /perm {subj,id,canonical,state}
 *   GET  /api/admin/notify · GET/PUT/DELETE /notify/{id} · POST /notify · POST /notify/{id}/send?test=
 *   POST /api/admin/notify/preview {template} · GET /notify/presets · POST /notify/presets/{key} · GET /notify/help
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class WebSettingsController {

    private final TelegramWebAppAuth auth;
    private final AdminSettingsService svc;

    private AppUser superadmin(String initData) {
        AppUser u = auth.authenticate(initData);
        if (u == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Ruxsat yo'q");
        if (u.getRole() != Role.SUPERADMIN)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Созламалар фақат SuperAdmin учун");
        return u;
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> business(BusinessException e) { return Map.of("error", e.getMessage()); }

    /* ---------- 🧩 menyu ---------- */
    public record MenuReq(List<String> order, Integer cols) {}

    @GetMapping("/settings/menu")
    public List<Map<String, Object>> menus(@RequestHeader("X-Telegram-Init-Data") String init) {
        superadmin(init); return svc.menus();
    }

    @PutMapping("/settings/menu/{key}")
    public List<Map<String, Object>> saveMenu(@RequestHeader("X-Telegram-Init-Data") String init,
                                              @PathVariable String key, @RequestBody MenuReq r) {
        svc.saveMenu(superadmin(init), key, r.order(), r.cols()); return svc.menus();
    }

    public record SchemaReq(Map<String, List<String>> menus) {}

    /** To'liq sxema (drag-and-drop muharriri): barcha menyular bir yo'la. */
    @PutMapping("/settings/schema")
    public List<Map<String, Object>> saveSchema(@RequestHeader("X-Telegram-Init-Data") String init, @RequestBody SchemaReq r) {
        svc.saveSchema(superadmin(init), r.menus()); return svc.menus();
    }

    @PostMapping("/settings/menu/{key}/reset")
    public List<Map<String, Object>> resetMenu(@RequestHeader("X-Telegram-Init-Data") String init,
                                               @PathVariable String key) {
        svc.resetMenu(superadmin(init), key); return svc.menus();
    }

    /* ---------- 🏷 nomlar ---------- */
    public record LabelReq(String canonical, String name, Boolean hidden) {}

    @GetMapping("/settings/labels")
    public List<Map<String, Object>> labels(@RequestHeader("X-Telegram-Init-Data") String init) {
        superadmin(init); return svc.labels();
    }

    @PutMapping("/settings/labels")
    public List<Map<String, Object>> saveLabel(@RequestHeader("X-Telegram-Init-Data") String init,
                                               @RequestBody LabelReq r) {
        svc.saveLabel(superadmin(init), r.canonical(), r.name(), r.hidden()); return svc.labels();
    }

    /* ---------- 👁 huquqlar ---------- */
    public record PermReq(String subj, long id, String canonical, Boolean state) {}

    public record RolePermReq(String role, String canonical, Boolean state) {}

    @PutMapping("/settings/perm/role")
    public List<Map<String, Object>> setRolePerm(@RequestHeader("X-Telegram-Init-Data") String init, @RequestBody RolePermReq r) {
        svc.setRolePerm(superadmin(init), r.role(), r.canonical(), r.state()); return svc.menus();
    }

    @GetMapping("/settings/perm")
    public Map<String, Object> perms(@RequestHeader("X-Telegram-Init-Data") String init) {
        superadmin(init); return svc.perms();
    }

    @GetMapping("/settings/perm/{subj}/{id}")
    public Map<String, Object> permOf(@RequestHeader("X-Telegram-Init-Data") String init,
                                      @PathVariable String subj, @PathVariable long id) {
        superadmin(init); return svc.permOf(subj, id);
    }

    @PutMapping("/settings/perm")
    public Map<String, Object> setPerm(@RequestHeader("X-Telegram-Init-Data") String init, @RequestBody PermReq r) {
        svc.setPerm(superadmin(init), r.subj(), r.id(), r.canonical(), r.state());
        return svc.permOf(r.subj(), r.id());
    }

    /* ---------- 🔔 shablonlar ---------- */
    public record PreviewReq(String template) {}
    public record MinReq(int min) {}

    @GetMapping("/notify")
    public List<Map<String, Object>> notifies(@RequestHeader("X-Telegram-Init-Data") String init) {
        superadmin(init); return svc.notifies();
    }

    @GetMapping("/notify/help")
    public Map<String, Object> help(@RequestHeader("X-Telegram-Init-Data") String init) {
        superadmin(init); return svc.help();
    }

    @PutMapping("/notify/confirm-delete")
    public Map<String, Object> confirmDelete(@RequestHeader("X-Telegram-Init-Data") String init, @RequestBody MinReq r) {
        svc.setConfirmDeleteMin(superadmin(init), r.min()); return svc.help();
    }

    @GetMapping("/notify/presets")
    public List<Map<String, Object>> presets(@RequestHeader("X-Telegram-Init-Data") String init) {
        superadmin(init); return svc.presets();
    }

    @PostMapping("/notify/presets/{key}")
    public Map<String, Object> fromPreset(@RequestHeader("X-Telegram-Init-Data") String init, @PathVariable String key) {
        return svc.createFromPreset(superadmin(init), key);
    }

    @PostMapping("/notify/preview")
    public Map<String, Object> preview(@RequestHeader("X-Telegram-Init-Data") String init, @RequestBody PreviewReq r) {
        return svc.preview(superadmin(init), r.template());
    }

    @GetMapping("/notify/{id}")
    public Map<String, Object> notify(@RequestHeader("X-Telegram-Init-Data") String init, @PathVariable long id) {
        superadmin(init); return svc.notify(id);
    }

    @PostMapping("/notify")
    public Map<String, Object> create(@RequestHeader("X-Telegram-Init-Data") String init,
                                      @RequestBody AdminSettingsService.NotifyReq r) {
        return svc.saveNotify(superadmin(init), null, r);
    }

    @PutMapping("/notify/{id}")
    public Map<String, Object> update(@RequestHeader("X-Telegram-Init-Data") String init, @PathVariable long id,
                                      @RequestBody AdminSettingsService.NotifyReq r) {
        return svc.saveNotify(superadmin(init), id, r);
    }

    @DeleteMapping("/notify/{id}")
    public Map<String, Object> delete(@RequestHeader("X-Telegram-Init-Data") String init, @PathVariable long id) {
        svc.deleteNotify(superadmin(init), id); return Map.of("ok", true);
    }

    @PostMapping("/notify/{id}/send")
    public Map<String, Object> send(@RequestHeader("X-Telegram-Init-Data") String init, @PathVariable long id,
                                    @RequestParam(defaultValue = "false") boolean test) {
        return Map.of("result", svc.sendNotify(superadmin(init), id, test));
    }
}
