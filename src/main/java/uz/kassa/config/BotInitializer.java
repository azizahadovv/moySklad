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
     * Buyruqlar menyusi FAQAT shaxsiy chatlarda ko'rinadi. Guruh/kanalda «/» bosilganda
     * /start, /clear kabi buyruqlar ro'yxati umuman chiqmaydi (default scope'dagi eski
     * BotFather buyruqlari ham o'chiriladi — guruh scope'lari bo'shligicha qoladi).
     */
    private void setupCommandScopes() {
        try {
            var cmds = java.util.List.of(
                    new org.telegram.telegrambots.meta.api.objects.commands.BotCommand(
                            "start", "Botni ishga tushirish"),
                    new org.telegram.telegrambots.meta.api.objects.commands.BotCommand(
                            "clear", "Chatni tozalash"));
            bot.execute(org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands.builder()
                    .commands(cmds)
                    .scope(org.telegram.telegrambots.meta.api.objects.commands.scope
                            .BotCommandScopeAllPrivateChats.builder().build())
                    .build());
            bot.execute(org.telegram.telegrambots.meta.api.methods.commands.DeleteMyCommands.builder()
                    .scope(org.telegram.telegrambots.meta.api.objects.commands.scope
                            .BotCommandScopeDefault.builder().build())
                    .build());
            bot.execute(org.telegram.telegrambots.meta.api.methods.commands.DeleteMyCommands.builder()
                    .scope(org.telegram.telegrambots.meta.api.objects.commands.scope
                            .BotCommandScopeAllGroupChats.builder().build())
                    .build());
            bot.execute(org.telegram.telegrambots.meta.api.methods.commands.DeleteMyCommands.builder()
                    .scope(org.telegram.telegrambots.meta.api.objects.commands.scope
                            .BotCommandScopeAllChatAdministrators.builder().build())
                    .build());
            log.info("Buyruqlar scope'i sozlandi: faqat shaxsiy chatlar, guruhlarda ko'rinmaydi");
        } catch (Exception e) {
            log.warn("Buyruqlar scope'ini sozlashda xato: {}", e.getMessage());
        }
    }
}
