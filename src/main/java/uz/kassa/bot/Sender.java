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

    /** Guruhda aynan shu xabarga JAVOB (reply) — qaysi rasm haqida gap ketayotgani ko'rinsin. */
    public void reply(long chatId, int replyToMessageId, String text, ReplyKeyboard kb) {
        SendMessage m = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .parseMode("HTML")
                .replyToMessageId(replyToMessageId)
                .build();
        if (kb != null) m.setReplyMarkup(kb);
        try {
            bot().execute(m);
        } catch (TelegramApiException e) {
            log.warn("Javob yuborilmadi ({}): {}", chatId, e.getMessage());
            send(chatId, text, kb);   // asl xabar o'chirilgan bo'lsa — oddiy xabar
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
    /** Rasm (PNG) + izoh + tugma. Yuborilgan xabar ID si (tasdiq tugmasini keyin yangilash uchun); xato — null. */
    public Integer sendPhoto(long chatId, byte[] png, String filename, String caption, InlineKeyboardMarkup kb) {
        var ph = org.telegram.telegrambots.meta.api.methods.send.SendPhoto.builder()
                .chatId(String.valueOf(chatId))
                .photo(new org.telegram.telegrambots.meta.api.objects.InputFile(
                        new java.io.ByteArrayInputStream(png), filename))
                .caption(caption)
                .parseMode("HTML")
                .build();
        if (kb != null) ph.setReplyMarkup(kb);
        try {
            return bot().execute(ph).getMessageId();
        } catch (TelegramApiException e) {
            log.warn("Rasm yuborilmadi ({}): {}", chatId, e.getMessage());
            return null;
        }
    }

    /** Rasmli xabarning izohini (caption) yangilash. */
    public void editCaption(long chatId, int messageId, String caption, InlineKeyboardMarkup kb) {
        var ec = org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageCaption.builder()
                .chatId(String.valueOf(chatId))
                .messageId(messageId)
                .caption(caption)
                .parseMode("HTML")
                .build();
        if (kb != null) ec.setReplyMarkup(kb);
        try {
            bot().execute(ec);
        } catch (TelegramApiException e) {
            log.warn("Izoh tahrirlanmadi ({}): {}", chatId, e.getMessage());
        }
    }

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

    /** Telegram faylini (masalan skrinshotni) vaqtinchalik faylga yuklab olish. null — xato. */
    public java.io.File downloadTgFile(String fileId) {
        try {
            var f = bot().execute(org.telegram.telegrambots.meta.api.methods.GetFile.builder()
                    .fileId(fileId).build());
            String path = f.getFilePath();
            String ext = path != null && path.contains(".")
                    ? path.substring(path.lastIndexOf('.')) : ".jpg";
            java.io.File tmp = java.io.File.createTempFile("tgocr-", ext);
            String url = "https://api.telegram.org/file/bot" + bot().getBotToken() + "/" + path;
            try (var in = java.net.URI.create(url).toURL().openStream()) {
                java.nio.file.Files.copy(in, tmp.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return tmp;
        } catch (Exception e) {
            log.debug("downloadTgFile: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Chatni to'liq tozalash: 1-xabardan {@code fromMessageId}gacha — BARCHA yozishmalarni o'chiradi.
     * Bot API deleteMessages (100 talik partiyalar) — topilmaganlari/o'chirib bo'lmaydiganlari
     * (masalan 48 soatdan eski, Telegram cheklovi) jim o'tkaziladi. Fonda ishlaydi, botni bloklamaydi.
     */
    public void clearChat(long chatId, int fromMessageId) {
        new Thread(() -> {
            try {
                String token = bot().getBotToken();
                java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
                int start = 1;
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

    private volatile Long cachedBotId;

    /** Botning o'z Telegram ID si (GetChatMember uchun kerak) — bir marta olinib keshlanadi. */
    private long botId() throws TelegramApiException {
        Long id = cachedBotId;
        if (id == null) cachedBotId = id = bot().execute(
                new org.telegram.telegrambots.meta.api.methods.GetMe()).getId();
        return id;
    }

    /** Chat haqida ma'lumot (guruh/kanal ID to'g'riligini va nomini tekshirish uchun). null — topilmadi/ruxsat yo'q. */
    public org.telegram.telegrambots.meta.api.objects.Chat getChat(long chatId) {
        try {
            return bot().execute(org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat
                    .builder().chatId(String.valueOf(chatId)).build());
        } catch (TelegramApiException e) {
            log.debug("getChat ({}): {}", chatId, e.getMessage());
            return null;
        }
    }

    /** Guruh/kanal adminlari (botlar chiqarib tashlangan). Xatoda bo'sh ro'yxat. */
    public java.util.List<org.telegram.telegrambots.meta.api.objects.User> chatAdmins(long chatId) {
        try {
            var list = bot().execute(org.telegram.telegrambots.meta.api.methods.groupadministration
                    .GetChatAdministrators.builder().chatId(String.valueOf(chatId)).build());
            java.util.List<org.telegram.telegrambots.meta.api.objects.User> out = new java.util.ArrayList<>();
            for (var cm : list)
                if (!Boolean.TRUE.equals(cm.getUser().getIsBot())) out.add(cm.getUser());
            return out;
        } catch (TelegramApiException e) {
            log.debug("getChatAdministrators ({}): {}", chatId, e.getMessage());
            return java.util.List.of();
        }
    }

    /** Foydalanuvchining shu chatdagi holati ("member"/"administrator"/"creator"...). null — aniqlanmadi. */
    public String memberStatus(long chatId, long userId) {
        try {
            var cm = bot().execute(org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember
                    .builder().chatId(String.valueOf(chatId)).userId(userId).build());
            return cm.getStatus();
        } catch (TelegramApiException e) {
            log.debug("getChatMember ({}, {}): {}", chatId, userId, e.getMessage());
            return null;
        }
    }

    /** Botning shu chatdagi holati: "administrator", "member", "left", "kicked"... null — aniqlanmadi. */
    public String botStatusInChat(long chatId) {
        try {
            var cm = bot().execute(org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember
                    .builder().chatId(String.valueOf(chatId)).userId(botId()).build());
            return cm.getStatus();
        } catch (TelegramApiException e) {
            log.debug("getChatMember ({}): {}", chatId, e.getMessage());
            return null;
        }
    }

    /** Tugma bosgan odamga POP-UP ogohlantirish (faqat unga ko'rinadi). */
    public void answerAlert(String callbackId, String text) {
        try {
            bot().execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId)
                    .text(text).showAlert(true).build());
        } catch (TelegramApiException e) {
            log.debug("answerAlert: {}", e.getMessage());
        }
    }

    public void answer(String callbackId) {
        try {
            bot().execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());
        } catch (TelegramApiException e) {
            log.debug("answerCallback: {}", e.getMessage());
        }
    }

    /** Chat menyu tugmasi (≡, matn maydoni yonida) — Mini App'ni ochadi. Faqat shaxsiy chat. */
    public void setMenuButton(long chatId, String text, String url) {
        try {
            bot().execute(org.telegram.telegrambots.meta.api.methods.menubutton.SetChatMenuButton.builder()
                    .chatId(String.valueOf(chatId))
                    .menuButton(org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonWebApp.builder()
                            .text(text)
                            .webAppInfo(org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo.builder().url(url).build())
                            .build())
                    .build());
        } catch (TelegramApiException e) {
            log.debug("Menyu tugmasi qo'yilmadi ({}): {}", chatId, e.getMessage());
        }
    }

    /** Inline tugma bilan Mini App — initData to'liq keladi (reply-klaviatura tugmasidan farqli). */
    public void sendWebAppButton(long chatId, String text, String button, String url) {
        var b = org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                .text(button)
                .webApp(org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo.builder().url(url).build())
                .build();
        send(chatId, text, Keyboards.inline(java.util.List.of(Keyboards.irow(b))));
    }
}
