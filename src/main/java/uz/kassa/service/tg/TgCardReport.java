package uz.kassa.service.tg;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.bot.Sender;
import uz.kassa.bot.TextUtil;
import uz.kassa.domain.TgCard;
import uz.kassa.repo.TgCardRepo;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import static uz.kassa.bot.TextUtil.esc;
import static uz.kassa.bot.TextUtil.fmt;

/**
 * 💳 Karta qoldiqlari guruh hisoboti — bot avtomat yig'gan oxirgi qoldiqlar (skrinshot o'rniga), Click hisoboti
 * uslubida guruh/kanalga. Interval va oyna ⚙️ 📨 Бот хабарлари. Har xabarda balans yangilanadi, hisobot davriy.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TgCardReport {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final String RULE = "━━━━━━━━━━━━━━━━━━━━";

    private final TgCardRepo cardRepo;
    private final uz.kassa.repo.ClickAccountRepo clickRepo;
    private final TgReaderConfig cfg;
    private final Sender sender;
    private final ZoneId zone = ZoneId.of("Asia/Tashkent");

    /** Hisobot matni (guruh va bot ichida bir xil). */
    public String text() {
        List<TgCard> cards = cardRepo.findAllByOrderByNameAscMaskAsc().stream()
                .filter(c -> !c.isHidden()).toList();   // shaxsiy karta — hisobotga kirmaydi
        StringBuilder sb = new StringBuilder("💳 <b>Карта қолдиқлари</b>\n<i>" + DTF.format(LocalDateTime.now(zone)) + " — бот автомат йиғган (скриншотсиз)</i>\n" + RULE + "\n");
        if (cards.isEmpty()) { sb.append("Ҳали карта хабари келмаган."); return sb.toString(); }
        long total = 0;
        boolean anyOld = false;
        java.time.LocalDate today = java.time.LocalDate.now(zone);
        for (TgCard c : cards) {
            total += c.getBalance();
            sb.append("💳 <b>").append(esc(c.getName().isBlank() ? "Карта" : c.getName())).append(" *").append(esc(c.getMask())).append("</b>");
            if (c.getClickAccountId() != null)   // qaysi Click hisobiga bog'langan — 📲 hisobotda shu nom bilan
                clickRepo.findById(c.getClickAccountId()).ifPresent(ca -> sb.append(" → 📲 ").append(esc(ca.getName())));
            sb.append("\n   қолдиқ <b>").append(TextUtil.fmtTiyin(c.getBalance())).append("</b> сўм");
            if (c.getLastTxnAt() != null) {
                // yo'nalish aniqlanmagan xabar «➖» emas, «•» — chiqim deb o'qilmasin
                String dir = "KIRIM".equals(c.getLastDir()) ? "➕" : "RASXOD".equals(c.getLastDir()) ? "➖" : "•";
                sb.append(" · охирги ").append(dir).append(c.getLastAmount() == null ? "" : " " + TextUtil.fmtTiyin(c.getLastAmount()))
                  .append(" (").append(DTF.format(c.getLastTxnAt())).append(")");
            }
            // ⏰ bugun bank botidan xabar kelmagan — qoldiq eski bo'lishi mumkin (01.11.2026 gacha yoshi ko'rinmasdi)
            if (c.getUpdatedAt() != null && java.time.LocalDate.ofInstant(c.getUpdatedAt(), zone).isBefore(today)) {
                anyOld = true;
                sb.append("\n   ⏰ <i>").append(DTF.format(LocalDateTime.ofInstant(c.getUpdatedAt(), zone))).append(" дан бери хабар йўқ</i>");
            }
            sb.append("\n");
        }
        sb.append(RULE).append("\nΣ <b>").append(TextUtil.fmtTiyin(total)).append("</b> сўм")
          .append("\n<i>Қолдиқ — банк ботининг охирги хабаридан.").append(anyOld ? " ⏰ — бугун хабар келмаган, қолдиқ эски." : "")
          .append(" 🙈 шахсий карталар киритилмаган.</i>");
        return sb.toString();
    }

    /** Hozir guruh/kanalga yuborish. Qaytadi: yuborilgan chatlar soni. */
    public int reportNow() {
        String t = text();
        int n = 0;
        for (Long chatId : cfg.reportChatIds()) {
            try { sender.send(chatId, t); n++; }
            catch (Exception e) { log.warn("Karta hisoboti guruhga yuborilmadi ({}): {}", chatId, e.getMessage()); }
        }
        return n;
    }

    /** Davriy (Jobs, har soat :00): interval va oyna gate'idan o'tsa guruhga. */
    public void tick() {
        if (!cfg.enabled() || cfg.reportEveryH() == 0 || cfg.reportChatIds().isEmpty()) return;
        LocalDateTime now = LocalDateTime.now(zone);
        int h = now.getHour();
        if (h < cfg.reportFrom() || h > cfg.reportTo()) return;
        if (h % cfg.reportEveryH() != 0) return;
        String mark = now.toLocalDate() + "#" + h;
        if (mark.equals(cfg.get(TgReaderConfig.REPORT_SENT).orElse(""))) return;
        cfg.set(TgReaderConfig.REPORT_SENT, mark);
        reportNow();
    }
}
