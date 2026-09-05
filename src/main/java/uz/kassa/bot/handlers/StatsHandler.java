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
    private final AdminSupport sup;


    /* ---------- 🏦 БУХГАЛТЕРИЯ HISOBOTI ---------- */

    void buxReport(Session s, long chatId) {
        syncService.syncIfStale(45);
        var n = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD);
        var k = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.KLIK);
        java.time.LocalDate from = ledger.today().withDayOfMonth(1);

        long kirim = 0, chiqim = 0, boshl = 0;
        List<String> rasxodLines = new ArrayList<>();
        for (Operation o : opRepo.byPeriod(from, ledger.today())) {
            boolean in = o.getToOwnerType() == OwnerType.BUXGALTERIYA;
            boolean out = o.getFromOwnerType() == OwnerType.BUXGALTERIYA;
            if (!in && !out) continue;
            if (o.getType() == OpType.BOSHLANGICH && in) boshl += o.getAmount();
            else if (in) kirim += o.getAmount();
            if (out) {
                chiqim += o.getAmount();
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
                + "🔴 Chiqimlar (shu oy): <b>" + fmt(chiqim) + "</b>\n");

        if (n.getAmount() < 0 || k.getAmount() < 0) {
            sb.append("\n⚠️ <b>Balans manfiy — bu QARZ EMAS.</b>\n");
            if (boshl == 0)
                sb.append("Sabab: boshlang'ich qoldiq kiritilmagan — tizim 0 dan boshlab "
                        + "hisoblayapti, MoySklad chiqimlari esa ayirilyapti.\n"
                        + "Yechim: ⚙️ Настройка → 💼 Бошланғич қолдиқ → Buxgalteriya.\n");
        }

        if (!rasxodLines.isEmpty())
            sb.append("\n💸 <b>Nimalarga chiqim bo'ldi</b> (oxirgi ")
              .append(rasxodLines.size()).append(" ta):\n")
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
                irow(btn("📲 Кликлар", "a:p:ck")),
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
        StringBuilder sb = new StringBuilder("📋 <b>Аудит</b> — " + esc(who) + "\n");
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
