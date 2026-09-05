package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.*;
import uz.kassa.domain.*;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.service.LedgerService;
import uz.kassa.service.NotificationService;
import uz.kassa.service.moysklad.MoySkladClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.*;
import static uz.kassa.bot.handlers.AdminSupport.*;

/**
 * 👥 Foydalanuvchilar: qo'shish, rol o'zgartirish, ro'yxat, faolsizlantirish.
 * (AdminHandler dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
public class UsersAdminHandler {

    private final Sender sender;
    private final NameService names;
    private final AppUserRepo userRepo;
    private final KassaRepo kassaRepo;
    private final uz.kassa.repo.GuestRepo guestRepo;
    private final MoySkladClient msClient;
    private final NotificationService notify;
    private final uz.kassa.service.AuditService audit;
    private final AdminSupport sup;


    /* ---------- 🔄 ROL O'ZGARTIRISH ---------- */

    List<String> userLabels() {
        List<String> out = new ArrayList<>();
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc())
            out.add("#" + x.getId() + " " + x.getFullName());
        return out;
    }


    AppUser userByLabel(String text) {
        if (!text.startsWith("#")) return null;
        int sp = text.indexOf(' ');
        if (sp < 0) return null;
        try {
            return userRepo.findById(Long.parseLong(text.substring(1, sp))).orElse(null);
        } catch (NumberFormatException e) { return null; }
    }


    List<String> roleLabels() {
        List<String> out = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            out.add("👤 Kassir — " + k.getName());
        out.add("🧮 Buxgalter");
        out.add("👑 SuperAdmin");
        return out;
    }


    boolean applyRole(AppUser actor, long userId, String text, long chatId) {
        Role newRole; Long kassaId = null;
        if (text.startsWith("👤 Kassir — ")) {
            Kassa k = sup.kassaByLabel(text.substring("👤 Kassir — ".length()));
            if (k == null) return false;
            newRole = Role.KASSIR; kassaId = k.getId();
        } else if (text.equals("🧮 Buxgalter")) newRole = Role.BUXGALTER;
        else if (text.equals("👑 SuperAdmin")) newRole = Role.SUPERADMIN;
        else return false;
        return applyRoleDirect(actor, userId, newRole, kassaId, chatId);
    }


    boolean applyRoleDirect(AppUser actor, long userId, Role newRole, Long kassaId,
                                    long chatId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null) return false;

        // Asosiy (yaratuvchi) SuperAdmin rolini pasaytirib bo'lmaydi — hatto o'zi ham:
        // aks holda SuperAdmin berish huquqi egasiz qolib, tizim qulflanadi
        if (sup.isCreatorId(x) && newRole != Role.SUPERADMIN) {
            sender.send(chatId, "⚠️ Asosiy (yaratuvchi) SuperAdmin rolini pasaytirib "
                    + "bo'lmaydi — tizim boshqaruvsiz qolmasligi uchun. Bu himoya "
                    + "hammaga, jumladan o'zingizga ham amal qiladi.");
            return true;
        }
        // Admin (SuperAdmin) maqomini berish/olish — faqat asosiy (yaratuvchi) SuperAdmin
        if ((x.getRole() == Role.SUPERADMIN || newRole == Role.SUPERADMIN) && !sup.isCreator(actor)) {
            sender.send(chatId, "⚠️ SuperAdmin maqomini berish yoki olishni faqat asosiy "
                    + "(yaratuvchi) SuperAdmin qila oladi.");
            return true;
        }
        // Oxirgi SuperAdmin'ni pasaytirib bo'lmaydi — tizim egasiz qolmasin
        if (x.getRole() == Role.SUPERADMIN && newRole != Role.SUPERADMIN
                && userRepo.findByRoleAndActiveTrue(Role.SUPERADMIN).size() <= 1) {
            sender.send(chatId, "⚠️ Bu oxirgi SuperAdmin — rolini o'zgartirib bo'lmaydi. "
                    + "Avval boshqa SuperAdmin tayinlang.");
            return true;
        }

        Role oldRole = x.getRole();
        x.setRole(newRole);
        x.setKassaId(kassaId);
        userRepo.save(x);
        audit.log(actor.getId(), "ROL_OZGARTIRILDI", "user", x.getId(),
                actor.getFullName() + ": " + x.getFullName() + " " + oldRole + " → " + newRole
                        + (kassaId == null ? "" : " (" + names.owner(OwnerType.KASSA, kassaId) + ")"));
        sender.send(chatId, "✅ <b>" + esc(x.getFullName()) + "</b> roli o'zgartirildi: <b>"
                + newRole + (kassaId == null ? "" : " · " + esc(names.owner(OwnerType.KASSA, kassaId)))
                + "</b>");
        notify.toUser(x.getTelegramId(),
                "🔄 Rolingiz o'zgartirildi. Yangi menyu uchun /start yozing.");
        return true;
    }


    void setUsers(long chatId, int msgId) {
        sup.show(chatId, msgId, "👥 <b>Фойдаланувчилар</b>", List.of(
                irow(btn("➕ Фойдаланувчи қўшиш", "a:p:sunew")),
                irow(btn("🚫 Фойдаланувчини ўчириш", "a:p:usr")),
                irow(sup.bk("a:p:set"))));
    }


    /* ==================== 👥 FOYDALANUVCHI QO'SHISH ==================== */

    /** Botga yozgan (hali qo'shilmagan) odamlar ro'yxatini ko'rsatadi — tanlash oson. */
    void auStart(Session s, long chatId) {
        s.reset();
        List<uz.kassa.domain.Guest> guests = guestRepo.findAllByOrderByLastSeenDesc().stream()
                .filter(g -> userRepo.findByTelegramId(g.getTelegramId()).isEmpty())
                .limit(8).toList();

        // MoySklad xodimlari (Владелец-сотрудник) — hali tizimda yo'qlari
        List<String[]> emps = new ArrayList<>();
        try {
            List<AppUser> all = userRepo.findAll();
            for (MoySkladClient.MsEmployee e : msClient.fetchEmployees()) {
                boolean exists = all.stream()
                        .anyMatch(x -> x.getFullName().equalsIgnoreCase(e.name()));
                if (!exists) emps.add(new String[]{e.name(), e.phone()});
                if (emps.size() >= 20) break;
            }
        } catch (Exception ignored) { }
        s.data.put("msEmps", emps);

        s.state = Session.State.ADM_AU_PICK;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (uz.kassa.domain.Guest g : guests) {
            String label = (g.getName() == null || g.getName().isBlank()
                        ? String.valueOf(g.getTelegramId()) : g.getName())
                    + (g.getPhone() != null ? " · " + g.getPhone()
                        : (g.getUsername() == null ? "" : " (@" + g.getUsername() + ")"));
            if (label.length() > 40) label = label.substring(0, 40) + "…";
            rows.add(irow(btn("👤 " + label, "a:gu:" + g.getTelegramId())));
        }
        for (int i = 0; i < emps.size(); i++) {
            String label = emps.get(i)[0];
            if (label.length() > 45) label = label.substring(0, 45) + "…";
            rows.add(irow(btn("👔 " + label, "a:me:" + i)));
        }
        rows.add(irow(btn("✍️ Telefon raqam bilan qidirish", "a:gu:m")));
        rows.add(irow(btn("❌ Bekor", "cx")));
        sender.send(chatId, "👥 <b>Yangi foydalanuvchi</b>\n\n"
                + (guests.isEmpty() ? "" : "👤 — botga yozgan odamlar\n")
                + (emps.isEmpty() ? "" : "👔 — MoySklad xodimlari (Владелец-сотрудник): "
                    + "tanlansangiz Telegram'siz yaratiladi, odam botga kirib telefonini "
                    + "yuborsa avtomatik ulanadi\n")
                + "\nBirini tanlang:", inline(rows));
    }


    /** MoySklad xodimi tanlandi — Telegram'siz foydalanuvchi sifatida yaratish yo'li. */
    void auEmp(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_AU_PICK) return;
        Object o = s.data.get("msEmps");
        int i = Integer.parseInt(arg);
        if (!(o instanceof List<?> l) || i < 0 || i >= l.size()) return;
        String[] emp = (String[]) l.get(i);
        s.data.remove("tgid");
        s.data.put("name", emp[0]);
        s.data.put("empPhone", emp[1] == null ? "" : emp[1]);
        s.state = Session.State.ADM_AU_ROLE;
        sender.edit(chatId, msgId, "👔 Tanlandi: <b>" + esc(emp[0]) + "</b>"
                + (emp[1] == null || emp[1].isBlank() ? "" : " · " + esc(emp[1]))
                + "\nℹ️ Telegram hali ulanmagan — u botga kirib «📱 Telefon raqamni "
                + "yuborish»ni bossa avtomatik ulanadi"
                + (emp[1] == null || emp[1].isBlank()
                    ? " (MoySkladda telefoni yo'q — keyin jadvalda Telefon ustunini to'ldiring)" : "")
                + "\n\nRolini tanlang:", inline(List.of(
                irow(btn("👤 Kassir", "a:rl:K")),
                irow(btn("🧮 Buxgalter", "a:rl:B"), btn("👑 SuperAdmin", "a:rl:S")),
                irow(btn("❌ Bekor", "cx")))));
    }


    void auPick(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_AU_PICK) return;
        if (arg.equals("m")) {
            s.state = Session.State.ADM_AU_TGID;
            sender.edit(chatId, msgId, "📱 Telefon raqamini kiriting (masalan +998901234567).\n"
                    + "<i>Foydalanuvchi botga kirib «Telefon raqamni yuborish» tugmasini "
                    + "bosgan bo'lishi kerak.</i>");
            return;
        }
        long tgId;
        try { tgId = Long.parseLong(arg); } catch (NumberFormatException e) { return; }
        if (userRepo.findByTelegramId(tgId).isPresent()) {
            s.reset();
            sender.edit(chatId, msgId, "⚠️ Bu foydalanuvchi allaqachon tizimda mavjud");
            return;
        }
        s.data.put("tgid", tgId);
        String suggested = guestRepo.findById(tgId)
                .map(uz.kassa.domain.Guest::getName).orElse(null);
        s.state = Session.State.ADM_AU_NAME;
        if (suggested != null && !suggested.isBlank()) {
            s.data.put("suggName", suggested);
            sender.edit(chatId, msgId, "Tanlandi: <code>" + tgId + "</code>\n\n"
                    + "Ism-familiyasini kiriting, yoki «<b>-</b>» yuboring — "
                    + "<b>" + esc(suggested) + "</b> deb yoziladi:");
        } else {
            sender.edit(chatId, msgId, "Tanlandi: <code>" + tgId + "</code>\n\n"
                    + "Ism-familiyasini kiriting:");
        }
    }


    /** Telefon raqam (asosiy yo'l) yoki Telegram ID bilan qidirish. */
    void auTgId(Session s, String text, long chatId) {
        String digits = text.replaceAll("\\D", "");
        if (digits.length() < 7) {
            sender.send(chatId, "⚠️ Telefon raqam (masalan +998901234567) yoki Telegram ID kiriting:");
            return;
        }

        // 1) Telefon bo'yicha — kontakt yuborgan mehmonlar orasidan
        for (uz.kassa.domain.Guest g : guestRepo.findAllByOrderByLastSeenDesc()) {
            String gp = g.getPhone() == null ? "" : g.getPhone().replaceAll("\\D", "");
            if (gp.isEmpty()) continue;
            if (uz.kassa.bot.TextUtil.phoneEq(gp, digits)) {   // faqat TO'LIQ moslik — suffiks emas
                if (userRepo.findByTelegramId(g.getTelegramId()).isPresent()) {
                    sender.send(chatId, "⚠️ Bu raqam egasi allaqachon tizimda");
                    s.reset();
                    return;
                }
                s.data.put("tgid", g.getTelegramId());
                String sugg = g.getName();
                s.state = Session.State.ADM_AU_NAME;
                if (sugg != null && !sugg.isBlank()) {
                    s.data.put("suggName", sugg);
                    sender.send(chatId, "✅ Topildi: <b>" + esc(sugg) + "</b> ("
                            + esc(g.getPhone()) + ")\n\nIsm-familiyasini kiriting, "
                            + "yoki «<b>-</b>» — shu nom qoladi:");
                } else sender.send(chatId, "✅ Topildi: " + esc(g.getPhone())
                        + "\n\nIsm-familiyasini kiriting:");
                return;
            }
        }

        // 2) Telefonga o'xshasa (998 bilan boshlanadi) lekin topilmasa — yo'riqnoma
        if (digits.startsWith("998") || text.trim().startsWith("+")) {
            sender.send(chatId, "⚠️ Bu raqam topilmadi.\n\n"
                    + "Foydalanuvchi botga kirib <b>«📱 Telefon raqamni yuborish»</b> "
                    + "tugmasini bossin — keyin raqami bilan topiladi.");
            return;
        }

        // 3) Telegram ID sifatida
        long tgId = Long.parseLong(digits);
        if (userRepo.findByTelegramId(tgId).isPresent()) {
            sender.send(chatId, "⚠️ Bu ID bilan foydalanuvchi allaqachon mavjud");
            s.reset();
            return;
        }
        s.data.put("tgid", tgId);
        s.state = Session.State.ADM_AU_NAME;
        sender.send(chatId, "Ism-familiyasini kiriting:");
    }


    void auName(Session s, String text, long chatId) {
        String sugg = s.getStr("suggName");
        s.data.put("name", text.equals("-") && sugg != null ? sugg : text);
        s.state = Session.State.ADM_AU_ROLE;
        sender.send(chatId, "Rolini tanlang:", inline(List.of(
                irow(btn("👤 Kassir", "a:rl:K")),
                irow(btn("🧮 Buxgalter", "a:rl:B"), btn("👑 SuperAdmin", "a:rl:S")),
                irow(btn("❌ Bekor", "cx")))));
    }


    void auRole(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_AU_ROLE) return;
        switch (arg) {
            case "K" -> {
                List<Kassa> list = kassaRepo.findByActiveTrueOrderByIdAsc();
                if (list.isEmpty()) {
                    s.reset();
                    sender.edit(chatId, msgId, "⚠️ Avval kassa qo'shing (🏪 Kassa qo'shish)");
                    return;
                }
                s.data.put("role", Role.KASSIR);
                s.state = Session.State.ADM_AU_KASSA;
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                for (Kassa k : list) rows.add(irow(btn("🏪 " + k.getName(), "a:ks:" + k.getId())));
                rows.add(irow(btn("❌ Bekor", "cx")));
                sender.edit(chatId, msgId, "Qaysi kassaga biriktiriladi?", inline(rows));
            }
            case "B" -> saveUser(s, Role.BUXGALTER, null, chatId, msgId);
            case "S" -> saveUser(s, Role.SUPERADMIN, null, chatId, msgId);
        }
    }


    void auKassa(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_AU_KASSA) return;
        saveUser(s, Role.KASSIR, Long.parseLong(arg), chatId, msgId);
    }


    void saveUser(Session s, Role role, Long kassaId, long chatId, int msgId) {
        Long tgId = s.data.get("tgid") == null ? null : s.getLong("tgid");
        String name = s.getStr("name");
        String phoneRaw = s.getStr("empPhone");
        String phone = phoneRaw == null ? "" : phoneRaw.replaceAll("\\D", "");
        // Bir xil telefon raqamli IKKINCHI foydalanuvchi yaratilmasin
        if (!phone.isEmpty()) {
            var dupPhone = userRepo.findAll().stream()
                    .filter(x -> x.getPhone() != null
                            && uz.kassa.bot.TextUtil.phoneEq(x.getPhone(), phone))
                    .findFirst();
            if (dupPhone.isPresent()) {
                s.reset();
                sender.edit(chatId, msgId, "⚠️ Bu telefon raqam allaqachon <b>"
                        + esc(dupPhone.get().getFullName()) + "</b>"
                        + (dupPhone.get().isActive() ? "" : " (nofaol)")
                        + "da yozilgan — takror foydalanuvchi yaratilmadi.\n\n"
                        + "Raqam haqiqatan boshqa odamniki bo'lsa, avval eskisining "
                        + "raqamini to'g'rilang yoki o'chiring.");
                return;
            }
        }
        s.reset();
        userRepo.save(AppUser.builder()
                .telegramId(tgId).fullName(name).role(role).kassaId(kassaId)
                .phone(phone.isEmpty() ? null : phone)
                .active(true).build());
        if (tgId != null) guestRepo.deleteById(tgId);   // ro'yxatga olindi — mehmonlardan chiqadi
        String where = kassaId == null ? "" : "\nKassa: " + esc(names.owner(OwnerType.KASSA, kassaId));
        sender.edit(chatId, msgId, "✅ Foydalanuvchi qo'shildi:\n<b>" + esc(name) + "</b> ("
                + role + ")" + where
                + (tgId != null
                    ? "\nTelegram ID: <code>" + tgId + "</code>\n\n"
                      + "Endi u botga <b>/start</b> yozsa — menyusi ochiladi."
                    : (phone.isEmpty() ? "" : "\nTelefon: <code>" + esc(phone) + "</code>")
                      + "\n\nℹ️ Telegram hali ulanmagan — u botga kirib «📱 Telefon raqamni "
                      + "yuborish»ni bossa avtomatik ulanadi."));
    }


    /* ==================== 👤 FOYDALANUVCHILAR ==================== */

    void listUsers(AppUser me, long chatId) {
        List<AppUser> users = userRepo.findByActiveTrueOrderByRoleAscIdAsc();
        StringBuilder sb = new StringBuilder("👤 <b>Faol foydalanuvchilar</b>\n\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (AppUser x : users) {
            String where = x.getKassaId() == null ? "" :
                    " — " + names.owner(OwnerType.KASSA, x.getKassaId());
            sb.append("• <b>").append(esc(x.getFullName())).append("</b> (")
              .append(x.getRole()).append(esc(where)).append(") — <code>")
              .append(x.getTelegramId() == null ? "tg ulanmagan" : x.getTelegramId())
              .append("</code>\n");
            if (!x.getId().equals(me.getId()) && rows.size() < 12)
                rows.add(irow(btn("🚫 " + x.getFullName(), "a:ux:" + x.getId())));
        }
        sb.append("\nFaolsizlantirish uchun tugmani bosing:");
        sender.send(chatId, sb.toString(), rows.isEmpty() ? null : inline(rows));
    }


    void deactivate(AppUser me, long userId, long chatId, int msgId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null || x.getId().equals(me.getId())) return;
        // Asosiy (yaratuvchi) SuperAdmin'ni hech kim faolsizlantira olmaydi
        if (sup.isCreatorId(x)) {
            sender.edit(chatId, msgId, "⚠️ Asosiy (yaratuvchi) SuperAdmin'ni "
                    + "faolsizlantirib bo'lmaydi.");
            return;
        }
        // SuperAdmin'ni faqat asosiy (yaratuvchi) SuperAdmin o'chira oladi
        if (x.getRole() == Role.SUPERADMIN && !sup.isCreator(me)) {
            sender.edit(chatId, msgId, "⚠️ SuperAdmin'ni faqat asosiy (yaratuvchi) "
                    + "SuperAdmin faolsizlantira oladi.");
            return;
        }
        x.setActive(false);
        userRepo.save(x);
        audit.log(me.getId(), "USER_FAOLSIZLANTIRILDI", "user", x.getId(),
                me.getFullName() + " faolsizlantirdi: " + x.getFullName() + " (" + x.getRole() + ")");
        sender.edit(chatId, msgId, "🚫 <b>" + esc(x.getFullName())
                + "</b> faolsizlantirildi. U endi botdan foydalana olmaydi.");
    }

}
