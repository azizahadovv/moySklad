package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.Sender;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Notify;
import uz.kassa.service.AuditService;
import uz.kassa.service.notify.NotifyPresets;
import uz.kassa.service.notify.NotifyService;
import uz.kassa.service.notify.TemplateService;

import java.util.ArrayList;
import java.util.List;

import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.esc;

/**
 * 📚 НАМУНАЛАР — mavjud hisobotlarning shablon nusxalarini bir tugma bilan
 * yaratish (hisobot konstruktori). Callback'lar (NotifyAdminHandler'dan «nfp…»):
 *   nfp            — ro'yxat
 *   nfpv:<key>     — ko'rish (jonli render, o'zimga)
 *   nfpc:<key>     — yaratish (o'chirilgan holda) → karta
 */
@Component
@RequiredArgsConstructor
public class NotifyPresetHandler {

    private final Sender sender;
    private final NotifyService svc;
    private final AuditService audit;

    /** @return yaratilgan Notify id (nfpc) yoki null — chaqiruvchi kartani ochadi. */
    public Long onCallback(AppUser u, String cmd, String arg, long chatId, int msgId) {
        switch (cmd) {
            case "nfp" -> list(chatId, msgId);
            case "nfpv" -> view(arg, chatId, msgId);
            case "nfpc" -> { return create(u, arg, chatId); }
            default -> { }
        }
        return null;
    }

    void list(long chatId, int msgId) {
        StringBuilder sb = new StringBuilder("📚 <b>Namunalar — hisobot konstruktori</b>\n\n"
                + "Mavjud hisobotlarning shablon ko'rinishidagi nusxalari. Tanlang → ko'ring → "
                + "«Yaratish» — yangi bildirishnoma <b>o'chirilgan holda</b> yaratiladi, matni, jadvali va "
                + "qabul qiluvchilarini o'zgartirib, keyin yoqasiz.\n"
                + "<i>Asl hisobotlar (kod) avvalgidek ishlayveradi — bu ularning nusxasi.</i>\n\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (NotifyPresets.Preset p : NotifyPresets.ALL) {
            sb.append("<b>").append(esc(p.title())).append("</b> — ").append(esc(p.about())).append("\n\n");
            rows.add(irow(btn(p.title(), "a:nfpv:" + p.key())));
        }
        rows.add(irow(btn("⬅️ Orqaga", "a:nfm")));
        show(chatId, msgId, sb.toString(), rows);
    }

    private void view(String key, long chatId, int msgId) {
        NotifyPresets.Preset p = NotifyPresets.byKey(key);
        if (p == null) { list(chatId, msgId); return; }
        Notify tmp = p.toNotify();
        TemplateService.Result r = svc.preview(tmp, chatId);
        String rendered = r.text().length() > 2500 ? r.text().substring(0, 2500) + "…" : r.text();
        String text = "📚 <b>" + esc(p.title()) + "</b>\n" + esc(p.about()) + "\n\n"
                + "⏰ Jadval: <b>" + esc(svc.describeSchedule(tmp)) + "</b>\n"
                + "👥 Kimga: " + svc.describeRecipients(tmp) + "\n\n"
                + "👁 <b>Hozirgi ko'rinishi</b> (jonli ma'lumot bilan):\n\n" + rendered
                + (r.unknown().isEmpty() ? "" : "\n\n⚠️ Noma'lum o'rinbosarlar: <code>"
                    + esc(String.join(" ", r.unknown())) + "</code>")
                + (r.msFailed() ? "\n\n⚠️ MoySklad o'qilmadi — zaxira qiymatlar" : "")
                + (key.equals("kassir") ? "\n\n<i>ℹ️ «mening» — kassirning o'z otdeli; admin ko'rinishida "
                    + "bo'sh chiqishi mumkin, kassirga yuborilganda to'ladi.</i>" : "");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("➕ Yaratish (o'chirilgan holda)", "a:nfpc:" + key)));
        rows.add(irow(btn("⬅️ Namunalar", "a:nfp")));
        show(chatId, msgId, text, rows);
    }

    private Long create(AppUser u, String key, long chatId) {
        NotifyPresets.Preset p = NotifyPresets.byKey(key);
        if (p == null) return null;
        Notify n = svc.save(p.toNotify());
        audit.log(u.getId(), "NOTIFY_YARATILDI", "notify", n.getId(),
                u.getFullName() + " — namuna: " + p.title());
        sender.send(chatId, "✅ <b>" + esc(n.getName()) + "</b> yaratildi (⚪ o'chirilgan). "
                + "Matn, ⏰ jadval va 👥 qabul qiluvchilarni tekshirib, ▶️ Yoqish tugmasini bosing.");
        return n.getId();
    }

    private void show(long chatId, int msgId, String text, List<List<InlineKeyboardButton>> rows) {
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sender.send(chatId, text, inline(rows));
    }
}
