package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.Sender;
import uz.kassa.bot.Session;
import uz.kassa.bot.StyledButton;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Jarima;
import uz.kassa.domain.Jarima.Holat;
import uz.kassa.domain.Jarima.Tur;
import uz.kassa.domain.Role;
import uz.kassa.repo.JarimaRepo;
import uz.kassa.service.AuditService;
import uz.kassa.service.BusinessException;
import uz.kassa.service.control.ControlNotifier;
import uz.kassa.service.jarima.JarimaConfig;
import uz.kassa.service.jarima.JarimaService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.esc;
import static uz.kassa.bot.TextUtil.fmt;

/**
 * ⚖️ Жарималар (docs/JARIMA.md):
 *  • SuperAdmin: ⚙️ Настройка → 🔗 MoySklad → ⚖️ Жарималар — sozlamalar, filtrli ro'yxat, karta, yopish/bekor, Excel (callback a:jr*).
 *  • Xodim: xabardagi «📋 Жарималарим» tugmasi — o'z jarimalari (callback jr:my:<kun>).
 */
@Component
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class JarimaHandler {

    public static final String LABEL = "⚖️ Жарималар";
    private static final String BACK = "a:jr";
    private static final int PAGE = 8;
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final Sender sender;
    private final JarimaConfig cfg;
    private final JarimaService svc;
    private final JarimaRepo repo;
    private final AuditService audit;
    private final ControlNotifier notifier;
    private final uz.kassa.webapp.ExcelReportService excel;

    /* ==================== ADMIN (a:jr*) ==================== */

    public boolean adminCallback(AppUser u, Session s, String cmd, String arg, long chatId, int msgId) {
        if (u.getRole() != Role.SUPERADMIN) { sender.send(chatId, "⚠️ Faqat SuperAdmin uchun."); return true; }
        switch (cmd) {
            case "jr" -> menu(s, chatId, msgId);
            case "jrg" -> { cfg.setEnabled(!cfg.enabled()); audit.log(u.getId(), "JARIMA_" + (cfg.enabled() ? "YOQILDI" : "OCHIRILDI"), "settings", null, ""); menu(s, chatId, msgId); }
            case "jrp" -> { cfg.setPaytTuzatilmadi(!cfg.paytTuzatilmadi()); audit.log(u.getId(), "JARIMA_SOZLAMA", "settings", null, "xato_payt=" + (cfg.paytTuzatilmadi() ? "TUZATILMADI" : "TOPILDI")); menu(s, chatId, msgId); }
            case "jrv" -> askValue(s, arg, chatId, msgId);
            case "jrl" -> list(s, Filt.parse(arg), chatId, msgId);
            case "jrc" -> card(s, arg, chatId, msgId);
            case "jry" -> askReason(s, arg, false, chatId, msgId);
            case "jrb" -> askReason(s, arg, true, chatId, msgId);
            case "jrx" -> excel(s, chatId);
            case "jrt" -> { int n = svc.sendDailyNow(); sender.send(chatId, n == 0 ? "ℹ️ Bugun jarima yozuvlari yo'q — jamlama yuborilmadi." : "✅ Kunlik jamlama yuborildi: " + n + " ta yozuv."); }
            case "jrn" -> { }
            default -> { return false; }
        }
        return true;
    }

    public void menu(Session s, long chatId, int msgId) {
        LocalDate today = LocalDate.now(cfg.zone());
        List<Jarima> bugun = repo.findBySanaOrderByIdAsc(today);
        long bugunSum = JarimaService.sumOchiq(bugun) + JarimaService.sumHolat(bugun, Holat.YOPIQ);
        StringBuilder sb = new StringBuilder("⚖️ <b>Jarimalar</b>\n\n");
        sb.append(cfg.enabled() ? "🟢 Yoqilgan" : "⚪ O'chirilgan (yozilmaydi, xabar ketmaydi)").append("\n");
        sb.append("💰 Bazaviy summa (kontragent xatosi asosi): <b>").append(fmt(cfg.bazaviy())).append("</b> so'm\n");
        sb.append("📐 Foiz: karta <b>").append(JarimaConfig.foizText(cfg.foiz(Tur.KARTA))).append("%</b> (oxirgi qoldiqdan) · kontragent <b>")
          .append(JarimaConfig.foizText(cfg.foiz(Tur.KONTRAGENT))).append("%</b> (bazaviydan) · otgruzka <b>")
          .append(JarimaConfig.foizText(cfg.foiz(Tur.OTGRUZKA))).append("%</b> (otgruzka summasidan)\n");
        sb.append("⚠️ Ogohlantirish: xodimning har turdagi birinchi <b>").append(cfg.ogohSoni()).append("</b> ta holati (summasiz), keyingilari jarima\n");
        sb.append("⏱ Xato (kontragent/otgruzka) jarimasi: <b>").append(cfg.paytTuzatilmadi() ? "admin eskalatsiyasida («tuzatilmadi»)" : "topilganda darhol").append("</b>\n");
        sb.append("💳 Karta: Click hisoboti vaqtida qoldiq «янгиланмаган»/«киритилмаган» bo'lsa — mas'ulga (bir epizodda bir marta)\n");
        sb.append("🕘 Kunlik jamlama: <b>").append(cfg.kunVaqt()).append("</b> — xodimga, admin/rahbarga, karta turi guruhga\n");
        sb.append(JarimaService.RULE).append("\n");
        sb.append("Bugun: <b>").append(bugun.size()).append("</b> ta yozuv · <b>").append(fmt(bugunSum)).append("</b> so'm\n");
        sb.append("Jami ochiq (to'lanmagan): <b>").append(fmt(repo.sumByHolat(Holat.OCHIQ))).append("</b> so'm · ")
          .append(repo.countByHolat(Holat.OCHIQ)).append(" ta\n");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn(cfg.enabled() ? "⏸ O'chirish" : "▶️ Yoqish", "a:jrg"), btn("💰 Bazaviy summa", "a:jrv:baz")));
        rows.add(irow(btn("📐 % Karta", "a:jrv:fk"), btn("📐 % Kontragent", "a:jrv:fkg"), btn("📐 % Otgruzka", "a:jrv:fot")));
        rows.add(irow(btn("⚠️ Ogoh soni", "a:jrv:ogoh"), btn(cfg.paytTuzatilmadi() ? "⏱ Payt: eskalatsiya" : "⏱ Payt: darhol", "a:jrp")));
        rows.add(irow(btn("🕘 Jamlama vaqti", "a:jrv:kun"), btn("🧪 Jamlama hozir", "a:jrt")));
        rows.add(irow(btn("📋 Ochiq jarimalar", "a:jrl:" + new Filt(Holat.OCHIQ, null, 0, 0, 0).self()),
                btn("📋 Hammasi (30 kun)", "a:jrl:" + new Filt(null, null, 0, 30, 0).self())));
        rows.add(irow(btn("⬅️ Orqaga", "a:p:set")));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    /* ---------- sozlama qiymatlari ---------- */

    private void askValue(Session s, String key, long chatId, int msgId) {
        String prompt = switch (key) {
            case "baz" -> "💰 Kontragent xatosi uchun hisoblash asosi — bazaviy summa (so'm). Jarima = shu summaning "
                    + JarimaConfig.foizText(cfg.foiz(Tur.KONTRAGENT)) + "%i.\nHozir: " + fmt(cfg.bazaviy()) + "\n\nSummani kiriting:";
            case "fk" -> "📐 Karta qoldig'i jarimasi — oxirgi qoldiqning necha foizi? (0–100, masalan 1 yoki 0.5)\nHozir: " + JarimaConfig.foizText(cfg.foiz(Tur.KARTA)) + "\n\nFoizni kiriting:";
            case "fkg" -> "📐 Kontragent xatosi jarimasi — bazaviy summaning necha foizi? (0–100)\nHozir: " + JarimaConfig.foizText(cfg.foiz(Tur.KONTRAGENT)) + "\n\nFoizni kiriting:";
            case "fot" -> "📐 Otgruzka kamchiligi jarimasi — otgruzka summasining necha foizi? (0–100)\nHozir: " + JarimaConfig.foizText(cfg.foiz(Tur.OTGRUZKA)) + "\n\nFoizni kiriting:";
            case "ogoh" -> "⚠️ Xodimning har turdagi nechta birinchi holati ogohlantirish bilan o'tsin? (0–10; 0 — birinchisidan jarima)\nHozir: " + cfg.ogohSoni() + "\n\nSonni kiriting:";
            case "kun" -> "🕘 Kunlik jamlama vaqti (HH:mm)\nHozir: " + cfg.kunVaqt() + "\n\nVaqtni kiriting:";
            default -> null;
        };
        if (prompt == null) { menu(s, chatId, msgId); return; }
        s.state = Session.State.ADM_JR_VAL;
        s.data.put("jrKey", key);
        sender.edit(chatId, msgId, prompt, inline(List.of(irow(btn("❌ Bekor", BACK)))));
    }

    private void askReason(Session s, String arg, boolean bekor, long chatId, int msgId) {
        long id;
        try { id = Long.parseLong(arg); } catch (NumberFormatException e) { menu(s, chatId, msgId); return; }
        Jarima j = repo.findById(id).orElse(null);
        if (j == null || j.getHolat() != Holat.OCHIQ) { card(s, arg, chatId, msgId); return; }
        s.state = Session.State.ADM_JR_VAL;
        s.data.put("jrKey", (bekor ? "b" : "y") + id);
        String prompt = (bekor ? "❌ <b>Bekor qilish</b> — jarima hisobdan chiqadi (xodimga xabar boradi)." : "✅ <b>Yopish</b> — jarima to'landi/undirildi deb belgilanadi (xodimga xabar boradi).")
                + "\n" + esc(j.getXodim()) + " · " + JarimaService.turTitleLat(j.getTur()) + " · <b>" + fmt(j.getSumma()) + "</b> so'm"
                + "\n\nSababni yozing (yoki «-»):";
        sender.edit(chatId, msgId, prompt, inline(List.of(irow(btn("❌ Bekor", "a:jrc:" + id)))));
    }

    /** ADM_JR_VAL: sozlama qiymati yoki yopish/bekor sababi. */
    public void onText(AppUser u, Session s, String text, long chatId) {
        String key = s.getStr("jrKey");
        s.reset();
        String t = text.trim();
        if (key == null) { menu(s, chatId, 0); return; }
        if (key.startsWith("y") || key.startsWith("b")) {
            try {
                long id = Long.parseLong(key.substring(1));
                Jarima j = svc.close(id, u, t.equals("-") ? null : t, key.startsWith("b"));
                sender.send(chatId, (j.getHolat() == Holat.BEKOR ? "❌ Bekor qilindi: " : "✅ Yopildi: ") + esc(j.getXodim()) + " · <b>" + fmt(j.getSumma()) + "</b> so'm");
            } catch (BusinessException e) { sender.send(chatId, "⚠️ " + esc(e.getMessage())); }
            catch (Exception e) { sender.send(chatId, "⚠️ Bajarilmadi — qaytadan urinib ko'ring."); }
            Filt f = s.getStr("jrF") == null ? new Filt(Holat.OCHIQ, null, 0, 0, 0) : Filt.parse(s.getStr("jrF"));
            list(s, f, chatId, 0);
            return;
        }
        try {
            switch (key) {
                case "baz" -> cfg.set(JarimaConfig.BAZAVIY, String.valueOf(Long.parseLong(t.replaceAll("\\D", ""))));
                case "fk" -> cfg.set(JarimaConfig.FOIZ_KARTA, String.valueOf(pct(t)));
                case "fkg" -> cfg.set(JarimaConfig.FOIZ_KG, String.valueOf(pct(t)));
                case "fot" -> cfg.set(JarimaConfig.FOIZ_OT, String.valueOf(pct(t)));
                case "ogoh" -> { int v = Integer.parseInt(t.replaceAll("\\D", "")); if (v > 10) throw new IllegalArgumentException(); cfg.set(JarimaConfig.OGOH_SONI, String.valueOf(v)); }
                case "kun" -> cfg.set(JarimaConfig.KUN_VAQT, LocalTime.parse(t.length() == 4 ? "0" + t : t).toString());
                default -> { sender.send(chatId, "⚠️ Noma'lum sozlama"); menu(s, chatId, 0); return; }
            }
            audit.log(u.getId(), "JARIMA_SOZLAMA", "settings", null, key + "=" + t);
            sender.send(chatId, "✅ Saqlandi: " + esc(key) + " = " + esc(t));
        } catch (Exception e) {
            sender.send(chatId, "⚠️ Qiymat noto'g'ri: " + esc(t) + ". Qaytadan: ⚙️ Настройка → " + LABEL);
        }
        menu(s, chatId, 0);
    }

    private static double pct(String t) {
        double v = Double.parseDouble(t.replace(',', '.').replaceAll("[^0-9.]", ""));
        if (v < 0 || v > 100) throw new IllegalArgumentException("oraliq");
        return Math.round(v * 100) / 100.0;
    }

    /* ---------- ro'yxat (filtr: holat · tur · xodim · davr) ---------- */

    /** arg: "holat.tur.user.days.page" ("-" = hammasi; days 0 = barcha davr). */
    record Filt(Holat holat, Tur tur, long user, int days, int page) {
        static Filt parse(String arg) {
            Holat h = null; Tur t = null; long u = 0; int d = 0, p = 0;
            if (arg != null && !arg.isBlank()) {
                String[] x = arg.split("\\.");
                try { if (x.length > 0 && !x[0].equals("-")) h = Holat.valueOf(x[0]); } catch (IllegalArgumentException ignored) { }
                try { if (x.length > 1 && !x[1].equals("-")) t = Tur.valueOf(x[1]); } catch (IllegalArgumentException ignored) { }
                try { if (x.length > 2) u = Long.parseLong(x[2]); } catch (NumberFormatException ignored) { }
                try { if (x.length > 3) d = Integer.parseInt(x[3]); } catch (NumberFormatException ignored) { }
                try { if (x.length > 4) p = Integer.parseInt(x[4]); } catch (NumberFormatException ignored) { }
            }
            return new Filt(h, t, u, d, p);
        }
        static String enc(Holat h, Tur t, long u, int d, int p) { return (h == null ? "-" : h.name()) + "." + (t == null ? "-" : t.name()) + "." + u + "." + d + "." + p; }
        String self() { return enc(holat, tur, user, days, page); }
        String withHolat(Holat h) { return enc(h, tur, user, days, 0); }
        String withTur(Tur t) { return enc(holat, t, user, days, 0); }
        String withUser(long u) { return enc(holat, tur, u, days, 0); }
        String withDays(int d) { return enc(holat, tur, user, d, 0); }
        String withPage(int p) { return enc(holat, tur, user, days, p); }
    }

    private static final int[] DAYS = {1, 7, 30, 90, 0};
    private static String daysTitle(int d) { return d == 0 ? "Hammasi" : d == 1 ? "Bugun" : d + " kun"; }

    private void list(Session s, Filt f, long chatId, int msgId) {
        s.data.put("jrF", f.self());
        LocalDate today = LocalDate.now(cfg.zone());
        LocalDate from = f.days == 0 ? null : today.minusDays(f.days - 1);
        // filtr chiplari sanog'i uchun: bittasi olib tashlangan bazalar
        List<Jarima> baseH = svc.list(new JarimaService.Filter(null, f.tur, f.user, null, from, null));
        List<Jarima> baseT = svc.list(new JarimaService.Filter(f.holat, null, f.user, null, from, null));
        List<Jarima> baseU = svc.list(new JarimaService.Filter(f.holat, f.tur, null, null, from, null));
        List<Jarima> list = baseH.stream().filter(j -> f.holat == null || j.getHolat() == f.holat).toList();

        int pages = Math.max(1, (list.size() + PAGE - 1) / PAGE);
        int page = Math.min(f.page, pages - 1);
        int fromI = page * PAGE;
        StringBuilder sb = new StringBuilder("⚖️ <b>Jarimalar</b> (" + (page + 1) + "/" + pages + ")\n\n");
        sb.append("🔎 ").append(f.holat == null ? "barcha holat" : JarimaService.holatTitleLat(f.holat)).append(" · ")
          .append(f.tur == null ? "barcha tur" : JarimaService.turTitleLat(f.tur)).append(" · ")
          .append(f.user == 0 ? "barcha xodim" : esc(notifier.userName(f.user))).append(" · ").append(daysTitle(f.days)).append("\n");
        sb.append("Topildi: <b>").append(list.size()).append("</b> ta · ochiq <b>").append(fmt(JarimaService.sumOchiq(list)))
          .append("</b> so'm · yopilgan ").append(fmt(JarimaService.sumHolat(list, Holat.YOPIQ)))
          .append(" · bekor ").append(fmt(JarimaService.sumHolat(list, Holat.BEKOR)))
          .append(" · ogoh ").append(list.stream().filter(j -> j.getHolat() == Holat.OGOH).count()).append(" ta\n");
        sb.append(JarimaService.RULE).append("\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (list.isEmpty()) sb.append("Yozuvlar yo'q.\n");
        for (int i = fromI; i < Math.min(list.size(), fromI + PAGE); i++) {
            Jarima j = list.get(i);
            String when = LocalDateTime.ofInstant(j.getCreatedAt(), cfg.zone()).format(SHORT);
            sb.append(i + 1).append(". ").append(when).append(" · ").append(JarimaService.turTitleLat(j.getTur()).substring(0, 2)).append(" ")
              .append(esc(j.getXodim())).append(" · ").append(j.getHolat() == Holat.OGOH ? "ogoh" : "<b>" + fmt(j.getSumma()) + "</b>")
              .append(" · ").append(JarimaService.holatTitleLat(j.getHolat()).substring(0, 2)).append("\n")
              .append("   <i>").append(esc(cut(j.getManbaNomi(), 40))).append("</i>\n");
            rows.add(irow(btn((i + 1) + ". " + cut(j.getXodim(), 14) + " · " + (j.getHolat() == Holat.OGOH ? "ogoh" : fmt(j.getSumma())) + " · " + JarimaService.holatTitleLat(j.getHolat()).substring(0, 2), "a:jrc:" + j.getId())));
        }
        if (pages > 1) {
            List<InlineKeyboardButton> nav = new ArrayList<>();
            if (page > 0) nav.add(btn("⬅️ Oldingi", "a:jrl:" + f.withPage(page - 1)));
            nav.add(btn("sahifa " + (page + 1) + "/" + pages, "a:jrn"));
            if (fromI + PAGE < list.size()) nav.add(btn("Keyingi ➡️", "a:jrl:" + f.withPage(page + 1)));
            rows.add(nav);
        }
        final String P = StyledButton.PRIMARY, S = StyledButton.SUCCESS;
        // 🟦 holat
        List<InlineKeyboardButton> hr = new ArrayList<>();
        hr.add(sbtn((f.holat == null ? "✔️ " : "") + "Hammasi", "a:jrl:" + f.withHolat(null), P));
        for (Holat h : Holat.values()) {
            long c = baseH.stream().filter(j -> j.getHolat() == h).count();
            if (c == 0 && h != f.holat) continue;
            hr.add(sbtn((h == f.holat ? "✔️ " : "") + JarimaService.holatTitleLat(h) + " " + c, "a:jrl:" + f.withHolat(h), P));
            if (hr.size() == 3) { rows.add(hr); hr = new ArrayList<>(); }
        }
        if (!hr.isEmpty()) rows.add(hr);
        // 🟩 tur
        List<InlineKeyboardButton> tr = new ArrayList<>();
        tr.add(sbtn((f.tur == null ? "✔️ " : "") + "Barcha tur", "a:jrl:" + f.withTur(null), S));
        for (Tur t : Tur.values()) {
            long c = baseT.stream().filter(j -> j.getTur() == t).count();
            if (c == 0 && t != f.tur) continue;
            tr.add(sbtn((t == f.tur ? "✔️ " : "") + JarimaService.turTitleLat(t).substring(0, 2) + " " + c, "a:jrl:" + f.withTur(t), S));
        }
        rows.add(tr);
        // ⬜ davr
        List<InlineKeyboardButton> dr = new ArrayList<>();
        for (int d : DAYS) dr.add(btn((d == f.days ? "✔️ " : "") + daysTitle(d), "a:jrl:" + f.withDays(d)));
        rows.add(dr);
        // 🟥 xodim (eng ko'p holatlilar, 6 tagacha)
        Map<Long, String> names = new LinkedHashMap<>();
        Map<Long, Integer> cnt = new HashMap<>();
        for (Jarima j : baseU) if (j.getUserId() != null) { names.putIfAbsent(j.getUserId(), j.getXodim()); cnt.merge(j.getUserId(), 1, Integer::sum); }
        List<Long> top = new ArrayList<>(names.keySet());
        top.sort((a, b) -> cnt.get(b) - cnt.get(a));
        List<InlineKeyboardButton> ur = new ArrayList<>();
        ur.add(sbtn((f.user == 0 ? "✔️ " : "") + "Barcha xodim", "a:jrl:" + f.withUser(0), StyledButton.DANGER));
        for (Long uid : top.subList(0, Math.min(6, top.size()))) {
            ur.add(sbtn((uid == f.user ? "✔️ " : "") + cut(names.get(uid), 12) + " " + cnt.get(uid), "a:jrl:" + f.withUser(uid), StyledButton.DANGER));
            if (ur.size() == 3) { rows.add(ur); ur = new ArrayList<>(); }
        }
        if (!ur.isEmpty()) rows.add(ur);
        rows.add(irow(btn("📥 Excel (" + list.size() + ")", "a:jrx"), btn("⬅️ " + LABEL, BACK)));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    private void card(Session s, String arg, long chatId, int msgId) {
        long id;
        try { id = Long.parseLong(arg); } catch (NumberFormatException e) { menu(s, chatId, msgId); return; }
        Jarima j = repo.findById(id).orElse(null);
        if (j == null) { sender.send(chatId, "⚠️ Yozuv topilmadi."); return; }
        String text = "⚖️ <b>Jarima #" + j.getId() + "</b> · " + JarimaService.holatTitleLat(j.getHolat()) + "\n\n" + svc.card(j, false);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (j.getHolat() == Holat.OCHIQ)
            rows.add(irow(sbtn("✅ Yopish (to'landi)", "a:jry:" + id, StyledButton.SUCCESS), sbtn("❌ Bekor qilish", "a:jrb:" + id, StyledButton.DANGER)));
        String back = s.getStr("jrF") == null ? new Filt(null, null, 0, 30, 0).self() : s.getStr("jrF");
        rows.add(irow(btn("⬅️ Ro'yxat", "a:jrl:" + back), btn(LABEL, BACK)));
        show(chatId, msgId, text, inline(rows));
    }

    private void excel(Session s, long chatId) {
        Filt f = s.getStr("jrF") == null ? new Filt(null, null, 0, 30, 0) : Filt.parse(s.getStr("jrF"));
        LocalDate today = LocalDate.now(cfg.zone());
        List<Jarima> list = svc.list(new JarimaService.Filter(f.holat, f.tur, f.user, null, f.days == 0 ? null : today.minusDays(f.days - 1), null));
        if (list.isEmpty()) { sender.send(chatId, "ℹ️ Filtr bo'yicha yozuv yo'q."); return; }
        byte[] data = excel.buildTable("Жарималар", JarimaService.XLS_COLS, svc.xlsRows(list));
        sender.sendDocument(chatId, data, "jarima-" + today + ".xlsx",
                "⚖️ Jarimalar: " + list.size() + " ta · ochiq <b>" + fmt(JarimaService.sumOchiq(list)) + "</b> so'm");
    }

    /* ==================== XODIM (jr:my:<kun>) ==================== */

    public void onCallback(AppUser u, Session s, String data, long chatId, int msgId) {
        String[] p = data.split(":", 3);
        String cmd = p.length > 1 ? p[1] : "";
        if (!cmd.equals("my")) return;
        int days = 30;
        try { if (p.length > 2) days = Integer.parseInt(p[2]); } catch (NumberFormatException ignored) { }
        List<Jarima> list = svc.mine(u, days);
        StringBuilder sb = new StringBuilder("⚖️ <b>ЖАРИМАЛАРИМ</b> — охирги ").append(days).append(" кун\n\n");
        if (list.isEmpty()) sb.append("Ёзувлар йўқ ✅\n");
        int n = 0;
        for (Jarima j : list) {
            if (++n > 15) { sb.append("… яна ").append(list.size() - 15).append(" та\n"); break; }
            sb.append(n).append(". ").append(LocalDateTime.ofInstant(j.getCreatedAt(), cfg.zone()).format(SHORT)).append(" · ")
              .append(JarimaService.turTitle(j.getTur())).append(" · ")
              .append(j.getHolat() == Holat.OGOH ? "огоҳлантириш" : "<b>" + fmt(j.getSumma()) + "</b> сўм")
              .append(" · ").append(JarimaService.holatTitle(j.getHolat())).append("\n   ")
              .append(esc(cut(JarimaService.sababLines(j.getSabab()), 220)).replace("\n", "\n   ")).append("\n");
        }
        sb.append(JarimaService.RULE).append("\n");
        sb.append("Очиқ (тўланмаган): <b>").append(fmt(repo.sumByHolatAndUser(Holat.OCHIQ, u.getId()))).append("</b> сўм\n");
        sb.append("Давр бўйича: очиқ ").append(fmt(JarimaService.sumOchiq(list))).append(" · ёпилган ")
          .append(fmt(JarimaService.sumHolat(list, Holat.YOPIQ))).append(" · бекор ").append(fmt(JarimaService.sumHolat(list, Holat.BEKOR))).append(" сўм\n");
        sb.append("\n<i>Эътироз бўлса бўлим раҳбарига мурожаат қилинг.</i>");
        List<InlineKeyboardButton> dr = new ArrayList<>();
        for (int d : new int[]{7, 30, 90}) dr.add(btn((d == days ? "✔️ " : "") + d + " кун", "jr:my:" + d));
        InlineKeyboardMarkup kb = inline(List.of(dr));
        if (msgId > 0) sender.edit(chatId, msgId, sb.toString(), kb); else sender.send(chatId, sb.toString(), kb);
    }

    /* ==================== yordamchi ==================== */

    private void show(long chatId, int msgId, String text, InlineKeyboardMarkup kb) {
        if (msgId > 0) sender.edit(chatId, msgId, text, kb); else sender.send(chatId, text, kb);
    }

    private static String cut(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
