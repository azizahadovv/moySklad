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

/**
 * MoySklad JSON API 1.2 mijozi (TZ 11-bo'lim).
 * MVPda tashqi bog'liqliksiz JDK HttpClient ishlatildi.
 * Faqat o'tkazilgan (applicable=true) hujjatlar olinadi.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MoySkladClient {

    private static final DateTimeFormatter FILTER_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter MOMENT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final AppProps props;
    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    /** Sotuv/vozvrat hujjati: summalar TIYINDA keladi (so'mga /100). */
    public record MsDoc(String id, LocalDate date, long cashTiyin, long noCashTiyin, String storeId) {}

    /** Kassa hujjati (Приходный/Расходный ордер, Входящий платеж, Выплата денег).
     *  groupId — hujjat egasining otdeli (Владелец-отдел), kassa bog'lanishi shu orqali.
     *  docNo — hujjat raqami, agent — kontragent, state — hujjat statusi (mas. «Клик»). */
    public record MsExpense(String id, String docNo, LocalDate date, long sumTiyin, String storeId,
                            String groupId, String expenseItem, String description,
                            String agent, String state) {}

    /** "retaildemand" (sotuv) yoki "retailsalesreturn" (vozvrat). */
    public List<MsDoc> fetchSales(String entity, LocalDateTime updatedFrom) {
        List<MsDoc> out = new ArrayList<>();
        for (JsonNode r : rows(entity, "", updatedFrom)) {
            if (!r.path("applicable").asBoolean(true)) continue;
            String href = r.path("retailStore").path("meta").path("href").asText("");
            out.add(new MsDoc(
                    r.path("id").asText(),
                    date(r),
                    r.path("cashSum").asLong(0),
                    r.path("noCashSum").asLong(0),
                    lastSegment(href)));
        }
        return out;
    }

    /** «Выплата денег» (retaildrawercashout) — savdo nuqtasi smenasidan naqd chiqim. */
    public List<MsExpense> fetchDrawerCashouts(LocalDateTime updatedFrom) {
        List<MsExpense> out = new ArrayList<>();
        for (JsonNode r : rows("retaildrawercashout", "&expand=retailShift", updatedFrom)) {
            if (!r.path("applicable").asBoolean(true)) continue;
            // Ba'zi javoblarda retailStore to'g'ridan-to'g'ri bo'ladi, aks holda smena orqali
            String storeHref = r.path("retailStore").path("meta").path("href").asText("");
            if (storeHref.isEmpty())
                storeHref = r.path("retailShift").path("retailStore").path("meta").path("href").asText("");
            out.add(new MsExpense(
                    r.path("id").asText(),
                    r.path("name").asText(""),
                    date(r),
                    r.path("sum").asLong(0),
                    lastSegment(storeHref),
                    groupOf(r),
                    "",
                    r.path("description").asText(""),
                    "", ""));
        }
        return out;
    }

    /** «Расходный ордер» (cashout) — otdel bo'yicha chiqim. */
    public List<MsExpense> fetchCashouts(LocalDateTime updatedFrom) {
        List<MsExpense> out = new ArrayList<>();
        for (JsonNode r : rows("cashout", "&expand=expenseItem,agent,state", updatedFrom)) {
            if (!r.path("applicable").asBoolean(true)) continue;
            out.add(new MsExpense(
                    r.path("id").asText(),
                    r.path("name").asText(""),
                    date(r),
                    r.path("sum").asLong(0),
                    "",
                    groupOf(r),
                    r.path("expenseItem").path("name").asText(""),
                    r.path("description").asText(""),
                    agentOf(r),
                    r.path("state").path("name").asText("")));
        }
        return out;
    }

    /** «Приходный ордер» (cashin) — otdel bo'yicha NAQD kirim. */
    public List<MsExpense> fetchCashins(LocalDateTime updatedFrom) {
        List<MsExpense> out = new ArrayList<>();
        for (JsonNode r : rows("cashin", "&expand=agent,state", updatedFrom)) {
            if (!r.path("applicable").asBoolean(true)) continue;
            out.add(new MsExpense(
                    r.path("id").asText(),
                    r.path("name").asText(""),
                    date(r),
                    r.path("sum").asLong(0),
                    "",
                    groupOf(r),
                    "",
                    r.path("description").asText(""),
                    agentOf(r),
                    r.path("state").path("name").asText("")));
        }
        return out;
    }

    /** «Входящий платеж» (paymentin) — Klik/bank kirimlari; status nomi state'da. */
    public List<MsExpense> fetchPaymentsIn(LocalDateTime updatedFrom) {
        List<MsExpense> out = new ArrayList<>();
        for (JsonNode r : rows("paymentin", "&expand=agent,state", updatedFrom)) {
            if (!r.path("applicable").asBoolean(true)) continue;
            out.add(new MsExpense(
                    r.path("id").asText(),
                    r.path("name").asText(""),
                    date(r),
                    r.path("sum").asLong(0),
                    "",
                    groupOf(r),
                    "",
                    r.path("description").asText(""),
                    agentOf(r),
                    r.path("state").path("name").asText("")));
        }
        return out;
    }

    /** Otdellar (Владелец-отдел) ro'yxati: UUID -> nomi. Xatoda bo'sh map. */
    public java.util.LinkedHashMap<String, String> fetchGroups() {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        JsonNode root = getJson(props.getMoysklad().getBaseUrl() + "/entity/group?limit=100");
        if (root != null)
            for (JsonNode r : root.path("rows"))
                out.put(r.path("id").asText(), r.path("name").asText());
        return out;
    }

    /**
     * Davr bo'yicha (hujjat sanasi — moment) hujjatlar: Excel hisobot uchun.
     * MoySklad'dagi filter ko'rinishiga mos: moment>=from 00:00:00 ; moment<=to 23:59:59.
     * entity: cashin / cashout / paymentin / paymentout.
     */
    public List<MsExpense> fetchDocsByMoment(String entity, LocalDate from, LocalDate to) {
        String filter = URLEncoder.encode(
                "moment>=" + from + " 00:00:00;moment<=" + to + " 23:59:59",
                StandardCharsets.UTF_8);
        String expand = entity.equals("cashout")
                ? "&expand=expenseItem,agent,state" : "&expand=agent,state";
        List<MsExpense> out = new ArrayList<>();
        for (JsonNode r : rowsFiltered(entity, expand, filter)) {
            if (!r.path("applicable").asBoolean(true)) continue;
            out.add(new MsExpense(
                    r.path("id").asText(),
                    r.path("name").asText(""),
                    date(r),
                    r.path("sum").asLong(0),
                    "",
                    groupOf(r),
                    r.path("expenseItem").path("name").asText(""),
                    r.path("description").asText(""),
                    agentOf(r),
                    r.path("state").path("name").asText("")));
        }
        return out;
    }

    /* -------------------- umumiy sahifalangan GET -------------------- */

    private List<JsonNode> rows(String entity, String extraQuery, LocalDateTime updatedFrom) {
        String filter = URLEncoder.encode(
                "updated>=" + updatedFrom.format(FILTER_FMT), StandardCharsets.UTF_8);
        return rowsFiltered(entity, extraQuery, filter);
    }

    private List<JsonNode> rowsFiltered(String entity, String extraQuery, String filter) {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 0; page < 10; page++) {           // himoya: maks 1000 hujjat/sikl
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
     * Bitta GET so'rov. 401/403 (huquq yo'q — doimiy) -> null, entity bo'sh deb qaraladi.
     * Boshqa xatolar (429/5xx/tarmoq) -> exception: sync watermark'ni surmasligi kerak,
     * aks holda o'qilmagan hujjatlar butunlay yo'qoladi.
     */
    private JsonNode getJson(String url) {
        try {
            // MoySklad API Accept-Encoding: gzip bo'lmasa 415 qaytaradi
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + props.getMoysklad().getToken())
                    .header("Accept", "application/json;charset=utf-8")
                    .header("Accept-Encoding", "gzip")
                    .GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            String body = decodeBody(resp);
            if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                log.warn("MoySklad ruxsat yo'q -> HTTP {} ({})", resp.statusCode(), url);
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

    /** Hujjat egasining otdeli (Владелец-отдел) UUID si. */
    private String groupOf(JsonNode r) {
        return lastSegment(r.path("group").path("meta").path("href").asText(""));
    }

    /** Kontragent: "Ism · telefon" (telefon bo'lsa). */
    private String agentOf(JsonNode r) {
        String name = r.path("agent").path("name").asText("");
        String phone = r.path("agent").path("phone").asText("");
        return phone.isEmpty() ? name : (name.isEmpty() ? phone : name + " · " + phone);
    }

    private String decodeBody(HttpResponse<byte[]> resp) throws java.io.IOException {
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

    private LocalDate date(JsonNode r) {
        return LocalDateTime.parse(r.path("moment").asText(), MOMENT_FMT).toLocalDate();
    }

    private String lastSegment(String href) {
        return href.isEmpty() ? "" : href.substring(href.lastIndexOf('/') + 1);
    }
}
