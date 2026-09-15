package uz.kassa.bot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.List;

/**
 * Rasm (PNG jadval) + hujjat(lar) (Excel) juftligini yuborish — "🕵️ Назорат" moduli push-hisobotlari
 * uchun umumiy pastki qatlam ({@code ControlNotifier} va {@code ControlWelcomeService} ikkalasi ham
 * shu orqali yuboradi, auditoriya filtri chaqiruvchida qoladi).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReportDispatcher {

    private final Sender sender;

    public record Doc(byte[] data, String filename) { }

    /** Tayyor hisobot: rasm baytlari + fayl nomi + caption (≤1024) + fallback matn (rasm ketmasa) + hujjat(lar). */
    public record Report(byte[] png, String pngName, String caption, String fallbackText, List<Doc> docs) { }

    /**
     * Bitta chatga: rasm (fileId berilgan bo'lsa qayta yuklamasdan, muvaffaqiyatsiz bo'lsa PNG baytidan
     * qayta urinib) + 0..N hujjat + caption (≤1024, Telegram photo caption limiti). Rasm umuman
     * ketmasa — fallbackText oddiy xabar sifatida (to'liq ro'yxat yo'qolmasligi uchun).
     * Qaytaradi: shu rasmning file_id'si (keyingi chaqiruvchiga uzatib, qayta yuklamasdan yuborish uchun).
     */
    public String send(long chatId, Report r, String fileId, InlineKeyboardMarkup kb) {
        return send(chatId, r.png(), r.pngName(), fileId, r.caption(), r.fallbackText(), kb, r.docs());
    }

    public String send(long chatId, byte[] png, String pngName, String fileId, String caption,
                        String fallbackText, InlineKeyboardMarkup kb, List<Doc> docs) {
        Sender.PhotoResult res = fileId != null
                ? sender.sendPhotoByFileId(chatId, fileId, caption, kb)
                : sender.sendPhotoGetFileId(chatId, png, pngName, caption, kb);
        if (res.messageId() == null && fileId != null)   // eskirgan/yaroqsiz fileId — baytdan qayta urinish
            res = sender.sendPhotoGetFileId(chatId, png, pngName, caption, kb);
        if (res.messageId() == null) {
            sender.send(chatId, fallbackText, kb);
        }
        if (docs != null)
            for (Doc d : docs) {
                try { sender.sendDocument(chatId, d.data(), d.filename(), null); }
                catch (Exception e) { log.warn("Hisobot hujjati yuborilmadi ({}): {}", chatId, e.getMessage()); }
            }
        return res.fileId();
    }
}
