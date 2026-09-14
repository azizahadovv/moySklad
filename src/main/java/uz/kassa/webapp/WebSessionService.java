package uz.kassa.webapp;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import uz.kassa.config.AppProps;
import uz.kassa.domain.AppUser;
import uz.kassa.repo.AppUserRepo;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 🖥 Браузердан кириш (Telegram'дан ташқарида): бот бир мартали ҳавола беради
 * (`/login?t=…`), ҳавола сессия куки'сига алмашади. Куки имзоси — бот токени билан
 * HMAC-SHA256, ичида фақат `user.id` ва муддат; серверда сессия сақланмайди.
 * Mini App ичида эса аввалгидек Telegram initData ишлайди — иккиси ёнма-ён яшайди.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebSessionService {

    public static final String COOKIE = "kn_sess";
    /** Бир мартали ҳавола муддати. */
    private static final Duration LINK_TTL = Duration.ofMinutes(15);
    /** Браузер сессияси муддати. */
    private static final Duration SESSION_TTL = Duration.ofDays(30);

    private final WebUrlService webUrl;
    private final AppProps props;
    private final AppUserRepo userRepo;
    private final SecureRandom rnd = new SecureRandom();
    /** token → (userId, муддат). Бир марта ишлатилади; қайта юклашда йўқолади (ҳавола 15 дақиқалик). */
    private final Map<String, long[]> links = new ConcurrentHashMap<>();

    /* ---------------- бир мартали ҳавола ---------------- */

    /** Фойдаланувчи учун янги ҳавола: `<WEBAPP_URL>/login?t=…`; base бўш бўлса null. */
    public String loginLink(AppUser u) {
        String base = webUrl.url();
        if (base.isBlank()) return null;
        byte[] b = new byte[24];
        rnd.nextBytes(b);
        String t = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        links.entrySet().removeIf(e -> e.getValue()[1] < System.currentTimeMillis());   // эскиларини тозалаш
        links.put(t, new long[]{u.getId(), System.currentTimeMillis() + LINK_TTL.toMillis()});
        return base + "/login?t=" + t;
    }

    public int linkMinutes() { return (int) LINK_TTL.toMinutes(); }

    /** Ҳаволани ишлатиш (бир марта). Яроқсиз/эскирган — null. */
    public AppUser consume(String token) {
        if (token == null || token.isBlank()) return null;
        long[] v = links.remove(token);
        if (v == null || v[1] < System.currentTimeMillis()) return null;
        return userRepo.findById(v[0]).filter(AppUser::isActive).orElse(null);
    }

    /* ---------------- сессия куки ---------------- */

    /** Куки қиймати: `userId.exp.hmac`. */
    public String newCookie(AppUser u) {
        long exp = System.currentTimeMillis() + SESSION_TTL.toMillis();
        String body = u.getId() + "." + exp;
        return body + "." + sign(body);
    }

    public long cookieMaxAgeSeconds() { return SESSION_TTL.toSeconds(); }

    /** Жорий сўровдаги куки бўйича фойдаланувчи (йўқ/яроқсиз — null). */
    public AppUser fromRequest() {
        try {
            var attr = RequestContextHolder.getRequestAttributes();
            if (!(attr instanceof ServletRequestAttributes sra)) return null;
            HttpServletRequest req = sra.getRequest();
            if (req.getCookies() == null) return null;
            for (var c : req.getCookies())
                if (COOKIE.equals(c.getName())) return fromCookie(c.getValue());
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public AppUser fromCookie(String value) {
        try {
            if (value == null) return null;
            int i = value.lastIndexOf('.');
            if (i < 0) return null;
            String body = value.substring(0, i), sig = value.substring(i + 1);
            if (!constantEquals(sign(body), sig)) return null;
            String[] p = body.split("\\.");
            if (p.length != 2 || Long.parseLong(p[1]) < System.currentTimeMillis()) return null;
            return userRepo.findById(Long.parseLong(p[0])).filter(AppUser::isActive).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.getBot().getToken().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("imzo: " + e.getMessage(), e);
        }
    }

    private static boolean constantEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}
