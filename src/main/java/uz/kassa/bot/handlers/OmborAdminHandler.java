package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.Sender;
import uz.kassa.bot.Session;
import uz.kassa.domain.*;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.repo.OmborQoidaRepo;
import uz.kassa.repo.OmborStoreRepo;
import uz.kassa.service.AuditService;
import uz.kassa.service.ombor.*;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.esc;

/**
 * ⚙️ Настройка → 🔗 MoySklad → 🏬 Омбор назорати (SuperAdmin): yoqish, vaqtlar, qoidalar (yoq/o'chir, severity,
 * rol, eskalatsiya, params JSON), rollar (do'kon kesimida zavsklad/zakupshik/direktor), ombor ↔ do'kon
 * bog'lanishi, hozir sinxron/tekshirish. Callback: a:om*.
 */
@Component
@RequiredArgsConstructor
public class OmborAdminHandler {

    public static final String LABEL = "🏬 Омбор назорати";
    private static final String BACK = "a:om";

    private final Sender sender;
    private final OmborConfig cfg;
    private final OmborRuleEngine engine;
    private final OmborSyncService sync;
    private final OmborQoidaRepo ruleRepo;
    private final OmborStoreRepo storeRepo;
    private final KassaRepo kassaRepo;
    private final AppUserRepo userRepo;
    private final AuditService audit;
    private final uz.kassa.repo.OmborDavrRepo davrRepo;
    private final uz.kassa.repo.OmborTovarRepo tovarRepo;
    private final OmborCalcService calc;
    private final OmborSanoqService sanoqSvc;
    private final OmborSalesService sales;
    private final OmborNarxService narxSvc;
    private final OmborDraftService draftSvc;


    public boolean onCallback(AppUser u, Session s, String cmd, String arg, long chatId, int msgId) {
        if (u.getRole() != Role.SUPERADMIN) { sender.send(chatId, "⚠️ Faqat SuperAdmin uchun."); return true; }
        switch (cmd) {
            case "om" -> menu(s, chatId, msgId);
            case "omg" -> { cfg.setEnabled(!cfg.enabled()); audit.log(u.getId(), "OMBOR_" + (cfg.enabled() ? "YOQILDI" : "OCHIRILDI"), "settings", null, ""); menu(s, chatId, msgId); }
            case "omv" -> askValue(s, arg, chatId, msgId);
            case "omk" -> rules(chatId, msgId);
            case "omkv" -> ruleCard(s, arg, chatId, msgId);
            case "omkt" -> { rule(arg).ifPresent(r -> { r.setEnabled(!r.isEnabled()); ruleRepo.save(r); audit.log(u.getId(), "OMBOR_QOIDA_SOZ", "ombor_qoida", null, arg + " enabled=" + r.isEnabled()); }); ruleCard(s, arg, chatId, msgId); }
            case "omks" -> { rule(arg).ifPresent(r -> { r.setSeverity(next(OmborConfig.SEVERITIES, r.getSeverity())); ruleRepo.save(r); }); ruleCard(s, arg, chatId, msgId); }
            case "omkr" -> { rule(arg).ifPresent(r -> { r.setToRole(next(OmborConfig.TO_ROLES, r.getToRole())); ruleRepo.save(r); }); ruleCard(s, arg, chatId, msgId); }
            case "omkn" -> {
                var r = rule(arg).orElse(null);
                if (r != null) { int[] c = engine.evaluate(r); sender.send(chatId, "🔎 " + esc(r.getTitle()) + ": yangi " + c[0] + " · yopildi " + c[1]); }
                ruleCard(s, arg, chatId, 0);
            }
            case "omr" -> roles(chatId, msgId);
            case "omrr" -> rolePick(arg, chatId, msgId);
            case "omrt" -> {
                String[] p = arg.split("\\.");
                cfg.toggleRoleUser(p[0], Long.parseLong(p[1]), Long.parseLong(p[2]));
                audit.log(u.getId(), "OMBOR_ROL", "settings", null, arg);
                rolePick(p[0] + "." + p[1], chatId, msgId);
            }
            case "oms" -> stores(s, chatId, msgId);
            case "omsp" -> storePick(s, Integer.parseInt(arg), chatId, msgId);
            case "omsk" -> {
                String[] p = arg.split("\\.");
                Object o = s.data.get("omStores");
                if (o instanceof List<?> l) {
                    int i = Integer.parseInt(p[0]);
                    if (i >= 0 && i < l.size()) {
                        long kassa = Long.parseLong(p[1]);
                        sync.bindStore(String.valueOf(l.get(i)), kassa == 0 ? null : kassa);
                        audit.log(u.getId(), "OMBOR_STORE_BOG", "settings", null, l.get(i) + " -> " + kassa);
                    }
                }
                stores(s, chatId, msgId);
            }
            case "omn" -> {
                sender.edit(chatId, msgId, "⏳ Sinxron ketmoqda (omborlar, tovarlar, qoldiqlar)…");
                new Thread(() -> { String r = sync.syncAll(); int[] c = engine.tick();
                    sender.send(chatId, "✅ <b>Sinxron</b>\n" + r + "⚠️ Qoidalar: yangi " + c[0] + " · yopildi " + c[1]); menu(s, chatId, 0); }, "ombor-sync-now").start();
            }
            case "omt" -> { int[] c = engine.tick(); sender.send(chatId, "🔎 Qoidalar tekshirildi: yangi " + c[0] + " · yopildi " + c[1]); menu(s, chatId, 0); }
            case "omc" -> {
                sender.edit(chatId, msgId, "⏳ Tunlik hisoblar (sotuv, ABC, buyurtma nuqtasi, fill rate, sanoq rejasi, hamkorlar)…");
                new Thread(() -> { sales.refreshRecent(); calc.nightly(); int n = sanoqSvc.planToday(); int h = sync.syncHamkorlar();
                    sender.send(chatId, "✅ Hisoblar bajarildi. Sanoq rejasi: " + n + " ta tovar · hamkorlar: " + h); menu(s, chatId, 0); }, "ombor-calc-now").start();
            }
            case "omcs" -> { sender.edit(chatId, msgId, "⏳ Sotuv tarixi yuklanmoqda (20 kun)…"); new Thread(() -> { int n = sales.backfillTick(); sender.send(chatId, "📈 Sotuv tarixi: " + n + " kun yuklandi" + (sales.backfillDone() ? " — to'liq" : ", davom etadi (har 15 daqiqa)")); menu(s, chatId, 0); }, "ombor-sales-now").start(); }
            case "omsm" -> { cfg.set(OmborConfig.SANOQ_MAJBURIY, cfg.sanoqMajburiy() ? "0" : "1"); audit.log(u.getId(), "OMBOR_SOZLAMA", "settings", null, "sanoq_majburiy=" + cfg.sanoqMajburiy()); menu(s, chatId, msgId); }
            case "omd" -> davrList(s, chatId, msgId);
            case "omda" -> {
                s.state = Session.State.ADM_OM_DAVR;
                sender.edit(chatId, msgId, "📅 <b>Yangi davr</b> — bir qatorda, «;» bilan:\n<code>TUR; tovar artikuli yoki nomi yoki guruh nomi; boshlanish dd.MM.yyyy; tugash dd.MM.yyyy; kod</code>\n"
                        + "TUR: AKSIYA · MAVSUM · YANGI (sinov partiyasi) · SOVISH.\nMasalan: <code>AKSIYA; AK-NN-06165; 01.10.2026; 15.10.2026; OKT26</code>\n"
                        + "Guruh uchun nomi oldiga «#»: <code>MAVSUM; #Kondensatorlar; 01.05.2026; 31.08.2026; YOZ</code>",
                        inline(List.of(irow(btn("❌ Bekor", "a:omd")))));
            }
            case "omdx" -> { davrRepo.deleteById(Long.parseLong(arg)); audit.log(u.getId(), "OMBOR_DAVR_OCHIRILDI", "ombor_davr", Long.parseLong(arg), ""); davrList(s, chatId, msgId); }
            case "omgs" -> { sender.edit(chatId, msgId, "⏳ Google Sheets o'qilmoqda…"); new Thread(() -> { String r = narxSvc.pullSheets(u.getId()); int n = narxSvc.fromSupplies();
                sender.send(chatId, r == null ? "⚠️ Google Sheets sozlanmagan (GSHEET_ID)" : "📥 <b>Sheets</b>\n" + esc(r) + "Priyomkadan narx: " + n + " yangi"); menu(s, chatId, 0); }, "ombor-sheets").start(); }
            case "omqb" -> { sender.edit(chatId, msgId, "⏳ Qoralama tuzilmoqda…"); new Thread(() -> { int n = draftSvc.buildAll(); sender.send(chatId, "🧾 Qoralama: " + n + " ta"); menu(s, chatId, 0); }, "ombor-draft").start(); }
            case "omh" -> hisobotPick(chatId, msgId);
            case "omht" -> { cfg.toggleHisobot(Long.parseLong(arg)); hisobotPick(chatId, msgId); }
            default -> { return false; }
        }
        return true;
    }


    /* ---------- asosiy ---------- */

    public void menu(Session s, long chatId, int msgId) {
        StringBuilder sb = new StringBuilder("🏬 <b>Омбор назорати</b>\n\n");
        sb.append(cfg.enabled() ? "🟢 Yoqilgan" : "⚪ O'chirilgan").append("\n");
        sb.append("🌙 Qoldiqlar o'qish vaqti: <b>").append(cfg.stockTime()).append("</b> · 🙈 e'tiborsiz oynasi: <b>").append(cfg.ignoreDays()).append(" kun</b>\n");
        sb.append("🚚 Muddat <b>").append(cfg.leadDays()).append("</b> · 🛡 xavfsizlik <b>").append(cfg.safetyDays()).append("</b> · 📦 qoplash <b>").append(cfg.coverDays())
          .append("</b> kun · 🔤 ABC ").append(cfg.abcDays('A')).append("/").append(cfg.abcDays('B')).append("/").append(cfg.abcDays('C'))
          .append(" · 🗓 hujjat ").append(cfg.docsDays()).append(" kun · 📈 sotuv ").append(sales.backfillDone() ? "to'liq" : "yuklanmoqda")
          .append(" · 🤝 teg: ").append(cfg.hamkorTag().isBlank() ? "—" : esc(cfg.hamkorTag())).append("\n");
        sb.append("📏 Qoidalar: <b>").append(ruleRepo.findByEnabledTrueOrderBySortAscCodeAsc().size()).append("/").append(ruleRepo.count()).append("</b> yoqilgan · ⚠️ ochiq: <b>")
          .append(engine.openCount()).append("</b>\n");
        long unbound = kassaRepo.findByActiveTrueOrderByIdAsc().stream().filter(k -> !k.isCashless() && k.getMoyskladWarehouseId() == null).count();
        if (unbound > 0) sb.append("⚪ Ombor bog'lanmagan do'konlar: <b>").append(unbound).append("</b> — 🏪 tugmasi\n");
        for (OmborSinxron x : sync.status())
            sb.append(x.getLastError() == null ? "🟢 " : "🔴 ").append(x.getEntity()).append(" ").append(x.getRowsN()).append(" ta  ");
        sb.append("\n\nTekshiruvchilar: ");
        List<String> cs = new ArrayList<>();
        for (OmborChecker c : engine.checkers()) cs.add(c.code());
        sb.append(esc(String.join(", ", cs)));

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn(cfg.enabled() ? "⏸ O'chirish" : "▶️ Yoqish", "a:omg"), btn("🌙 Qoldiq vaqti", "a:omv:stock"), btn("🙈 Kunlar", "a:omv:ignore")));
        rows.add(irow(btn("📏 Qoidalar", "a:omk"), btn("👥 Rollar (do'kon)", "a:omr"), btn("📣 Hisobot oluvchilar", "a:omh")));
        rows.add(irow(btn("🏪 Ombor ↔ do'kon", "a:oms"), btn("📅 Davrlar (aksiya/mavsum)", "a:omd")));
        rows.add(irow(btn(cfg.sanoqMajburiy() ? "🔢 Sanoq majburiy: ON" : "🔢 Sanoq majburiy: OFF", "a:omsm"), btn("🔤 ABC kunlar", "a:omv:abc")));
        rows.add(irow(btn("🚚 Muddat (kun)", "a:omv:lead"), btn("🛡 Xavfsizlik (kun)", "a:omv:safety"), btn("📦 Qoplash (kun)", "a:omv:cover")));
        rows.add(irow(btn("🗓 Hujjat oynasi (kun)", "a:omv:docs"), btn("📈 Sotuv tarixi (kun)", "a:omv:sales"), btn("🤝 Hamkor tegi", "a:omv:hamkor")));
        rows.add(irow(btn("🔄 Hozir sinxron", "a:omn"), btn("🔎 Hozir tekshirish", "a:omt")));
        rows.add(irow(btn("🧮 Hisoblar hozir", "a:omc"), btn("📈 Sotuv tarixi +20 kun", "a:omcs")));
        rows.add(irow(btn("📥 Sheets (narx/yetkazuvchi/davr)", "a:omgs"), btn("🧾 Qoralama tuzish", "a:omqb")));
        rows.add(irow(btn("💰 Katta summa (so'm)", "a:omv:katta"), btn("🧊 Sovish (kun)", "a:omv:sovish")));
        rows.add(irow(btn("⬅️ Orqaga", "a:p:set")));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    private void askValue(Session s, String key, long chatId, int msgId) {
        String prompt = switch (key) {
            case "stock" -> "🌙 Qoldiqlar (report/stock) kuniga bir marta qaysi vaqtdan keyin o'qilsin? (HH:mm)\nHozir: " + cfg.stockTime();
            case "ignore" -> "🙈 «E'tiborsiz» deyilgan kamchilik necha kun qayta chiqmasin? (1–365)\nHozir: " + cfg.ignoreDays();
            case "lead" -> "🚚 Standart yetkazib berish muddati (kun, 0–120) — buyurtma nuqtasi = 90 kunlik o'rtacha × (muddat + xavfsizlik)\nHozir: " + cfg.leadDays();
            case "safety" -> "🛡 Xavfsizlik zaxirasi (kun, 0–120)\nHozir: " + cfg.safetyDays();
            case "cover" -> "📦 Qoralama miqdori necha kunga yetsin? (1–365)\nHozir: " + cfg.coverDays();
            case "docs" -> "🗓 Hujjatlar necha kun orqadan o'qilsin va tekshirilsin? (7–400)\nHozir: " + cfg.docsDays();
            case "sales" -> "📈 Sotuv tarixi chuqurligi (kun, 30–800). O'zgartirilsa kursor qayta boshlanadi.\nHozir: " + cfg.salesDays();
            case "abc" -> "🔤 ABC sanoq davrlari (kun), vergul bilan A,B,C: masalan <code>10,30,90</code>\nHozir: " + cfg.abcDays('A') + "," + cfg.abcDays('B') + "," + cfg.abcDays('C');
            case "katta" -> "💰 Direktor tasdig'i kerak bo'ladigan qoralama summasi (so'm)\nHozir: " + cfg.kattaSumma();
            case "sovish" -> "🧊 Aksiyadan keyin necha kun tovar qoralamaga tushmasin? (1–120)\nHozir: " + cfg.sovishKun();
            case "hamkor" -> "🤝 Hamkor do'konlar (tuman ustalari) MoySklad kontragent TEGI (Группы) nomi. «-» — o'chirish.\nHozir: " + (cfg.hamkorTag().isBlank() ? "—" : cfg.hamkorTag());
            default -> {
                if (key.startsWith("params:")) {
                    var r = rule(key.substring(7)).orElse(null);
                    yield r == null ? null : "🧩 <b>" + esc(r.getTitle()) + "</b> — params (JSON) yozing.\n"
                            + esc(engine.checker(r.getChecker()) == null ? "" : engine.checker(r.getChecker()).help()) + "\nHozir: <code>" + esc(r.getParams()) + "</code>";
                }
                if (key.startsWith("esc1:") || key.startsWith("esc2:")) {
                    var r = rule(key.substring(5)).orElse(null);
                    yield r == null ? null : (key.startsWith("esc1") ? "⏰ Xabardan necha DAQIQADA tuzatilmasa RAHBARGA (0 — yo'q)? Hozir: " + r.getEsc1Min()
                            : "❌ Necha DAQIQADA «tuzatilmadi» ADMIN + rahbarga (0 — yo'q)? Hozir: " + r.getEsc2Min());
                }
                if (key.startsWith("title:")) {
                    var r = rule(key.substring(6)).orElse(null);
                    yield r == null ? null : "✏️ Qoida nomi (xabarlarda ko'rinadi):\nHozir: " + esc(r.getTitle());
                }
                yield null;
            }
        };
        if (prompt == null) { menu(s, chatId, msgId); return; }
        s.state = Session.State.ADM_OM_VAL;
        s.data.put("omKey", key);
        sender.edit(chatId, msgId, prompt, inline(List.of(irow(btn("❌ Bekor", BACK)))));
    }

    public void onText(AppUser u, Session s, String text, long chatId) {
        String key = s.getStr("omKey");
        s.reset();
        String t = text.trim();
        try {
            if (key == null) throw new IllegalArgumentException("kalit yo'q");
            if (key.equals("stock")) cfg.set(OmborConfig.STOCK_TIME, LocalTime.parse(t.length() == 4 ? "0" + t : t).toString());
            else if (key.equals("ignore")) cfg.set(OmborConfig.IGNORE_DAYS, String.valueOf(range(t, 1, 365)));
            else if (key.equals("lead")) cfg.set(OmborConfig.LEAD_DAYS, String.valueOf(range(t, 0, 120)));
            else if (key.equals("safety")) cfg.set(OmborConfig.SAFETY_DAYS, String.valueOf(range(t, 0, 120)));
            else if (key.equals("cover")) cfg.set(OmborConfig.COVER_DAYS, String.valueOf(range(t, 1, 365)));
            else if (key.equals("docs")) cfg.set(OmborConfig.DOCS_DAYS, String.valueOf(range(t, 7, 400)));
            else if (key.equals("sales")) { cfg.set(OmborConfig.SALES_DAYS, String.valueOf(range(t, 30, 800))); cfg.set(OmborConfig.SALES_CURSOR, ""); }
            else if (key.equals("abc")) {
                String[] a = t.split("[,;\\s]+");
                if (a.length != 3) throw new IllegalArgumentException("3 ta son kerak");
                cfg.set(OmborConfig.ABC_A_DAYS, String.valueOf(range(a[0], 1, 365))); cfg.set(OmborConfig.ABC_B_DAYS, String.valueOf(range(a[1], 1, 365))); cfg.set(OmborConfig.ABC_C_DAYS, String.valueOf(range(a[2], 1, 365)));
            }
            else if (key.equals("hamkor")) cfg.set(OmborConfig.HAMKOR_TAG, t.equals("-") ? "" : t);
            else if (key.equals("katta")) cfg.set(OmborConfig.KATTA_SUMMA, String.valueOf(Long.parseLong(t.replaceAll("\\D", ""))));
            else if (key.equals("sovish")) cfg.set(OmborConfig.SOVISH_KUN, String.valueOf(range(t, 1, 120)));
            else if (key.startsWith("params:")) {
                String err = engine.validateParams(t);
                if (err != null) throw new IllegalArgumentException("JSON xato: " + err);
                rule(key.substring(7)).ifPresent(r -> { r.setParams(t); ruleRepo.save(r); });
            } else if (key.startsWith("esc1:")) rule(key.substring(5)).ifPresent(r -> { r.setEsc1Min(range(t, 0, 43200)); ruleRepo.save(r); });
            else if (key.startsWith("esc2:")) rule(key.substring(5)).ifPresent(r -> { r.setEsc2Min(range(t, 0, 43200)); ruleRepo.save(r); });
            else if (key.startsWith("title:")) rule(key.substring(6)).ifPresent(r -> { r.setTitle(t.length() > 200 ? t.substring(0, 200) : t); ruleRepo.save(r); });
            else throw new IllegalArgumentException("noma'lum");
            audit.log(u.getId(), "OMBOR_SOZLAMA", "settings", null, key + "=" + t);
            sender.send(chatId, "✅ Saqlandi: " + esc(key));
        } catch (Exception e) {
            sender.send(chatId, "⚠️ Qiymat noto'g'ri: " + esc(t) + " — " + esc(String.valueOf(e.getMessage())));
        }
        if (key != null && key.contains(":")) ruleCard(s, key.substring(key.indexOf(':') + 1), chatId, 0);
        else menu(s, chatId, 0);
    }


    /* ---------- 📅 davrlar ---------- */

    private void davrList(Session s, long chatId, int msgId) {
        s.reset();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        StringBuilder sb = new StringBuilder("📅 <b>Davrlar</b> — aksiya (sotuv o'rtachasidan chiqariladi, tugagach " + cfg.sovishKun()
                + " kun SOVISH), mavsum (o'tgan yil koeffitsienti), yangi (sinov partiyasi — qoralamaga tushmaydi).\n\n");
        int n = 0;
        for (uz.kassa.domain.OmborDavr d : davrRepo.findAllByOrderByFromDateDesc()) {
            if (++n > 25) { sb.append("… yana bor\n"); break; }
            String what = d.getProductMsId() != null ? tovarRepo.findById(d.getProductMsId()).map(uz.kassa.domain.OmborTovar::getName).orElse(d.getProductMsId()) : "#" + d.getFolderName();
            rows.add(irow(btn(cut(d.getKind() + " " + d.getCode() + " · " + what + " · " + d.getFromDate().getDayOfMonth() + "." + d.getFromDate().getMonthValue() + "–" + d.getToDate().getDayOfMonth() + "." + d.getToDate().getMonthValue(), 58), "a:omd"),
                    btn("🗑", "a:omdx:" + d.getId())));
        }
        if (n == 0) sb.append("Hali davr yo'q.\n");
        rows.add(irow(btn("➕ Davr qo'shish", "a:omda")));
        rows.add(irow(btn("⬅️ Orqaga", BACK)));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    /** «TUR; tovar/guruh; dd.MM.yyyy; dd.MM.yyyy; kod» */
    public void onDavrText(AppUser u, Session s, String text, long chatId) {
        s.reset();
        try {
            String[] p = text.split(";");
            if (p.length < 4) throw new IllegalArgumentException("4 ta maydon kerak");
            String kind = p[0].trim().toUpperCase();
            if (!List.of("AKSIYA", "MAVSUM", "YANGI", "SOVISH").contains(kind)) throw new IllegalArgumentException("TUR: AKSIYA/MAVSUM/YANGI/SOVISH");
            String what = p[1].trim();
            var f = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
            java.time.LocalDate from = java.time.LocalDate.parse(p[2].trim(), f), to = java.time.LocalDate.parse(p[3].trim(), f);
            if (to.isBefore(from)) throw new IllegalArgumentException("tugash boshlanishdan oldin");
            String code = p.length > 4 ? p[4].trim() : "";
            uz.kassa.domain.OmborDavr d = uz.kassa.domain.OmborDavr.builder().kind(kind).fromDate(from).toDate(to).code(code).createdBy(u.getId()).build();
            if (what.startsWith("#")) d.setFolderName(what.substring(1).trim());
            else {
                List<uz.kassa.domain.OmborTovar> found = tovarRepo.search(uz.kassa.domain.OmborTovar.norm(what));
                uz.kassa.domain.OmborTovar exact = found.stream().filter(t -> t.getArticle().equalsIgnoreCase(what) || t.getCode().equalsIgnoreCase(what) || t.getName().equalsIgnoreCase(what)).findFirst().orElse(null);
                if (exact == null && found.size() == 1) exact = found.get(0);
                if (exact == null) throw new IllegalArgumentException(found.isEmpty() ? "tovar topilmadi: " + what : found.size() + " ta tovar mos keldi, aniqroq yozing (artikul)");
                d.setProductMsId(exact.getMsId());
            }
            davrRepo.save(d);
            audit.log(u.getId(), "OMBOR_DAVR", "ombor_davr", d.getId(), kind + " " + what + " " + from + "–" + to);
            sender.send(chatId, "✅ Davr saqlandi: " + kind + " · " + esc(what) + " · " + from + " – " + to);
        } catch (Exception e) {
            sender.send(chatId, "⚠️ " + esc(String.valueOf(e.getMessage())) + "\nFormat: <code>AKSIYA; artikul; 01.10.2026; 15.10.2026; KOD</code>");
        }
        davrList(s, chatId, 0);
    }


    /* ---------- 📣 hisobot oluvchilar ---------- */

    private void hisobotPick(long chatId, int msgId) {
        Set<Long> cur = cfg.hisobotIds();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc())
            rows.add(irow(btn(cut((cur.contains(x.getId()) ? "✅ " : "▫️ ") + x.getFullName() + (x.getTelegramId() == null ? " · 🟡" : ""), 55), "a:omht:" + x.getId())));
        rows.add(irow(btn("⬅️ Orqaga", BACK)));
        show(chatId, msgId, "📣 <b>Ombor hisoboti oluvchilari</b>\n\n🔔 Билдиришномалар → 📚 Namunalar → «🏬 Омбор кунлик/ҳафталик» shablonini yoqing; "
                + "oluvchilar shablonda <code>user:ID</code> yoki rol bilan beriladi. Bu ro'yxat {ombor.*} hisobot uchun mo'ljallangan odamlar (Zufar):", inline(rows));
    }


    /* ---------- 📏 qoidalar ---------- */

    private void rules(long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (OmborQoida r : ruleRepo.findAllByOrderBySortAscCodeAsc())
            rows.add(irow(btn((r.isEnabled() ? r.emoji() + " " : "⚪ ") + cut(r.getTitle(), 50), "a:omkv:" + r.getCode())));
        rows.add(irow(btn("⬅️ Orqaga", BACK)));
        show(chatId, msgId, "📏 <b>Ombor qoidalari</b>\n\nHar qoida — jadval qatori: tekshiruvchi + parametrlar + kimga + eskalatsiya. "
                + "🔴 muhim · 🟠 ogohlantirish · ℹ️ jim (faqat ro'yxat) · ⚪ o'chiq.\n\nTanlang:", inline(rows));
    }

    private void ruleCard(Session s, String code, long chatId, int msgId) {
        OmborQoida r = rule(code).orElse(null);
        if (r == null) { rules(chatId, msgId); return; }
        OmborChecker c = engine.checker(r.getChecker());
        StringBuilder sb = new StringBuilder(r.emoji() + " <b>" + esc(r.getTitle()) + "</b> <code>" + r.getCode() + "</code>\n\n");
        sb.append(r.isEnabled() ? "🟢 Yoqilgan" : "⚪ O'chirilgan").append("\n");
        sb.append("🧩 Tekshiruvchi: <b>").append(r.getChecker()).append("</b>").append(c == null ? " ⚠️ topilmadi" : "").append("\n");
        if (c != null) sb.append("<i>").append(esc(c.help())).append("</i>\n");
        sb.append("⚙️ params: <code>").append(esc(r.getParams())).append("</code>\n");
        sb.append("🎚 Darajasi: <b>").append(r.getSeverity()).append("</b> · 👤 Kimga: <b>").append(OmborConfig.roleTitle(r.getToRole())).append("</b>\n");
        sb.append("⏰ Rahbarga: <b>").append(r.getEsc1Min() == 0 ? "yo'q" : r.getEsc1Min() + " min").append("</b> · ❌ Adminga: <b>")
          .append(r.getEsc2Min() == 0 ? "yo'q" : r.getEsc2Min() + " min").append("</b>\n");
        sb.append("⚠️ Ochiq: <b>").append(countOpen(code)).append("</b>");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn(r.isEnabled() ? "⏸ O'chirish" : "▶️ Yoqish", "a:omkt:" + code), btn("🎚 " + r.getSeverity(), "a:omks:" + code), btn("👤 Kimga", "a:omkr:" + code)));
        rows.add(irow(btn("⚙️ params", "a:omv:params:" + code), btn("✏️ Nom", "a:omv:title:" + code)));
        rows.add(irow(btn("⏰ Rahbar (min)", "a:omv:esc1:" + code), btn("❌ Admin (min)", "a:omv:esc2:" + code)));
        rows.add(irow(btn("🔎 Hozir tekshirish", "a:omkn:" + code), btn("⬅️ Qoidalar", "a:omk")));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    private long countOpen(String code) { return engine.openByRule(code); }


    /* ---------- 👥 rollar ---------- */

    private void roles(long chatId, int msgId) {
        StringBuilder sb = new StringBuilder("👥 <b>Ombor rollari</b>\n\nQoidadagi «kimga» shu ro'yxatdan oluvchini topadi: avval do'kon bo'yicha, "
                + "bo'lmasa umumiy, bo'lmasa otdel rahbari, bo'lmasa SuperAdmin.\n\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String role : OmborConfig.ROLES) {
            rows.add(irow(btn(OmborConfig.roleTitle(role) + " · umumiy (" + cfg.roleUsersExact(role, 0).size() + ")", "a:omrr:" + role + ".0")));
            for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
                if (k.isCashless() || role.equals("DIREKTOR")) continue;
                rows.add(irow(btn("   " + cut(k.getName(), 24) + " (" + cfg.roleUsersExact(role, k.getId()).size() + ")", "a:omrr:" + role + "." + k.getId())));
            }
        }
        rows.add(irow(btn("⬅️ Orqaga", BACK)));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    /** arg: ROLE.kassaId */
    private void rolePick(String arg, long chatId, int msgId) {
        String[] p = arg.split("\\.");
        String role = p[0]; long kassa = Long.parseLong(p[1]);
        Set<Long> cur = cfg.roleUsersExact(role, kassa);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc()) {
            if (kassa > 0 && x.getKassaId() != null && !x.getKassaId().equals(kassa) && !cur.contains(x.getId())) continue;
            String label = (cur.contains(x.getId()) ? "✅ " : "▫️ ") + x.getFullName()
                    + (x.getJobTitle() == null || x.getJobTitle().isBlank() ? "" : " · " + x.getJobTitle())
                    + (x.getTelegramId() == null ? " · 🟡" : "");
            rows.add(irow(btn(cut(label, 55), "a:omrt:" + arg + "." + x.getId())));
        }
        rows.add(irow(btn("⬅️ Rollar", "a:omr")));
        show(chatId, msgId, "👥 <b>" + OmborConfig.roleTitle(role) + "</b> — " + (kassa == 0 ? "umumiy (hamma do'kon)" : esc(kassaRepo.findById(kassa).map(Kassa::getName).orElse("?")))
                + "\n\n✅ — oladi, ▫️ — olmaydi (🟡 Telegram ulanmagan):", inline(rows));
    }


    /* ---------- 🏪 ombor ↔ do'kon ---------- */

    private void stores(Session s, long chatId, int msgId) {
        List<OmborStore> list = storeRepo.findAllByOrderByNameAsc();
        List<String> ids = new ArrayList<>();
        StringBuilder sb = new StringBuilder("🏪 <b>MoySklad omborlari ↔ do'konlar</b>\n\n"
                + "Qoldiqlar shu bog'lanish orqali do'konga yoziladi. Savdo nuqtasi (retailstore) orqali avtomatik topiladi; "
                + "topilmasa — bosib tanlang.\n\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (list.isEmpty()) sb.append("Omborlar hali o'qilmagan — 🔄 Hozir sinxron.\n");
        for (OmborStore st : list) {
            ids.add(st.getMsId());
            String k = st.getKassaId() == null ? "—" : kassaRepo.findById(st.getKassaId()).map(Kassa::getName).orElse("#" + st.getKassaId());
            rows.add(irow(btn((st.isArchived() ? "🗄 " : "") + cut(st.getName(), 24) + " → " + cut(k, 20), "a:omsp:" + (ids.size() - 1))));
        }
        s.data.put("omStores", ids);
        rows.add(irow(btn("⬅️ Orqaga", BACK)));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    private void storePick(Session s, int idx, long chatId, int msgId) {
        Object o = s.data.get("omStores");
        if (!(o instanceof List<?> l) || idx < 0 || idx >= l.size()) { stores(s, chatId, msgId); return; }
        OmborStore st = storeRepo.findById(String.valueOf(l.get(idx))).orElse(null);
        if (st == null) { stores(s, chatId, msgId); return; }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn((k.getId().equals(st.getKassaId()) ? "✅ " : "▫️ ") + k.getName(), "a:omsk:" + idx + "." + k.getId())));
        rows.add(irow(btn("🚫 Bog'lamaslik", "a:omsk:" + idx + ".0"), btn("⬅️ Orqaga", "a:oms")));
        show(chatId, msgId, "🏪 <b>" + esc(st.getName()) + "</b>\n<code>" + st.getMsId() + "</code>\n\nQaysi do'kon (kassa)?", inline(rows));
    }


    /* ---------- yordamchi ---------- */

    private java.util.Optional<OmborQoida> rule(String code) { return ruleRepo.findById(code); }

    private static String next(List<String> opts, String cur) {
        int i = opts.indexOf(cur);
        return opts.get((i + 1) % opts.size());
    }

    private static int range(String t, int min, int max) {
        int v = Integer.parseInt(t.replaceAll("\\D", ""));
        if (v < min || v > max) throw new IllegalArgumentException("oraliq " + min + "–" + max);
        return v;
    }

    private static String cut(String s, int n) { return s.length() > n ? s.substring(0, n - 1) + "…" : s; }

    private void show(long chatId, int msgId, String text, InlineKeyboardMarkup kb) {
        if (msgId > 0) sender.edit(chatId, msgId, text, kb);
        else sender.send(chatId, text, kb);
    }
}
