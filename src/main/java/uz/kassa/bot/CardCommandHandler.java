package uz.kassa.bot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import uz.kassa.bot.handlers.AdminHandler;
import uz.kassa.bot.handlers.BuxgalterHandler;
import uz.kassa.bot.handlers.KassirHandler;
import uz.kassa.domain.*;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.OperationRepo;
import uz.kassa.repo.SubmissionRepo;
import uz.kassa.service.*;
import java.util.Optional;
import static uz.kassa.bot.TextUtil.*;

/**
 * Karta qoldig'i tugmalari (tasdiq/tuzatish) va /karta, /kartamas buyruqlari.
 * (Router dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CardCommandHandler {

    private final AppUserRepo userRepo;
    private final Sender sender;
    private final AuditService audit;
    private final uz.kassa.repo.ClickAccountRepo clickRepo;
    private final uz.kassa.service.notify.NotifyService notifySvc;
    private final CardCaptureHandler cardCapture;


    /**
     * Karta/summa tanlash tugmalari: kb (karta tanlandi), ks (summa tanlandi),
     * ku (summa tanlandi, endi karta so'raladi), kx (bekor). Oxirgi bo'lak — yuborgan
     * odamning tgId'si: faqat o'sha odam (yoki SuperAdmin) bosa oladi.
     */
    void kartaPickDecision(CallbackQuery cb, String data, long chatId, int msgId) {
        try {
            String[] p = data.split(":");
            long presser = cb.getFrom().getId();
            long senderTg = Long.parseLong(p[p.length - 1]);
            boolean superadmin = userRepo.findByTelegramId(presser)
                    .filter(AppUser::isActive)
                    .map(x -> x.getRole() == Role.SUPERADMIN).orElse(false);
            if (presser != senderTg && !superadmin) {
                // FAQAT yuborgan odam yoki SuperAdmin — begona bosganda pop-up bilan
                // tushuntiriladi va logga yoziladi (avval jim edi, kim bosgani bilinmasdi)
                log.info("Karta tugmasi RAD: {} ({}) bosdi, ruxsat faqat {} yoki SuperAdmin — data {}",
                        presser, cardCapture.displayName(cb.getFrom()), senderTg, data);
                sender.answerAlert(cb.getId(), "⛔ Bu tugmani faqat rasmni/summani yuborgan odam "
                        + "yoki SuperAdmin bosa oladi.");
                return;
            }
            log.info("Karta tugmasi: {} ({}) bosdi{} — data {}", presser, cardCapture.displayName(cb.getFrom()),
                    superadmin && presser != senderTg ? " [SuperAdmin]" : "", data);
            switch (p[0]) {
                case "kx" -> sender.edit(chatId, msgId, "❌ Bekor qilindi");
                case "kq" -> {
                    sender.edit(chatId, msgId,
                            "✅ O'zgarishsiz qoldirildi — avvalgi saqlash kuchda.");
                    // Tuzatish yakunlandi — o'chirish taymeri 0 dan qayta
                    notifySvc.scheduleDelete(chatId, msgId, notifySvc.confirmDeleteMin());
                }
                case "kf" -> {   // ✏️ Tuzatish: to'g'ri kartani tanlash oynasi
                    long cardId = Long.parseLong(p[1]);
                    long sum = Long.parseLong(p[2]);
                    notifySvc.cancelDelete(chatId, msgId);   // odam tanlayotganda o'chib ketmasin
                    sender.edit(chatId, msgId, "✏️ <b>Tuzatish</b> — summa: <code>" + CardCaptureHandler.fmtT(sum)
                            + "</code> so'm\nTO'G'RI kartani tanlang:",
                            cardCapture.movePickKb(cardId, sum, senderTg));
                }
                case "km" -> {   // to'g'ri karta tanlandi — ko'chirish
                    long newId = Long.parseLong(p[1]);
                    long oldId = Long.parseLong(p[2]);
                    long sum = Long.parseLong(p[3]);
                    cardCapture.moveCardBalance(oldId, newId, sum, senderTg, cb.getFrom(), chatId, msgId);
                }
                case "ku", "kp" -> {   // summa tanlandi / ⬅️ boshqa karta — karta ro'yxati
                    long sum = Long.parseLong(p[1]);
                    sender.edit(chatId, msgId, "Summa: <code>" + CardCaptureHandler.fmtT(sum)
                            + "</code> so'm\nQAYSI kartaning qoldig'i?", cardCapture.cardPickKb(sum, senderTg));
                }
                case "kb" -> {   // karta tanlandi — DARHOL SAQLANMAYDI: avval tasdiq,
                                 // adashgan bo'lsa ⬅️ bilan orqaga qaytadi (foydalanuvchi talabi)
                    long cardId = Long.parseLong(p[1]);
                    long sum = Long.parseLong(p[2]);
                    var co = clickRepo.findById(cardId);
                    if (co.isEmpty()) { sender.edit(chatId, msgId, "⚠️ Hisob topilmadi"); return; }
                    sender.edit(chatId, msgId, "💳 <b>" + esc(co.get().getName()) + "</b>\n"
                            + "Summa: <code>" + CardCaptureHandler.fmtT(sum) + "</code> so'm\n\nTo'g'rimi?",
                            Keyboards.inline(java.util.List.of(Keyboards.irow(
                                    Keyboards.btn("✅ Ha, saqlansin",
                                            "kv:" + cardId + ":" + sum + ":" + senderTg),
                                    Keyboards.btn("⬅️ Boshqa karta",
                                            "kp:" + sum + ":" + senderTg)))));
                }
                case "kv", "ks" -> {   // yakuniy tasdiq (kv) / karta ma'lum, summa tanlandi (ks)
                    long cardId = Long.parseLong(p[1]);
                    long sum = Long.parseLong(p[2]);
                    var co = clickRepo.findById(cardId);
                    if (co.isEmpty()) { sender.edit(chatId, msgId, "⚠️ Hisob topilmadi"); return; }
                    cardCapture.saveCardBalance(co.get(), sum, cardCapture.displayName(cb.getFrom()), presser,
                            chatId, msgId, true);
                }
                default -> { }
            }
        } catch (Exception e) {
            log.debug("karta pick callback: {}", e.getMessage());
        }
    }


    /**
     * kc:y|n:&lt;cardId&gt;:&lt;sum&gt;:&lt;senderTgId&gt; — skrinshot (OCR) natijasini
     * tasdiqlash/rad etish. Faqat YUBORGAN ODAM (yoki SuperAdmin) bosa oladi —
     * begonalar bosishi jim e'tiborsiz qoldiriladi.
     */
    void kartaOcrDecision(CallbackQuery cb, String data, long chatId, int msgId) {
        try {
            String[] p = data.split(":");
            boolean yes = p[1].equals("y");
            long cardId = Long.parseLong(p[2]);
            long sum = Long.parseLong(p[3]);
            long senderTg = Long.parseLong(p[4]);
            long presser = cb.getFrom().getId();
            boolean superadmin = userRepo.findByTelegramId(presser)
                    .filter(AppUser::isActive)
                    .map(x -> x.getRole() == Role.SUPERADMIN).orElse(false);
            if (presser != senderTg && !superadmin) {
                log.info("OCR tasdiq tugmasi RAD: {} ({}) bosdi, ruxsat faqat {} yoki SuperAdmin",
                        presser, cardCapture.displayName(cb.getFrom()), senderTg);
                sender.answerAlert(cb.getId(), "⛔ Bu tugmani faqat skrinshotni yuborgan odam "
                        + "yoki SuperAdmin bosa oladi.");
                return;
            }
            log.info("OCR tasdiq tugmasi: {} ({}) bosdi{} — {}", presser, cardCapture.displayName(cb.getFrom()),
                    superadmin && presser != senderTg ? " [SuperAdmin]" : "", data);
            var co = clickRepo.findById(cardId);
            if (co.isEmpty()) { sender.edit(chatId, msgId, "⚠️ Hisob topilmadi"); return; }
            if (!yes) {
                sender.edit(chatId, msgId, "❌ <b>" + esc(co.get().getName())
                        + "</b> — skrinshotdagi summa RAD etildi.\nTo'g'ri summani yozing: "
                        + "<code>/karta " + cardId + " СУММА</code>");
                return;
            }
            cardCapture.saveCardBalance(co.get(), sum, cardCapture.displayName(cb.getFrom()), presser, chatId, msgId, true);
        } catch (Exception e) {
            log.debug("kc callback: {}", e.getMessage());
        }
    }


    /** 💳 /karta — hisoblar ro'yxati; /karta <id> <summa> — karta HAQIQIY qoldig'ini
     *  qayd etish (MoySklad'da bu raqam yo'q — faqat mas'ul kiritadi). */
    void handleKarta(AppUser user, String text, long chatId) {
        String[] p = text.trim().split("\\s+");
        var list = clickRepo.findByActiveTrueOrderByIdAsc();
        if (p.length < 3) {
            StringBuilder b = new StringBuilder("💳 <b>Karta qoldig'ini kiritish</b>\n"
                    + "Format: <code>/karta ID SUMMA</code> — masalan <code>/karta 2 12804310.45</code> (tiyin ham bo'ladi)\n\n");
            for (var c : list)
                b.append(c.getId()).append(". ").append(esc(c.getName()))
                 .append(c.getCardBalance() == null ? " — <i>kiritilmagan</i>"
                        : " — " + uz.kassa.bot.TextUtil.fmtTiyin(c.getCardBalance()) + " so'm")
                 .append("\n");
            sender.send(chatId, b.toString());
            return;
        }
        long id;
        try { id = Long.parseLong(p[1]); }
        catch (NumberFormatException e) {
            sender.send(chatId, "⚠️ ID raqam bo'lishi kerak. <code>/karta</code> deb yozib "
                    + "ro'yxatni ko'ring.");
            return;
        }
        var co = clickRepo.findById(id).filter(uz.kassa.domain.ClickAccount::isActive);
        if (co.isEmpty()) {
            sender.send(chatId, "⚠️ Bunday hisob topilmadi. <code>/karta</code> — ro'yxat.");
            return;
        }
        String sumS = String.join(" ", java.util.Arrays.copyOfRange(p, 2, p.length));
        long sum = uz.kassa.bot.TextUtil.parseAmountTiyin(sumS);   // TIYIN
        if (sum < 0) {
            sender.send(chatId, "⚠️ Summani so'mda kiriting, tiyin bo'lsa nuqta/vergul bilan: "
                    + "<code>12804310</code> yoki <code>12804310.45</code>");
            return;
        }
        var c = co.get();
        c.setCardBalance(sum);
        c.setCardBalanceAt(java.time.Instant.now());
        c.setCardBalanceBy(user.getFullName());
        clickRepo.save(c);
        audit.log(user.getId(), "KARTA_QOLDIQ", "click", id,
                user.getFullName() + ": " + c.getName() + " = " + sum);
        sender.send(chatId, "✅ <b>" + esc(c.getName()) + "</b> karta qoldig'i qayd etildi: <b>"
                + uz.kassa.bot.TextUtil.fmtTiyin(sum) + "</b> so'm\n"
                + "Keyingi hisobotda MoySklad bilan solishtirilib chiqadi.");
    }


    /** /kartamas <id> <matn> — hisob uchun mas'ulni o'rnatish ("@username" yoki
     *  "{id=123456;Ism}"); «-» — olib tashlash. Faqat SuperAdmin. */
    void handleKartaMas(AppUser user, String text, long chatId) {
        String[] p = text.trim().split("\\s+", 3);
        if (p.length < 3) {
            sender.send(chatId, "Format: <code>/kartamas ID @username</code> yoki "
                    + "<code>/kartamas ID {id=123456;Ism}</code>\n«-» — mas'ulni olib tashlash.");
            return;
        }
        long id;
        try { id = Long.parseLong(p[1]); }
        catch (NumberFormatException e) { sender.send(chatId, "⚠️ ID raqam bo'lishi kerak"); return; }
        var co = clickRepo.findById(id);
        if (co.isEmpty()) { sender.send(chatId, "⚠️ Hisob topilmadi"); return; }
        var c = co.get();
        c.setCardResponsible("-".equals(p[2].trim()) ? null : p[2].trim());
        clickRepo.save(c);
        audit.log(user.getId(), "KARTA_MASUL", "click", id,
                c.getName() + " -> " + p[2].trim());
        sender.send(chatId, "✅ <b>" + esc(c.getName()) + "</b> uchun mas'ul: "
                + (c.getCardResponsible() == null ? "<i>olib tashlandi</i>" : esc(c.getCardResponsible())));
    }

}
