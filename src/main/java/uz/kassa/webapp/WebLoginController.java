package uz.kassa.webapp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.kassa.domain.AppUser;
import uz.kassa.service.AuditService;

import java.io.IOException;

/**
 * 🖥 Брaузердан кириш: ботдаги `/panel` бир мартали ҳавола беради → бу ерда
 * сессия куки'сига алмашади → `/` га йўналтирилади. Чиқиш: `/logout`.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class WebLoginController {

    private final WebSessionService sessions;
    private final AuditService audit;

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    public void login(@RequestParam(name = "t", required = false) String token,
                      HttpServletRequest req, HttpServletResponse res) throws IOException {
        AppUser u = sessions.consume(token);
        if (u == null) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("text/html;charset=UTF-8");
            res.getWriter().write(page("⛔ Ҳавола яроқсиз ёки эскирган",
                    "Ҳар ҳавола бир марта ва " + sessions.linkMinutes() + " дақиқа ишлайди.<br>"
                            + "Ботда <b>/panel</b> буйруғини юбориб, янги ҳавола олинг."));
            return;
        }
        res.addHeader("Set-Cookie", cookie(sessions.newCookie(u), sessions.cookieMaxAgeSeconds(), secure(req)));
        audit.log(u.getId(), "WEB_LOGIN", "web", null, u.getFullName() + " браузердан кирди");
        log.info("Web login: {} (#{})", u.getFullName(), u.getId());
        res.sendRedirect("/");
    }

    @GetMapping(value = "/logout", produces = MediaType.TEXT_HTML_VALUE)
    public void logout(HttpServletRequest req, HttpServletResponse res) throws IOException {
        AppUser u = sessions.fromRequest();
        res.addHeader("Set-Cookie", cookie("", 0, secure(req)));
        if (u != null) audit.log(u.getId(), "WEB_LOGOUT", "web", null, u.getFullName());
        res.setContentType("text/html;charset=UTF-8");
        res.getWriter().write(page("✅ Чиқилди", "Қайта кириш учун ботда <b>/panel</b> буйруғини юборинг."));
    }

    /** Proxy (Caddy/nginx) ортида ҳам тўғри аниқлаш. */
    private static boolean secure(HttpServletRequest req) {
        String proto = req.getHeader("X-Forwarded-Proto");
        return req.isSecure() || "https".equalsIgnoreCase(proto);
    }

    private static String cookie(String value, long maxAge, boolean secure) {
        return WebSessionService.COOKIE + "=" + value + "; Path=/; Max-Age=" + maxAge
                + "; HttpOnly; SameSite=Lax" + (secure ? "; Secure" : "");
    }

    private static String page(String title, String body) {
        return "<!DOCTYPE html><html lang=\"uz\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Касса Назорати</title><style>"
                + "body{background:#EEF1EE;color:#16211C;font:500 15px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;"
                + "display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0;padding:20px}"
                + ".c{background:#fff;border:1px solid #D3DBD5;border-top:3px solid #0B6B5D;border-radius:8px;padding:22px 24px;max-width:420px}"
                + "h1{font-size:19px;margin:0 0 8px}p{margin:0;color:#64746C}"
                + "@media(prefers-color-scheme:dark){body{background:#0F1513;color:#E4EBE6}.c{background:#17201C;border-color:#2A3630}p{color:#8C9A93}}"
                + "</style></head><body><div class=\"c\"><h1>" + title + "</h1><p>" + body + "</p></div></body></html>";
    }
}
