package uz.kassa.bot;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionStore {
    private final Map<Long, Session> map = new ConcurrentHashMap<>();
    public Session get(long telegramId) {
        return map.computeIfAbsent(telegramId, k -> new Session());
    }
}
