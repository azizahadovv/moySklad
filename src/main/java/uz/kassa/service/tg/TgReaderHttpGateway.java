package uz.kassa.service.tg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 📨 Akkaunt ulash darvozasi — Python (Telethon) {@code tg-reader} xizmatiga HTTP orqali (bir xil Docker
 * tarmog'ida, umumiy {@code TGREADER_SECRET} bilan). {@code src/tdlib/java}dagi TDLib mijozining o'rnini
 * bosadi: mvn.mchv.eu qurish tarmoq muammosiga bog'liq emas, chunki Telethon PyPI'dan o'rnatiladi.
 * Doim kompilyatsiya bo'ladi va DOIM bean sifatida ro'yxatdan o'tadi — {@code @ConditionalOnMissingBean}
 * ATAYLAB ISHLATILMAYDI: bu klass o'zi {@link TgAccountGateway}ni amalga oshirgani uchun («o'zini o'ziga
 * qarshi» sharti) Spring buni har doim emas, balki ko'pincha «allaqachon bor» deb aniqlab, o'zini
 * RO'YXATDAN O'TKAZMASDAN jim qoladi (startup xatosiz, lekin bean umuman yo'q) — bu aynan shu xatoga olib
 * kelgan edi (2026-09-15, «Serverda hali yoqilmagan» hech qachon yo'qolmasdi). Amaliyotda «tdlib» profili
 * endi standart build'da ISHLATILMAYDI (Dockerfile {@code ARG TDLIB} bo'sh), shuning uchun ikkinchi
 * {@link TgAccountGateway} bean paydo bo'lish xavfi yo'q; agar u profil kelajakda qayta qurilsa, Spring
 * ikkala bean bilan aniq (yashirin emas) xato beradi — shuni ANIQ hal qilish kerak bo'ladi (masalan biriga
 * {@code @Primary} qo'yib).
 */
@Component
@Slf4j
public class TgReaderHttpGateway implements TgAccountGateway {

    private final TgReaderService reader;
    private final TgReaderConfig cfg;
    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${TGREADER_URL:http://tg-reader:8081}") private String baseUrl;
    @Value("${TGREADER_SECRET:}") private String secret;

    public TgReaderHttpGateway(TgReaderService reader, TgReaderConfig cfg) { this.reader = reader; this.cfg = cfg; }

    @Override
    public boolean available() { return cfg.hasApi(); }

    @Override
    public Result startQrLogin(long userId) {
        if (!available()) return Result.error("Kalitlar sozlanmagan (TG_API_ID/HASH/SOURCE_BOT)");
        return toResult(userId, post("/login/qr/start", Map.of("userId", userId)));
    }

    @Override
    public Result pollQr(long userId) {
        JsonNode r = post("/login/qr/poll", Map.of("userId", userId));
        // vaqtinchalik tarmoq/HTTP xatosi ERROR emas — chaqiruvchi (TgHandler.pollQr sikli) keyingi urinishda davom etadi
        if (r == null) throw new IllegalStateException("tg-reader javob bermadi");
        return toResult(userId, r);
    }

    @Override
    public Result submitPassword(long userId, String password) {
        return toResult(userId, post("/login/qr/password", Map.of("userId", userId, "password", password)));
    }

    @Override
    public void cancelLogin(long userId) {
        post("/login/qr/cancel", Map.of("userId", userId));
    }

    @Override
    public void pause(String phone) {
        post("/account/pause", Map.of("phone", phone));
        reader.setActive(phone, false);
    }

    @Override
    public void disconnect(String phone) {
        post("/account/delete", Map.of("phone", phone));
        reader.setActive(phone, false);
    }

    @Override
    public boolean reconnect(String phone) {
        if (!available()) return false;
        JsonNode r = post("/account/resume", Map.of("phone", phone));
        boolean ok = r != null && r.path("ok").asBoolean(false);
        if (ok) reader.setActive(phone, true);
        return ok;
    }

    @Override
    public TestResult test(String phone) {
        JsonNode r = post("/account/test", Map.of("phone", phone));
        if (r == null) return new TestResult(false, NO_REPLY);
        return new TestResult(r.path("ok").asBoolean(false), r.path("message").asText(""));
    }

    @Override
    public List<PendingLogin> pendingLogins() {
        List<PendingLogin> out = new ArrayList<>();
        JsonNode r = get("/pending");
        if (r == null) return out;
        for (JsonNode n : r.path("pending")) {
            out.add(new PendingLogin(n.path("userId").asLong(0), Instant.ofEpochSecond(n.path("startedAt").asLong(0))));
        }
        return out;
    }

    @Override
    public BalanceResult balance(String phone, List<String> steps) {
        JsonNode r = post("/account/balance", Map.of("phone", phone, "steps", steps));
        if (r == null) return new BalanceResult(false, NO_REPLY, List.of());
        List<String> replies = new ArrayList<>();
        for (JsonNode n : r.path("replies")) replies.add(n.asText(""));
        return new BalanceResult(r.path("ok").asBoolean(false), r.path("message").asText(""), replies);
    }

    @Override
    public SecurityInfo security(String phone) {
        JsonNode r = post("/account/security", Map.of("phone", phone));
        if (r == null) return new SecurityInfo(false, NO_REPLY, null, List.of());
        if (!r.path("ok").asBoolean(false)) return new SecurityInfo(false, r.path("message").asText("xato"), null, List.of());
        List<SessionInfo> list = new ArrayList<>();
        for (JsonNode n : r.path("sessions"))
            list.add(new SessionInfo(n.path("hash").asLong(), n.path("device").asText(""), n.path("platform").asText(""), n.path("app").asText(""),
                    n.path("country").asText(""), n.path("dateActive").isNull() ? null : n.path("dateActive").asText(null), n.path("current").asBoolean(false)));
        return new SecurityInfo(true, null, r.path("twoFa").asBoolean(false), list);
    }

    /* ==================== yordamchi ==================== */

    private Result toResult(long userId, JsonNode r) {
        if (r == null) return Result.error(NO_REPLY);
        String step = r.path("step").asText("ERROR");
        if ("ERROR".equals(step)) return Result.error(r.path("error").asText("Noma'lum xato"));
        int gen = r.path("gen").asInt(0);
        if ("QR".equals(step)) return new Result(Step.QR, gen, r.path("png").asText(null), null, null);
        if ("NEED_PASSWORD".equals(step)) return new Result(Step.NEED_PASSWORD, gen, null, null, null);
        if ("CONNECTED".equals(step)) {
            String phone = r.path("phone").asText(null);
            if (phone != null) { reader.linkUser(phone, userId); reader.setActive(phone, true); }
            return new Result(Step.CONNECTED, gen, null, phone, null);
        }
        return Result.error("Noma'lum javob: " + step);
    }

    /** Tarmoq darajasida javob yo'q — deyarli har doim tg-reader konteyneri ishlamayapti (yiqilgan/ko'tarilmagan). */
    static final String NO_REPLY = "tg-reader xizmati javob bermadi — serverda tekshiring: docker compose ps tg-reader / docker compose logs --tail=50 tg-reader";

    private JsonNode post(String path, Map<String, Object> body) {
        try {
            ObjectNode node = om.valueToTree(body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .header("X-Reader-Secret", secret)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(node), StandardCharsets.UTF_8))
                    .build();
            return send(req);
        } catch (Exception e) {
            log.error("tg-reader POST {}: {}", path, e.toString());
            return null;
        }
    }

    private JsonNode get(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("X-Reader-Secret", secret)
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            return send(req);
        } catch (Exception e) {
            log.error("tg-reader GET {}: {}", path, e.toString());
            return null;
        }
    }

    private JsonNode send(HttpRequest req) throws Exception {
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            log.warn("tg-reader {} -> HTTP {}: {}", req.uri(), resp.statusCode(),
                    resp.body().length() > 200 ? resp.body().substring(0, 200) : resp.body());
            return null;
        }
        return om.readTree(resp.body());
    }
}
