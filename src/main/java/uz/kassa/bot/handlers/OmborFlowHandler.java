package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.Sender;
import uz.kassa.bot.Session;
import uz.kassa.domain.*;
import uz.kassa.repo.KassaRepo;
import uz.kassa.repo.OmborTovarRepo;
import uz.kassa.repo.OmborYetkazuvchiRepo;
import uz.kassa.service.ombor.*;
import uz.kassa.service.ombor.checks.KorsatkichChegaraChecker;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.*;

/**
 * 🏬 Омбор — jarayonlar (B3–B5): 📝 Сўров (do'kon so'rovi), 🧾 Қоралама (tasdiq zanjiri), 🤝 Ҳамкорлар, 💵 Нарх (zakupshik).
 * Callback: om:sr* (so'rov), om:qr* (qoralama), om:hk* (hamkor), om:nx* (narx). OmborHandler orqali chaqiriladi.
 */
@Component
@RequiredArgsConstructor
public class OmborFlowHandler {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final List<String> REASONS = List.of("YOQ", "KAM", "MIJOZ", "YANGI", "HAMKOR", "LOT");

    private final Sender sender;
    private final OmborSorovService sorov;
    private final OmborDraftService draft;
    private final OmborNarxService narx;
    private final OmborRecipients rec;
    private final OmborMetrics metrics;
    private final OmborConfig cfg;
    private final OmborTovarRepo tovarRepo;
    private final OmborYetkazuvchiRepo supRepo;
    private final KassaRepo kassaRepo;

    boolean isZakupshik(AppUser u) { return u.getRole() != Role.KASSIR || cfg.hasRole(u.getId(), "ZAKUPSHIK"); }
    boolean isZavsklad(AppUser u) { return u.getRole() != Role.KASSIR || cfg.hasRole(u.getId(), "ZAVSKLAD"); }
    boolean isDirektor(AppUser u) { return u.getRole() == Role.SUPERADMIN || cfg.hasRole(u.getId(), "DIREKTOR"); }


    /* ============================ MATN (holatlar) ============================ */

    public boolean onText(AppUser u, Session s, String text, long chatId) {
        switch (s.state) {
            case OM_SOROV_PROD -> { sorovProduct(u, s, text, chatId); return true; }
            case OM_SOROV_NEW -> { s.data.put("srText", text.trim()); s.data.remove("srPid"); s.state = Session.State.OM_SOROV_QTY;
                sender.send(chatId, "🔢 Necha dona kerak?"); return true; }
            case OM_SOROV_QTY -> { sorovQty(u, s, text, chatId); return true; }
            case OM_SOROV_ANS -> { long id = s.getLong("srId"); String st = s.getStr("srSt"); s.reset(); sorov.answer(id, u, st, text.trim());
                sender.send(chatId, "✅ So'rov #" + id + " — " + OmborSorov.statusTitle(st) + ": " + esc(text.trim())); sorovList(u, "", chatId, 0); return true; }
            case OM_QR_QTY -> { long lid = s.getLong("qrLine"); long qid = s.getLong("qrId"); s.reset();
                try { BigDecimal v = new BigDecimal(text.trim().replace(',', '.').replace(" ", "")); if (v.signum() <= 0) throw new NumberFormatException();
                    draft.setQty(lid, v, u); sender.send(chatId, "✅ Miqdor o'zgartirildi: " + v.stripTrailingZeros().toPlainString()); }
                catch (Exception e) { sender.send(chatId, "⚠️ Miqdor noto'g'ri."); }
                qrCard(u, qid, chatId, 0); return true; }
            case OM_NARX -> { s.reset();
                try { OmborNarx n = narx.addManual(text, u); sender.send(chatId, "✅ Narx saqlandi: " + fmtTiyin(n.getPrice()) + " so'm" + (n.getLeadDays() == null ? "" : " · muddat " + n.getLeadDays() + " kun")); }
                catch (Exception e) { sender.send(chatId, "⚠️ " + esc(String.valueOf(e.getMessage())) + "\nFormat: <code>Yetkazuvchi nomi; artikul; 120000; 3; izoh</code>"); }
                return true; }
            default -> { return false; }
        }
    }


    /* ============================ CALLBACK ============================ */

    public boolean onCallback(AppUser u, Session s, String cmd, String arg, long chatId, int msgId) {
        switch (cmd) {
            case "sr" -> sorovList(u, arg, chatId, msgId);
            case "srn" -> { s.reset(); s.state = Session.State.OM_SOROV_PROD; s.data.put("srKassa", pickKassa(u, arg));
                sender.edit(chatId, msgId, "📝 <b>So'rov</b> — " + esc(rec.notifier().kassaName(s.getLong("srKassa"))) + "\n\n🔎 Tovar nomi/artikulini yozing (yangi tovar bo'lsa oldiga «+»: <code>+ Kamera 4MP PoE</code>):",
                        inline(List.of(irow(btn("⬅️ Orqaga", "om:sr"))))); }
            case "srp" -> { s.data.put("srPid", arg); s.data.remove("srText"); s.state = Session.State.OM_SOROV_QTY; sender.edit(chatId, msgId, "🔢 Necha dona kerak?"); }
            case "srr" -> sorovSave(u, s, arg, chatId, msgId);
            case "srv" -> sorovCard(u, Long.parseLong(arg), chatId, msgId);
            case "sra" -> { int dot = arg.indexOf('.'); long id = Long.parseLong(arg.substring(0, dot)); String st = arg.substring(dot + 1);
                if (!isZakupshik(u)) { sender.answerAlert("", ""); return true; }
                if (st.equals("RAD")) { s.state = Session.State.OM_SOROV_ANS; s.data.put("srId", id); s.data.put("srSt", st); sender.edit(chatId, msgId, "✍️ Rad sababi:"); }
                else { sorov.answer(id, u, st, null); sorovCard(u, id, chatId, msgId); } }
            case "qr" -> qrList(u, arg, chatId, msgId);
            case "qrv" -> qrCard(u, Long.parseLong(arg), chatId, msgId);
            case "qra" -> qrAdvance(u, Long.parseLong(arg), chatId, msgId);
            case "qrx" -> { if (isZakupshik(u)) { draft.cancel(Long.parseLong(arg), u, "bot orqali bekor"); } qrCard(u, Long.parseLong(arg), chatId, msgId); }
            case "qrq" -> { String[] p = arg.split("\\."); s.state = Session.State.OM_QR_QTY; s.data.put("qrId", Long.parseLong(p[0])); s.data.put("qrLine", Long.parseLong(p[1]));
                sender.edit(chatId, msgId, "✍️ Yangi miqdorni yozing:", inline(List.of(irow(btn("⬅️ Orqaga", "om:qrv:" + p[0]))))); }
            case "qrb" -> { if (!isZakupshik(u)) return true; sender.edit(chatId, msgId, "⏳ Qoralama tuzilmoqda…"); int n = draft.buildAll(); sender.send(chatId, "🧾 Qoralama tuzildi: " + n + " ta"); qrList(u, "", chatId, 0); }
            case "hk" -> hamkorList(u, chatId, msgId);
            case "nx" -> { if (!isZakupshik(u)) return true; s.state = Session.State.OM_NARX;
                sender.edit(chatId, msgId, "💵 <b>Yetkazuvchi narxi</b> — bir qatorda:\n<code>Yetkazuvchi nomi; tovar artikuli; narx so'm; muddat kun; izoh</code>\nMasalan: <code>Ортик ака; AK-NN-06165; 95000; 2</code>\n\n"
                        + "Priyomkalardagi haqiqiy narxlar avtomatik yoziladi; bu — taklif narxi.", inline(List.of(irow(btn("⬅️ Orqaga", "om:m"))))); }
            case "nxl" -> narxList(u, arg, chatId, msgId);
            default -> { return false; }
        }
        return true;
    }

    private long pickKassa(AppUser u, String arg) {
        if (!arg.isBlank()) try { return Long.parseLong(arg); } catch (NumberFormatException ignored) { }
        if (u.getKassaId() != null) return u.getKassaId();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) if (!k.isCashless()) return k.getId();
        return 0;
    }


    /* ==================== 📝 Сўров ==================== */

    private void sorovList(AppUser u, String arg, long chatId, int msgId) {
        List<OmborSorov> list = isZakupshik(u) ? sorov.open() : sorov.repo().findByByUserIdOrderByCreatedAtDesc(u.getId());
        StringBuilder sb = new StringBuilder("📝 <b>Do'kon so'rovlari</b>\n");
        sb.append(isZakupshik(u) ? "Ochiq: <b>" + list.size() + "</b> ta\n" : "Mening so'rovlarim: " + list.size() + "\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int n = 0;
        for (OmborSorov x : list) {
            if (++n > 15) break;
            String what = x.getProductMsId() != null ? tovarRepo.findById(x.getProductMsId()).map(OmborTovar::getName).orElse(x.getProductMsId()) : "🆕 " + x.getText();
            rows.add(irow(btn(cut(OmborSorov.statusTitle(x.getStatus()).substring(0, 2) + " " + what + " ×" + x.getQty().stripTrailingZeros().toPlainString()
                    + (x.getKassaId() == null ? "" : " · " + rec.notifier().kassaName(x.getKassaId()).replace("Отдел ", "")), 60), "om:srv:" + x.getId())));
        }
        List<InlineKeyboardButton> kr = new ArrayList<>();
        if (u.getRole() == Role.KASSIR) kr.add(btn("➕ Yangi so'rov", "om:srn"));
        else for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) if (!k.isCashless()) { kr.add(btn("➕ " + cut(k.getName().replace("Отдел ", ""), 12), "om:srn:" + k.getId())); if (kr.size() == 3) { rows.add(kr); kr = new ArrayList<>(); } }
        if (!kr.isEmpty()) rows.add(kr);
        rows.add(irow(btn("⬅️ Омбор", "om:m")));
        sb.append("\nSabab kodlari: tovar yo'q · kam · mijoz · yangi tovar · hamkor · tender loti.");
        if (msgId > 0) sender.edit(chatId, msgId, sb.toString(), inline(rows)); else sender.send(chatId, sb.toString(), inline(rows));
    }

    private void sorovProduct(AppUser u, Session s, String text, long chatId) {
        String t = text.trim();
        if (t.startsWith("+")) { s.data.put("srText", t.substring(1).trim()); s.data.remove("srPid"); s.state = Session.State.OM_SOROV_QTY; sender.send(chatId, "🆕 Yangi tovar: " + esc(s.getStr("srText")) + "\n🔢 Necha dona kerak?"); return; }
        List<OmborTovar> found = tovarRepo.search(OmborTovar.norm(t));
        if (found.isEmpty()) { sender.send(chatId, "Topilmadi. Yana yozing yoki yangi tovar uchun oldiga «+» qo'ying."); return; }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int n = 0;
        for (OmborTovar x : found) { if (++n > 8) break; rows.add(irow(btn(cut(x.getName(), 60), "om:srp:" + x.getMsId()))); }
        rows.add(irow(btn("🆕 Yangi tovar sifatida", "om:srn"), btn("❌ Bekor", "om:sr")));
        s.state = Session.State.OM_SOROV_PROD;
        sender.send(chatId, "Tovarni tanlang:", inline(rows));
    }

    private void sorovQty(AppUser u, Session s, String text, long chatId) {
        BigDecimal v;
        try { v = new BigDecimal(text.trim().replace(',', '.').replace(" ", "")); if (v.signum() <= 0) throw new NumberFormatException(); }
        catch (Exception e) { sender.send(chatId, "⚠️ Son yozing."); return; }
        s.data.put("srQty", v);
        s.state = Session.State.IDLE;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String r : REASONS) rows.add(irow(btn(OmborSorov.reasonTitle(r), "om:srr:" + r)));
        sender.send(chatId, "Sabab kodi:", inline(rows));
    }

    private void sorovSave(AppUser u, Session s, String reason, long chatId, int msgId) {
        Object q = s.data.get("srQty");
        Long kassa = s.data.get("srKassa") == null ? u.getKassaId() : s.getLong("srKassa");
        String pid = s.getStr("srPid"), txt = s.getStr("srText");
        s.reset();
        if (q == null || (pid == null && txt == null)) { sender.edit(chatId, msgId, "⚠️ So'rov ma'lumoti yo'qoldi, qaytadan boshlang."); return; }
        OmborSorov x = sorov.create(u, kassa, pid, txt, (BigDecimal) q, reason);
        sender.edit(chatId, msgId, "✅ So'rov #" + x.getId() + " yuborildi — zakupshik ko'radi.");
        sorovList(u, "", chatId, 0);
    }

    private void sorovCard(AppUser u, long id, long chatId, int msgId) {
        OmborSorov x = sorov.repo().findById(id).orElse(null);
        if (x == null) { sender.edit(chatId, msgId, "⚠️ Topilmadi."); return; }
        String what = x.getProductMsId() != null ? tovarRepo.findById(x.getProductMsId()).map(OmborTovar::getName).orElse(x.getProductMsId()) : "🆕 " + x.getText();
        StringBuilder sb = new StringBuilder("📝 <b>So'rov #" + id + "</b> — " + OmborSorov.statusTitle(x.getStatus()) + "\n\n<b>" + esc(what) + "</b> × " + x.getQty().stripTrailingZeros().toPlainString() + "\n");
        sb.append(OmborSorov.reasonTitle(x.getReason())).append("\n");
        if (x.getKassaId() != null) sb.append("🏪 ").append(esc(rec.notifier().kassaName(x.getKassaId()))).append("\n");
        sb.append("👤 ").append(esc(rec.notifier().userName(x.getByUserId()))).append(" · ").append(x.getCreatedAt().atZone(cfg.zone()).format(DTF)).append("\n");
        if (x.getProductMsId() != null) {
            BigDecimal total = metrics.stockOf(x.getProductMsId()).getOrDefault(0L, BigDecimal.ZERO);
            sb.append("📦 Kompaniya qoldig'i: ").append(total.stripTrailingZeros().toPlainString()).append("\n");
        }
        if (!x.getAnswer().isBlank()) sb.append("💬 ").append(esc(x.getAnswer())).append("\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (isZakupshik(u) && (x.getStatus().equals("YANGI") || x.getStatus().equals("KORILDI")))
            rows.add(irow(btn("👀 Ko'rildi", "om:sra:" + id + ".KORILDI"), btn("🧾 Qoralamaga", "om:sra:" + id + ".QORALAMADA"), btn("❌ Rad", "om:sra:" + id + ".RAD")));
        if (x.getProductMsId() != null) rows.add(irow(btn("📦 Qoldiqlar", "om:t:" + x.getProductMsId())));
        rows.add(irow(btn("⬅️ So'rovlar", "om:sr"), btn("🏬 Омбор", "om:m")));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }


    /* ==================== 🧾 Қоралама ==================== */

    private void qrList(AppUser u, String arg, long chatId, int msgId) {
        boolean all = arg.equals("all");
        List<OmborQoralama> list = all ? draft.repo().findTop30ByOrderByUpdatedAtDesc()
                : draft.repo().findByStatusInOrderByUpdatedAtDesc(List.of("QORALAMA", "ZAKUPSHIK", "ZAVSKLAD", "DIREKTOR", "TASDIQ"));
        if (u.getRole() == Role.KASSIR && !isZakupshik(u) && !isZavsklad(u) && !isDirektor(u)) list = list.stream().filter(q -> Objects.equals(q.getKassaId(), u.getKassaId())).toList();
        StringBuilder sb = new StringBuilder("🧾 <b>Buyurtma qoralamalari</b>" + (all ? " (oxirgi 30)" : " — ochiq") + "\n");
        sb.append("Zanjir: zakupshik (narx/yetkazuvchi) → zavsklad (sanoq) → direktor (").append(fmt(cfg.kattaSumma())).append(" so'mdan katta) → yuborish.\n");
        sb.append("💰 Mavjud pul: <b>").append(fmt(draft.cashAvailableSom())).append("</b> so'm\n\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int n = 0;
        for (OmborQoralama q : list) {
            if (++n > 15) break;
            rows.add(irow(btn(cut(OmborQoralama.statusTitle(q.getStatus()).substring(0, 2) + " #" + q.getId() + " " + rec.notifier().kassaName(q.getKassaId()).replace("Отдел ", "") + " · " + q.getAgentName() + " · " + fmtTiyin(q.getTotal()), 60), "om:qrv:" + q.getId())));
        }
        if (list.isEmpty()) sb.append("Ochiq qoralama yo'q.\n");
        List<InlineKeyboardButton> r = new ArrayList<>();
        if (isZakupshik(u)) r.add(btn("🧮 Qayta tuzish", "om:qrb"));
        r.add(btn(all ? "Ochiqlar" : "Hammasi", all ? "om:qr" : "om:qr:all"));
        rows.add(r);
        rows.add(irow(btn("⬅️ Омбор", "om:m")));
        if (msgId > 0) sender.edit(chatId, msgId, sb.toString(), inline(rows)); else sender.send(chatId, sb.toString(), inline(rows));
    }

    private void qrCard(AppUser u, long id, long chatId, int msgId) {
        OmborQoralama q = draft.repo().findById(id).orElse(null);
        if (q == null) { sender.edit(chatId, msgId, "⚠️ Topilmadi."); return; }
        List<OmborQoralamaQator> lines = draft.lines().findByQoralamaIdOrderByIdAsc(id);
        StringBuilder sb = new StringBuilder("🧾 <b>Qoralama #" + id + "</b> — " + OmborQoralama.statusTitle(q.getStatus()) + "\n");
        sb.append("🏪 ").append(esc(rec.notifier().kassaName(q.getKassaId()))).append(" · 🚚 ").append(esc(q.getAgentName())).append("\n");
        sb.append("💵 Jami (landed): <b>").append(fmtTiyin(q.getTotal())).append("</b> so'm · mavjud pul ").append(fmt(q.getCashAvailable())).append(" so'm");
        if (draft.needsDirector(q)) sb.append(" · 👔 direktor tasdig'i kerak");
        sb.append("\n");
        if (!q.getNote().isBlank()) sb.append(esc(q.getNote())).append("\n");
        sb.append("\n");
        int n = 0;
        for (OmborQoralamaQator l : lines) {
            if (++n > 20) { sb.append("… yana ").append(lines.size() - 20).append(" qator\n"); break; }
            String name = tovarRepo.findById(l.getProductMsId()).map(OmborTovar::getName).orElse(l.getProductMsId());
            sb.append(n).append(". <b>").append(esc(name)).append("</b> × <b>").append(l.getQty().stripTrailingZeros().toPlainString()).append("</b>");
            if (l.getLandedPrice() > 0) sb.append(" · ").append(fmtTiyin(l.getLandedPrice())).append(" = ").append(fmtTiyin(l.lineTotal()));
            if (!l.getFlags().isBlank()) sb.append(" ").append(flagsText(l.getFlags()));
            sb.append("\n   <i>").append(esc(l.getBasis())).append("</i>\n");
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (q.open()) {
            String next = draft.nextRole(q);
            boolean can = next != null && switch (next) { case "ZAKUPSHIK" -> isZakupshik(u); case "ZAVSKLAD" -> isZavsklad(u); case "DIREKTOR" -> isDirektor(u); default -> false; };
            if (can) rows.add(irow(btn(switch (q.getStatus()) { case "QORALAMA" -> "✅ Narx/yetkazuvchi to'g'ri"; case "ZAKUPSHIK" -> "✅ Sanoq tasdiq (zavsklad)"; case "ZAVSKLAD" -> "✅ Direktor tasdig'i"; case "TASDIQ" -> "📤 Yetkazuvchiga yuborildi"; default -> "✅"; }, "om:qra:" + id)));
            else if (next != null) sb.append("\n⏳ Navbat: ").append(OmborConfig.roleTitle(next));
            if (isZakupshik(u) && (q.getStatus().equals("QORALAMA") || q.getStatus().equals("ZAKUPSHIK"))) {
                List<InlineKeyboardButton> r = new ArrayList<>();
                int i = 0;
                for (OmborQoralamaQator l : lines) { if (++i > 6) break; r.add(btn("✏️ " + i, "om:qrq:" + id + "." + l.getId())); if (r.size() == 3) { rows.add(r); r = new ArrayList<>(); } }
                if (!r.isEmpty()) rows.add(r);
            }
            if (isZakupshik(u)) rows.add(irow(btn("❌ Bekor qilish", "om:qrx:" + id)));
        }
        rows.add(irow(btn("⬅️ Qoralamalar", "om:qr"), btn("🏬 Омбор", "om:m")));
        if (msgId > 0) sender.edit(chatId, msgId, sb.toString(), inline(rows)); else sender.send(chatId, sb.toString(), inline(rows));
    }

    private void qrAdvance(AppUser u, long id, long chatId, int msgId) {
        OmborQoralama q = draft.repo().findById(id).orElse(null);
        if (q == null) return;
        String next = draft.nextRole(q);
        String role = next == null ? null : switch (next) { case "ZAKUPSHIK" -> isZakupshik(u) ? "ZAKUPSHIK" : null; case "ZAVSKLAD" -> isZavsklad(u) ? "ZAVSKLAD" : null; case "DIREKTOR" -> isDirektor(u) ? "DIREKTOR" : null; default -> null; };
        if (role == null) { sender.edit(chatId, msgId, "⛔ Bu bosqichni tasdiqlash sizga tegishli emas."); return; }
        String st = draft.advance(id, u, role);
        if (st != null) {
            // keyingi tasdiqlovchiga xabar
            OmborQoralama q2 = draft.repo().findById(id).orElse(q);
            String nr = draft.nextRole(q2);
            if (nr != null && q2.open()) {
                Set<AppUser> to = new LinkedHashSet<>();
                for (AppUser x : usersOf(nr, q2.getKassaId())) to.add(x);
                if (to.isEmpty()) to.addAll(rec.notifier().superadmins());
                rec.notifier().send(to, "🧾 <b>Qoralama #" + id + "</b> sizning tasdig'ingizni kutmoqda — " + esc(OmborConfig.roleTitle(nr)) + "\n🏪 "
                        + esc(rec.notifier().kassaName(q2.getKassaId())) + " · " + esc(q2.getAgentName()) + " · " + fmtTiyin(q2.getTotal()) + " so'm",
                        inline(List.of(irow(btn("🧾 Ochish", "om:qrv:" + id)))));
            }
        }
        qrCard(u, id, chatId, msgId);
    }

    private Set<AppUser> usersOf(String role, Long kassa) {
        Set<AppUser> out = new LinkedHashSet<>();
        for (Long uid : cfg.roleUsers(role, kassa)) rec.userRepo().findById(uid).filter(AppUser::isActive).ifPresent(out::add);
        return out;
    }

    private static String flagsText(String flags) {
        StringBuilder sb = new StringBuilder();
        for (String f : flags.split(",")) switch (f) {
            case "EHTIYOT" -> sb.append("⚠️ehtiyot ");
            case "NARX_OSHDI" -> sb.append("📈narx↑ ");
            case "NARX_TUSHDI" -> sb.append("📉narx↓ ");
            case "SOROV" -> sb.append("📝so'rov ");
            case "YETKAZUVCHI_YOQ" -> sb.append("❓yetkazuvchi yo'q ");
            default -> { }
        }
        return sb.toString().trim();
    }


    /* ==================== 🤝 Ҳамкорлар ==================== */

    private void hamkorList(AppUser u, long chatId, int msgId) {
        StringBuilder sb = new StringBuilder("🤝 <b>Hamkor do'konlar</b> (teg: " + (cfg.hamkorTag().isBlank() ? "sozlanmagan" : esc(cfg.hamkorTag())) + ")\n\n");
        List<Map<String, Object>> debts = metrics.latest("HAMKOR_QARZ", "kassa_id = 0");
        if (debts.isEmpty()) sb.append("Ma'lumot yo'q — ⚙️ 🏬 Омбор назорати → 🤝 Hamkor tegi ni sozlang, tunda yig'iladi.\n");
        long total = 0; int n = 0;
        debts.sort((a, b) -> ((BigDecimal) b.get("value")).compareTo((BigDecimal) a.get("value")));
        for (Map<String, Object> m : debts) {
            long v = ((BigDecimal) m.get("value")).longValue();
            if (v > 0) total += v;
            if (++n > 20) continue;
            String id = (String) m.get("product_ms_id");
            sb.append(v > 0 ? "🔴 " : "• ").append(esc(KorsatkichChegaraChecker.hamkorNames.getOrDefault(id, id))).append(": <b>").append(fmt(v)).append("</b> so'm\n");
        }
        if (!debts.isEmpty()) sb.append("Σ qarz: <b>").append(fmt(total)).append("</b> so'm\n");
        List<Map<String, Object>> iv = metrics.latest("HAMKOR_INTERVAL", "kassa_id = 0");
        if (!iv.isEmpty()) {
            sb.append("\n<b>Tugagan bo'lishi mumkin</b> (xarid intervali o'tgan):\n");
            n = 0;
            for (Map<String, Object> m : iv) {
                if (++n > 15) { sb.append("… yana ").append(iv.size() - 15).append("\n"); break; }
                String key = (String) m.get("product_ms_id");
                String agent = key.substring(0, key.indexOf('|')), pid = key.substring(key.indexOf('|') + 1);
                sb.append("• ").append(esc(KorsatkichChegaraChecker.hamkorNames.getOrDefault(agent, agent))).append(" — ")
                  .append(esc(tovarRepo.findById(pid).map(OmborTovar::getName).orElse(pid))).append(" (").append(((BigDecimal) m.get("value")).intValue()).append(" kun)\n");
            }
        }
        sender.edit(chatId, msgId, sb.toString(), inline(List.of(irow(btn("📝 Hamkor so'rovi", "om:srn"), btn("🏬 Омбор", "om:m")))));
    }


    /* ==================== 💵 Нарх ro'yxati ==================== */

    private void narxList(AppUser u, String pid, long chatId, int msgId) {
        OmborTovar t = tovarRepo.findById(pid).orElse(null);
        StringBuilder sb = new StringBuilder("💵 <b>" + esc(t == null ? pid : t.getName()) + "</b> — yetkazuvchi narxlari\n\n");
        Map<String, OmborNarx> last = narx.lastPrices(pid);
        if (last.isEmpty()) sb.append("Narx yo'q. Zakupshik 💵 Нарх киритиш orqali qo'shadi; priyomkalardan avtomatik keladi.\n");
        for (var e : last.entrySet()) {
            OmborYetkazuvchi s = supRepo.findById(e.getKey()).orElse(null);
            OmborNarx[] two = narx.lastTwo(e.getKey(), pid);
            sb.append("• <b>").append(esc(s == null ? e.getKey() : s.getName())).append("</b>: ").append(fmtTiyin(e.getValue().getPrice())).append(" (").append(e.getValue().getAtDate()).append(", ").append(e.getValue().getSource().toLowerCase()).append(")");
            if (two[1] != null) sb.append(" · oldingi ").append(fmtTiyin(two[1].getPrice()));
            if (s != null) sb.append(" · muddat ").append(s.getLeadDaysAm()).append("/").append(s.getLeadDaysPm()).append(" kun").append(s.trusted() ? "" : " · ⚠️ yangi manba");
            sb.append("\n");
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (isZakupshik(u)) rows.add(irow(btn("💵 Narx kiritish", "om:nx")));
        rows.add(irow(btn("📦 Qoldiqlar", "om:t:" + pid), btn("🏬 Омбор", "om:m")));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }

    private static String cut(String s, int n) { return s.length() > n ? s.substring(0, n - 1) + "…" : s; }
}
