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

/** Buxgalter oqimlari (TZ 8.2). SuperAdmin ham shu oqimlardan foydalanadi. */
@Component
@RequiredArgsConstructor
public class BuxgalterHandler {

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final Sender sender;
    private final NameService names;
    private final NotificationService notify;
    private final LedgerService ledger;
    private final TransferService transferService;
    private final SubmissionService submissionService;
    private final KassaRepo kassaRepo;
    private final DebtRepo debtRepo;
    private final DayRepo dayRepo;
    private final OperationRepo opRepo;
    private final SubmissionRepo subRepo;
    private final AppUserRepo userRepo;
    private final uz.kassa.webapp.ExcelReportService excelReport;
    private final uz.kassa.service.moysklad.MoySkladSyncService syncService;

    /* ============================ MATN ============================ */

    public boolean onText(AppUser u, Session s, String text, long chatId) {
        switch (s.state) {
            case TR_AMT -> { trAmount(s, text, chatId); return true; }
            case TR_CMT -> { trFinish(u, s, text, chatId); return true; }
            case RJ_SUB_REASON -> { rejectSubmission(u, s, text, chatId); return true; }
            case SBP_NAQD -> { partialNaqd(s, text, chatId); return true; }
            case SBP_KLIK -> { partialKlik(u, s, text, chatId); return true; }
            default -> { }
        }

        return switch (text) {
            case "🏪 Kassalar holati" -> { overview(chatId); yield true; }
            case "📥 Kutilayotganlar" -> { pendingList(chatId); yield true; }
            case "🔁 O'tkazma" -> { trStart(s, chatId); yield true; }
            case "🧾 Qarzlar registri" -> { debtsRegistry(chatId); yield true; }
            case "📜 Tarix" -> { historyMenu(chatId); yield true; }
            case "📊 Excel hisobot" -> { excelMenu(chatId); yield true; }
            default -> false;
        };
    }

    /* ============================ CALLBACK ============================ */

    public boolean onCallback(AppUser u, Session s, String data, long chatId, int msgId) {
        if (!data.startsWith("b:")) return false;

        String[] p = data.split(":", 3);
        String cmd = p[1];
        String arg = p.length > 2 ? p[2] : "";

        switch (cmd) {
            case "tg" -> {
                if (s.state != Session.State.TR_TGT) return true;
                s.data.put("toId", Long.parseLong(arg));
                s.state = Session.State.TR_MT;
                sender.edit(chatId, msgId, "Qabul qiluvchi: <b>"
                        + esc(names.owner(OwnerType.KASSA, s.getLong("toId")))
                        + "</b>\n\nPul turini tanlang:", mtChoice("b:tm"));
            }
            case "tm" -> {
                if (s.state != Session.State.TR_MT) return true;
                s.data.put("mt", MoneyType.valueOf(arg));
                s.state = Session.State.TR_AMT;
                sender.edit(chatId, msgId, "Pul turi: " + mtLabel(MoneyType.valueOf(arg))
                        + "\n\nSummani kiriting (so'm):");
            }
            case "tk" -> trKind(s, arg, chatId, msgId);
            case "db" -> {
                if (s.state != Session.State.TR_DEBT) return true;
                s.data.put("debtId", Long.parseLong(arg));
                s.state = Session.State.TR_CMT;
                sender.edit(chatId, msgId, "Izoh kiriting (shart emas — «-» yuboring):");
            }
            case "hp" -> kassaPick(arg, chatId, msgId);            // tarix: davr tanlandi
            case "hk" -> renderHistory(arg, chatId, msgId);        // tarix: kassa tanlandi
            case "kp" -> kassaProfile(arg + ":7", chatId, msgId);  // kassa profili (7 kun)
            case "kh" -> kassaProfile(arg, chatId, msgId);         // profil: davr almashdi
            case "xp" -> runExcel(u, arg, chatId, msgId);          // excel: davr tanlandi
            default -> { return false; }
        }
        return true;
    }

    /* ============================ 🏪 UMUMIY HOLAT ============================ */

    public void overview(long chatId) {
        syncService.syncIfStale(45);   // so'ralganda oxirgi ma'lumot kelsin
        StringBuilder sb = new StringBuilder("🏪 <b>Kassalar holati</b>\n");
        long totN = 0, totK = 0, totT = 0;
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;
            Balance n = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.NAQD);
            Balance kl = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.KLIK);
            long term = dayRepo.findByKassaIdAndDate(k.getId(), ledger.today())
                    .map(DayRecord::getPrixodTerminal).orElse(0L);
            totN += n.getAmount(); totK += kl.getAmount(); totT += term;
            int open = submissionService.submittableDays(k.getId()).size();
            sb.append("\n<b>").append(esc(k.getName())).append("</b>\n")
              .append("  💵 Naqd: ").append(fmt(n.getAmount()))
              .append(n.getReserved() > 0 ? " (rezervda " + fmt(n.getReserved()) + " · mavjud " + fmt(n.available()) + ")" : "")
              .append("\n  📲 Click: ").append(fmt(kl.getAmount()))
              .append(kl.getReserved() > 0 ? " (rezervda " + fmt(kl.getReserved()) + " · mavjud " + fmt(kl.available()) + ")" : "")
              .append("\n  💳 Terminal (bugun): ").append(fmt(term))
              .append("\n  ➕ <b>Jami (naqd+click): ").append(fmt(n.getAmount() + kl.getAmount()))
              .append("</b> so'm");
            if (open > 0) sb.append("\n  ⏳ Topshirilmagan kunlar: ").append(open);
            sb.append("\n");
        }
        Balance bn = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD);
        Balance bk = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.KLIK);
        sb.append("\n🏦 <b>Отдел Основной</b>\n  💵 Naqd: ").append(fmt(bn.getAmount()))
          .append("\n  📲 Click: ").append(fmt(bk.getAmount()))
          .append("\n  ➕ <b>Jami: ").append(fmt(bn.getAmount() + bk.getAmount())).append("</b> so'm");
        sb.append("\n\n💰 <b>UMUMIY</b> (kassalar + buxgalteriya)")
          .append("\n  💵 Naqd: ").append(fmt(totN + bn.getAmount()))
          .append("\n  📲 Click: ").append(fmt(totK + bk.getAmount()))
          .append("\n  💳 Terminal (bugun, balansga KIRMAYDI — firma bank hisobida): ").append(fmt(totT))
          .append("\n  ➕ <b>Jami (naqd+click): ")
          .append(fmt(totN + bn.getAmount() + totK + bk.getAmount()))
          .append("</b> so'm");
        sb.append("\n\nKassa profili uchun tugmani bosing:");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<Kassa> ks = kassaRepo.findByActiveTrueOrderByIdAsc().stream()
                .filter(k -> !k.isCashless()).toList();   // B5: hisobotda yo'q kassaga tugma ham yo'q
        for (int i = 0; i < ks.size(); i += 2) {
            if (i + 1 < ks.size())
                rows.add(irow(btn("🏪 " + ks.get(i).getName(), "b:kp:" + ks.get(i).getId()),
                              btn("🏪 " + ks.get(i + 1).getName(), "b:kp:" + ks.get(i + 1).getId())));
            else rows.add(irow(btn("🏪 " + ks.get(i).getName(), "b:kp:" + ks.get(i).getId())));
        }
        sender.send(chatId, sb.toString(), rows.isEmpty() ? null : inline(rows));
    }

    /* ============================ 📥 KUTILAYOTGANLAR ============================ */

    public void pendingList(long chatId) { pendingList(chatId, null); }

    /** kassaId != null — faqat shu kassaning kutilayotgan hisobotlari (o'tkazmalarsiz). */
    public void pendingList(long chatId, Long kassaId) {
        int shown = 0;

        List<Submission> subs = kassaId == null
                ? subRepo.findByStatusOrderByIdAsc(SubmissionStatus.KUTILMOQDA)
                : subRepo.findByKassaIdAndStatusOrderByIdAsc(kassaId, SubmissionStatus.KUTILMOQDA);
        for (Submission sub : subs.stream().limit(10).toList()) {
            StringBuilder detail = new StringBuilder();
            dayRepo.findAllById(sub.getDayIds()).stream()
                    .sorted((a, b) -> a.getDate().compareTo(b.getDate()))
                    .forEach(d -> {
                        detail.append("• ").append(d.getDate().format(DF))
                              .append(" — Naqd ").append(fmt(d.remainNaqd()))
                              .append(" · Click ").append(fmt(d.remainKlik()));
                        if (d.getRasxodNaqd() + d.getRasxodKlik() > 0)
                            detail.append(" (💸 rasxod: ").append(fmt(d.getRasxodNaqd()))
                                  .append(" · ").append(fmt(d.getRasxodKlik())).append(")");
                        detail.append("\n");
                    });
            sender.send(chatId, "📤 <b>Hisobot</b> #" + sub.getId() + " — <b>"
                    + esc(names.owner(OwnerType.KASSA, sub.getKassaId())) + "</b>\n\n" + detail
                    + "\nJami: Naqd <b>" + fmt(sub.getNaqd()) + "</b> · Click <b>"
                    + fmt(sub.getKlik()) + "</b> so'm"
                    + "\nℹ️ Click summasi qabul qilinganda kassaning o'z hisobida qoladi.",
                    inline(List.of(
                            irow(btn("✅ To'liq qabul", "sb:f:" + sub.getId())),
                            irow(btn("🟡 Qisman qabul", "sb:p:" + sub.getId()),
                                 btn("❌ Rad etish", "sb:r:" + sub.getId())))));
            shown++;
        }

        List<Operation> transfers = kassaId == null
                ? opRepo.incomingTransfers(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID)
                : List.of();
        for (Operation op : transfers.stream().limit(10).toList()) {
            sender.send(chatId, "🔁 <b>Kiruvchi o'tkazma</b> #" + op.getId() + "\n"
                    + "Kimdan: <b>" + esc(names.owner(op.getFromOwnerType(), op.getFromOwnerId())) + "</b>\n"
                    + "Summa: <b>" + fmt(op.getAmount()) + "</b> so'm (" + mtLabel(op.getMoneyType()) + ")",
                    inline(List.of(irow(
                            btn("✅ Oldim", "tr:a:" + op.getId()),
                            btn("❌ Olmadim", "tr:r:" + op.getId())))));
            shown++;
        }

        if (shown == 0) sender.send(chatId, "📥 Kutilayotgan amallar yo'q ✅");
    }

    /* ============================ 🔁 O'TKAZMA (BUX -> KASSA) ============================ */

    public void trStart(Session s, long chatId) {
        s.reset(); s.state = Session.State.TR_TGT;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;   // B5: cashless'ga pul kiritilmaydi
            rows.add(irow(btn("🏪 " + k.getName(), "b:tg:" + k.getId())));
        }
        rows.add(irow(btn("❌ Bekor", "cx")));
        sender.send(chatId, "🔁 <b>O'tkazma</b> (Buxgalteriya → kassa)\n\nQaysi kassaga?", inline(rows));
    }

    private void trAmount(Session s, String text, long chatId) {
        long amt = parseAmount(text);
        if (amt <= 0) { sender.send(chatId, "⚠️ Summani raqamda kiriting"); return; }
        MoneyType mt = (MoneyType) s.data.get("mt");
        long avail = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, mt).available();
        if (amt > avail) {
            sender.send(chatId, "⚠️ Mavjud qoldiq yetarli emas: <b>" + fmt(avail) + "</b> so'm");
            return;
        }
        s.data.put("amt", amt);
        s.state = Session.State.TR_KIND;
        sender.send(chatId, "Summa: <b>" + fmt(amt) + "</b> so'm\n\nO'tkazma turi:",
                inline(List.of(
                        irow(btn("Oddiy o'tkazma", "b:tk:O")),
                        irow(btn("🤝 Qarz berish", "b:tk:B")),
                        irow(btn("↩️ Qarz qaytarish", "b:tk:Q")),
                        irow(btn("❌ Bekor", "cx")))));
    }

    private void trKind(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.TR_KIND) return;
        switch (arg) {
            case "O" -> { s.data.put("kind", TransferKind.ODDIY); toComment(s, chatId, msgId); }
            case "B" -> { s.data.put("kind", TransferKind.QARZ_BERISH); toComment(s, chatId, msgId); }
            case "Q" -> {
                s.data.put("kind", TransferKind.QARZ_QAYTARISH);
                long toId = s.getLong("toId");
                MoneyType mt = (MoneyType) s.data.get("mt");
                List<Debt> debts = debtRepo
                        .findByDebtorTypeAndDebtorIdAndStatus(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, DebtStatus.OCHIQ)
                        .stream()
                        .filter(d -> d.getCreditorType() == OwnerType.KASSA
                                && d.getCreditorId() == toId && d.getMoneyType() == mt)
                        .toList();
                if (debts.isEmpty()) {
                    sender.send(chatId, "⚠️ Bu kassaga " + mtLabel(mt)
                            + " turida ochiq qarz yo'q. Boshqa turni tanlang.");
                    return;
                }
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                for (Debt d : debts)
                    rows.add(irow(btn("#" + d.getId() + " — qoldiq " + fmt(d.remain()) + " so'm",
                            "b:db:" + d.getId())));
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
        long toId = s.getLong("toId");
        MoneyType mt = (MoneyType) s.data.get("mt");
        long amt = s.getLong("amt");
        TransferKind kind = (TransferKind) s.data.get("kind");
        Long debtId = s.data.containsKey("debtId") ? s.getLong("debtId") : null;

        Operation op = transferService.create(u, OwnerType.BUXGALTERIYA, LedgerService.BUX_ID,
                OwnerType.KASSA, toId, mt, amt, kind, debtId, comment);
        s.reset();

        String kindTxt = switch (kind) {
            case ODDIY -> "";
            case QARZ_BERISH -> " (qarz sifatida)";
            case QARZ_QAYTARISH -> " (qarz qaytarish)";
        };
        notify.toKassa(toId, "🔁 <b>Sizga o'tkazma</b> #" + op.getId() + kindTxt + "\n\n"
                + "Kimdan: <b>Отдел Основной</b>\n"
                + "Summa: <b>" + fmt(amt) + "</b> so'm (" + mtLabel(mt) + ")\n"
                + (comment.isEmpty() ? "" : "Izoh: " + esc(comment) + "\n")
                + "\nPulni qabul qilganingizni tasdiqlang:",
                inline(List.of(irow(
                        btn("✅ Oldim", "tr:a:" + op.getId()),
                        btn("❌ Olmadim", "tr:r:" + op.getId())))));

        sender.send(chatId, "📨 O'tkazma #" + op.getId() + " yuborildi" + kindTxt
                + ". Kassir «Oldim» bosgach yakunlanadi.");
    }

    /* ============================ QARORLAR DAVOMI ============================ */

    private void rejectSubmission(AppUser u, Session s, String reason, long chatId) {
        long subId = s.getLong("subId");
        long srcChat = s.getLong("srcChat");
        int srcMsg = (int) s.getLong("srcMsg");
        s.reset();

        Submission sub = submissionService.reject(subId, u, reason);
        sender.edit(srcChat, srcMsg, "📤 Hisobot #" + sub.getId() + " — "
                + esc(names.owner(OwnerType.KASSA, sub.getKassaId()))
                + "\n\n❌ <b>Rad etildi</b> — " + esc(u.getFullName())
                + "\nSabab: " + esc(reason));
        notify.toKassa(sub.getKassaId(), "❌ Hisobot #" + sub.getId() + " rad etildi.\n"
                + "Sabab: " + esc(reason)
                + "\nKunlar yana topshirishga tayyor, band qilingan pul bo'shatildi.", null);
        sender.send(chatId, "Rad etildi ✔️");
    }

    private void partialNaqd(Session s, String text, long chatId) {
        long v = parseAmount(text);
        long max = s.getLong("maxN");
        if (v < 0 || v > max) {
            sender.send(chatId, "⚠️ 0 dan " + fmt(max) + " gacha summa kiriting");
            return;
        }
        s.data.put("accN", v);
        s.state = Session.State.SBP_KLIK;
        sender.send(chatId, "✍️ Hisobotda tasdiqlanadigan <b>CLICK</b> summani kiriting "
                + "(0 dan " + fmt(s.getLong("maxK")) + " gacha).\n"
                + "<i>Click puli kassaning o'z hisobida qoladi — bu faqat hisobotni yopish uchun:</i>");
    }

    private void partialKlik(AppUser u, Session s, String text, long chatId) {
        long v = text.equals("0") ? 0 : parseAmount(text);
        long max = s.getLong("maxK");
        if (v < 0 || v > max) {
            sender.send(chatId, "⚠️ 0 dan " + fmt(max) + " gacha summa kiriting");
            return;
        }
        long subId = s.getLong("subId");
        long accN = s.getLong("accN");
        long srcChat = s.getLong("srcChat");
        int srcMsg = (int) s.getLong("srcMsg");
        s.reset();

        Submission sub = submissionService.acceptPartial(subId, u, accN, v);
        long debtN = sub.getNaqd() - accN;
        long debtK = sub.getKlik() - v;

        sender.edit(srcChat, srcMsg, "📤 Hisobot #" + sub.getId() + " — "
                + esc(names.owner(OwnerType.KASSA, sub.getKassaId()))
                + "\n\n🟡 <b>Qisman qabul</b> — " + esc(u.getFullName())
                + "\nQabul: Naqd <b>" + fmt(accN) + "</b> · Click <b>" + fmt(v) + "</b> so'm"
                + "\nFarq: Naqd " + fmt(debtN) + " · Click " + fmt(debtK));
        notify.toKassa(sub.getKassaId(), "🟡 Hisobot #" + sub.getId() + " qisman qabul qilindi.\n"
                + "Qabul: Naqd <b>" + fmt(accN) + "</b> · Click <b>" + fmt(v) + "</b> so'm\n"
                + "Farq (qarzdorlik): Naqd <b>" + fmt(debtN) + "</b> · Click <b>" + fmt(debtK)
                + "</b> so'm — tegishli kunlar ochiq qoldi, keyingi topshiriqda yopiladi."
                + (v > 0 ? "\nℹ️ Click pulingiz o'z hisobingizda qoladi." : ""), null);
        sender.send(chatId, "Qisman qabul qilindi ✔️");
    }

    /* ============================ 🧾 / 📜 ============================ */

    public void debtsRegistry(long chatId) {
        List<Debt> debts = debtRepo.findByStatusOrderByIdAsc(DebtStatus.OCHIQ);
        if (debts.isEmpty()) { sender.send(chatId, "🧾 Ochiq qarzlar yo'q ✅"); return; }
        StringBuilder sb = new StringBuilder("🧾 <b>Qarzlar registri</b> (ochiq)\n\n");
        for (Debt d : debts)
            sb.append("#").append(d.getId()).append(" ")
              .append(esc(names.owner(d.getDebtorType(), d.getDebtorId())))
              .append(" → ")
              .append(esc(names.owner(d.getCreditorType(), d.getCreditorId())))
              .append(": <b>").append(fmt(d.remain())).append("</b> so'm (")
              .append(mtLabel(d.getMoneyType())).append(")\n");
        sender.send(chatId, sb.toString());
    }

    /* ============================ 📜 TARIX (davr bo'yicha) ============================ */

    /** Davr kodi -> [dan, gacha]. t=bugun, y=kecha, 7, 30, m=shu oy. */
    private java.time.LocalDate[] periodOf(String code) {
        java.time.LocalDate t = ledger.today();
        return switch (code) {
            case "t" -> new java.time.LocalDate[]{t, t};
            case "y" -> new java.time.LocalDate[]{t.minusDays(1), t.minusDays(1)};
            case "7" -> new java.time.LocalDate[]{t.minusDays(6), t};
            case "30" -> new java.time.LocalDate[]{t.minusDays(29), t};
            default -> new java.time.LocalDate[]{t.withDayOfMonth(1), t};
        };
    }

    private String periodLabel(String code) {
        java.time.LocalDate[] p = periodOf(code);
        return p[0].equals(p[1]) ? p[0].format(DF) : p[0].format(DF) + " — " + p[1].format(DF);
    }

    private InlineKeyboardMarkup periodButtons(String prefix) {
        return inline(List.of(
                irow(btn("📆 Bugun", prefix + ":t"), btn("Kecha", prefix + ":y")),
                irow(btn("7 kun", prefix + ":7"), btn("30 kun", prefix + ":30"), btn("Shu oy", prefix + ":m")),
                irow(btn("❌ Bekor", "cx"))));
    }

    public void historyMenu(long chatId) {
        sender.send(chatId, "📜 <b>Tranzaksiyalar tarixi</b>\n\nDavrni tanlang:", periodButtons("b:hp"));
    }

    /** Davr tanlandi — endi kassa tanlash. */
    private void kassaPick(String code, long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("🏢 Hammasi", "b:hk:0:" + code), btn("🏦 Buxgalteriya", "b:hk:B:" + code)));
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn("🏪 " + k.getName(), "b:hk:" + k.getId() + ":" + code)));
        rows.add(irow(btn("❌ Bekor", "cx")));
        sender.edit(chatId, msgId, "📜 Davr: <b>" + periodLabel(code) + "</b>\n\nKimning tarixi?", inline(rows));
    }

    /** arg = "<kassaId|0|B>:<davr kodi>" — tarixni chiqarish. */
    private void renderHistory(String arg, long chatId, int msgId) {
        String[] a = arg.split(":");
        String who = a[0], code = a[1];
        java.time.LocalDate[] p = periodOf(code);
        List<Operation> all = opRepo.byPeriod(p[0], p[1]);

        StringBuilder sb = new StringBuilder("📜 <b>Tarix</b> · " + periodLabel(code) + "\n");
        long kirim = 0, chiqim = 0;
        List<String> lines = new ArrayList<>();
        for (Operation o : all) {
            boolean show;
            if (who.equals("0")) show = true;
            else if (who.equals("B")) show = touches(o, OwnerType.BUXGALTERIYA, LedgerService.BUX_ID);
            else show = touches(o, OwnerType.KASSA, Long.parseLong(who));
            if (!show) continue;
            // Faqat TASDIQLANGAN operatsiyalar summaga kiradi — rad etilgan/yo'ldagi pul emas
            if (o.getStatus() == uz.kassa.domain.OpStatus.TASDIQLANGAN) {
                if (o.getType() == OpType.PRIXOD || o.getType() == OpType.BOSHLANGICH) kirim += o.getAmount();
                if (o.getType() == OpType.RASXOD || o.getType() == OpType.VOZVRAT
                        || o.getType() == OpType.TOPSHIRIQ) chiqim += o.getAmount();
            }
            if (lines.size() < 25) lines.add(opText(o));
        }
        String whoLabel = who.equals("0") ? "🏢 Hammasi"
                : who.equals("B") ? "🏦 Buxgalteriya"
                : "🏪 " + names.owner(OwnerType.KASSA, Long.parseLong(who));
        sb.append(esc(whoLabel)).append("\n\n")
          .append("🟢 Kirim: <b>").append(fmt(kirim)).append("</b> so'm\n")
          .append("🔴 Chiqim: <b>").append(fmt(chiqim)).append("</b> so'm\n")
          .append("➕ Farq: <b>").append(fmt(kirim - chiqim)).append("</b> so'm\n");
        if (lines.isEmpty()) sb.append("\nBu davrda tranzaksiya yo'q");
        else {
            sb.append("\n").append(String.join("\n", lines));
            long total = all.stream().filter(o -> who.equals("0")
                    || (who.equals("B") ? touches(o, OwnerType.BUXGALTERIYA, LedgerService.BUX_ID)
                        : touches(o, OwnerType.KASSA, Long.parseLong(who)))).count();
            if (total > lines.size())
                sb.append("\n\n<i>…yana ").append(total - lines.size())
                  .append(" ta. To'liq ro'yxat: 📊 Excel hisobot</i>");
        }
        sender.edit(chatId, msgId, sb.toString(), periodButtons(who.equals("0") ? "b:hp" : "b:hp"));
    }

    private boolean touches(Operation o, OwnerType ot, Long oid) {
        return (o.getFromOwnerType() == ot && oid.equals(o.getFromOwnerId()))
                || (o.getToOwnerType() == ot && oid.equals(o.getToOwnerId()));
    }

    private String opText(Operation o) {
        String emoji = switch (o.getType()) {
            case PRIXOD -> "🟢"; case VOZVRAT -> "🔻"; case RASXOD -> "💸";
            case OTKAZMA -> "🔁"; case TOPSHIRIQ -> "🏦";
            case KORREKTIROVKA, BOSHLANGICH -> "⚙️";
        };
        String dir = "";
        if (o.getFromOwnerType() != null && o.getToOwnerType() != null)
            dir = " " + esc(names.owner(o.getFromOwnerType(), o.getFromOwnerId()))
                + "→" + esc(names.owner(o.getToOwnerType(), o.getToOwnerId()));
        else if (o.getFromOwnerType() != null)
            dir = " " + esc(names.owner(o.getFromOwnerType(), o.getFromOwnerId()));
        else if (o.getToOwnerType() != null)
            dir = " " + esc(names.owner(o.getToOwnerType(), o.getToOwnerId()));
        String st = switch (o.getStatus()) {
            case KUTILMOQDA -> " ⏳"; case YOLDA -> " 🚚";
            case RAD_ETILGAN -> " ❌"; case BEKOR -> " 🚫"; default -> "";
        };
        return emoji + " " + o.getOpDate().format(DF) + " <b>" + fmt(o.getAmount())
                + "</b> (" + mtLabel(o.getMoneyType()) + ")" + dir + st;
    }

    /* ============================ 🏪 KASSA PROFILI ============================ */

    /** arg = "<kassaId>:<davr kodi>" */
    private void kassaProfile(String arg, long chatId, int msgId) {
        syncService.syncIfStale(45);
        String[] a = arg.split(":");
        long id = Long.parseLong(a[0]);
        String code = a.length > 1 ? a[1] : "7";
        Kassa k = kassaRepo.findById(id).orElse(null);
        if (k == null) return;
        java.time.LocalDate[] p = periodOf(code);

        Balance n = ledger.view(OwnerType.KASSA, id, MoneyType.NAQD);
        Balance kl = ledger.view(OwnerType.KASSA, id, MoneyType.KLIK);
        long term = dayRepo.findByKassaIdAndDate(id, ledger.today())
                .map(DayRecord::getPrixodTerminal).orElse(0L);
        int open = submissionService.submittableDays(id).size();

        StringBuilder sb = new StringBuilder("🏪 <b>" + esc(k.getName()) + "</b> — profil\n\n");
        sb.append("💵 Naqd: <b>").append(fmt(n.getAmount())).append("</b>")
          .append(n.getReserved() > 0 ? " (rezervda " + fmt(n.getReserved()) + " · mavjud " + fmt(n.available()) + ")" : "").append("\n")
          .append("📲 Click: <b>").append(fmt(kl.getAmount())).append("</b>")
          .append(kl.getReserved() > 0 ? " (rezervda " + fmt(kl.getReserved()) + " · mavjud " + fmt(kl.available()) + ")" : "").append("\n")
          .append("💳 Terminal (bugun): ").append(fmt(term)).append("\n")
          .append("➕ <b>Jami (naqd+click): ").append(fmt(n.getAmount() + kl.getAmount())).append("</b> so'm\n");
        if (open > 0) sb.append("⏳ Topshirilmagan kunlar: <b>").append(open).append("</b>\n");

        List<AppUser> kassirs = userRepo.findByKassaIdAndActiveTrue(id);
        if (!kassirs.isEmpty()) {
            sb.append("\n👤 Kassirlar: ");
            sb.append(esc(String.join(", ", kassirs.stream().map(AppUser::getFullName).toList())));
            sb.append("\n");
        }

        long kirim = 0, chiqim = 0;
        List<String> lines = new ArrayList<>();
        for (Operation o : opRepo.byPeriod(p[0], p[1])) {
            if (!touches(o, OwnerType.KASSA, id)) continue;
            if (o.getStatus() == uz.kassa.domain.OpStatus.TASDIQLANGAN) {
                if (o.getType() == OpType.PRIXOD || o.getType() == OpType.BOSHLANGICH) kirim += o.getAmount();
                if (o.getType() == OpType.RASXOD || o.getType() == OpType.VOZVRAT
                        || o.getType() == OpType.TOPSHIRIQ) chiqim += o.getAmount();
            }
            if (lines.size() < 15) lines.add(opText(o));
        }
        sb.append("\n📜 <b>").append(periodLabel(code)).append("</b>\n")
          .append("🟢 Kirim: <b>").append(fmt(kirim)).append("</b> · 🔴 Chiqim: <b>")
          .append(fmt(chiqim)).append("</b> · ➕ Farq: <b>").append(fmt(kirim - chiqim)).append("</b>\n");
        if (!lines.isEmpty()) sb.append("\n").append(String.join("\n", lines));

        InlineKeyboardMarkup kb = inline(List.of(
                irow(btn("📆 Bugun", "b:kh:" + id + ":t"), btn("7 kun", "b:kh:" + id + ":7"),
                     btn("30 kun", "b:kh:" + id + ":30"), btn("Shu oy", "b:kh:" + id + ":m"))));
        if (msgId > 0) sender.edit(chatId, msgId, sb.toString(), kb);
        else sender.send(chatId, sb.toString(), kb);
    }

    /* ============================ 📊 EXCEL ============================ */

    private void excelMenu(long chatId) {
        sender.send(chatId, "📊 <b>Excel hisobot</b>\n\n"
                + "Davrni tanlang — fayl shu chatga yuboriladi.\n"
                + "<i>Varaqlar: Umumiy · Tranzaksiyalar · MoySklad hujjatlari</i>",
                periodButtons("b:xp"));
    }

    private void runExcel(AppUser u, String code, long chatId, int msgId) {
        java.time.LocalDate[] p = periodOf(code);
        sender.edit(chatId, msgId, "⏳ Excel tayyorlanmoqda: <b>" + periodLabel(code)
                + "</b>\nMoySklad so'ralmoqda, biroz kuting…");
        new Thread(() -> {
            try {
                byte[] xlsx = excelReport.build(p[0], p[1]);
                sender.sendDocument(chatId, xlsx,
                        "kassa-hisobot_" + p[0] + "_" + p[1] + ".xlsx",
                        "📊 Kassa hisoboti: <b>" + periodLabel(code) + "</b>");
            } catch (Exception e) {
                sender.send(chatId, "⚠️ Excel tayyorlashda xato: " + esc(e.getMessage()));
            }
        }).start();
    }
}
