package uz.kassa.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import uz.kassa.bot.KassaBot;

@Component
@RequiredArgsConstructor
@Slf4j
public class BotInitializer {

    private final KassaBot bot;
    private final AppProps props;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        String token = props.getBot().getToken();
        if (token == null || token.isBlank()) {
            log.warn("BOT_TOKEN berilmagan — Telegram bot ishga tushirilmadi");
            return;
        }
        try {
            new TelegramBotsApi(DefaultBotSession.class).registerBot(bot);
            log.info("Telegram bot ishga tushdi: @{}", props.getBot().getUsername());
            setupCommandScopes();
        } catch (Exception e) {
            log.error("Botni ishga tushirishda xato", e);
        }
    }

    /**
     * Buyruqlar menyusi: shaxsiy chatda /start /kunlik /karta /clear; guruh/kanalda
     * /kunlik /karta; guruh adminlariga qo'shimcha sozlash buyruqlari (02.09.2026,
     * foydalanuvchi talabi — avval guruhlarda menyu umuman chiqmas edi).
     */
    private void setupCommandScopes() {
        try {
            java.util.function.BiFunction<String, String, org.telegram.telegrambots.meta.api.objects.commands.BotCommand> c =
                    (n, d) -> new org.telegram.telegrambots.meta.api.objects.commands.BotCommand(n, d);
            // Shaxsiy chat: hamma uchun
            var priv = java.util.List.of(
                    c.apply("start", "Botni ishga tushirish"),
                    c.apply("kunlik", "Kunlik kassa solishtirish (rasm + Excel)"),
                    c.apply("karta", "Karta qoldig'ini kiritish"),
                    c.apply("clear", "Chatni tozalash"));
            // Guruh/kanal: oddiy a'zolar
            var group = java.util.List.of(
                    c.apply("kunlik", "Kunlik kassa solishtirish (rasm + Excel)"),
                    c.apply("karta", "Karta qoldig'ini kiritish"));
            // Guruh adminlari: qo'shimcha sozlash buyruqlari
            var admins = java.util.List.of(
                    c.apply("kunlik", "Kunlik kassa solishtirish (rasm + Excel)"),
                    c.apply("karta", "Karta qoldig'ini kiritish"),
                    c.apply("auditclick", "Click va naqd auditi"),
                    c.apply("dukon", "Otdel yonidagi do'kon nomi"),
                    c.apply("setclickgroup", "Shu chatni Click guruhi qilish"));
            bot.execute(org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands.builder()
                    .commands(priv)
                    .scope(org.telegram.telegrambots.meta.api.objects.commands.scope
                            .BotCommandScopeAllPrivateChats.builder().build())
                    .build());
            bot.execute(org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands.builder()
                    .commands(group)
                    .scope(org.telegram.telegrambots.meta.api.objects.commands.scope
                            .BotCommandScopeAllGroupChats.builder().build())
                    .build());
            bot.execute(org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands.builder()
                    .commands(admins)
                    .scope(org.telegram.telegrambots.meta.api.objects.commands.scope
                            .BotCommandScopeAllChatAdministrators.builder().build())
                    .build());
            bot.execute(org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands.builder()
                    .commands(group)
                    .scope(org.telegram.telegrambots.meta.api.objects.commands.scope
                            .BotCommandScopeDefault.builder().build())
                    .build());
            log.info("Buyruqlar menyusi sozlandi: shaxsiy {} ta, guruh {} ta, admin {} ta",
                    priv.size(), group.size(), admins.size());
        } catch (Exception e) {
            log.warn("Buyruqlar scope'ini sozlashda xato: {}", e.getMessage());
        }
    }
}
