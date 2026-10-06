package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.Sender;
import uz.kassa.bot.Session;
import uz.kassa.domain.AdeskLink;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Role;
import uz.kassa.repo.AdeskLinkRepo;
import uz.kassa.service.AuditService;
import uz.kassa.service.adesk.AdeskAccountMapper;
import uz.kassa.service.adesk.AdeskConfig;
import uz.kassa.service.adesk.AdeskClient;
import uz.kassa.service.adesk.AdeskHttp;
import uz.kassa.service.adesk.AdeskMsReader;
import uz.kassa.service.adesk.AdeskRunner;
import uz.kassa.service.adesk.AdeskSyncService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.esc;
import static uz.kassa.bot.TextUtil.fmt;
import static uz.kassa.bot.TextUtil.fmtTiyin;

/**
 * 📒 Adesk ↔ MoySklad (docs/ADESK.md §8): SuperAdmin — ⚙️ Настройка → 🔗 MoySklad → 📒 Adesk.
 * Callback'lar a:ad*: ad — menyu, adg — yoqish/o'chirish, adv:KEY — qiymat kiritish, adr/adf — sinxron/to'liq,
 * adc — solishtirish, ade:N — xatolar, ado/ados:N — ombor firmasi, adrv/adrvy — Adesk→MoySklad, ads — to'xtatish.
 */
@Component
@RequiredArgsConstructor
public class AdeskHandler {

    public static final String LABEL = "📒 Adesk";
    private static final String BACK = "a:ad";
    private static final int PAGE = 10;
    private static final String RULE = "━━━━━━━━━━━━━━━━━━━━";
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final Sender sender;
    private final AdeskConfig cfg;
    private final AdeskRunner runner;
    private final AdeskHttp http;
    private final AdeskMsReader msr;
    private final AdeskSyncService sync;
    private final AdeskLinkRepo repo;
    private final AuditService audit;
    private final AdeskAccountMapper mapper;

    public boolean adminCallback(AppUser u, Session s, String cmd, String arg, long chatId, int msgId) {
        if (u.getRole() != Role.SUPERADMIN) { sender.send(chatId, "⚠️ Faqat SuperAdmin uchun."); return true; }
        switch (cmd) {
            case "ad" -> menu(s, chatId, msgId);
            case "adg" -> {
                if (!cfg.enabled() && !cfg.hasToken()) { sender.send(chatId, "❗️ Avval Adesk tokenini kiriting: 🔑 Token."); menu(s, chatId, msgId); return true; }
                cfg.setEnabled(!cfg.enabled());
                audit.log(u.getId(), "ADESK_" + (cfg.enabled() ? "YOQILDI" : "OCHIRILDI"), "settings", null, "");
                menu(s, chatId, msgId);
            }
            case "adv" -> askValue(s, arg, chatId, msgId);
            case "adr", "adf" -> {
                if (!cfg.hasToken()) { sender.send(chatId, "❗️ Adesk tokeni kiritilmagan."); return true; }
                boolean full = cmd.equals("adf");
                if (runner.requestRun(full, chatId)) {
                    audit.log(u.getId(), "ADESK_SINXRON", "settings", null, full ? "full" : "inc");
                    sender.send(chatId, "⏳ " + (full ? "To'liq sinxron" : "Sinxron") + " boshlandi. Tugagach natija shu yerga keladi."
                            + (repo.countByKindAndStatus(AdeskLink.CONTRACTOR, AdeskLink.OK) == 0
                            ? "\n<i>Birinchi yuklash uzoq davom etadi (kontragent va tovarlar minglab) — jarayonni 🔃 Yangilash bilan kuzating.</i>" : ""));
                } else sender.send(chatId, "⏳ Sinxron hozir ishlayapti — tugashini kuting.");
                menu(s, chatId, msgId);
            }
            case "adc" -> {
                if (!cfg.hasToken()) { sender.send(chatId, "❗️ Adesk tokeni kiritilmagan."); return true; }
                if (runner.requestCheck(chatId)) sender.send(chatId, "⏳ Solishtirilmoqda (1–3 daqiqa) — natija va Excel shu yerga keladi.");
                else sender.send(chatId, "⏳ Sinxron hozir ishlayapti — tugagach qayta bosing.");
            }
            case "ade" -> errors(arg, chatId, msgId);
            case "ado" -> orgList(chatId, msgId);
            case "ados" -> {
                List<AdeskMsReader.MsOrg> orgs = msr.orgs();
                int i = idx(arg, orgs.size());
                cfg.set(AdeskConfig.STOCK_ORG, orgs.get(i).id());
                audit.log(u.getId(), "ADESK_SOZLAMA", "settings", null, "stockOrg=" + orgs.get(i).name());
                menu(s, chatId, msgId);
            }
            case "adrv" -> {
                if (cfg.reverse()) {
                    cfg.setReverse(false);
                    audit.log(u.getId(), "ADESK_SOZLAMA", "settings", null, "reverse=0");
                    menu(s, chatId, msgId);
                } else reverseConfirm(chatId, msgId);
            }
            case "adrvy" -> {
                cfg.setReverse(true);
                audit.log(u.getId(), "ADESK_SOZLAMA", "settings", null, "reverse=1");
                menu(s, chatId, msgId);
            }
            case "adm", "adme", "adms", "admr", "admc", "admy" -> {
                if (!cfg.hasToken()) { sender.send(chatId, "❗️ Avval Adesk tokenini kiriting: 🔑 Token."); return true; }
                if (cfg.accountsConfirmed() && !cmd.equals("adm")) { sender.send(chatId, "ℹ️ Hisoblar bog'lash allaqachon tasdiqlangan."); menu(s, chatId, msgId); return true; }
                try { mapCallback(u, s, cmd, arg, chatId, msgId); }
                catch (Exception e) { sender.send(chatId, "⚠️ Hisoblar o'qilmadi — " + esc(String.valueOf(e.getMessage()))); }
            }
            case "adt" -> {
                List<List<InlineKeyboardButton>> r2 = new ArrayList<>();
                r2.add(irow(btn("🔑 Token", "a:adv:tok"), btn("🏢 Ombor firmasi", "a:ado")));
                r2.add(irow(btn("📅 Davr boshi", "a:adv:start"), btn("📅 Davr oxiri", "a:adv:end")));
                r2.add(irow(btn("🏷 Kirim statyasi", "a:adv:cin"), btn("⏱ Oraliq", "a:adv:int")));
                r2.add(irow(btn("🕘 Hisobot vaqti", "a:adv:time"), btn("📣 Hisobot chati", "a:adv:chat")));
                r2.add(irow(btn("↩️ Adesk → MoySklad", "a:adrv")));
                r2.add(irow(btn("⬅️ Orqaga", BACK)));
                sender.edit(chatId, msgId, "⚙️ <b>Adesk sozlamalari</b>\n\nSozlamani tanlang:", inline(r2));
            }
            case "ads" -> {
                sender.send(chatId, runner.requestStop() ? "⏹ To'xtatish so'raldi — joriy qadam tugagach to'xtaydi. Bajarilgani saqlanadi."
                        : "ℹ️ Hozir hech narsa ishlamayapti.");
                menu(s, chatId, msgId);
            }
            default -> { return false; }
        }
        return true;
    }

    /* ==================== menyu ==================== */

    public void menu(Session s, long chatId, int msgId) {
        StringBuilder sb = new StringBuilder("📒 <b>Adesk ↔ MoySklad</b>\n\n");
        sb.append(cfg.enabled() ? "🟢 Yoqilgan — har " + cfg.intervalMin() + " daqiqada sinxron" : "⚪ O'chirilgan (avtomatik sinxron yurmaydi)").append("\n");
        String tok = cfg.token();
        sb.append("🔑 Token: ").append(tok.isBlank() ? "❗️ kiritilmagan" : "✅ <code>…" + esc(tok.substring(Math.max(0, tok.length() - 4))) + "</code>").append("\n");
        LocalDate end = cfg.end();
        sb.append("📅 Davr: <b>").append(cfg.start().format(DF)).append(" – ").append(end == null ? "bugungacha" : end.format(DF)).append("</b>\n");
        sb.append("🏢 Ombor firmasi (boshlang'ich zaxira): ").append(esc(stockOrgName())).append("\n");
        List<Long> chats = cfg.reportChats();
        sb.append("🕘 Kunlik solishtirish: <b>").append(cfg.reportTime()).append("</b> · ")
          .append(chats.isEmpty() ? "SuperAdmin va buxgalterlarga" : chats.size() + " ta chat + SuperAdmin/buxgalter").append("\n");
        sb.append("↩️ Adesk → MoySklad: ").append(cfg.reverse() ? "<b>yoqiq</b> (qo'lda kiritilgan operatsiyalar MoySklad'ga yoziladi)" : "o'chiq (faqat farq sifatida ko'rsatiladi)").append("\n");
        sb.append("🏷 Statyalar: kirim — «").append(esc(cfg.catIncome())).append("», o'tkazma — «").append(esc(cfg.catTransfer())).append("» · valyuta ").append(esc(cfg.currency())).append("\n");

        Map<String, long[]> cnt = counts();
        if (!cfg.accountsConfirmed())
            sb.append("\n🔗 <b>Hisoblar bog'lashi tasdiqlanmagan.</b> «🔗 Hisoblarni bog'lash» ni oching: Adesk'dagi hisoblaringiz "
                    + "MoySklad hisoblariga bog'lanadi, qolganlari yaratiladi. Adesk'da bog'lanmagan hisob bo'lsa, sinxron tasdiqgacha kutadi.\n");
        sb.append(RULE).append("\n<b>Bog'langan</b>: ")
          .append("hisob ").append(n(cnt, AdeskLink.ACCOUNT)).append(" · statya ").append(n(cnt, AdeskLink.CATEGORY))
          .append(" · kontragent ").append(fmt(n(cnt, AdeskLink.CONTRACTOR) + n(cnt, AdeskLink.EMPLOYEE) + n(cnt, AdeskLink.ORGC)))
          .append(" · tovar ").append(fmt(n(cnt, AdeskLink.PRODUCT)))
          .append(" · operatsiya ").append(fmt(n(cnt, AdeskLink.MONEY)))
          .append(" · otgruzka/priyomka ").append(fmt(n(cnt, AdeskLink.COMMIT))).append("\n");
        long errs = cnt.values().stream().mapToLong(v -> v[1]).sum();
        if (errs > 0) sb.append("⚠️ Xatolar: <b>").append(errs).append("</b> ta\n");

        String st = runner.status();
        sb.append(RULE).append("\n");
        if (st != null) sb.append(esc(st)).append("\n\n");
        cfg.get(AdeskConfig.LAST_RUN).ifPresent(x -> sb.append("<b>Oxirgi sinxron</b>\n").append(x).append("\n\n"));
        cfg.get(AdeskConfig.LAST_CHECK).ifPresent(x -> sb.append("<b>Oxirgi solishtirish</b>: ").append(esc(x)).append("\n"));

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn(cfg.enabled() ? "⏸ O'chirish" : "▶️ Yoqish", "a:adg"), btn("🔁 Sinxron", "a:adf")));
        rows.add(irow(btn("🧮 Solishtirish", "a:adc"), btn("⚠️ Xatolar", "a:ade:0")));
        boolean needMap = !cfg.accountsConfirmed();
        if (needMap) rows.add(0, irow(btn("🔗 Hisoblarni bog'lash", "a:adm:0")));
        rows.add(irow(btn("⚙️ Sozlamalar", "a:adt"), btn(st != null ? "⏹ To'xtatish" : "🔃 Yangilash", st != null ? "a:ads" : "a:ad")));
        rows.add(irow(btn("⬅️ Orqaga", "a:p:set")));
        String text = sb.toString();
        if (text.length() > 4000) text = text.substring(0, 3990) + "…";
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sender.send(chatId, text, inline(rows));
    }

    private Map<String, long[]> counts() {
        Map<String, long[]> m = new HashMap<>();
        for (Object[] row : repo.countGrouped()) {
            long[] v = m.computeIfAbsent((String) row[0], k -> new long[2]);
            long c = ((Number) row[2]).longValue();
            if (AdeskLink.OK.equals(row[1])) v[0] += c;
            else if (AdeskLink.ERROR.equals(row[1])) v[1] += c;
        }
        return m;
    }

    private static long n(Map<String, long[]> m, String kind) { long[] v = m.get(kind); return v == null ? 0 : v[0]; }

    private String stockOrgName() {
        String id = cfg.stockOrg();
        if (id.isBlank()) return "standart (001 …)";
        return repo.findByKindAndMsKey(AdeskLink.ORG, id).map(AdeskLink::getName).orElse(id);
    }

    /* ==================== qiymatlar ==================== */

    private void askValue(Session s, String key, long chatId, int msgId) {
        String prompt = switch (key) {
            case "tok" -> "🔑 <b>Adesk API token</b>\nAdesk → Настройки → Профиль → «Токен API» dan nusxa oling.\n\nTokenni yuboring (yoki «-»):";
            case "start" -> "📅 Davr boshi — shu sanadan boshlab hujjatlar o'tkaziladi, hisoblarning boshlang'ich qoldig'i va ombor zaxirasi shu sanaga olinadi.\nHozir: "
                    + cfg.start().format(DF) + "\n\nSanani kiriting (dd.MM.yyyy):";
            case "end" -> "📅 Davr oxiri — shu sanagacha bo'lgan hujjatlar o'tkaziladi. Birinchi bosqich: 30.09.2026 (faqat sentabr).\n"
                    + "Doimiy sinxron uchun «0» yuboring (bugungacha).\nHozir: " + (cfg.end() == null ? "bugungacha" : cfg.end().format(DF)) + "\n\nSanani kiriting (dd.MM.yyyy):";
            case "time" -> "🕘 Kunlik to'liq sinxron va solishtirish vaqti (HH:mm).\nHozir: " + cfg.reportTime() + "\n\nVaqtni kiriting:";
            case "chat" -> "📣 Kunlik solishtirish yuboriladigan guruh/kanal chat ID'lari (vergul bilan, masalan -1001234567890).\n"
                    + "Bot o'sha chatda a'zo bo'lishi kerak. Faqat SuperAdmin/buxgalterlarga — «0».\nHozir: "
                    + (cfg.reportChats().isEmpty() ? "yo'q" : cfg.reportChats().toString()) + "\n\nChat ID'ni kiriting:";
            case "cin" -> "🏷 MoySklad kirim hujjatlarida statya yo'q — Adesk'da qaysi kirim statyasiga yozilsin?\nHozir: «"
                    + esc(cfg.catIncome()) + "»\n\nStatya nomini kiriting:";
            case "int" -> "⏱ Avtomatik sinxron oralig'i, daqiqa (2–240).\nHozir: " + cfg.intervalMin() + "\n\nDaqiqani kiriting:";
            default -> null;
        };
        if (prompt == null) { menu(s, chatId, msgId); return; }
        s.state = Session.State.ADM_AD_VAL;
        s.data.put("adKey", key);
        sender.edit(chatId, msgId, prompt, inline(List.of(irow(btn("❌ Bekor", BACK)))));
    }

    /** Matn emoji bilan boshlansa — bu menyu tugmasi bosilgani (qiymat emas). «-» va «-100…» chat ID'lari qiymat. */
    public static boolean isButton(String text) {
        String t = text == null ? "" : text.trim();
        return !t.isEmpty() && !Character.isLetterOrDigit(t.codePointAt(0)) && t.charAt(0) != '-' && t.charAt(0) != '+';
    }

    /** ADM_AD_VAL: sozlama qiymati. */
    public void onText(AppUser u, Session s, String text, long chatId) {
        String key = s.getStr("adKey");
        s.reset();
        String t = text.trim();
        if (key == null || t.equals("-")) { sender.send(chatId, "❌ Bekor qilindi."); menu(s, chatId, 0); return; }
        try {
            switch (key) {
                case "tok" -> {
                    if (t.length() < 16 || t.contains(" ")) throw new IllegalArgumentException("tokenga o'xshamaydi");
                    String err = http.test(t);
                    if (err != null) { sender.send(chatId, "⚠️ Token tekshiruvdan o'tmadi — " + esc(err) + ". Saqlanmadi."); menu(s, chatId, 0); return; }
                    cfg.set(AdeskConfig.TOKEN, t);
                    audit.log(u.getId(), "ADESK_TOKEN", "settings", null, t.substring(0, 4) + "…");
                    sender.send(chatId, "✅ Token saqlandi va tekshirildi — Adesk javob berdi.");
                    menu(s, chatId, 0);
                    return;
                }
                case "start" -> {
                    LocalDate d = date(t);
                    cfg.set(AdeskConfig.START, d.toString());
                    sync.resetCursors();
                }
                case "end" -> {
                    if (t.equals("0")) cfg.set(AdeskConfig.END, "-");
                    else {
                        LocalDate d = date(t);
                        if (d.isBefore(cfg.start())) throw new IllegalArgumentException("davr boshidan oldin");
                        cfg.set(AdeskConfig.END, d.toString());
                    }
                    sync.resetCursors();
                }
                case "time" -> cfg.set(AdeskConfig.REPORT_TIME, LocalTime.parse(t.length() == 4 ? "0" + t : t).toString());
                case "chat" -> {
                    if (t.equals("0")) cfg.set(AdeskConfig.REPORT_CHATS, "");
                    else {
                        List<String> ids = new ArrayList<>();
                        for (String p : t.split("[,\\s]+")) if (!p.isBlank()) ids.add(String.valueOf(Long.parseLong(p.trim())));
                        cfg.set(AdeskConfig.REPORT_CHATS, String.join(",", ids));
                    }
                }
                case "cin" -> {
                    if (t.length() < 2 || t.length() > 100) throw new IllegalArgumentException("nom 2–100 belgi");
                    cfg.set(AdeskConfig.CAT_INCOME, t);
                    sync.resetCursors();
                }
                case "int" -> {
                    int v = Integer.parseInt(t.replaceAll("\\D", ""));
                    if (v < 2 || v > 240) throw new IllegalArgumentException("2–240");
                    cfg.set(AdeskConfig.INTERVAL, String.valueOf(v));
                }
                default -> { menu(s, chatId, 0); return; }
            }
            audit.log(u.getId(), "ADESK_SOZLAMA", "settings", null, key + "=" + t);
            sender.send(chatId, "✅ Saqlandi." + (Set.of("start", "end", "cin").contains(key)
                    ? "\n<i>Keyingi sinxron hamma hujjatlarni yangi sozlama bo'yicha qayta solishtiradi — 🔁 To'liq sinxron bosing.</i>" : ""));
        } catch (Exception e) {
            sender.send(chatId, "⚠️ Qiymat noto'g'ri: " + esc(t) + (e.getMessage() == null ? "" : " — " + esc(e.getMessage())));
        }
        menu(s, chatId, 0);
    }

    private static LocalDate date(String t) {
        String x = t.trim();
        if (x.matches("\\d{2}\\.\\d{2}\\.\\d{4}")) return LocalDate.parse(x, DF);
        return LocalDate.parse(x);
    }

    /* ==================== xatolar ==================== */

    private void errors(String arg, long chatId, int msgId) {
        List<AdeskLink> all = repo.findByStatusOrderByUpdatedAtDesc(AdeskLink.ERROR);
        int pages = Math.max(1, (all.size() + PAGE - 1) / PAGE);
        int p = Math.min(idx(arg, pages), pages - 1);
        StringBuilder sb = new StringBuilder("⚠️ <b>Adesk xatolari</b>" + (all.isEmpty() ? "" : " (" + (p + 1) + "/" + pages + ")") + "\n\n");
        if (all.isEmpty()) sb.append("Xatolar yo'q.");
        for (AdeskLink l : all.subList(p * PAGE, Math.min(all.size(), (p + 1) * PAGE))) {
            sb.append("• <b>").append(esc(kindTitle(l))).append("</b>");
            if (l.getName() != null) sb.append(" ").append(esc(l.getName()));
            if (l.getDocDate() != null) sb.append(" · ").append(l.getDocDate().format(DF));
            if (l.getSumTiyin() != null) sb.append(" · ").append(fmtTiyin(Math.abs(l.getSumTiyin())));
            sb.append("\n   — ").append(esc(cut(l.getError(), 160))).append("\n");
        }
        if (!all.isEmpty()) sb.append("\n<i>Sababni MoySklad'da tuzating va 🔁 To'liq sinxron bosing — xatoli yozuvlar qayta yuboriladi.</i>");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> nav = new ArrayList<>();
        if (p > 0) nav.add(btn("⬅️ Oldingi", "a:ade:" + (p - 1)));
        if (p + 1 < pages) nav.add(btn("Keyingi ➡️", "a:ade:" + (p + 1)));
        if (!nav.isEmpty()) rows.add(nav);
        rows.add(irow(btn("⬅️ Orqaga", BACK)));
        if (msgId > 0) sender.edit(chatId, msgId, sb.toString(), inline(rows));
        else sender.send(chatId, sb.toString(), inline(rows));
    }

    private static String kindTitle(AdeskLink l) {
        if (l.getMsType() != null) return switch (l.getMsType()) {
            case "cashin" -> "Приходный ордер";
            case "cashout" -> "Расходный ордер";
            case "paymentin" -> "Входящий платёж";
            case "paymentout" -> "Исходящий платёж";
            case "demand" -> "Отгрузка";
            case "supply" -> "Приёмка";
            case "salesreturn" -> "Возврат покупателя";
            case "purchasereturn" -> "Возврат поставщику";
            default -> l.getMsType();
        };
        return switch (l.getKind()) {
            case AdeskLink.ORG -> "Yuridik shaxs";
            case AdeskLink.ACCOUNT -> "Hisob";
            case AdeskLink.CATEGORY -> "Statya";
            case AdeskLink.CONTRACTOR, AdeskLink.EMPLOYEE, AdeskLink.ORGC -> "Kontragent";
            case AdeskLink.PRODUCT -> "Tovar";
            default -> l.getKind();
        };
    }

    /* ==================== ombor firmasi / teskari yo'nalish ==================== */

    private void orgList(long chatId, int msgId) {
        List<AdeskMsReader.MsOrg> orgs = msr.orgs();
        String cur = cfg.stockOrg();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < orgs.size(); i++)
            rows.add(irow(btn((orgs.get(i).id().equals(cur) ? "✅ " : "") + orgs.get(i).shortName(), "a:ados:" + i)));
        rows.add(irow(btn("⬅️ Orqaga", BACK)));
        sender.edit(chatId, msgId, "🏢 <b>Ombor firmasi</b>\n\nMoySklad'da zaxira tashkilotlarga bo'linmaydi, Adesk'da esa tovar partiyasi "
                + "yuridik shaxs balansida turadi. Davr boshidagi zaxira (boshlang'ich partiya) qaysi firma balansiga yozilsin?\n"
                + "<i>Faqat yangi yaratiladigan tovarlarga ta'sir qiladi.</i>\n\nFirmani tanlang:", inline(rows));
    }

    /* ==================== 🔗 hisoblarni bog'lash ==================== */

    private static final int MAP_PAGE = 8;

    private void mapCallback(AppUser u, Session s, String cmd, String arg, long chatId, int msgId) {
        switch (cmd) {
            case "adm" -> mapList(idx(arg, 1000), chatId, msgId);
            case "adme" -> mapEdit(idx(arg, 1000), chatId, msgId);
            case "adms" -> {
                String[] p = arg.split("\\.");
                AdeskAccountMapper.View v = mapper.view(false);
                int i = idx(p[0], v.rows().size());
                long id = p.length > 1 ? Long.parseLong(p[1]) : 0;
                mapper.set(v.rows().get(i).msKey(), id == 0 ? null : id);
                mapList(pageOf(mapper.view(false), i), chatId, msgId);
            }
            case "admr" -> { mapper.reset(); mapList(0, chatId, msgId); }
            case "admc" -> mapConfirm(chatId, msgId);
            case "admy" -> {
                AdeskAccountMapper.View v = mapper.view(false);
                int n = mapper.confirm();
                audit.log(u.getId(), "ADESK_HISOB_BOGLASH", "settings", null, "bog'landi=" + n + ", yangi=" + (v.rows().size() - n));
                sender.send(chatId, "✅ Hisoblar bog'landi: <b>" + n + "</b> ta. Yangi yaratiladi: <b>" + (v.rows().size() - n) + "</b> ta.\n"
                        + "Endi 🔁 To'liq sinxron bosing — Adesk'ka ma'lumot o'tishi shu bilan boshlanadi.");
                menu(s, chatId, msgId);
            }
            default -> menu(s, chatId, msgId);
        }
    }

    /** Ko'rsatish tartibi: avval bog'langanlar, keyin yangilari (indekslar — view.rows() dagi asl o'rin). */
    private static List<Integer> order(AdeskAccountMapper.View v) {
        List<Integer> o = new ArrayList<>();
        for (int i = 0; i < v.rows().size(); i++) if (v.rows().get(i).adeskId() != null) o.add(i);
        for (int i = 0; i < v.rows().size(); i++) if (v.rows().get(i).adeskId() == null) o.add(i);
        return o;
    }

    private static int pageOf(AdeskAccountMapper.View v, int rowIdx) { return Math.max(0, order(v).indexOf(rowIdx)) / MAP_PAGE; }

    private void mapList(int page, long chatId, int msgId) {
        AdeskAccountMapper.View v = mapper.view(false);
        List<Integer> ord = order(v);
        int pages = Math.max(1, (ord.size() + MAP_PAGE - 1) / MAP_PAGE);
        int p = Math.min(Math.max(0, page), pages - 1);
        List<AdeskClient.AdAccount> free = v.unmapped();
        StringBuilder sb = new StringBuilder("🔗 <b>Hisoblarni bog'lash</b> (" + (p + 1) + "/" + pages + ")\n\n");
        sb.append("MoySklad: <b>").append(v.rows().size()).append("</b> hisob · Adesk: <b>").append(v.adesk().size()).append("</b> hisob\n");
        sb.append("✅ Bog'lanadi: <b>").append(v.mapped()).append("</b> · ➕ yangi yaratiladi: <b>").append(v.rows().size() - v.mapped()).append("</b>\n");
        if (!free.isEmpty())
            sb.append("⚠️ Adesk'da bog'lanmay qoladi: ").append(free.size()).append(" ta — ")
              .append(esc(String.join(", ", free.stream().limit(8).map(AdeskClient.AdAccount::name).toList()))).append(free.size() > 8 ? " …" : "").append("\n");
        sb.append("\n<i>").append(v.suggested().isEmpty() ? "Sizning tanlovingiz ko'rsatilgan." : "Bot nomlar bo'yicha taklif qildi — tekshiring.")
          .append(" O'zgartirish uchun hisobni bosing. Tasdiqlangach birinchi sinxron bog'langan Adesk hisobini MoySklad nomiga o'zgartiradi va qoldig'ini ")
          .append(cfg.openingDate().format(DF)).append(" holatiga qo'yadi.</i>");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int k = p * MAP_PAGE; k < Math.min(ord.size(), (p + 1) * MAP_PAGE); k++) {
            int i = ord.get(k);
            AdeskAccountMapper.Row r = v.rows().get(i);
            AdeskClient.AdAccount a = v.adesk(r.adeskId());
            String label = a == null ? "➕ " + cut(r.msName(), 30) + " · yangi" : "✅ " + cut(r.msName(), 22) + " → " + cut(a.name(), 20);
            rows.add(irow(btn(label, "a:adme:" + i)));
        }
        List<InlineKeyboardButton> nav = new ArrayList<>();
        if (p > 0) nav.add(btn("⬅️ Oldingi", "a:adm:" + (p - 1)));
        if (p + 1 < pages) nav.add(btn("Keyingi ➡️", "a:adm:" + (p + 1)));
        if (!nav.isEmpty()) rows.add(nav);
        rows.add(irow(btn("🔄 Qayta taklif", "a:admr"), btn("✅ Tasdiqlash", "a:admc")));
        rows.add(irow(btn("⬅️ Orqaga", BACK)));
        if (msgId > 0) sender.edit(chatId, msgId, sb.toString(), inline(rows));
        else sender.send(chatId, sb.toString(), inline(rows));
    }

    private void mapEdit(int i, long chatId, int msgId) {
        AdeskAccountMapper.View v = mapper.view(false);
        if (i < 0 || i >= v.rows().size()) { mapList(0, chatId, msgId); return; }
        AdeskAccountMapper.Row r = v.rows().get(i);
        Map<Long, String> usedBy = new HashMap<>();
        for (AdeskAccountMapper.Row x : v.rows()) if (x.adeskId() != null && x != r) usedBy.put(x.adeskId(), x.msName());
        StringBuilder sb = new StringBuilder("🔗 <b>").append(esc(r.msName())).append("</b>\n")
                .append("MoySklad · ").append(esc(r.orgShort())).append(r.cash() ? " · naqd kassa" : "").append("\n\n")
                .append("Adesk'dagi qaysi hisobga bog'lansin? ↪️ — boshqa MoySklad hisobiga bog'langan, tanlasangiz u yerdan olinadi.\n\nHisobni tanlang:");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn((r.adeskId() == null ? "✅ " : "") + "➕ Yangi yaratilsin", "a:adms:" + i + ".0")));
        for (AdeskClient.AdAccount a : v.adesk()) {
            String mark = Objects.equals(r.adeskId(), a.id()) ? "✅ " : usedBy.containsKey(a.id()) ? "↪️ " : "";
            rows.add(irow(btn(mark + cut(a.name(), 34), "a:adms:" + i + "." + a.id())));
        }
        rows.add(irow(btn("⬅️ Ro'yxat", "a:adm:" + pageOf(v, i))));
        sender.edit(chatId, msgId, sb.toString(), inline(rows));
    }

    private void mapConfirm(long chatId, int msgId) {
        AdeskAccountMapper.View v = mapper.view(false);
        List<AdeskClient.AdAccount> free = v.unmapped();
        StringBuilder sb = new StringBuilder("🔗 <b>Bog'lashni tasdiqlash</b>\n\n");
        sb.append("✅ Bog'lanadi: <b>").append(v.mapped()).append("</b> ta — Adesk'dagi nomi MoySklad nomiga o'zgartiriladi\n");
        sb.append("➕ Yangi yaratiladi: <b>").append(v.rows().size() - v.mapped()).append("</b> ta\n");
        if (!free.isEmpty()) sb.append("⚠️ Adesk'da bog'lanmay qoladi: <b>").append(free.size()).append("</b> ta — ")
                .append(esc(String.join(", ", free.stream().map(AdeskClient.AdAccount::name).toList())))
                .append(". Ular o'zgarmaydi, solishtirishda «ортиқча» bo'lib ko'rinadi.\n");
        sb.append("\nBoshlang'ich qoldiqlar ").append(cfg.openingDate().format(DF)).append(" holatiga MoySklad'dan qo'yiladi.\n\nTasdiqlaysizmi?");
        sender.edit(chatId, msgId, sb.toString(), inline(List.of(irow(btn("✅ Ha, tasdiqlansin", "a:admy"), btn("❌ Yo'q", "a:adm:0")))));
    }

    private void reverseConfirm(long chatId, int msgId) {
        sender.edit(chatId, msgId, "↩️ <b>Adesk → MoySklad</b>\n\n"
                + "Yoqilsa, Adesk'da QO'LDA kiritilgan har bir kirim/chiqim MoySklad'ga hujjat bo'lib yoziladi:\n"
                + "• kassa hisobi — Приходный / Расходный ордер\n"
                + "• bank/karta hisobi — Входящий / Исходящий платёж\n"
                + "• kontragent va chiqim statyasi MoySklad'da bo'lmasa — shu nom bilan yaratiladi\n\n"
                + "⚠️ Bu hujjatlar bot kassa hisobiga ham tushadi (MoySklad — asosiy manba). O'zgartirish va o'chirish faqat MoySklad'da qilinadi.\n\n"
                + "Yoqilsinmi?", inline(List.of(irow(btn("✅ Ha, yoqilsin", "a:adrvy"), btn("❌ Yo'q", BACK)))));
    }

    private static int idx(String arg, int size) {
        try { int i = Integer.parseInt(arg); return i < 0 || i >= size ? 0 : i; } catch (Exception e) { return 0; }
    }

    private static String cut(String s, int max) { return s == null ? "" : s.length() <= max ? s : s.substring(0, max) + "…"; }
}
