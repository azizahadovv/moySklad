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
 * 🗓 Kalendar: sana tanlash oynasi va tanlangan sanani tegishli oqimga (pul qabul, boshlang'ich qoldiq, click, korrektirovka, rasxod, davr) uzatish.
 * (AdminHandler dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
public class CalendarHandler {

    private final Sender sender;
    private final LedgerService ledger;
    private final uz.kassa.service.moysklad.MoySkladSyncService syncService;
    private final AdminSupport sup;
    private final StatsHandler statsH;
    private final OtdelHandler otdelH;
    private final BalanceAdminHandler balanceH;
    private final KassaAdminHandler kassaH;


    void calOpen(Session s, long chatId, int msgId, String ctx) {
        s.data.remove("calFrom");
        s.data.put("calCtx", ctx);
        calShow(s, chatId, msgId, ctx, java.time.YearMonth.from(ledger.today()));
    }


    /** Bir-sanali kontekstlar: q — pul qabul, ib — boshlang'ich qoldiq, ck — Click qoldiq,
     *  kr — korrektirovka sanasi,
     *  rxa/rxo/rxk<id> — Расходлар bo'limida kun tanlash (barchasi/osnovnoy/kassa). */
    boolean calSingle(String ctx) {
        return ctx.equals("q") || ctx.equals("ib") || ctx.equals("ck")
                || ctx.equals("kr") || ctx.startsWith("rx");
    }


    void calShow(Session s, long chatId, int msgId, String ctx, java.time.YearMonth ym) {
        calShow(s, chatId, msgId, ctx, ym, null);
    }


    void calShow(Session s, long chatId, int msgId, String ctx,
                         java.time.YearMonth ym, String warn) {
        String fromStr = s.getStr("calFrom");
        String body = calSingle(ctx)
                ? "📅 <b>Sanani tanlang:</b>"
                : (fromStr == null
                    ? "📍 <b>Boshlanish</b> sanasini tanlang:"
                    : "📍 Boshlanish: <b>" + java.time.LocalDate.parse(fromStr).format(DF)
                      + "</b>\n🏁 Endi <b>tugash</b> sanasini tanlang\n"
                      + "(bitta kun uchun o'sha kunni yana bosing)");
        String title = "🗓 <b>Kalendar</b>\n\n" + (warn == null ? "" : warn + "\n") + body;
        InlineKeyboardMarkup kb = calKb(ym, ctx,
                fromStr == null ? null : java.time.LocalDate.parse(fromStr));
        if (msgId > 0) sender.edit(chatId, msgId, title, kb);
        else sup.sendContent(s, chatId, title, kb);
    }


    InlineKeyboardMarkup calKb(java.time.YearMonth ym, String ctx, java.time.LocalDate sel) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(
                btn("‹", "a:cal:n:" + ctx + ":" + ym.minusMonths(1)),
                btn(OYLAR[ym.getMonthValue() - 1] + " " + ym.getYear(), "a:cal:z"),
                btn("›", "a:cal:n:" + ctx + ":" + ym.plusMonths(1))));
        rows.add(irow(btn("Du", "a:cal:z"), btn("Se", "a:cal:z"), btn("Ch", "a:cal:z"),
                btn("Pa", "a:cal:z"), btn("Ju", "a:cal:z"), btn("Sh", "a:cal:z"), btn("Ya", "a:cal:z")));
        java.time.LocalDate today = ledger.today();
        int shift = ym.atDay(1).getDayOfWeek().getValue() - 1;   // Dushanba = 0
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (int i = 0; i < shift; i++) row.add(btn("⠀", "a:cal:z"));
        for (int day = 1; day <= ym.lengthOfMonth(); day++) {
            java.time.LocalDate d = ym.atDay(day);
            String label = d.equals(sel) ? "✅" + day
                    : d.equals(today) ? "·" + day + "·" : String.valueOf(day);
            row.add(btn(label, "a:cal:d:" + ctx + ":" + d.toEpochDay()));
            if (row.size() == 7) { rows.add(row); row = new ArrayList<>(); }
        }
        if (!row.isEmpty()) {
            while (row.size() < 7) row.add(btn("⠀", "a:cal:z"));
            rows.add(row);
        }
        rows.add(irow(btn("❌ Yopish", "a:cal:c")));
        return inline(rows);
    }


    /** a:cal:<op>... — z: bo'sh joy, c: yopish, o: ochish, n: oy almashtirish, d: kun. */
    void calCb(AppUser u, Session s, String arg, long chatId, int msgId) {
        if (arg.equals("z")) return;
        if (arg.equals("c")) {
            s.data.remove("calFrom");
            s.data.remove("calCtx");
            sender.deleteMessage(chatId, msgId);
            return;
        }
        String[] a = arg.split(":");
        String ctx = a[1];
        switch (a[0]) {
            case "o" -> {
                s.data.remove("calFrom");
                s.data.put("calCtx", ctx);
                calShow(s, chatId, msgId, ctx, java.time.YearMonth.from(ledger.today()));
            }
            case "n" -> calShow(s, chatId, msgId, ctx, java.time.YearMonth.parse(a[2]));
            case "d" -> {
                java.time.LocalDate d = java.time.LocalDate.ofEpochDay(Long.parseLong(a[2]));

                // BIR-SANALI rejim (pul qabul / qoldiq kiritish): bitta bosish yetadi
                if (calSingle(ctx)) {
                    if (d.isAfter(ledger.today())) {
                        calShow(s, chatId, msgId, ctx, java.time.YearMonth.from(d),
                                "⚠️ Kelajak sanasi bo'lmaydi.");
                        return;
                    }
                    s.data.remove("calFrom");
                    s.data.remove("calCtx");
                    if (ctx.startsWith("rx")) { statsH.rasxodByCtx(s, chatId, msgId, ctx, d); return; }
                    switch (ctx) {
                        case "q" -> otdelH.qbCommit(u, s, d, chatId, msgId);
                        case "ib" -> {
                            if (s.state != Session.State.ADM_IB_SANA) return;
                            sender.edit(chatId, msgId, "📅 Sana: <b>" + d.format(DF) + "</b>");
                            balanceH.ibCommit(u, s, d, chatId);
                        }
                        case "ck" -> {
                            if (s.state != Session.State.ADM_CK_SANA) return;
                            sender.edit(chatId, msgId, "📅 Sana: <b>" + d.format(DF) + "</b>");
                            kassaH.ckCommit(u, s, d, chatId);
                        }
                        case "kr" -> {
                            if (s.state != Session.State.ADM_KR_SANA) return;
                            balanceH.krSanaChosen(s, d, chatId, msgId);
                        }
                    }
                    return;
                }

                // DIAPAZON rejimi (hisobot/Excel): ikki bosish — boshlanish va tugash
                String fromStr = s.getStr("calFrom");
                if (fromStr == null) {
                    s.data.put("calFrom", d.toString());
                    calShow(s, chatId, msgId, ctx, java.time.YearMonth.from(d));
                    return;
                }
                java.time.LocalDate f = java.time.LocalDate.parse(fromStr);
                s.data.remove("calFrom");
                s.data.remove("calCtx");
                java.time.LocalDate from = f.isBefore(d) ? f : d;
                java.time.LocalDate to = f.isBefore(d) ? d : f;
                if (ctx.equals("x")) statsH.genExcelRange(chatId, msgId, from, to, null);
                else if (ctx.startsWith("xo"))
                    statsH.genExcelRange(chatId, msgId, from, to, Long.parseLong(ctx.substring(2)));
                else if (ctx.startsWith("k")) {
                    syncService.syncIfStale(45);
                    otdelH.kassaPeriodRange(s, Long.parseLong(ctx.substring(1)), from, to, chatId, msgId);
                }
            }
        }
    }

}
