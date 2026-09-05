package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uz.kassa.bot.NameService;
import uz.kassa.bot.Sender;
import uz.kassa.bot.TextUtil;
import uz.kassa.config.AppProps;
import uz.kassa.domain.*;
import uz.kassa.repo.*;
import uz.kassa.scheduler.Jobs;
import uz.kassa.service.*;
import uz.kassa.service.moysklad.MoySkladClient;

import java.util.*;

import static uz.kassa.webapp.AdminApiService.mapOf;

/**
 * 🏢 Ташкилот (Mini App, faqat SuperAdmin): kassalar, otdel bog'lash, xodimlar,
 * karta mas'ullari, Click guruh/kanallari va jadvali. Qoidalar botdagi handler'lar
 * (KassaAdminHandler, UsersAdminHandler, SettingsAdminHandler) bilan bir xil.
 */
@Service
@RequiredArgsConstructor
public class AdminOrgService {

    private final KassaRepo kassaRepo;
    private final AppUserRepo userRepo;
    private final GuestRepo guestRepo;
    private final ClickAccountRepo clickRepo;
    private final DayRepo dayRepo;
    private final LedgerService ledger;
    private final MoySkladClient msClient;
    private final NameService names;
    private final NotificationService notify;
    private final AuditService audit;
    private final Sender sender;
    private final Jobs jobs;
    private final SettingsService settings;
    private final AppProps props;
    private final DailyReportService dailyReport;

    /* ================= 🏪 Kassalar ================= */

    public Map<String, Object> kassalar() {
        Map<String, String> groups = safeGroups();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            String g = k.getMoyskladGroupId();
            long n = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.NAQD).getAmount();
            long kl = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.KLIK).getAmount();
            out.add(mapOf("id", k.getId(), "name", k.getName(), "label", nz(k.getShopLabel()),
                    "cashless", k.isCashless(), "nameLocked", k.isNameLocked(),
                    "storeId", nz(k.getMoyskladStoreId()), "groupId", nz(g),
                    "groupName", g == null || g.isBlank() ? "" : groups.getOrDefault(g, g),
                    "naqd", n, "klik", kl, "canDeactivate", deactivateBlock(k.getId()) == null,
                    "xodimlar", userRepo.findByKassaIdAndActiveTrue(k.getId()).size()));
        }
        List<Map<String, Object>> gl = new ArrayList<>();
        for (var e : groups.entrySet()) {
            List<String> holders = kassaRepo.findByActiveTrueOrderByIdAsc().stream()
                    .filter(k -> e.getKey().equals(k.getMoyskladGroupId())).map(Kassa::getName).toList();
            gl.add(mapOf("id", e.getKey(), "name", e.getValue(), "holders", holders));
        }
        return mapOf("kassalar", out, "groups", gl, "msOk", !groups.isEmpty());
    }

    private Map<String, String> safeGroups() {
        try { return msClient.fetchGroups(); } catch (Exception e) { return Map.of(); }
    }

    public record KassaReq(String name, String storeId, String groupId) {}

    public Map<String, Object> createKassa(AppUser by, KassaReq r) {
        String name = r.name() == null ? "" : r.name().trim();
        if (name.length() < 2 || name.length() > 40) throw new BusinessException("Ном 2–40 белги");
        if (kassaRepo.findByActiveTrueOrderByIdAsc().stream().anyMatch(k -> k.getName().equalsIgnoreCase(name)))
            throw new BusinessException("Бу номли касса бор");
        String gid = r.groupId() == null || r.groupId().isBlank() ? null : r.groupId().trim();
        if (gid != null) {
            List<Kassa> holders = holders(gid, null);
            if (!holders.isEmpty()) throw new BusinessException("Бу отдел «" + holders.get(0).getName()
                    + "» кассасига боғланган — аввал уни кўчиринг");
        }
        Kassa k = kassaRepo.save(Kassa.builder().name(name)
                .moyskladStoreId(r.storeId() == null || r.storeId().isBlank() ? null : r.storeId().trim())
                .moyskladGroupId(gid).active(true).build());
        audit.log(by.getId(), "KASSA_QOSHILDI", "kassa", k.getId(), k.getName() + " (web)");
        return mapOf("id", k.getId(), "name", k.getName());
    }

    private List<Kassa> holders(String groupId, Long except) {
        return kassaRepo.findByActiveTrueOrderByIdAsc().stream()
                .filter(k -> groupId.equals(k.getMoyskladGroupId()) && (except == null || !except.equals(k.getId())))
                .toList();
    }

    /** Otdel biriktirish (move=true — boshqa kassadan ko'chirish), groupId bo'sh — olib tashlash. */
    public Map<String, Object> setOtdel(AppUser by, long kassaId, String groupId, boolean move) {
        Kassa k = kassaRepo.findById(kassaId).orElseThrow(() -> new BusinessException("Касса топилмади"));
        if (groupId == null || groupId.isBlank()) {
            String old = k.getMoyskladGroupId();
            k.setMoyskladGroupId(null);
            kassaRepo.save(k);
            audit.log(by.getId(), "OTDEL_OLIB_TASHLANDI", "kassa", k.getId(),
                    by.getFullName() + " «" + k.getName() + "» kassasidan otdelni oldi (edi: " + old + ") (web)");
            return mapOf("ok", true, "warning", "Энди бу кассага MoySklad'дан автоматик ҳеч нарса тушмайди — отдел ҳужжатлари Бухгалтерияга ёзилади.");
        }
        String gName = safeGroups().getOrDefault(groupId, groupId);
        List<Kassa> holders = holders(groupId, kassaId);
        if (!holders.isEmpty() && !move)
            return mapOf("ok", false, "needMove", true, "holders", holders.stream().map(Kassa::getName).toList(),
                    "groupName", gName);
        for (Kassa o : holders) {
            o.setMoyskladGroupId(null);
            kassaRepo.save(o);
            audit.log(by.getId(), "OTDEL_OLIB_TASHLANDI", "kassa", o.getId(),
                    by.getFullName() + " «" + gName + "» otdelini «" + o.getName() + "» kassasidan oldi (ko'chirish, web)");
        }
        k.setMoyskladGroupId(groupId);
        kassaRepo.save(k);
        audit.log(by.getId(), "OTDEL_BIRIKTIRILDI", "kassa", k.getId(),
                by.getFullName() + " «" + gName + "» otdelini «" + k.getName() + "» kassasiga biriktirdi (web)");
        return mapOf("ok", true, "groupName", gName);
    }

    public record KassaEditReq(String label, Boolean cashless) {}

    public void editKassa(AppUser by, long kassaId, KassaEditReq r) {
        Kassa k = kassaRepo.findById(kassaId).orElseThrow(() -> new BusinessException("Касса топилмади"));
        if (r.label() != null) k.setShopLabel(r.label().trim().isEmpty() ? null : r.label().trim());
        if (r.cashless() != null) k.setCashless(r.cashless());
        kassaRepo.save(k);
        audit.log(by.getId(), "KASSA_TAHRIR", "kassa", k.getId(), by.getFullName() + " (web): label=" + nz(k.getShopLabel())
                + " cashless=" + k.isCashless());
    }

    public String deactivateBlock(long id) {
        long n = ledger.view(OwnerType.KASSA, id, MoneyType.NAQD).getAmount();
        long kl = ledger.view(OwnerType.KASSA, id, MoneyType.KLIK).getAmount();
        long rem = dayRepo.findByKassaIdAndStatusInOrderByDateAsc(id, List.of(DayStatus.OCHIQ, DayStatus.YOPILGAN))
                .stream().mapToLong(d -> d.remainNaqd() + d.remainKlik()).sum();
        if (n == 0 && kl == 0 && rem == 0) return null;
        return "Кассада пул бор — ўчириб бўлмайди: нақд " + TextUtil.fmt(n) + " · click " + TextUtil.fmt(kl)
                + (rem == 0 ? "" : " · топширилмаган қолдиқ " + TextUtil.fmt(rem))
                + ". Аввал қолдиқни 0 қилинг (ҳисобот қабул, пул қабул, расход ёки корректировка).";
    }

    public void deactivateKassa(AppUser by, long id) {
        String block = deactivateBlock(id);
        if (block != null) throw new BusinessException(block);
        Kassa k = kassaRepo.findById(id).orElseThrow(() -> new BusinessException("Касса топилмади"));
        k.setActive(false);
        kassaRepo.save(k);
        audit.log(by.getId(), "KASSA_OCHIRILDI", "kassa", id, by.getFullName() + " (web): " + k.getName());
    }

    /* ================= 👥 Xodimlar ================= */

    public Map<String, Object> users() {
        List<Map<String, Object>> out = new ArrayList<>();
        Long creator = props.getSuperadmin().getTelegramId();
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc())
            out.add(mapOf("id", x.getId(), "name", x.getFullName(), "role", x.getRole().name(),
                    "tgId", x.getTelegramId() == null ? 0L : x.getTelegramId(), "phone", nz(x.getPhone()),
                    "kassaId", x.getKassaId() == null ? 0 : x.getKassaId(),
                    "kassa", x.getKassaId() == null ? "" : names.owner(OwnerType.KASSA, x.getKassaId()),
                    "creator", creator != null && creator.equals(x.getTelegramId())));
        List<Map<String, Object>> guests = new ArrayList<>();
        for (Guest g : guestRepo.findAllByOrderByLastSeenDesc()) {
            if (userRepo.findByTelegramId(g.getTelegramId()).isPresent()) continue;
            guests.add(mapOf("tgId", g.getTelegramId(), "name", nz(g.getName()), "username", nz(g.getUsername()),
                    "phone", nz(g.getPhone())));
            if (guests.size() >= 20) break;
        }
        List<Map<String, Object>> emps = new ArrayList<>();
        try {
            List<AppUser> all = userRepo.findAll();
            for (MoySkladClient.MsEmployee e : msClient.fetchEmployees()) {
                if (all.stream().anyMatch(x -> x.getFullName().equalsIgnoreCase(e.name()))) continue;
                emps.add(mapOf("name", e.name(), "phone", nz(e.phone())));
                if (emps.size() >= 30) break;
            }
        } catch (Exception ignored) { /* MoySklad o'qilmasa — ro'yxat bo'sh */ }
        return mapOf("users", out, "guests", guests, "employees", emps);
    }

    public record UserReq(Long tgId, String name, String phone, String role, Long kassaId) {}

    public Map<String, Object> createUser(AppUser by, UserReq r) {
        String name = r.name() == null ? "" : r.name().trim();
        if (name.length() < 2) throw new BusinessException("Исм-фамилияни киритинг");
        Role role;
        try { role = Role.valueOf(r.role()); } catch (Exception e) { throw new BusinessException("Рол нотўғри"); }
        if (role == Role.SUPERADMIN && !isCreator(by)) throw new BusinessException("SuperAdmin'ни фақат асосий SuperAdmin тайинлайди");
        if (role == Role.KASSIR && (r.kassaId() == null || r.kassaId() == 0)) throw new BusinessException("Кассир учун кассани танланг");
        Long tgId = r.tgId() == null || r.tgId() == 0 ? null : r.tgId();
        if (tgId != null && userRepo.findByTelegramId(tgId).isPresent()) throw new BusinessException("Бу Telegram ID билан фойдаланувчи бор");
        String phone = r.phone() == null ? "" : r.phone().replaceAll("\\D", "");
        if (!phone.isEmpty()) {
            var dup = userRepo.findAll().stream().filter(x -> x.getPhone() != null && TextUtil.phoneEq(x.getPhone(), phone)).findFirst();
            if (dup.isPresent()) throw new BusinessException("Бу телефон рақам «" + dup.get().getFullName() + "» да ёзилган");
            if (tgId == null)
                for (Guest g : guestRepo.findAllByOrderByLastSeenDesc())
                    if (g.getPhone() != null && TextUtil.phoneEq(g.getPhone().replaceAll("\\D", ""), phone)) { tgId = g.getTelegramId(); break; }
        }
        AppUser created = userRepo.save(AppUser.builder().telegramId(tgId).fullName(name).role(role)
                .kassaId(role == Role.KASSIR ? r.kassaId() : null).phone(phone.isEmpty() ? null : phone).active(true).build());
        if (tgId != null) guestRepo.deleteById(tgId);
        audit.log(by.getId(), "USER_QOSHILDI", "user", created.getId(), by.getFullName() + " (web): " + name + " (" + role + ")"
                + (tgId == null ? "" : " tg=" + tgId));
        if (tgId != null) notify.toUser(tgId, "✅ Siz tizimga qo'shildingiz! Botga /start yozing.");
        return mapOf("id", created.getId(), "tgLinked", tgId != null);
    }

    public record RoleReq(String role, Long kassaId) {}

    public void changeRole(AppUser by, long userId, RoleReq r) {
        AppUser x = userRepo.findById(userId).orElseThrow(() -> new BusinessException("Фойдаланувчи топилмади"));
        Role newRole;
        try { newRole = Role.valueOf(r.role()); } catch (Exception e) { throw new BusinessException("Рол нотўғри"); }
        Long kassaId = newRole == Role.KASSIR ? r.kassaId() : null;
        if (newRole == Role.KASSIR && (kassaId == null || kassaId == 0)) throw new BusinessException("Кассир учун кассани танланг");
        if (isCreatorId(x) && newRole != Role.SUPERADMIN) throw new BusinessException("Асосий SuperAdmin ролини пасайтириб бўлмайди");
        if ((x.getRole() == Role.SUPERADMIN || newRole == Role.SUPERADMIN) && !isCreator(by))
            throw new BusinessException("SuperAdmin мақомини бериш/олишни фақат асосий SuperAdmin қилади");
        if (x.getRole() == Role.SUPERADMIN && newRole != Role.SUPERADMIN
                && userRepo.findByRoleAndActiveTrue(Role.SUPERADMIN).size() <= 1)
            throw new BusinessException("Бу охирги SuperAdmin — аввал бошқасини тайинланг");
        Role old = x.getRole();
        x.setRole(newRole);
        x.setKassaId(kassaId);
        userRepo.save(x);
        audit.log(by.getId(), "ROL_OZGARTIRILDI", "user", x.getId(), by.getFullName() + " (web): " + x.getFullName() + " " + old + " → " + newRole
                + (kassaId == null ? "" : " (" + names.owner(OwnerType.KASSA, kassaId) + ")"));
        if (x.getTelegramId() != null) notify.toUser(x.getTelegramId(), "🔄 Rolingiz o'zgartirildi. Yangi menyu uchun /start yozing.");
    }

    public void deactivateUser(AppUser by, long userId) {
        AppUser x = userRepo.findById(userId).orElseThrow(() -> new BusinessException("Фойдаланувчи топилмади"));
        if (x.getId().equals(by.getId())) throw new BusinessException("Ўзингизни ўчира олмайсиз");
        if (isCreatorId(x)) throw new BusinessException("Асосий SuperAdmin'ни фаолсизлантириб бўлмайди");
        if (x.getRole() == Role.SUPERADMIN && !isCreator(by)) throw new BusinessException("SuperAdmin'ни фақат асосий SuperAdmin ўчиради");
        x.setActive(false);
        userRepo.save(x);
        audit.log(by.getId(), "USER_FAOLSIZLANTIRILDI", "user", x.getId(), by.getFullName() + " (web): " + x.getFullName() + " (" + x.getRole() + ")");
    }

    private boolean isCreatorId(AppUser x) {
        Long t = props.getSuperadmin().getTelegramId();
        return t != null && t > 0 && t.equals(x.getTelegramId());
    }
    private boolean isCreator(AppUser by) { return isCreatorId(by); }

    /* ================= 💳 Karta mas'ullari ================= */

    public Map<String, Object> cardOwners() {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc())
            cards.add(mapOf("id", c.getId(), "name", c.getName(),
                    "kassa", c.getKassaId() == null ? "" : names.owner(OwnerType.KASSA, c.getKassaId()),
                    "masul", nz(c.getCardResponsible()).replaceAll("\\{id=\\d+;([^}]+)\\}", "$1"),
                    "masulTg", tgOf(c.getCardResponsible())));
        List<Map<String, Object>> users = new ArrayList<>();
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc())
            if (x.getTelegramId() != null) users.add(mapOf("id", x.getId(), "name", x.getFullName(), "role", x.getRole().name()));
        return mapOf("cards", cards, "users", users);
    }

    private static long tgOf(String resp) {
        if (resp == null) return 0;
        var m = java.util.regex.Pattern.compile("id=(\\d+)").matcher(resp);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    /** userId=0 — mas'ulni olib tashlash. */
    public void setCardOwner(AppUser by, long cardId, long userId) {
        ClickAccount c = clickRepo.findById(cardId).orElseThrow(() -> new BusinessException("Карта топилмади"));
        if (userId == 0) {
            c.setCardResponsible(null);
            clickRepo.save(c);
            audit.log(by.getId(), "KARTA_MASUL", "click", cardId, by.getFullName() + ": " + c.getName() + " -> mas'ul olib tashlandi (web)");
            return;
        }
        AppUser x = userRepo.findById(userId).orElseThrow(() -> new BusinessException("Фойдаланувчи топилмади"));
        if (x.getTelegramId() == null) throw new BusinessException("Бу фойдаланувчининг Telegram'и уланмаган");
        c.setCardResponsible("{id=" + x.getTelegramId() + ";" + x.getFullName() + "}");
        clickRepo.save(c);
        audit.log(by.getId(), "KARTA_MASUL", "click", cardId, by.getFullName() + ": " + c.getName() + " -> " + x.getFullName() + " (web)");
    }

    /* ================= 📣 Guruh/kanallar + jadval ================= */

    public Map<String, Object> clickGroups() {
        List<Map<String, Object>> chats = new ArrayList<>();
        for (long gid : jobs.clickChatIds()) {
            var chat = sender.getChat(gid);
            String st = sender.botStatusInChat(gid);
            boolean ok = "administrator".equals(st) || "member".equals(st) || "creator".equals(st);
            String name = chat == null ? null : (chat.getTitle() != null ? chat.getTitle() : chat.getUserName());
            chats.add(mapOf("id", gid, "name", name == null ? "ID " + gid : name, "ok", ok,
                    "channel", chat != null && chat.isChannelChat()));
        }
        return mapOf("chats", chats, "every", jobs.clickEvery(), "from", jobs.clickFrom(), "to", jobs.clickTo(),
                "offset", jobs.clickOffsetMin(), "footer", jobs.clickFooter(), "dailyTime", dailyReport.time(),
                "botUsername", nz(props.getBot().getUsername()));
    }

    public Map<String, Object> addClickGroup(AppUser by, long gid) {
        var chat = sender.getChat(gid);
        String st = sender.botStatusInChat(gid);
        boolean ok = chat != null && ("administrator".equals(st) || "member".equals(st) || "creator".equals(st));
        if (!ok) throw new BusinessException("Бу ID билан чат топилмади ёки бот унга қўшилмаган. Аввал @"
                + nz(props.getBot().getUsername()) + " ни гуруҳга қўшинг.");
        jobs.addClickChat(gid);
        audit.log(by.getId(), "CLICK_GROUP_SET", "settings", null, by.getFullName() + " guruh/kanal qo'shdi (web): " + gid);
        return mapOf("name", chat.getTitle() != null ? chat.getTitle() : chat.getUserName());
    }

    public void removeClickGroup(AppUser by, long gid) {
        jobs.removeClickChat(gid);
        audit.log(by.getId(), "CLICK_GROUP_DEL", "settings", null, by.getFullName() + " guruh/kanalni oldi (web): " + gid);
    }

    public record ScheduleReq(Integer every, Integer from, Integer to, Integer offset, String footer, String dailyTime) {}

    public void saveSchedule(AppUser by, ScheduleReq r) {
        if (r.every() != null) settings.set(Jobs.CLICK_EVERY_KEY, String.valueOf(Math.max(1, Math.min(24, r.every()))));
        if (r.from() != null) settings.set(Jobs.CLICK_FROM_KEY, String.valueOf(Math.max(0, Math.min(23, r.from()))));
        if (r.to() != null) settings.set(Jobs.CLICK_TO_KEY, String.valueOf(Math.max(0, Math.min(23, r.to()))));
        if (r.offset() != null) settings.set(Jobs.CLICK_OFFSET_KEY, String.valueOf(Math.max(-20, Math.min(20, r.offset()))));
        if (r.footer() != null) {
            if (r.footer().length() > 300) throw new BusinessException("Ост матн 300 белгигача");
            settings.set(Jobs.CLICK_FOOTER_KEY, r.footer().trim());
        }
        if (r.dailyTime() != null) {
            if (!r.dailyTime().matches("\\d{2}:\\d{2}")) throw new BusinessException("Вақт HH:MM кўринишида");
            settings.set(DailyReportService.TIME_KEY, r.dailyTime());
        }
        audit.log(by.getId(), "CLICK_JADVAL", "settings", null, by.getFullName() + " (web): " + r);
    }

    public void clickTestSend() { new Thread(jobs::clickReportNow, "web-click-test").start(); }

    private static String nz(String s) { return s == null ? "" : s; }
}
