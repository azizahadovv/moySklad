package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.*;
import uz.kassa.domain.*;
import uz.kassa.repo.*;
import uz.kassa.service.*;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.*;

/** Kassir oqimlari (TZ 8.1). */
@Component
@RequiredArgsConstructor
public class KassirHandler {

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final Sender sender;
    private final NameService names;
    private final NotificationService notify;
    private final LedgerService ledger;
    private final RasxodService rasxodService;
    private final TransferService transferService;
    private final SubmissionService submissionService;
    private final CategoryRepo categoryRepo;
    private final KassaRepo kassaRepo;
    private final DebtRepo debtRepo;
    private final DayRepo dayRepo;
    private final OperationRepo opRepo;
    private final uz.kassa.webapp.ExcelReportService excelReport;

    /* ============================ MATN ============================ */

    public boolean onText(AppUser u, Session s, String text, long chatId) {
        // FSM: matn kutilayotgan holatlar
        switch (s.state) {
            case RX_AMT -> { rxAmount(u, s, text, chatId); return true; }
            case RX_CMT -> { rxFinish(u, s, text, chatId); return true; }
            case TR_AMT -> { trAmount(u, s, text, chatId); return true; }
            case TR_CMT -> { trFinish(u, s, text, chatId); return true; }
            default -> { }
        }

        // 📊 КАССАМ paneli ichida — pastki menu tugmalari bo'yicha navigatsiya
        String knav = s.getStr("knav");
        if (knav != null && handleKNav(u, s, knav, text, chatId)) return true;

        return switch (text) {
            case "📊 КАССАМ" -> { knavPanel(u, s, chatId); yield true; }
            case "💰 БУГУНГИ ТУШУМ", "📊 Bugungi holat" -> { today(u, chatId); yield true; }
            case "💰 Balansim" -> { balance(u, chatId); yield true; }
            case "💸 Rasxod" -> {
                s.reset(); s.state = Session.State.RX_MT;
                sender.send(chatId, "💸 <b>Rasxod so'rovi</b>\n\nPul turini tanlang:", mtChoice("k:mt"));
                yield true;
            }
            case "🔁 O'tkazma" -> { trStart(u, s, chatId); yield true; }
            case "📤 Hisobot topshirish" -> { sbStart(u, s, chatId); yield true; }
            case "🧾 Qarzlarim" -> { debts(u, chatId); yield true; }
            case "📜 Tarix" -> {
                sender.send(chatId, "📜 <b>Tranzaksiyalar tarixi</b>\n\nDavrni tanlang:",
                        inline(List.of(
                                irow(btn("📆 Bugun", "k:hp:t"), btn("Kecha", "k:hp:y")),
                                irow(btn("7 kun", "k:hp:7"), btn("30 kun", "k:hp:30"),
                                     btn("Shu oy", "k:hp:m")),
                                irow(btn("❌ Bekor", "cx")))));
                yield true;
            }
            default -> false;
        };
    }

    /* ============================ CALLBACK ============================ */

    public boolean onCallback(AppUser u, Session s, String data, long chatId, int msgId) {
        if (!data.startsWith("k:")) return false;
        String[] p = data.split(":", 3);
        String cmd = p[1];
        String arg = p.length > 2 ? p[2] : "";

        switch (cmd) {
            case "mt" -> {   // rasxod: pul turi
                if (s.state != Session.State.RX_MT) return true;
                s.data.put("mt", MoneyType.valueOf(arg));
                s.state = Session.State.RX_AMT;
                sender.edit(chatId, msgId, "💸 Rasxod — " + mtLabel(MoneyType.valueOf(arg))
                        + "\n\nSummani kiriting (so'm):");
            }
            case "cat" -> {  // rasxod: kategoriya
                if (s.state != Session.State.RX_CAT) return true;
                long catId = Long.parseLong(arg);
                String catName = categoryRepo.findById(catId).map(Category::getName).orElse("?");
                s.data.put("cat", catId);
                s.data.put("catName", catName);
                s.state = Session.State.RX_CMT;
                sender.edit(chatId, msgId, "Kategoriya: <b>" + esc(catName) + "</b>\n\n"
                        + "Izoh kiriting (shart emas — «-» yuboring):");
            }
            case "tg" -> {   // o'tkazma: qabul qiluvchi
                if (s.state != Session.State.TR_TGT) return true;
                if (arg.equals("B")) {
                    s.data.put("toT", OwnerType.BUXGALTERIYA);
                    s.data.put("toId", LedgerService.BUX_ID);
                } else {
                    s.data.put("toT", OwnerType.KASSA);
                    s.data.put("toId", Long.parseLong(arg.substring(1)));
                }
                s.state = Session.State.TR_MT;
                sender.edit(chatId, msgId, "Qabul qiluvchi: <b>"
                        + esc(names.owner((OwnerType) s.data.get("toT"), s.getLong("toId")))
                        + "</b>\n\nPul turini tanlang:", mtChoice("k:tm"));
            }
            case "tm" -> {   // o'tkazma: pul turi
                if (s.state != Session.State.TR_MT) return true;
                s.data.put("mt", MoneyType.valueOf(arg));
                s.state = Session.State.TR_AMT;
                sender.edit(chatId, msgId, "Pul turi: " + mtLabel(MoneyType.valueOf(arg))
                        + "\n\nSummani kiriting (so'm):");
            }
            case "tk" -> trKind(u, s, arg, chatId, msgId);
            case "db" -> {   // qarz qaytarish: qarz tanlandi
                if (s.state != Session.State.TR_DEBT) return true;
                s.data.put("debtId", Long.parseLong(arg));
                s.state = Session.State.TR_CMT;
                sender.edit(chatId, msgId, "Izoh kiriting (shart emas — «-» yuboring):");
            }
            case "sd" -> sbCreate(u, s, arg, chatId, msgId);
            case "hp" -> historyPeriod(u, arg, chatId, msgId);
            default -> { return false; }
        }
        return true;
    }

    /* ============================ 📊 / 💰 ============================ */

    private void today(AppUser u, long chatId) {
        Long kid = u.getKassaId();
        DayRecord d = dayRepo.findByKassaIdAndDate(kid, ledger.today()).orElse(null);
        List<DayRecord> pending = submissionService.submittableDays(kid);
        Balance n = ledger.view(OwnerType.KASSA, kid, MoneyType.NAQD);
        Balance k = ledger.view(OwnerType.KASSA, kid, MoneyType.KLIK);

        StringBuilder sb = new StringBuilder("📊 <b>Bugungi holat</b> — "
                + esc(names.owner(OwnerType.KASSA, kid)) + "\n\n");
        if (d == null) {
            sb.append("Bugun hali harakat yo'q.\n");
        } else {
            sb.append("Kirim: Naqd <b>").append(fmt(d.getPrixodNaqd()))
              .append("</b> · Click <b>").append(fmt(d.getPrixodKlik()))
              .append("</b> · Terminal <b>").append(fmt(d.getPrixodTerminal())).append("</b>\n");
            if (d.getVozvratNaqd() + d.getVozvratKlik() > 0)
                sb.append("Vozvrat: Naqd ").append(fmt(d.getVozvratNaqd()))
                  .append(" · Click ").append(fmt(d.getVozvratKlik())).append("\n");
            if (d.getRasxodNaqd() + d.getRasxodKlik() > 0)
                sb.append("Rasxod: Naqd ").append(fmt(d.getRasxodNaqd()))
                  .append(" · Click ").append(fmt(d.getRasxodKlik())).append("\n");
            if (d.getKirimNaqd() + d.getKirimKlik() + d.getChiqimNaqd() + d.getChiqimKlik() > 0)
                sb.append("O'tkazmalar: kirim ").append(fmt(d.getKirimNaqd() + d.getKirimKlik()))
                  .append(" · chiqim ").append(fmt(d.getChiqimNaqd() + d.getChiqimKlik())).append("\n");
            sb.append("Kun sof: Naqd <b>").append(fmt(d.netNaqd()))
              .append("</b> · Click <b>").append(fmt(d.netKlik())).append("</b>\n");
        }
        sb.append("\nQo'limdagi mavjud: Naqd <b>").append(fmt(n.available()))
          .append("</b> · Click <b>").append(fmt(k.available())).append("</b> so'm");
        if (!pending.isEmpty())
            sb.append("\n\n⏳ Topshirilmagan kunlar: <b>").append(pending.size()).append("</b> ta");
        sender.send(chatId, sb.toString());
    }

    private void balance(AppUser u, long chatId) {
        Long kid = u.getKassaId();
        Balance n = ledger.view(OwnerType.KASSA, kid, MoneyType.NAQD);
        Balance k = ledger.view(OwnerType.KASSA, kid, MoneyType.KLIK);
        sender.send(chatId, "💰 <b>Balansim</b> — " + esc(names.owner(OwnerType.KASSA, kid)) + "\n\n"
                + "💵 Naqd: <b>" + fmt(n.getAmount()) + "</b> so'm"
                + (n.getReserved() > 0 ? " (band: " + fmt(n.getReserved()) + ")" : "") + "\n"
                + "📲 Click: <b>" + fmt(k.getAmount()) + "</b> so'm"
                + (k.getReserved() > 0 ? " (band: " + fmt(k.getReserved()) + ")" : "") + "\n\n"
                + "Mavjud (band qilinmagan): Naqd <b>" + fmt(n.available())
                + "</b> · Click <b>" + fmt(k.available()) + "</b>");
    }

    /* ============================ 💸 RASXOD ============================ */

    private void rxAmount(AppUser u, Session s, String text, long chatId) {
        long amt = parseAmount(text);
        if (amt <= 0) { sender.send(chatId, "⚠️ Summani raqamda kiriting, masalan: 150000"); return; }
        MoneyType mt = (MoneyType) s.data.get("mt");
        long avail = ledger.view(OwnerType.KASSA, u.getKassaId(), mt).available();
        if (amt > avail) {
            sender.send(chatId, "⚠️ Mavjud qoldiq yetarli emas.\nMavjud: <b>" + fmt(avail)
                    + "</b> so'm (" + mtLabel(mt) + ")");
            return;
        }
        s.data.put("amt", amt);
        s.state = Session.State.RX_CAT;

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Category c : categoryRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn(c.getName(), "k:cat:" + c.getId())));
        rows.add(irow(btn("❌ Bekor", "cx")));
        sender.send(chatId, "Summa: <b>" + fmt(amt) + "</b> so'm\n\nKategoriyani tanlang:", inline(rows));
    }

    private void rxFinish(AppUser u, Session s, String text, long chatId) {
        String comment = text.equals("-") ? "" : text;
        MoneyType mt = (MoneyType) s.data.get("mt");
        long amt = s.getLong("amt");
        Long catId = s.getLong("cat");
        String catName = s.getStr("catName");

        Operation op = rasxodService.createRequest(u, mt, amt, catId, comment);
        s.reset();

        InlineKeyboardMarkup kb = inline(List.of(irow(
                btn("✅ Tasdiqlash", "rx:a:" + op.getId()),
                btn("❌ Rad etish", "rx:r:" + op.getId()))));
        notify.toBuxgalteriya("💸 <b>Rasxod so'rovi</b> #" + op.getId() + "\n\n"
                + "Kassa: <b>" + esc(names.owner(OwnerType.KASSA, u.getKassaId())) + "</b>\n"
                + "Summa: <b>" + fmt(amt) + "</b> so'm (" + mtLabel(mt) + ")\n"
                + "Kategoriya: " + esc(catName) + "\n"
                + (comment.isEmpty() ? "" : "Izoh: " + esc(comment) + "\n")
                + "Kassir: " + esc(u.getFullName()), kb);

        sender.send(chatId, "✅ So'rov #" + op.getId() + " buxgalterga yuborildi.\n"
                + "Summa band qilindi — tasdiqlangach balansdan ayriladi.");
    }

    /* ============================ 🔁 O'TKAZMA ============================ */

    private void trStart(AppUser u, Session s, long chatId) {
        s.reset(); s.state = Session.State.TR_TGT;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            if (!k.getId().equals(u.getKassaId()))
                rows.add(irow(btn("🏪 " + k.getName(), "k:tg:K" + k.getId())));
        rows.add(irow(btn("🏦 Buxgalteriya", "k:tg:B")));
        rows.add(irow(btn("❌ Bekor", "cx")));
        sender.send(chatId, "🔁 <b>O'tkazma</b>\n\nKimga o'tkazasiz?", inline(rows));
    }

    private void trAmount(AppUser u, Session s, String text, long chatId) {
        long amt = parseAmount(text);
        if (amt <= 0) { sender.send(chatId, "⚠️ Summani raqamda kiriting"); return; }
        MoneyType mt = (MoneyType) s.data.get("mt");
        long avail = ledger.view(OwnerType.KASSA, u.getKassaId(), mt).available();
        if (amt > avail) {
            sender.send(chatId, "⚠️ Mavjud qoldiq yetarli emas: <b>" + fmt(avail) + "</b> so'm");
            return;
        }
        s.data.put("amt", amt);
        s.state = Session.State.TR_KIND;
        sender.send(chatId, "Summa: <b>" + fmt(amt) + "</b> so'm\n\nO'tkazma turi:",
                inline(List.of(
                        irow(btn("Oddiy o'tkazma", "k:tk:O")),
                        irow(btn("🤝 Qarz berish", "k:tk:B")),
                        irow(btn("↩️ Qarz qaytarish", "k:tk:Q")),
                        irow(btn("❌ Bekor", "cx")))));
    }

    private void trKind(AppUser u, Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.TR_KIND) return;
        switch (arg) {
            case "O" -> { s.data.put("kind", TransferKind.ODDIY); toComment(s, chatId, msgId); }
            case "B" -> { s.data.put("kind", TransferKind.QARZ_BERISH); toComment(s, chatId, msgId); }
            case "Q" -> {
                s.data.put("kind", TransferKind.QARZ_QAYTARISH);
                OwnerType toT = (OwnerType) s.data.get("toT");
                long toId = s.getLong("toId");
                MoneyType mt = (MoneyType) s.data.get("mt");
                List<Debt> debts = debtRepo
                        .findByDebtorTypeAndDebtorIdAndStatus(OwnerType.KASSA, u.getKassaId(), DebtStatus.OCHIQ)
                        .stream()
                        .filter(d -> d.getCreditorType() == toT && d.getCreditorId() == toId
                                && d.getMoneyType() == mt)
                        .toList();
                if (debts.isEmpty()) {
                    sender.send(chatId, "⚠️ Bu yo'nalishda " + mtLabel(mt)
                            + " turida ochiq qarz yo'q. Boshqa turni tanlang.");
                    return;
                }
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                for (Debt d : debts)
                    rows.add(irow(btn("#" + d.getId() + " — qoldiq " + fmt(d.remain()) + " so'm",
                            "k:db:" + d.getId())));
                rows.add(irow(btn("❌ Bekor", "cx")));
                s.state = Session.State.TR_DEBT;
                sender.edit(chatId, msgId, "Qaysi qarzni qaytarasiz?", inline(rows));
            }
        }
    }

    private void toComment(Session s, long chatId, int msgId) {
        s.state = Session.State.TR_CMT;
        sender.edit(chatId, msgId, "Izoh kiriting (shart emas — «-» yuboring):");
    }

    private void trFinish(AppUser u, Session s, String text, long chatId) {
        String comment = text.equals("-") ? "" : text;
        OwnerType toT = (OwnerType) s.data.get("toT");
        long toId = s.getLong("toId");
        MoneyType mt = (MoneyType) s.data.get("mt");
        long amt = s.getLong("amt");
        TransferKind kind = (TransferKind) s.data.get("kind");
        Long debtId = s.data.containsKey("debtId") ? s.getLong("debtId") : null;

        Operation op = transferService.create(u, OwnerType.KASSA, u.getKassaId(),
                toT, toId, mt, amt, kind, debtId, comment);
        s.reset();

        String kindTxt = switch (kind) {
            case ODDIY -> "";
            case QARZ_BERISH -> " (qarz sifatida)";
            case QARZ_QAYTARISH -> " (qarz qaytarish)";
        };
        InlineKeyboardMarkup kb = inline(List.of(irow(
                btn("✅ Oldim", "tr:a:" + op.getId()),
                btn("❌ Olmadim", "tr:r:" + op.getId()))));
        String msg = "🔁 <b>Sizga o'tkazma</b> #" + op.getId() + kindTxt + "\n\n"
                + "Kimdan: <b>" + esc(names.owner(OwnerType.KASSA, u.getKassaId())) + "</b>\n"
                + "Summa: <b>" + fmt(amt) + "</b> so'm (" + mtLabel(mt) + ")\n"
                + (comment.isEmpty() ? "" : "Izoh: " + esc(comment) + "\n")
                + "\nPulni qabul qilganingizni tasdiqlang:";
        if (toT == OwnerType.KASSA) notify.toKassa(toId, msg, kb);
        else notify.toBuxgalteriya(msg, kb);

        sender.send(chatId, "📨 O'tkazma #" + op.getId() + " yuborildi" + kindTxt + ".\n"
                + "Summa band qilindi — qabul qiluvchi «Oldim» bosgach yakunlanadi.");
    }

    /* ============================ 📤 HISOBOT ============================ */

    private void sbStart(AppUser u, Session s, long chatId) {
        s.reset();
        List<DayRecord> days = submissionService.submittableDays(u.getKassaId());
        if (days.isEmpty()) {
            sender.send(chatId, "📤 Topshiriladigan yopilgan kun yo'q.\n"
                    + "Kun 00:00 da avtomatik yopiladi, keyin topshirish mumkin.");
            return;
        }
        StringBuilder sb = new StringBuilder("📤 <b>Hisobot topshirish</b>\n\nYopilgan kunlar (eng eskisidan):\n");
        for (DayRecord d : days)
            sb.append("• ").append(d.getDate().format(DF))
              .append(" — Naqd <b>").append(fmt(d.remainNaqd()))
              .append("</b> · Click <b>").append(fmt(d.remainKlik())).append("</b>\n");
        sb.append("\nNechta kunni topshirasiz? (eng eskisidan boshlab)");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> cur = new ArrayList<>();
        int max = Math.min(days.size(), 6);
        for (int i = 1; i <= max; i++) {
            cur.add(btn(i + " kun", "k:sd:" + i));
            if (cur.size() == 3) { rows.add(cur); cur = new ArrayList<>(); }
        }
        if (!cur.isEmpty()) rows.add(cur);
        rows.add(irow(btn("Hammasi (" + days.size() + " kun)", "k:sd:all")));
        rows.add(irow(btn("❌ Bekor", "cx")));
        sender.send(chatId, sb.toString(), inline(rows));
    }

    private void sbCreate(AppUser u, Session s, String arg, long chatId, int msgId) {
        int n = arg.equals("all") ? Integer.MAX_VALUE : Integer.parseInt(arg);
        Submission sub = submissionService.create(u, n);

        List<DayRecord> days = dayRepo.findAllById(sub.getDayIds()).stream()
                .sorted((a, b) -> a.getDate().compareTo(b.getDate())).toList();
        StringBuilder detail = new StringBuilder();
        for (DayRecord d : days)
            detail.append("• ").append(d.getDate().format(DF))
                  .append(" — Naqd ").append(fmt(d.remainNaqd()))
                  .append(" · Click ").append(fmt(d.remainKlik())).append("\n");

        InlineKeyboardMarkup kb = inline(List.of(
                irow(btn("✅ To'liq qabul", "sb:f:" + sub.getId())),
                irow(btn("🟡 Qisman qabul", "sb:p:" + sub.getId()),
                     btn("❌ Rad etish", "sb:r:" + sub.getId()))));
        notify.toBuxgalteriya("📤 <b>Hisobot</b> #" + sub.getId() + " — <b>"
                + esc(names.owner(OwnerType.KASSA, sub.getKassaId())) + "</b>\n"
                + "Kassir: " + esc(u.getFullName()) + "\n\n" + detail
                + "\nJami: Naqd <b>" + fmt(sub.getNaqd()) + "</b> · Click <b>"
                + fmt(sub.getKlik()) + "</b> so'm", kb);

        sender.edit(chatId, msgId, "✅ Hisobot #" + sub.getId() + " yuborildi ("
                + sub.getDayIds().size() + " kun).\n"
                + "Naqd <b>" + fmt(sub.getNaqd()) + "</b> · Click <b>" + fmt(sub.getKlik())
                + "</b> so'm band qilindi.\nBuxgalter qabul qilishi kutilmoqda.");
    }

    /* ============================ 🧾 / 📜 ============================ */

    private void debts(AppUser u, long chatId) {
        Long kid = u.getKassaId();
        List<Debt> we = debtRepo.findByDebtorTypeAndDebtorIdAndStatus(OwnerType.KASSA, kid, DebtStatus.OCHIQ);
        List<Debt> they = debtRepo.findByCreditorTypeAndCreditorIdAndStatus(OwnerType.KASSA, kid, DebtStatus.OCHIQ);
        if (we.isEmpty() && they.isEmpty()) {
            sender.send(chatId, "🧾 Ochiq qarzlar yo'q ✅");
            return;
        }
        StringBuilder sb = new StringBuilder("🧾 <b>Qarzlarim</b>\n");
        if (!we.isEmpty()) {
            sb.append("\n📕 Biz qarzdormiz:\n");
            for (Debt d : we)
                sb.append("• #").append(d.getId()).append(" ")
                  .append(esc(names.owner(d.getCreditorType(), d.getCreditorId())))
                  .append("ga: <b>").append(fmt(d.remain())).append("</b> so'm (")
                  .append(mtLabel(d.getMoneyType())).append(")\n");
        }
        if (!they.isEmpty()) {
            sb.append("\n📗 Bizdan qarzdor:\n");
            for (Debt d : they)
                sb.append("• #").append(d.getId()).append(" ")
                  .append(esc(names.owner(d.getDebtorType(), d.getDebtorId())))
                  .append(": <b>").append(fmt(d.remain())).append("</b> so'm (")
                  .append(mtLabel(d.getMoneyType())).append(")\n");
        }
        sender.send(chatId, sb.toString());
    }

    /* ==================== 📊 КАССАМ PANELI (menu tugmali) ==================== */

    private static final List<String> KASSAM_MENU = List.of(
            "💰 Бугунги тушум", "💸 Расход", "📆 Давр танлаш",
            "💼 Салдо", "🧾 Қарзларим", "📊 Excel");
    private static final List<String> KPERIODS = List.of(
            "📆 Bugun", "Kecha", "7 kun", "30 kun", "Shu oy");

    private void knavPanel(AppUser u, Session s, long chatId) {
        String name = u.getKassaId() == null ? "?" : names.owner(OwnerType.KASSA, u.getKassaId());
        s.data.put("knav", "panel");
        kSend(s, chatId, "📊 <b>КАССАМ</b> — " + esc(name) + "\n\nBo'limni tanlang:",
                levelMenu(KASSAM_MENU));
    }

    /** Panel prompt-xabarini yuborish, oldingisini o'chirib — chat toza qoladi. */
    private void kSend(Session s, long chatId, String text,
                       org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard kb) {
        Object prev = s.data.remove("panelMsg");
        if (prev instanceof Integer i) sender.deleteMessage(chatId, i);
        Integer id = sender.sendId(chatId, text, kb);
        if (id != null) s.data.put("panelMsg", id);
    }

    private boolean handleKNav(AppUser u, Session s, String knav, String text, long chatId) {
        if (text.equals("⬅️ Orqaga")) {
            if (knav.equals("panel")) {
                s.data.remove("knav");
                Object prev = s.data.remove("panelMsg");
                if (prev instanceof Integer i) sender.deleteMessage(chatId, i);
                sender.send(chatId, "🏠 Bosh menyu", kassirMenu());
            } else knavPanel(u, s, chatId);
            return true;
        }
        switch (knav) {
            case "panel" -> {
                switch (text) {
                    case "💰 Бугунги тушум" -> today(u, chatId);
                    case "💸 Расход" -> rasxodToday(u, chatId);
                    case "📆 Давр танлаш" -> {
                        s.data.put("knav", "kdavr");
                        kSend(s, chatId, "📆 <b>Давр танлаш</b>\n\nDavrni tanlang:",
                                levelMenu(KPERIODS));
                    }
                    case "💼 Салдо" -> balance(u, chatId);
                    case "🧾 Қарзларим" -> debts(u, chatId);
                    case "📊 Excel" -> {
                        s.data.put("knav", "kexcel");
                        kSend(s, chatId, "📊 <b>Excel</b> — o'z kassangiz bo'yicha\n\n"
                                + "Davrni tanlang, fayl shu chatga yuboriladi:", levelMenu(KPERIODS));
                    }
                    default -> { return false; }
                }
            }
            case "kdavr" -> {
                String code = kCodeOf(text);
                if (code == null) return false;
                historyPeriod(u, code, chatId, 0);
            }
            case "kexcel" -> {
                String code = kCodeOf(text);
                if (code == null) return false;
                kassirExcel(u, code, chatId);
            }
            default -> { return false; }
        }
        return true;
    }

    private String kCodeOf(String text) {
        return switch (text) {
            case "📆 Bugun" -> "t"; case "Kecha" -> "y";
            case "7 kun" -> "7"; case "30 kun" -> "30"; case "Shu oy" -> "m";
            default -> null;
        };
    }

    /** Kassir Exceli — FAQAT o'z kassasi kesimida. */
    private void kassirExcel(AppUser u, String code, long chatId) {
        Long kid = u.getKassaId();
        if (kid == null) { sender.send(chatId, "⚠️ Sizga kassa biriktirilmagan"); return; }
        Kassa kassa = kassaRepo.findById(kid).orElse(null);
        if (kassa == null) return;

        java.time.LocalDate t = ledger.today();
        java.time.LocalDate from = switch (code) {
            case "t" -> t; case "y" -> t.minusDays(1);
            case "7" -> t.minusDays(6); case "30" -> t.minusDays(29);
            default -> t.withDayOfMonth(1);
        };
        java.time.LocalDate to = code.equals("y") ? t.minusDays(1) : t;
        String label = kassa.getName() + " · "
                + (from.equals(to) ? from.format(DF) : from.format(DF) + " — " + to.format(DF));

        sender.send(chatId, "⏳ Excel tayyorlanmoqda: <b>" + esc(label) + "</b>…");
        java.time.LocalDate fFrom = from, fTo = to;
        new Thread(() -> {
            try {
                byte[] xlsx = excelReport.build(fFrom, fTo, kassa);
                sender.sendDocument(chatId, xlsx,
                        "kassa" + kid + "_" + fFrom + "_" + fTo + ".xlsx",
                        "📊 Excel: <b>" + esc(label) + "</b>");
            } catch (Exception e) {
                sender.send(chatId, "⚠️ Excel xatosi: " + esc(e.getMessage()));
            }
        }).start();
    }

    /** Bugungi chiqimlar — o'z kassasi bo'yicha. */
    private void rasxodToday(AppUser u, long chatId) {
        Long kid = u.getKassaId();
        if (kid == null) { sender.send(chatId, "⚠️ Sizga kassa biriktirilmagan"); return; }
        DayRecord d = dayRepo.findByKassaIdAndDate(kid, ledger.today()).orElse(null);
        long rn = d == null ? 0 : d.getRasxodNaqd();
        long rk = d == null ? 0 : d.getRasxodKlik();
        StringBuilder sb = new StringBuilder("💸 <b>Расход</b> — bugun\n\n"
                + "💵 Naqd: <b>" + fmt(rn) + "</b> · 📲 Click: <b>" + fmt(rk) + "</b>\n"
                + "➕ <b>Jami: " + fmt(rn + rk) + "</b> so'm\n");
        int shown = 0;
        for (Operation o : opRepo.byPeriod(ledger.today(), ledger.today())) {
            if (o.getType() != OpType.RASXOD) continue;
            if (o.getFromOwnerType() != OwnerType.KASSA || !kid.equals(o.getFromOwnerId())) continue;
            if (shown++ >= 15) break;
            sb.append("\n• ").append(fmt(o.getAmount())).append(" so'm")
              .append(o.getStatus() == OpStatus.KUTILMOQDA ? " ⏳" : "")
              .append(o.getComment() == null || o.getComment().isEmpty()
                      ? "" : " — " + esc(o.getComment()));
        }
        if (shown == 0) sb.append("\nBugun rasxod yo'q");
        sender.send(chatId, sb.toString());
    }

    /** Davr bo'yicha tarix: code = t|y|7|30|m. */
    void historyPeriod(AppUser u, String code, long chatId, int msgId) {
        if (u.getKassaId() == null) {
            if (msgId > 0) sender.edit(chatId, msgId, "⚠️ Sizga kassa biriktirilmagan");
            else sender.send(chatId, "⚠️ Sizga kassa biriktirilmagan");
            return;
        }
        java.time.LocalDate t = java.time.LocalDate.now();
        java.time.LocalDate from = switch (code) {
            case "t" -> t; case "y" -> t.minusDays(1);
            case "7" -> t.minusDays(6); case "30" -> t.minusDays(29);
            default -> t.withDayOfMonth(1);
        };
        java.time.LocalDate to = code.equals("y") ? t.minusDays(1) : t;
        String label = from.equals(to) ? from.format(DF) : from.format(DF) + " — " + to.format(DF);

        long kirim = 0, chiqim = 0;
        java.util.List<String> lines = new java.util.ArrayList<>();
        long total = 0;
        for (Operation o : opRepo.byPeriod(from, to)) {
            boolean mine = (o.getFromOwnerType() == OwnerType.KASSA && u.getKassaId().equals(o.getFromOwnerId()))
                    || (o.getToOwnerType() == OwnerType.KASSA && u.getKassaId().equals(o.getToOwnerId()));
            if (!mine) continue;
            total++;
            if (o.getType() == OpType.PRIXOD || o.getType() == OpType.BOSHLANGICH) kirim += o.getAmount();
            if (o.getType() == OpType.RASXOD || o.getType() == OpType.VOZVRAT
                    || o.getType() == OpType.TOPSHIRIQ) chiqim += o.getAmount();
            if (lines.size() < 25) lines.add(opLine(o, u.getKassaId()));
        }
        StringBuilder sb = new StringBuilder("📜 <b>Tarix</b> · " + label + "\n\n"
                + "🟢 Kirim: <b>" + fmt(kirim) + "</b> so'm\n"
                + "🔴 Chiqim: <b>" + fmt(chiqim) + "</b> so'm\n"
                + "➕ Farq: <b>" + fmt(kirim - chiqim) + "</b> so'm\n");
        if (lines.isEmpty()) sb.append("\nBu davrda tranzaksiya yo'q");
        else {
            sb.append("\n").append(String.join("\n", lines));
            if (total > lines.size())
                sb.append("\n\n<i>…yana ").append(total - lines.size()).append(" ta</i>");
        }
        if (msgId > 0)
            sender.edit(chatId, msgId, sb.toString(), inline(List.of(
                    irow(btn("📆 Bugun", "k:hp:t"), btn("Kecha", "k:hp:y")),
                    irow(btn("7 kun", "k:hp:7"), btn("30 kun", "k:hp:30"), btn("Shu oy", "k:hp:m")))));
        else sender.send(chatId, sb.toString());   // menu-rejim: inline tugmalarsiz
    }

    private String opLine(Operation o, Long myKassaId) {
        boolean outgoing = o.getFromOwnerType() == OwnerType.KASSA
                && myKassaId.equals(o.getFromOwnerId());
        String emoji = switch (o.getType()) {
            case PRIXOD -> "🟢";
            case VOZVRAT -> "🔻";
            case RASXOD -> "💸";
            case OTKAZMA -> outgoing ? "📤" : "📥";
            case TOPSHIRIQ -> "🏦";
            case KORREKTIROVKA, BOSHLANGICH -> "⚙️";
        };
        String st = switch (o.getStatus()) {
            case KUTILMOQDA -> " ⏳";
            case YOLDA -> " 🚚";
            case RAD_ETILGAN -> " ❌";
            case BEKOR -> " 🚫";
            case TASDIQLANGAN -> "";
        };
        return emoji + " " + o.getOpDate().format(DF) + " — " + fmt(o.getAmount())
                + " so'm (" + mtLabel(o.getMoneyType()) + ")" + st;
    }
}
