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

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String[] OYLAR = {"Yanvar", "Fevral", "Mart", "Aprel", "May", "Iyun",
            "Iyul", "Avgust", "Sentabr", "Oktabr", "Noyabr", "Dekabr"};
    private static final List<Integer> DAY_CHOICES = List.of(1, 2, 3, 5, 7, 10);

    private final Sender sender;
    private final MoySkladClient msClient;
    private final ReminderService reminders;
    private final AppUserRepo userRepo;
    private final GuestRepo guestRepo;
    private final SettingsService settings;
    private final NotificationService notify;
    private final AuditService audit;
    private final uz.kassa.config.AppProps props;

    private LocalDate today() { return LocalDate.now(props.zoneId()); }

    private InlineKeyboardButton bk(String data) { return btn("⬅️ Orqaga", data); }

    /* ============================ MATN ============================ */

    public boolean onText(AppUser u, Session s, String text, long chatId) {
        switch (s.state) {
            case KG_SEARCH -> { doSearch(s, text, chatId); return true; }
            case KG_MN_NAME -> { mnName(s, text, chatId); return true; }
            case KG_MN_INFO -> { mnInfo(s, text, chatId); return true; }
            case KG_SUM -> { wzSum(s, text, chatId); return true; }
            case KG_IZOH -> { wzIzoh(u, s, text, chatId); return true; }
            case KG_PAY_AMOUNT -> { payAmount(u, s, text, chatId); return true; }
            case KG_AU_TGID -> { auTgId(u, s, text, chatId); return true; }
            case KG_AU_NAME -> { auName(u, s, text, chatId); return true; }
            case KG_RN_NAME -> { rnName(u, s, text, chatId); return true; }
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
                        + "<b>INN</b>ini yozing:", inline(List.of(irow(bk("kg:m")))));
            }
            case "a" -> agentCard(u, s, arg, chatId, msgId);
            case "n" -> wzStart(s, arg, chatId, msgId);
            case "mn" -> {
                s.state = Session.State.KG_MN_NAME;
                sender.edit(chatId, msgId, "➕ <b>Boshqa shaxs</b> (MoySkladda yo'q)\n\n"
                        + "Ism/nomini yozing:", inline(List.of(irow(bk("kg:m")))));
            }
            case "d" -> wzDirection(s, arg, chatId, msgId);
            case "c" -> calCb(s, arg, chatId, msgId);
            case "r" -> wzDayToggle(s, arg, chatId, msgId);
            case "rk" -> wzRecipients(u, s, chatId, msgId);
            case "u" -> wzUserToggle(u, s, arg, chatId, msgId);
            case "uk" -> wzConfirm(u, s, chatId, msgId);
            case "ok" -> wzSave(u, s, chatId, msgId);
            case "l" -> list(u, s, chatId, msgId);
            case "v" -> card(u, Long.parseLong(arg), chatId, msgId);
            case "f" -> closeRem(u, Long.parseLong(arg), true, chatId, msgId);
            case "x" -> closeRem(u, Long.parseLong(arg), false, chatId, msgId);
            case "pw" -> payStart(s, Long.parseLong(arg), chatId, msgId);
            case "pa" -> payDecide(u, arg, true, chatId, msgId);
            case "pr" -> payDecide(u, arg, false, chatId, msgId);
            case "st" -> staffMenu(u, chatId, msgId);
            case "sa" -> staffAddStart(u, s, chatId, msgId);
            case "sg" -> staffGuest(s, arg, chatId, msgId);
            case "sl" -> staffList(u, chatId, msgId);
            case "rd" -> staffDelRequest(u, Long.parseLong(arg), chatId, msgId);
            case "rn" -> staffRenStart(s, Long.parseLong(arg), chatId, msgId);
            case "apd" -> staffDelApprove(u, arg, chatId, msgId);
            case "apr" -> staffRenApprove(u, arg, chatId, msgId);
            case "rjd", "rjr" -> staffReject(u, arg, chatId, msgId);
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
                    + "Boshqa nom/telefon/INN yozing:", inline(List.of(irow(bk("kg:m")))));
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
        rows.add(irow(bk("kg:m")));
        s.data.put("kgAgents", cache);
        sender.send(chatId, "🔎 Topildi: <b>" + found.size() + "</b> ta. Kontragentni tanlang:",
                inline(rows));
    }

    private MoySkladClient.MsAgent cachedAgent(Session s, String id) {
        Object o = s.data.get("kgAgents");
        if (o instanceof Map<?, ?> m && m.get(id) instanceof MoySkladClient.MsAgent a) return a;
        return null;
    }

    private void agentCard(AppUser u, Session s, String id, long chatId, int msgId) {
        MoySkladClient.MsAgent a = cachedAgent(s, id);
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
        rows.add(irow(bk("kg:m")));
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
        askDirection(s, chatId, 0);
    }

    /* ==================== ➕ ESLATMA WIZARD ==================== */

    private void wzStart(Session s, String agentId, long chatId, int msgId) {
        MoySkladClient.MsAgent a = cachedAgent(s, agentId);
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

    private void askDirection(Session s, long chatId, int msgId) {
        String text = "🤝 <b>" + esc(s.getStr("kgAgName")) + "</b>\n\nQarz yo'nalishini tanlang:";
        InlineKeyboardMarkup kb = inline(List.of(
                irow(btn("🔴 Биз қарздормиз (тўлаймиз)", "kg:d:B")),
                irow(btn("🟢 У биздан қарздор (оламиз)", "kg:d:U")),
                irow(btn("❌ Bekor", "cx"))));
        if (msgId > 0) sender.edit(chatId, msgId, text, kb);
        else sender.send(chatId, text, kb);
    }

    private void wzDirection(Session s, String arg, long chatId, int msgId) {
        if (s.getStr("kgAgName") == null) return;
        s.data.put("kgDir", arg.equals("B") ? "BIZ_QARZDOR" : "U_QARZDOR");
        s.state = Session.State.KG_SUM;
        sender.edit(chatId, msgId, "💰 <b>Qarz summasini kiriting</b> (so'm):");
    }

    private void wzSum(Session s, String text, long chatId) {
        long sum = parseAmount(text);
        if (sum <= 0) { sender.send(chatId, "⚠️ Musbat summa kiriting:"); return; }
        s.data.put("kgSum", sum);
        s.state = Session.State.IDLE;
        calShow(s, chatId, 0, YearMonth.from(today()), null);
    }

    /* ---------- muddat kalendari (kelajak sanasi tanlanadi) ---------- */

    private void calShow(Session s, long chatId, int msgId, YearMonth ym, String warn) {
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
            String label = d.equals(today()) ? "·" + day + "·" : String.valueOf(day);
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
    private void calCb(Session s, String arg, long chatId, int msgId) {
        if (arg.equals("z")) return;
        int dot = arg.indexOf('.');
        String op = dot < 0 ? arg : arg.substring(0, dot);
        String val = dot < 0 ? "" : arg.substring(dot + 1);
        switch (op) {
            case "n" -> calShow(s, chatId, msgId, YearMonth.parse(val), null);
            case "d" -> {
                LocalDate d = LocalDate.ofEpochDay(Long.parseLong(val));
                if (d.isBefore(today())) {
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

    private void wzIzoh(AppUser u, Session s, String text, long chatId) {
        s.data.put("kgIzoh", text.equals("-") ? "" : text.trim());
        s.state = Session.State.IDLE;
        s.data.put("kgDays", new java.util.TreeSet<Integer>());
        sendDays(s, chatId, 0);
    }

    @SuppressWarnings("unchecked")
    private java.util.Set<Integer> daySel(Session s) {
        return (java.util.Set<Integer>) s.data.computeIfAbsent("kgDays",
                k -> new java.util.TreeSet<Integer>());
    }

    private void sendDays(Session s, long chatId, int msgId) {
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

    private void wzDayToggle(Session s, String arg, long chatId, int msgId) {
        if (s.data.get("kgDue") == null) return;
        int d = Integer.parseInt(arg);
        java.util.Set<Integer> sel = daySel(s);
        if (!sel.remove(d)) sel.add(d);
        sendDays(s, chatId, msgId);
    }

    @SuppressWarnings("unchecked")
    private java.util.Set<Long> userSel(Session s) {
        return (java.util.Set<Long>) s.data.computeIfAbsent("kgUsers",
                k -> new java.util.LinkedHashSet<Long>());
    }

    private void wzRecipients(AppUser u, Session s, long chatId, int msgId) {
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

    private InlineKeyboardButton userBtn(AppUser x, AppUser me, java.util.Set<Long> sel) {
        String name = x.getId().equals(me.getId()) ? "O'zim (" + x.getFullName() + ")" : x.getFullName();
        if (name.length() > 28) name = name.substring(0, 28);
        return btn((sel.contains(x.getId()) ? "✅ " : "") + name, "kg:u:" + x.getId());
    }

    private void wzUserToggle(AppUser u, Session s, String arg, long chatId, int msgId) {
        if (s.data.get("kgDue") == null) return;
        long id = Long.parseLong(arg);
        java.util.Set<Long> sel = userSel(s);
        if (!sel.remove(id)) sel.add(id);
        wzRecipients(u, s, chatId, msgId);
    }

    private void wzConfirm(AppUser u, Session s, long chatId, int msgId) {
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

    private Reminder buildFrom(AppUser u, Session s) {
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

    private void wzSave(AppUser u, Session s, long chatId, int msgId) {
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

    /* ==================== 🔔 ХАБАРНОМАЛАР ==================== */

    private void list(AppUser u, Session s, long chatId, int msgId) {
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
        rows.add(irow(bk("kg:m")));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }

    private void card(AppUser u, long id, long chatId, int msgId) {
        Reminder r = reminders.activeAll().stream()
                .filter(x -> x.getId().equals(id)).findFirst().orElse(null);
        if (r == null) {
            sender.edit(chatId, msgId, "⚠️ Eslatma topilmadi yoki allaqachon yopilgan",
                    inline(List.of(irow(bk("kg:l")))));
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
        rows.add(irow(bk("kg:l")));
        sender.edit(chatId, msgId, "📄 <b>Eslatma #" + id + "</b>\n\n"
                + reminders.render(r, true), inline(rows));
    }

    private void closeRem(AppUser u, long id, boolean done, long chatId, int msgId) {
        Reminder r = reminders.close(id, u, done);
        String st = done ? "✅ BAJARILDI deb yopildi" : "🚫 BEKOR qilindi";
        sender.edit(chatId, msgId, "📄 Eslatma #" + id + " — " + st + "\n\n"
                + reminders.render(r, false), inline(List.of(irow(bk("kg:l")))));
        String text = "ℹ️ Eslatma #" + id + " (" + esc(r.getAgentName()) + " — "
                + fmt(r.getAmount()) + " so'm) " + st + ".\n✍️ " + esc(u.getFullName());
        for (Long uid : r.recipientSet())
            if (!uid.equals(u.getId()))
                userRepo.findById(uid).ifPresent(x -> notify.toUser(x.getTelegramId(), text));
    }

    /* ==================== 💵 QISMAN TO'LOV ==================== */

    private void payStart(Session s, long id, long chatId, int msgId) {
        Reminder r = reminders.find(id).orElse(null);
        if (r == null) { sender.edit(chatId, msgId, "⚠️ Eslatma topilmadi yoki allaqachon yopilgan."); return; }
        s.state = Session.State.KG_PAY_AMOUNT;
        s.data.put("kgPayId", id);
        sender.edit(chatId, msgId, "💵 <b>Qisman to'lov</b>\n\nQoldiq: <b>" + fmt(r.remain())
                + "</b> so'm\n\nTo'langan summani kiriting:", inline(List.of(irow(bk("kg:v:" + id)))));
    }

    private void payAmount(AppUser u, Session s, String text, long chatId) {
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
    private void payDecide(AppUser admin, String arg, boolean approve, long chatId, int msgId) {
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

    /* ==================== ⚙️ НАСТРОЙКА (o'z otdeli) ==================== */

    private void staffMenu(AppUser u, long chatId, int msgId) {
        if (u.getKassaId() == null) return;
        sender.edit(chatId, msgId, "⚙️ <b>Настройка</b> — otdelim xodimlari\n\n"
                + "➕ Qo'shish darhol kuchga kiradi (SuperAdmin'ga xabar boradi).\n"
                + "🚫 O'chirish va ✏️ tahrirlash — SuperAdmin tasdig'i bilan.", inline(List.of(
                irow(btn("➕ Одам қўшиш", "kg:sa")),
                irow(btn("👥 Отделим ходимлари", "kg:sl")),
                irow(bk("kg:m")))));
    }

    private void staffAddStart(AppUser u, Session s, long chatId, int msgId) {
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
        rows.add(irow(bk("kg:st")));
        s.state = Session.State.KG_AU_TGID;
        sender.edit(chatId, msgId, "➕ <b>Одам қўшиш</b> — otdelingizga kassir sifatida\n\n"
                + (guests.isEmpty() ? "" : "Botga yozganlar ro'yxatidan tanlang yoki ")
                + "Telegram ID raqamini yozib yuboring:", inline(rows));
    }

    private void staffGuest(Session s, String arg, long chatId, int msgId) {
        s.data.put("kgStTg", Long.parseLong(arg));
        s.state = Session.State.KG_AU_NAME;
        sender.edit(chatId, msgId, "Tanlandi: <code>" + arg + "</code>\n\n"
                + "✍️ To'liq ism-familiyasini yozing:");
    }

    private void auTgId(AppUser u, Session s, String text, long chatId) {
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

    private void auName(AppUser u, Session s, String text, long chatId) {
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

    private void staffList(AppUser u, long chatId, int msgId) {
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
        rows.add(irow(bk("kg:st")));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }

    private void staffDelRequest(AppUser u, long userId, long chatId, int msgId) {
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

    private void staffRenStart(Session s, long userId, long chatId, int msgId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null) return;
        s.state = Session.State.KG_RN_NAME;
        s.data.put("kgRnId", userId);
        sender.edit(chatId, msgId, "✏️ <b>" + esc(x.getFullName())
                + "</b> uchun yangi ism-familiya yozing (SuperAdmin tasdiqlaydi):");
    }

    private void rnName(AppUser u, Session s, String text, long chatId) {
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
    private void staffDelApprove(AppUser admin, String arg, long chatId, int msgId) {
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

    private void staffRenApprove(AppUser admin, String arg, long chatId, int msgId) {
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

    private void staffReject(AppUser admin, String arg, long chatId, int msgId) {
        if (admin.getRole() != Role.SUPERADMIN) return;
        long reqId = Long.parseLong(arg.split("\\.")[1]);
        sender.edit(chatId, msgId, "❌ So'rov rad etildi.");
        userRepo.findById(reqId).ifPresent(r -> notify.toUser(r.getTelegramId(),
                "❌ Otdel xodimi bo'yicha so'rovingiz SuperAdmin tomonidan rad etildi."));
    }
}
