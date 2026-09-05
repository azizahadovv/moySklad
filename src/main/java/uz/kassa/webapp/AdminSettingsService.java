package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uz.kassa.bot.*;
import uz.kassa.domain.*;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.service.AuditService;
import uz.kassa.service.BusinessException;
import uz.kassa.service.SettingsService;
import uz.kassa.service.notify.NotifyPresets;
import uz.kassa.service.notify.NotifyService;
import uz.kassa.service.notify.TemplateService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static uz.kassa.webapp.AdminApiService.mapOf;

/**
 * ⚙️ Созламалар (Mini App, faqat SuperAdmin) — bot admin paneli bilan BIR manba:
 * 🧩 menyu tartibi (MenuSchemaService), 🏷 nomlar/yashirish (LabelService),
 * 👁 huquqlar (PermService), 🔔 shablonlar (NotifyService). Bu yerda yangi
 * biznes-qoida yo'q: har amal botdagi handler chaqiradigan servis metodini chaqiradi.
 */
@Service
@RequiredArgsConstructor
public class AdminSettingsService {

    private final MenuSchemaService schema;
    private final LabelService labelSvc;
    private final PermService permSvc;
    private final NotifyService notifySvc;
    private final TemplateService templates;
    private final AppUserRepo userRepo;
    private final KassaRepo kassaRepo;
    private final SettingsService settings;
    private final AuditService audit;

    /* ================= 🧩 Menyu tartibi + 🏷 nomlar ================= */

    /** Bot menyu sxemasi: har menyu, tugmalari (amal yoki ostmenyu), erkin/kontekstli, ota menyu. */
    public List<Map<String, Object>> menus() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MenuSchemaService.MenuDef d : MenuSchemaService.MENUS.values()) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (String c : schema.current(d.key())) items.add(labelInfo(c));
            out.add(mapOf("key", d.key(), "title", d.title(), "cols", schema.cols(d.key()),
                    "free", d.free(), "parent", d.free() ? schema.parentOf(d.key()) : null,
                    "customized", schema.customized(d.key()), "items", items));
        }
        return out;
    }

    private Map<String, Object> labelInfo(String c) {
        boolean renamable = LabelService.RENAMABLE.contains(c);
        String sub = MenuSchemaService.SUBMENUS.get(c);
        return mapOf("canonical", c, "display", labelSvc.display(c),
                "renamed", labelSvc.isRenamed(c), "hidden", labelSvc.isHidden(c),
                "renamable", renamable, "protected", c.equals(LabelService.PROTECTED_LABEL),
                "submenu", sub, "pinned", MenuSchemaService.PINNED.contains(c),
                "saOnly", MenuSchemaService.SA_ONLY.contains(c),
                "roles", mapOf("KASSIR", permSvc.roleOverride(Role.KASSIR, c), "BUXGALTER", permSvc.roleOverride(Role.BUXGALTER, c)),
                "userExceptions", permSvc.userExceptions(c));
    }

    /** Rol darajasida ruxsat/taqiq (web sxema): faqat RENAMABLE (huquq boshqariladigan) tugmalar. */
    public void setRolePerm(AppUser by, String role, String canonical, Boolean state) {
        Role r;
        try { r = Role.valueOf(role); } catch (Exception e) { throw new BusinessException("Рол нотўғри"); }
        if (r == Role.SUPERADMIN) throw new BusinessException("SuperAdmin ҳамма нарсани кўради");
        if (!LabelService.RENAMABLE.contains(canonical)) throw new BusinessException("Бу тугма учун ҳуқуқ бошқарилмайди");
        if (MenuSchemaService.SA_ONLY.contains(canonical) && Boolean.TRUE.equals(state))
            throw new BusinessException("Бу бўлим фақат SuperAdmin учун");
        permSvc.setRole(r, canonical, state);
        audit.log(by.getId(), "HUQUQ", "role", null, r + ": " + canonical + "=" + (state == null ? "meros" : state ? "ruxsat" : "taqiq") + " (web)");
    }

    /** To'liq sxemani saqlash (web muharriri, drag-and-drop): kalit → tugmalar ro'yxati. */
    public void saveSchema(AppUser by, Map<String, List<String>> menus) {
        if (menus == null || menus.isEmpty()) throw new BusinessException("Схема бўш");
        schema.saveAll(menus);
        audit.log(by.getId(), "MENYU_SXEMA", "menu", null, by.getFullName() + " (web) to'liq sxemani saqladi: " + menus.keySet());
    }

    /** Sudrab-tashlab tartib: to'liq ro'yxat + ustunlar. */
    public void saveMenu(AppUser by, String key, List<String> order, Integer cols) {
        MenuSchemaService.MenuDef d = MenuSchemaService.MENUS.get(key);
        if (d == null) throw new BusinessException("Меню топилмади: " + key);
        if (order != null) {
            List<String> clean = new ArrayList<>();
            for (String o : order) if (d.defaults().contains(o) && !clean.contains(o)) clean.add(o);
            for (String o : d.defaults()) if (!clean.contains(o)) clean.add(o);
            settings.set("menu.order." + key, clean.equals(d.defaults()) ? "" : String.join("\n", clean));
        }
        if (cols != null) settings.set("menu.cols." + key, cols == 2 ? "" : String.valueOf(Math.max(1, Math.min(3, cols))));
        schema.reload();
        audit.log(by.getId(), "MENYU_SXEMA", "menu", null, key + ": web tartib" + (cols == null ? "" : " ustun=" + cols));
    }

    public void resetMenu(AppUser by, String key) {
        schema.reset(key);
        audit.log(by.getId(), "MENYU_SXEMA", "menu", null, key + ": asl holat (web)");
    }

    public List<Map<String, Object>> labels() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String c : LabelService.RENAMABLE) out.add(labelInfo(c));
        return out;
    }

    public void saveLabel(AppUser by, String canonical, String name, Boolean hidden) {
        if (!LabelService.RENAMABLE.contains(canonical)) throw new BusinessException("Тугма топилмади");
        if (name != null) {
            String n = name.trim();
            if (!n.isEmpty()) {
                if (n.length() < 2 || n.length() > 30) throw new BusinessException("Ном 2–30 белги бўлсин");
                if (n.chars().allMatch(Character::isDigit)) throw new BusinessException("Фақат рақамдан иборат ном бўлмайди");
                if (labelSvc.clashes(canonical, n)) throw new BusinessException("Бу ном бошқа тугма билан бир хил");
            }
            labelSvc.rename(canonical, n);
            audit.log(by.getId(), "TUGMA_NOMI", "label", null, canonical + " → " + (n.isEmpty() ? "(асл)" : n) + " (web)");
        }
        if (hidden != null) {
            if (canonical.equals(LabelService.PROTECTED_LABEL) && hidden) throw new BusinessException("Бу бўлимни яшириб бўлмайди");
            labelSvc.setHidden(canonical, hidden);
            audit.log(by.getId(), hidden ? "BOLIM_OCHIRILDI" : "BOLIM_YOQILDI", "label", null, canonical + " (web)");
        }
    }

    /* ================= 👁 Huquqlar ================= */

    public Map<String, Object> perms() {
        List<Map<String, Object>> users = new ArrayList<>();
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc()) {
            if (x.getRole() == Role.SUPERADMIN) continue;
            users.add(mapOf("id", x.getId(), "name", x.getFullName(), "role", x.getRole().name(),
                    "kassaId", x.getKassaId() == null ? 0 : x.getKassaId(),
                    "configured", permSvc.userConfigured(x.getId())));
        }
        List<Map<String, Object>> kassas = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            kassas.add(mapOf("id", k.getId(), "name", k.getName(), "configured", permSvc.kassaConfigured(k.getId())));
        return mapOf("users", users, "kassas", kassas, "sections", labels());
    }

    /** subj: user|kassa. Har bo'lim: null (meros) / true / false + natijaviy ko'rinish. */
    public Map<String, Object> permOf(String subj, long id) {
        List<Map<String, Object>> rows = new ArrayList<>();
        AppUser u = subj.equals("user") ? userRepo.findById(id).orElse(null) : null;
        for (String c : LabelService.RENAMABLE) {
            Boolean o = subj.equals("user") ? permSvc.userOverride(id, c) : permSvc.kassaOverride(id, c);
            rows.add(mapOf("canonical", c, "display", labelSvc.display(c), "override", o,
                    "effective", u == null ? !labelSvc.isHidden(c) : permSvc.visible(u, c)));
        }
        boolean configured = subj.equals("user") ? permSvc.userConfigured(id) : permSvc.kassaConfigured(id);
        return mapOf("subj", subj, "id", id, "configured", configured, "rows", rows);
    }

    public void setPerm(AppUser by, String subj, long id, String canonical, Boolean state) {
        if (!subj.equals("user") && !subj.equals("kassa")) throw new BusinessException("subj: user|kassa");
        if (!LabelService.RENAMABLE.contains(canonical)) throw new BusinessException("Бўлим топилмади");
        permSvc.set(subj, id, canonical, state);
        audit.log(by.getId(), "HUQUQ", subj, id, canonical + "=" + (state == null ? "meros" : state ? "ruxsat" : "taqiq") + " (web)");
    }

    /* ================= 🔔 Shablonlar ================= */

    public List<Map<String, Object>> notifies() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Notify n : notifySvc.all()) out.add(notifyBrief(n));
        return out;
    }

    private Map<String, Object> notifyBrief(Notify n) {
        return mapOf("id", n.getId(), "name", n.getName(), "active", n.isActive(),
                "schedule", n.getSchedule(), "scheduleText", notifySvc.describeSchedule(n),
                "recipientsText", notifySvc.describeRecipients(n),
                "buttonLabel", n.getButtonLabel(), "lastSent", n.getLastSent(), "lastError", n.getLastError());
    }

    public Map<String, Object> notify(long id) {
        Notify n = notifySvc.find(id).orElseThrow(() -> new BusinessException("Шаблон топилмади"));
        Map<String, Object> m = notifyBrief(n);
        m.put("template", n.getTemplate());
        m.put("weekdays", n.getWeekdays());
        m.put("recipients", n.getRecipients());
        m.put("autoDeleteMin", n.getAutoDeleteMin());
        m.put("buttonRoles", n.getButtonRoles());
        m.put("next", n.isActive() ? String.valueOf(notifySvc.nextRun(n)) : "");
        return m;
    }

    public record NotifyReq(String name, String template, String schedule, String weekdays, String recipients,
                            Integer autoDeleteMin, Boolean active, String buttonLabel, String buttonRoles) {}

    public Map<String, Object> saveNotify(AppUser by, Long id, NotifyReq r) {
        Notify n = id == null ? Notify.builder().active(false).build()
                : notifySvc.find(id).orElseThrow(() -> new BusinessException("Шаблон топилмади"));
        if (r.name() != null) {
            String nm = r.name().trim();
            if (nm.isEmpty() || nm.length() > 80) throw new BusinessException("Ном 1–80 белги");
            n.setName(nm);
        }
        if (r.template() != null) {
            if (r.template().length() > 3800) throw new BusinessException("Шаблон 3800 белгигача");
            n.setTemplate(r.template());
        }
        if (r.schedule() != null) n.setSchedule(NotifyService.parseScheduleText(r.schedule()));
        if (r.weekdays() != null) n.setWeekdays(NotifyService.parseWeekdaysText(r.weekdays()));
        if (r.recipients() != null) n.setRecipients(NotifyService.parseRecipientsText(r.recipients()));
        if (r.autoDeleteMin() != null) n.setAutoDeleteMin(Math.max(0, Math.min(1440, r.autoDeleteMin())));
        if (r.buttonLabel() != null) {
            String bl = r.buttonLabel().trim();
            if (!bl.isEmpty()) {
                String problem = NotifyService.buttonLabelProblem(bl);
                if (problem != null) throw new BusinessException(problem);
            }
            n.setButtonLabel(bl);
        }
        if (r.buttonRoles() != null) n.setButtonRoles(NotifyService.parseButtonRolesText(r.buttonRoles()));
        if (r.active() != null) n.setActive(r.active());
        if (n.getName() == null || n.getName().isBlank()) n.setName("Билдиришнома");
        n = notifySvc.save(n);
        audit.log(by.getId(), id == null ? "NOTIFY_YARATILDI" : "NOTIFY_OZGARDI", "notify", n.getId(),
                by.getFullName() + " (web): " + n.getName());
        Map<String, Object> m = notify(n.getId());
        m.put("unknown", templates.unknownPlaceholders(n.getTemplate()));
        return m;
    }

    public void deleteNotify(AppUser by, long id) {
        Notify n = notifySvc.find(id).orElseThrow(() -> new BusinessException("Шаблон топилмади"));
        notifySvc.delete(id);
        audit.log(by.getId(), "NOTIFY_OCHIRILDI", "notify", id, by.getFullName() + " (web): " + n.getName());
    }

    /** Jonli ko'rinish (admin kontekstida); saqlanmagan matn ham bo'lishi mumkin. */
    public Map<String, Object> preview(AppUser by, String template) {
        TemplateService.Result r = templates.render(template == null ? "" : template,
                new TemplateService.Ctx(by.getTelegramId(), by.getKassaId()));
        return mapOf("text", r.text(), "unknown", r.unknown(), "msFailed", r.msFailed());
    }

    public String sendNotify(AppUser by, long id, boolean test) {
        Notify n = notifySvc.find(id).orElseThrow(() -> new BusinessException("Шаблон топилмади"));
        if (test) {
            if (by.getTelegramId() == null) throw new BusinessException("Telegram уланмаган");
            TemplateService.Result r = notifySvc.preview(n, by.getTelegramId());
            return r.text();
        }
        return notifySvc.send(n);
    }

    public List<Map<String, Object>> presets() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (NotifyPresets.Preset p : NotifyPresets.ALL)
            out.add(mapOf("key", p.key(), "title", p.title(), "about", p.about(), "schedule", p.schedule(),
                    "recipients", p.recipients(), "template", p.template()));
        return out;
    }

    public Map<String, Object> createFromPreset(AppUser by, String key) {
        NotifyPresets.Preset p = NotifyPresets.byKey(key);
        if (p == null) throw new BusinessException("Намуна топилмади");
        Notify n = notifySvc.save(p.toNotify());
        audit.log(by.getId(), "NOTIFY_YARATILDI", "notify", n.getId(), by.getFullName() + " (web) намуна: " + p.title());
        return notify(n.getId());
    }

    public Map<String, Object> help() {
        return mapOf("pages", List.of(TemplateService.HELP_1, TemplateService.HELP_2, TemplateService.HELP_3),
                "confirmDeleteMin", notifySvc.confirmDeleteMin());
    }

    public void setConfirmDeleteMin(AppUser by, int min) {
        settings.set(NotifyService.CONFIRM_DELETE_KEY, String.valueOf(Math.max(0, Math.min(1440, min))));
        audit.log(by.getId(), "NOTIFY_SOZLAMA", "settings", null, "confirmDeleteMin=" + min + " (web)");
    }
}
