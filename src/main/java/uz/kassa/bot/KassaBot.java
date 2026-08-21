package uz.kassa.bot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;
import uz.kassa.config.AppProps;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@Slf4j
public class KassaBot extends TelegramLongPollingBot {

    /**
     * Update'lar PARALLEL qayta ishlanadi — bitta foydalanuvchining og'ir so'rovi
     * (MoySklad sinxroni, Excel, kontragent balansi) boshqalarni to'xtatmasin.
     * Bir foydalanuvchining xabarlari esa DOIM ketma-ket (FSM buzilmasligi uchun):
     * har bir foydalanuvchi o'z Telegram ID si bo'yicha bitta ishchi oqimga tushadi.
     */
    private static final int WORKERS = 8;
    private final ExecutorService[] workers = new ExecutorService[WORKERS];

    private final AppProps props;
    private final Router router;

    public KassaBot(AppProps props, Router router) {
        super(props.getBot().getToken() == null ? "" : props.getBot().getToken());
        this.props = props;
        this.router = router;
        for (int i = 0; i < WORKERS; i++) {
            final int n = i;
            workers[i] = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "bot-worker-" + n);
                t.setDaemon(true);
                return t;
            });
        }
    }

    @Override
    public String getBotUsername() {
        return props.getBot().getUsername();
    }

    @Override
    public void onUpdateReceived(Update update) {
        long key = userKey(update);
        workers[Math.floorMod(Long.hashCode(key), WORKERS)].submit(() -> {
            try {
                router.route(update);
            } catch (Exception e) {
                log.error("Update qayta ishlashda xato", e);
            }
        });
    }

    /** Foydalanuvchi kaliti — shu bo'yicha bitta odam bitta oqimga bog'lanadi. */
    private long userKey(Update u) {
        if (u.hasCallbackQuery()) return u.getCallbackQuery().getFrom().getId();
        if (u.hasMessage() && u.getMessage().getFrom() != null)
            return u.getMessage().getFrom().getId();
        if (u.hasMessage()) return u.getMessage().getChatId();
        return 0;
    }
}
