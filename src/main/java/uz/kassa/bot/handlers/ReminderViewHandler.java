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
import static uz.kassa.bot.handlers.KontragentHandler.*;

/**
 * Eslatmalar ro'yxati, kartasi, yopish va qisman to'lov (so'rov/tasdiq).
 * (KontragentHandler dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
public class ReminderViewHandler {

    private final Sender sender;
    private final ReminderService reminders;
    private final AppUserRepo userRepo;
    private final NotificationService notify;
    private final KontragentSupport ks;


    /* ==================== 🔔 ХАБАРНОМАЛАР ==================== */

    void list(AppUser u, Session s, long chatId, int msgId) {
        List<Reminder> list = reminders.visibleFor(u);
        StringBuilder sb = new StringBuilder("🔔 <b>Хабарномалар</b>"
                + (u.getRole() == Role.SUPERADMIN ? " (hammasi)" : "") + "\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (list.isEmpty()) sb.append("\nFaol eslatmalar yo'q.");
        int shown = 0;
        for (Reminder r : list) {
            if (shown++ >= 15) break;
            rows.add(irow(btn("📄 #" + r.getId() + " · " + r.getAgentName() + " · "
                    + fmt(r.remain()) + " · " + r.getDueDate().format(DF), "kg:v:" + r.getId())));
        }
        if (!list.isEmpty()) sb.append("\nBatafsil ko'rish uchun tanlang:");
        rows.add(irow(ks.bk("kg:m")));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }


    void card(AppUser u, long id, long chatId, int msgId) {
        Reminder r = reminders.activeAll().stream()
                .filter(x -> x.getId().equals(id)).findFirst().orElse(null);
        if (r == null) {
            sender.edit(chatId, msgId, "⚠️ Eslatma topilmadi yoki allaqachon yopilgan",
                    inline(List.of(irow(ks.bk("kg:l")))));
            return;
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (r.getPendingManualAmount() != null)
            rows.add(irow(btn("⏳ Tasdiq kutilmoqda: " + fmt(r.getPendingManualAmount()) + " so'm", "kg:m")));
        else if (r.remain() > 0)
            rows.add(irow(btn("💵 Qisman to'lov kiritish", "kg:pw:" + id)));
        if (r.getCreatorUserId().equals(u.getId()) || u.getRole() == Role.SUPERADMIN)
            rows.add(irow(btn("✅ Bajarildi (yopish)", "kg:f:" + id),
                          btn("🚫 Bekor qilish", "kg:x:" + id)));
        rows.add(irow(ks.bk("kg:l")));
        sender.edit(chatId, msgId, "📄 <b>Eslatma #" + id + "</b>\n\n"
                + reminders.render(r, true), inline(rows));
    }


    void closeRem(AppUser u, long id, boolean done, long chatId, int msgId) {
        Reminder r = reminders.close(id, u, done);
        String st = done ? "✅ BAJARILDI deb yopildi" : "🚫 BEKOR qilindi";
        sender.edit(chatId, msgId, "📄 Eslatma #" + id + " — " + st + "\n\n"
                + reminders.render(r, false), inline(List.of(irow(ks.bk("kg:l")))));
        String text = "ℹ️ Eslatma #" + id + " (" + esc(r.getAgentName()) + " — "
                + fmt(r.getAmount()) + " so'm) " + st + ".\n✍️ " + esc(u.getFullName());
        for (Long uid : r.recipientSet())
            if (!uid.equals(u.getId()))
                userRepo.findById(uid).ifPresent(x -> notify.toUser(x.getTelegramId(), text));
    }


    /* ==================== 💵 QISMAN TO'LOV ==================== */

    void payStart(Session s, long id, long chatId, int msgId) {
        Reminder r = reminders.find(id).orElse(null);
        if (r == null) { sender.edit(chatId, msgId, "⚠️ Eslatma topilmadi yoki allaqachon yopilgan."); return; }
        s.state = Session.State.KG_PAY_AMOUNT;
        s.data.put("kgPayId", id);
        sender.edit(chatId, msgId, "💵 <b>Qisman to'lov</b>\n\nQoldiq: <b>" + fmt(r.remain())
                + "</b> so'm\n\nTo'langan summani kiriting:", inline(List.of(irow(ks.bk("kg:v:" + id)))));
    }


    void payAmount(AppUser u, Session s, String text, long chatId) {
        long id = s.getLong("kgPayId");
        s.reset();
        long sum = parseAmount(text);
        Reminder r = reminders.requestManualPayment(id, u, sum);
        sender.send(chatId, "📨 So'rov yuborildi: <b>" + fmt(sum) + "</b> so'm to'lov — "
                + "buxgalter/SuperAdmin tasdig'i kutilmoqda.");
        String note = "❓ <b>" + esc(u.getFullName()) + "</b> qarz eslatmasiga to'lov kiritishni so'rayapti:\n\n"
                + reminders.render(r, false) + "\n\n💵 To'lov: <b>" + fmt(sum) + "</b> so'm";
        InlineKeyboardMarkup kb = inline(List.of(irow(
                btn("✅ Tasdiqlash", "kg:pa:" + id + "." + u.getId()),
                btn("❌ Rad etish", "kg:pr:" + id + "." + u.getId()))));
        notify.toRole(Role.BUXGALTER, note, kb);
        notify.toRole(Role.SUPERADMIN, note, kb);
    }


    /** arg: "<reminderId>.<requesterId>" */
    void payDecide(AppUser admin, String arg, boolean approve, long chatId, int msgId) {
        long id = Long.parseLong(arg.split("\\.")[0]);
        long reqId = Long.parseLong(arg.split("\\.")[1]);
        Reminder r = approve ? reminders.approveManualPayment(id, admin) : reminders.rejectManualPayment(id, admin);
        String st = approve ? "✅ Tasdiqlandi" : "❌ Rad etildi";
        sender.edit(chatId, msgId, "📄 Eslatma #" + id + " to'lovi — " + st + "\n\n" + reminders.render(r, false));
        String text = approve
                ? "✅ To'lov so'rovingiz tasdiqlandi: <b>" + esc(r.getAgentName()) + "</b> — qoldiq: "
                    + fmt(r.remain()) + " so'm.\n✍️ " + esc(admin.getFullName())
                : "❌ To'lov so'rovingiz rad etildi: <b>" + esc(r.getAgentName()) + "</b>.\n✍️ "
                    + esc(admin.getFullName());
        userRepo.findById(reqId).ifPresent(x -> notify.toUser(x.getTelegramId(), text));
    }

}
