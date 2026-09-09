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
            case "ee" -> errorExcel(u, arg, chatId);
            case "ox" -> issueExcel(u, arg, chatId);
            default -> { return false; }
        }
        return true;
    }


    /* ==================== 🧾 ҚАРЗДОРЛАР ==================== */

    /** arg: "" | "<kassaId|0>.<page>[.<statusIdx|-1>[.<tur 0-3>]]" — status indeksi distinctStates ro'yxati bo'yicha,
     *  tur: 0 hammasi · 1 Юр · 2 ИП · 3 Физ. */
    void debtList(AppUser u, Session s, String arg, long chatId, int msgId) {
        long kassa = 0; int page = 0; int st = -1; int type = 0;
        if (!arg.isBlank()) {
            String[] p = arg.split("\\.");
            try {
                kassa = Long.parseLong(p[0]);
                page = p.length > 1 ? Integer.parseInt(p[1]) : 0;
                st = p.length > 2 ? Integer.parseInt(p[2]) : -1;
                type = p.length > 3 ? Integer.parseInt(p[3]) : 0;
            } catch (NumberFormatException ignored) { }
        }
        if (type < 0 || type >= ShipmentControlService.TYPE_KEYS.length) type = 0;
        boolean admin = u.getRole() == Role.SUPERADMIN || u.getRole() == Role.BUXGALTER;
        List<Shipment> all = ships.visibleFor(u, kassa > 0 ? kassa : null);
        List<String> states = ShipmentControlService.distinctStates(all);
        String stName = st >= 0 && st < states.size() ? states.get(st) : null;
        if (stName == null) st = -1;
        List<Shipment> list = stName == null ? all : ships.visibleFor(u, kassa > 0 ? kassa : null, stName.equals("—") ? "" : stName);
        final int typeF = type;
        long unknownType = list.stream().filter(x -> x.getAgentType().isBlank()).count();
        if (type > 0) list = list.stream().filter(x -> ShipmentControlService.typeMatches(x, typeF)).toList();
        final String tail = "." + type;
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
        if (type > 0) sb.append(" · ").append(ShipmentControlService.typeLabel(ShipmentControlService.TYPE_KEYS[type]));
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
        if (page > 0) nav.add(btn("⬅️ Oldingi", "kg:dl:" + kassa + "." + (page - 1) + "." + st + tail));
        if (from + PAGE < list.size()) nav.add(btn("Keyingi ➡️", "kg:dl:" + kassa + "." + (page + 1) + "." + st + tail));
        if (!nav.isEmpty()) rows.add(nav);
        // 🏢/👤 kontragent turi filtri
        {
            List<Shipment> base = stName == null ? all : ships.visibleFor(u, kassa > 0 ? kassa : null, stName.equals("—") ? "" : stName);
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(btn((type == 0 ? "✅ " : "") + "Barcha tur", "kg:dl:" + kassa + ".0." + st + ".0"));
            for (int t = 1; t < ShipmentControlService.TYPE_KEYS.length; t++)
                r.add(btn((type == t ? "✅ " : "") + ShipmentControlService.typeLabel(ShipmentControlService.TYPE_KEYS[t])
                        + " (" + ShipmentControlService.countType(base, t) + ")", "kg:dl:" + kassa + ".0." + st + "." + t));
            rows.add(r);
            if (unknownType > 0 && type > 0) sb.append("\n❔ ").append(unknownType).append(" ta otgruzkada kontragent turi hali o'qilmagan (bir necha soatda to'ldiriladi)");
        }
        // 🏷 status filtri
        if (!states.isEmpty()) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(btn((st < 0 ? "✅ " : "") + "Hammasi (" + all.size() + ")", "kg:dl:" + kassa + ".0.-1" + tail));
            for (int i = 0; i < states.size(); i++) {
                String name = states.get(i);
                r.add(btn((st == i ? "✅ " : "") + (ships.isQuietState(name) ? "🏦 " : "") + name + " ("
                        + ShipmentControlService.countState(all, name) + ")", "kg:dl:" + kassa + ".0." + i + tail));
                if (r.size() == 3) { rows.add(r); r = new ArrayList<>(); }
            }
            if (!r.isEmpty()) rows.add(r);
        }
        if (admin) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(btn(kassa == 0 ? "✅ Hammasi" : "Hammasi", "kg:dl:0.0." + st + tail));
            for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
                if (k.isCashless()) continue;
                r.add(btn((kassa == k.getId() ? "✅ " : "🏪 ") + k.getName(), "kg:dl:" + k.getId() + ".0." + st + tail));
                if (r.size() == 3) { rows.add(r); r = new ArrayList<>(); }
            }
            if (!r.isEmpty()) rows.add(r);
        }
        if (!list.isEmpty()) rows.add(irow(btn("📥 Excel (shu filtr bilan)", "kg:dx:" + kassa + "." + st + tail)));
        rows.add(tools(u, "dl." + kassa));
        rows.add(irow(ks.bk("kg:m")));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }


    /** arg: "<kassa>[.<statusIdx>[.<tur>]]" — ro'yxatdagi filtrlar bilan bir xil. */
    void debtExcel(AppUser u, String arg, long chatId) {
        long kassa = 0; int st = -1; int type = 0;
        String[] p = arg.split("\\.");
        try {
            kassa = Long.parseLong(p[0]);
            st = p.length > 1 ? Integer.parseInt(p[1]) : -1;
            type = p.length > 2 ? Integer.parseInt(p[2]) : 0;
        } catch (NumberFormatException ignored) { }
        List<Shipment> all = ships.visibleFor(u, kassa > 0 ? kassa : null);
        List<String> states = ShipmentControlService.distinctStates(all);
        String stName = st >= 0 && st < states.size() ? states.get(st) : null;
        List<Shipment> list = stName == null ? all : ships.visibleFor(u, kassa > 0 ? kassa : null, stName.equals("—") ? "" : stName);
        final int typeF = type;
        if (type > 0) list = list.stream().filter(x -> ShipmentControlService.typeMatches(x, typeF)).toList();
        byte[] xlsx = excel.buildDebts(list, notifier::userName, notifier::kassaName, ks.zone());
        sender.sendDocument(chatId, xlsx, "qarzdorlar_" + ks.today() + ".xlsx",
                "🧾 Qarzdorlar: " + list.size() + " ta" + (kassa > 0 ? " · " + notifier.kassaName(kassa) : "")
                + (stName == null ? "" : " · " + stName)
                + (type > 0 ? " · " + ShipmentControlService.typeLabel(ShipmentControlService.TYPE_KEYS[type]) : ""));
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

    /** Kontragent xatolari filtri: arg "<page>[.<kassa|0>[.<kod|->]]" — kod K1..K8 yoki "-" (hammasi). */
    private record ErrFilter(int page, long kassa, String code) {
        static ErrFilter parse(String arg) {
            int page = 0; long kassa = 0; String code = "-";
            if (arg != null && !arg.isBlank()) {
                String[] p = arg.split("\\.");
                try {
                    page = Integer.parseInt(p[0]);
                    kassa = p.length > 1 ? Long.parseLong(p[1]) : 0;
                    code = p.length > 2 && !p[2].isBlank() ? p[2] : "-";
                } catch (NumberFormatException ignored) { }
            }
            return new ErrFilter(page, kassa, code);
        }
        String tail() { return "." + kassa + "." + code; }
    }

    private List<AgentCheck> filteredErrors(AppUser u, ErrFilter f) {
        List<AgentCheck> list = new ArrayList<>(agents.visibleFor(u));
        if (f.kassa() > 0) list.removeIf(ac -> !Long.valueOf(f.kassa()).equals(ac.getKassaId()));
        if (!f.code().equals("-")) list.removeIf(ac -> !ac.violationList().contains(f.code()));
        return list;
    }

    void errorList(AppUser u, String arg, long chatId, int msgId) {
        ErrFilter f = ErrFilter.parse(arg);
        int page = f.page();
        boolean admin = u.getRole() == Role.SUPERADMIN || u.getRole() == Role.BUXGALTER;
        List<AgentCheck> base = filteredErrors(u, new ErrFilter(0, f.kassa(), "-"));
        List<AgentCheck> list = filteredErrors(u, f);
        StringBuilder sb = new StringBuilder("⚠️ <b>Контрагент хатолари</b>"
                + (u.getRole() == Role.KASSIR ? "" : " (hammasi)"));
        if (f.kassa() > 0) sb.append(" — ").append(esc(notifier.kassaName(f.kassa())));
        if (!f.code().equals("-")) sb.append(" · ").append(AgentCheckService.shortTitle(f.code()));
        sb.append("\n");
        if (list.isEmpty()) sb.append("\nTuzatilmagan kontragentlar yo'q ✅");
        else sb.append("Tuzatilmagan: <b>").append(list.size()).append("</b> ta\n\nTanlang:");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("✅ 🏢 Kontragentlar (" + agents.visibleFor(u).size() + ")", "kg:el"),
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
        if (page > 0) nav.add(btn("⬅️ Oldingi", "kg:el:" + (page - 1) + f.tail()));
        if (from + PAGE < list.size()) nav.add(btn("Keyingi ➡️", "kg:el:" + (page + 1) + f.tail()));
        if (!nav.isEmpty()) rows.add(nav);
        // 🏷 xato turi filtri (faqat uchraydiganlar)
        java.util.Map<String, Integer> byCode = new java.util.LinkedHashMap<>();
        for (AgentCheck ac : base) for (String c : ac.violationList()) byCode.merge(c, 1, Integer::sum);
        if (!byCode.isEmpty()) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(btn((f.code().equals("-") ? "✅ " : "") + "Hammasi (" + base.size() + ")", "kg:el:0." + f.kassa() + ".-"));
            for (var e : byCode.entrySet()) {
                r.add(btn((f.code().equals(e.getKey()) ? "✅ " : "") + AgentCheckService.shortTitle(e.getKey()) + " (" + e.getValue() + ")",
                        "kg:el:0." + f.kassa() + "." + e.getKey()));
                if (r.size() == 3) { rows.add(r); r = new ArrayList<>(); }
            }
            if (!r.isEmpty()) rows.add(r);
        }
        if (admin) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(btn(f.kassa() == 0 ? "✅ Hammasi" : "Hammasi", "kg:el:0.0." + f.code()));
            for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
                if (k.isCashless()) continue;
                r.add(btn((f.kassa() == k.getId() ? "✅ " : "🏪 ") + k.getName(), "kg:el:0." + k.getId() + "." + f.code()));
                if (r.size() == 3) { rows.add(r); r = new ArrayList<>(); }
            }
            if (!r.isEmpty()) rows.add(r);
        }
        if (!list.isEmpty()) rows.add(irow(btn("📥 Excel (shu filtr bilan)", "kg:ee:" + f.kassa() + "." + f.code())));
        rows.add(tools(u, "el"));
        rows.add(irow(ks.bk("kg:m")));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }


    /** arg: "<kassa>.<kod>" (ro'yxat filtri). */
    void errorExcel(AppUser u, String arg, long chatId) {
        String[] p = (arg == null ? "" : arg).split("\\.");
        long kassa = 0; String code = "-";
        try { if (p.length > 0 && !p[0].isBlank()) kassa = Long.parseLong(p[0]); } catch (NumberFormatException ignored) { }
        if (p.length > 1 && !p[1].isBlank()) code = p[1];
        List<AgentCheck> list = filteredErrors(u, new ErrFilter(0, kassa, code));
        byte[] xlsx = excel.buildAgentErrors(list, notifier::userName, notifier::kassaName,
                uz.kassa.service.control.ControlConfig::ruleTitle);
        sender.sendDocument(chatId, xlsx, "kontragent_xatolari_" + ks.today() + ".xlsx",
                "⚠️ Tuzatilmagan kontragentlar: " + list.size() + " ta"
                + (kassa > 0 ? " · " + notifier.kassaName(kassa) : "")
                + (code.equals("-") ? "" : " · " + AgentCheckService.shortTitle(code)));
    }


    /** Otgruzka kamchiliklari filtri: arg "<page>[.<kassa|0>[.<tur 0-3>[.<kod|->]]]" — kod O1..O5. */
    private record IssFilter(int page, long kassa, int type, String code) {
        static IssFilter parse(String arg) {
            int page = 0; long kassa = 0; int type = 0; String code = "-";
            if (arg != null && !arg.isBlank()) {
                String[] p = arg.split("\\.");
                try {
                    page = Integer.parseInt(p[0]);
                    kassa = p.length > 1 ? Long.parseLong(p[1]) : 0;
                    type = p.length > 2 ? Integer.parseInt(p[2]) : 0;
                    code = p.length > 3 && !p[3].isBlank() ? p[3] : "-";
                } catch (NumberFormatException ignored) { }
            }
            if (type < 0 || type >= ShipmentControlService.TYPE_KEYS.length) type = 0;
            return new IssFilter(page, kassa, type, code);
        }
        String tail() { return "." + kassa + "." + type + "." + code; }
    }

    private List<Shipment> filteredIssues(AppUser u, IssFilter f) {
        List<Shipment> list = new ArrayList<>(ships.visibleIssuesFor(u, f.kassa() > 0 ? f.kassa() : null));
        if (f.type() > 0) list.removeIf(x -> !ShipmentControlService.typeMatches(x, f.type()));
        if (!f.code().equals("-")) list.removeIf(x -> !x.issueList().contains(f.code()));
        return list;
    }

    /** 📦 Kamchilikli (qarzdagi) otgruzkalar: muddat/masul/status/izoh/telefon — otdel, tur, kamchilik filtri bilan. */
    void issueList(AppUser u, String arg, long chatId, int msgId) {
        IssFilter f = IssFilter.parse(arg);
        int page = f.page();
        boolean admin = u.getRole() == Role.SUPERADMIN || u.getRole() == Role.BUXGALTER;
        List<Shipment> base = filteredIssues(u, new IssFilter(0, f.kassa(), 0, "-"));
        List<Shipment> list = filteredIssues(u, f);
        StringBuilder sb = new StringBuilder("📦 <b>Otgruzka kamchiliklari</b>"
                + (u.getRole() == Role.KASSIR ? "" : " (hammasi)"));
        if (f.kassa() > 0) sb.append(" — ").append(esc(notifier.kassaName(f.kassa())));
        if (f.type() > 0) sb.append(" · ").append(ShipmentControlService.typeLabel(ShipmentControlService.TYPE_KEYS[f.type()]));
        if (!f.code().equals("-")) sb.append(" · ").append(ShipmentControlService.issueShort(f.code()));
        sb.append("\n");
        if (list.isEmpty()) sb.append("\nKamchilikli otgruzkalar yo'q ✅");
        else sb.append("Qarzdagi otgruzkalar: <b>").append(list.size()).append("</b> ta — ")
               .append(ShipmentControlService.issueSummary(list))
               .append("\nMoySklad'da otgruzkani ochib to'ldiring, bot o'zi tekshiradi. Tanlang:");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("🏢 Kontragentlar (" + agents.visibleFor(u).size() + ")", "kg:el"),
                      btn("✅ 📦 Otgruzkalar (" + ships.visibleIssuesFor(u, null).size() + ")", "kg:ol")));
        int from = page * PAGE;
        for (int i = from; i < Math.min(list.size(), from + PAGE); i++) {
            Shipment x = list.get(i);
            String label = "📦 №" + x.getDocNo() + " · " + x.getAgentName() + " · " + ShipmentControlService.issueLabels(x.issueList());
            if (label.length() > 60) label = label.substring(0, 60);
            rows.add(irow(btn(label, "kg:dv:" + x.getId())));
        }
        List<InlineKeyboardButton> nav = new ArrayList<>();
        if (page > 0) nav.add(btn("⬅️ Oldingi", "kg:ol:" + (page - 1) + f.tail()));
        if (from + PAGE < list.size()) nav.add(btn("Keyingi ➡️", "kg:ol:" + (page + 1) + f.tail()));
        if (!nav.isEmpty()) rows.add(nav);
        // ❗ kamchilik turi filtri
        java.util.Map<String, Integer> byCode = new java.util.LinkedHashMap<>();
        for (Shipment x : base) for (String c : x.issueList()) byCode.merge(c, 1, Integer::sum);
        if (!byCode.isEmpty()) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(btn((f.code().equals("-") ? "✅ " : "") + "Hammasi (" + base.size() + ")", "kg:ol:0." + f.kassa() + "." + f.type() + ".-"));
            for (var e : byCode.entrySet()) {
                r.add(btn((f.code().equals(e.getKey()) ? "✅ " : "") + ShipmentControlService.issueShort(e.getKey()) + " (" + e.getValue() + ")",
                        "kg:ol:0." + f.kassa() + "." + f.type() + "." + e.getKey()));
                if (r.size() == 3) { rows.add(r); r = new ArrayList<>(); }
            }
            if (!r.isEmpty()) rows.add(r);
        }
        // 🏢/👤 kontragent turi
        {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(btn((f.type() == 0 ? "✅ " : "") + "Barcha tur", "kg:ol:0." + f.kassa() + ".0." + f.code()));
            for (int t = 1; t < ShipmentControlService.TYPE_KEYS.length; t++)
                r.add(btn((f.type() == t ? "✅ " : "") + ShipmentControlService.typeLabel(ShipmentControlService.TYPE_KEYS[t])
                        + " (" + ShipmentControlService.countType(base, t) + ")", "kg:ol:0." + f.kassa() + "." + t + "." + f.code()));
            rows.add(r);
        }
        if (admin) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(btn(f.kassa() == 0 ? "✅ Hammasi" : "Hammasi", "kg:ol:0.0." + f.type() + "." + f.code()));
            for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
                if (k.isCashless()) continue;
                r.add(btn((f.kassa() == k.getId() ? "✅ " : "🏪 ") + k.getName(), "kg:ol:0." + k.getId() + "." + f.type() + "." + f.code()));
                if (r.size() == 3) { rows.add(r); r = new ArrayList<>(); }
            }
            if (!r.isEmpty()) rows.add(r);
        }
        if (!list.isEmpty()) rows.add(irow(btn("📥 Excel (shu filtr bilan)", "kg:ox:" + f.kassa() + "." + f.type() + "." + f.code())));
        rows.add(tools(u, "ol"));
        rows.add(irow(ks.bk("kg:m")));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }


    /** arg: "<kassa>.<tur>.<kod>" — kamchilikli otgruzkalar Excel (kamchilik ustuni bilan). */
    void issueExcel(AppUser u, String arg, long chatId) {
        IssFilter f = IssFilter.parse("0." + (arg == null ? "" : arg));
        List<Shipment> list = filteredIssues(u, f);
        byte[] xlsx = excel.buildDebts(list, notifier::userName, notifier::kassaName, ks.zone());
        sender.sendDocument(chatId, xlsx, "otgruzka_kamchiliklari_" + ks.today() + ".xlsx",
                "📦 Kamchilikli otgruzkalar: " + list.size() + " ta"
                + (f.kassa() > 0 ? " · " + notifier.kassaName(f.kassa()) : "")
                + (f.type() > 0 ? " · " + ShipmentControlService.typeLabel(ShipmentControlService.TYPE_KEYS[f.type()]) : "")
                + (f.code().equals("-") ? "" : " · " + ShipmentControlService.issueShort(f.code())));
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
                if (v == null) {
                    note = "\n\n🗑 Kontragent MoySklad'da topilmadi (o'chirilgan) — xato yopildi.";
                    if (ac.getStatus() == AgentCheck.Status.OCHIQ) agents.markDeleted(ac);
                }
                else if (v.isEmpty()) note = "\n\n✅ Hozir xato yo'q.";
            } catch (Exception e) { note = "\n\n⚠️ MoySklad'dan o'qilmadi: " + esc(String.valueOf(e.getMessage())); }
        }
        StringBuilder sb = new StringBuilder("⚠️ <b>Kontragent xatosi</b> · ");
        sb.append(switch (ac.getStatus()) {
            case OCHIQ -> "🔴 tuzatilmagan"; case TUZATILDI -> "✅ tuzatildi"; case ETIBORSIZ -> "🙈 e'tiborsiz"; case OK -> "✅ ok";
            case OCHIRILDI -> "🗑 o'chirilgan"; });
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
