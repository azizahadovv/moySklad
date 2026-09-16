package uz.kassa.service.tg;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import uz.kassa.bot.Sender;
import uz.kassa.domain.*;
import uz.kassa.repo.DayRepo;
import uz.kassa.repo.TgAkkauntRepo;
import uz.kassa.repo.TgXabarRepo;
import uz.kassa.service.NotifySwitches;
import uz.kassa.service.control.ControlNotifier;
import uz.kassa.repo.TgManbaHolatRepo;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.bot.OcrEngine;
import org.springframework.beans.factory.ObjectProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.esc;
import static uz.kassa.bot.TextUtil.fmt;

/**
 * 📨 Bot xabarlari nazorati (2026-09-14): tg-reader servisi HTTP orqali uzatgan xabarlarni saqlaydi,
 * tekshiradi (kalit so'z / shablon, summani bizdagi yozuv bilan solishtirish) va ogohlantiradi
 * (SuperAdmin + rahbar + guruh/kanal). Jimlik nazorati — Jobs orqali.
 * Bot MoySklad'ga o'xshab qoida-dvigatel emas: bu yerda tekshiruvlar sodda, sozlamadan boshqariladi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TgReaderService {

    private static final Pattern AMOUNT = Pattern.compile("(\\d[\\d\\s.\\u00A0]*(?:,\\d{1,2})?)\\s*(?:so'?m|су[мн]|сўм|uzs)", Pattern.CASE_INSENSITIVE);

    private final TgAkkauntRepo accRepo;
    private final TgXabarRepo msgRepo;
    private final TgReaderConfig cfg;
    private final ControlNotifier notifier;
    private final Sender sender;
    private final DayRepo dayRepo;
    private final uz.kassa.repo.TgCardRepo cardRepo;
    private final uz.kassa.repo.ClickAccountRepo clickRepo;
    private final TgManbaHolatRepo holatRepo;
    private final AppUserRepo userRepo;
    private final OcrEngine ocr;
    /** Darvoza (tg-reader HTTP) — ObjectProvider: TgReaderHttpGateway o'zi shu servisga bog'liq (siklik bog'liqlik). */
    private final ObjectProvider<TgAccountGateway> gatewayProvider;
    private final ObjectMapper om = new ObjectMapper();
    private final ZoneId zone = ZoneId.of("Asia/Tashkent");

    /* ==================== ingest (tg-reader → ilova) ==================== */

    /** tg-reader akkaunt haqida ma'lumot bergani (login/har ulanish). */
    public void upsertAccount(String phone, String name, Long tgUserId, String username, String sourceBot) {
        TgAkkaunt a = accRepo.findById(phone).orElseGet(() -> TgAkkaunt.builder().phone(phone).createdAt(Instant.now()).build());
        if (name != null && !name.isBlank()) a.setName(name);
        if (tgUserId != null) a.setTgUserId(tgUserId);
        if (username != null) a.setUsername(username);
        if (sourceBot != null) a.setSourceBot(sourceBot);
        a.setLastSeenAt(Instant.now());
        a.setLastError(null);
        accRepo.save(a);
    }

    public void heartbeat(String phone, String error) {
        accRepo.findById(phone).ifPresent(a -> {
            a.setLastSeenAt(Instant.now());
            a.setLastError(error);
            accRepo.save(a);
        });
    }

    /** tg-reader qayerdan davom etsin: manba kesimida oxirgi ma'lum xabar id (asosiy bot uchun eski maydon ham hisobga olinadi). */
    public long lastMsgId(String phone, String source) {
        TgAkkaunt a = accRepo.findById(phone).orElse(null);
        String src = source == null || source.isBlank() ? (a == null ? "" : a.getSourceBot()) : source;
        long fromTable = holatRepo.findById(new TgManbaHolat.Id(phone, src)).map(TgManbaHolat::getLastMsgId).orElse(0L);
        long legacy = a != null && src.equals(a.getSourceBot()) ? a.getLastMsgId() : 0L;
        return Math.max(fromTable, legacy);
    }

    /** Kelgan xabarlar (bir yoki bir nechta, manba kesimida). Yangi xabarlar tekshiriladi va kerak bo'lsa ogohlantiriladi. */
    public void ingest(String phone, List<Msg> messages) {
        TgAkkaunt acc = accRepo.findById(phone).orElse(null);
        if (acc == null) { upsertAccount(phone, "", null, "", ""); acc = accRepo.findById(phone).orElse(null); }
        if (acc == null || messages == null || messages.isEmpty()) return;
        Map<String, TgManbaHolat> holat = new LinkedHashMap<>();
        for (Msg m : messages) {
            String src = m.sourceBot() == null || m.sourceBot().isBlank() ? acc.getSourceBot() : m.sourceBot();
            if (msgRepo.findByPhoneAndSourceBotAndMsgId(phone, src, m.msgId()).isPresent()) continue;
            LocalDateTime at = parseAt(m.date());
            String text = m.text() == null ? "" : m.text();
            if (m.sender() != null && !m.sender().isBlank()) text = "👤 " + m.sender() + "\n" + text;   // guruh: kim yozgani
            if (m.photoB64() != null && !m.photoB64().isBlank() && cfg.mediaOcr()) text = text + ocrText(phone, m.photoB64());
            TgXabar x = TgXabar.builder().phone(phone).sourceBot(src)
                    .msgId(m.msgId()).msgAt(at).text(cut(text, 8000)).media(m.media() == null ? "" : m.media())
                    .verdict("YANGI").createdAt(Instant.now()).build();
            check(acc, x);
            msgRepo.save(x);
            TgManbaHolat h = holat.computeIfAbsent(src, k -> holatRepo.findById(new TgManbaHolat.Id(phone, k)).orElseGet(() -> TgManbaHolat.of(phone, k)));
            if (m.msgId() > h.getLastMsgId()) h.setLastMsgId(m.msgId());
            if (at != null && (h.getLastMsgAt() == null || at.isAfter(h.getLastMsgAt()))) h.setLastMsgAt(at);
            // per-xabar ogohlantirish faqat kalit so'z / nomuvofiqlikda (karta qoldig'i jimgina yig'iladi, guruhga hisobot alohida)
            if ("OGOH".equals(x.getVerdict()) || "NOMOS".equals(x.getVerdict())) notify(acc, x);
        }
        for (TgManbaHolat h : holat.values()) {
            holatRepo.save(h);
            if (h.getId().getSource().equals(acc.getSourceBot())) {   // asosiy bot — eski maydonlar ham (ro'yxat/holat ekranlari uchun)
                if (h.getLastMsgId() > acc.getLastMsgId()) acc.setLastMsgId(h.getLastMsgId());
                if (h.getLastMsgAt() != null && (acc.getLastMsgAt() == null || h.getLastMsgAt().isAfter(acc.getLastMsgAt()))) acc.setLastMsgAt(h.getLastMsgAt());
            }
        }
        acc.setLastSeenAt(Instant.now());
        acc.setLastError(null);
        accRepo.save(acc);
    }

    /** tg-reader'dan keladigan xabar: sourceBot — manba kaliti (bot/odam username, «me», chat id), sender — guruhda kim yozgani, photoB64 — rasm (OCR uchun). */
    public record Msg(long msgId, String date, String text, String media, String sourceBot, String sender, String photoB64) {}

    /** Rasmni OCR qilib matnga qo'shish («[OCR]» bo'limi) — karta/summa parserlari va kalit so'zlar shu matnda ham ishlaydi. */
    private String ocrText(String phone, String b64) {
        java.io.File tmp = null;
        try {
            byte[] data = Base64.getDecoder().decode(b64);
            tmp = java.io.File.createTempFile("tgsrc-", ".jpg");
            java.nio.file.Files.write(tmp.toPath(), data);
            String t = ocr.ocrMultiPass(tmp);
            return t == null || t.isBlank() ? "" : "\n[OCR]\n" + t.trim();
        } catch (Exception e) {
            log.warn("tg-reader OCR ({}): {}", phone, e.toString());
            return "";
        } finally { if (tmp != null) tmp.delete(); }
    }

    /* ==================== tekshiruv ==================== */

    private void check(TgAkkaunt acc, TgXabar x) {
        String low = x.getText() == null ? "" : x.getText().toLowerCase();
        // 1) karta shabloni (HUMOCARD kabi): qoldiqni avtomat yig'ish
        TgCardParser.Card c = TgCardParser.parse(x.getText());
        boolean isCard = c != null && c.usable();
        if (isCard) {
            // 🙈 Shaxsiy karta (tg_card.hidden, V43): xabar MAZMUNI va summasi SAQLANMAYDI, kalit so'z/ogohlantirish yo'q,
            // qoldiq yangilanmaydi — xodimning shaxsiy harakatlari jurnalda qolmasin. 👁 qaytarilsa keyingi xabarlardan davom etadi.
            TgCard hidden = cardRepo.findBySourceBotAndMask(x.getSourceBot(), c.mask()).filter(TgCard::isHidden).orElse(null);
            if (hidden != null) {
                x.setText("🙈 shaxsiy karta *" + c.mask() + " — mazmuni saqlanmadi");
                x.setCardMask(c.mask());
                x.setVerdict("SHAXSIY");
                x.setNote("shaxsiy karta");
                hidden.setUpdatedAt(Instant.now());   // faqat «xabar kelyapti» belgisi
                cardRepo.save(hidden);
                return;
            }
            x.setDir(c.dir());
            x.setAmount(c.amount());
            x.setBalance(c.balance());
            x.setCardMask(c.mask());
            x.setMerchant(c.merchant() == null ? "" : cut(c.merchant(), 200));
            updateCard(acc, x, c);
            x.setVerdict("KARTA");
            x.setNote((c.dir() == null ? "" : ("KIRIM".equals(c.dir()) ? "kirim " : "rasxod ") + (c.amount() == null ? "" : fmt(c.amount() / 100) + " · "))
                    + (c.balance() == null ? "" : "qoldiq " + fmt(c.balance() / 100)));
        }
        // 2) kalit so'z / shablon — karta bo'lsa ham qo'shimcha ogohlantirish berishi mumkin
        for (String kw : cfg.keywords())
            if (low.contains(kw)) { x.setVerdict("OGOH"); x.setNote("kalit so'z: «" + kw + "»" + (x.getNote().isBlank() ? "" : " · " + x.getNote())); return; }
        if (isCard) return;
        // 3) karta emas: summani bizdagi yozuv bilan solishtirish (ixtiyoriy)
        Long amt = TgCardParser.toTiyin(firstMoney(x.getText()));
        x.setAmount(amt);
        if (cfg.match() && amt != null && acc.getKassaId() != null && x.getMsgAt() != null) {
            LocalDate d = x.getMsgAt().toLocalDate();
            DayRecord day = dayRepo.findByKassaIdAndDate(acc.getKassaId(), d).orElse(null);
            long tol = cfg.matchTol();
            if (day != null) {
                long[] cands = {day.getPrixodTerminal() * 100, day.getPrixodKlik() * 100, day.getPrixodNaqd() * 100};
                for (long cc : cands) if (Math.abs(cc - amt) <= tol * 100 && cc > 0) { x.setVerdict("MOS"); x.setNote("kun yozuvi bilan mos: " + fmt(cc / 100) + " so'm"); return; }
                x.setVerdict("NOMOS"); x.setNote("kun yozuvida bu summa yo'q (terminal " + fmt(day.getPrixodTerminal()) + ")");
                return;
            }
            x.setVerdict("NOMOS"); x.setNote("bu kun uchun kassa yozuvi yo'q");
            return;
        }
        x.setVerdict("OK");
    }

    /** Karta qoldig'ini yangilash (mask kesimida oxirgi holat). Bog'langan ClickAccount bo'lsa, uning
     * "карта қолдиғи" (📲 Клик hisoboti — MoySklad bilan solishtiriladigan qator) ham avtomat to'ldiriladi —
     * FAQAT oxirgi qoldiq (har kirim/chiqim emas), qo'lda /karta kiritishning o'rnini bosadi. */
    private void updateCard(TgAkkaunt acc, TgXabar x, TgCardParser.Card c) {
        String bot = x.getSourceBot();
        TgCard card = cardRepo.findBySourceBotAndMask(bot, c.mask())
                .orElseGet(() -> TgCard.builder().sourceBot(bot).mask(c.mask()).build());
        if (c.cardName() != null && !c.cardName().isBlank()) card.setName(cut(c.cardName(), 64));
        card.setPhone(acc.getPhone());
        if (card.getKassaId() == null) card.setKassaId(acc.getKassaId());
        if (c.balance() != null) card.setBalance(c.balance());
        card.setLastDir(c.dir());
        card.setLastAmount(c.amount());
        if (c.merchant() != null) card.setLastMerchant(cut(c.merchant(), 200));
        if (c.at() != null) card.setLastTxnAt(c.at());
        card.setUpdatedAt(Instant.now());
        cardRepo.save(card);
        if (c.balance() != null && card.getClickAccountId() != null) {
            clickRepo.findById(card.getClickAccountId()).ifPresent(ca -> {
                ca.setCardBalance(c.balance());
                ca.setCardBalanceAt(Instant.now());
                ca.setCardBalanceBy("HUMOcardbot");
                clickRepo.save(ca);
            });
        }
    }

    /** Karta emas xabarlar uchun: matndagi birinchi «… UZS/so'm» summa satri (tiyin uchun toTiyin bilan). */
    private static String firstMoney(String text) {
        if (text == null) return null;
        Matcher m = AMOUNT.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /* ==================== ogohlantirish ==================== */

    private void notify(TgAkkaunt acc, TgXabar x) {
        String who = acc.getName().isBlank() ? acc.getPhone() : acc.getName() + " (" + acc.getPhone() + ")";
        String head = ("NOMOS".equals(x.getVerdict()) ? "⚠️" : "OGOH".equals(x.getVerdict()) ? "🔔" : "ℹ️")
                + " <b>Bot xabari</b> — " + esc(x.getSourceBot()) + "\n"
                + "👤 " + esc(who) + (acc.getKassaId() == null ? "" : " · " + esc(notifier.kassaName(acc.getKassaId()))) + "\n";
        String body = (x.getNote().isBlank() ? "" : esc(x.getNote()) + "\n")
                + (x.getAmount() == null ? "" : "💵 " + fmt(x.getAmount()) + " so'm\n")
                + "━━━━━━━━━━━━━━━━━━━━\n<i>" + esc(cut(x.getText(), 600)) + "</i>";
        String text = head + body;
        InlineKeyboardMarkup kb = inline(List.of(irow(btn("📨 Bot xabarlari", "tg:m"))));
        Set<AppUser> to = new LinkedHashSet<>(notifier.superadmins());
        to.addAll(notifier.heads(acc.getKassaId()));
        notifier.send(NotifySwitches.TG_XABAR, to, text, kb);
        for (Long chatId : cfg.chatIds()) try { sender.send(chatId, text); } catch (Exception e) { log.warn("Bot xabari guruhga yuborilmadi ({}): {}", chatId, e.getMessage()); }
        x.setNotifiedAt(LocalDateTime.now(zone));
    }

    /* ==================== jimlik nazorati (Jobs) ==================== */

    /** Har akkaunt uchun: heartbeat/xabar shu soatdan ko'p kelmasa — bir marta ogohlantirish (kuniga bir). */
    public void silenceTick() {
        if (!cfg.enabled()) return;
        int h = cfg.silenceHours();
        Instant limit = Instant.now().minusSeconds(h * 3600L);
        LocalDate today = LocalDate.now(zone);
        List<String> quiet = new ArrayList<>();
        for (TgAkkaunt a : accRepo.findByActiveTrueOrderByPhoneAsc())
            if (a.getLastSeenAt() == null || a.getLastSeenAt().isBefore(limit))
                quiet.add((a.getName().isBlank() ? a.getPhone() : a.getName()) + (a.getLastSeenAt() == null ? " (hech ulanmagan)" : " (oxirgi " + a.getLastSeenAt().atZone(zone).toLocalDate() + ")"));
        if (quiet.isEmpty()) { cfg.set(TgReaderConfig.SILENCE_SENT, ""); return; }
        if (today.toString().equals(cfg.get(TgReaderConfig.SILENCE_SENT).orElse(""))) return;
        cfg.set(TgReaderConfig.SILENCE_SENT, today.toString());
        String text = "⚠️ <b>Bot xabarlari nazorati</b>\n\n" + h + " soatdan ko'p vaqt xabar/ulanish yo'q:\n• " + esc(String.join("\n• ", quiet))
                + "\n\n<i>tg-reader servisi to'xtagan yoki akkaunt uzilgan bo'lishi mumkin.</i>";
        notifier.send(NotifySwitches.TG_JIM, notifier.superadmins(), text, null);
        for (Long chatId : cfg.chatIds()) try { sender.send(chatId, text); } catch (Exception ignored) { }
    }

    /* ==================== 💰 qoldiq so'rovi / 🔐 xavfsizlik (tg-reader orqali) ==================== */

    private TgAccountGateway gw() { return gatewayProvider.getIfAvailable(); }

    public boolean balanceConfigured() { return cfg.enabled() && !cfg.balanceSteps().isEmpty(); }
    public int balanceBeforeMin() { return cfg.balanceBeforeMin(); }

    /** Barcha faol akkauntlardan (phone == null) yoki bittasidan asosiy botga buyruq yuborib qoldiqni so'rash. Javoblar
     *  oddiy xabar sifatida ham keladi (ingest → karta yangilanadi); bu yerda odam o'qiydigan xulosa qaytariladi. */
    public String requestBalances(String phone, String reason) {
        var gw = gw();
        List<String> steps = cfg.balanceSteps();
        if (gw == null || steps.isEmpty()) return "⚠️ Qoldiq so'rovi sozlanmagan (💰 Qoldiq so'rovi)";
        List<TgAkkaunt> list = phone == null ? accRepo.findByActiveTrueOrderByPhoneAsc() : accRepo.findById(phone).map(a -> List.of(a)).orElse(List.of());
        StringBuilder sb = new StringBuilder();
        for (TgAkkaunt a : list) {
            TgAccountGateway.BalanceResult r;
            try { r = gw.balance(a.getPhone(), steps); } catch (Exception e) { r = new TgAccountGateway.BalanceResult(false, e.toString(), List.of()); }
            log.info("Qoldiq so'rovi ({}, {}): {} — {}", a.getPhone(), reason, r.ok() ? "ok" : "xato", r.message());
            sb.append(r.ok() ? "✅ " : "⚠️ ").append(esc(a.getName().isBlank() ? a.getPhone() : a.getName())).append(": ").append(esc(r.message())).append("\n");
            for (String rep : r.replies()) sb.append("   ↳ <i>").append(esc(cut(rep.replaceAll("\\s+", " "), 120))).append("</i>\n");
        }
        return sb.length() == 0 ? "⚠️ Faol akkaunt yo'q" : sb.toString().trim();
    }

    /** Akkaunt xavfsizligi: tg-reader'dan jonli seanslar + 2FA; saqlaydi va YANGI (avval ko'rilmagan) seans bo'lsa ogohlantiradi. */
    public TgAccountGateway.SecurityInfo refreshSecurity(TgAkkaunt a, boolean notifyNew) {
        var gw = gw();
        if (gw == null) return null;
        TgAccountGateway.SecurityInfo info;
        try { info = gw.security(a.getPhone()); } catch (Exception e) { info = new TgAccountGateway.SecurityInfo(false, e.toString(), null, List.of()); }
        if (info == null || !info.ok()) return info;
        Set<Long> known = new HashSet<>();
        boolean first = a.getSessionsJson() == null;
        if (!first) try { for (JsonNode n : om.readTree(a.getSessionsJson())) known.add(n.path("hash").asLong()); } catch (Exception ignored) { }
        List<TgAccountGateway.SessionInfo> fresh = info.sessions().stream().filter(s -> !s.current() && !known.contains(s.hash())).toList();
        try { a.setSessionsJson(om.writeValueAsString(info.sessions())); } catch (Exception ignored) { }
        a.setTwoFa(info.twoFa());
        a.setSessionsAt(Instant.now());
        accRepo.save(a);
        if (notifyNew && !first && !fresh.isEmpty()) {
            StringBuilder sb = new StringBuilder("🔐 <b>Akkauntga yangi qurilma kirdi</b>\n👤 ")
                    .append(esc(a.getName().isBlank() ? a.getPhone() : a.getName() + " (" + a.getPhone() + ")")).append("\n");
            for (var s : fresh) sb.append("📱 ").append(esc(sessionLine(s))).append("\n");
            sb.append("\n<i>Agar bu siz emas — Telegram → Sozlamalar → Qurilmalar → seansni tugating va parolni almashtiring.</i>");
            Set<AppUser> to = new LinkedHashSet<>(notifier.superadmins());
            if (a.getUserId() != null) userRepo.findById(a.getUserId()).ifPresent(to::add);
            notifier.send(NotifySwitches.TG_XAVFSIZLIK, to, sb.toString(), null);
            log.info("Xavfsizlik ({}): {} ta yangi seans — ogohlantirildi", a.getPhone(), fresh.size());
        }
        return info;
    }

    /** Seans qatori: qurilma · ilova · davlat · oxirgi faollik (Toshkent). */
    public static String sessionLine(TgAccountGateway.SessionInfo s) {
        String when = "";
        if (s.dateActive() != null) {
            try { when = java.time.OffsetDateTime.parse(s.dateActive()).atZoneSameInstant(ZoneId.of("Asia/Tashkent")).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")); }
            catch (Exception e) { when = s.dateActive(); }
        }
        return (s.device().isBlank() ? "?" : s.device()) + " · " + s.app() + (s.country().isBlank() ? "" : " · " + s.country()) + (when.isBlank() ? "" : " · " + when);
    }

    /** Soatiga bir (Jobs, har 5 daqiqada chaqiriladi): barcha faol akkauntlar seanslarini tekshirish. */
    public void securityTick() {
        if (!cfg.enabled() || gw() == null) return;
        String mark = LocalDate.now(zone) + "#" + LocalDateTime.now(zone).getHour();
        if (mark.equals(cfg.get(TgReaderConfig.SECURITY_AT).orElse(""))) return;
        cfg.set(TgReaderConfig.SECURITY_AT, mark);
        for (TgAkkaunt a : accRepo.findByActiveTrueOrderByPhoneAsc())
            try { refreshSecurity(a, true); } catch (Exception e) { log.warn("Xavfsizlik tekshiruvi ({}): {}", a.getPhone(), e.getMessage()); }
    }

    /* ==================== o'qish (bot/web) ==================== */

    public List<TgAkkaunt> accounts() { return accRepo.findAll(); }
    public TgAkkaunt account(String phone) { return accRepo.findById(phone).orElse(null); }
    public void saveAccount(TgAkkaunt a) { accRepo.save(a); }

    /** Akkauntni botdagi xodimga bog'lash (QR-login tugagach). Qator hali yo'q bo'lsa ham yaratadi — tg-reader'ning
     * o'z {@code upsertAccount} chaqiruvi (ism/tgUserId) BILAN BIR VAQTDA, tartibsiz kelishi mumkin (HTTP orqali). */
    public void linkUser(String phone, Long userId) {
        TgAkkaunt a = accRepo.findById(phone).orElseGet(() -> TgAkkaunt.builder().phone(phone).createdAt(Instant.now()).build());
        a.setUserId(userId);
        accRepo.save(a);
    }
    /** Yoqish/o'chirish (o'chiq — TDLib ulanmaydi). */
    public void setActive(String phone, boolean active) {
        accRepo.findById(phone).ifPresent(a -> { a.setActive(active); accRepo.save(a); });
    }
    public boolean isActive(String phone) { return accRepo.findById(phone).map(TgAkkaunt::isActive).orElse(true); }
    /** Akkaunt qatorini butunlay o'chirish (xabarlari qoladi). */
    public void deleteAccount(String phone) { accRepo.deleteById(phone); }

    public TgXabar message(long id) { return msgRepo.findById(id).orElse(null); }

    public List<TgXabar> recent(String phone, String verdictFilter, int limit) {
        PageRequest p = PageRequest.of(0, limit);
        if (phone != null) return msgRepo.findByPhoneOrderByMsgAtDescIdDesc(phone, p);
        if ("FLAG".equals(verdictFilter)) return msgRepo.findByVerdictInOrderByMsgAtDescIdDesc(List.of("OGOH", "NOMOS"), p);
        return msgRepo.findByOrderByMsgAtDescIdDesc(p);
    }

    public long[] stats() {
        return new long[]{accRepo.countByActiveTrue(), msgRepo.count(),
                msgRepo.countByVerdict("OGOH") + msgRepo.countByVerdict("NOMOS"),
                msgRepo.countByMsgAtAfter(LocalDate.now(zone).atStartOfDay())};
    }

    private LocalDateTime parseAt(String iso) {
        try { return java.time.OffsetDateTime.parse(iso).atZoneSameInstant(zone).toLocalDateTime(); }
        catch (Exception e) { try { return LocalDateTime.parse(iso); } catch (Exception e2) { return null; } }
    }

    private static String cut(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n - 1) + "…" : s; }
}
