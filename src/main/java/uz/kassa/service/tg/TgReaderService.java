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

    /** tg-reader qayerdan davom etsin: oxirgi ma'lum xabar id. */
    public long lastMsgId(String phone) {
        return accRepo.findById(phone).map(TgAkkaunt::getLastMsgId).orElse(0L);
    }

    /** Kelgan xabarlar (bir yoki bir nechta). Yangi xabarlar tekshiriladi va kerak bo'lsa ogohlantiriladi. */
    public void ingest(String phone, List<Msg> messages) {
        TgAkkaunt acc = accRepo.findById(phone).orElse(null);
        if (acc == null) { upsertAccount(phone, "", null, "", ""); acc = accRepo.findById(phone).orElse(null); }
        if (acc == null || messages == null || messages.isEmpty()) return;
        long maxId = acc.getLastMsgId();
        LocalDateTime maxAt = acc.getLastMsgAt();
        for (Msg m : messages) {
            if (msgRepo.findByPhoneAndMsgId(phone, m.msgId()).isPresent()) continue;
            LocalDateTime at = parseAt(m.date());
            TgXabar x = TgXabar.builder().phone(phone).sourceBot(m.sourceBot() == null ? acc.getSourceBot() : m.sourceBot())
                    .msgId(m.msgId()).msgAt(at).text(cut(m.text(), 8000)).media(m.media() == null ? "" : m.media())
                    .verdict("YANGI").createdAt(Instant.now()).build();
            check(acc, x);
            msgRepo.save(x);
            if (m.msgId() > maxId) maxId = m.msgId();
            if (at != null && (maxAt == null || at.isAfter(maxAt))) maxAt = at;
            // per-xabar ogohlantirish faqat kalit so'z / nomuvofiqlikda (karta qoldig'i jimgina yig'iladi, guruhga hisobot alohida)
            if ("OGOH".equals(x.getVerdict()) || "NOMOS".equals(x.getVerdict())) notify(acc, x);
        }
        acc.setLastMsgId(maxId);
        acc.setLastMsgAt(maxAt);
        acc.setLastSeenAt(Instant.now());
        acc.setLastError(null);
        accRepo.save(acc);
    }

    /** tg-reader'dan keladigan xabar. */
    public record Msg(long msgId, String date, String text, String media, String sourceBot) {}

    /* ==================== tekshiruv ==================== */

    private void check(TgAkkaunt acc, TgXabar x) {
        String low = x.getText() == null ? "" : x.getText().toLowerCase();
        // 1) karta shabloni (HUMOCARD kabi): qoldiqni avtomat yig'ish
        TgCardParser.Card c = TgCardParser.parse(x.getText());
        boolean isCard = c != null && c.usable();
        if (isCard) {
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

    /** Karta qoldig'ini yangilash (mask kesimida oxirgi holat). */
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

    /* ==================== o'qish (bot/web) ==================== */

    public List<TgAkkaunt> accounts() { return accRepo.findAll(); }
    public TgAkkaunt account(String phone) { return accRepo.findById(phone).orElse(null); }
    public void saveAccount(TgAkkaunt a) { accRepo.save(a); }

    /** Akkauntni botdagi xodimga bog'lash (TDLib login tugagach). */
    public void linkUser(String phone, Long userId) {
        accRepo.findById(phone).ifPresent(a -> { a.setUserId(userId); accRepo.save(a); });
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
