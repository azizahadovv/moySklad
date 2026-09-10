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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.*;
import static uz.kassa.bot.handlers.AdminSupport.*;

/**
 * 📈 Статистика: buxgalteriya hisoboti, saldo, Свод (Excel), bugungi tushum/rasxod ko'rinishlari, 📋 audit.
 * (AdminHandler dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
public class StatsHandler {

    private final Sender sender;
    private final NameService names;
    private final LedgerService ledger;
    private final AppUserRepo userRepo;
    private final KassaRepo kassaRepo;
    private final uz.kassa.repo.DayRepo dayRepo;
    private final uz.kassa.repo.OperationRepo opRepo;
    private final uz.kassa.repo.DebtRepo debtRepo;
    private final uz.kassa.webapp.ExcelReportService excelReport;
    private final uz.kassa.service.moysklad.MoySkladSyncService syncService;
    private final uz.kassa.repo.AuditRepo auditRepo;
    private final uz.kassa.config.AppProps props;
    private final uz.kassa.webapp.AdminMoneyReportService moneyReport;
    private final uz.kassa.service.SubmissionService submissionService;
    private final NotificationService notify;
    private final AdminSupport sup;


    /* ---------- 🏦 ТОПШИРИЛГАН ПУЛЛАР (davr bo'yicha qabul qilingan pullar) ---------- */

    void topshirilgan(AppUser u, Session s, long chatId, int msgId, String code) {
        java.time.LocalDate[] p = sup.periodOf(code);
        topshirilganRange(u, s, chatId, msgId, p[0], p[1]);
    }


    /**
     * Kassalardan buxgalteriyaga QABUL QILINGAN pullar (TOPSHIRIQ: bevosita yoki
     * kassir hisoboti orqali) — kassa kesimida, har bir qabul alohida qatorda;
     * pastda kassir hisobotlari xulosasi. Ma'lumot Mini App bilan bir manbadan
     * (AdminMoneyReportService.money), Excel ham o'sha.
     */
    @SuppressWarnings("unchecked")
    void topshirilganRange(AppUser u, Session s, long chatId, int msgId,
                           java.time.LocalDate from, java.time.LocalDate to) {
        Map<String, Object> d = moneyReport.money(from, to, null);
        Map<String, Object> ct = (Map<String, Object>) d.get("colTotals");
        Map<String, Object> st = (Map<String, Object>) d.get("subTotals");
        List<Map<String, Object>> cols = (List<Map<String, Object>>) d.get("collections");
        List<Map<String, Object>> perKassa = (List<Map<String, Object>>) d.get("perKassa");

        StringBuilder sb = new StringBuilder("🏦 <b>ТОПШИРИЛГАН ПУЛЛАР</b>\n📅 "
                + sup.rangeLabel(from, to) + "\n\n"
                + "💵 Қабул қилинган нақд: <b>" + fmt(num(ct, "naqd")) + "</b> so'm ("
                + num(ct, "soni") + " та)\n");
        if (num(ct, "terminal") > 0)
            sb.append("💳 Терминал (фақат журнал): <b>").append(fmt(num(ct, "terminal"))).append("</b> so'm\n");
        sb.append("   ҳисобот орқали: ").append(num(ct, "viaSub"))
          .append(" · бевосита: ").append(num(ct, "direct")).append("\n");

        int lines = 0;
        for (Map<String, Object> k : perKassa) {
            long kid = num(k, "kassaId");
            sb.append("\n🏪 <b>").append(esc(String.valueOf(k.get("kassa")))).append("</b>: <b>")
              .append(fmt(num(k, "naqd"))).append("</b> so'm (").append(num(k, "soni")).append(" та)\n");
            int shown = 0, more = 0;
            for (Map<String, Object> c : cols) {
                if (num(c, "kassaId") != kid) continue;
                if (shown >= 8 || lines >= 45) { more++; continue; }
                shown++; lines++;
                String mt = "TERMINAL".equals(c.get("mt")) ? "💳" : "💵";
                sb.append("  • ").append(java.time.LocalDate.parse(String.valueOf(c.get("date"))).format(DF))
                  .append(" — <b>").append(fmt(num(c, "amount"))).append("</b> ").append(mt)
                  .append(" · ").append(esc(String.valueOf(c.get("topshirdi"))))
                  .append(" → ").append(esc(String.valueOf(c.get("qabulQildi"))))
                  .append(" (").append(esc(String.valueOf(c.get("source")))).append(")\n");
            }
            if (more > 0) sb.append("  … яна ").append(more).append(" та (Excel'да тўлиқ)\n");
        }
        if (perKassa.isEmpty()) sb.append("\nБу даврда қабул қилинган пул йўқ.\n");

        sb.append("\n📤 <b>Кассир ҳисоботлари:</b> ").append(num(st, "soni")).append(" та")
          .append(num(st, "pending") > 0 ? " (кутмоқда " + num(st, "pending") + ")" : "").append("\n")
          .append("   топширилди: 💵 ").append(fmt(num(st, "naqd"))).append(" · 📲 ").append(fmt(num(st, "klik")))
          .append("\n   қабул қилинди: 💵 ").append(fmt(num(st, "accNaqd"))).append(" · 📲 ").append(fmt(num(st, "accKlik")));

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        // Tanlangan davr tugmasi ✅ bilan belgilanadi
        rows.add(irow(pbtn("📆 Bugun", "t", from, to), pbtn("Kecha", "y", from, to), pbtn("7 kun", "7", from, to)));
        rows.add(irow(pbtn("30 kun", "30", from, to), pbtn("Shu oy", "m", from, to), btn("🗓 Kalendar", "a:cal:o:tp")));
        rows.add(irow(btn("📗 Excel", "a:tpx:" + from + ":" + to)));
        // Xato/ikki marta qabulni bekor qilish — faqat SuperAdmin
        if (u.getRole() == Role.SUPERADMIN && !cols.isEmpty())
            rows.add(irow(btn("❌ Қабулни бекор қилиш", "a:tpc:0")));
        rows.add(irow(sup.bk("a:p:st")));
        InlineKeyboardMarkup kb = inline(rows);
        if (msgId > 0) sender.edit(chatId, msgId, sb.toString(), kb);
        else sup.sendContent(s, chatId, sb.toString(), kb);
    }


    /** a:tpx:<from>:<to> — shu davr uchun Excel (Mini App bilan bir xil fayl). */
    void topshirilganExcel(AppUser u, String arg, long chatId) {
        String[] a = arg.split(":");
        try {
            moneyReport.sendExcel(u, java.time.LocalDate.parse(a[0]), java.time.LocalDate.parse(a[1]), null);
            sender.send(chatId, "⏳ Excel tayyorlanmoqda…");
        } catch (uz.kassa.service.BusinessException e) {
            sender.send(chatId, "⚠️ " + esc(e.getMessage()));
        }
    }


    /* ---------- ❌ Qabulni bekor qilish (faqat SuperAdmin) ---------- */

    private static final int TPC_PAGE = 8;

    /** a:tpc:<sahifa> — bevosita qabullar, sahifalab (davrga BOG'LIQ EMAS — eski kunlar ham ko'rinsin). */
    void tpcList(Session s, String arg, long chatId, int msgId) {
        int page = 0;
        try { page = Math.max(0, Integer.parseInt(arg.split(":")[0])); } catch (NumberFormatException ignored) { }
        long total = opRepo.countByTypeAndStatusAndMoneyType(OpType.TOPSHIRIQ, OpStatus.TASDIQLANGAN, MoneyType.NAQD);
        int pages = (int) Math.max(1, (total + TPC_PAGE - 1) / TPC_PAGE);
        if (page >= pages) page = pages - 1;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int n = 0;
        for (Operation o : opRepo.findByTypeAndStatusAndMoneyTypeOrderByIdDesc(
                OpType.TOPSHIRIQ, OpStatus.TASDIQLANGAN, MoneyType.NAQD,
                org.springframework.data.domain.PageRequest.of(page, TPC_PAGE))) {
            if (o.getFromOwnerType() != OwnerType.KASSA) continue;
            n++;
            rows.add(irow(btn("#" + o.getId() + " · " + o.getOpDate().format(DF) + " · "
                    + names.owner(OwnerType.KASSA, o.getFromOwnerId()) + " · " + fmt(o.getAmount()),
                    "a:tpcx:" + o.getId())));
        }
        if (pages > 1) {
            List<InlineKeyboardButton> nav = new ArrayList<>();
            if (page > 0) nav.add(btn("‹ Олдинги", "a:tpc:" + (page - 1)));
            nav.add(btn((page + 1) + " / " + pages, "a:tpc:" + page));
            if (page < pages - 1) nav.add(btn("Кейинги ›", "a:tpc:" + (page + 1)));
            rows.add(nav);
        }
        rows.add(irow(sup.bk("a:tp:m")));
        String text = "❌ <b>Қабулни бекор қилиш</b>\n<i>Жами " + total + " та қабул (барча саналар), "
                + "саҳифа " + (page + 1) + "/" + pages + "</i>\n\n"
                + (n == 0 ? "Бекор қилинадиган қабул йўқ."
                    : "Қайси қабул хато ёки икки марта қилинган? Танланг:");
        sender.edit(chatId, msgId, text, inline(rows));
    }


    /** a:tpcx:<opId> — tasdiqlash oynasi. */
    void tpcConfirm(String arg, long chatId, int msgId) {
        Operation o = opRepo.findById(Long.parseLong(arg)).orElse(null);
        if (o == null || o.getStatus() != OpStatus.TASDIQLANGAN) {
            sender.edit(chatId, msgId, "⚠️ Операция топилмади ёки аллақачон бекор қилинган.",
                    inline(List.of(irow(sup.bk("a:tp:m")))));
            return;
        }
        String who = o.getComment() != null && o.getComment().startsWith("Topshirdi: ")
                ? o.getComment().substring(11) : "";
        sender.edit(chatId, msgId, "❌ <b>Қабулни бекор қилиш</b>\n\n"
                + "Операция: <b>#" + o.getId() + "</b>\n"
                + "🏪 Касса: <b>" + esc(names.owner(OwnerType.KASSA, o.getFromOwnerId())) + "</b>\n"
                + "💰 Сумма: <b>" + fmt(o.getAmount()) + "</b> so'm\n"
                + "📅 Сана: <b>" + o.getOpDate().format(DF) + "</b>\n"
                + (who.isEmpty() ? "" : "👤 Топширди: " + esc(who) + "\n")
                + "\nБекор қилинса: пул бухгалтериядан кассага қайтади, кунлар яна «топширилмаган» бўлади, "
                + "операция журналда «бекор» деб қолади, ҳисоботларга кирмайди.\n\n<b>Тасдиқлайсизми?</b>",
                inline(List.of(
                        irow(btn("✅ Ҳа, бекор қилиш", "a:tpcy:" + o.getId())),
                        irow(sup.bk("a:tp:m")))));
    }


    /** a:tpcy:<opId> — bajarish. */
    void tpcDo(AppUser u, String arg, long chatId, int msgId) {
        try {
            Operation o = submissionService.cancelCollect(Long.parseLong(arg), u,
                    "SuperAdmin " + u.getFullName() + " бекор қилди");
            long kassaId = o.getFromOwnerId();
            long avail = ledger.view(OwnerType.KASSA, kassaId, MoneyType.NAQD).available();
            long bux = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD).getAmount();
            sender.edit(chatId, msgId, "✅ <b>Қабул бекор қилинди</b> #" + o.getId() + "\n\n"
                    + "🏪 " + esc(names.owner(OwnerType.KASSA, kassaId)) + " ← 🏦 Бухгалтерия: <b>"
                    + fmt(o.getAmount()) + "</b> so'm\n"
                    + "💼 Кассада энди: <b>" + fmt(avail) + "</b> so'm\n"
                    + "🏦 Отдел основной: <b>" + fmt(bux) + "</b> so'm",
                    inline(List.of(irow(btn("🏦 Топширилган пуллар", "a:tp:m")), irow(sup.bk("a:p:st")))));
            notify.toKassa(kassaId, "↩️ Бухгалтерия " + o.getOpDate().format(DF) + " учун қабул қилинган <b>"
                    + fmt(o.getAmount()) + "</b> so'm қабулини бекор қилди — пул кассангизга қайтарилди.", null);
        } catch (uz.kassa.service.BusinessException e) {
            sender.edit(chatId, msgId, "⚠️ " + esc(e.getMessage()), inline(List.of(irow(sup.bk("a:tp:m")))));
        }
    }


    /** Davr tugmasi: joriy davrga mos kelsa «✅ …». */
    private InlineKeyboardButton pbtn(String label, String code, java.time.LocalDate from, java.time.LocalDate to) {
        java.time.LocalDate[] p = sup.periodOf(code);
        boolean on = p[0].equals(from) && p[1].equals(to);
        return btn((on ? "✅ " : "") + label, "a:tp:" + code);
    }


    private static long num(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Number n ? n.longValue() : 0;
    }


    /* ---------- 🏦 БУХГАЛТЕРИЯ HISOBOTI ---------- */

    void buxReport(Session s, long chatId) {
        syncService.syncIfStale(45);
        var n = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD);
        var k = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.KLIK);
        java.time.LocalDate from = ledger.today().withDayOfMonth(1);

        long kirim = 0, chiqim = 0, boshl = 0, terminal = 0; int rasxodTotal = 0;
        List<String> rasxodLines = new ArrayList<>();
        for (Operation o : opRepo.byPeriod(from, ledger.today())) {
            boolean in = o.getToOwnerType() == OwnerType.BUXGALTERIYA;
            boolean out = o.getFromOwnerType() == OwnerType.BUXGALTERIYA;
            if (!in && !out) continue;
            // Rad etilgan / yo'ldagi operatsiyalar pul emas
            if (o.getStatus() != uz.kassa.domain.OpStatus.TASDIQLANGAN) continue;
            // TERMINAL (karta) topshirig'i balansga kirmaydi — alohida qatorda ko'rsatiladi (N4)
            if (o.getMoneyType() == MoneyType.TERMINAL) { if (in) terminal += o.getAmount(); continue; }
            if (o.getType() == OpType.BOSHLANGICH && in) boshl += o.getAmount();
            else if (in) kirim += o.getAmount();
            if (out) {
                chiqim += o.getAmount(); rasxodTotal++;
                if (rasxodLines.size() < 15)
                    rasxodLines.add("• " + o.getOpDate().format(DF) + " — <b>"
                            + fmt(o.getAmount()) + "</b> so'm"
                            + (o.getComment() == null || o.getComment().isEmpty()
                                ? "" : " — " + esc(o.getComment())));
            }
        }

        StringBuilder sb = new StringBuilder("🏦 <b>БУХГАЛТЕРИЯ ҲИСОБОТИ</b>\n📅 "
                + from.format(DF) + " — " + ledger.today().format(DF) + "\n\n"
                + "💵 Naqd balans: <b>" + fmt(n.getAmount()) + "</b> so'm\n"
                + "📲 Click balans: <b>" + fmt(k.getAmount()) + "</b> so'm\n\n"
                + "⚙️ Boshlang'ich qoldiq: <b>" + fmt(boshl) + "</b>\n"
                + "🟢 Kirimlar (shu oy): <b>" + fmt(kirim) + "</b>\n"
                + "🔴 Chiqimlar (shu oy): <b>" + fmt(chiqim) + "</b>\n"
                + (terminal > 0 ? "💳 Terminal topshiriqlari (balansga kirmaydi): <b>" + fmt(terminal) + "</b>\n" : ""));

        if (n.getAmount() < 0 || k.getAmount() < 0) {
            sb.append("\n⚠️ <b>Balans manfiy — bu QARZ EMAS.</b>\n");
            if (boshl == 0)
                sb.append("Sabab: boshlang'ich qoldiq kiritilmagan — tizim 0 dan boshlab "
                        + "hisoblayapti, MoySklad chiqimlari esa ayirilyapti.\n"
                        + "Yechim: ⚙️ Настройка → 💼 Бошланғич қолдиқ → Buxgalteriya.\n");
        }

        if (!rasxodLines.isEmpty())
            sb.append("\n💸 <b>Nimalarga chiqim bo'ldi</b> (")
              .append(rasxodTotal > rasxodLines.size() ? rasxodLines.size() + " / " + rasxodTotal + " ta" : rasxodLines.size() + " ta")
              .append("):\n")
              .append(String.join("\n", rasxodLines)).append("\n");

        List<Debt> oweTo = debtRepo.findByDebtorTypeAndDebtorIdAndStatus(
                OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, DebtStatus.OCHIQ);
        List<Debt> oweFrom = debtRepo.findByCreditorTypeAndCreditorIdAndStatus(
                OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, DebtStatus.OCHIQ);
        sb.append("\n🧾 <b>Qarzlar registri bo'yicha:</b>\n");
        if (oweTo.isEmpty() && oweFrom.isEmpty())
            sb.append("Buxgalteriyaning hech kimga qarzi yo'q va hech kimdan haqi yo'q ✅");
        for (Debt d : oweTo)
            sb.append("🔴 KIMGA qarzdor: <b>")
              .append(esc(names.owner(d.getCreditorType(), d.getCreditorId())))
              .append("</b> — ").append(fmt(d.remain())).append(" so'm")
              .append(d.getReason() == null || d.getReason().isEmpty()
                      ? "" : " (" + esc(d.getReason()) + ")").append("\n");
        for (Debt d : oweFrom)
            sb.append("🟢 KIMDAN haqdor: <b>")
              .append(esc(names.owner(d.getDebtorType(), d.getDebtorId())))
              .append("</b> — ").append(fmt(d.remain())).append(" so'm")
              .append(d.getReason() == null || d.getReason().isEmpty()
                      ? "" : " (" + esc(d.getReason()) + ")").append("\n");

        sup.sendContent(s, chatId, sb.toString(), null);
    }


    /* ---------- 📈 СТАТИСТИКА ---------- */

    void statMenu(long chatId, int msgId) {
        sup.show(chatId, msgId, "📈 <b>Статистика</b>", List.of(
                irow(btn("🧾 Карзлар реестр", "a:p:dbt"), btn("📜 История", "a:p:his")),
                irow(btn("👥 Фойдаланувчилар умумий", "a:p:usr")),
                irow(btn("💼 Салдо", "a:p:sd"), btn("📊 Свод", "a:p:sv")),
                irow(btn("📲 Кликлар", "a:p:ck"), btn("🏦 Топширилган пуллар", "a:tp:m")),
                irow(sup.bk("a:p:main"))));
    }


    /** Салдо — faqat Основной отдел (buxgalteriya) qoldig'i. */
    void saldoKassa(Session s, String who, long chatId, int msgId) {
        OwnerType ot = who.equals("B") ? OwnerType.BUXGALTERIYA : OwnerType.KASSA;
        Long id = who.equals("B") ? LedgerService.BUX_ID : Long.parseLong(who);
        var n = ledger.view(ot, id, MoneyType.NAQD);
        var k = ledger.view(ot, id, MoneyType.KLIK);
        String name = ot == OwnerType.BUXGALTERIYA ? "Основной отдел" : names.owner(ot, id);
        String text = "💼 <b>Салдо</b> — " + esc(name) + "\n\n"
                + "💵 Касса (нақд): <b>" + fmt(n.getAmount()) + "</b> so'm"
                + (n.getReserved() > 0 ? " (band " + fmt(n.getReserved()) + ")" : "") + "\n"
                + "📲 Click: <b>" + fmt(k.getAmount()) + "</b> so'm"
                + (k.getReserved() > 0 ? " (band " + fmt(k.getReserved()) + ")" : "") + "\n"
                + "➕ <b>Жами: " + fmt(n.getAmount() + k.getAmount()) + "</b> so'm";
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(List.of(irow(sup.bk("a:p:st")))));
        else sup.sendContent(s, chatId, text, null);
    }


    /* ---------- 📊 СВОД (Excel) ---------- */

    void svodMenu(long chatId, int msgId) {
        sup.show(chatId, msgId, "📊 <b>Свод</b>\n\nExcel turini tanlang:", List.of(
                irow(btn("📗 Умумий Excel (шу ой)", "a:p:xe:all")),
                irow(btn("📘 Даврий Excel", "a:p:xe:per")),
                irow(btn("📙 Отдел Excel", "a:p:xe:otd")),
                irow(sup.bk("a:p:st"))));
    }


    /** a = [xe, tur, ...]: all | per | perc:<code> | otd | otdk:<id> | otdp:<id>:<code> */
    void excelFlow(String[] a, long chatId, int msgId) {
        switch (a[1]) {
            case "all" -> genExcel(chatId, msgId, "m", null);
            case "per" -> sup.show(chatId, msgId, "📘 <b>Даврий Excel</b>\n\nDavrni tanlang:", List.of(
                    irow(btn("Bugun", "a:p:xe:perc:t"), btn("Kecha", "a:p:xe:perc:y")),
                    irow(btn("7 kun", "a:p:xe:perc:7"), btn("30 kun", "a:p:xe:perc:30"),
                         btn("Shu oy", "a:p:xe:perc:m")),
                    irow(btn("🗓 Kalendar", "a:cal:o:x")),
                    irow(sup.bk("a:p:sv"))));
            case "perc" -> genExcel(chatId, msgId, a[2], null);
            case "otd" -> {
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
                    rows.add(irow(btn("🏪 " + k.getName(), "a:p:xe:otdk:" + k.getId())));
                rows.add(irow(sup.bk("a:p:sv")));
                sup.show(chatId, msgId, "📙 <b>Отдел Excel</b>\n\nKassani tanlang:", rows);
            }
            case "otdk" -> sup.show(chatId, msgId, "📙 <b>Отдел Excel</b> — "
                    + esc(names.owner(OwnerType.KASSA, Long.parseLong(a[2])))
                    + "\n\nDavrni tanlang:", List.of(
                    irow(btn("Bugun", "a:p:xe:otdp:" + a[2] + ":t"),
                         btn("7 kun", "a:p:xe:otdp:" + a[2] + ":7")),
                    irow(btn("30 kun", "a:p:xe:otdp:" + a[2] + ":30"),
                         btn("Shu oy", "a:p:xe:otdp:" + a[2] + ":m")),
                    irow(btn("🗓 Kalendar", "a:cal:o:xo" + a[2])),
                    irow(sup.bk("a:p:xe:otd"))));
            case "otdp" -> genExcel(chatId, msgId, a[3], Long.parseLong(a[2]));
        }
    }


    void genExcel(long chatId, int msgId, String code, Long kassaId) {
        java.time.LocalDate[] p = sup.periodOf(code);
        genExcelRange(chatId, msgId, p[0], p[1], kassaId);
    }


    void genExcelRange(long chatId, int msgId, java.time.LocalDate from,
                               java.time.LocalDate to, Long kassaId) {
        Kassa only = kassaId == null ? null : kassaRepo.findById(kassaId).orElse(null);
        String label = (only == null ? "Умумий" : only.getName()) + " · " + sup.rangeLabel(from, to);
        sender.edit(chatId, msgId, "⏳ Excel tayyorlanmoqda: <b>" + esc(label)
                + "</b>\nMoySklad so'ralmoqda, biroz kuting…");
        Kassa fOnly = only;
        new Thread(() -> {
            try {
                byte[] xlsx = excelReport.build(from, to, fOnly);
                sender.sendDocument(chatId, xlsx,
                        "hisobot_" + (fOnly == null ? "umumiy" : "kassa" + fOnly.getId())
                                + "_" + from + "_" + to + ".xlsx",
                        "📊 Excel: <b>" + esc(label) + "</b>");
            } catch (Exception e) {
                sender.send(chatId, "⚠️ Excel xatosi: " + esc(e.getMessage()));
            }
        }).start();
    }


    /* ---------- 💰 БУГУНГИ ТУШУМ (barcha kassalar) ---------- */

    void tushumAll(Session s, long chatId) {
        syncService.syncIfStale(45);   // so'ralganda oxirgi ma'lumot kelsin
        StringBuilder sb = new StringBuilder("💰 <b>БУГУНГИ ТУШУМ</b>\n📅 "
                + ledger.today().format(DF) + "\n");
        long tn = 0, tk = 0, tt = 0;
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;
            DayRecord d = dayRepo.findByKassaIdAndDate(k.getId(), ledger.today()).orElse(null);
            long n = d == null ? 0 : d.getPrixodNaqd();
            long kl = d == null ? 0 : d.getPrixodKlik();
            long t = d == null ? 0 : d.getPrixodTerminal();
            tn += n; tk += kl; tt += t;
            sb.append("\n<b>").append(esc(k.getName())).append("</b> — ")
              .append(fmt(n + kl + t)).append(" so'm\n")
              .append("  💵 ").append(fmt(n)).append(" · 📲 ").append(fmt(kl))
              .append(" · 💳 ").append(fmt(t)).append("\n");
        }
        sb.append("\n➕ <b>ЖАМИ: ").append(fmt(tn + tk + tt)).append("</b> so'm")
          .append("\n  💵 Нақд: ").append(fmt(tn))
          .append(" · 📲 Click: ").append(fmt(tk))
          .append(" · 💳 Terminal: ").append(fmt(tt));
        sup.sendContent(s, chatId, sb.toString(), null);
    }


    /** 🧾 Бугунги расход — kassalar bo'yicha (💰 Бугунги тушумнинг rasxod ko'zgusi). */
    /** 🧾 Расходлар — avval otdel, keyin sana tanlanadi. */
    void rasxodMenu(Session s, long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("➕ Барчаси", "a:cal:o:rxa")));
        rows.add(irow(btn("🏦 Отдел основной", "a:cal:o:rxo")));
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;
            rows.add(irow(btn("🏪 " + k.getName(), "a:cal:o:rxk" + k.getId())));
        }
        String text = "🧾 <b>Расходлар</b>\n\nOtdelni tanlang:";
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sup.sendContent(s, chatId, text, inline(rows));
    }


    /** rxa — barchasi, rxo — Отдел основной, rxk<id> — bitta kassa. */
    void rasxodByCtx(Session s, long chatId, int msgId, String ctx, java.time.LocalDate date) {
        if (ctx.equals("rxa")) { rasxodAll(s, chatId, msgId, date); return; }
        if (ctx.equals("rxo")) {
            rasxodOwner(s, chatId, msgId, OwnerType.BUXGALTERIYA, LedgerService.BUX_ID,
                    "🏦 Отдел основной", date, ctx);
            return;
        }
        if (ctx.startsWith("rxk")) {
            long kid = Long.parseLong(ctx.substring(3));
            String name = kassaRepo.findById(kid).map(Kassa::getName).orElse("Kassa #" + kid);
            rasxodOwner(s, chatId, msgId, OwnerType.KASSA, kid, "🏪 " + name, date, ctx);
        }
    }


    /** Bitta otdel/kassaning tanlangan kundagi rasxodi — har bir yozuv kimga/necha ekani bilan. */
    void rasxodOwner(Session s, long chatId, int msgId, OwnerType ot, Long oid, String label,
                             java.time.LocalDate date, String calCtx) {
        syncService.syncIfStale(45);
        long naqd = 0, klik = 0;
        StringBuilder lines = new StringBuilder();
        for (Operation o : opRepo.byPeriod(date, date)) {
            if (o.getStatus() != OpStatus.TASDIQLANGAN || o.getType() != OpType.RASXOD) continue;
            if (o.getFromOwnerType() != ot || !oid.equals(o.getFromOwnerId())) continue;
            if (o.getMoneyType() == MoneyType.KLIK) klik += o.getAmount(); else naqd += o.getAmount();
            lines.append("• ").append(fmt(o.getAmount())).append(" so'm (")
                 .append(o.getMoneyType() == MoneyType.KLIK ? "📲" : "💵").append(")")
                 .append(o.getComment() == null || o.getComment().isBlank() ? "" : " — " + esc(o.getComment()))
                 .append("\n");
        }
        StringBuilder sb = new StringBuilder("🧾 <b>" + label + "</b>\n📅 " + date.format(DF) + "\n\n");
        sb.append(lines.length() == 0 ? "Rasxod yo'q.\n" : lines);
        sb.append("\n➕ <b>Жами: ").append(fmt(naqd + klik)).append("</b> so'm")
          .append(" (💵 ").append(fmt(naqd)).append(" · 📲 ").append(fmt(klik)).append(")");

        InlineKeyboardMarkup kb = inline(List.of(
                irow(btn("📆 Кун танлаш", "a:cal:o:" + calCtx)),
                irow(sup.bk("a:rxm"))));
        if (msgId > 0) sender.edit(chatId, msgId, sb.toString(), kb);
        else sup.sendContent(s, chatId, sb.toString(), kb);
    }


    /** 🧾 Расход — tanlangan kun, otdellar kesimida, har bir chiqim kimga/necha ekani bilan. */
    void rasxodAll(Session s, long chatId, int msgId, java.time.LocalDate date) {
        syncService.syncIfStale(45);
        List<Operation> ops = opRepo.byPeriod(date, date).stream()
                .filter(o -> o.getStatus() == OpStatus.TASDIQLANGAN && o.getType() == OpType.RASXOD)
                .toList();

        StringBuilder sb = new StringBuilder("🧾 <b>РАСХОД</b>\n📅 " + date.format(DF) + "\n");
        long totNaqd = 0, totKlik = 0;

        long osnNaqd = 0, osnKlik = 0;   // pul turi bo'yicha AJRATILADI — hammasi «naqd» emas
        StringBuilder osnLines = new StringBuilder();
        for (Operation o : ops) {
            if (o.getFromOwnerType() != OwnerType.BUXGALTERIYA) continue;
            if (o.getMoneyType() == MoneyType.KLIK) osnKlik += o.getAmount(); else osnNaqd += o.getAmount();
            osnLines.append("  • ").append(fmt(o.getAmount())).append(" so'm (")
                    .append(o.getMoneyType() == MoneyType.KLIK ? "📲" : "💵").append(")")
                    .append(o.getComment() == null || o.getComment().isBlank() ? "" : " — " + esc(o.getComment()))
                    .append("\n");
        }
        totNaqd += osnNaqd; totKlik += osnKlik;
        sb.append("\n🏦 <b>Отдел основной</b> — <b>").append(fmt(osnNaqd + osnKlik)).append("</b> so'm\n").append(osnLines);

        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;
            long n = 0, kl = 0;
            StringBuilder lines = new StringBuilder();
            for (Operation o : ops) {
                if (o.getFromOwnerType() != OwnerType.KASSA || !k.getId().equals(o.getFromOwnerId())) continue;
                if (o.getMoneyType() == MoneyType.KLIK) kl += o.getAmount(); else n += o.getAmount();
                lines.append("  • ").append(fmt(o.getAmount())).append(" so'm (")
                     .append(o.getMoneyType() == MoneyType.KLIK ? "📲" : "💵").append(")")
                     .append(o.getComment() == null || o.getComment().isBlank() ? "" : " — " + esc(o.getComment()))
                     .append("\n");
            }
            totNaqd += n; totKlik += kl;
            sb.append("\n<b>").append(esc(k.getName())).append("</b> — <b>").append(fmt(n + kl)).append("</b> so'm\n")
              .append(lines);
        }

        sb.append("\n➕ <b>ЖАМИ: ").append(fmt(totNaqd + totKlik)).append("</b> so'm")
          .append("\n  💵 Нақд: ").append(fmt(totNaqd))
          .append(" · 📲 Click: ").append(fmt(totKlik));

        InlineKeyboardMarkup kb = inline(List.of(
                irow(btn("📆 Кун танлаш", "a:cal:o:rxa")),
                irow(sup.bk("a:rxm"))));
        if (msgId > 0) sender.edit(chatId, msgId, sb.toString(), kb);
        else sup.sendContent(s, chatId, sb.toString(), kb);
    }


    void auditMenu(Session s, long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<AppUser> users = userRepo.findByActiveTrueOrderByRoleAscIdAsc();
        for (int i = 0; i < users.size(); i += 2) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(btn("👤 " + users.get(i).getFullName(), "a:aud:" + users.get(i).getId()));
            if (i + 1 < users.size())
                r.add(btn("👤 " + users.get(i + 1).getFullName(), "a:aud:" + users.get(i + 1).getId()));
            rows.add(r);
        }
        rows.add(irow(btn("📄 Ҳаммаси (oxirgi 15)", "a:aud:0")));
        rows.add(irow(btn("📥 Excel (to'liq jurnal)", "a:aux:0")));
        String text = "📋 <b>Аудит</b>\n\nKimning amallarini ko'rasiz?";
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sup.sendContent(s, chatId, text, inline(rows));
    }


    void auditView(Session s, long userId, long chatId, int msgId) {
        List<AuditLog> logs = userId == 0
                ? auditRepo.findTop15ByOrderByIdDesc()
                : auditRepo.findTop15ByUserIdOrderByIdDesc(userId);
        String who = userId == 0 ? "Ҳаммаси"
                : userRepo.findById(userId).map(AppUser::getFullName).orElse("#" + userId);
        StringBuilder sb = new StringBuilder("📋 <b>Аудит</b> — " + esc(who) + " <i>(oxirgi 15 ta; to'liq jurnal — 📊 Excel)</i>\n");
        if (logs.isEmpty()) sb.append("\nYozuvlar yo'q.");
        java.util.Map<Long, String> nameCache = new java.util.HashMap<>();
        for (AuditLog a : logs) {
            String un = a.getUserId() == null ? "tizim"
                    : nameCache.computeIfAbsent(a.getUserId(), id ->
                        userRepo.findById(id).map(AppUser::getFullName).orElse("#" + id));
            String pl = a.getPayload() == null ? "" : a.getPayload();
            if (pl.length() > 60) pl = pl.substring(0, 60) + "…";
            sb.append("\n• ").append(AUDIT_DF.withZone(props.zoneId()).format(a.getCreatedAt()))
              .append(" — <b>").append(esc(a.getAction())).append("</b>");
            if (userId == 0) sb.append(" · ").append(esc(un));
            if (a.getEntity() != null)
                sb.append(" · ").append(esc(a.getEntity()))
                  .append(a.getEntityId() == null ? "" : "#" + a.getEntityId());
            if (!pl.isEmpty()) sb.append("\n   <i>").append(esc(pl)).append("</i>");
        }
        sender.edit(chatId, msgId, sb.toString(), inline(List.of(
                irow(btn("📥 Excel", "a:aux:" + userId)),
                irow(sup.bk("a:audm")))));
    }


    void auditExcel(long userId, long chatId) {
        String who = userId == 0 ? "hammasi"
                : userRepo.findById(userId).map(AppUser::getFullName).orElse("user" + userId);
        sender.send(chatId, "⏳ Audit Excel tayyorlanmoqda: <b>" + esc(who) + "</b>…");
        new Thread(() -> {
            try {
                List<AuditLog> logs = userId == 0
                        ? auditRepo.findTop5000ByOrderByIdDesc()
                        : auditRepo.findTop5000ByUserIdOrderByIdDesc(userId);
                byte[] xlsx = excelReport.buildAudit(logs,
                        id -> userRepo.findById(id).map(AppUser::getFullName).orElse("#" + id),
                        props.zoneId());
                sender.sendDocument(chatId, xlsx,
                        "audit_" + (userId == 0 ? "hammasi" : "user" + userId)
                                + "_" + ledger.today() + ".xlsx",
                        "📋 Audit jurnali: <b>" + esc(who) + "</b> (oxirgi " + logs.size() + " yozuv)");
            } catch (Exception e) {
                sender.send(chatId, "⚠️ Excel xatosi: " + esc(e.getMessage()));
            }
        }).start();
    }

}
