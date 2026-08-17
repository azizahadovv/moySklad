package uz.kassa.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.ZoneId;

@Component
@ConfigurationProperties(prefix = "app")
@Getter @Setter
public class AppProps {
    private String zone = "Asia/Tashkent";
    private int reminderHour = 21;
    private Bot bot = new Bot();
    private Superadmin superadmin = new Superadmin();
    private Moysklad moysklad = new Moysklad();

    /** Telegram Mini App ochiladigan tashqi HTTPS manzil (bo'sh — tugma ko'rsatilmaydi). */
    private String webappUrl = "";

    public ZoneId zoneId() { return ZoneId.of(zone); }

    @Getter @Setter public static class Bot {
        private String token;
        private String username;
    }
    @Getter @Setter public static class Superadmin {
        private Long telegramId = 0L;
        private String name = "SuperAdmin";
    }
    @Getter @Setter public static class Moysklad {
        private String token;
        private String baseUrl = "https://api.moysklad.ru/api/remap/1.2";
        private int syncOverlapMinutes = 2;
    }
}
