package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.Sender;
import uz.kassa.bot.Session;
import uz.kassa.domain.*;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.GuestRepo;
import uz.kassa.service.*;
import uz.kassa.service.moysklad.MoySkladClient;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.*;

/**
 * Отдел Али xodimlari: qo'shish (mehmon/telefon), ro'yxat, nom o'zgartirish va o'chirish so'rovlari, tasdiq/rad.
 * (KontragentHandler dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
public class KontragentStaffHandler {

    private final Sender sender;
    private final AppUserRepo userRepo;
    private final GuestRepo guestRepo;
    private final SettingsService settings;
    private final NotificationService notify;
    private final AuditService audit;
    private final KontragentSupport ks;


    /* ==================== ⚙️ НАСТРОЙКА (o'z otdeli) ==================== */

    void staffMenu(AppUser u, long chatId, int msgId) {
        if (u.getKassaId() == null) return;
        sender.edit(chatId, msgId, "⚙️ <b>Настройка</b> — otdelim xodimlari\n\n"
                + "➕ Qo'shish darhol kuchga kiradi (SuperAdmin'ga xabar boradi).\n"
                + "🚫 O'chirish va ✏️ tahrirlash — SuperAdmin tasdig'i bilan.", inline(List.of(
                irow(btn("➕ Одам қўшиш", "kg:sa")),
                irow(btn("👥 Отделим ходимлари", "kg:sl")),
                irow(ks.bk("kg:m")))));
    }


    void staffAddStart(AppUser u, Session s, long chatId, int msgId) {
        if (u.getKassaId() == null) return;
        List<Guest> guests = guestRepo.findAllByOrderByLastSeenDesc().stream()
                .filter(g -> userRepo.findByTelegramId(g.getTelegramId()).isEmpty())
                .limit(8).toList();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Guest g : guests) {
            String label = (g.getName() == null || g.getName().isBlank() ? "?" : g.getName())
                    + (g.getUsername() == null ? "" : " (@" + g.getUsername() + ")");
            if (label.length() > 50) label = label.substring(0, 50);
            rows.add(irow(btn(label, "kg:sg:" + g.getTelegramId())));
        }
        rows.add(irow(ks.bk("kg:st")));
        s.state = Session.State.KG_AU_TGID;
        sender.edit(chatId, msgId, "➕ <b>Одам қўшиш</b> — otdelingizga kassir sifatida\n\n"
                + (guests.isEmpty() ? "" : "Botga yozganlar ro'yxatidan tanlang yoki ")
                + "Telegram ID raqamini yozib yuboring:", inline(rows));
    }


    void staffGuest(Session s, String arg, long chatId, int msgId) {
        s.data.put("kgStTg", Long.parseLong(arg));
        s.state = Session.State.KG_AU_NAME;
        sender.edit(chatId, msgId, "Tanlandi: <code>" + arg + "</code>\n\n"
                + "✍️ To'liq ism-familiyasini yozing:");
    }


    void auTgId(AppUser u, Session s, String text, long chatId) {
        long tgId;
        try { tgId = Long.parseLong(text.trim()); }
        catch (NumberFormatException e) {
            sender.send(chatId, "⚠️ Telegram ID — faqat raqam. Qaytadan yozing:");
            return;
        }
        if (userRepo.findByTelegramId(tgId).isPresent()) {
            sender.send(chatId, "⚠️ Bu Telegram ID allaqachon tizimda bor.");
            s.reset();
            return;
        }
        s.data.put("kgStTg", tgId);
        s.state = Session.State.KG_AU_NAME;
        sender.send(chatId, "✍️ To'liq ism-familiyasini yozing:");
    }


    void auName(AppUser u, Session s, String text, long chatId) {
        if (u.getKassaId() == null) { s.reset(); return; }
        long tgId = s.getLong("kgStTg");
        String name = text.trim();
        s.reset();
        AppUser x = userRepo.save(AppUser.builder()
                .telegramId(tgId).fullName(name).role(Role.KASSIR)
                .kassaId(u.getKassaId()).active(true).build());
        guestRepo.deleteById(tgId);
        audit.log(u.getId(), "OTDEL_XODIM_QOSHILDI", "user", x.getId(), name + " tg=" + tgId);
        sender.send(chatId, "✅ <b>" + esc(name) + "</b> otdelingizga qo'shildi.\n"
                + "U botga /start yozsa menyusi ochiladi.");
        notify.toRole(Role.SUPERADMIN, "➕ <b>" + esc(u.getFullName())
                + "</b> o'z otdeliga xodim qo'shdi: <b>" + esc(name)
                + "</b> (tg: <code>" + tgId + "</code>)", null);
    }


    void staffList(AppUser u, long chatId, int msgId) {
        if (u.getKassaId() == null) return;
        List<AppUser> staff = userRepo.findByKassaIdAndActiveTrue(u.getKassaId()).stream()
                .filter(x -> !x.getId().equals(u.getId())).toList();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        StringBuilder sb = new StringBuilder("👥 <b>Отделим ходимлари</b>\n");
        if (staff.isEmpty()) sb.append("\nSizdan boshqa xodim yo'q.");
        for (AppUser x : staff) {
            sb.append("\n• ").append(esc(x.getFullName()));
            rows.add(irow(btn("🚫 " + x.getFullName(), "kg:rd:" + x.getId()),
                          btn("✏️ " + x.getFullName(), "kg:rn:" + x.getId())));
        }
        if (!staff.isEmpty())
            sb.append("\n\n🚫 o'chirish / ✏️ ism o'zgartirish — SuperAdmin tasdiqlaydi:");
        rows.add(irow(ks.bk("kg:st")));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }


    void staffDelRequest(AppUser u, long userId, long chatId, int msgId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null) return;
        sender.edit(chatId, msgId, "📨 So'rov yuborildi: <b>" + esc(x.getFullName())
                + "</b>ni o'chirish — SuperAdmin tasdig'i kutilmoqda.");
        notify.toRole(Role.SUPERADMIN, "❓ <b>" + esc(u.getFullName())
                        + "</b> o'z otdelidan xodimni O'CHIRISHNI so'rayapti:\n👤 <b>"
                        + esc(x.getFullName()) + "</b>",
                inline(List.of(irow(
                        btn("✅ Tasdiqlash", "kg:apd:" + userId + "." + u.getId()),
                        btn("❌ Rad etish", "kg:rjd:" + userId + "." + u.getId())))));
    }


    void staffRenStart(Session s, long userId, long chatId, int msgId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null) return;
        s.state = Session.State.KG_RN_NAME;
        s.data.put("kgRnId", userId);
        sender.edit(chatId, msgId, "✏️ <b>" + esc(x.getFullName())
                + "</b> uchun yangi ism-familiya yozing (SuperAdmin tasdiqlaydi):");
    }


    void rnName(AppUser u, Session s, String text, long chatId) {
        long userId = s.getLong("kgRnId");
        s.reset();
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null) return;
        String newName = text.trim();
        settings.set("kgrename." + userId, newName);
        sender.send(chatId, "📨 So'rov yuborildi: <b>" + esc(x.getFullName()) + "</b> → <b>"
                + esc(newName) + "</b> — SuperAdmin tasdig'i kutilmoqda.");
        notify.toRole(Role.SUPERADMIN, "❓ <b>" + esc(u.getFullName())
                        + "</b> xodim ismini O'ZGARTIRISHNI so'rayapti:\n👤 <b>"
                        + esc(x.getFullName()) + "</b> → <b>" + esc(newName) + "</b>",
                inline(List.of(irow(
                        btn("✅ Tasdiqlash", "kg:apr:" + userId + "." + u.getId()),
                        btn("❌ Rad etish", "kg:rjr:" + userId + "." + u.getId())))));
    }


    /** arg: "<userId>.<requesterId>" */
    void staffDelApprove(AppUser admin, String arg, long chatId, int msgId) {
        if (admin.getRole() != Role.SUPERADMIN) return;
        long userId = Long.parseLong(arg.split("\\.")[0]);
        long reqId = Long.parseLong(arg.split("\\.")[1]);
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null) return;
        x.setActive(false);
        userRepo.save(x);
        audit.log(admin.getId(), "OTDEL_XODIM_OCHIRILDI", "user", userId, x.getFullName());
        sender.edit(chatId, msgId, "✅ <b>" + esc(x.getFullName())
                + "</b> o'chirildi (so'rov tasdiqlandi).");
        userRepo.findById(reqId).ifPresent(r -> notify.toUser(r.getTelegramId(),
                "✅ So'rovingiz tasdiqlandi: <b>" + esc(x.getFullName()) + "</b> o'chirildi."));
    }


    void staffRenApprove(AppUser admin, String arg, long chatId, int msgId) {
        if (admin.getRole() != Role.SUPERADMIN) return;
        long userId = Long.parseLong(arg.split("\\.")[0]);
        long reqId = Long.parseLong(arg.split("\\.")[1]);
        AppUser x = userRepo.findById(userId).orElse(null);
        String newName = settings.get("kgrename." + userId).orElse("");
        if (x == null || newName.isBlank()) {
            sender.edit(chatId, msgId, "⚠️ So'rov eskirgan yoki topilmadi.");
            return;
        }
        String old = x.getFullName();
        x.setFullName(newName);
        userRepo.save(x);
        settings.set("kgrename." + userId, "");
        audit.log(admin.getId(), "OTDEL_XODIM_TAHRIR", "user", userId, old + " -> " + newName);
        sender.edit(chatId, msgId, "✅ Ism o'zgartirildi: <b>" + esc(old) + "</b> → <b>"
                + esc(newName) + "</b> (so'rov tasdiqlandi).");
        userRepo.findById(reqId).ifPresent(r -> notify.toUser(r.getTelegramId(),
                "✅ So'rovingiz tasdiqlandi: <b>" + esc(old) + "</b> → <b>" + esc(newName) + "</b>"));
    }


    void staffReject(AppUser admin, String arg, long chatId, int msgId) {
        if (admin.getRole() != Role.SUPERADMIN) return;
        long reqId = Long.parseLong(arg.split("\\.")[1]);
        sender.edit(chatId, msgId, "❌ So'rov rad etildi.");
        userRepo.findById(reqId).ifPresent(r -> notify.toUser(r.getTelegramId(),
                "❌ Otdel xodimi bo'yicha so'rovingiz SuperAdmin tomonidan rad etildi."));
    }

}
