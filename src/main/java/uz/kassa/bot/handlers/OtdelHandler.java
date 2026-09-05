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
 * 🏬 Отдел bo'limi: kassa kartasi (tushum, topshirilmagan pul, rasxod, davr statistikasi) va 💰 kassadan pul qabul qilish oqimi.
 * (AdminHandler dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
public class OtdelHandler {

    private final Sender sender;
    private final NameService names;
    private final LedgerService ledger;
    private final AppUserRepo userRepo;
    private final KassaRepo kassaRepo;
    private final uz.kassa.repo.DayRepo dayRepo;
    private final uz.kassa.repo.OperationRepo opRepo;
    private final NotificationService notify;
    private final uz.kassa.service.SubmissionService submissionService;
    private final uz.kassa.repo.SubmissionRepo subRepo;
    private final AdminSupport sup;


    /* ---------- 🏬 ОТДЕЛ ---------- */

    void otdel(long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn(OSN_LABEL, "a:p:bq")));
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;   // B5: pul yuritilmaydigan kassa panelda ko'rinmaydi
            rows.add(irow(btn("🏪 " + k.getName(), "a:p:k:" + k.getId())));
        }
        rows.add(irow(sup.bk("a:p:main")));
        sup.show(chatId, msgId, "🏬 <b>Отдел</b>\n\nKassani tanlang:", rows);
    }


    void kassaMenu(long id, long chatId, int msgId) {
        String name = names.owner(OwnerType.KASSA, id);
        sup.show(chatId, msgId, "🏪 <b>" + esc(name) + "</b>\n\nBo'limni tanlang:", List.of(
                irow(btn("💰 Бугунги тушум", "a:p:kt:" + id)),
                irow(btn("💸 Расход", "a:p:kr:" + id)),
                irow(btn("💵 Топширилмаган пул", "a:p:kq:" + id)),
                irow(btn("📆 Давр танлаш", "a:p:kd:" + id)),
                irow(sup.bk("a:p:otd"))));
    }


    /** Бугунги тушум: Касса (naqd) va Click bo'lib ko'rsatiladi. */
    void kassaTushum(Session s, long id, long chatId, int msgId) {
        DayRecord d = dayRepo.findByKassaIdAndDate(id, ledger.today()).orElse(null);
        long n = d == null ? 0 : d.getPrixodNaqd();
        long k = d == null ? 0 : d.getPrixodKlik();
        long t = d == null ? 0 : d.getPrixodTerminal();
        String text = "💰 <b>Бугунги тушум</b> — "
                + esc(names.owner(OwnerType.KASSA, id)) + "\n📅 " + ledger.today().format(DF) + "\n\n"
                + "💵 Касса (нақд): <b>" + fmt(n) + "</b> so'm\n"
                + "📲 Click: <b>" + fmt(k) + "</b> so'm\n"
                + "💳 Terminal: <b>" + fmt(t) + "</b> so'm\n"
                + "➕ <b>Жами: " + fmt(n + k + t) + "</b> so'm";
        InlineKeyboardMarkup qb = inline(List.of(
                irow(btn("💰 Пулларни қабул қилиш", "a:p:qb:" + id))));
        if (msgId > 0) sender.edit(chatId, msgId, text, qb);
        else sup.sendContent(s, chatId, text, qb);
    }


    /**
     * 💵 Топширилмаган пул — kassaning buxgalteriyaga hali topshirilmagan puli:
     * joriy qo'ldagi qoldiq (naqd/click) + topshirilmagan yopilgan kunlar ro'yxati.
     */
    void kassaTopshirilmagan(Session s, long id, long chatId, int msgId) {
        Balance n = ledger.view(OwnerType.KASSA, id, MoneyType.NAQD);
        Balance k = ledger.view(OwnerType.KASSA, id, MoneyType.KLIK);
        List<DayRecord> days = submissionService.submittableDays(id);

        StringBuilder sb = new StringBuilder("💵 <b>Топширилмаган пул</b> — "
                + esc(names.owner(OwnerType.KASSA, id)) + "\n\n"
                + "Қўлдаги қолдиқ (буxгалтерияга топширилмаган):\n"
                + "💵 Нақд: <b>" + fmt(n.getAmount()) + "</b> so'm"
                + (n.getReserved() > 0 ? " (банд: " + fmt(n.getReserved()) + ")" : "") + "\n"
                + "📲 Click: <b>" + fmt(k.getAmount()) + "</b> so'm"
                + (k.getReserved() > 0 ? " (банд: " + fmt(k.getReserved()) + ")" : "") + "\n"
                + "➕ <b>Жами: " + fmt(n.getAmount() + k.getAmount()) + "</b> so'm\n");

        if (days.isEmpty()) {
            sb.append("\n✅ Топширилмаган ёпилган кун йўқ");
        } else {
            long dn = 0, dk = 0;
            sb.append("\n⏳ Топширилмаган ёпилган кунлар: <b>").append(days.size()).append("</b> та\n");
            for (DayRecord d : days) {
                dn += d.remainNaqd(); dk += d.remainKlik();
                sb.append("• ").append(d.getDate().format(DF))
                  .append(" — Нақд ").append(fmt(d.remainNaqd()))
                  .append(" · Click ").append(fmt(d.remainKlik())).append("\n");
            }
            sb.append("Кунлар жами: Нақд <b>").append(fmt(dn))
              .append("</b> · Click <b>").append(fmt(dk)).append("</b> so'm");
        }
        // Kassir yuborgan, hali ko'rib chiqilmagan hisobotlar — bevosita qabul
        // taqiqlanadi, avval shu hisobotlar qabul/rad qilinishi kerak
        List<Submission> pend = subRepo.findByKassaIdAndStatusOrderByIdAsc(id, SubmissionStatus.KUTILMOQDA);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (!pend.isEmpty()) {
            sb.append("\n\n📤 <b>Кўриб чиқилмаган ҳисобот: ").append(pend.size()).append(" та</b>\n");
            for (Submission x : pend)
                sb.append("• #").append(x.getId()).append(" — Нақд ").append(fmt(x.getNaqd()))
                  .append(" · Click ").append(fmt(x.getKlik())).append("\n");
            sb.append("⚠️ Аввал шу ҳисобот(лар)ни қабул қилинг ёки рад этинг — "
                    + "ундан кейин пулни бевосита қабул қилиш мумкин.");
            rows.add(irow(btn("📥 Ҳисоботларни кўриш", "a:p:pnd:" + id)));
        }
        rows.add(irow(btn("💰 Пулларни қабул қилиш", "a:p:qb:" + id)));
        InlineKeyboardMarkup qb = inline(rows);
        if (msgId > 0) sender.edit(chatId, msgId, sb.toString(), qb);
        else sup.sendContent(s, chatId, sb.toString(), qb);
    }


    /** 🏦 Основной отдел (Buxgalteriya) pul qoldig'i + kassalarda turgan topshirilmagan pul. */
    void osnovnoyQoldiq(Session s, long chatId) {
        Balance n = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD);
        Balance k = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.KLIK);
        long kn = 0, kk = 0;
        for (Kassa kas : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            kn += ledger.view(OwnerType.KASSA, kas.getId(), MoneyType.NAQD).getAmount();
            kk += ledger.view(OwnerType.KASSA, kas.getId(), MoneyType.KLIK).getAmount();
        }
        String text = "🏦 <b>Отдел основной</b> (Буxгалтерия) — пул қолдиғи\n\n"
                + "💵 Нақд: <b>" + fmt(n.getAmount()) + "</b> so'm"
                + (n.getReserved() > 0 ? " (банд: " + fmt(n.getReserved()) + ")" : "") + "\n"
                + "📲 Click: <b>" + fmt(k.getAmount()) + "</b> so'm"
                + (k.getReserved() > 0 ? " (банд: " + fmt(k.getReserved()) + ")" : "") + "\n"
                + "➕ <b>Жами: " + fmt(n.getAmount() + k.getAmount()) + "</b> so'm\n\n"
                + "🏪 Кассаларда (ҳали топширилмаган):\n"
                + "💵 Нақд: <b>" + fmt(kn) + "</b> · 📲 Click: <b>" + fmt(kk) + "</b>\n"
                + "➕ <b>Жами: " + fmt(kn + kk) + "</b> so'm";
        sup.sendContent(s, chatId, text, null);
    }


    /** 💸 Расход — oxirgi 7 kunlik chiqimlar + kassa nomidan rasxod kiritish tugmasi. */
    void kassaRasxodPanel(Session s, long id, long chatId) {
        java.time.LocalDate to = ledger.today();
        java.time.LocalDate from = to.minusDays(6);
        StringBuilder sb = new StringBuilder("💸 <b>Расход</b> — "
                + esc(names.owner(OwnerType.KASSA, id)) + "\n📅 "
                + from.format(DF) + " — " + to.format(DF) + "\n");
        long rn = 0, rk = 0;
        int shown = 0;
        for (Operation o : opRepo.byPeriod(from, to)) {
            if (o.getType() != OpType.RASXOD || o.getStatus() != OpStatus.TASDIQLANGAN) continue;
            if (o.getFromOwnerType() != OwnerType.KASSA || !Long.valueOf(id).equals(o.getFromOwnerId())) continue;
            if (o.getMoneyType() == MoneyType.KLIK) rk += o.getAmount(); else rn += o.getAmount();
            if (shown++ >= 20) continue;
            sb.append("\n• ").append(o.getOpDate().format(DF)).append(" — <b>")
              .append(fmt(o.getAmount())).append("</b> so'm (")
              .append(mtLabel(o.getMoneyType())).append(")")
              .append(o.getComment() == null || o.getComment().isEmpty()
                      ? "" : " — " + esc(o.getComment()));
        }
        if (shown == 0) sb.append("\nBu davrda rasxod yo'q");
        sb.append("\n\n💵 Нақд: <b>").append(fmt(rn)).append("</b> · 📲 Клик: <b>")
          .append(fmt(rk)).append("</b>\n➕ <b>Жами: ").append(fmt(rn + rk)).append("</b> so'm");
        sup.sendContent(s, chatId, sb.toString(), null);
    }


    void kassaDavr(long id, long chatId, int msgId) {
        sup.show(chatId, msgId, "📆 <b>Давр танлаш</b> — "
                + esc(names.owner(OwnerType.KASSA, id)), List.of(
                irow(btn("Bugun", "a:p:kpp:" + id + ":t"), btn("Kecha", "a:p:kpp:" + id + ":y")),
                irow(btn("7 kun", "a:p:kpp:" + id + ":7"), btn("30 kun", "a:p:kpp:" + id + ":30"),
                     btn("Shu oy", "a:p:kpp:" + id + ":m")),
                irow(btn("🗓 Kalendar", "a:cal:o:k" + id)),
                irow(sup.bk("a:p:k:" + id))));
    }


    void kassaPeriodStats(Session s, long id, String code, long chatId, int msgId) {
        java.time.LocalDate[] p = sup.periodOf(code);
        kassaPeriodRange(s, id, p[0], p[1], chatId, msgId);
    }


    void kassaPeriodRange(Session s, long id, java.time.LocalDate from,
                                  java.time.LocalDate to, long chatId, int msgId) {
        long kn = 0, kk = 0, rn = 0, rk = 0;
        for (Operation o : opRepo.byPeriod(from, to)) {
            boolean in = o.getToOwnerType() == OwnerType.KASSA && Long.valueOf(id).equals(o.getToOwnerId());
            boolean out = o.getFromOwnerType() == OwnerType.KASSA && Long.valueOf(id).equals(o.getFromOwnerId());
            if (o.getType() == OpType.PRIXOD && in) {
                if (o.getMoneyType() == MoneyType.KLIK) kk += o.getAmount(); else kn += o.getAmount();
            }
            if (o.getType() == OpType.RASXOD && out) {
                if (o.getMoneyType() == MoneyType.KLIK) rk += o.getAmount(); else rn += o.getAmount();
            }
        }
        String text = "📆 <b>" + sup.rangeLabel(from, to) + "</b> — "
                + esc(names.owner(OwnerType.KASSA, id)) + "\n\n"
                + "🟢 Тушум: 💵 <b>" + fmt(kn) + "</b> · 📲 <b>" + fmt(kk) + "</b>\n"
                + "🔴 Расход: 💵 <b>" + fmt(rn) + "</b> · 📲 <b>" + fmt(rk) + "</b>\n"
                + "➕ <b>Фарқ: " + fmt(kn + kk - rn - rk) + "</b> so'm";
        InlineKeyboardMarkup qb = inline(List.of(
                irow(btn("💰 Пулларни қабул қилиш", "a:p:qb:" + id))));
        if (msgId > 0) sender.edit(chatId, msgId, text, qb);
        else sup.sendContent(s, chatId, text, qb);
    }


    /* ---------- 💰 ПУЛЛАРНИ ҚАБУЛ ҚИЛИШ ---------- */

    void qbStart(Session s, long kassaId, long chatId) {
        sup.sendContent(s, chatId, "💰 <b>Пулларни қабул қилиш</b> — "
                        + esc(names.owner(OwnerType.KASSA, kassaId)) + "\n\nPul turini tanlang:\n"
                        + "<i>📲 Klik qabul qilinmaydi — u kassaning o'z hisobida yig'iladi, "
                        + "hisoboti «📤 Hisobot topshirish» orqali yopiladi.</i>",
                inline(List.of(
                        irow(btn("💵 Нақд", "a:p:qm:" + kassaId + ":NAQD"),
                             btn("💳 Терминал", "a:p:qm:" + kassaId + ":TERMINAL")),
                        irow(btn("❌ Bekor", "cx")))));
    }


    void qbMoney(Session s, long kassaId, String mt, long chatId, int msgId) {
        s.data.put("qbKassa", kassaId);
        s.data.put("qbMt", mt);
        s.state = Session.State.ADM_QB_SUM;
        String extra = "";
        if (MoneyType.valueOf(mt) == MoneyType.NAQD) {
            long avail = ledger.view(OwnerType.KASSA, kassaId, MoneyType.NAQD).available();
            extra = "\n💼 Kassada mavjud: <b>" + fmt(avail) + "</b> so'm"
                    + (avail <= 0 ? "\n⚠️ Mavjud pul yo'q — qabul o'tmaydi." : "");
        }
        sender.edit(chatId, msgId, "💰 " + esc(names.owner(OwnerType.KASSA, kassaId))
                + " — " + mtLabel(MoneyType.valueOf(mt)) + extra
                + "\n\n<b>Olingan summani kiriting</b> (so'm):");
    }


    void qbSum(AppUser u, Session s, String text, long chatId) {
        long sum = parseAmount(text);
        if (sum <= 0) { sender.send(chatId, "⚠️ Musbat summa kiriting:"); return; }
        // NAQD: mavjud qoldiqdan ko'p qabul qilib bo'lmaydi (balans 0 — pul yo'q)
        if (MoneyType.valueOf(s.getStr("qbMt")) == MoneyType.NAQD) {
            long avail = ledger.view(OwnerType.KASSA, s.getLong("qbKassa"), MoneyType.NAQD).available();
            if (sum > avail) {
                sender.send(chatId, "⚠️ Kassada mavjud: <b>" + fmt(avail) + "</b> so'm — "
                        + "undan ko'p qabul qilib bo'lmaydi.\n"
                        + "Agar pul haqiqatan kassada bo'lsa — avval MoySklad kirim "
                        + "hujjatlarini tekshiring.\nBoshqa summa kiriting:");
                return;
            }
        }
        s.data.put("qbSum", sum);
        s.state = Session.State.IDLE;

        long kassaId = s.getLong("qbKassa");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        // Avval shu kassaning kassirlari, keyin qolgan faol foydalanuvchilar
        List<AppUser> own = userRepo.findByKassaIdAndActiveTrue(kassaId);
        for (AppUser x : own)
            rows.add(irow(btn("👤 " + x.getFullName(), "a:qbu:" + x.getId())));
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc()) {
            if (own.stream().anyMatch(o -> o.getId().equals(x.getId()))) continue;
            if (x.getId().equals(u.getId())) continue;
            if (rows.size() >= 12) break;
            rows.add(irow(btn(x.getFullName(), "a:qbu:" + x.getId())));
        }
        rows.add(irow(btn("❌ Bekor", "cx")));
        sup.sendContent(s, chatId, "💰 Summa: <b>" + fmt(sum) + "</b> so'm ("
                + mtLabel(MoneyType.valueOf(s.getStr("qbMt"))) + ")\n\n"
                + "<b>Kim topshirdi?</b>", inline(rows));
    }


    /** Kim topshirgani tanlandi — endi QAYSI SANA uchun qabul qilinishi so'raladi. */
    void qbUser(AppUser u, Session s, String arg, long chatId, int msgId) {
        if (s.data.get("qbSum") == null) return;
        String topshirgan = userRepo.findById(Long.parseLong(arg))
                .map(AppUser::getFullName).orElse("?");
        s.data.put("qbWho", topshirgan);
        sender.edit(chatId, msgId, "💰 Summa: <b>" + fmt(s.getLong("qbSum")) + "</b> so'm ("
                + mtLabel(MoneyType.valueOf(s.getStr("qbMt"))) + ")\n"
                + "👤 Topshirdi: <b>" + esc(topshirgan) + "</b>\n\n"
                + "📅 <b>Qaysi sana uchun qabul qilinsin?</b>", inline(List.of(
                irow(btn("📅 Bugun", "a:qbd:0"), btn("Kecha", "a:qbd:1")),
                irow(btn("🗓 Kalendar", "a:cal:o:q")),
                irow(btn("❌ Bekor", "cx")))));
    }


    void qbDate(AppUser u, Session s, String arg, long chatId, int msgId) {
        if (s.data.get("qbWho") == null) return;
        qbCommit(u, s, ledger.today().minusDays(Long.parseLong(arg)), chatId, msgId);
    }


    void qbCommit(AppUser u, Session s, java.time.LocalDate date, long chatId, int msgId) {
        if (s.data.get("qbSum") == null || s.data.get("qbWho") == null) return;
        long kassaId = s.getLong("qbKassa");
        MoneyType mt = MoneyType.valueOf(s.getStr("qbMt"));
        long sum = s.getLong("qbSum");
        String topshirgan = s.getStr("qbWho");
        // Avval amal — xato bo'lsa (mavjud yetarli emas / kutilayotgan hisobot bor)
        // sessiya saqlanib qoladi va foydalanuvchi xabarni ko'radi
        var op = submissionService.directCollect(kassaId, mt, sum, u, topshirgan, date);
        String nav = s.getStr("nav");
        Object pm = s.data.get("panelMsg");
        s.reset();
        if (nav != null) s.data.put("nav", nav);   // panel navigatsiyasi saqlanadi
        if (pm != null) s.data.put("panelMsg", pm);
        s.data.put("contentMsg", msgId);           // tasdiq xabari — joriy kontent
        String kassaName = names.owner(OwnerType.KASSA, kassaId);
        sender.edit(chatId, msgId, "✅ <b>Pul qabul qilindi</b> #" + op.getId() + "\n\n"
                + "🏪 Kassa: <b>" + esc(kassaName) + "</b> → 🏦 Buxgalteriya\n"
                + "💰 Summa: <b>" + fmt(sum) + "</b> so'm (" + mtLabel(mt) + ")\n"
                + "📅 Sana: <b>" + date.format(DF) + "</b>\n"
                + "👤 Topshirdi: <b>" + esc(topshirgan) + "</b>\n"
                + "✍️ Qabul qildi: " + esc(u.getFullName())
                + (mt == MoneyType.TERMINAL
                    ? "\n\nℹ️ Terminal puli faqat jurnalga yozildi — u firma bank hisobida."
                    : "\n\nKassa balansidan yechildi, Buxgalteriyaga qo'shildi."));
        notify.toKassa(kassaId, "💰 Buxgalteriya kassangizdan pul qabul qildi: <b>"
                + fmt(sum) + "</b> so'm (" + mtLabel(mt) + ")\n📅 Sana: "
                + date.format(DF) + "\nTopshirdi: " + esc(topshirgan), null);
    }

}
