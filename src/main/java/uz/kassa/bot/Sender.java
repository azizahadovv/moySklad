package uz.kassa.bot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Bot orqali xabar yuborish/tahrirlash.
 * ObjectProvider — siklik bog'liqlikni proxy'siz uzadi: KassaBot chaqiruv
 * paytida olinadi. (@Lazy CGLIB proxy bilan final execute(SendDocument)
 * metodi proxy ichida NPE berardi.)
 */
@Component
@Slf4j
public class Sender {

    private final ObjectProvider<KassaBot> botProvider;
    private volatile KassaBot bot;

    public Sender(ObjectProvider<KassaBot> botProvider) {
        this.botProvider = botProvider;
    }

    private KassaBot bot() {
        KassaBot b = bot;
        if (b == null) bot = b = botProvider.getObject();
        return b;
    }

    public void send(long chatId, String text) { send(chatId, text, null); }

    public void send(long chatId, String text, ReplyKeyboard kb) {
        SendMessage m = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .parseMode("HTML")
                .build();
        if (kb != null) m.setReplyMarkup(kb);
        try {
            bot().execute(m);
        } catch (TelegramApiException e) {
            log.warn("Xabar yuborilmadi ({}): {}", chatId, e.getMessage());
        }
    }

    public void edit(long chatId, int messageId, String text) { edit(chatId, messageId, text, null); }

    public void edit(long chatId, int messageId, String text, InlineKeyboardMarkup kb) {
        EditMessageText e = EditMessageText.builder()
                .chatId(String.valueOf(chatId))
                .messageId(messageId)
                .text(text)
                .parseMode("HTML")
                .build();
        if (kb != null) e.setReplyMarkup(kb);
        try {
            bot().execute(e);
        } catch (TelegramApiException ex) {
            log.warn("Xabar tahrirlanmadi ({}): {}", chatId, ex.getMessage());
        }
    }

    /** Fayl (hujjat) yuborish — Excel hisobotlar uchun. */
    public void sendDocument(long chatId, byte[] data, String filename, String caption) {
        var doc = org.telegram.telegrambots.meta.api.methods.send.SendDocument.builder()
                .chatId(String.valueOf(chatId))
                .document(new org.telegram.telegrambots.meta.api.objects.InputFile(
                        new java.io.ByteArrayInputStream(data), filename))
                .caption(caption)
                .parseMode("HTML")
                .build();
        try {
            bot().execute(doc);
        } catch (TelegramApiException e) {
            log.warn("Hujjat yuborilmadi ({}): {}", chatId, e.getMessage());
        }
    }

    public void answer(String callbackId) {
        try {
            bot().execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());
        } catch (TelegramApiException e) {
            log.debug("answerCallback: {}", e.getMessage());
        }
    }
}
