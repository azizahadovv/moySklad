package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.LabelService;
import uz.kassa.bot.MenuSchemaService;
import uz.kassa.bot.Sender;
import uz.kassa.bot.Session;
import uz.kassa.domain.AppUser;

import java.util.ArrayList;
import java.util.List;

import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.esc;

/**
 * 🧩 МЕНЮ ТАРТИБИ — admin paneldan tugmalar tartibi, ustunlar soni va
 * yashirish (sxema orqali boshqarish, 2-bosqich). Callback'lar: a:mo…
 *   mom          — menyular ro'yxati
 *   mo:<key>     — menyu kartasi
 *   mou/mod:<key>:<idx> — yuqoriga/pastga
 *   moh:<key>:<idx>     — yashirish/yoqish (faqat LabelService.RENAMABLE)
 *   moc:<key>:<n>       — ustunlar soni 1..3
 *   mor:<key>           — asl holat
 */
@Component
@RequiredArgsConstructor
public class MenuSchemaHandler {

    private final Sender sender;
    private final MenuSchemaService schema;
    private final LabelService labelSvc;
    private final uz.kassa.service.AuditService audit;
    private final AdminSupport sup;

    public boolean onCallback(AppUser u, Session s, String cmd, String arg, long chatId, int msgId) {
        String[] w = arg.split(":");
        String key = w.length > 0 ? w[0] : "";
        int idx = -1;
        if (w.length > 1) { try { idx = Integer.parseInt(w[1]); } catch (NumberFormatException ignored) {} }
        switch (cmd) {
            case "mom" -> menu(s, chatId, msgId);
            case "mo" -> card(key, chatId, msgId);
            case "mou" -> { schema.move(key, idx, -1); log(u, key, "tartib"); card(key, chatId, msgId); }
            case "mod" -> { schema.move(key, idx, +1); log(u, key, "tartib"); card(key, chatId, msgId); }
            case "moc" -> { schema.setCols(key, idx); log(u, key, "ustun=" + idx); card(key, chatId, msgId); }
            case "mor" -> { schema.reset(key); log(u, key, "asl holat"); card(key, chatId, msgId); }
            case "moh" -> hideToggle(u, key, idx, chatId, msgId);
            default -> { return false; }
        }
        return true;
    }

    private void log(AppUser u, String key, String what) {
        audit.log(u == null ? null : u.getId(), "MENYU_SXEMA", "menu", null, key + ": " + what);
    }

    public void menu(Session s, long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (MenuSchemaService.MenuDef d : MenuSchemaService.MENUS.values())
            rows.add(irow(btn((schema.customized(d.key()) ? "✏️ " : "") + d.title(), "a:mo:" + d.key())));
        String text = "🧩 <b>Меню тартиби</b>\n\n"
                + "Qaysi menyuni sozlaysiz? Har birida tugmalar tartibi, qatordagi ustunlar soni "
                + "va yashirish/yoqish.\n"
                + "<i>✏️ — sozlangan (koddagi standartdan farq qiladi)</i>";
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sup.sendContent(s, chatId, text, inline(rows));
    }

    void card(String key, long chatId, int msgId) {
        MenuSchemaService.MenuDef d = MenuSchemaService.MENUS.get(key);
        if (d == null) return;
        List<String> items = schema.current(key);
        int cols = schema.cols(key);
        StringBuilder sb = new StringBuilder();
        sb.append("🧩 <b>").append(esc(d.title())).append("</b>\n");
        sb.append("Ustunlar: <b>").append(cols).append("</b>")
          .append(schema.customized(key) ? " · ✏️ sozlangan" : " · standart (kod tartibi)").append("\n\n");
        for (int i = 0; i < items.size(); i++) {
            String c = items.get(i);
            sb.append(i + 1).append(". ")
              .append(labelSvc.isHidden(c) ? "🙈 " : "")
              .append(esc(labelSvc.display(c)))
              .append(labelSvc.isRenamed(c) ? " *" : "").append("\n");
        }
        sb.append("\n<i>⬆️⬇️ — o'rnini almashtirish · 🙈 — yashirish, 👁 — qaytarish "
                + "(faqat 🏷 ro'yxatdagilar) · * — nomi o'zgartirilgan.\n"
                + "Yangi tartib foydalanuvchilarda menyu qayta ochilganda (/start) ko'rinadi.</i>");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            String c = items.get(i);
            boolean canHide = LabelService.RENAMABLE.contains(c)
                    && !c.equals(LabelService.PROTECTED_LABEL);
            boolean hidden = labelSvc.isHidden(c);
            String name = (i + 1) + ". " + (hidden ? "🙈 " : "") + labelSvc.display(c);
            // Nom — faqat ko'rsatish (bosilsa hech narsa o'zgarmaydi); yashirish ALOHIDA 🙈/👁 tugmada
            List<InlineKeyboardButton> r = irow(
                    btn(i == 0 ? "·" : "⬆️", i == 0 ? "a:mo:" + key : "a:mou:" + key + ":" + i),
                    btn(i == items.size() - 1 ? "·" : "⬇️",
                            i == items.size() - 1 ? "a:mo:" + key : "a:mod:" + key + ":" + i),
                    btn(name, "a:mo:" + key));
            if (canHide) r.add(btn(hidden ? "👁" : "🙈", "a:moh:" + key + ":" + i));
            rows.add(r);
        }
        rows.add(irow(
                btn((cols == 1 ? "✅ " : "") + "1 ustun", "a:moc:" + key + ":1"),
                btn((cols == 2 ? "✅ " : "") + "2 ustun", "a:moc:" + key + ":2"),
                btn((cols == 3 ? "✅ " : "") + "3 ustun", "a:moc:" + key + ":3")));
        if (schema.customized(key))
            rows.add(irow(btn("♻️ Asl tartibga qaytarish", "a:mor:" + key)));
        rows.add(irow(sup.bk("a:mom")));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }

    private void hideToggle(AppUser u, String key, int idx, long chatId, int msgId) {
        List<String> items = schema.current(key);
        if (idx < 0 || idx >= items.size()) { card(key, chatId, msgId); return; }
        String c = items.get(idx);
        if (!LabelService.RENAMABLE.contains(c) || c.equals(LabelService.PROTECTED_LABEL)) {
            card(key, chatId, msgId);
            return;
        }
        boolean nowHidden = !labelSvc.isHidden(c);
        labelSvc.setHidden(c, nowHidden);
        audit.log(u == null ? null : u.getId(), nowHidden ? "BOLIM_OCHIRILDI" : "BOLIM_YOQILDI",
                "label", null, c);
        card(key, chatId, msgId);
    }
}
