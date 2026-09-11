package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.Sender;
import uz.kassa.bot.Session;
import uz.kassa.domain.*;
import uz.kassa.repo.KassaRepo;
import uz.kassa.repo.OmborHujjatRepo;
import uz.kassa.repo.OmborKamchilikRepo;
import uz.kassa.repo.OmborTovarRepo;
import uz.kassa.service.ombor.*;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.*;

/**
 * 🏬 Омбор — foydalanuvchi bo'limi (docs/OMBOR-TZ.md §10): ⚠️ Камчиликлар (ro'yxat, karta, ✅/🙈/javoblar),
 * 📦 Қолдиқ (qidirish, do'kon kesimida), 🔢 Санoq (kladovchi: fakt kiritish, tasdiqlash), 🔄 Yangilash, 📊 Ҳолат.
 * Kassir — o'z do'koni; buxgalter/SuperAdmin — hammasi. Callback prefiksi: om:*.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OmborHandler {

    public static final String LABEL = "🏬 Омбор";
    private static final int PAGE = 10;
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final Sender sender;
    private final OmborRuleEngine engine;
    private final OmborRecipients rec;
    private final OmborSyncService sync;
    private final OmborSalesService sales;
    private final OmborCalcService calc;
    private final OmborSanoqService sanoq;
    private final OmborMetrics metrics;
    private final OmborConfig cfg;
    private final OmborKamchilikRepo repo;
    private final OmborTovarRepo tovarRepo;
    private final OmborHujjatRepo hujjatRepo;
    private final KassaRepo kassaRepo;
    private final OmborFlowHandler flow;
    private final uz.kassa.webapp.ExcelReportService excel;
    private final OmborNarxService narx;
    private final uz.kassa.repo.OmborYetkazuvchiRepo supRepo;
    private final uz.kassa.service.ombor.checks.DublikatChecker dublikat;
    private final OmborExcelService omborExcel;


    /* ============================ MATN ============================ */

    public boolean onText(AppUser u, Session s, String text, long chatId) {
        if (s.state == Session.State.OM_SEARCH) { s.reset(); search(u, text, chatId, 0, 0); return true; }
        if (s.state == Session.State.OM_SANOQ_QTY) { sanoqQty(u, s, text, chatId); return true; }
        if (s.state == Session.State.OM_ISSUE_Q) { issueQuery(u, s, text, chatId); return true; }
        if (flow.onText(u, s, text, chatId)) return true;
        if (!text.equals(LABEL)) return false;
        s.reset();
        sender.send(chatId, mainText(u), mainKb(u));
        return true;
    }

    private String mainText(AppUser u) {
        List<OmborKamchilik> open = visible(u, null, null);
        long muhim = 0;
        Map<String, OmborQoida> rules = engine.rulesMap();
        for (OmborKamchilik k : open) { OmborQoida r = rules.get(k.getRuleCode()); if (r != null && "MUHIM".equals(r.getSeverity())) muhim++; }
        StringBuilder sb = new StringBuilder("🏬 <b>Омбор</b>\n\n");
        if (!cfg.enabled()) sb.append("⚪ Modul o'chirilgan (⚙️ Настройка → 🔗 MoySklad → 🏬 Омбор назорати)\n\n");
        sb.append("⚠️ Ochiq kamchiliklar: <b>").append(open.size()).append("</b>").append(muhim > 0 ? " · 🔴 muhim: " + muhim : "").append("\n");
        var d = metrics.lastDate(OmborMetrics.QOLDIQ);
        sb.append("📦 Qoldiqlar: ").append(d == null ? "hali o'qilmagan" : d + " holatiga").append("\n");
        sb.append("🗂 Tovarlar: <b>").append(tovarRepo.countByArchivedFalse()).append("</b> ta faol\n");
        long sn = sanoqOpenFor(u);
        if (sn > 0) sb.append("🔢 Sanoq kutmoqda: <b>").append(sn).append("</b> ta tovar\n");
        var fr = metrics.latest(OmborCalcService.FILL_RATE_30, "kassa_id = 0 AND product_ms_id = ''");
        if (!fr.isEmpty()) sb.append("📈 Fill rate (30 kun, A): <b>").append(((BigDecimal) fr.get(0).get("value")).stripTrailingZeros().toPlainString()).append("%</b>\n");
        return sb.toString();
    }

    private InlineKeyboardMarkup mainKb(AppUser u) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(sbtn("⚠️ Камчиликлар", "om:l", uz.kassa.bot.StyledButton.DANGER), sbtn("📦 Қолдиқ (qidirish)", "om:s", uz.kassa.bot.StyledButton.PRIMARY)));
        rows.add(irow(sbtn("🔢 Санoq", "om:sn", uz.kassa.bot.StyledButton.SUCCESS), sbtn("📝 Сўров", "om:sr", uz.kassa.bot.StyledButton.SUCCESS)));
        rows.add(irow(sbtn("🧾 Қоралама", "om:qr", uz.kassa.bot.StyledButton.PRIMARY), btn("🤝 Ҳамкорлар", "om:hk")));
        List<InlineKeyboardButton> r3 = new ArrayList<>();
        r3.add(btn("📊 Ҳолат", "om:st"));
        if (flow.isZakupshik(u)) r3.add(btn("💵 Нарх киритиш", "om:nx"));
        rows.add(r3);
        if (u.getRole() != Role.KASSIR) rows.add(irow(btn("🔄 Yangilash (MoySklad)", "om:rf")));
        return inline(rows);
    }


    /* ============================ CALLBACK ============================ */

    public boolean onCallback(AppUser u, Session s, String data, long chatId, int msgId) {
        if (!data.startsWith("om:")) return false;
        String[] p = data.split(":", 3);
        String cmd = p[1];
        String arg = p.length > 2 ? p[2] : "";
        switch (cmd) {
            case "m" -> { s.reset(); sender.edit(chatId, msgId, mainText(u), mainKb(u)); }   // filtr va qidiruv tozalanadi
            case "l" -> list(u, s, arg, chatId, msgId);
            case "lq" -> askIssueQuery(s, chatId, msgId);
            case "lg" -> {
                Set<String> c = new LinkedHashSet<>();
                for (String g : (s.getStr("omGc") == null ? "sev,type,rule,kassa" : s.getStr("omGc")).split(",")) if (!g.isBlank()) c.add(g);
                if (!c.remove(arg)) c.add(arg);
                s.data.put("omGc", String.join(",", c));
                list(u, s, s.getStr("omF"), chatId, msgId);
            }
            case "nop" -> { }
            case "lx" -> { s.data.remove("omQ"); list(u, s, s.getStr("omF"), chatId, msgId); }
            case "le" -> { issuesExcel(u, s, chatId); }
            case "v" -> card(u, s, Long.parseLong(arg), chatId, msgId);
            case "ok", "ig" -> resolve(u, s, Long.parseLong(arg), cmd.equals("ig") ? OmborRuleEngine.ANSWER_IGNORE : OmborRuleEngine.ANSWER_FIXED, chatId, msgId);
            case "an" -> { int dot = arg.indexOf('.'); resolve(u, s, Long.parseLong(arg.substring(0, dot)), arg.substring(dot + 1), chatId, msgId); }
            case "s" -> {
                s.state = Session.State.OM_SEARCH;
                sender.edit(chatId, msgId, "🔎 Tovar <b>nomi</b>, <b>artikuli</b>, <b>kodi</b> yoki <b>shtrix-kodi</b>ni yozing:",
                        inline(List.of(irow(btn("⬅️ Orqaga", "om:m")))));
            }
            case "q" -> search(u, arg.substring(arg.indexOf('.') + 1), chatId, Integer.parseInt(arg.substring(0, arg.indexOf('.'))), msgId);
            case "t" -> tovar(u, s, arg, chatId, msgId);
            case "sn" -> sanoqList(u, s, arg, chatId, msgId);
            case "snv" -> sanoqAsk(u, s, Long.parseLong(arg), chatId, msgId);
            case "snc" -> sanoqConfirm(u, s, Long.parseLong(arg), chatId, msgId);
            case "rf" -> refresh(u, chatId, msgId);
            case "st" -> status(u, chatId, msgId);
            default -> { return flow.onCallback(u, s, cmd, arg, chatId, msgId); }
        }
        return true;
    }


    /* ==================== ⚠️ Камчиликлар ==================== */

    private List<OmborKamchilik> visible(AppUser u, Long kassa, String rule) {
        List<OmborKamchilik> out = new ArrayList<>();
        for (OmborKamchilik k : repo.findByResolvedAtIsNullOrderBySinceDesc()) {
            if (!rec.canSee(u, k)) continue;
            if (kassa != null && kassa > 0 && !kassa.equals(k.getKassaId())) continue;
            if (rule != null && !rule.equals(k.getRuleCode())) continue;
            out.add(k);
        }
        return out;
    }

    /** Filtr: do'kon · qoida · daraja · tur · matn (tovar). arg: "kassa.page.rule.sev.type" ("-" = hammasi). */
    private record Filt(long kassa, int page, String rule, String sev, String type) {
        static Filt parse(String arg) {
            long k = 0; int pg = 0; String r = null, sv = null, tp = null;
            if (arg != null && !arg.isBlank()) {
                String[] p = arg.split("\\.");
                try { k = Long.parseLong(p[0]); pg = p.length > 1 ? Integer.parseInt(p[1]) : 0; } catch (NumberFormatException ignored) { }
                if (p.length > 2 && !p[2].equals("-")) r = p[2];
                if (p.length > 3 && !p[3].equals("-")) sv = p[3];
                if (p.length > 4 && !p[4].equals("-")) tp = p[4];
            }
            return new Filt(k, pg, r, sv, tp);
        }
        String enc(long k, int pg, String r, String sv, String tp) { return k + "." + pg + "." + (r == null ? "-" : r) + "." + (sv == null ? "-" : sv) + "." + (tp == null ? "-" : tp); }
        String self() { return enc(kassa, page, rule, sev, type); }
        String withKassa(long k) { return enc(k, 0, rule, sev, type); }
        String withRule(String r) { return enc(kassa, 0, r, sev, type); }
        String withSev(String v) { return enc(kassa, 0, rule, v, type); }
        String withType(String t) { return enc(kassa, 0, rule, sev, t); }
        String withPage(int p) { return enc(kassa, p, rule, sev, type); }
    }

    private static final String[] TYPES = {"tovar", "hujjat", "sanoq", "sorov", "qoralama", "hamkor", "narx", "sinxron"};
    private static String typeTitle(String t) {
        return switch (t) { case "tovar" -> "📦 Tovar"; case "hujjat" -> "📄 Hujjat"; case "sanoq" -> "🔢 Sanoq"; case "sorov" -> "📝 So'rov";
            case "qoralama" -> "🧾 Qoralama"; case "hamkor" -> "🤝 Hamkor"; case "narx" -> "💵 Narx"; case "sinxron" -> "🔄 Sinxron"; default -> t; };
    }

    /** Filtrlangan ochiq kamchiliklar (foydalanuvchi ko'ra oladiganlar). q — matn (tovar nomi/artikul) bo'yicha. */
    private List<OmborKamchilik> filtered(AppUser u, Filt f, String q, Map<String, OmborQoida> rules) {
        String qn = q == null ? "" : OmborTovar.norm(q);
        List<OmborKamchilik> out = new ArrayList<>();
        for (OmborKamchilik k : repo.findByResolvedAtIsNullOrderBySinceDesc()) {
            if (!rec.canSee(u, k)) continue;
            if (f.kassa > 0 && !Long.valueOf(f.kassa).equals(k.getKassaId())) continue;
            if (f.rule != null && !f.rule.equals(k.getRuleCode())) continue;
            if (f.type != null && !f.type.equals(k.getSubjectType())) continue;
            if (f.sev != null) { OmborQoida r = rules.get(k.getRuleCode()); if (r == null || !f.sev.equals(r.getSeverity())) continue; }
            if (!qn.isEmpty() && !OmborTovar.norm(k.getTitle() + " " + k.getDetail().replaceAll("<[^>]+>", "")).contains(qn)) continue;
            out.add(k);
        }
        return out;
    }

    private void list(AppUser u, Session s, String arg, long chatId, int msgId) {
        Filt f = Filt.parse(arg);
        s.data.put("omF", f.self());
        String q = s.getStr("omQ");
        Map<String, OmborQoida> rules = engine.rulesMap();
        List<OmborKamchilik> baseSev = filtered(u, new Filt(f.kassa, 0, f.rule, null, f.type), q, rules);
        List<OmborKamchilik> baseType = filtered(u, new Filt(f.kassa, 0, f.rule, f.sev, null), q, rules);
        List<OmborKamchilik> baseRule = filtered(u, new Filt(f.kassa, 0, null, f.sev, f.type), q, rules);
        List<OmborKamchilik> baseKassa = filtered(u, new Filt(0, 0, f.rule, f.sev, f.type), q, rules);
        List<OmborKamchilik> list = filtered(u, f, q, rules);
        StringBuilder sb = new StringBuilder("⚠️ <b>Омбор камчиликлари</b> — <b>").append(list.size()).append("</b> ta\n");
        List<String> act = new ArrayList<>();
        if (f.kassa > 0) act.add("🟥 " + rec.notifier().kassaName(f.kassa));
        if (f.sev != null) act.add("🟦 " + sevTitle(f.sev));
        if (f.type != null) act.add("🟩 " + typeTitle(f.type));
        if (f.rule != null && rules.containsKey(f.rule)) act.add("⬜ " + rules.get(f.rule).getTitle());
        if (q != null && !q.isBlank()) act.add("🔎 «" + q + "»");
        sb.append(act.isEmpty() ? "Filtr: hammasi" : "Filtr: " + esc(String.join(" · ", act))).append("\n");
        if (list.isEmpty()) sb.append("\nBu filtr bo'yicha ochiq kamchilik yo'q ✅");
        else sb.append("\n📋 <b>Ro'yxat</b> (bosib kartasini oching), pastda 🎛 filtrlar (guruhni bosib oching): 🟦 daraja · 🟩 tur · ⬜ qoida · 🟥 do'kon");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int from = f.page * PAGE;
        int pages = Math.max(1, (list.size() + PAGE - 1) / PAGE);
        if (!list.isEmpty()) rows.add(irow(btn("📋 RO'YXAT · " + (from + 1) + "–" + Math.min(list.size(), from + PAGE) + " / " + list.size(), "om:nop")));
        for (int i = from; i < Math.min(list.size(), from + PAGE); i++) {
            OmborKamchilik k = list.get(i);
            OmborQoida r = rules.get(k.getRuleCode());
            String label = (r == null ? "•" : r.emoji()) + " " + k.getTitle()
                    + (k.getKassaId() != null && f.kassa == 0 ? " · " + rec.notifier().kassaName(k.getKassaId()).replace("Отдел ", "") : "");
            rows.add(irow(btn(cut(label, 60), "om:v:" + k.getId())));
        }
        if (pages > 1) {
            List<InlineKeyboardButton> nav = new ArrayList<>();
            if (f.page > 0) nav.add(btn("⬅️", "om:l:" + f.withPage(f.page - 1)));
            nav.add(btn("sahifa " + (f.page + 1) + "/" + pages, "om:nop"));
            if (from + PAGE < list.size()) nav.add(btn("➡️", "om:l:" + f.withPage(f.page + 1)));
            rows.add(nav);
        }
        // ───────── 🎛 FILTRLAR — har guruh o'z rangida, bittasi ochiq (om:lg:<g>) ─────────
        // yopiq guruhlar to'plami (har biri alohida yopiladi/ochiladi)
        // standart: hammasi YOPIQ ("omGc" — yopiqlar; boshlang'ich qiymat to'rttalasi)
        if (s.getStr("omGc") == null) s.data.put("omGc", "sev,type,rule,kassa");
        Set<String> closed = new HashSet<>();
        for (String g : s.getStr("omGc").split(",")) if (!g.isBlank()) closed.add(g);
        final String P = uz.kassa.bot.StyledButton.PRIMARY, S = uz.kassa.bot.StyledButton.SUCCESS, D = uz.kassa.bot.StyledButton.DANGER;
        // 🟦 daraja (ko'k)
        rows.add(irow(sbtn("🟦 DARAJA: " + (f.sev == null ? "hammasi" : sevTitle(f.sev)) + (closed.contains("sev") ? "  ▾" : "  ▴"), "om:lg:sev", P)));
        if (!closed.contains("sev")) {
            List<InlineKeyboardButton> sr = new ArrayList<>();
            sr.add(sbtn((f.sev == null ? "✔️ " : "") + "Hammasi", "om:l:" + f.withSev(null), P));
            for (String v : List.of("MUHIM", "OGOH", "INFO")) {
                long c = baseSev.stream().filter(k -> { OmborQoida r = rules.get(k.getRuleCode()); return r != null && v.equals(r.getSeverity()); }).count();
                if (c == 0 && !v.equals(f.sev)) continue;
                sr.add(sbtn((v.equals(f.sev) ? "✔️ " : "") + sevTitle(v) + " " + c, "om:l:" + f.withSev(v), P));
            }
            rows.add(sr);
        }
        // 🟩 tur (yashil)
        rows.add(irow(sbtn("🟩 TUR: " + (f.type == null ? "hammasi" : typeTitle(f.type)) + (closed.contains("type") ? "  ▾" : "  ▴"), "om:lg:type", S)));
        if (!closed.contains("type")) {
            List<InlineKeyboardButton> tr = new ArrayList<>();
            tr.add(sbtn((f.type == null ? "✔️ " : "") + "Hammasi", "om:l:" + f.withType(null), S));
            for (String t : TYPES) {
                long c = baseType.stream().filter(k -> t.equals(k.getSubjectType())).count();
                if (c == 0 && !t.equals(f.type)) continue;
                tr.add(sbtn((t.equals(f.type) ? "✔️ " : "") + typeTitle(t) + " " + c, "om:l:" + f.withType(t), S));
                if (tr.size() == 3) { rows.add(tr); tr = new ArrayList<>(); }
            }
            if (!tr.isEmpty()) rows.add(tr);
        }
        // ⬜ qoida (oddiy)
        Map<String, Integer> byRule = new LinkedHashMap<>();
        for (OmborKamchilik k : baseRule) byRule.merge(k.getRuleCode(), 1, Integer::sum);
        List<Map.Entry<String, Integer>> ruleList = new ArrayList<>(byRule.entrySet());
        ruleList.sort((x, y) -> y.getValue() - x.getValue());
        rows.add(irow(btn("⬜ QOIDA: " + (f.rule == null ? "hammasi (" + byRule.size() + ")" : cut(rules.containsKey(f.rule) ? rules.get(f.rule).getTitle() : f.rule, 28)) + (closed.contains("rule") ? "  ▾" : "  ▴"), "om:lg:rule")));
        if (!closed.contains("rule")) {
            List<InlineKeyboardButton> rr = new ArrayList<>();
            rr.add(btn((f.rule == null ? "✔️ " : "") + "Hammasi", "om:l:" + f.withRule(null)));
            for (var e : ruleList) {
                OmborQoida r = rules.get(e.getKey());
                rr.add(btn((e.getKey().equals(f.rule) ? "✔️ " : (r == null ? "" : r.emoji() + " ")) + cut(r == null ? e.getKey() : r.getTitle(), 22) + " " + e.getValue(), "om:l:" + f.withRule(e.getKey())));
                if (rr.size() == 2) { rows.add(rr); rr = new ArrayList<>(); }
            }
            if (!rr.isEmpty()) rows.add(rr);
        }
        // 🟥 do'kon / sklad (qizil)
        if (u.getRole() != Role.KASSIR) {
            rows.add(irow(sbtn("🟥 DO'KON: " + (f.kassa == 0 ? "hammasi" : rec.notifier().kassaName(f.kassa)) + (closed.contains("kassa") ? "  ▾" : "  ▴"), "om:lg:kassa", D)));
            if (!closed.contains("kassa")) {
                List<InlineKeyboardButton> kr = new ArrayList<>();
                kr.add(sbtn((f.kassa == 0 ? "✔️ " : "") + "Hammasi", "om:l:" + f.withKassa(0), D));
                for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
                    if (k.isCashless()) continue;
                    long c = baseKassa.stream().filter(x -> k.getId().equals(x.getKassaId())).count();
                    kr.add(sbtn((f.kassa == k.getId() ? "✔️ " : "") + cut(k.getName().replace("Отдел ", ""), 12) + " " + c, "om:l:" + f.withKassa(k.getId()), D));
                    if (kr.size() == 3) { rows.add(kr); kr = new ArrayList<>(); }
                }
                if (!kr.isEmpty()) rows.add(kr);
            }
        }
        // 🔎 qidiruv · 📥 Excel (oddiy, doim ko'rinadi)
        List<InlineKeyboardButton> tools = new ArrayList<>();
        tools.add(btn("🔎 Tovar/matn" + (q != null && !q.isBlank() ? ": «" + cut(q, 12) + "»" : ""), "om:lq"));
        if (q != null && !q.isBlank()) tools.add(btn("❌ Tozalash", "om:lx"));
        tools.add(btn("📥 Excel (" + list.size() + ")", "om:le"));
        rows.add(tools);
        rows.add(irow(btn("⬅️ Омбор", "om:m")));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }

    private static String sevTitle(String v) { return switch (v) { case "MUHIM" -> "🔴 Muhim"; case "OGOH" -> "🟠 Ogoh"; case "INFO" -> "ℹ️ Info"; default -> v; }; }

    /** 🔎 matn so'rash → OM_ISSUE_Q → list qayta chiziladi (filtr saqlanadi). */
    private void askIssueQuery(Session s, long chatId, int msgId) {
        s.state = Session.State.OM_ISSUE_Q;
        s.data.put("omMsg", msgId);
        sender.edit(chatId, msgId, "🔎 Tovar nomi, artikuli yoki istalgan matnni yozing — kamchiliklar shu bo'yicha filtrlanadi:",
                inline(List.of(irow(btn("⬅️ Ro'yxat", "om:l:" + s.getStr("omF"))))));
    }

    private void issueQuery(AppUser u, Session s, String text, long chatId) {
        String f = s.getStr("omF");
        s.state = Session.State.IDLE;
        s.data.put("omQ", text.trim());
        Integer sent = sender.sendId(chatId, "🔎 Filtr: «" + esc(text.trim()) + "»", null);
        if (sent != null) list(u, s, Filt.parse(f).withPage(0), chatId, sent);
    }

    /** 📥 Dublikat hisoboti — skrinshotdagi shablon: №, Название 1, Код 1, Артикул 1, Остаток 1, Название 2, Код 2, Артикул 2, Остаток 2, Причина, Статус. */
    public byte[] dublikatExcel(String q) {
        String qn = q == null ? "" : OmborTovar.norm(q);
        List<Object[]> rows = new ArrayList<>();
        int n = 0;
        for (var pr : dublikat.pairs("name,barcode,article")) {
            if (!qn.isEmpty() && !OmborTovar.norm(pr.a().getName() + " " + pr.b().getName() + " " + pr.a().getArticle() + " " + pr.b().getArticle()).contains(qn)) continue;
            rows.add(new Object[]{++n, pr.a().getName(), pr.a().getCode(), pr.a().getArticle(), stockOf(pr.a().getMsId()), pr.a().getFolderName(),
                    pr.b().getName(), pr.b().getCode(), pr.b().getArticle(), stockOf(pr.b().getMsId()), pr.b().getFolderName(), pr.reason(), "Дубликат"});
        }
        return excel.buildTable("Дубликаты", new String[]{"№", "Название 1", "Код 1", "Артикул 1", "Остаток 1", "Группа 1",
                "Название 2", "Код 2", "Артикул 2", "Остаток 2", "Группа 2", "Причина совпадения", "Статус"}, rows);
    }

    private BigDecimal stockOf(String pid) { return metrics.stockOf(pid).getOrDefault(0L, BigDecimal.ZERO); }

    /** 📥 Excel — filtrlangan ochiq kamchiliklar, har qoida turi o'z faktli varag'ida (OmborExcelService). */
    private void issuesExcel(AppUser u, Session s, long chatId) {
        Filt f = Filt.parse(s.getStr("omF"));
        Map<String, OmborQoida> rules = engine.rulesMap();
        List<OmborKamchilik> list = filtered(u, f, s.getStr("omQ"), rules);
        byte[] data = omborExcel.build(list, rules);
        Set<String> sheets = new LinkedHashSet<>();
        for (OmborKamchilik k : list) sheets.add(OmborExcelService.sheetOf(k.getRuleCode()));
        sender.sendDocument(chatId, data, "ombor-kamchiliklar-" + java.time.LocalDate.now(cfg.zone()) + ".xlsx",
                "📥 Ombor kamchiliklari: " + list.size() + " ta" + (f.kassa > 0 ? " · " + rec.notifier().kassaName(f.kassa) : "")
                + (f.rule != null && rules.containsKey(f.rule) ? " · " + rules.get(f.rule).getTitle() : "")
                + "\nVaraqlar: Хулоса" + (sheets.isEmpty() ? "" : ", " + String.join(", ", sheets)));
    }


    private String back(Session s) { String f = s.getStr("omF"); return "om:l:" + (f == null ? "" : f); }

    private void card(AppUser u, Session s, long id, long chatId, int msgId) {
        OmborKamchilik k = repo.findById(id).orElse(null);
        if (k == null || !rec.canSee(u, k)) { sender.edit(chatId, msgId, "⚠️ Topilmadi.", inline(List.of(irow(btn("⬅️ Ro'yxat", back(s)))))); return; }
        OmborQoida r = engine.rulesMap().get(k.getRuleCode());
        ZoneId z = cfg.zone();
        StringBuilder sb = new StringBuilder((r == null ? "•" : r.emoji()) + " <b>" + esc(r == null ? k.getRuleCode() : r.getTitle()) + "</b>\n\n");
        sb.append(k.getDetail()).append("\n\n");
        if (k.getKassaId() != null) sb.append("🏪 ").append(esc(rec.notifier().kassaName(k.getKassaId()))).append("\n");
        if (k.getOwnerUserId() != null) sb.append("👤 ").append(esc(rec.notifier().userName(k.getOwnerUserId()))).append("\n");
        sb.append("🕒 Topildi: ").append(k.getSince().atZone(z).format(DTF));
        if (k.getEsc1At() != null) sb.append(" · ⏰ rahbarga ").append(k.getEsc1At().atZone(z).format(DTF));
        if (k.getEsc2At() != null) sb.append(" · ❌ adminga ").append(k.getEsc2At().atZone(z).format(DTF));
        if (!k.open()) sb.append("\n✅ Yopildi: ").append(k.getResolvedAt().atZone(z).format(DTF)).append(" · ")
                .append(k.getResolvedBy() == null ? "avto" : esc(rec.notifier().userName(k.getResolvedBy()))).append(" · ").append(esc(OmborRuleEngine.answerTitle(String.valueOf(k.getAnswer()))));
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (k.open()) {
            List<String> answers = engine.answers(r);
            if (answers.isEmpty()) rows.add(irow(btn("✅ Tuzatdim", "om:ok:" + id), btn("🙈 E'tiborsiz (" + cfg.ignoreDays() + " kun)", "om:ig:" + id)));
            else {
                for (String a : answers) rows.add(irow(btn(OmborRuleEngine.answerTitle(a), "om:an:" + id + "." + a)));
                rows.add(irow(btn("🙈 E'tiborsiz", "om:ig:" + id)));
            }
        }
        if (k.getSubjectType().equals("tovar") && k.getSubjectKey().contains("@")) {
            String pid = k.getSubjectKey().substring(0, k.getSubjectKey().indexOf('@'));
            if (pid.contains(":")) pid = pid.substring(pid.indexOf(':') + 1);
            rows.add(irow(btn("📦 Tovar qoldiqlari", "om:t:" + pid)));
        }
        if (k.getSubjectType().equals("hujjat"))
            hujjatRepo.findByMsId(k.getSubjectKey()).ifPresent(h -> rows.add(irow(uz.kassa.service.control.ControlNotifier.urlBtn("🔗 MoySklad'da ochish", h.url()))));
        if (k.getSubjectType().equals("sanoq") && k.getSubjectKey().startsWith("kassa:"))
            rows.add(irow(btn("🔢 Sanoqqa o'tish", "om:sn:" + k.getSubjectKey().substring(6))));
        rows.add(irow(btn("⬅️ Ro'yxat", back(s)), btn("🏬 Омбор", "om:m")));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }

    private void resolve(AppUser u, Session s, long id, String answer, long chatId, int msgId) {
        OmborKamchilik k = repo.findById(id).orElse(null);
        if (k == null || !rec.canSee(u, k)) { sender.edit(chatId, msgId, "⚠️ Topilmadi."); return; }
        if (!k.open()) { card(u, s, id, chatId, msgId); return; }
        engine.resolve(id, u, answer);
        String msg = switch (answer) {
            case OmborRuleEngine.ANSWER_IGNORE -> "🙈 <b>E'tiborsiz qoldirildi</b> — " + cfg.ignoreDays() + " kun qayta chiqmaydi";
            case OmborRuleEngine.ANSWER_FIXED -> "✅ <b>Tuzatildi deb belgilandi</b> — bot keyingi tekshiruvda tasdiqlaydi (yana topsa qayta ochiladi)";
            default -> "✅ <b>Javob yozildi:</b> " + esc(OmborRuleEngine.answerTitle(answer));
        };
        sender.edit(chatId, msgId, msg + "\n\n" + esc(k.getTitle()), inline(List.of(irow(btn("⬅️ Ro'yxat", back(s)), btn("🏬 Омбор", "om:m")))));
    }


    /* ==================== 📦 Қолдиқ ==================== */

    private void search(AppUser u, String q, long chatId, int page, int msgId) {
        String norm = OmborTovar.norm(q);
        List<OmborTovar> list = norm.length() < 2 ? List.of() : tovarRepo.search(norm);
        StringBuilder sb = new StringBuilder("📦 <b>Qidiruv:</b> " + esc(q) + "\n");
        if (list.isEmpty()) sb.append("\nTopilmadi. Kamida 2 belgi; nom, artikul, kod yoki shtrix-kod.");
        else sb.append("Topildi: <b>").append(list.size()).append("</b> ta\n\nTanlang:");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int from = page * PAGE;
        for (int i = from; i < Math.min(list.size(), from + PAGE); i++) {
            OmborTovar t = list.get(i);
            BigDecimal total = metrics.stockOf(t.getMsId()).getOrDefault(0L, BigDecimal.ZERO);
            rows.add(irow(btn(cut(t.getName() + " · " + total.stripTrailingZeros().toPlainString(), 60), "om:t:" + t.getMsId())));
        }
        List<InlineKeyboardButton> nav = new ArrayList<>();
        String qq = q.length() > 40 ? q.substring(0, 40) : q;
        if (page > 0) nav.add(btn("⬅️ Oldingi", "om:q:" + (page - 1) + "." + qq));
        if (from + PAGE < list.size()) nav.add(btn("Keyingi ➡️", "om:q:" + (page + 1) + "." + qq));
        if (!nav.isEmpty()) rows.add(nav);
        rows.add(irow(btn("🔎 Yana qidirish", "om:s"), btn("🏬 Омбор", "om:m")));
        if (msgId > 0) sender.edit(chatId, msgId, sb.toString(), inline(rows));
        else sender.send(chatId, sb.toString(), inline(rows));
    }

    /** 📦 Tovar kartasi — bitta tovar bo'yicha hamma fakt bir joyda: qoldiq (do'kon kesimida), sotuv, ABC, buyurtma nuqtasi,
     *  yetkazuvchi narxi, ochiq kamchiliklar, MoySklad havolasi. */
    private void tovar(AppUser u, Session s, String msId, long chatId, int msgId) {
        OmborTovar t = tovarRepo.findById(msId).orElse(null);
        if (t == null) { sender.edit(chatId, msgId, "⚠️ Tovar topilmadi.", inline(List.of(irow(btn("🔎 Qidirish", "om:s"))))); return; }
        Map<Long, BigDecimal> st = metrics.stockOf(msId);
        StringBuilder sb = new StringBuilder("📦 <b>" + esc(t.getName()) + "</b>\n");
        List<String> ids = new ArrayList<>();
        if (!t.getCode().isBlank()) ids.add("kod " + esc(t.getCode()));
        if (!t.getArticle().isBlank()) ids.add("artikul " + esc(t.getArticle()));
        if (!t.getBarcode().isBlank()) ids.add("shtrix " + esc(t.getBarcode()));
        if (!ids.isEmpty()) sb.append(String.join(" · ", ids)).append("\n");
        if (!t.getFolderName().isBlank()) sb.append("📁 ").append(esc(t.getFolderName())).append("\n");
        if (t.isArchived()) sb.append("🗄 Arxivlangan\n");
        // 💵 narxlar
        sb.append("\n<b>💵 Narx</b>: tannarx ").append(t.getBuyPrice() > 0 ? fmtTiyin(t.getBuyPrice()) : "—").append(" · sotuv ").append(t.getSalePrice() > 0 ? fmtTiyin(t.getSalePrice()) : "—");
        if (t.getBuyPrice() > 0 && t.getSalePrice() > 0) sb.append(" · ustama ").append((t.getSalePrice() - t.getBuyPrice()) * 100 / t.getBuyPrice()).append("%");
        sb.append("\n");
        Map<String, OmborNarx> sup = narx.lastPrices(msId);
        if (!sup.isEmpty()) {
            sb.append("🚚 Yetkazuvchilar (oxirgi narx): ");
            List<String> parts = new ArrayList<>();
            for (var e : sup.entrySet()) parts.add(esc(supRepo.findById(e.getKey()).map(OmborYetkazuvchi::getName).orElse("?")) + " " + fmtTiyin(e.getValue().getPrice()) + " (" + e.getValue().getAtDate().getDayOfMonth() + "." + e.getValue().getAtDate().getMonthValue() + ")");
            sb.append(String.join(", ", parts)).append("\n");
        }
        // 📈 sotuv va tahlil
        String abc = metricOf(OmborCalcService.ABC, 0, msId), ayl = metricOf(OmborMetrics.AYLANMA_KUN, 0, msId), avg0 = metricOf(OmborCalcService.ORTACHA_90, 0, msId);
        sb.append("<b>📈 Sotuv</b>: ").append(avg0 == null ? "90 kunda sotilmagan" : avg0 + " dona/kun (90 kun o'rtacha)")
          .append(" · ABC <b>" + (abc == null ? "C" : abc.equals("1") ? "A" : abc.equals("2") ? "B" : "C") + "</b>" + (abc == null ? " (sotuv yo'q)" : ""))
          .append(ayl == null ? "" : " · harakatsiz " + ayl + " kun").append("\n");
        if (t.getMinBalance().signum() > 0) sb.append("📉 Minimal qoldiq (kartochka): ").append(t.getMinBalance().stripTrailingZeros().toPlainString()).append("\n");
        // 📦 qoldiq
        sb.append("\n<b>📦 Qoldiq</b> (").append(metrics.lastDate(OmborMetrics.QOLDIQ)).append(") · ROP = buyurtma nuqtasi:\n");
        boolean any = false;
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            BigDecimal v = st.get(k.getId());
            if (v == null) continue;
            if (u.getRole() == Role.KASSIR && !k.getId().equals(u.getKassaId()) && !rec.notifier().headOf(u).contains(k.getId())) continue;
            any = true;
            sb.append(v.signum() < 0 ? "🔴 " : "• ").append(esc(k.getName())).append(": <b>").append(v.stripTrailingZeros().toPlainString()).append("</b> ").append(esc(t.getUom()));
            String rop = metricOf(OmborCalcService.BUYURTMA_NUQTA, k.getId(), msId), avg = metricOf(OmborCalcService.ORTACHA_90, k.getId(), msId), cov = metricOf(OmborCalcService.QOPLASH_KUN, k.getId(), msId);
            if (rop != null) sb.append(" · ROP ").append(rop);
            if (avg != null) sb.append(" · ").append(avg).append("/kun");
            if (cov != null) sb.append(" · ").append(cov).append(" kunga yetadi");
            sb.append("\n");
        }
        BigDecimal total = st.get(0L);
        if (total != null) sb.append("Σ Kompaniya: <b>").append(total.stripTrailingZeros().toPlainString()).append("</b>\n");
        else if (!any) sb.append("— qoldiq ma'lumoti yo'q\n");
        // ⚠️ kamchiliklar
        List<OmborKamchilik> issues = new ArrayList<>();
        for (OmborKamchilik k : repo.findByResolvedAtIsNullOrderBySinceDesc())
            if (k.getSubjectKey().contains(msId) && rec.canSee(u, k)) issues.add(k);
        Map<String, OmborQoida> rules = engine.rulesMap();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (!issues.isEmpty()) {
            sb.append("\n<b>⚠️ Ochiq kamchiliklar: ").append(issues.size()).append("</b> (bosib oching)\n");
            int n = 0;
            for (OmborKamchilik k : issues) {
                if (++n > 6) { sb.append("… yana ").append(issues.size() - 6).append(" ta\n"); break; }
                OmborQoida r = rules.get(k.getRuleCode());
                rows.add(irow(btn((r == null ? "•" : r.emoji()) + " " + cut(r == null ? k.getRuleCode() : r.getTitle(), 30) + (k.getKassaId() == null ? "" : " · " + rec.notifier().kassaName(k.getKassaId()).replace("Отдел ", "")), "om:v:" + k.getId())));
            }
        } else sb.append("\n✅ Bu tovar bo'yicha ochiq kamchilik yo'q\n");
        String msUrl = "https://online.moysklad.ru/app/#" + (t.getType().equals("variant") ? "variant" : t.getType().equals("bundle") ? "bundle" : "good") + "/edit?id=" + msId;
        rows.add(irow(uz.kassa.service.control.ControlNotifier.urlBtn("🔗 MoySklad'da ochish", msUrl), btn("💵 Narxlar", "om:nxl:" + msId)));
        rows.add(irow(btn("📝 So'rov berish", "om:srn"), btn("🔎 Qidirish", "om:s")));
        rows.add(irow(btn("⬅️ Ro'yxat", back(s)), btn("🏬 Омбор", "om:m")));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }

    private String metricOf(String code, long kassa, String pid) {
        for (Map<String, Object> m : metrics.latest(code, "kassa_id = ? AND product_ms_id = ?", kassa, pid))
            return ((BigDecimal) m.get("value")).stripTrailingZeros().toPlainString();
        return null;
    }


    /* ==================== 🔢 Санoq ==================== */

    private long sanoqOpenFor(AppUser u) {
        if (u.getRole() != Role.KASSIR) return sanoq.repo().countByStatusNotAndPlanDateLessThanEqual("TASDIQ", java.time.LocalDate.now(cfg.zone()));
        long n = u.getKassaId() == null ? 0 : sanoq.openCount(u.getKassaId());
        for (Long k : rec.notifier().headOf(u)) if (!k.equals(u.getKassaId())) n += sanoq.openCount(k);
        return n;
    }

    private boolean canCount(AppUser u, long kassa) {
        if (u.getRole() != Role.KASSIR) return true;
        return kassa == (u.getKassaId() == null ? -1 : u.getKassaId()) || rec.notifier().headOf(u).contains(kassa)
                || cfg.roleUsers("ZAVSKLAD", kassa).contains(u.getId());
    }

    /** arg: "" — do'kon tanlash (yoki kassirning o'zi) | "<kassa>[.<page>]" */
    private void sanoqList(AppUser u, Session s, String arg, long chatId, int msgId) {
        long kassa = 0; int page = 0;
        if (!arg.isBlank()) { String[] p = arg.split("\\."); try { kassa = Long.parseLong(p[0]); page = p.length > 1 ? Integer.parseInt(p[1]) : 0; } catch (NumberFormatException ignored) { } }
        if (kassa == 0 && u.getRole() == Role.KASSIR && u.getKassaId() != null) kassa = u.getKassaId();
        if (kassa == 0) {
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
                if (!k.isCashless() && k.getMoyskladWarehouseId() != null)
                    rows.add(irow(btn(k.getName() + " · " + sanoq.openCount(k.getId()) + " ta", "om:sn:" + k.getId())));
            rows.add(irow(btn("⬅️ Омбор", "om:m")));
            sender.edit(chatId, msgId, "🔢 <b>Rotatsion sanoq</b>\n\nA tovarlar har " + cfg.abcDays('A') + ", B har " + cfg.abcDays('B') + ", C har " + cfg.abcDays('C')
                    + " kunda bir sanaladi. Do'konni tanlang:", inline(rows));
            return;
        }
        if (!canCount(u, kassa)) { sender.edit(chatId, msgId, "⛔ Bu do'kon sanog'i sizga ochiq emas."); return; }
        List<OmborSanoq> list = sanoq.open(kassa);
        StringBuilder sb = new StringBuilder("🔢 <b>Sanoq</b> — " + esc(rec.notifier().kassaName(kassa)) + "\n");
        long entered = list.stream().filter(x -> x.getFactQty() != null).count();
        if (list.isEmpty()) sb.append("\nBugungi sanoq yo'q yoki hammasi tasdiqlangan ✅");
        else sb.append("Kutmoqda: <b>").append(list.size()).append("</b> ta · kiritilgan: ").append(entered)
                .append("\n\nTovarni bosing, fakt miqdorni yozing; hammasini kiritgach «✅ Tasdiqlash»:");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int from = page * PAGE;
        for (int i = from; i < Math.min(list.size(), from + PAGE); i++) {
            OmborSanoq x = list.get(i);
            String name = tovarRepo.findById(x.getProductMsId()).map(OmborTovar::getName).orElse(x.getProductMsId());
            String label = (x.getFactQty() == null ? "▫️ " : (x.diff() ? "⚠️ " : "✅ ")) + x.getAbc() + " · " + name
                    + (x.getFactQty() == null ? "" : " = " + x.getFactQty().stripTrailingZeros().toPlainString())
                    + (x.getPlanDate().isBefore(java.time.LocalDate.now(cfg.zone())) ? " · " + x.getPlanDate().getDayOfMonth() + "-kun" : "");
            rows.add(irow(btn(cut(label, 60), "om:snv:" + x.getId())));
        }
        List<InlineKeyboardButton> nav = new ArrayList<>();
        if (page > 0) nav.add(btn("⬅️ Oldingi", "om:sn:" + kassa + "." + (page - 1)));
        if (from + PAGE < list.size()) nav.add(btn("Keyingi ➡️", "om:sn:" + kassa + "." + (page + 1)));
        if (!nav.isEmpty()) rows.add(nav);
        if (entered > 0) rows.add(irow(btn("✅ Tasdiqlash (" + entered + ")", "om:snc:" + kassa)));
        rows.add(irow(btn("⬅️ Омбор", "om:m")));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }

    private void sanoqAsk(AppUser u, Session s, long id, long chatId, int msgId) {
        OmborSanoq x = sanoq.repo().findById(id).orElse(null);
        if (x == null || !canCount(u, x.getKassaId())) { sender.edit(chatId, msgId, "⚠️ Topilmadi."); return; }
        OmborTovar t = tovarRepo.findById(x.getProductMsId()).orElse(null);
        s.state = Session.State.OM_SANOQ_QTY;
        s.data.put("snId", id);
        s.data.put("snMsg", msgId);
        sender.edit(chatId, msgId, "🔢 <b>" + esc(t == null ? x.getProductMsId() : t.getName()) + "</b>"
                + (t == null || t.getArticle().isBlank() ? "" : " · art. " + esc(t.getArticle()))
                + "\nHisobda: <b>" + x.getSystemQty().stripTrailingZeros().toPlainString() + "</b> " + (t == null ? "" : esc(t.getUom()))
                + "\n\n✍️ Haqiqiy (sanalgan) miqdorni yozing:", inline(List.of(irow(btn("⬅️ Ro'yxat", "om:sn:" + x.getKassaId())))));
    }

    private void sanoqQty(AppUser u, Session s, String text, long chatId) {
        long id = s.getLong("snId");
        s.reset();
        BigDecimal v;
        try { v = new BigDecimal(text.trim().replace(',', '.').replace(" ", "")); if (v.signum() < 0) throw new NumberFormatException(); }
        catch (Exception e) { sender.send(chatId, "⚠️ Miqdor noto'g'ri: " + esc(text) + ". Qaytadan 🔢 Санoq dan tanlang."); return; }
        OmborSanoq x = sanoq.enterFact(id, v, u);
        if (x == null) { sender.send(chatId, "⚠️ Topilmadi."); return; }
        String name = tovarRepo.findById(x.getProductMsId()).map(OmborTovar::getName).orElse(x.getProductMsId());
        Integer sent = sender.sendId(chatId, (x.diff() ? "⚠️ " : "✅ ") + esc(name) + ": fakt <b>" + v.stripTrailingZeros().toPlainString() + "</b>"
                + (x.diff() ? " · hisob " + x.getSystemQty().stripTrailingZeros().toPlainString() + " · farq <b>" + v.subtract(x.getSystemQty()).stripTrailingZeros().toPlainString() + "</b>" : ""),
                null);
        if (sent != null) sanoqList(u, s, String.valueOf(x.getKassaId()), chatId, sent);
    }

    private void sanoqConfirm(AppUser u, Session s, long kassa, long chatId, int msgId) {
        if (!canCount(u, kassa)) { sender.edit(chatId, msgId, "⛔ Ruxsat yo'q."); return; }
        int n = sanoq.confirm(kassa, u);
        sender.send(chatId, "✅ Sanoq tasdiqlandi: <b>" + n + "</b> ta tovar — " + esc(rec.notifier().kassaName(kassa)) + ". Farqlar rahbarga alohida chiqadi.");
        sanoqList(u, s, String.valueOf(kassa), chatId, msgId);
    }


    /* ==================== 🔄 / 📊 ==================== */

    private void refresh(AppUser u, long chatId, int msgId) {
        if (u.getRole() == Role.KASSIR) return;
        sender.edit(chatId, msgId, "⏳ MoySklad'dan omborlar, tovarlar, hujjatlar, qoldiqlar o'qilmoqda; hisoblar va qoidalar… (1–5 daqiqa)");
        new Thread(() -> {
            try {
                String r = sync.syncAll();
                sales.refreshRecent();
                calc.nightly();
                sanoq.planToday();
                int[] c = engine.tick();
                sender.send(chatId, "✅ <b>Ombor yangilandi</b>\n" + r + "⚠️ Qoidalar: yangi " + c[0] + " · yopildi " + c[1], mainKb(u));
            } catch (Exception e) {
                sender.send(chatId, "⚠️ Yangilash xatosi: " + esc(String.valueOf(e.getMessage())));
            }
        }, "ombor-refresh").start();
    }

    private void status(AppUser u, long chatId, int msgId) {
        ZoneId z = cfg.zone();
        Map<String, OmborSinxron> st = new HashMap<>();
        for (OmborSinxron x : sync.status()) st.put(x.getEntity(), x);
        java.time.Instant lastOk = null; List<String> errors = new ArrayList<>(); List<String> changed = new ArrayList<>();
        for (OmborSinxron x : st.values()) {
            if (x.getLastError() != null) errors.add(entityTitle(x.getEntity()) + ": " + cut(x.getLastError(), 80));
            if (x.getLastOkAt() != null && (lastOk == null || x.getLastOkAt().isAfter(lastOk)) && !x.getEntity().equals(OmborSyncService.E_QOLDIQ)) lastOk = x.getLastOkAt();
            if (OmborDocSync.TYPES.contains(x.getEntity()) && x.getRowsN() > 0) changed.add(OmborHujjat.typeTitle(x.getEntity()) + " " + x.getRowsN());
        }
        StringBuilder sb = new StringBuilder("📊 <b>Ombor holati</b>\n\n");
        sb.append(errors.isEmpty() ? "🟢 Sinxron ishlayapti" : "🔴 Sinxronda xato bor").append(lastOk == null ? "" : " · oxirgi " + lastOk.atZone(z).format(DTF)).append("\n");
        for (String e : errors) sb.append("   ⚠️ ").append(esc(e)).append("\n");
        sb.append("🏪 Omborlar: <b>").append(st.containsKey(OmborSyncService.E_STORE) ? st.get(OmborSyncService.E_STORE).getRowsN() : 0).append("</b>")
          .append(" · 🗂 Tovarlar: <b>").append(tovarRepo.countByArchivedFalse()).append("</b> faol\n");
        OmborSinxron stock = st.get(OmborSyncService.E_QOLDIQ);
        sb.append("📦 Qoldiqlar: ").append(stock == null || stock.getLastOkAt() == null ? "hali o'qilmagan" : "<b>" + stock.getRowsN() + "</b> qator · " + stock.getLastOkAt().atZone(z).format(DTF)).append(" (kuniga bir, ").append(cfg.stockTime()).append(" dan keyin)\n");
        long docs = 0; List<String> byType = new ArrayList<>();
        for (String t : OmborDocSync.TYPES) { long c = hujjatRepo.countByType(t); docs += c; if (c > 0) byType.add(OmborHujjat.typeTitle(t) + " " + c); }
        sb.append("📄 Hujjatlar (oxirgi ").append(cfg.docsDays()).append(" kun): <b>").append(docs).append("</b> — ").append(esc(String.join(", ", byType))).append("\n");
        sb.append("   oxirgi sinxronda o'zgargan: ").append(changed.isEmpty() ? "yo'q" : esc(String.join(", ", changed))).append("\n");
        sb.append("📈 Sotuv tarixi: ").append(sales.backfillDone() ? "to'liq (" + cfg.salesDays() + " kun)" : "yuklanmoqda, " + cfg.get(OmborConfig.SALES_CURSOR).orElse("boshlanmagan") + " gacha").append("\n");
        var d = metrics.lastDate(OmborCalcService.ABC);
        sb.append("🧮 Hisoblar (ABC, buyurtma nuqtasi, fill rate): ").append(d == null ? "hali yo'q" : d + " holatiga").append("\n");
        sb.append("\n<b>Do'konlar</b>\n");
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;
            long[] ss = metrics.stockStats(k.getId());
            String fr = metricOf(OmborCalcService.FILL_RATE_30, k.getId(), "");
            sb.append(k.getMoyskladWarehouseId() == null ? "⚪ " : "🏪 ").append(esc(k.getName())).append(": ")
              .append(k.getMoyskladWarehouseId() == null ? "MoySklad ombori bog'lanmagan" : ss[0] + " tovar" + (ss[1] > 0 ? " · 🔴 manfiy " + ss[1] : "") + (fr == null ? "" : " · fill " + fr + "%"))
              .append(" · sanoq kutmoqda ").append(sanoq.openCount(k.getId())).append("\n");
        }
        Map<String, OmborQoida> rules = engine.rulesMap();
        long openAll = 0, silent = 0;
        StringBuilder rs = new StringBuilder();
        for (OmborQoida r : rules.values().stream().sorted(Comparator.comparingInt(OmborQoida::getSort)).toList()) {
            int n = engine.openByRule(r.getCode());
            if (!r.isEnabled()) { rs.append("⚪ ").append(esc(r.getTitle())).append(" — o'chirilgan\n"); continue; }
            if (n == 0) continue;
            openAll += n; if (r.silent()) silent += n;
            rs.append(r.emoji()).append(" ").append(esc(r.getTitle())).append(": <b>").append(n).append("</b>\n");
        }
        sb.append("\n<b>Ochiq kamchiliklar: ").append(openAll).append("</b>").append(silent > 0 ? " (shundan " + silent + " ta ℹ️ info — xabarsiz, faqat ro'yxatda)" : "").append("\n").append(rs);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("⚠️ Камчиликлар", "om:l"), btn("🏬 Омбор", "om:m")));
        if (u.getRole() != Role.KASSIR) rows.add(irow(btn("🔄 Yangilash (MoySklad)", "om:rf")));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }

    private static String entityTitle(String e) {
        return switch (e) { case "store" -> "omborlar"; case "assortment" -> "tovarlar"; case "stock" -> "qoldiqlar"; default -> OmborHujjat.typeTitle(e); };
    }

    private static String cut(String s, int n) { return s.length() > n ? s.substring(0, n - 1) + "…" : s; }
}
