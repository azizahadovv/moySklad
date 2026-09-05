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
 * 🤝 КОНТРАГЕНТ — qarz daftari (Отдел Али TZ bo'yicha, barcha xodimlar uchun):
 *   • Контрагентлар — MoySklad ro'yxatidan qidirish (nom/telefon/INN), balans ko'rish,
 *     qarz eslatmasi qo'shish (summa, muddat, izoh, necha kun oldin eslatish, kimlarga);
 *   • Хабарномалар — o'ziga tegishli eslatmalar ro'yxati (kimga qachon qancha);
 *   • Настройка — o'z otdeliga odam qo'shish (erkin, SuperAdmin'ga xabar),
 *     o'chirish/tahrirlash — SuperAdmin tasdig'i bilan.
 * Har kim faqat O'ZI yaratgan yoki O'ZIGA yuborilgan eslatmalarni ko'radi
 * (SuperAdmin — hammasini).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KontragentHandler {

    private final Sender sender;
    private final MoySkladClient msClient;
    private final ReminderService reminders;

    static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    static final String[] OYLAR = {"Yanvar", "Fevral", "Mart", "Aprel", "May", "Iyun",
            "Iyul", "Avgust", "Sentabr", "Oktabr", "Noyabr", "Dekabr"};
    static final List<Integer> DAY_CHOICES = List.of(1, 2, 3, 5, 7, 10);
    private final KontragentSupport ks;
    private final ReminderViewHandler view;
    private final ReminderWizardHandler wizard;
    private final KontragentStaffHandler staff;


    /* ============================ MATN ============================ */

    public boolean onText(AppUser u, Session s, String text, long chatId) {
        switch (s.state) {
            case KG_SEARCH -> { doSearch(s, text, chatId); return true; }
            case KG_MN_NAME -> { mnName(s, text, chatId); return true; }
            case KG_MN_INFO -> { mnInfo(s, text, chatId); return true; }
            case KG_SUM -> { wizard.wzSum(s, text, chatId); return true; }
            case KG_IZOH -> { wizard.wzIzoh(u, s, text, chatId); return true; }
            case KG_PAY_AMOUNT -> { view.payAmount(u, s, text, chatId); return true; }
            case KG_AU_TGID -> { staff.auTgId(u, s, text, chatId); return true; }
            case KG_AU_NAME -> { staff.auName(u, s, text, chatId); return true; }
            case KG_RN_NAME -> { staff.rnName(u, s, text, chatId); return true; }
            default -> { }
        }
        if (text.equals("🤝 КОНТРАГЕНТ")) {
            s.reset();
            sender.send(chatId, mainText(), mainKb(u));
            return true;
        }
        return false;
    }


    private String mainText() {
        return "🤝 <b>Контрагент</b> — qarz daftari\n\n"
                + "Qarzdorlar bilan ishlash: kimga qachon qancha to'lash yoki "
                + "kimdan olish kerakligini nazorat qilish va eslatmalar olish.";
    }


    private InlineKeyboardMarkup mainKb(AppUser u) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("👥 Контрагентлар (MoySklad)", "kg:s")));
        rows.add(irow(btn("➕ Boshqa shaxs (qo'lda)", "kg:mn")));
        rows.add(irow(btn("🔔 Хабарномалар", "kg:l")));
        if (u.getKassaId() != null) rows.add(irow(btn("⚙️ Настройка (otdelim)", "kg:st")));
        return inline(rows);
    }


    /* ============================ CALLBACK ============================ */

    public boolean onCallback(AppUser u, Session s, String data, long chatId, int msgId) {
        if (!data.startsWith("kg:")) return false;
        String[] p = data.split(":", 3);
        String cmd = p[1];
        String arg = p.length > 2 ? p[2] : "";

        switch (cmd) {
            case "m" -> sender.edit(chatId, msgId, mainText(), mainKb(u));
            case "s" -> {
                s.state = Session.State.KG_SEARCH;
                sender.edit(chatId, msgId, "🔎 Kontragent <b>nomi</b>, <b>telefoni</b> yoki "
                        + "<b>INN</b>ini yozing:", inline(List.of(irow(ks.bk("kg:m")))));
            }
            case "a" -> agentCard(u, s, arg, chatId, msgId);
            case "n" -> wizard.wzStart(s, arg, chatId, msgId);
            case "mn" -> {
                s.state = Session.State.KG_MN_NAME;
                sender.edit(chatId, msgId, "➕ <b>Boshqa shaxs</b> (MoySkladda yo'q)\n\n"
                        + "Ism/nomini yozing:", inline(List.of(irow(ks.bk("kg:m")))));
            }
            case "d" -> wizard.wzDirection(s, arg, chatId, msgId);
            case "c" -> wizard.calCb(s, arg, chatId, msgId);
            case "r" -> wizard.wzDayToggle(s, arg, chatId, msgId);
            case "rk" -> wizard.wzRecipients(u, s, chatId, msgId);
            case "u" -> wizard.wzUserToggle(u, s, arg, chatId, msgId);
            case "uk" -> wizard.wzConfirm(u, s, chatId, msgId);
            case "ok" -> wizard.wzSave(u, s, chatId, msgId);
            case "l" -> view.list(u, s, chatId, msgId);
            case "v" -> view.card(u, Long.parseLong(arg), chatId, msgId);
            case "f" -> view.closeRem(u, Long.parseLong(arg), true, chatId, msgId);
            case "x" -> view.closeRem(u, Long.parseLong(arg), false, chatId, msgId);
            case "pw" -> view.payStart(s, Long.parseLong(arg), chatId, msgId);
            case "pa" -> view.payDecide(u, arg, true, chatId, msgId);
            case "pr" -> view.payDecide(u, arg, false, chatId, msgId);
            case "st" -> staff.staffMenu(u, chatId, msgId);
            case "sa" -> staff.staffAddStart(u, s, chatId, msgId);
            case "sg" -> staff.staffGuest(s, arg, chatId, msgId);
            case "sl" -> staff.staffList(u, chatId, msgId);
            case "rd" -> staff.staffDelRequest(u, Long.parseLong(arg), chatId, msgId);
            case "rn" -> staff.staffRenStart(s, Long.parseLong(arg), chatId, msgId);
            case "apd" -> staff.staffDelApprove(u, arg, chatId, msgId);
            case "apr" -> staff.staffRenApprove(u, arg, chatId, msgId);
            case "rjd", "rjr" -> staff.staffReject(u, arg, chatId, msgId);
            default -> { return false; }
        }
        return true;
    }


    /* ==================== 👥 KONTRAGENT QIDIRUV ==================== */

    private void doSearch(Session s, String text, long chatId) {
        s.state = Session.State.IDLE;
        List<MoySkladClient.MsAgent> found = msClient.searchAgents(text, 10);
        if (found.isEmpty()) {
            s.state = Session.State.KG_SEARCH;
            sender.send(chatId, "😕 «" + esc(text) + "» bo'yicha kontragent topilmadi.\n"
                    + "Boshqa nom/telefon/INN yozing:", inline(List.of(irow(ks.bk("kg:m")))));
            return;
        }
        Map<String, MoySkladClient.MsAgent> cache = new java.util.HashMap<>();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (MoySkladClient.MsAgent a : found) {
            cache.put(a.id(), a);
            String label = a.name();
            if (!a.phone().isBlank()) label += " · " + a.phone();
            else if (!a.inn().isBlank()) label += " · INN " + a.inn();
            if (label.length() > 60) label = label.substring(0, 60);
            rows.add(irow(btn(label, "kg:a:" + a.id())));
        }
        rows.add(irow(ks.bk("kg:m")));
        s.data.put("kgAgents", cache);
        sender.send(chatId, "🔎 Topildi: <b>" + found.size() + "</b> ta. Kontragentni tanlang:",
                inline(rows));
    }


    private void agentCard(AppUser u, Session s, String id, long chatId, int msgId) {
        MoySkladClient.MsAgent a = ks.cachedAgent(s, id);
        if (a == null) {
            s.state = Session.State.KG_SEARCH;
            sender.edit(chatId, msgId, "🔎 Qidiruv eskirgan — kontragent nomini qayta yozing:");
            return;
        }
        StringBuilder sb = new StringBuilder("🤝 <b>" + esc(a.name()) + "</b>\n");
        if (!a.phone().isBlank()) sb.append("📞 ").append(esc(a.phone())).append("\n");
        if (!a.inn().isBlank()) sb.append("🧾 INN: ").append(esc(a.inn())).append("\n");

        Long bal = msClient.fetchAgentBalanceSom(id);
        if (bal == null) sb.append("\n💼 MoySklad balansi: <i>olinmadi</i>\n");
        else if (bal < 0) sb.append("\n💼 MoySklad balansi: <b>").append(fmt(-bal))
                .append("</b> so'm — 🟢 kontragent BIZGA qarzdor\n");
        else if (bal > 0) sb.append("\n💼 MoySklad balansi: <b>").append(fmt(bal))
                .append("</b> so'm — 🔴 biz kontragentga QARZDORMIZ\n");
        else sb.append("\n💼 MoySklad balansi: <b>0</b> so'm\n");

        List<Reminder> act = reminders.activeForAgent(id);
        if (!act.isEmpty()) {
            sb.append("\n🔔 <b>Faol eslatmalar (").append(act.size()).append(" ta):</b>\n");
            for (Reminder r : act) {
                sb.append("• ").append(fmt(r.getAmount())).append(" so'm — ")
                  .append(r.getDueDate().format(DF));
                if (r.getRepaid() > 0)
                    sb.append(" (to'landi ").append(fmt(r.getRepaid()))
                      .append(" · qoldiq ").append(fmt(r.remain())).append(")");
                sb.append("\n");
            }
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("➕ Qarz eslatmasi qo'shish", "kg:n:" + id)));
        for (Reminder r : act)
            rows.add(irow(btn("📄 #" + r.getId() + " · " + fmt(r.remain()) + " · "
                    + r.getDueDate().format(DF), "kg:v:" + r.getId())));
        rows.add(irow(ks.bk("kg:m")));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }


    /* ==================== ➕ QO'LDA SHAXS ==================== */

    private void mnName(Session s, String text, long chatId) {
        if (text.length() < 2) { sender.send(chatId, "⚠️ Ism juda qisqa, qaytadan yozing:"); return; }
        s.data.put("kgAgName", text.trim());
        s.state = Session.State.KG_MN_INFO;
        sender.send(chatId, "📞 Telefon yoki qo'shimcha ma'lumot yozing "
                + "(bo'lmasa «-» yuboring):");
    }


    private void mnInfo(Session s, String text, long chatId) {
        s.data.put("kgAgInfo", text.equals("-") ? "" : text.trim());
        s.data.remove("kgAgId");
        wizard.askDirection(s, chatId, 0);
    }

}
