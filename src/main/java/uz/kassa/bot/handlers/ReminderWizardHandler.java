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
 * Yangi eslatma yaratish ustasi: yo'nalish, summa, muddat (kalendar), izoh, eslatish kunlari, qabul qiluvchilar, tasdiq.
 * (KontragentHandler dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
public class ReminderWizardHandler {

    private final Sender sender;
    private final ReminderService reminders;
    private final AppUserRepo userRepo;
    private final NotificationService notify;
    private final AuditService audit;
    private final KontragentSupport ks;


    /* ==================== ➕ ESLATMA WIZARD ==================== */

    void wzStart(Session s, String agentId, long chatId, int msgId) {
        MoySkladClient.MsAgent a = ks.cachedAgent(s, agentId);
        if (a == null) {
            s.state = Session.State.KG_SEARCH;
            sender.edit(chatId, msgId, "🔎 Qidiruv eskirgan — kontragent nomini qayta yozing:");
            return;
        }
        s.data.put("kgAgId", agentId);
        s.data.put("kgAgName", a.name());
        s.data.put("kgAgInfo", a.phone().isBlank() ? a.inn() : a.phone());
        askDirection(s, chatId, msgId);
    }


    void askDirection(Session s, long chatId, int msgId) {
        String text = "🤝 <b>" + esc(s.getStr("kgAgName")) + "</b>\n\nQarz yo'nalishini tanlang:";
        InlineKeyboardMarkup kb = inline(List.of(
                irow(btn("🔴 Биз қарздормиз (тўлаймиз)", "kg:d:B")),
                irow(btn("🟢 У биздан қарздор (оламиз)", "kg:d:U")),
                irow(btn("❌ Bekor", "cx"))));
        if (msgId > 0) sender.edit(chatId, msgId, text, kb);
        else sender.send(chatId, text, kb);
    }


    void wzDirection(Session s, String arg, long chatId, int msgId) {
        if (s.getStr("kgAgName") == null) return;
        s.data.put("kgDir", arg.equals("B") ? "BIZ_QARZDOR" : "U_QARZDOR");
        s.state = Session.State.KG_SUM;
        sender.edit(chatId, msgId, "💰 <b>Qarz summasini kiriting</b> (so'm):");
    }


    void wzSum(Session s, String text, long chatId) {
        long sum = parseAmount(text);
        if (sum <= 0) { sender.send(chatId, "⚠️ Musbat summa kiriting:"); return; }
        s.data.put("kgSum", sum);
        s.state = Session.State.IDLE;
        calShow(s, chatId, 0, YearMonth.from(ks.today()), null);
    }


    /* ---------- muddat kalendari (kelajak sanasi tanlanadi) ---------- */

    void calShow(Session s, long chatId, int msgId, YearMonth ym, String warn) {
        String title = "📅 <b>Qaytarish muddatini tanlang:</b>"
                + (warn == null ? "" : "\n" + warn);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("‹", "kg:c:n." + ym.minusMonths(1)),
                btn(OYLAR[ym.getMonthValue() - 1] + " " + ym.getYear(), "kg:c:z"),
                btn("›", "kg:c:n." + ym.plusMonths(1))));
        rows.add(irow(btn("Du", "kg:c:z"), btn("Se", "kg:c:z"), btn("Ch", "kg:c:z"),
                btn("Pa", "kg:c:z"), btn("Ju", "kg:c:z"), btn("Sh", "kg:c:z"), btn("Ya", "kg:c:z")));
        int shift = ym.atDay(1).getDayOfWeek().getValue() - 1;
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (int i = 0; i < shift; i++) row.add(btn("⠀", "kg:c:z"));
        for (int day = 1; day <= ym.lengthOfMonth(); day++) {
            LocalDate d = ym.atDay(day);
            String label = d.equals(ks.today()) ? "·" + day + "·" : String.valueOf(day);
            row.add(btn(label, "kg:c:d." + d.toEpochDay()));
            if (row.size() == 7) { rows.add(row); row = new ArrayList<>(); }
        }
        if (!row.isEmpty()) {
            while (row.size() < 7) row.add(btn("⠀", "kg:c:z"));
            rows.add(row);
        }
        rows.add(irow(btn("❌ Bekor", "cx")));
        if (msgId > 0) sender.edit(chatId, msgId, title, inline(rows));
        else sender.send(chatId, title, inline(rows));
    }


    /** kg:c:<op>.<val> — z: bo'sh, n: oy, d: kun (muddat — bugundan oldin bo'lmasin). */
    void calCb(Session s, String arg, long chatId, int msgId) {
        if (arg.equals("z")) return;
        int dot = arg.indexOf('.');
        String op = dot < 0 ? arg : arg.substring(0, dot);
        String val = dot < 0 ? "" : arg.substring(dot + 1);
        switch (op) {
            case "n" -> calShow(s, chatId, msgId, YearMonth.parse(val), null);
            case "d" -> {
                LocalDate d = LocalDate.ofEpochDay(Long.parseLong(val));
                if (d.isBefore(ks.today())) {
                    calShow(s, chatId, msgId, YearMonth.from(d), "⚠️ O'tgan sana bo'lmaydi.");
                    return;
                }
                if (s.data.get("kgSum") == null) return;
                s.data.put("kgDue", d.toString());
                s.state = Session.State.KG_IZOH;
                sender.edit(chatId, msgId, "📅 Muddat: <b>" + d.format(DF) + "</b>\n\n"
                        + "💬 <b>Izoh yozing</b> (bo'lmasa «-» yuboring):");
            }
        }
    }


    void wzIzoh(AppUser u, Session s, String text, long chatId) {
        s.data.put("kgIzoh", text.equals("-") ? "" : text.trim());
        s.state = Session.State.IDLE;
        s.data.put("kgDays", new java.util.TreeSet<Integer>());
        sendDays(s, chatId, 0);
    }


    @SuppressWarnings("unchecked")
    java.util.Set<Integer> daySel(Session s) {
        return (java.util.Set<Integer>) s.data.computeIfAbsent("kgDays",
                k -> new java.util.TreeSet<Integer>());
    }


    void sendDays(Session s, long chatId, int msgId) {
        java.util.Set<Integer> sel = daySel(s);
        List<InlineKeyboardButton> r1 = new ArrayList<>(), r2 = new ArrayList<>();
        for (int i = 0; i < DAY_CHOICES.size(); i++) {
            int d = DAY_CHOICES.get(i);
            InlineKeyboardButton b = btn((sel.contains(d) ? "✅ " : "") + d + " kun", "kg:r:" + d);
            if (i < 3) r1.add(b); else r2.add(b);
        }
        String text = "🔔 <b>Necha kun OLDIN eslatilsin?</b>\n"
                + "Bir nechtasini tanlash mumkin. Muddat kunining o'zida "
                + "har doim eslatiladi.\nTanlangan: <b>"
                + (sel.isEmpty() ? "yo'q" : sel.toString()) + "</b>";
        InlineKeyboardMarkup kb = inline(List.of(r1, r2,
                irow(btn("➡️ Davom etish", "kg:rk")),
                irow(btn("❌ Bekor", "cx"))));
        if (msgId > 0) sender.edit(chatId, msgId, text, kb);
        else sender.send(chatId, text, kb);
    }


    void wzDayToggle(Session s, String arg, long chatId, int msgId) {
        if (s.data.get("kgDue") == null) return;
        int d = Integer.parseInt(arg);
        java.util.Set<Integer> sel = daySel(s);
        if (!sel.remove(d)) sel.add(d);
        sendDays(s, chatId, msgId);
    }


    @SuppressWarnings("unchecked")
    java.util.Set<Long> userSel(Session s) {
        return (java.util.Set<Long>) s.data.computeIfAbsent("kgUsers",
                k -> new java.util.LinkedHashSet<Long>());
    }


    void wzRecipients(AppUser u, Session s, long chatId, int msgId) {
        if (s.data.get("kgDue") == null) return;
        java.util.Set<Long> sel = userSel(s);
        if (s.data.get("kgUsersInit") == null) {
            sel.add(u.getId());   // o'zi standart tanlangan — xohlasa olib tashlaydi
            s.data.put("kgUsersInit", true);
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<AppUser> users = userRepo.findByActiveTrueOrderByRoleAscIdAsc().stream()
                .filter(x -> x.getTelegramId() != null).toList();
        for (int i = 0; i < users.size(); i += 2) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(userBtn(users.get(i), u, sel));
            if (i + 1 < users.size()) r.add(userBtn(users.get(i + 1), u, sel));
            rows.add(r);
        }
        rows.add(irow(btn("✅ Tasdiqlash", "kg:uk")));
        rows.add(irow(btn("❌ Bekor", "cx")));
        sender.edit(chatId, msgId, "👥 <b>Kimlarga xabar borsin?</b>\n"
                + "Bir yoki bir nechtasini tanlang (o'zingizni ham qoldirishingiz mumkin — "
                + "faqat o'zingizga kelishi uchun boshqalarni olib tashlang):", inline(rows));
    }


    InlineKeyboardButton userBtn(AppUser x, AppUser me, java.util.Set<Long> sel) {
        String name = x.getId().equals(me.getId()) ? "O'zim (" + x.getFullName() + ")" : x.getFullName();
        if (name.length() > 28) name = name.substring(0, 28);
        return btn((sel.contains(x.getId()) ? "✅ " : "") + name, "kg:u:" + x.getId());
    }


    void wzUserToggle(AppUser u, Session s, String arg, long chatId, int msgId) {
        if (s.data.get("kgDue") == null) return;
        long id = Long.parseLong(arg);
        java.util.Set<Long> sel = userSel(s);
        if (!sel.remove(id)) sel.add(id);
        wzRecipients(u, s, chatId, msgId);
    }


    void wzConfirm(AppUser u, Session s, long chatId, int msgId) {
        if (s.data.get("kgDue") == null) return;
        if (userSel(s).isEmpty()) {
            wzRecipients(u, s, chatId, msgId);
            return;
        }
        Reminder r = buildFrom(u, s);
        sender.edit(chatId, msgId, "📋 <b>Tasdiqlang:</b>\n\n" + reminders.render(r, true),
                inline(List.of(
                        irow(btn("✅ Saqlash", "kg:ok")),
                        irow(btn("❌ Bekor", "cx")))));
    }


    Reminder buildFrom(AppUser u, Session s) {
        return Reminder.builder()
                .creatorUserId(u.getId())
                .agentMsId(s.getStr("kgAgId"))
                .agentName(s.getStr("kgAgName"))
                .agentInfo(s.getStr("kgAgInfo"))
                .direction("BIZ_QARZDOR".equals(s.getStr("kgDir"))
                        ? Reminder.Direction.BIZ_QARZDOR : Reminder.Direction.U_QARZDOR)
                .amount(s.getLong("kgSum"))
                .dueDate(LocalDate.parse(s.getStr("kgDue")))
                .comment(s.getStr("kgIzoh"))
                .remindDays(daySel(s).stream().map(String::valueOf)
                        .reduce((a, b) -> a + "," + b).orElse(""))
                .recipients(userSel(s).stream().map(String::valueOf)
                        .reduce((a, b) -> a + "," + b).orElse(""))
                .build();
    }


    void wzSave(AppUser u, Session s, long chatId, int msgId) {
        if (s.data.get("kgDue") == null) return;
        Reminder r = reminders.save(buildFrom(u, s));
        audit.log(u.getId(), "ESLATMA_QOSHILDI", "reminder", r.getId(),
                r.getAgentName() + " " + r.getAmount() + " " + r.getDueDate());
        String saved = "✅ <b>Eslatma #" + r.getId() + " saqlandi</b>\n\n" + reminders.render(r, true);
        sender.edit(chatId, msgId, saved);

        // Tanlangan oluvchilarga darhol xabar (yaratuvchidan tashqari)
        String intro = "🆕 Sizga yangi qarz eslatmasi biriktirildi:\n\n" + reminders.render(r, false)
                + "\n✍️ Kiritgan: " + esc(u.getFullName());
        for (Long uid : r.recipientSet())
            if (!uid.equals(u.getId()))
                userRepo.findById(uid).ifPresent(x -> notify.toUser(x.getTelegramId(), intro));

        s.reset();
    }

}
