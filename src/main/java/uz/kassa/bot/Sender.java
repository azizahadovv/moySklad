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

    /** Yuborilgan xabar ID sini qaytaradi (panel xabarlarini keyin o'chirish uchun). */
    public Integer sendId(long chatId, String text, ReplyKeyboard kb) {
        SendMessage m = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .parseMode("HTML")
                .build();
        if (kb != null) m.setReplyMarkup(kb);
        try {
            return bot().execute(m).getMessageId();
        } catch (TelegramApiException e) {
            log.warn("Xabar yuborilmadi ({}): {}", chatId, e.getMessage());
            return null;
        }
    }

    /** Xabarni o'chirish (48 soatdan eski bo'lsa Telegram rad etadi — jim o'tamiz). */
    public void deleteMessage(long chatId, int messageId) {
        try {
            bot().execute(org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
                    .builder().chatId(String.valueOf(chatId)).messageId(messageId).build());
        } catch (TelegramApiException e) {
            log.debug("O'chirilmadi ({}:{}): {}", chatId, messageId, e.getMessage());
        }
    }

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

    /**
     * Chatni tozalash: joriy xabardan orqaga `count` ta xabarni o'chiradi.
     * Bot API deleteMessages (100 talik partiyalar) — topilmaganlari jim o'tkaziladi.
     * Fonda ishlaydi, botni bloklamaydi.
     */
    public void clearChat(long chatId, int fromMessageId, int count) {
        new Thread(() -> {
            try {
                String token = bot().getBotToken();
                java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
                int start = Math.max(1, fromMessageId - count + 1);
                StringBuilder ids = new StringBuilder();
                int inBatch = 0;
                for (int i = fromMessageId; i >= start; i--) {
                    if (inBatch > 0) ids.append(',');
                    ids.append(i);
                    if (++inBatch == 100) {
                        batchDelete(http, token, chatId, ids.toString());
                        ids.setLength(0); inBatch = 0;
                    }
                }
                if (inBatch > 0) batchDelete(http, token, chatId, ids.toString());
            } catch (Exception e) {
                log.warn("clearChat ({}): {}", chatId, e.getMessage());
            }
        }).start();
    }

    private void batchDelete(java.net.http.HttpClient http, String token,
                             long chatId, String ids) throws Exception {
        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        "https://api.telegram.org/bot" + token + "/deleteMessages"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        "{\"chat_id\":" + chatId + ",\"message_ids\":[" + ids + "]}"))
                .build();
        http.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
    }

    public void answer(String callbackId) {
        try {
            bot().execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());
        } catch (TelegramApiException e) {
            log.debug("answerCallback: {}", e.getMessage());
        }
    }
}
