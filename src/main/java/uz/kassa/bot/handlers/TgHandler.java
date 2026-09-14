package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.Sender;
import uz.kassa.bot.Session;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Kassa;
import uz.kassa.domain.Role;
import uz.kassa.domain.TgAkkaunt;
import uz.kassa.domain.TgXabar;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.service.AuditService;
import uz.kassa.service.tg.TgReaderConfig;
import uz.kassa.service.tg.TgReaderService;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.esc;
import static uz.kassa.bot.TextUtil.fmt;

/**
 * 📨 Bot xabarlari — ko'rish (tg:*) va SuperAdmin sozlamasi (⚙️ Настройка → 🔗 MoySklad → 📨 Бот хабарлари, a:tg*).
 * Ulangan akkauntlar TDLib Java mijozi orqali (docs/BOT-XABARLARI.md). Bu yerda faqat jurnal, tekshiruv sozlamasi va ro'yxat.
 */
@Component
@RequiredArgsConstructor
public class TgHandler {

    public static final String LABEL = "📨 Бот хабарлари";
    private static final String BACK = "a:tg";
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final int PAGE = 8;

    private final Sender sender;
    private final TgReaderService svc;
    private final uz.kassa.service.tg.TgCardReport cardReport;
    private final uz.kassa.repo.TgCardRepo cardRepo;
    private final TgReaderConfig cfg;
    private final KassaRepo kassaRepo;
    private final AppUserRepo userRepo;
    private final AuditService audit;
    private final org.springframework.beans.factory.ObjectProvider<uz.kassa.service.tg.TgAccountGateway> gatewayProvider;

    private uz.kassa.service.tg.TgAccountGateway gw() { return gatewayProvider.getIfAvailable(); }

    /* ==================== 📨 ko'rish (tg:*) ==================== */

    public boolean onCallback(AppUser u, Session s, String data, long chatId, int msgId) {
        if (!data.startsWith("tg:")) return false;
        String[] p = data.split(":", 3);
        String cmd = p[1];
        String arg = p.length > 2 ? p[2] : "";
        // xodim (har rol) uchun: o'z akkauntini ulash
        switch (cmd) {
            case "my" -> { myScreen(u, s, chatId, msgId); return true; }
            case "add" -> { askPhone(u, s, chatId, msgId); return true; }
            case "dc" -> { disconnectOwn(u, s, arg, chatId, msgId); return true; }
            case "cancel" -> { String ph = s.getStr("tgPhone"); if (gw() != null && ph != null) gw().cancelLogin(ph); s.reset(); myScreen(u, s, chatId, msgId); return true; }
            default -> { }
        }
        // qolgani — buxgalter/admin
        if (u.getRole() == Role.KASSIR) { sender.edit(chatId, msgId, "⛔ Bu bo'lim sizga ochiq emas — /akkaunt bilan o'z akkauntingizni ulang."); return true; }
        switch (cmd) {
            case "m" -> list(null, "FLAG", chatId, msgId);
            case "all" -> list(null, "ALL", chatId, msgId);
            case "acc" -> list(arg, "ACC", chatId, msgId);
            case "x" -> detail(Long.parseLong(arg), chatId, msgId);
            case "c" -> cards(u, chatId, msgId);
            case "cr" -> { cardReport.reportNow(); cards(u, chatId, msgId); }
            default -> { return false; }
        }
        return true;
    }

    private void list(String phone, String mode, long chatId, int msgId) {
        long[] st = svc.stats();
        List<TgXabar> rows = svc.recent(phone, mode.equals("FLAG") ? "FLAG" : null, 30);
        StringBuilder sb = new StringBuilder("📨 <b>Bot xabarlari</b>");
        if (phone != null) { TgAkkaunt a = svc.account(phone); sb.append(" — ").append(esc(a == null ? phone : (a.getName().isBlank() ? phone : a.getName()))); }
        sb.append("\n\n");
        sb.append("Akkaunt: <b>").append(st[0]).append("</b> · jami xabar <b>").append(st[1]).append("</b> · ⚠️ belgilangan <b>").append(st[2]).append("</b> · bugun <b>").append(st[3]).append("</b>\n");
        if (!cfg.enabled()) sb.append("⚪ Modul o'chirilgan (⚙️ → 🔗 MoySklad → 📨 Бот хабарлари)\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        if (rows.isEmpty()) sb.append(mode.equals("FLAG") ? "Belgilangan xabar yo'q." : "Xabar yo'q.");
        List<List<InlineKeyboardButton>> kb = new ArrayList<>();
        int n = 0;
        for (TgXabar x : rows) {
            if (++n > PAGE) break;
            kb.add(irow(btn(mark(x.getVerdict()) + " " + (x.getMsgAt() == null ? "" : DTF.format(x.getMsgAt())) + " · " + cut(oneLine(x.getText()), 34), "tg:x:" + x.getId())));
        }
        List<InlineKeyboardButton> tabs = new ArrayList<>();
        tabs.add(btn((mode.equals("FLAG") ? "✅ " : "") + "⚠️ Belgilangan", "tg:m"));
        tabs.add(btn((mode.equals("ALL") ? "✅ " : "") + "Hammasi", "tg:all"));
        kb.add(tabs);
        kb.add(irow(btn("💳 Karta qoldiqlari", "tg:c")));
        List<InlineKeyboardButton> accs = new ArrayList<>();
        for (TgAkkaunt a : svc.accounts()) {
            accs.add(btn((online(a) ? "🟢 " : "🔴 ") + cut(a.getName().isBlank() ? a.getPhone() : a.getName(), 14), "tg:acc:" + a.getPhone()));
            if (accs.size() == 2) { kb.add(new ArrayList<>(accs)); accs.clear(); }
        }
        if (!accs.isEmpty()) kb.add(accs);
        kb.add(irow(btn("⬅️ Настройка", "a:p:set")));
        sender.edit(chatId, msgId, sb.toString(), inline(kb));
    }

    private void detail(long id, long chatId, int msgId) {
        TgXabar x = svc.message(id);
        if (x == null) { sender.edit(chatId, msgId, "⚠️ Topilmadi.", inline(List.of(irow(btn("⬅️ Ro'yxat", "tg:m"))))); return; }
        TgAkkaunt a = svc.account(x.getPhone());
        StringBuilder sb = new StringBuilder(mark(x.getVerdict()) + " <b>Bot xabari</b> — " + esc(x.getSourceBot()) + "\n");
        sb.append("👤 ").append(esc(a == null ? x.getPhone() : (a.getName().isBlank() ? a.getPhone() : a.getName()))).append("\n");
        sb.append("🕒 ").append(x.getMsgAt() == null ? "—" : DTF.format(x.getMsgAt()));
        if (x.getAmount() != null) sb.append(" · 💵 ").append(fmt(x.getAmount())).append(" so'm");
        sb.append("\n");
        if (!x.getNote().isBlank()) sb.append("📋 ").append(esc(x.getNote())).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n").append(esc(x.getText()));
        sender.edit(chatId, msgId, sb.toString(), inline(List.of(irow(btn("⬅️ Ro'yxat", "tg:m")))));
    }

    /** 💳 Karta qoldiqlari (bot avtomat yig'gan). */
    private void cards(AppUser u, long chatId, int msgId) {
        StringBuilder sb = new StringBuilder(cardReport.text());
        if (!cfg.enabled()) sb.append("\n\n⚪ Modul o'chirilgan.");
        List<List<InlineKeyboardButton>> kb = new ArrayList<>();
        if (u.getRole() != Role.KASSIR) kb.add(irow(btn("📤 Guruhga yuborish", "tg:cr")));
        kb.add(irow(btn("📨 Xabarlar", "tg:m"), btn("⬅️ Настройка", "a:p:set")));
        sender.edit(chatId, msgId, sb.toString(), inline(kb));
    }

    /* ==================== 🔗 xodim: o'z akkaunti ==================== */

    /** /akkaunt yoki 🔗 tugma: xodimning o'z akkaunt(lar)i holati va ulash. */
    public void myScreen(AppUser u, Session s, long chatId, int msgId) {
        s.reset();
        List<TgAkkaunt> mine = svc.accounts().stream().filter(a -> u.getId().equals(a.getUserId())).toList();
        StringBuilder sb = new StringBuilder("🔗 <b>Akkaunt ulash</b>\n\n");
        if (gw() == null || !gw().available())
            sb.append("⚠️ Serverda hali yoqilmagan (TG_API_ID/HASH kerak). Admin sozlaydi.\n");
        else if (mine.isEmpty())
            sb.append("Sizda ulangan akkaunt yo'q.\n<i>«➕ Ulash» → telefon raqamingizni kiriting → Telegram'dan kelgan kodni kiriting. Shundan so'ng bot o'sha akkauntga kelgan " + esc(cfg.sourceBot().isBlank() ? "bot" : "@" + cfg.sourceBot()) + " xabarlarini o'qiy boshlaydi.</i>\n");
        else for (TgAkkaunt a : mine) {
            sb.append(online(a) ? "🟢 " : "🔴 ").append("<b>").append(esc(a.getPhone())).append("</b>")
              .append(a.isActive() ? "" : " · ⏸ o'chirilgan").append("\n");
            if (a.getLastError() != null) sb.append("   ⚠️ ").append(esc(a.getLastError())).append("\n");
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        boolean can = gw() != null && gw().available();
        if (can) rows.add(irow(btn("➕ Ulash", "tg:add")));
        for (TgAkkaunt a : mine) rows.add(irow(btn("🔌 Uzish · " + a.getPhone(), "tg:dc:" + a.getPhone())));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    private void askPhone(AppUser u, Session s, long chatId, int msgId) {
        if (gw() == null || !gw().available()) { myScreen(u, s, chatId, msgId); return; }
        s.state = Session.State.TG_PHONE;
        sender.edit(chatId, msgId, "📱 Telefon raqamingizni xalqaro shaklda kiriting (masalan <code>+998901234567</code>):\n"
                + "<i>Shu raqamli Telegram akkauntga " + esc(cfg.sourceBot().isBlank() ? "bot" : "@" + cfg.sourceBot()) + " xabarlari keladi.</i>",
                inline(List.of(irow(btn("❌ Bekor", "tg:cancel")))));
    }

    private void disconnectOwn(AppUser u, Session s, String phone, long chatId, int msgId) {
        TgAkkaunt a = svc.account(phone);
        if (a == null || !u.getId().equals(a.getUserId())) { myScreen(u, s, chatId, msgId); return; }
        if (gw() != null) gw().disconnect(phone);
        svc.deleteAccount(phone);
        audit.log(u.getId(), "TG_UZILDI", "tg_akkaunt", null, phone);
        sender.edit(chatId, msgId, "🔌 Uzildi: " + esc(phone));
        myScreen(u, s, chatId, msgId);
    }

    /** Xodim login matni (telefon/kod/parol). Router har rol uchun shu yerga yo'naltiradi. */
    public boolean onLoginText(AppUser u, Session s, String text, long chatId) {
        var gw = gw();
        if (gw == null || !gw.available()) { s.reset(); sender.send(chatId, "⚠️ Serverda yoqilmagan."); return true; }
        String t = text.trim();
        try {
            if (s.state == Session.State.TG_PHONE) {
                String phone = t.replaceAll("[^+0-9]", "");
                if (!phone.startsWith("+") || phone.length() < 8) { sender.send(chatId, "⚠️ Raqam <code>+998...</code> shaklida bo'lsin. Qayta kiriting:"); return true; }
                s.data.put("tgPhone", phone);
                Object forU = s.data.get("tgForUser");
                long ownerId = forU instanceof Long ? (Long) forU : u.getId();
                sender.send(chatId, "⏳ Telegram'ga so'rov yuborilmoqda…");
                var r = gw.startLogin(phone, ownerId);
                route(u, s, r, chatId);
            } else if (s.state == Session.State.TG_CODE) {
                var r = gw.submitCode(s.getStr("tgPhone"), t.replaceAll("[^0-9]", ""));
                route(u, s, r, chatId);
            } else if (s.state == Session.State.TG_PWD) {
                var r = gw.submitPassword(s.getStr("tgPhone"), t);
                route(u, s, r, chatId);
            } else return false;
        } catch (Exception e) {
            s.reset();
            sender.send(chatId, "⚠️ Xato: " + esc(String.valueOf(e.getMessage())));
        }
        return true;
    }

    private void route(AppUser u, Session s, uz.kassa.service.tg.TgAccountGateway.Result r, long chatId) {
        switch (r.step()) {
            case NEED_CODE -> { s.state = Session.State.TG_CODE; sender.send(chatId, "🔑 Telegram ilovangizga kelgan <b>kodni</b> kiriting:", inline(List.of(irow(btn("❌ Bekor", "tg:cancel"))))); }
            case NEED_PASSWORD -> { s.state = Session.State.TG_PWD; sender.send(chatId, "🔒 Akkauntda 2FA (bulutli parol) yoqilgan — <b>parolni</b> kiriting:", inline(List.of(irow(btn("❌ Bekor", "tg:cancel"))))); }
            case CONNECTED -> { audit.log(u.getId(), "TG_ULANDI", "tg_akkaunt", null, s.getStr("tgPhone")); s.reset();
                sender.send(chatId, "✅ <b>Ulandi!</b> " + esc(s.getStr("tgPhone")) + " — endi bot bu akkauntdagi " + esc(cfg.sourceBot().isBlank() ? "bot" : "@" + cfg.sourceBot()) + " xabarlarini avtomat o'qiydi.",
                        inline(List.of(irow(btn("🔗 Akkauntlarim", "tg:my"))))); }
            case ERROR -> { s.reset(); sender.send(chatId, "⚠️ Ulanmadi: " + esc(r.error() == null ? "noma'lum" : r.error()) + "\n<i>Qayta urinish: /akkaunt</i>"); }
        }
    }

    /* ==================== ⚙️ sozlama (a:tg*) ==================== */

    public boolean adminCallback(AppUser u, Session s, String cmd, String arg, long chatId, int msgId) {
        switch (cmd) {
            case "tg" -> menu(s, chatId, msgId);
            case "tgg" -> { cfg.setEnabled(!cfg.enabled()); audit.log(u.getId(), "TG_" + (cfg.enabled() ? "YOQILDI" : "OCHIRILDI"), "settings", null, ""); menu(s, chatId, msgId); }
            case "tgm2" -> { cfg.toggleMatch(); menu(s, chatId, msgId); }
            case "tgv" -> askValue(s, arg, chatId, msgId);
            case "tgacc" -> accountList(chatId, msgId);
            case "tgap" -> accountCard(arg, chatId, msgId);
            case "tgau" -> { bindUser(u, arg, chatId, msgId); }
            case "tgak" -> { bindKassa(u, arg, chatId, msgId); }
            case "tgen" -> { if (gw() != null) gw().reconnect(arg); else svc.setActive(arg, true); audit.log(u.getId(), "TG_YOQILDI", "tg_akkaunt", null, arg); accountCard(arg, chatId, msgId); }
            case "tgdis" -> { if (gw() != null) gw().disconnect(arg); else svc.setActive(arg, false); audit.log(u.getId(), "TG_OCHIRILDI", "tg_akkaunt", null, arg); accountCard(arg, chatId, msgId); }
            case "tgadd" -> addPick(u, s, arg, chatId, msgId);
            case "tgrn" -> { int n = cardReport.reportNow(); sender.send(chatId, n == 0 ? "⚠️ Guruh sozlanmagan (тgreader.chat_ids yoki Click guruhi)" : "📤 Karta qoldiqlari " + n + " ta guruhga yuborildi"); menu(s, chatId, 0); }
            default -> { return false; }
        }
        return true;
    }

    public void menu(Session s, long chatId, int msgId) {
        StringBuilder sb = new StringBuilder("📨 <b>Бот хабарлари назорати</b>\n\n");
        sb.append(cfg.enabled() ? "🟢 Yoqilgan" : "⚪ O'chirilgan").append(" · manba bot: <b>").append(esc(cfg.sourceBot().isBlank() ? "—" : cfg.sourceBot())).append("</b>\n");
        long[] st = svc.stats();
        sb.append("🔗 Akkaunt: <b>").append(st[0]).append("</b> · jami xabar <b>").append(st[1]).append("</b>\n");
        sb.append("🔔 Kalit so'zlar: <b>").append(cfg.keywords().isEmpty() ? "—" : esc(String.join(", ", cfg.keywords()))).append("</b>\n");
        sb.append("💵 Summani solishtirish: <b>").append(cfg.match() ? "ON" : "OFF").append("</b>").append(cfg.matchTol() > 0 ? " · farq " + fmt(cfg.matchTol()) : "").append("\n");
        sb.append("🔇 Jimlik: <b>").append(cfg.silenceHours()).append("</b> soat\n");
        sb.append("💳 Kartalar: <b>").append(cardRepo.count()).append("</b> · guruh hisoboti: <b>")
          .append(cfg.reportEveryH() == 0 ? "o'chiq" : "har " + cfg.reportEveryH() + " soat " + cfg.reportFrom() + "–" + cfg.reportTo()).append("</b> · chat <b>")
          .append(cfg.reportChatIds().size()).append("</b>\n");
        sb.append("\n<i>Akkauntni ulash: serverda TgLoginMain (docs/BOT-XABARLARI.md).</i>");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn(cfg.enabled() ? "⏸ O'chirish" : "▶️ Yoqish", "a:tgg"), btn("🔗 Akkauntlar", "a:tgacc")));
        rows.add(irow(btn("🔔 Kalit so'zlar", "a:tgv:kw"), btn("🔇 Jimlik (soat)", "a:tgv:silence")));
        rows.add(irow(btn(cfg.match() ? "💵 Solishtirish: ON" : "💵 Solishtirish: OFF", "a:tgm2"), btn("± Farq (so'm)", "a:tgv:tol")));
        rows.add(irow(btn("⏰ Hisobot interval", "a:tgv:rep"), btn("📤 Hisobotni hozir", "a:tgrn")));
        rows.add(irow(btn("💳 Karta qoldiqlari", "tg:c"), btn("📨 Xabarlar", "tg:m")));
        rows.add(irow(btn("⬅️ Orqaga", "a:p:set")));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    private void accountList(long chatId, int msgId) {
        StringBuilder sb = new StringBuilder("🔗 <b>Ulangan akkauntlar</b>\n\n");
        List<TgAkkaunt> all = svc.accounts();
        boolean can = gw() != null && gw().available();
        if (all.isEmpty()) sb.append(can
                ? "Hali akkaunt ulanmagan.\n<i>«➕ Akkaunt qo'shish» — xodimni tanlab, uning telefoni va Telegram kodini kiritasiz. Xodim o'zi ham /akkaunt bilan ulay oladi.</i>"
                : "⚠️ Avval 🔑 API kalitini kiriting (⚙️ 📨 → 🔑 API), so'ng bu yerdan qo'shasiz.");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (TgAkkaunt a : all)
            rows.add(irow(btn((!a.isActive() ? "⏸ " : online(a) ? "🟢 " : "🔴 ") + cut((a.getName().isBlank() ? a.getPhone() : a.getName()) + " · " + a.getPhone(), 30), "a:tgap:" + a.getPhone())));
        if (can) rows.add(irow(btn("➕ Akkaunt qo'shish", "a:tgadd")));
        else rows.add(irow(btn("🔑 API kalitini kiritish", "a:tgv:api")));
        rows.add(irow(btn("⬅️ Orqaga", BACK)));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    /** Admin ➕: qaysi xodim uchun akkaunt ulanadi (yoki o'zi). arg — bo'sh (ro'yxat) | userId. */
    private void addPick(AppUser u, Session s, String arg, long chatId, int msgId) {
        if (gw() == null || !gw().available()) { accountList(chatId, msgId); return; }
        if (!arg.isBlank()) {
            s.data.put("tgForUser", "0".equals(arg) ? u.getId() : Long.parseLong(arg));
            askPhone(u, s, chatId, msgId);
            return;
        }
        StringBuilder sb = new StringBuilder("➕ <b>Akkaunt qo'shish</b>\n\nBu akkaunt qaysi xodimniki? (Telegram kodi o'sha telefonga keladi)");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("👤 O'zim (admin)", "a:tgadd:0")));
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc())
            rows.add(irow(btn(cut(x.getFullName(), 34) + (x.getKassaId() == null ? "" : " · " + kassaRepo.findById(x.getKassaId()).map(Kassa::getName).orElse("")), "a:tgadd:" + x.getId())));
        rows.add(irow(btn("⬅️ Akkauntlar", "a:tgacc")));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    private void accountCard(String phone, long chatId, int msgId) {
        TgAkkaunt a = svc.account(phone);
        if (a == null) { menu(null, chatId, msgId); return; }
        StringBuilder sb = new StringBuilder("🔗 <b>").append(esc(a.getName().isBlank() ? phone : a.getName())).append("</b>\n\n");
        sb.append("📱 ").append(esc(a.getPhone())).append(a.getUsername().isBlank() ? "" : " · @" + esc(a.getUsername())).append("\n");
        sb.append(!a.isActive() ? "⏸ O'chirilgan" : online(a) ? "🟢 Ulangan" : "🔴 Uzilgan").append(a.getLastSeenAt() == null ? "" : " · oxirgi " + DTF.format(a.getLastSeenAt().atZone(ZoneId.of("Asia/Tashkent")).toLocalDateTime())).append("\n");
        if (a.getLastError() != null) sb.append("⚠️ ").append(esc(a.getLastError())).append("\n");
        sb.append("👷 Xodim: <b>").append(a.getUserId() == null ? "bog'lanmagan" : esc(userRepo.findById(a.getUserId()).map(AppUser::getFullName).orElse("?"))).append("</b>\n");
        sb.append("🏪 Do'kon: <b>").append(a.getKassaId() == null ? "—" : esc(kassaRepo.findById(a.getKassaId()).map(Kassa::getName).orElse("?"))).append("</b> (summa solishtirish uchun)\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("👷 Xodimga bog'lash", "a:tgau:" + phone), btn("🏪 Do'konga bog'lash", "a:tgak:" + phone)));
        rows.add(irow(a.isActive() ? btn("⏸ O'chirish", "a:tgdis:" + phone) : btn("▶️ Yoqish", "a:tgen:" + phone), btn("📨 Xabarlari", "tg:acc:" + phone)));
        rows.add(irow(btn("⬅️ Akkauntlar", "a:tgacc")));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    /** Xodimga bog'lash: faol, tg ulangan xodimlar ro'yxati (arg — phone[.userId]). */
    private void bindUser(AppUser actor, String arg, long chatId, int msgId) {
        String[] pa = arg.split("\\.");
        String phone = pa[0];
        if (pa.length > 1) {
            svc.account(phone); TgAkkaunt a = svc.account(phone);
            if (a != null) { a.setUserId("0".equals(pa[1]) ? null : Long.parseLong(pa[1])); svc.saveAccount(a); audit.log(actor.getId(), "TG_XODIM", "tg_akkaunt", null, arg); }
            accountCard(phone, chatId, msgId); return;
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("🚫 Bog'lamaslik", "a:tgau:" + phone + ".0")));
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc())
            if (x.getTelegramId() != null) rows.add(irow(btn(cut(x.getFullName(), 32) + (x.getKassaId() == null ? "" : " · " + kassaRepo.findById(x.getKassaId()).map(Kassa::getName).orElse("")), "a:tgau:" + phone + "." + x.getId())));
        rows.add(irow(btn("⬅️ Orqaga", "a:tgap:" + phone)));
        show(chatId, msgId, "👷 <b>Xodimni tanlang</b> — " + esc(phone), inline(rows));
    }

    private void bindKassa(AppUser actor, String arg, long chatId, int msgId) {
        String[] pa = arg.split("\\.");
        String phone = pa[0];
        if (pa.length > 1) {
            TgAkkaunt a = svc.account(phone);
            if (a != null) { a.setKassaId("0".equals(pa[1]) ? null : Long.parseLong(pa[1])); svc.saveAccount(a); audit.log(actor.getId(), "TG_KASSA", "tg_akkaunt", null, arg); }
            accountCard(phone, chatId, msgId); return;
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("🚫 Bog'lamaslik", "a:tgak:" + phone + ".0")));
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn(cut(k.getName(), 32), "a:tgak:" + phone + "." + k.getId())));
        rows.add(irow(btn("⬅️ Orqaga", "a:tgap:" + phone)));
        show(chatId, msgId, "🏪 <b>Do'konni tanlang</b> — " + esc(phone), inline(rows));
    }

    private void askValue(Session s, String key, long chatId, int msgId) {
        String prompt = switch (key) {
            case "kw" -> "🔔 OGOH beradigan kalit so'zlar (vergul bilan). Xabar matnida shu so'z bo'lsa darhol ogohlantirish.\nMasalan: <code>бекор, қайтарилди, возврат</code>\nHozir: " + (cfg.keywords().isEmpty() ? "—" : String.join(", ", cfg.keywords()));
            case "silence" -> "🔇 Necha soat xabar/ulanish kelmasa ogohlantirilsin? (1–168)\nHozir: " + cfg.silenceHours();
            case "tol" -> "± Summani solishtirishda ruxsat etilgan farq (so'm, 0 — aniq)\nHozir: " + fmt(cfg.matchTol());
            case "api" -> "🔑 TDLib api_id va api_hash (my.telegram.org → API development tools), bo'sh joy bilan:
<code>123456 0123456789abcdef0123456789abcdef</code>
Hozir: " + (cfg.apiId() > 0 ? cfg.apiId() + " · hash ****" : "yo'q");
            case "source" -> "🤖 Qaysi bot xabarlari o'qilsin (@ siz)? Masalan <code>HUMOcardbot</code>
Hozir: " + (cfg.sourceBot().isBlank() ? "yo'q" : "@" + cfg.sourceBot());
            case "rep" -> "⏰ Karta qoldiqlari guruh hisoboti: <b>интервал соатлар</b> ва <b>ойна</b>, масалан <code>3 8 22</code> (ҳар 3 соат, 8–22). Интервал 0 — ўчиқ. Гуруҳ: tgreader.chat_ids ёки Click гуруҳи.\nҲозир: " + cfg.reportEveryH() + " " + cfg.reportFrom() + " " + cfg.reportTo();
            default -> null;
        };
        if (prompt == null) { menu(s, chatId, msgId); return; }
        s.state = Session.State.ADM_TG_VAL;
        s.data.put("tgKey", key);
        sender.edit(chatId, msgId, prompt, inline(List.of(irow(btn("❌ Bekor", BACK)))));
    }

    public void onText(AppUser u, Session s, String text, long chatId) {
        String key = s.getStr("tgKey");
        s.reset();
        String t = text.trim();
        try {
            if ("kw".equals(key)) cfg.setKeywords(t.equals("-") ? "" : t);
            else if ("silence".equals(key)) cfg.set(TgReaderConfig.SILENCE_H, String.valueOf(Math.max(1, Math.min(168, Integer.parseInt(t.replaceAll("\\D", ""))))));
            else if ("tol".equals(key)) cfg.set(TgReaderConfig.MATCH_TOL, String.valueOf(Long.parseLong(t.replaceAll("\\D", "").isEmpty() ? "0" : t.replaceAll("\\D", ""))));
            else if ("api".equals(key)) {
                String[] a = t.split("[\s,]+");
                if (a.length < 2) throw new IllegalArgumentException("api_id va api_hash kerak (bo'sh joy bilan)");
                cfg.setApi(Integer.parseInt(a[0].replaceAll("\D", "")), a[1]);
            }
            else if ("source".equals(key)) cfg.setSourceBot(t);
            else if ("rep".equals(key)) {
                String[] a = t.split("[\\s,]+");
                cfg.set(TgReaderConfig.REPORT_EVERY_H, String.valueOf(Math.max(0, Math.min(24, Integer.parseInt(a[0])))));
                if (a.length > 1) cfg.set(TgReaderConfig.REPORT_FROM, String.valueOf(Math.max(0, Math.min(23, Integer.parseInt(a[1])))));
                if (a.length > 2) cfg.set(TgReaderConfig.REPORT_TO, String.valueOf(Math.max(0, Math.min(23, Integer.parseInt(a[2])))));
            }
            else { menu(s, chatId, 0); return; }
            audit.log(u.getId(), "TG_SOZLAMA", "settings", null, key + "=" + t);
            sender.send(chatId, "✅ Saqlandi");
        } catch (Exception e) {
            sender.send(chatId, "⚠️ Qiymat noto'g'ri: " + esc(t));
        }
        menu(s, chatId, 0);
    }

    /* ==================== yordamchi ==================== */

    private boolean online(TgAkkaunt a) {
        return a.getLastSeenAt() != null && a.getLastSeenAt().isAfter(Instant.now().minusSeconds(cfg.silenceHours() * 3600L));
    }
    private static String mark(String v) {
        return switch (v) { case "OGOH" -> "🔔"; case "NOMOS" -> "⚠️"; case "MOS" -> "✅"; case "OK" -> "▫️"; default -> "•"; };
    }
    private static String oneLine(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").trim(); }
    private static String cut(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n - 1) + "…" : s; }
    private void show(long chatId, int msgId, String text, InlineKeyboardMarkup kb) {
        if (msgId == 0) sender.send(chatId, text, kb); else sender.edit(chatId, msgId, text, kb);
    }
}
