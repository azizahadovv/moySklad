package uz.kassa.gsheets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uz.kassa.config.AppProps;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Google Sheets API v4 mijozi — service account (JWT RS256) bilan,
 * tashqi kutubxonasiz (JDK HttpClient + java.security).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GoogleSheetsClient {

    private static final String API = "https://sheets.googleapis.com/v4/spreadsheets/";

    private final AppProps props;
    private final ObjectMapper om = new ObjectMapper();
    // Apps Script /exec javobni script.googleusercontent.com ga redirect qiladi
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS).build();

    private volatile String token;
    private volatile long tokenExp;

    /** Apps Script rejimi (oson yo'l — Cloud'siz). */
    private boolean scriptMode() {
        var g = props.getGsheet();
        return g != null && g.getScriptUrl() != null && !g.getScriptUrl().isBlank();
    }

    public boolean configured() {
        var g = props.getGsheet();
        if (g == null) return false;
        // G3: sekret majburiy — bo'sh sekret bilan ishlashga yo'l qo'yilmaydi
        // (ilgari yml'da ochiq default qiymat bor edi)
        if (scriptMode()) return g.getSecret() != null && !g.getSecret().isBlank();
        return g.getId() != null && !g.getId().isBlank()
                && g.getCredentials() != null && !g.getCredentials().isBlank()
                && Files.exists(Path.of(g.getCredentials()));
    }

    public String sheetId() { return props.getGsheet().getId(); }

    /* ---------------- Apps Script transport ---------------- */

    /**
     * G3: o'qish ham POST orqali — sekret URL query'da EMAS, body ichida yuboriladi
     * (GET query'lar Apps Script/proksi loglariga tushib qolardi).
     * Yangi docs/apps-script.gs (action:"read") deploy qilingan bo'lishi shart.
     */
    private JsonNode scriptGet(String tab, String range) throws Exception {
        java.util.Map<String, Object> payload = range == null
                ? java.util.Map.of("secret", props.getGsheet().getSecret(),
                        "action", "read", "tab", tab)
                : java.util.Map.of("secret", props.getGsheet().getSecret(),
                        "action", "read", "tab", tab, "range", range);
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(
                        URI.create(props.getGsheet().getScriptUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        om.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        JsonNode j = om.readTree(resp.body());
        if (j.has("error")) throw new IllegalStateException("Apps Script: " + j.path("error").asText()
                + ("unknown".equals(j.path("error").asText())
                    ? " — docs/apps-script.gs YANGI versiyasini deploy qiling (action:read)" : ""));
        return j;
    }

    private void scriptPost(Object payload) throws Exception {
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(
                        URI.create(props.getGsheet().getScriptUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        om.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        JsonNode j = om.readTree(resp.body());
        if (j.has("error")) throw new IllegalStateException("Apps Script: " + j.path("error").asText());
    }

    /* ------------------------- AUTH ------------------------- */

    private synchronized String token() throws Exception {
        if (token != null && System.currentTimeMillis() < tokenExp - 60_000) return token;

        JsonNode sa = om.readTree(Files.readString(Path.of(props.getGsheet().getCredentials())));
        String email = sa.path("client_email").asText();
        String pem = sa.path("private_key").asText()
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));

        long now = System.currentTimeMillis() / 1000;
        String header = b64url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String claims = b64url("{\"iss\":\"" + email + "\","
                + "\"scope\":\"https://www.googleapis.com/auth/spreadsheets\","
                + "\"aud\":\"https://oauth2.googleapis.com/token\","
                + "\"iat\":" + now + ",\"exp\":" + (now + 3600) + "}");
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update((header + "." + claims).getBytes(StandardCharsets.UTF_8));
        String jwt = header + "." + claims + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());

        HttpRequest req = HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=" + jwt))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode j = om.readTree(resp.body());
        if (resp.statusCode() != 200)
            throw new IllegalStateException("Google auth xato: " + resp.body());
        token = j.path("access_token").asText();
        tokenExp = System.currentTimeMillis() + j.path("expires_in").asLong(3600) * 1000;
        return token;
    }

    private String b64url(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /* ------------------------- REST ------------------------- */

    private JsonNode call(String method, String url, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token());
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300)
            throw new IllegalStateException("Sheets HTTP " + resp.statusCode() + ": "
                    + (resp.body().length() > 300 ? resp.body().substring(0, 300) : resp.body()));
        return om.readTree(resp.body());
    }

    private String enc(String range) {
        return URLEncoder.encode(range, StandardCharsets.UTF_8);
    }

    /** Diapazon qiymatlari (satrlar ro'yxati). range: "Tab!A2:E100". */
    public List<List<String>> get(String range) throws Exception {
        JsonNode j;
        if (scriptMode()) {
            int i = range.indexOf('!');
            j = scriptGet(i < 0 ? range : range.substring(0, i),
                    i < 0 ? null : range.substring(i + 1));
        } else {
            j = call("GET", API + sheetId() + "/values/" + enc(range), null);
        }
        List<List<String>> out = new ArrayList<>();
        for (JsonNode row : j.path("values")) {
            List<String> r = new ArrayList<>();
            for (JsonNode c : row) r.add(c.asText());
            out.add(r);
        }
        return out;
    }

    /** Varaqni tozalab, A1 dan boshlab yozish. */
    public void overwrite(String tab, List<List<Object>> rows) throws Exception {
        if (scriptMode()) {
            scriptPost(java.util.Map.of(
                    "secret", props.getGsheet().getSecret(),
                    "action", "overwrite", "tab", tab, "rows", rows));
            return;
        }
        call("POST", API + sheetId() + "/values/" + enc(tab + "!A1:Z100000") + ":clear", "{}");
        StringBuilder sb = new StringBuilder("{\"values\":[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('[');
            List<Object> row = rows.get(i);
            for (int c = 0; c < row.size(); c++) {
                if (c > 0) sb.append(',');
                Object v = row.get(c);
                if (v instanceof Number) sb.append(v);
                else sb.append(om.writeValueAsString(String.valueOf(v == null ? "" : v)));
            }
            sb.append(']');
        }
        sb.append("]}");
        call("PUT", API + sheetId() + "/values/" + enc(tab + "!A1")
                + "?valueInputOption=RAW", sb.toString());
    }

    /** Kerakli varaqlarni yaratib qo'yish (bor bo'lsa tegilmaydi). */
    public void ensureTabs(List<String> needed) throws Exception {
        if (scriptMode()) {
            scriptPost(java.util.Map.of(
                    "secret", props.getGsheet().getSecret(),
                    "action", "ensureTabs", "tabs", needed));
            return;
        }
        Set<String> have = new LinkedHashSet<>();
        JsonNode j = call("GET", API + sheetId() + "?fields=sheets.properties.title", null);
        for (JsonNode sh : j.path("sheets"))
            have.add(sh.path("properties").path("title").asText());
        for (String t : needed)
            if (!have.contains(t))
                call("POST", API + sheetId() + ":batchUpdate",
                        "{\"requests\":[{\"addSheet\":{\"properties\":{\"title\":"
                                + om.writeValueAsString(t) + "}}}]}");
    }
}
