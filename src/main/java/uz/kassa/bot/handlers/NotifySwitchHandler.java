package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.Sender;
import uz.kassa.bot.Session;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Role;
import uz.kassa.service.AuditService;
import uz.kassa.service.NotifySwitches;
import uz.kassa.service.NotifySwitches.Aud;
import uz.kassa.service.NotifySwitches.Sw;

import java.util.ArrayList;
import java.util.List;

import static uz.kassa.bot.Keyboards.btn;
import static uz.kassa.bot.Keyboards.inline;
import static uz.kassa.bot.Keyboards.irow;
import static uz.kassa.bot.TextUtil.esc;

/**
 * 🔕 Хабарномалар — Настройка ichida: bot o'zi yuboradigan avtomatik xabarlarni tur va auditoriya bo'yicha
 * yoqish/o'chirish. a:ns — guruhlar, a:nsg:N — guruh, a:nsc:KOD — kalit kartasi, a:nst:KOD — turni yoqish/o'chirish,
 * a:nsu:KOD:AUD — auditoriya, a:nsa:N:1|0 — guruh butunlay.
 */
@Component
@RequiredArgsConstructor
public class NotifySwitchHandler {

    public static final String LABEL = "🔕 Хабарномалар";

    private final Sender sender;
    private final NotifySwitches sw;
    private final AuditService audit;
    private final AdminSupport sup;


    public boolean onCallback(AppUser u, Session s, String cmd, String arg, long chatId, int msgId) {
        if (u.getRole() != Role.SUPERADMIN) { sender.send(chatId, "⚠️ Faqat SuperAdmin uchun."); return true; }
        List<String> groups = NotifySwitches.groups();
        switch (cmd) {
            case "ns" -> menu(s, chatId, msgId);
            case "nsg" -> group(idx(arg, groups.size()), chatId, msgId);
            case "nsc" -> {
                Sw x = NotifySwitches.find(arg);
                if (x == null) menu(s, chatId, msgId); else card(x, chatId, msgId);
            }
            case "nst" -> {
                Sw x = NotifySwitches.find(arg);
                if (x == null) { menu(s, chatId, msgId); return true; }
                sw.toggle(arg);
                audit.log(u.getId(), "XABARNOMA_KALIT", "settings", null, arg + "=" + (sw.on(arg) ? "on" : "off"));
                card(x, chatId, msgId);
            }
            case "nsu" -> {
                String[] p = arg.split(":");
                Sw x = NotifySwitches.find(p[0]);
                Aud a = null;
                try { if (p.length > 1) a = Aud.valueOf(p[1]); } catch (Exception ignored) { }
                if (x == null || a == null) { menu(s, chatId, msgId); return true; }
                sw.toggleAud(x.code(), a);
                audit.log(u.getId(), "XABARNOMA_AUD", "settings", null, x.code() + ":" + a.name() + "=" + (sw.on(x.code(), a) ? "on" : "off"));
                card(x, chatId, msgId);
            }
            case "nsa" -> {
                String[] p = arg.split(":");
                int gi = idx(p[0], groups.size());
                boolean on = p.length > 1 && "1".equals(p[1]);
                sw.setGroup(groups.get(gi), on);
                audit.log(u.getId(), "XABARNOMA_GURUH", "settings", null, groups.get(gi) + "=" + (on ? "on" : "off"));
                group(gi, chatId, msgId);
            }
            default -> { return false; }
        }
        return true;
    }

    /** Reply-menyu yo'lidan (msgId=0) ham TUGMALAR bilan — sup.show() 0 da inline'siz yuboradi, bu bo'limda tugmasiz ma'no yo'q. */
    private void show(long chatId, int msgId, String text, List<List<InlineKeyboardButton>> rows) {
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sender.send(chatId, text, inline(rows));
    }

    private static int idx(String arg, int size) {
        try { int i = Integer.parseInt(arg); return i < 0 || i >= size ? 0 : i; }
        catch (Exception e) { return 0; }
    }


    /** Guruhlar ro'yxati (reply-menyu yo'lidan msgId=0 bilan ham chaqiriladi). */
    public void menu(Session s, long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<String> groups = NotifySwitches.groups();
        for (int i = 0; i < groups.size(); i++) {
            String g = groups.get(i);
            int on = sw.onCount(g), all = NotifySwitches.inGroup(g).size();
            String st = on == all ? "✅" : on == 0 ? "🔕" : "◐";
            rows.add(irow(btn(st + " " + g + " · " + on + "/" + all, "a:nsg:" + i)));
        }
        rows.add(irow(sup.bk("a:p:set")));
        int off = sw.offTotal();
        show(chatId, msgId, "🔕 <b>Хабарномалар</b>\n\n"
                + "Bot O'ZI yuboradigan avtomatik xabarlarni tur bo'yicha va KIMGA borishi bo'yicha yoqish/o'chirish: "
                + "👑 Admin · 🧮 Buxgalter · 👔 Rahbar · 👤 Xodim.\n"
                + "O'chirilganda hisob-kitob (audit, status, eskalatsiya vaqti) baribir yuradi — faqat Telegram'ga xabar ketmaydi.\n\n"
                + "ℹ️ 🔔 Билдиришномалар (shablonlar) bunga kirmaydi — ular o'z ro'yxatida yoqiladi/o'chiriladi.\n\n"
                + (off == 0 ? "Hozir barcha turlar yoqiq." : "O'chirilgan turlar: <b>" + off + "</b> ta.")
                + "\n\n✅ — hammasi yoqiq · ◐ — qisman · 🔕 — hammasi o'chiq. Guruhni tanlang:", rows);
    }


    private void group(int gi, long chatId, int msgId) {
        List<String> groups = NotifySwitches.groups();
        String g = groups.get(gi);
        List<Sw> list = NotifySwitches.inGroup(g);
        StringBuilder sb = new StringBuilder("🔕 <b>" + esc(g) + "</b>\n\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Sw x : list) {
            boolean on = sw.on(x.code());
            String st = !on ? "▫️" : sw.partial(x) ? "◐" : "✅";
            sb.append(st).append(" <b>").append(esc(x.title())).append("</b> — ").append(esc(x.desc()));
            if (on && !x.auds().isEmpty()) sb.append(" · ").append(sw.audLine(x));
            sb.append("\n");
            rows.add(irow(btn(st + " " + x.title() + (on && sw.partial(x) ? " · " + sw.audLine(x) : ""), "a:nsc:" + x.code())));
        }
        rows.add(irow(btn("✅ Hammasini yoqish", "a:nsa:" + gi + ":1"), btn("🔕 Hammasini o'chirish", "a:nsa:" + gi + ":0")));
        rows.add(irow(sup.bk("a:ns")));
        sb.append("\n✅ — yuboriladi · ◐ — ba'zi auditoriyaga · ▫️ — yuborilmaydi. Kalitni bosib sozlang:");
        show(chatId, msgId, sb.toString(), rows);
    }


    /** Bitta kalit: tur ON/OFF + auditoriyalar (faqat shu xabar aslida boradiganlari). */
    private void card(Sw x, long chatId, int msgId) {
        boolean on = sw.on(x.code());
        int gi = NotifySwitches.groups().indexOf(x.group());
        StringBuilder sb = new StringBuilder("🔕 <b>" + esc(x.title()) + "</b>\n" + esc(x.group()) + "\n\n" + esc(x.desc()) + "\n\n");
        sb.append(on ? "Holat: <b>✅ yoqiq</b>" : "Holat: <b>▫️ o'chiq</b> — hech kimga ketmaydi");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn(on ? "🔕 Turni o'chirish" : "✅ Turni yoqish", "a:nst:" + x.code())));
        if (x.auds().isEmpty()) {
            sb.append("\n\nBu xabar guruh/kanalga boradi — auditoriya bo'yicha bo'linmaydi.");
        } else {
            sb.append("\n\n<b>Kimga boradi</b> (bosib yoqing/o'chiring):\n");
            List<InlineKeyboardButton> row = new ArrayList<>();
            for (Aud a : x.auds()) {
                boolean aon = sw.on(x.code(), a);
                sb.append(aon ? "✅ " : "▫️ ").append(a.emoji).append(" ").append(a.title).append("\n");
                row.add(btn((aon ? "✅ " : "▫️ ") + a.emoji + " " + a.title, "a:nsu:" + x.code() + ":" + a.name()));
                if (row.size() == 2) { rows.add(row); row = new ArrayList<>(); }
            }
            if (!row.isEmpty()) rows.add(row);
            if (!on) sb.append("<i>Tur o'chiq — auditoriya sozlamalari tur yoqilganda ishlaydi.</i>\n");
            sb.append("\n👑 Admin — SuperAdmin · 🧮 Buxgalter · 👔 Rahbar — otdel rahbari · 👤 Xodim — qolganlar.");
        }
        rows.add(irow(sup.bk("a:nsg:" + Math.max(gi, 0))));
        show(chatId, msgId, sb.toString(), rows);
    }
}
