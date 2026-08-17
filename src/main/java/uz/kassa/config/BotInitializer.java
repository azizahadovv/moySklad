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
        } catch (Exception e) {
            log.error("Botni ishga tushirishda xato", e);
        }
    }
}
