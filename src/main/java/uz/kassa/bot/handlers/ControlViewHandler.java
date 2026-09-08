package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.Sender;
import uz.kassa.bot.Session;
import uz.kassa.domain.AgentCheck;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Kassa;
import uz.kassa.domain.Role;
import uz.kassa.domain.Shipment;
import uz.kassa.repo.KassaRepo;
import uz.kassa.service.control.AgentCheckService;
import uz.kassa.service.control.ControlNotifier;
import uz.kassa.service.control.ShipmentControlService;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.*;

/**
 * 🤝 КОНТРАГЕНТ → «🧾 Қарздорлар» (otgruzka nazorati) va «⚠️ Хатолар» (kontragent sifati).
 * Kassir — faqat o'z yozuvlari (+ rahbar bo'lgan otdeli); buxgalter/SuperAdmin — hammasi, otdel filtri.
 * Callback prefiksi: kg:d* (qarzlar), kg:e* (xatolar).
 */
@Component
@RequiredArgsConstructor
public class ControlViewHandler {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final int PAGE = 10;

    private final Sender sender;
    private final ShipmentControlService ships;
    private final AgentCheckService agents;
    private final KassaRepo kassaRepo;
    private final KontragentSupport ks;
    private final ControlNotifier notifier;
    private final uz.kassa.webapp.ExcelReportService excel;
    private final uz.kassa.service.control.ControlRefreshService refreshSvc;
    private final uz.kassa.service.control.ControlStatsService stats;


    public boolean onCallback(AppUser u, Session s, String cmd, String arg, long chatId, int msgId) {
        switch (cmd) {
            case "dl" -> debtList(u, s, arg, chatId, msgId);
            case "dv" -> debtCard(u, Long.parseLong(arg), chatId, msgId);
            case "dr" -> debtRefresh(u, Long.parseLong(arg), chatId, msgId);
            case "dc" -> debtClose(u, s, Long.parseLong(arg), chatId, msgId);
            case "el" -> errorList(u, arg, chatId, msgId);
            case "ev" -> errorCard(u, Long.parseLong(arg), chatId, msgId, false);
            case "er" -> errorCard(u, Long.parseLong(arg), chatId, msgId, true);
            case "ex" -> errorIgnore(u, Long.parseLong(arg), chatId, msgId);
            case "dx" -> debtExcel(u, arg, chatId);
            case "ol" -> issueList(u, arg, chatId, msgId);
            case "rf" -> refresh(u, arg, chatId, msgId);
            case "es" -> statsView(u, arg, chatId, msgId);
            case "ee" -> errorExcel(u, chatId);
            default -> { return false; }
        }
        return true;
    }


    /* ==================== 🧾 ҚАРЗДОРЛАР ==================== */

    /** arg: "" | "<kassaId|0>.<page>[.<statusIdx|-1>]" — status indeksi distinctStates ro'yxati bo'yicha. */
    void debtList(AppUser u, Session s, String arg, long chatId, int msgId) {
        long kassa = 0; int page = 0; int st = -1;
        if (!arg.isBlank()) {
            String[] p = arg.split("\\.");
            try {
                kassa = Long.parseLong(p[0]);
                page = p.length > 1 ? Integer.parseInt(p[1]) : 0;
                st = p.length > 2 ? Integer.parseInt(p[2]) : -1;
            } catch (NumberFormatException ignored) { }
        }
        boolean admin = u.getRole() == Role.SUPERADMIN || u.getRole() == Role.BUXGALTER;
        List<Shipment> all = ships.visibleFor(u, kassa > 0 ? kassa : null);
        List<String> states = ShipmentControlService.distinctStates(all);
        String stName = st >= 0 && st < states.size() ? states.get(st) : null;
        if (stName == null) st = -1;
        List<Shipment> list = stName == null ? all : ships.visibleFor(u, kassa > 0 ? kassa : null, stName.equals("—") ? "" : stName);
        long total = 0;
        LocalDate today = LocalDate.now(ks.zone());
        long overdue = 0, noDue = 0;
        for (Shipment x : list) {
            total += x.remain();
            if (x.getDueAt() == null) noDue++;
            else if (x.getDueAt().isBefore(today)) overdue++;
        }
        StringBuilder sb = new StringBuilder("🧾 <b>Қарздорлар</b>");
        if (kassa > 0) sb.append(" — ").append(esc(kassaRepo.findById(kassa).map(Kassa::getName).orElse("?")));
        else if (admin) sb.append(" (hammasi)");
        if (stName != null) sb.append(" · ").append(esc(stName)).append(ships.isQuietState(stName) ? " 🏦" : "");
        sb.append("\n");
        if (list.isEmpty()) sb.append("\nQarzdor otgruzkalar yo'q ✅");
        else {
            sb.append("Jami: <b>").append(list.size()).append("</b> ta · <b>").append(fmt(total)).append("</b> so'm");
            if (overdue > 0) sb.append(" · ⚠️ muddati o'tgan: ").append(overdue);
            if (noDue > 0) sb.append(" · ❗ muddatsiz: ").append(noDue);
            sb.append("\n\nTanlang (muddati yaqin/o'tganlar birinchi):");
        }
        List<String> quiet = states.stream().filter(ships::isQuietState).toList();
        if (!quiet.isEmpty()) sb.append("\n🏦 ").append(esc(String.join(", ", quiet))).append(" — jim: xabar bormaydi, faqat ro'yxatda");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int from = page * PAGE;
        for (int i = from; i < Math.min(list.size(), from + PAGE); i++) {
            Shipment x = list.get(i);
            String label = (x.getDueAt() != null && x.getDueAt().isBefore(today) ? "⚠️ " : x.getDueAt() == null ? "❗ " : "📦 ")
                    + x.getAgentName() + " · №" + x.getDocNo() + " · " + fmt(x.remain());
            if (label.length() > 60) label = label.substring(0, 60);
            rows.add(irow(btn(label, "kg:dv:" + x.getId())));
        }
        List<InlineKeyboardButton> nav = new ArrayList<>();
        if (page > 0) nav.add(btn("⬅️ Oldingi", "kg:dl:" + kassa + "." + (page - 1) + "." + st));
        if (from + PAGE < list.size()) nav.add(btn("Keyingi ➡️", "kg:dl:" + kassa + "." + (page + 1) + "." + st));
        if (!nav.isEmpty()) rows.add(nav);
        // 🏷 status filtri
        if (!states.isEmpty()) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(btn((st < 0 ? "✅ " : "") + "Hammasi (" + all.size() + ")", "kg:dl:" + kassa + ".0.-1"));
            for (int i = 0; i < states.size(); i++) {
                String name = states.get(i);
                r.add(btn((st == i ? "✅ " : "") + (ships.isQuietState(name) ? "🏦 " : "") + name + " ("
                        + ShipmentControlService.countState(all, name) + ")", "kg:dl:" + kassa + ".0." + i));
                if (r.size() == 3) { rows.add(r); r = new ArrayList<>(); }
            }
            if (!r.isEmpty()) rows.add(r);
        }
        if (admin) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(btn(kassa == 0 ? "✅ Hammasi" : "Hammasi", "kg:dl:0.0." + st));
            for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
                if (k.isCashless()) continue;
                r.add(btn((kassa == k.getId() ? "✅ " : "🏪 ") + k.getName(), "kg:dl:" + k.getId() + ".0." + st));
                if (r.size() == 3) { rows.add(r); r = new ArrayList<>(); }
            }
            if (!r.isEmpty()) rows.add(r);
            if (!list.isEmpty()) rows.add(irow(btn("📥 Excel", "kg:dx:" + kassa + "." + st)));
        }
        rows.add(tools(u, "dl." + kassa));
        rows.add(irow(ks.bk("kg:m")));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }


    /** arg: "<kassa>[.<statusIdx>]" */
    void debtExcel(AppUser u, String arg, long chatId) {
        long kassa = 0; int st = -1;
        String[] p = arg.split("\\.");
        try { kassa = Long.parseLong(p[0]); st = p.length > 1 ? Integer.parseInt(p[1]) : -1; } catch (NumberFormatException ignored) { }
        List<Shipment> all = ships.visibleFor(u, kassa > 0 ? kassa : null);
        List<String> states = ShipmentControlService.distinctStates(all);
        String stName = st >= 0 && st < states.size() ? states.get(st) : null;
        List<Shipment> list = stName == null ? all : ships.visibleFor(u, kassa > 0 ? kassa : null, stName.equals("—") ? "" : stName);
        byte[] xlsx = excel.buildDebts(list, notifier::userName, notifier::kassaName, ks.zone());
        sender.sendDocument(chatId, xlsx, "qarzdorlar_" + ks.today() + ".xlsx",
                "🧾 Qarzdorlar: " + list.size() + " ta" + (stName == null ? "" : " · " + stName));
    }


    void debtCard(AppUser u, long id, long chatId, int msgId) { debtCard(u, id, chatId, msgId, ""); }

    void debtCard(AppUser u, long id, long chatId, int msgId, String note) {
        Shipment x = ships.find(id).orElse(null);
        if (x == null || !ships.canSee(u, x)) {
            sender.edit(chatId, msgId, "⚠️ Yozuv topilmadi.", inline(List.of(irow(ks.bk("kg:dl")))));
            return;
        }
        String text = "🧾 <b>Qarz #" + x.getId() + "</b> · " + ShipmentControlService.statusLabel(x.getControlStatus())
                + "\n\n" + ships.render(x, true);
        if (x.getControlStatus() != Shipment.Status.QARZ && x.getCloseReason() != null)
            text += "\n📝 " + esc(x.getCloseReason()) + (x.getClosedAt() == null ? "" : " · "
                    + x.getClosedAt().atZone(ks.zone()).format(DTF));
        text += note;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (x.getControlStatus() == Shipment.Status.QARZ) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(btn("🔄 Yangilash", "kg:dr:" + id));
            if (u.getRole() == Role.SUPERADMIN || u.getRole() == Role.BUXGALTER) r.add(btn("✅ Yopish", "kg:dc:" + id));
            rows.add(r);
            if (x.getReminderId() != null) rows.add(irow(btn("💵 Qisman to'lov (bot ichida)", "kg:pw:" + x.getReminderId())));
        }
        rows.add(ships.kb(x).getKeyboard().get(0));
        rows.add(irow(ks.bk("kg:dl")));
        sender.edit(chatId, msgId, text, inline(rows));
    }


    void debtRefresh(AppUser u, long id, long chatId, int msgId) {
        Shipment x = ships.find(id).orElse(null);
        if (x == null || !ships.canSee(u, x)) { debtList(u, null, "", chatId, msgId); return; }
        String note;
        try { ships.refresh(x); note = "\n\n🔄 MoySklad'dan yangilandi: " + java.time.LocalTime.now(ks.zone()).withNano(0); }
        catch (Exception e) { note = "\n\n⚠️ MoySklad'dan o'qilmadi: " + esc(String.valueOf(e.getMessage())); }
        debtCard(u, id, chatId, msgId, note);
    }


    /** ✅ Yopish: balans tekshiruvi; SuperAdmin bo'lsa va balans 0 emas — sabab so'raladi. */
    void debtClose(AppUser u, Session s, long id, long chatId, int msgId) {
        Shipment x = ships.find(id).orElse(null);
        if (x == null || !ships.canSee(u, x)) { debtList(u, s, "", chatId, msgId); return; }
        if (u.getRole() != Role.SUPERADMIN && u.getRole() != Role.BUXGALTER) {
            sender.send(chatId, "⚠️ Faqat buxgalter/SuperAdmin yopa oladi.");
            return;
        }
        String res;
        try { res = ships.closeManual(x, u, null); }
        catch (Exception e) { sender.send(chatId, "⚠️ MoySklad'dan o'qilmadi: " + esc(String.valueOf(e.getMessage()))); return; }
        if (res.equals("OK") || res.equals("GONE")) { debtCard(u, id, chatId, msgId); return; }
        String bal = res.substring(res.indexOf(':') + 1);
        if (res.startsWith("DENIED")) {
            sender.edit(chatId, msgId, "⛔ <b>Yopib bo'lmaydi</b> — MoySklad balansi hali <b>" + bal
                    + "</b> so'm (qarz). To'lov MoySklad'ga tushgach bot o'zi yopadi.\n"
                    + "Istisno holatda faqat SuperAdmin sabab bilan yopa oladi.",
                    inline(List.of(irow(ks.bk("kg:dv:" + id)))));
            return;
        }
        s.state = Session.State.KG_CLOSE_REASON;
        s.data.put("kgCloseId", id);
        sender.edit(chatId, msgId, "⚠️ MoySklad balansi hali <b>" + bal + "</b> so'm (qarz).\n\n"
                + "SuperAdmin sifatida baribir yopish uchun <b>sababini yozing</b> (auditda saqlanadi, "
                + "xodim va rahbarga xabar boradi):", inline(List.of(irow(ks.bk("kg:dv:" + id)))));
    }


    public void closeReason(AppUser u, Session s, String text, long chatId) {
        long id = s.getLong("kgCloseId");
        s.reset();
        Shipment x = ships.find(id).orElse(null);
        if (x == null) { sender.send(chatId, "⚠️ Yozuv topilmadi."); return; }
        if (text.trim().length() < 3) { sender.send(chatId, "⚠️ Sabab juda qisqa. Qaytadan: 🧾 Қарздорлар → yozuv → ✅ Yopish"); return; }
        String res = ships.closeManual(x, u, text.trim());
        sender.send(chatId, res.equals("OK") ? "✅ Qarz #" + id + " yopildi (sabab: " + esc(text.trim()) + ")"
                : "⚠️ Yopilmadi: " + esc(res));
    }


    /* ==================== 🔄 YANGILASH · 📊 STATISTIKA ==================== */

    /** Qo'lda yangilash: buxgalter, SuperAdmin, otdel rahbari (kassir — avtomatik 2 daqiqa). */
    boolean canRefresh(AppUser u) {
        return u.getRole() == Role.SUPERADMIN || u.getRole() == Role.BUXGALTER || !notifier.headOf(u).isEmpty();
    }

    private List<InlineKeyboardButton> tools(AppUser u, String ctx) {
        List<InlineKeyboardButton> r = new ArrayList<>();
        if (canRefresh(u)) r.add(btn("🔄 Yangilash", "kg:rf:" + ctx));
        r.add(btn("📊 Statistika", "kg:es:30"));
        return r;
    }

    /** arg: "<ctx>[.<param>]" — dl (param=kassa) | el | ol | es (param=days). */
    void refresh(AppUser u, String arg, long chatId, int msgId) {
        if (!canRefresh(u)) {
            sender.send(chatId, "⚠️ Qo'lda yangilash faqat buxgalter, SuperAdmin va otdel rahbari uchun. "
                    + "Ma'lumot avtomatik har 2 daqiqada yangilanadi.");
            return;
        }
        int dot = arg.indexOf('.');
        String ctx = dot < 0 ? arg : arg.substring(0, dot);
        String param = dot < 0 ? "" : arg.substring(dot + 1);
        long r = refreshSvc.start(() -> {
            try {
                switch (ctx) {
                    case "el" -> errorList(u, "", chatId, msgId);
                    case "ol" -> issueList(u, "", chatId, msgId);
                    case "es" -> statsView(u, param, chatId, msgId);
                    default -> debtList(u, null, param.isBlank() ? "" : param + ".0", chatId, msgId);
                }
                sender.send(chatId, "🔄 Yangilandi: " + esc(refreshSvc.lastResult()));
            } catch (Exception e) { sender.send(chatId, "⚠️ Yangilashdan keyin ko'rinish: " + esc(String.valueOf(e.getMessage()))); }
        });
        if (r == 0) sender.edit(chatId, msgId, "⏳ MoySklad'dan yangilanmoqda: kontragentlar, otgruzkalar, balanslar… (10–60 soniya)");
        else if (r < 0) sender.edit(chatId, msgId, "⏳ Yangilash allaqachon ketmoqda, kuting…", inline(List.of(irow(ks.bk("kg:m")))));
        else sender.edit(chatId, msgId, "⏱ Yaqinda yangilangan: " + esc(refreshSvc.lastResult()) + "\nQayta yangilash "
                + r + " soniyadan keyin. Avtomatik: har 2 daqiqa.", inline(List.of(irow(ks.bk("kg:m")))));
    }


    /** 📊 Xodim kesimida: topildi / tuzatildi / ochiq. arg: kun soni (0 — butun davr). */
    void statsView(AppUser u, String arg, long chatId, int msgId) {
        int days = 30;
        try { if (!arg.isBlank()) days = Integer.parseInt(arg); } catch (NumberFormatException ignored) { }
        List<uz.kassa.service.control.ControlStatsService.Row> rows = stats.rows(u, days);
        StringBuilder sb = new StringBuilder("📊 <b>Nazorat statistikasi</b> — "
                + (days <= 0 ? "butun davr" : "oxirgi " + days + " kun") + "\n");
        long allFixed = rows.stream().filter(r -> r.openTotal() == 0 && (r.kgFixed + r.otgFixed) > 0).count();
        long kgF = 0, kgX = 0, kgO = 0, otF = 0, otX = 0, otO = 0, qO = 0, qC = 0;
        for (var r : rows) { kgF += r.kgFound; kgX += r.kgFixed; kgO += r.kgOpen; otF += r.otgFound(); otX += r.otgFixed; otO += r.otgOpen; qO += r.qarzOpen; qC += r.qarzClosed; }
        sb.append("👥 Xato/qarzli xodimlar: <b>").append(rows.size()).append("</b> · hammasini tuzatgan: <b>").append(allFixed).append("</b>\n");
        sb.append("🏢 Kontragent xatolari: topildi ").append(kgF).append(" · tuzatildi <b>").append(kgX).append("</b> · ochiq ").append(kgO).append("\n");
        sb.append("📦 Otgruzka kamchiligi: topildi ").append(otF).append(" · tuzatildi <b>").append(otX).append("</b> · ochiq ").append(otO).append("\n");
        sb.append("🧾 Qarz: ochiq ").append(qO).append(" · yopildi <b>").append(qC).append("</b>\n");
        int n = 0;
        for (var r : rows) {
            if (++n > 15) { sb.append("\n… yana ").append(rows.size() - 15).append(" xodim (🌐 Админ панел → Назорат статистикаси)"); break; }
            sb.append("\n👤 <b>").append(esc(r.name)).append("</b>").append(r.linked ? "" : " <i>(botda yo'q)</i>");
            if (r.kassaId != null) sb.append(" · ").append(esc(stats.kassaName(r.kassaId)));
            if (r.kgFound > 0) sb.append("\n   🏢 ").append(r.kgFound).append(" topildi · ").append(r.kgFixed).append(" tuzatildi · ").append(r.kgOpen).append(" ochiq");
            if (r.otgFound() > 0) sb.append("\n   📦 ").append(r.otgFound()).append(" topildi · ").append(r.otgFixed).append(" tuzatildi · ").append(r.otgOpen).append(" ochiq");
            if (r.qarzOpen + r.qarzClosed > 0) sb.append("\n   🧾 qarz ").append(r.qarzOpen).append(" ochiq (").append(fmt(r.qarzOpenSum)).append(") · ").append(r.qarzClosed).append(" yopildi");
        }
        if (rows.isEmpty()) sb.append("\nBu davrda xato yoki qarz yo'q ✅");
        List<List<InlineKeyboardButton>> kb = new ArrayList<>();
        kb.add(irow(btn(days == 1 ? "✅ Bugun" : "Bugun", "kg:es:1"), btn(days == 7 ? "✅ 7 kun" : "7 kun", "kg:es:7"),
                btn(days == 30 ? "✅ 30 kun" : "30 kun", "kg:es:30"), btn(days == 0 ? "✅ Hammasi" : "Hammasi", "kg:es:0")));
        if (canRefresh(u)) kb.add(irow(btn("🔄 MoySklad'dan yangilash", "kg:rf:es." + days)));
        kb.add(irow(ks.bk("kg:m")));
        sender.edit(chatId, msgId, sb.toString(), inline(kb));
    }


    /* ==================== ⚠️ ХАТОЛАР ==================== */

    void errorList(AppUser u, String arg, long chatId, int msgId) {
        int page = 0;
        try { if (!arg.isBlank()) page = Integer.parseInt(arg); } catch (NumberFormatException ignored) { }
        List<AgentCheck> list = agents.visibleFor(u);
        StringBuilder sb = new StringBuilder("⚠️ <b>Контрагент хатолари</b>"
                + (u.getRole() == Role.KASSIR ? "" : " (hammasi)") + "\n");
        if (list.isEmpty()) sb.append("\nTuzatilmagan kontragentlar yo'q ✅");
        else sb.append("Tuzatilmagan: <b>").append(list.size()).append("</b> ta\n\nTanlang:");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("✅ 🏢 Kontragentlar (" + list.size() + ")", "kg:el"),
                      btn("📦 Otgruzkalar (" + ships.visibleIssuesFor(u, null).size() + ")", "kg:ol")));
        int from = page * PAGE;
        for (int i = from; i < Math.min(list.size(), from + PAGE); i++) {
            AgentCheck ac = list.get(i);
            String label = "🏢 " + ac.getAgentName() + " · "
                    + String.join(",", ac.violationList().stream().map(AgentCheckService::shortTitle).toList());
            if (label.length() > 60) label = label.substring(0, 60);
            rows.add(irow(btn(label, "kg:ev:" + ac.getId())));
        }
        List<InlineKeyboardButton> nav = new ArrayList<>();
        if (page > 0) nav.add(btn("⬅️ Oldingi", "kg:el:" + (page - 1)));
        if (from + PAGE < list.size()) nav.add(btn("Keyingi ➡️", "kg:el:" + (page + 1)));
        if (!nav.isEmpty()) rows.add(nav);
        if (!list.isEmpty() && u.getRole() != Role.KASSIR) rows.add(irow(btn("📥 Excel", "kg:ee")));
        rows.add(tools(u, "el"));
        rows.add(irow(ks.bk("kg:m")));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }


    void errorExcel(AppUser u, long chatId) {
        List<AgentCheck> list = agents.visibleFor(u);
        byte[] xlsx = excel.buildAgentErrors(list, notifier::userName, notifier::kassaName,
                uz.kassa.service.control.ControlConfig::ruleTitle);
        sender.sendDocument(chatId, xlsx, "kontragent_xatolari_" + ks.today() + ".xlsx",
                "⚠️ Tuzatilmagan kontragentlar: " + list.size() + " ta");
    }


    /** 📦 Kamchilikli (qarzdagi) otgruzkalar: muddat/masul/status/izoh/telefon. arg: page. */
    void issueList(AppUser u, String arg, long chatId, int msgId) {
        int page = 0;
        try { if (!arg.isBlank()) page = Integer.parseInt(arg); } catch (NumberFormatException ignored) { }
        List<Shipment> list = ships.visibleIssuesFor(u, null);
        StringBuilder sb = new StringBuilder("📦 <b>Otgruzka kamchiliklari</b>"
                + (u.getRole() == Role.KASSIR ? "" : " (hammasi)") + "\n");
        if (list.isEmpty()) sb.append("\nKamchilikli otgruzkalar yo'q ✅");
        else sb.append("Qarzdagi otgruzkalar: <b>").append(list.size()).append("</b> ta — ")
               .append(ShipmentControlService.issueSummary(list))
               .append("\nMoySklad'da otgruzkani ochib to'ldiring, bot o'zi tekshiradi. Tanlang:");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("🏢 Kontragentlar (" + agents.visibleFor(u).size() + ")", "kg:el"),
                      btn("✅ 📦 Otgruzkalar (" + list.size() + ")", "kg:ol")));
        int from = page * PAGE;
        for (int i = from; i < Math.min(list.size(), from + PAGE); i++) {
            Shipment x = list.get(i);
            String label = "📦 №" + x.getDocNo() + " · " + x.getAgentName() + " · " + ShipmentControlService.issueLabels(x.issueList());
            if (label.length() > 60) label = label.substring(0, 60);
            rows.add(irow(btn(label, "kg:dv:" + x.getId())));
        }
        List<InlineKeyboardButton> nav = new ArrayList<>();
        if (page > 0) nav.add(btn("⬅️ Oldingi", "kg:ol:" + (page - 1)));
        if (from + PAGE < list.size()) nav.add(btn("Keyingi ➡️", "kg:ol:" + (page + 1)));
        if (!nav.isEmpty()) rows.add(nav);
        if (!list.isEmpty() && u.getRole() != Role.KASSIR) rows.add(irow(btn("📥 Excel (qarzdorlar, kamchilik ustuni bilan)", "kg:dx:0")));
        rows.add(tools(u, "ol"));
        rows.add(irow(ks.bk("kg:m")));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }


    void errorCard(AppUser u, long id, long chatId, int msgId, boolean recheck) {
        AgentCheck ac = agents.find(id).orElse(null);
        if (ac == null) { sender.edit(chatId, msgId, "⚠️ Topilmadi.", inline(List.of(irow(ks.bk("kg:el"))))); return; }
        List<AgentCheckService.Violation> v = null;
        String note = "";
        if (recheck) {
            try {
                v = agents.recheck(ac);
                ac = agents.find(id).orElse(ac);
                if (v == null) note = "\n\n🗑 Kontragent MoySklad'da topilmadi (o'chirilgan).";
                else if (v.isEmpty()) note = "\n\n✅ Hozir xato yo'q.";
            } catch (Exception e) { note = "\n\n⚠️ MoySklad'dan o'qilmadi: " + esc(String.valueOf(e.getMessage())); }
        }
        StringBuilder sb = new StringBuilder("⚠️ <b>Kontragent xatosi</b> · ");
        sb.append(switch (ac.getStatus()) {
            case OCHIQ -> "🔴 tuzatilmagan"; case TUZATILDI -> "✅ tuzatildi"; case ETIBORSIZ -> "🙈 e'tiborsiz"; case OK -> "✅ ok"; });
        sb.append("\n\n🏢 <b>").append(esc(ac.getAgentName())).append("</b>\n👤 Yaratgan: ").append(esc(agents.who(ac)));
        if (ac.getKassaId() != null) sb.append(" · ").append(esc(kassaRepo.findById(ac.getKassaId()).map(Kassa::getName).orElse("")));
        sb.append("\n🕒 ").append(ac.getMsCreatedAt() == null ? "—" : ac.getMsCreatedAt().format(DTF)).append("\n\n");
        if (v != null && !v.isEmpty()) { sb.append("<b>Tuzatish kerak:</b>\n"); for (var x : v) sb.append("• ").append(x.text()).append("\n"); }
        else if (!ac.violationList().isEmpty() && ac.getStatus() == AgentCheck.Status.OCHIQ)
            sb.append("<b>Tuzatish kerak:</b>\n").append(AgentCheckService.bullets(ac.violationList()));
        sb.append(note);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> r = new ArrayList<>();
        r.add(btn("🔄 Qayta tekshirish", "kg:er:" + id));
        if (u.getRole() == Role.SUPERADMIN && ac.getStatus() == AgentCheck.Status.OCHIQ) r.add(btn("🙈 E'tiborsiz", "kg:ex:" + id));
        rows.add(r);
        rows.add(irow(ControlNotifier.urlBtn("🔗 MoySklad'da ochish", ControlNotifier.MS_AGENT_URL + ac.getAgentMsId())));
        rows.add(irow(ks.bk("kg:el")));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }


    void errorIgnore(AppUser u, long id, long chatId, int msgId) {
        if (u.getRole() != Role.SUPERADMIN) return;
        agents.find(id).ifPresent(ac -> agents.ignore(ac, u));
        errorList(u, "", chatId, msgId);
    }
}
