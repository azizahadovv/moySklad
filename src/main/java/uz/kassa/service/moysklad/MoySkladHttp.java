package uz.kassa.service.moysklad;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uz.kassa.config.AppProps;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import static uz.kassa.service.moysklad.MoySkladClient.*;

/**
 * MoySklad API transporti: token (baza/.env), HTTP so'rov, sahifalab o'qish (rows/rowsFiltered/rowsAll), gzip, 403 kuzatuvi.
 * (MoySkladClient dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MoySkladHttp {

    private final AppProps props;
    private final uz.kassa.service.SettingsService settings;
    final ObjectMapper om = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();

    /** Joriy token keshi (settings o'zgarganda null qilinadi). */
    private volatile String cachedToken = null;

    /** Oxirgi 401/403 (huquq yetmagan) javob vaqti va URL — sinxron ogohlantirishi uchun (M4). */
    private volatile long last403At = 0;
    private volatile String last403Url = "";


    public long last403At() { return last403At; }

    public String last403Url() { return last403Url; }


    /**
     * Amaldagi token: avval settings jadvalidagi «moysklad.token» (SuperAdmin
     * botdan o'zgartirgan bo'lsa), bo'lmasa .env dagi MOYSKLAD_TOKEN.
     */
    public String currentToken() {
        String t = cachedToken;
        if (t == null) {
            t = settings.get("moysklad.token").orElse("").trim();
            if (t.isBlank()) {
                String env = props.getMoysklad().getToken();
                t = env == null ? "" : env.trim();
            }
            cachedToken = t;
        }
        return t;
    }


    /** SuperAdmin yangi kalit kiritdi — saqlash va keshni yangilash. */
    public void updateToken(String token) {
        settings.set("moysklad.token", token == null ? "" : token.trim());
        cachedToken = null;
    }


    /** Kalitni tekshirish: API oddiy so'rovga 200 qaytarsa — yaroqli. */
    public boolean testToken(String token) {
        try {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create(props.getMoysklad().getBaseUrl() + "/entity/currency?limit=1"))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json;charset=utf-8")
                    .header("Accept-Encoding", "gzip")
                    .GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }


    /* -------------------- umumiy sahifalangan GET -------------------- */

    List<JsonNode> rows(String entity, String extraQuery, LocalDateTime updatedFrom) {
        String filter = URLEncoder.encode(
                "updated>=" + updatedFrom.format(FILTER_FMT), StandardCharsets.UTF_8);
        return rowsFiltered(entity, extraQuery, filter);
    }


    List<JsonNode> rowsFiltered(String entity, String extraQuery, String filter) {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 0; ; page++) {
            // Himoya: 30 000+ hujjat bir siklda — exception, watermark surilmaydi,
            // keyingi siklda davom etadi (hujjat YO'QOLMAYDI, avvalgi 1000-lik limit yo'qotardi).
            if (page >= 300)
                throw new IllegalStateException("MoySklad: " + entity
                        + " bo'yicha 30000+ hujjat bir siklda — keyingi siklda davom etadi");
            String url = props.getMoysklad().getBaseUrl()
                    + "/entity/" + entity
                    + "?limit=100&offset=" + (page * 100)
                    + "&order=updated,asc&filter=" + filter + extraQuery;
            JsonNode root = getJson(url);
            if (root == null) break;
            JsonNode rws = root.path("rows");
            for (JsonNode r : rws) out.add(r);
            if (rws.size() < 100) break;
        }
        return out;
    }


    /**
     * Sana/updated filtrisiz TO'LIQ ro'yxat (hisob yaratilgandan buyon) — faqat
     * kamdan-kam ishlaydigan Click balans auditida ishlatiladi. Sahifalar orasida
     * kichik pauza — MoySklad rate-limit (~45 so'rov/3 soniya)ga tegmaslik uchun.
     */
    List<JsonNode> rowsAll(String entity, String extraQuery) {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 0; page < 400; page++) {
            String url = props.getMoysklad().getBaseUrl()
                    + "/entity/" + entity
                    + "?limit=100&offset=" + (page * 100) + extraQuery;
            JsonNode root = getJson(url);
            if (root == null) break;
            JsonNode rws = root.path("rows");
            for (JsonNode r : rws) out.add(r);
            if (rws.size() < 100) break;
            try { Thread.sleep(150); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        return out;
    }


    /**
     * Bitta GET so'rov. 401/403 (huquq yo'q — doimiy) -> null, entity bo'sh deb qaraladi.
     * Boshqa xatolar (429/5xx/tarmoq) -> exception: sync watermark'ni surmasligi kerak,
     * aks holda o'qilmagan hujjatlar butunlay yo'qoladi.
     */
    JsonNode getJson(String url) {
        try {
            // MoySklad API Accept-Encoding: gzip bo'lmasa 415 qaytaradi
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + currentToken())
                    .header("Accept", "application/json;charset=utf-8")
                    .header("Accept-Encoding", "gzip")
                    .GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            String body = decodeBody(resp);
            if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                log.warn("MoySklad ruxsat yo'q -> HTTP {} ({})", resp.statusCode(), url);
                last403At = System.currentTimeMillis();
                last403Url = url;
                return null;
            }
            if (resp.statusCode() != 200)
                throw new IllegalStateException("MoySklad HTTP " + resp.statusCode() + ": "
                        + (body.length() > 200 ? body.substring(0, 200) : body));
            return om.readTree(body);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("MoySklad so'rovida xato: " + e.getMessage(), e);
        }
    }


    String decodeBody(HttpResponse<byte[]> resp) throws java.io.IOException {
        byte[] b = resp.body() == null ? new byte[0] : resp.body();
        boolean gzip = resp.headers().firstValue("Content-Encoding")
                .map(v -> v.toLowerCase().contains("gzip")).orElse(false);
        if (gzip && b.length > 0) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(b))) {
                b = in.readAllBytes();
            }
        }
        return new String(b, StandardCharsets.UTF_8);
    }

}
