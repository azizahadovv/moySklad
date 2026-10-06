package uz.kassa.service.adesk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 📒 Adesk API transporti (docs/ADESK.md §2).
 *  • v1 — {@code application/x-www-form-urlencoded}, token {@code api_token} query parametrida;
 *  • v2 — JSON, token {@code X-API-Token} sarlavhasida.
 * Hamma so'rov bitta tezlik darvozasidan o'tadi (soniyasiga {@link AdeskConfig#rps()}), 429/5xx/tarmoq xatosida
 * kutib qayta uriniladi. Javobda {@code success:false} bo'lsa {@link AdeskException} (Adesk xato matni bilan).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdeskHttp {

    private final AdeskConfig cfg;
    final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    /** Keyingi so'rov yuborilishi mumkin bo'lgan vaqt (epoch ms). */
    private long nextAt = 0;
    private static final int RETRIES = 4;

    /** Adesk xatosi. fatal — token yaroqsiz (401) yoki obuna tugagan (code 21): ishni to'xtatish kerak. */
    public static class AdeskException extends RuntimeException {
        public final int http;
        public final int code;
        public final boolean fatal;
        public AdeskException(int http, int code, String msg, boolean fatal) {
            super(msg);
            this.http = http; this.code = code; this.fatal = fatal;
        }
    }

    /* -------------------- v1 -------------------- */

    public JsonNode get(String path, Map<String, String> params) {
        String url = cfg.baseUrl() + "/v1/" + path + "?api_token=" + enc(token()) + (params == null || params.isEmpty() ? "" : "&" + form(params));
        return send(HttpRequest.newBuilder(URI.create(url)).GET(), url);
    }

    public JsonNode postForm(String path, Map<String, String> params) {
        String url = cfg.baseUrl() + "/v1/" + path + "?api_token=" + enc(token());
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(params == null ? "" : form(params), StandardCharsets.UTF_8));
        return send(b, url);
    }

    /* -------------------- v2 -------------------- */

    public JsonNode postJson(String path, Object body) {
        String url = cfg.baseUrl() + "/v2/" + path;
        String json;
        try { json = body instanceof String s ? s : om.writeValueAsString(body); }
        catch (Exception e) { throw new IllegalStateException("Adesk JSON: " + e.getMessage(), e); }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("X-API-Token", token())
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        return send(b, url);
    }

    /** Tokenni tekshirish: yuridik shaxslar ro'yxati 200 + success qaytarsa — yaroqli. Xato matni yoki null. */
    public String test(String token) {
        try {
            String url = cfg.baseUrl() + "/v1/legal-entities?api_token=" + enc(token);
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode j = parse(r.body());
            if (r.statusCode() == 200 && j != null && j.path("success").asBoolean(false)) return null;
            return errorText(r.statusCode(), j, r.body());
        } catch (Exception e) {
            return "tarmoq xatosi: " + e.getMessage();
        }
    }

    /* -------------------- ichki -------------------- */

    private String token() {
        String t = cfg.token();
        if (t.isBlank()) throw new AdeskException(401, 401, "Adesk token kiritilmagan", true);
        return t;
    }

    private void gate() throws InterruptedException {
        long wait;
        synchronized (this) {
            long now = System.currentTimeMillis();
            long gap = 1000L / Math.max(1, cfg.rps());
            long at = Math.max(now, nextAt);
            nextAt = at + gap;
            wait = at - now;
        }
        if (wait > 0) Thread.sleep(wait);
    }

    private JsonNode send(HttpRequest.Builder b, String url) {
        b.header("Accept", "application/json").timeout(Duration.ofSeconds(90));
        HttpRequest req = b.build();
        String shown = url.replaceAll("api_token=[^&]*", "api_token=***");
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> resp;
            try {
                gate();
                resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AdeskException(0, 0, "to'xtatildi", true);
            } catch (Exception e) {
                if (attempt < RETRIES) { pause(attempt, 0); continue; }
                throw new AdeskException(0, 0, "tarmoq xatosi: " + e.getMessage(), false);
            }
            int st = resp.statusCode();
            JsonNode j = parse(resp.body());
            int code = j == null ? 0 : j.path("code").asInt(0);
            if (st == 429 || code == 429 || st >= 500) {
                if (attempt < RETRIES) {
                    long ra = resp.headers().firstValue("Retry-After").map(v -> { try { return Long.parseLong(v.trim()) * 1000; } catch (Exception e) { return 0L; } }).orElse(0L);
                    log.info("Adesk HTTP {} — kutib qayta ({}/{}): {}", st, attempt + 1, RETRIES, shown);
                    pause(attempt, ra);
                    continue;
                }
                throw new AdeskException(st, code, errorText(st, j, resp.body()), false);
            }
            if (st == 401 || code == 401) throw new AdeskException(st, 401, "Adesk token yaroqsiz (Auth required)", true);
            if (code == 21) throw new AdeskException(st, 21, "Adesk obunasi tugagan (Payment required)", true);
            if (j == null) throw new AdeskException(st, 0, "Adesk javobi JSON emas (HTTP " + st + ")", false);
            if (st != 200 || !j.path("success").asBoolean(true)) throw new AdeskException(st, code, errorText(st, j, resp.body()), false);
            return j;
        }
    }

    private void pause(int attempt, long retryAfterMs) {
        long ms = retryAfterMs > 0 ? Math.min(retryAfterMs, 60_000) : (long) (2000 * Math.pow(2, attempt));
        synchronized (this) { nextAt = Math.max(nextAt, System.currentTimeMillis() + ms); }
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) return null;
        try { return om.readTree(body); } catch (Exception e) { return null; }
    }

    /** Adesk xato javobidan o'qiladigan matn: message + errors {maydon: [sabab]}. */
    static String errorText(int st, JsonNode j, String body) {
        if (j == null) return "HTTP " + st + (body == null ? "" : ": " + body.substring(0, Math.min(200, body.length())));
        StringBuilder sb = new StringBuilder();
        String m = j.path("message").asText("");
        if (!m.isBlank()) sb.append(m);
        JsonNode errs = j.path("errors");
        if (errs.isObject()) errs.fields().forEachRemaining(e -> {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append(": ").append(e.getValue().isArray() && e.getValue().size() > 0 ? e.getValue().get(0).asText() : e.getValue().toString());
        });
        else if (errs.isArray()) for (JsonNode e : errs) { if (sb.length() > 0) sb.append("; "); sb.append(e.toString()); }
        if (sb.length() == 0) sb.append("HTTP ").append(st);
        String s = sb.toString();
        return s.length() > 400 ? s.substring(0, 400) : s;
    }

    static String form(Map<String, String> params) {
        StringJoiner sj = new StringJoiner("&");
        params.forEach((k, v) -> { if (v != null) sj.add(enc(k) + "=" + enc(v)); });
        return sj.toString();
    }

    static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
}
