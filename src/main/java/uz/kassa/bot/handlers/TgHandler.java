package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.Sender;
import uz.kassa.bot.Session;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.ClickAccount;
import uz.kassa.domain.Kassa;
import uz.kassa.domain.Role;
import uz.kassa.domain.TgAkkaunt;
import uz.kassa.domain.TgCard;
import uz.kassa.domain.TgXabar;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.service.AuditService;
import uz.kassa.service.tg.TgAccountGateway;
import uz.kassa.service.tg.TgReaderConfig;
import uz.kassa.service.tg.TgReaderService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.esc;
import static uz.kassa.bot.TextUtil.fmt;

/**
 * 📨 Bot xabarlari — ko'rish (tg:*) va SuperAdmin sozlamasi (⚙️ Настройка → 🔗 MoySklad → 📨 Бот хабарлари, a:tg*).
 * Ulangan akkauntlar {@code tg-reader} (Python/Telethon) xizmati orqali, {@link uz.kassa.service.tg.TgReaderHttpGateway}
 * bilan HTTP orqali (docs/BOT-XABARLARI.md). Bu yerda faqat jurnal, tekshiruv sozlamasi va ro'yxat.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TgHandler {

    public static final String LABEL = "📨 Бот хабарлари";
    private static final String BACK = "a:tg";
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final int PAGE = 8;

    private final Sender sender;
    private final TgReaderService svc;
    private final uz.kassa.service.tg.TgCardReport cardReport;
    private final uz.kassa.repo.TgCardRepo cardRepo;
    private final uz.kassa.repo.ClickAccountRepo clickRepo;
    private final TgReaderConfig cfg;
    private final KassaRepo kassaRepo;
    private final AppUserRepo userRepo;
    private final AuditService audit;
    private final org.springframework.beans.factory.ObjectProvider<TgAccountGateway> gatewayProvider;

    private TgAccountGateway gw() { return gatewayProvider.getIfAvailable(); }

    private static final String QR_CAPTION = "📷 <b>QR kodni skanerlang</b>\n\n"
            + "Telefoningizda: Telegram → <b>Sozlamalar</b> → <b>Ulangan qurilmalar</b> → <b>Qurilma ulash</b> → shu rasmni kamerada skanerlang.\n\n"
            + "<i>Skanerlash uchun vaqtingiz bor — kod o'zi muntazam yangilanib turadi.</i>";

    /** QR ko'rsatilgan xabar — fon rejimida (pollQr) holatini kuzatib, natijaga qarab tahrirlaymiz. Kalit — bog'lanadigan xodim (AppUser id). */
    private record QrPending(long actorId, Session session, long chatId, int msgId, int lastGen) {}
    private final Map<Long, QrPending> qrPending = new ConcurrentHashMap<>();

    /** QR kuzatuvi uchun ALOHIDA oqim (2026-09-16): umumiy Spring scheduler'ida (Jobs.java — 27 ta og'ir ish: MoySklad,
     *  Sheets, ombor tunlik, bildirishnoma sikllari) pollQr navbatda qolib, 74 s davomida bitta ham so'rov ketmagan,
     *  QR rasmi yangilanmagan va «Ulandi» hech qachon ko'rinmagan edi. Bu oqim faqat shu ish uchun. */
    private final ScheduledExecutorService qrPoller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tg-qr-poll"); t.setDaemon(true); return t;
    });

    @PostConstruct
    void startQrPoller() { qrPoller.scheduleWithFixedDelay(this::pollQr, 3, 1500, TimeUnit.MILLISECONDS); }

    @PreDestroy
    void stopQrPoller() { qrPoller.shutdownNow(); }

    /* ==================== 📨 ko'rish (tg:*) ==================== */

    public boolean onCallback(AppUser u, Session s, String data, long chatId, int msgId) {
        if (!data.startsWith("tg:")) return false;
        String[] p = data.split(":", 3);
        String cmd = p[1];
        String arg = p.length > 2 ? p[2] : "";
        // xodim (har rol) uchun: o'z akkauntini ulash
        switch (cmd) {
            case "my" -> { myScreen(u, s, chatId, msgId); return true; }
            case "add" -> { startQr(u, s, u.getId(), chatId, msgId); return true; }
            case "dc" -> { disconnectOwn(u, s, arg, chatId, msgId); return true; }
            case "test" -> { testOwn(u, arg, chatId); return true; }
            case "cancel" -> {
                boolean onPhoto = cancelQr(s, msgId);
                s.reset();
                // QR RASM xabarini matn sifatida tahrirlab bo'lmaydi ("there is no text in the message to edit") —
                // izohini yangilab, ro'yxatni yangi xabar qilib yuboramiz
                if (onPhoto) { sender.editCaption(chatId, msgId, "❌ Bekor qilindi.", null); myScreen(u, s, chatId, 0); }
                else myScreen(u, s, chatId, msgId);
                return true;
            }
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
        if (u.getRole() == Role.SUPERADMIN) kb.add(irow(btn("🔗 📲 Клик билан боғлаш", "a:tgcl")));
        kb.add(irow(btn("📨 Xabarlar", "tg:m"), btn("⬅️ Настройка", "a:p:set")));
        sender.edit(chatId, msgId, sb.toString(), inline(kb));
    }

    /** 🔗 Kartani ClickAccount'ga bog'lash ro'yxati — bog'lansa, karta balansi 📲 Клик hisobotidagi
     * "карта қолдиғи"ni AVTOMAT to'ldiradi (qo'lda /karta o'rniga), MoySklad bilan solishtiruv o'zi ishlaydi. */
    private void cardLinkList(long chatId, int msgId) {
        StringBuilder sb = new StringBuilder("🔗 <b>Kartalarni Клик ҳисобига боғлаш</b>\n\n"
                + "<i>Боғлангач, карта қолдиғи 📲 Клик ҳисоботидаги MoySklad солиштирувида АВТОМАТ ишлатилади (қўлда /karta киритиш ўрнига).</i>\n\n");
        List<TgCard> cards = cardRepo.findAllByOrderByNameAscMaskAsc();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (cards.isEmpty()) sb.append("Ҳали карта хабари келмаган.");
        for (TgCard c : cards) {
            String linked = c.getClickAccountId() == null ? "—"
                    : clickRepo.findById(c.getClickAccountId()).map(ClickAccount::getName).orElse("?");
            rows.add(irow(btn(cut((c.isHidden() ? "🙈 " : "") + (c.getName().isBlank() ? "Karta" : c.getName()) + " *" + c.getMask() + " → " + linked, 40), "a:tgcli:" + c.getId())));
        }
        if (cards.stream().anyMatch(TgCard::isHidden)) sb.append("<i>🙈 — shaxsiy karta, hisobotdan yashirilgan.</i>");
        rows.add(irow(btn("⬅️ Orqaga", "tg:c")));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    private void cardLinkPick(String cardIdStr, long chatId, int msgId) {
        long cardId = Long.parseLong(cardIdStr);
        TgCard card = cardRepo.findById(cardId).orElse(null);
        if (card == null) { cardLinkList(chatId, msgId); return; }
        StringBuilder sb = new StringBuilder("🔗 <b>" + esc(card.getName().isBlank() ? "Karta" : card.getName()) + " *" + esc(card.getMask()) + "</b>\n\nQaysi Клик ҳисобига боғлансин?");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("🚫 Bog'lamaslik", "a:tgcls:" + cardId + ".0")));
        for (ClickAccount ca : clickRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn((ca.getId().equals(card.getClickAccountId()) ? "✅ " : "") + cut(ca.getName(), 34), "a:tgcls:" + cardId + "." + ca.getId())));
        rows.add(irow(btn(card.isHidden() ? "👁 Hisobotga qaytarish" : "🙈 Hisobotdan yashirish (shaxsiy)", "a:tgclh:" + cardId)));
        rows.add(irow(btn("⬅️ Orqaga", "a:tgcl")));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    /** 🙈 Shaxsiy kartani hisobotdan yashirish / qaytarish (tg_card.hidden) — xabarlari baribir o'qiladi, faqat hisobot va Σ ga kirmaydi. */
    private void cardHideToggle(AppUser actor, String cardIdStr, long chatId, int msgId) {
        long cardId = Long.parseLong(cardIdStr);
        TgCard card = cardRepo.findById(cardId).orElse(null);
        if (card != null) {
            card.setHidden(!card.isHidden());
            if (card.isHidden()) card.setClickAccountId(null);   // shaxsiy karta Click hisobiga bog'lanmaydi
            cardRepo.save(card);
            audit.log(actor.getId(), card.isHidden() ? "TG_KARTA_YASHIRILDI" : "TG_KARTA_KORSATILDI", "tg_card", cardId, card.getMask());
        }
        cardLinkPick(cardIdStr, chatId, msgId);
    }

    private void cardLinkSet(AppUser actor, String arg, long chatId, int msgId) {
        String[] pa = arg.split("\\.");
        long cardId = Long.parseLong(pa[0]);
        long clickId = Long.parseLong(pa[1]);
        TgCard card = cardRepo.findById(cardId).orElse(null);
        if (card != null) {
            card.setClickAccountId(clickId == 0 ? null : clickId);
            cardRepo.save(card);
            audit.log(actor.getId(), "TG_KARTA_KLIK", "tg_card", cardId, arg);
        }
        cardLinkList(chatId, msgId);
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
            sb.append("Sizda ulangan akkaunt yo'q.\n<i>«➕ Ulash» tugmasini bosing — QR kod chiqadi, uni telefoningizdagi Telegram ilovasi bilan (Sozlamalar → Ulangan qurilmalar → Qurilma ulash) skanerlang. Shundan so'ng bot o'sha akkauntga kelgan " + esc(cfg.sourceBot().isBlank() ? "bot" : "@" + cfg.sourceBot()) + " xabarlarini o'qiy boshlaydi.</i>\n");
        else for (TgAkkaunt a : mine) {
            sb.append(online(a) ? "🟢 " : "🔴 ").append("<b>").append(esc(a.getPhone())).append("</b>")
              .append(a.isActive() ? "" : " · ⏸ to'xtatilgan").append("\n");
            if (a.getLastError() != null) sb.append("   ⚠️ ").append(esc(a.getLastError())).append("\n");
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        boolean can = gw() != null && gw().available();
        if (can) rows.add(irow(btn("➕ Ulash", "tg:add")));
        for (TgAkkaunt a : mine) rows.add(irow(btn("✅ Tekshirish · " + a.getPhone(), "tg:test:" + a.getPhone()), btn("🔌 Uzish", "tg:dc:" + a.getPhone())));
        // orqaga: admin — akkauntlar ro'yxati + bo'lim ildizi; buxgalter — Настройка (cards() kabi); kassir uchun inline
        // «ota» ekran yo'q (bosh menyu — pastdagi reply klaviatura), shuning uchun unga qator ko'rsatilmaydi
        if (u.getRole() == Role.SUPERADMIN) rows.add(irow(btn("⬅️ Akkauntlar", "a:tgacc"), btn(LABEL, BACK)));
        else if (u.getRole() != Role.KASSIR) rows.add(irow(btn("⬅️ Настройка", "a:p:set")));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    /** Xodim o'z akkauntini tekshiradi — «Saqlangan xabarlar»iga test xabar yuboriladi, natija xabar sifatida. */
    private void testOwn(AppUser u, String phone, long chatId) {
        TgAkkaunt a = svc.account(phone);
        if (a == null || !u.getId().equals(a.getUserId())) return;
        var gw = gw();
        var r = gw == null ? null : gw.test(phone);
        sender.send(chatId, r == null ? "⚠️ Server yoqilmagan" : (r.ok() ? "✅ " : "⚠️ ") + esc(r.message()));
    }

    /** QR-login boshlash: targetUserId — bu akkaunt bog'lanadigan xodim (odatda o'zi, admin boshqa xodim uchun ham boshlashi mumkin — QR kodni o'sha xodim o'z telefonida skanerlashi kerak). */
    private void startQr(AppUser actor, Session s, long targetUserId, long chatId, int msgId) {
        var gw = gw();
        if (gw == null || !gw.available()) { myScreen(actor, s, chatId, msgId); return; }
        var r = gw.startQrLogin(targetUserId);
        if (r.step() != TgAccountGateway.Step.QR || r.qrPngBase64() == null) {
            sender.edit(chatId, msgId, "⚠️ QR yaratilmadi: " + esc(r.error() == null ? "noma'lum xato" : r.error()),
                    inline(List.of(irow(btn("⬅️ Orqaga", "tg:my")))));
            return;
        }
        byte[] png = Base64.getDecoder().decode(r.qrPngBase64());
        Integer photoMsgId = sender.sendPhoto(chatId, png, "qr.png", QR_CAPTION, inline(List.of(irow(btn("❌ Bekor", "tg:cancel")))));
        if (photoMsgId == null) { gw.cancelLogin(targetUserId); sender.send(chatId, "⚠️ QR rasm yuborilmadi — qaytadan urinib ko'ring."); return; }
        s.data.put("tgQrUser", targetUserId);
        qrPending.put(targetUserId, new QrPending(actor.getId(), s, chatId, photoMsgId, r.gen()));
        log.info("QR boshlandi: user={} actor={} gen={}", targetUserId, actor.getId(), r.gen());
    }

    /** ❌ Bekor bosilganda: joriy sessiyada boshlangan QR (agar bor bo'lsa) Python tomonida ham bekor qilinadi.
     *  Qaytadi: bosilgan tugma aynan QR RASM xabarida edimi (u holda editMessageText ishlamaydi). */
    private boolean cancelQr(Session s, int msgId) {
        Object v = s.data.get("tgQrUser");
        if (!(v instanceof Number n)) return false;
        long userId = n.longValue();
        QrPending p = qrPending.remove(userId);
        if (gw() != null) gw().cancelLogin(userId);
        log.info("QR bekor: user={}", userId);
        return p != null && p.msgId() == msgId;
    }

    /** Fon rejimida (ALOHIDA oqimda, har 1.5 s) barcha QR kutilayotgan loginlarni tekshiradi: token yangilansa rasmni, natija chiqsa xabarni yangilaydi. */
    public void pollQr() {
        if (qrPending.isEmpty()) return;
        var gw = gw();
        if (gw == null) return;
        for (var e : List.copyOf(qrPending.entrySet())) {
            long userId = e.getKey();
            try { pollOne(gw, userId, e.getValue()); }
            catch (Exception ex) { log.warn("QR poll (user={}): {} — keyingi urinishda davom etadi", userId, ex.toString()); }   // vaqtinchalik xato loginni o'ldirmaydi
        }
    }

    private void pollOne(TgAccountGateway gw, long userId, QrPending p) {
        TgAccountGateway.Result r = gw.pollQr(userId);
        switch (r.step()) {
            case QR -> {
                if (r.gen() != p.lastGen() && r.qrPngBase64() != null) {
                    byte[] png = Base64.getDecoder().decode(r.qrPngBase64());
                    if (sender.editPhoto(p.chatId(), p.msgId(), png, "qr.png", QR_CAPTION, inline(List.of(irow(btn("❌ Bekor", "tg:cancel"))))))
                        qrPending.put(userId, new QrPending(p.actorId(), p.session(), p.chatId(), p.msgId(), r.gen()));
                    log.info("QR yangilandi: user={} gen={}", userId, r.gen());
                }
            }
            case NEED_PASSWORD -> {
                qrPending.remove(userId);
                p.session().state = Session.State.TG_PWD;
                p.session().data.put("tgQrUser", userId);
                log.info("QR skanerlandi, 2FA kutilmoqda: user={}", userId);
                sender.editCaption(p.chatId(), p.msgId(), "✅ Skanerlandi. Endi 2FA parolni yozing.", null);
                sender.send(p.chatId(), "🔒 Akkauntda 2FA (bulutli parol) yoqilgan — <b>parolni</b> kiriting:", inline(List.of(irow(btn("❌ Bekor", "tg:cancel")))));
            }
            case CONNECTED -> {
                qrPending.remove(userId);
                p.session().reset();
                audit.log(p.actorId(), "TG_ULANDI", "tg_akkaunt", null, r.phone());
                log.info("QR ULANDI: user={} phone={}", userId, r.phone());
                sender.editCaption(p.chatId(), p.msgId(), "✅ <b>Ulandi!</b> " + esc(r.phone()) + " — endi bot bu akkauntdagi "
                        + esc(cfg.sourceBot().isBlank() ? "bot" : "@" + cfg.sourceBot()) + " xabarlarini avtomat o'qiydi.",
                        inline(List.of(irow(btn("🔗 Akkauntlarim", "tg:my")))));
            }
            case ERROR -> {
                qrPending.remove(userId);
                p.session().reset();
                log.warn("QR xato: user={} — {}", userId, r.error());
                sender.editCaption(p.chatId(), p.msgId(), "⚠️ Ulanmadi: " + esc(r.error() == null ? "noma'lum" : r.error()) + "\n<i>Qayta urinish: /akkaunt</i>", null);
            }
        }
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

    /** Xodim login matni — endi faqat 2FA parol bosqichi (QR bosqichida matn kutilmaydi). Router faqat TG_PWD holatida shu yerga yo'naltiradi. */
    public boolean onLoginText(AppUser u, Session s, String text, long chatId) {
        if (s.state != Session.State.TG_PWD) return false;
        var gw = gw();
        if (gw == null || !gw.available()) { s.reset(); sender.send(chatId, "⚠️ Serverda yoqilmagan."); return true; }
        Object v = s.data.get("tgQrUser");
        if (!(v instanceof Number n)) { s.reset(); sender.send(chatId, "⚠️ Sessiya topilmadi — qaytadan boshlang: /akkaunt"); return true; }
        try {
            var r = gw.submitPassword(n.longValue(), text.trim());
            s.reset();
            if (r.step() == TgAccountGateway.Step.CONNECTED) {
                audit.log(u.getId(), "TG_ULANDI", "tg_akkaunt", null, r.phone());
                sender.send(chatId, "✅ <b>Ulandi!</b> " + esc(r.phone()) + " — endi bot bu akkauntdagi " + esc(cfg.sourceBot().isBlank() ? "bot" : "@" + cfg.sourceBot()) + " xabarlarini avtomat o'qiydi.",
                        inline(List.of(irow(btn("🔗 Akkauntlarim", "tg:my")))));
            } else {
                sender.send(chatId, "⚠️ Ulanmadi: " + esc(r.error() == null ? "noma'lum" : r.error()) + "\n<i>Qayta urinish: /akkaunt</i>");
            }
        } catch (Exception e) {
            s.reset();
            sender.send(chatId, "⚠️ Xato: " + esc(String.valueOf(e.getMessage())));
        }
        return true;
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
            case "tgdis" -> { if (gw() != null) gw().pause(arg); else svc.setActive(arg, false); audit.log(u.getId(), "TG_TOXTATILDI", "tg_akkaunt", null, arg); accountCard(arg, chatId, msgId); }
            case "tgdel" -> confirmDelete(arg, chatId, msgId);
            case "tgdy" -> doDelete(u, arg, chatId, msgId);
            case "tgtest" -> runAdminTest(arg, chatId, msgId);
            case "tgset" -> settingsScreen(chatId, msgId);
            case "tgadd" -> addPick(u, s, arg, chatId, msgId);
            case "tgcl" -> cardLinkList(chatId, msgId);
            case "tgcli" -> cardLinkPick(arg, chatId, msgId);
            case "tgcls" -> cardLinkSet(u, arg, chatId, msgId);
            case "tgclh" -> cardHideToggle(u, arg, chatId, msgId);
            case "tgrn" -> { int n = cardReport.reportNow(); sender.send(chatId, n == 0 ? "⚠️ Guruh sozlanmagan (тgreader.chat_ids yoki Click guruhi)" : "📤 Karta qoldiqlari " + n + " ta guruhga yuborildi"); menu(s, chatId, 0); }
            case "tgmo" -> { cfg.toggleMediaOcr(); audit.log(u.getId(), "TG_SOZLAMA", "settings", null, "media_ocr=" + cfg.mediaOcr()); menu(s, chatId, msgId); }
            case "tgbn" -> { sender.send(chatId, "💰 <b>Qoldiq so'rovi</b>\n" + svc.requestBalances(null, "qo'lda")); menu(s, chatId, 0); }
            case "tgbal" -> { sender.send(chatId, "💰 <b>Qoldiq so'rovi</b>\n" + svc.requestBalances(arg, "qo'lda")); accountCard(arg, chatId, msgId); }
            case "tgsec" -> securityScreen(arg, chatId, msgId);
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
        sb.append("📡 Manbalar: <b>").append(cfg.sources().size()).append("</b> (").append(esc(cut(String.join(", ", cfg.sources()), 80))).append(")").append(cfg.mediaOcr() ? " · 🖼 OCR" : "").append("\n");
        sb.append("💰 Qoldiq so'rovi: <b>").append(cfg.balanceSteps().isEmpty() ? "o'chiq" : esc(String.join(" | ", cfg.balanceSteps())) + "</b> · hisobotdan <b>" + cfg.balanceBeforeMin() + "</b> min oldin").append(cfg.balanceSteps().isEmpty() ? "</b>" : "").append("\n");
        sb.append("💳 Kartalar: <b>").append(cardRepo.count()).append("</b> · guruh hisoboti: <b>")
          .append(cfg.reportEveryH() == 0 ? "o'chiq" : "har " + cfg.reportEveryH() + " soat " + cfg.reportFrom() + "–" + cfg.reportTo()).append("</b> · chat <b>")
          .append(cfg.reportChatIds().size()).append("</b>\n");
        sb.append("\n<i>Akkaunt ulash: 🔗 Akkauntlar → ➕ Akkaunt qo'shish (yoki xodim o'zi /akkaunt bilan).</i>");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn(cfg.enabled() ? "⏸ O'chirish" : "▶️ Yoqish", "a:tgg"), btn("🔗 Akkauntlar", "a:tgacc")));
        rows.add(irow(btn("🔑 Sozlamalar (API/bot)", "a:tgset")));
        rows.add(irow(btn("🔔 Kalit so'zlar", "a:tgv:kw"), btn("🔇 Jimlik (soat)", "a:tgv:silence")));
        rows.add(irow(btn(cfg.match() ? "💵 Solishtirish: ON" : "💵 Solishtirish: OFF", "a:tgm2"), btn("± Farq (so'm)", "a:tgv:tol")));
        rows.add(irow(btn("⏰ Hisobot interval", "a:tgv:rep"), btn("📤 Hisobotni hozir", "a:tgrn")));
        rows.add(irow(btn("📡 Manbalar", "a:tgv:src"), btn(cfg.mediaOcr() ? "🖼 Rasm OCR: ON" : "🖼 Rasm OCR: OFF", "a:tgmo")));
        rows.add(irow(btn("💰 Qoldiq so'rovi", "a:tgv:bal"), btn("🔄 Hozir so'rash", "a:tgbn")));
        rows.add(irow(btn("💳 Karta qoldiqlari", "tg:c"), btn("📨 Xabarlar", "tg:m")));
        rows.add(irow(btn("⬅️ Orqaga", "a:p:set")));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    /** Bir marta, butun ilova uchun: API kaliti va manba bot — istalgan payt bu yerdan o'zgartiriladi. */
    private void settingsScreen(long chatId, int msgId) {
        StringBuilder sb = new StringBuilder("🔑 <b>Ulanish sozlamalari</b>\n\n");
        sb.append("API kaliti: <b>").append(cfg.apiId() > 0 ? "kiritilgan (id " + cfg.apiId() + ")" : "kiritilmagan").append("</b>\n");
        sb.append("Манба bot: <b>").append(cfg.sourceBot().isBlank() ? "kiritilmagan" : "@" + esc(cfg.sourceBot())).append("</b>\n");
        sb.append("\n<i>Bular BIR MARTA, butun ilova uchun sozlanadi. Xodimlar o'z akkauntini ulashda bularni kiritmaydi — faqat QR kodni skanerlaydi va (bo'lsa) 2FA parolni kiritadi.</i>");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("🔑 API kalitini kiritish", "a:tgv:api")));
        rows.add(irow(btn("🤖 Манба botni kiritish", "a:tgv:source")));
        rows.add(irow(btn("⬅️ Orqaga", BACK)));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    private void accountList(long chatId, int msgId) {
        StringBuilder sb = new StringBuilder("🔗 <b>Ulangan akkauntlar</b>\n\n");
        List<TgAkkaunt> all = svc.accounts();
        boolean can = gw() != null && gw().available();
        if (all.isEmpty()) sb.append(can
                ? "Hali akkaunt ulanmagan.\n<i>«➕ Akkaunt qo'shish» — xodimni tanlang, chiqqan QR kodni O'SHA XODIM o'z telefonida (Sozlamalar → Ulangan qurilmalar → Qurilma ulash) skanerlasin. Xodim o'zi ham /akkaunt bilan ulay oladi.</i>"
                : "⚠️ Avval 🔑 API kalitini kiriting (pastdagi tugma), so'ng bu yerdan qo'shasiz.");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (TgAkkaunt a : all) {
            String owner = a.getUserId() == null ? "" : " · " + userRepo.findById(a.getUserId()).map(AppUser::getFullName).orElse("?");
            rows.add(irow(btn((!a.isActive() ? "⏸ " : online(a) ? "🟢 " : "🔴 ") + cut((a.getName().isBlank() ? a.getPhone() : a.getName()) + " · " + a.getPhone() + owner, 34), "a:tgap:" + a.getPhone())));
        }
        if (can) {
            var pending = gw().pendingLogins();
            if (!pending.isEmpty()) {
                sb.append("\n⏳ <b>Chala qolgan (jarayonda)</b>:\n");
                for (var pl : pending) {
                    String who = userRepo.findById(pl.userId()).map(AppUser::getFullName).orElse("?");
                    sb.append("• ").append(esc(who)).append(" · boshlandi ")
                      .append(DTF.format(pl.startedAt().atZone(ZoneId.of("Asia/Tashkent")).toLocalDateTime())).append("\n");
                }
            }
            rows.add(irow(btn("➕ Akkaunt qo'shish", "a:tgadd")));
        } else rows.add(irow(btn("🔑 API kalitini kiritish", "a:tgv:api")));
        rows.add(irow(btn("⬅️ Orqaga", BACK)));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    /** Admin ➕: qaysi xodim uchun akkaunt ulanadi (yoki o'zi). arg — bo'sh (ro'yxat) | userId. */
    private void addPick(AppUser u, Session s, String arg, long chatId, int msgId) {
        if (gw() == null || !gw().available()) { accountList(chatId, msgId); return; }
        if (!arg.isBlank()) {
            long targetId = "0".equals(arg) ? u.getId() : Long.parseLong(arg);
            startQr(u, s, targetId, chatId, msgId);
            return;
        }
        StringBuilder sb = new StringBuilder("➕ <b>Akkaunt qo'shish</b>\n\nBu akkaunt qaysi xodimniki? (QR kodni SHU XODIM o'z telefonida skanerlashi kerak)");
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
        sb.append(!a.isActive() ? "⏸ To'xtatilgan" : online(a) ? "🟢 Ulangan" : "🔴 Uzilgan").append(a.getLastSeenAt() == null ? "" : " · oxirgi " + DTF.format(a.getLastSeenAt().atZone(ZoneId.of("Asia/Tashkent")).toLocalDateTime())).append("\n");
        if (a.getCreatedAt() != null) sb.append("🕒 Ulangan: ").append(DTF.format(a.getCreatedAt().atZone(ZoneId.of("Asia/Tashkent")).toLocalDateTime())).append("\n");
        if (a.getLastError() != null) sb.append("⚠️ ").append(esc(a.getLastError())).append("\n");
        sb.append("👷 Xodim: <b>").append(a.getUserId() == null ? "bog'lanmagan" : esc(userRepo.findById(a.getUserId()).map(AppUser::getFullName).orElse("?"))).append("</b>\n");
        sb.append("🏪 Do'kon: <b>").append(a.getKassaId() == null ? "—" : esc(kassaRepo.findById(a.getKassaId()).map(Kassa::getName).orElse("?"))).append("</b> (summa solishtirish uchun)\n");
        sb.append("🔐 2FA: <b>").append(a.getTwoFa() == null ? "?" : a.getTwoFa() ? "yoqilgan" : "YO'Q ⚠️").append("</b> · qurilmalar: <b>").append(sessionCount(a)).append("</b>")
          .append(a.getSessionsAt() == null ? " (tekshirilmagan)" : " (" + DTF.format(a.getSessionsAt().atZone(ZoneId.of("Asia/Tashkent")).toLocalDateTime()) + ")").append("\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("👷 Xodimga bog'lash", "a:tgau:" + phone), btn("🏪 Do'konga bog'lash", "a:tgak:" + phone)));
        rows.add(irow(a.isActive() ? btn("⏸ To'xtatish", "a:tgdis:" + phone) : btn("▶️ Yoqish", "a:tgen:" + phone), btn("✅ Tekshirish", "a:tgtest:" + phone)));
        rows.add(irow(btn("🔐 Qurilmalar", "a:tgsec:" + phone), btn("💰 Qoldiqni so'rash", "a:tgbal:" + phone)));
        rows.add(irow(btn("📨 Xabarlari", "tg:acc:" + phone), btn("🗑 Butunlay o'chirish", "a:tgdel:" + phone)));
        rows.add(irow(btn("⬅️ Akkauntlar", "a:tgacc")));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    /** 🔐 Qurilmalar: tg-reader'dan jonli seanslar ro'yxati (account.getAuthorizations) + 2FA; natija saqlanadi (yangi seans ogohlantirishi — securityTick). */
    private void securityScreen(String phone, long chatId, int msgId) {
        TgAkkaunt a = svc.account(phone);
        if (a == null) { accountList(chatId, msgId); return; }
        var info = svc.refreshSecurity(a, false);
        StringBuilder sb = new StringBuilder("🔐 <b>Qurilmalar</b> — ").append(esc(a.getName().isBlank() ? phone : a.getName())).append("\n\n");
        if (info == null || !info.ok()) sb.append("⚠️ O'qib bo'lmadi: ").append(esc(info == null ? "server yoqilmagan" : String.valueOf(info.error()))).append("\n");
        else {
            sb.append("2FA (bulutli parol): <b>").append(Boolean.TRUE.equals(info.twoFa()) ? "yoqilgan ✅" : "YO'Q ⚠️ — yoqish tavsiya etiladi").append("</b>\n\n");
            int n = 0;
            for (var x : info.sessions()) sb.append(++n).append(". ").append(x.current() ? "🤖 " : "📱 ").append(esc(TgReaderService.sessionLine(x))).append("\n");
            sb.append("\n<i>🤖 — shu bot (tg-reader) seansi. Notanish qurilma bo'lsa: Telegram → Sozlamalar → Qurilmalar → tugatish.</i>");
        }
        show(chatId, msgId, sb.toString(), inline(List.of(irow(btn("🔄 Yangilash", "a:tgsec:" + phone)), irow(btn("⬅️ Orqaga", "a:tgap:" + phone)))));
    }

    /** ✅ Tekshirish (admin): «Saqlangan xabarlar»ga test yuboradi, natija xabar sifatida, karta o'zgarmaydi. */
    private void runAdminTest(String phone, long chatId, int msgId) {
        var gw = gw();
        var r = gw == null ? null : gw.test(phone);
        sender.send(chatId, r == null ? "⚠️ Server yoqilmagan" : (r.ok() ? "✅ " : "⚠️ ") + esc(r.message()));
        accountCard(phone, chatId, msgId);
    }

    /** 🗑 Butunlay o'chirish — halokatli amal, tasdiq so'raladi (⏸ To'xtatish'dan farqli — bu qaytarilmaydi). */
    private void confirmDelete(String phone, long chatId, int msgId) {
        sender.edit(chatId, msgId, "🗑 <b>Butunlay o'chirilsinmi?</b>\n\n" + esc(phone)
                + " — sessiya butunlay o'chadi, xodim qayta ulash uchun QR-kodni qaytadan skanerlashi kerak bo'ladi.",
                inline(List.of(irow(btn("✅ Ha, o'chirilsin", "a:tgdy:" + phone), btn("❌ Yo'q", "a:tgap:" + phone)))));
    }

    private void doDelete(AppUser u, String phone, long chatId, int msgId) {
        if (gw() != null) gw().disconnect(phone);
        svc.deleteAccount(phone);
        audit.log(u.getId(), "TG_OCHIRILDI", "tg_akkaunt", null, phone);
        accountList(chatId, msgId);
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
            case "api" -> "🔑 api_id va api_hash (my.telegram.org → API development tools), bo'sh joy bilan:\n<code>123456 0123456789abcdef0123456789abcdef</code>\nHozir: " + (cfg.apiId() > 0 ? cfg.apiId() + " · hash ****" : "yo'q");
            case "source" -> "🤖 Qaysi bot xabarlari o'qilsin (@ siz)? Masalan <code>HUMOcardbot</code>\nHozir: " + (cfg.sourceBot().isBlank() ? "yo'q" : "@" + cfg.sourceBot());
            case "src" -> "📡 Qo'shimcha manbalar (vergul bilan): bot yoki odam <code>@username</code>, guruh/kanal <code>@username</code> yoki <code>-100…</code> ID, <code>me</code> — Saqlangan xabarlar. Asosiy bot (" + (cfg.sourceBot().isBlank() ? "—" : "@" + esc(cfg.sourceBot())) + ") doim o'qiladi. FAQAT shu ro'yxatdagilar o'qiladi — xodimning boshqa chatlariga tegilmaydi. «-» — tozalash.\nHozir: " + (cfg.extraSources().isEmpty() ? "—" : esc(String.join(", ", cfg.extraSources())));
            case "bal" -> "💰 Qoldiqni so'rash: asosiy botga yuboriladigan tugma/buyruq matni. Bir necha qadam — <code>|</code> bilan (<code>💳 Мои карты | 1</code>); inline tugma bo'lsa matni yoziladi, bot o'zi bosadi. Oxirida <code>@10</code> — Click hisobotidan necha daqiqa oldin (5 ga karrali, standart 5). «-» — o'chirish.\nHozir: " + (cfg.balanceSteps().isEmpty() ? "o'chiq" : esc(String.join(" | ", cfg.balanceSteps())) + " @" + cfg.balanceBeforeMin());
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
                // my.telegram.org'dan butun matn (yorliqlar bilan) nusxalansa ham ishlaydi — ichidan
                // 32 belgili hash va raqamni o'zi qidirib topadi, faqat ikkalasini emas.
                java.util.regex.Matcher hm = java.util.regex.Pattern.compile("\\b[0-9a-fA-F]{32}\\b").matcher(t);
                if (!hm.find()) throw new IllegalArgumentException("api_hash topilmadi (32 ta harf-raqam kerak)");
                String hash = hm.group();
                java.util.regex.Matcher im = java.util.regex.Pattern.compile("\\b\\d{4,10}\\b").matcher(t.replace(hash, " "));
                if (!im.find()) throw new IllegalArgumentException("api_id topilmadi (4–10 xonali raqam kerak)");
                cfg.setApi(Integer.parseInt(im.group()), hash);
            }
            else if ("source".equals(key)) cfg.setSourceBot(t);
            else if ("rep".equals(key)) {
                String[] a = t.split("[\\s,]+");
                cfg.set(TgReaderConfig.REPORT_EVERY_H, String.valueOf(Math.max(0, Math.min(24, Integer.parseInt(a[0])))));
                if (a.length > 1) cfg.set(TgReaderConfig.REPORT_FROM, String.valueOf(Math.max(0, Math.min(23, Integer.parseInt(a[1])))));
                if (a.length > 2) cfg.set(TgReaderConfig.REPORT_TO, String.valueOf(Math.max(0, Math.min(23, Integer.parseInt(a[2])))));
            }
            else if ("src".equals(key)) cfg.setSources(t.equals("-") ? "" : t);
            else if ("bal".equals(key)) {
                if (t.equals("-")) cfg.setBalanceCmd("");
                else {
                    java.util.regex.Matcher bm = java.util.regex.Pattern.compile("@(\\d{1,2})\\s*$").matcher(t);
                    String cmd = t;
                    if (bm.find()) { cfg.set(TgReaderConfig.BALANCE_BEFORE, bm.group(1)); cmd = t.substring(0, bm.start()).trim(); }
                    cfg.setBalanceCmd(cmd);
                }
            }
            else { menu(s, chatId, 0); return; }
            audit.log(u.getId(), "TG_SOZLAMA", "settings", null, key + "=" + t);
            sender.send(chatId, "✅ Saqlandi");
        } catch (Exception e) {
            String reason = e.getMessage();
            sender.send(chatId, "⚠️ Qiymat noto'g'ri" + (reason == null || reason.isBlank() ? ": " + esc(t) : " — " + esc(reason)));
        }
        menu(s, chatId, 0);
    }

    /* ==================== yordamchi ==================== */

    private boolean online(TgAkkaunt a) {
        return a.getLastSeenAt() != null && a.getLastSeenAt().isAfter(Instant.now().minusSeconds(cfg.silenceHours() * 3600L));
    }
    private static int sessionCount(TgAkkaunt a) {
        if (a.getSessionsJson() == null) return 0;
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readTree(a.getSessionsJson()).size(); } catch (Exception e) { return 0; }
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
