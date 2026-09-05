package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.*;
import uz.kassa.domain.*;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.service.LedgerService;
import uz.kassa.service.NotificationService;
import uz.kassa.service.moysklad.MoySkladClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.*;
import static uz.kassa.bot.handlers.AdminSupport.*;

/**
 * 💼 Boshlang'ich qoldiq va 🛠 Корректировка oqimlari.
 * (AdminHandler dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
public class BalanceAdminHandler {

    private final Sender sender;
    private final NameService names;
    private final LedgerService ledger;
    private final KassaRepo kassaRepo;
    private final NotificationService notify;
    private final uz.kassa.repo.ClickAccountRepo clickRepo;


    /* ==================== 💼 BOSHLANG'ICH QOLDIQ ==================== */

    /** Boshlang'ich qoldiq faqat Основной отдел (buxgalteriya)ga kiritiladi. */
    void ibStart(Session s, long chatId) {
        s.reset(); s.state = Session.State.ADM_IB_NAQD;
        s.data.put("obT", OwnerType.BUXGALTERIYA);
        s.data.put("obId", LedgerService.BUX_ID);
        sender.send(chatId, "💼 <b>Boshlang'ich qoldiq</b> — Основной отдел\n\n"
                + "💵 <b>NAQD</b> boshlang'ich qoldiqni kiriting (so'm, 0 mumkin):", cancelOnly());
    }


    void ibOwner(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_IB_OWNER) return;
        if (arg.equals("B")) {
            s.data.put("obT", OwnerType.BUXGALTERIYA);
            s.data.put("obId", LedgerService.BUX_ID);
        } else {
            s.data.put("obT", OwnerType.KASSA);
            s.data.put("obId", Long.parseLong(arg.substring(1)));
        }
        s.state = Session.State.ADM_IB_NAQD;
        sender.edit(chatId, msgId, "Tanlandi: <b>"
                + esc(names.owner((OwnerType) s.data.get("obT"), s.getLong("obId")))
                + "</b>\n\n💵 <b>NAQD</b> boshlang'ich qoldiqni kiriting (so'm, 0 mumkin):");
    }


    void ibNaqd(Session s, String text, long chatId) {
        long v = text.equals("0") ? 0 : parseAmount(text);
        if (v < 0) { sender.send(chatId, "⚠️ 0 yoki musbat summa kiriting"); return; }
        s.data.put("ibNaqd", v);
        s.state = Session.State.ADM_IB_KLIK;
        sender.send(chatId, "📲 <b>CLICK</b> boshlang'ich qoldiqni kiriting (so'm, 0 mumkin):");
    }


    void ibFinish(AppUser u, Session s, String text, long chatId) {
        long klik = text.equals("0") ? 0 : parseAmount(text);
        if (klik < 0) { sender.send(chatId, "⚠️ 0 yoki musbat summa kiriting"); return; }
        if (s.getLong("ibNaqd") == 0 && klik == 0) {
            s.reset();
            sender.send(chatId, "Ikkala summa ham 0 — hech narsa yozilmadi.");
            return;
        }
        s.data.put("ibKlik", klik);
        s.state = Session.State.ADM_IB_SANA;
        java.time.LocalDate now = java.time.LocalDate.now();
        sender.send(chatId, "📅 <b>Qaysi sanaga kiritilsin?</b>\n\n"
                        + "Tugmani bosing yoki eskiroq sanani o'zingiz yozing (masalan <code>"
                        + now.minusDays(10).format(DF) + "</code>):",
                inline(List.of(
                        irow(btn("📅 Bugun", "a:ibd:0"), btn("Kecha", "a:ibd:1")),
                        irow(btn(now.minusDays(2).format(DF), "a:ibd:2"),
                             btn(now.minusDays(3).format(DF), "a:ibd:3"),
                             btn(now.minusDays(4).format(DF), "a:ibd:4")),
                        irow(btn("🗓 Kalendar", "a:cal:o:ib")),
                        irow(btn("❌ Bekor", "cx")))));
    }


    void ibSanaBtn(AppUser u, Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_IB_SANA) return;
        java.time.LocalDate d = java.time.LocalDate.now().minusDays(Long.parseLong(arg));
        sender.edit(chatId, msgId, "📅 Sana: <b>" + d.format(DF) + "</b>");
        ibCommit(u, s, d, chatId);
    }


    void ibSana(AppUser u, Session s, String text, long chatId) {
        java.time.LocalDate d;
        try { d = java.time.LocalDate.parse(text.trim(), DF); }
        catch (Exception e) {
            try { d = java.time.LocalDate.parse(text.trim()); }
            catch (Exception e2) {
                sender.send(chatId, "⚠️ Sana formati: <code>kun.oy.yil</code> — masalan <code>"
                        + java.time.LocalDate.now().format(DF) + "</code>");
                return;
            }
        }
        if (d.isAfter(java.time.LocalDate.now())) {
            sender.send(chatId, "⚠️ Kelajak sanasi bo'lmaydi. Qaytadan kiriting:");
            return;
        }
        ibCommit(u, s, d, chatId);
    }


    void ibCommit(AppUser u, Session s, java.time.LocalDate date, long chatId) {
        OwnerType ot = (OwnerType) s.data.get("obT");
        long oid = s.getLong("obId");
        long naqd = s.getLong("ibNaqd");
        long klik = s.getLong("ibKlik");
        s.reset();

        if (naqd > 0) ledger.postAdjustment(OpType.BOSHLANGICH, ot, oid, MoneyType.NAQD,
                naqd, "Boshlang'ich qoldiq", u.getId(), date);
        if (klik > 0) ledger.postAdjustment(OpType.BOSHLANGICH, ot, oid, MoneyType.KLIK,
                klik, "Boshlang'ich qoldiq", u.getId(), date);

        sender.send(chatId, "✅ Boshlang'ich qoldiq kiritildi — <b>"
                + esc(names.owner(ot, oid)) + "</b>\n"
                + "📅 Sana: <b>" + date.format(DF) + "</b>\n"
                + "💵 Naqd: <b>" + fmt(naqd) + "</b> so'm\n"
                + "📲 Click: <b>" + fmt(klik) + "</b> so'm");
    }


    /* ==================== 🛠 KORREKTIROVKA (har bir otdel uchun) ==================== */

    /** Korrektirovka: otdel (Buxgalteriya, kassa yoki alohida Click hisobi) tanlanadi. Faqat SuperAdmin. */
    void krStart(Session s, long chatId) {
        s.reset(); s.state = Session.State.ADM_KR_OWNER;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("🏦 Буxгалтерия (Основной)", "a:kro:B")));
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;   // B5: cashless'da korrektirovka ham yo'q
            rows.add(irow(btn("🏪 " + k.getName(), "a:kro:K" + k.getId())));
        }
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn("📲 " + c.getName(), "a:kro:C" + c.getId())));
        rows.add(irow(btn("❌ Bekor", "cx")));
        sender.send(chatId, "🛠 <b>Корректировка</b>\n\n"
                + "Balans qo'lda tuzatiladigan otdelni tanlang:", inline(rows));
    }


    void krOwner(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_KR_OWNER) return;
        if (arg.equals("B")) {
            s.data.put("krT", OwnerType.BUXGALTERIYA);
            s.data.put("krId", LedgerService.BUX_ID);
        } else if (arg.startsWith("C")) {
            s.data.put("krT", OwnerType.CLICK);
            s.data.put("krId", Long.parseLong(arg.substring(1)));
        } else {
            s.data.put("krT", OwnerType.KASSA);
            s.data.put("krId", Long.parseLong(arg.substring(1)));
        }
        OwnerType ot = (OwnerType) s.data.get("krT");
        long oid = s.getLong("krId");

        // Click hisobida faqat KLIK bo'ladi — pul turi so'ralmaydi, to'g'ridan-to'g'ri sanaga o'tadi
        if (ot == OwnerType.CLICK) { krProceedToSana(s, MoneyType.KLIK, chatId, msgId); return; }

        long n = ledger.view(ot, oid, MoneyType.NAQD).getAmount();
        long k = ledger.view(ot, oid, MoneyType.KLIK).getAmount();
        s.state = Session.State.ADM_KR_MT;
        sender.edit(chatId, msgId, "🛠 <b>" + esc(names.owner(ot, oid)) + "</b>\n\n"
                        + "💵 Naqd: <b>" + fmt(n) + "</b> so'm\n"
                        + "📲 Click: <b>" + fmt(k) + "</b> so'm\n\n"
                        + "Qaysi pul turi tuzatiladi?",
                inline(List.of(
                        irow(btn("💵 Naqd", "a:krm:NAQD"), btn("📲 Click", "a:krm:KLIK")),
                        irow(btn("❌ Bekor", "cx")))));
    }


    /** Pul turi tanlandi — AVVAL sana so'raladi (keyin soat, keyin summa). */
    void krMt(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_KR_MT) return;
        krProceedToSana(s, MoneyType.valueOf(arg), chatId, msgId);
    }


    void krProceedToSana(Session s, MoneyType mt, long chatId, int msgId) {
        s.data.put("krMt", mt);
        long cur = ledger.view((OwnerType) s.data.get("krT"), s.getLong("krId"), mt).getAmount();
        s.state = Session.State.ADM_KR_SANA;
        String txt = "🛠 <b>" + esc(names.owner((OwnerType) s.data.get("krT"),
                        s.getLong("krId"))) + "</b> — " + mtLabel(mt)
                + "\nJoriy balans: <b>" + fmt(cur) + "</b> so'm\n\n"
                + "📅 <b>Qaysi sana bilan korrektirovka qilinsin?</b>";
        InlineKeyboardMarkup kb = inline(List.of(
                irow(btn("📅 Bugun", "a:krd:0"), btn("Kecha", "a:krd:1")),
                irow(btn("🗓 Kalendar", "a:cal:o:kr")),
                irow(btn("❌ Bekor", "cx"))));
        if (msgId > 0) sender.edit(chatId, msgId, txt, kb);
        else sender.send(chatId, txt, kb);
    }


    void krSanaBtn(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_KR_SANA) return;
        krSanaChosen(s, ledger.today().minusDays(Long.parseLong(arg)), chatId, msgId);
    }


    /** Sana tanlandi (tugma yoki kalendar) — TO'G'RIDAN-TO'G'RI summa so'raladi.
     *  Soat (HH:mm) bosqichi OLIB TASHLANDI: u hech narsaga ta'sir qilmasdi
     *  (faqat izohga yozilardi) va «vaqt ishlamayapti» degan chalkashlik berardi. */
    void krSanaChosen(Session s, java.time.LocalDate d, long chatId, int msgId) {
        s.data.put("krDate", d.toString());
        krAskSum(s, chatId, msgId);
    }


    /** Eski xabarlardagi «⏱ Hozirgi vaqt» tugmasi — soat bosqichi olib tashlangan, no-op. */
    void krVaqtNow(AppUser u, Session s, long chatId, int msgId) { }


    /** Eski holatdan qolgan matn — soat bosqichi olib tashlangan, no-op. */
    void krVaqt(AppUser u, Session s, String text, long chatId) { }


    /** Summa so'raladi (o'tgan sanada — o'sha kungi balansga nisbatan). */
    void krAskSum(Session s, long chatId, int msgId) {
        MoneyType mt = (MoneyType) s.data.get("krMt");
        OwnerType ot = (OwnerType) s.data.get("krT");
        long oid = s.getLong("krId");
        java.time.LocalDate date = java.time.LocalDate.parse(s.getStr("krDate"));
        long cur = ledger.view(ot, oid, mt).getAmount();
        // O'tgan sana: o'sha kun oxiridagi balans — keyingi kunlarning harakatlarisiz.
        // Tuzatish shu qiymatga nisbatan kiritiladi, bugungi prixod-rasxodlar saqlanadi.
        boolean past = date.isBefore(ledger.today());
        long asOf = past ? ledger.balanceAsOf(ot, oid, mt, date) : cur;
        s.data.put("krAsOf", asOf);
        s.state = Session.State.ADM_KR_SUM;
        StringBuilder txt = new StringBuilder("📅 <b>" + date.format(DF) + "</b> — "
                + mtLabel(mt) + "\n");
        if (past) {
            txt.append("📆 ").append(date.format(DF)).append(" kun oxiridagi balans: <b>")
               .append(fmt(asOf)).append("</b> so'm\n")
               .append("📊 Hozirgi balans: <b>").append(fmt(cur))
               .append("</b> so'm (keyingi kunlar harakatlari bilan)\n");
        } else {
            txt.append("Joriy balans: <b>").append(fmt(cur)).append("</b> so'm\n");
        }
        txt.append("\nTuzatish summasini kiriting:\n")
           .append("• musbat — qo'shiladi (masalan <code>500000</code>)\n")
           .append("• manfiy — ayriladi (masalan <code>-500000</code>)\n")
           .append("• yoki <code>=</code> bilan O'SHA KUNGI bo'lishi kerak bo'lgan balans ")
           .append("(masalan <code>=423461000</code>) — farqni tizim o'zi hisoblaydi");
        if (past) txt.append("\n\nℹ️ Keyingi kunlardagi prixod-rasxodlar saqlanadi — "
                + "ular tuzatish ustiga qo'shilib boradi.");
        InlineKeyboardMarkup kb = inline(List.of(irow(btn("❌ Bekor", "cx"))));
        if (msgId > 0) sender.edit(chatId, msgId, txt.toString(), kb);
        else sender.send(chatId, txt.toString(), kb);
    }


    void krSum(Session s, String text, long chatId) {
        String t = text.trim().replace(" ", "");
        long asOf = s.getLong("krAsOf");

        // «=maqsad» — o'sha kungi balans shu bo'lishi kerak; farqni tizim hisoblaydi
        if (t.startsWith("=")) {
            String body = t.substring(1);
            boolean negTarget = body.startsWith("-");
            long target = parseAmount(body);
            if (target < 0) {
                sender.send(chatId, "⚠️ Maqsad balansni raqamda kiriting, masalan "
                        + "<code>=423461000</code>");
                return;
            }
            if (negTarget) target = -target;
            long delta = target - asOf;
            if (delta == 0) {
                sender.send(chatId, "ℹ️ Balans allaqachon <b>" + fmt(target)
                        + "</b> so'm — tuzatish shart emas. Boshqa summa kiriting yoki bekor qiling:",
                        cancelOnly());
                return;
            }
            // Maqsad rejimi: TARGET saqlanadi — tasdiqlash va commit paytida farq
            // BALANS QAYTA O'QILIB yangidan hisoblanadi (oradagi sinxron adashtirmasin)
            s.data.put("krTarget", target);
            s.data.put("krSum", delta);
            s.state = Session.State.ADM_KR_IZOH;
            sender.send(chatId, "📆 O'sha kungi balans: <b>" + fmt(asOf) + "</b> → maqsad: <b>"
                    + fmt(target) + "</b> so'm\nFarq (tuzatish): <b>"
                    + (delta > 0 ? "+" : "") + fmt(delta) + "</b> so'm\n\n"
                    + "✍️ Sababini yozing (auditda saqlanadi):", cancelOnly());
            return;
        }

        boolean neg = t.startsWith("-");
        long v = parseAmount(t);
        if (v <= 0) {
            sender.send(chatId, "⚠️ Nolga teng bo'lmagan summa kiriting, masalan "
                    + "<code>500000</code>, <code>-500000</code> yoki <code>=423461000</code>");
            return;
        }
        s.data.put("krSum", neg ? -v : v);
        s.state = Session.State.ADM_KR_IZOH;
        sender.send(chatId, "Summa: <b>" + fmt(neg ? -v : v) + "</b> so'm\n\n"
                + "✍️ Sababini yozing (auditda saqlanadi):", cancelOnly());
    }


    /** Sabab olindi — TASDIQLASH ekrani ko'rsatiladi (darhol qo'llanMAYdi). */
    void krIzoh(AppUser u, Session s, String text, long chatId) {
        s.data.put("krReason", text.trim().equals("-") ? "Korrektirovka" : text.trim());
        s.state = Session.State.IDLE;   // endi faqat tasdiq tugmasi kutiladi
        krConfirmScreen(s, chatId);
    }


    /**
     * TASDIQLASH ekrani (K1/K2): balans shu yerda QAYTA o'qiladi, preview
     * ko'rsatiladi va faqat «✅ Tasdiqlayman» bosilgandagina qo'llanadi.
     */
    void krConfirmScreen(Session s, long chatId) {
        OwnerType ot = (OwnerType) s.data.get("krT");
        long oid = s.getLong("krId");
        MoneyType mt = (MoneyType) s.data.get("krMt");
        java.time.LocalDate date = java.time.LocalDate.parse(s.getStr("krDate"));
        boolean past = date.isBefore(ledger.today());
        long cur = ledger.view(ot, oid, mt).getAmount();
        long asOf = past ? ledger.balanceAsOf(ot, oid, mt, date) : cur;   // YANGIDAN o'qildi
        Long target = s.data.get("krTarget") == null ? null : s.getLong("krTarget");
        long sum = target != null ? target - asOf : s.getLong("krSum");
        if (sum == 0) {
            s.reset();
            sender.send(chatId, "ℹ️ Balans allaqachon kerakli qiymatda — tuzatish shart emas.");
            return;
        }
        s.data.put("krAsOf", asOf);
        s.data.put("krSum", sum);
        String txt = "🛠 <b>TASDIQLASH — Korrektirovka</b>\n\n"
                + "🏪 " + esc(names.owner(ot, oid)) + " · " + mtLabel(mt) + "\n"
                + "📅 Sana: <b>" + date.format(DF) + "</b>\n"
                + (past ? "📆 O'sha kun oxiri: <b>" + fmt(asOf) + "</b> → <b>"
                        + fmt(asOf + sum) + "</b> so'm\n" : "")
                + "📊 Hozirgi balans: <b>" + fmt(cur) + "</b> → <b>" + fmt(cur + sum) + "</b> so'm\n"
                + "Tuzatish: <b>" + (sum > 0 ? "+" : "") + fmt(sum) + "</b> so'm\n"
                + "Sabab: " + esc(s.getStr("krReason")) + "\n\n"
                + "Hammasi to'g'rimi?";
        sender.send(chatId, txt, inline(List.of(
                irow(btn("✅ Tasdiqlayman", "a:krok"), btn("❌ Bekor", "cx")))));
    }


    /**
     * ✅ Tasdiqlandi (callback) — commit OLDIDAN balans YANA qayta o'qiladi:
     * tasdiqlashdan buyon 30-soniyalik sinxron o'tgan bo'lsa ham «=maqsad»
     * aynan maqsad qiymatga tushadi (K1 tuzatildi).
     */
    void krCommit(AppUser u, Session s, long chatId, int msgId) {
        if (s.data.get("krT") == null || s.data.get("krReason") == null
                || s.data.get("krMt") == null || s.getStr("krDate") == null) {
            sender.edit(chatId, msgId, "⚠️ Bu tasdiqlash eskirgan — «🛠 Корректировка»ni "
                    + "qaytadan boshlang.");
            return;
        }
        OwnerType ot = (OwnerType) s.data.get("krT");
        long oid = s.getLong("krId");
        MoneyType mt = (MoneyType) s.data.get("krMt");
        java.time.LocalDate date = java.time.LocalDate.parse(s.getStr("krDate"));
        boolean past = date.isBefore(ledger.today());
        long cur = ledger.view(ot, oid, mt).getAmount();
        long asOf = past ? ledger.balanceAsOf(ot, oid, mt, date) : cur;   // commit oldidan yana
        Long target = s.data.get("krTarget") == null ? null : s.getLong("krTarget");
        long sum = target != null ? target - asOf : s.getLong("krSum");
        String reasonBase = s.getStr("krReason");
        s.reset();
        if (sum == 0) {
            sender.edit(chatId, msgId, "ℹ️ Balans allaqachon kerakli qiymatda — tuzatish yozilmadi.");
            return;
        }
        String reason = reasonBase + " [" + date.format(DF) + "]";

        ledger.postAdjustment(OpType.KORREKTIROVKA, ot, oid, mt, sum, reason, u.getId(), date);
        long after = ledger.view(ot, oid, mt).getAmount();
        String owner = names.owner(ot, oid);
        // O'tgan sana: o'sha kun oxiri endi qancha bo'ldi — bugungi harakatlar saqlangan
        String asOfLine = past
                ? "📆 " + date.format(DF) + " kun oxiri endi: <b>" + fmt(asOf + sum) + "</b> so'm\n"
                : "";

        sender.edit(chatId, msgId, "✅ <b>Korrektirovka bajarildi</b> — " + esc(owner) + "\n"
                + mtLabel(mt) + ": <b>" + (sum > 0 ? "+" : "") + fmt(sum) + "</b> so'm\n"
                + "📅 Sana: <b>" + date.format(DF) + "</b>\n"
                + asOfLine
                + "Hozirgi balans: <b>" + fmt(after) + "</b> so'm"
                + (past ? " (keyingi kunlar harakatlari bilan)" : "") + "\n"
                + "Sabab: " + esc(reasonBase));

        String info = "🛠 Korrektirovka — <b>" + esc(owner) + "</b>: <b>"
                + (sum > 0 ? "+" : "") + fmt(sum) + "</b> so'm (" + mtLabel(mt) + ")\n"
                + "📅 Sana: <b>" + date.format(DF) + "</b>\n"
                + asOfLine
                + "Hozirgi balans: <b>" + fmt(after) + "</b> so'm\n"
                + "Sabab: " + esc(reason) + "\nKim: " + esc(u.getFullName());
        notify.toBuxgalteriya(info, null);
        if (ot == OwnerType.KASSA) notify.toKassa(oid, info, null);
    }

}
