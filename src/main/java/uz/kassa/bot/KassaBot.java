package uz.kassa.bot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;
import uz.kassa.config.AppProps;

@Component
@Slf4j
public class KassaBot extends TelegramLongPollingBot {

    private final AppProps props;
    private final Router router;

    public KassaBot(AppProps props, Router router) {
        super(props.getBot().getToken() == null ? "" : props.getBot().getToken());
        this.props = props;
        this.router = router;
    }

    @Override
    public String getBotUsername() {
        return props.getBot().getUsername();
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            router.route(update);
        } catch (Exception e) {
            log.error("Update qayta ishlashda xato", e);
        }
    }
}
