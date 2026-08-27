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
    private final uz.kassa.service.SettingsService settings;
    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    /** Joriy token keshi (settings o'zgarganda null qilinadi). */
    private volatile String cachedToken = null;

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

    /** Sotuv/vozvrat hujjati: summalar TIYINDA keladi (so'mga /100).
     *  applicable=false — hujjat o'tkazilmagan (bekor qilingan) — yozuv STORNO qilinadi. */
    public record MsDoc(String id, LocalDate date, long cashTiyin, long noCashTiyin,
                        String storeId, boolean applicable) {}

    /** Kassa hujjati (Приходный/Расходный ордер, Входящий платеж, Выплата денег).
     *  groupId — hujjat egasining otdeli (Владелец-отдел), kassa bog'lanishi shu orqali.
     *  docNo — hujjat raqami, agent — kontragent, state — hujjat statusi (mas. «Клик»).
     *  applicable=false — hujjat o'tkazilmagan (bekor qilingan) — yozuv STORNO qilinadi. */
    public record MsExpense(String id, String docNo, LocalDate date, long sumTiyin, String storeId,
                            String groupId, String expenseItem, String description,
                            String agent, String state, String currencyId, double rateValue,
                            boolean applicable, String accountId) {}

    /** Bitta hujjatning API'dagi holati (o'chirilganlikni aniqlash uchun). */
    public enum DocStatus { OK, UNAPPLIED, DELETED, UNKNOWN }

    /** "retaildemand" (sotuv) yoki "retailsalesreturn" (vozvrat). */
    public List<MsDoc> fetchSales(String entity, LocalDateTime updatedFrom) {
        List<MsDoc> out = new ArrayList<>();
        for (JsonNode r : rows(entity, "", updatedFrom)) {
            String href = r.path("retailStore").path("meta").path("href").asText("");
            out.add(new MsDoc(
                    r.path("id").asText(),
                    date(r),
                    r.path("cashSum").asLong(0),
                    r.path("noCashSum").asLong(0),
                    lastSegment(href),
                    r.path("applicable").asBoolean(true)));
        }
        return out;
    }

    /** «Выплата денег» (retaildrawercashout) — savdo nuqtasi smenasidan naqd chiqim. */
    public List<MsExpense> fetchDrawerCashouts(LocalDateTime updatedFrom) {
        List<MsExpense> out = new ArrayList<>();
        for (JsonNode r : rows("retaildrawercashout", "&expand=retailShift", updatedFrom)) {
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
                    "", "", currencyOf(r), rateOf(r),
                    r.path("applicable").asBoolean(true), accountOf(r)));
        }
        return out;
    }

    /** «Расходный ордер» (cashout) — otdel bo'yicha chiqim. */
    public List<MsExpense> fetchCashouts(LocalDateTime updatedFrom) {
        List<MsExpense> out = new ArrayList<>();
        for (JsonNode r : rows("cashout", "&expand=expenseItem,agent,state", updatedFrom)) {
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
                    r.path("state").path("name").asText(""),
                    currencyOf(r), rateOf(r),
                    r.path("applicable").asBoolean(true), accountOf(r)));
        }
        return out;
    }

    /** «Приходный ордер» (cashin) — otdel bo'yicha NAQD kirim. */
    public List<MsExpense> fetchCashins(LocalDateTime updatedFrom) {
        List<MsExpense> out = new ArrayList<>();
        for (JsonNode r : rows("cashin", "&expand=agent,state", updatedFrom)) {
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
                    r.path("state").path("name").asText(""),
                    currencyOf(r), rateOf(r),
                    r.path("applicable").asBoolean(true), accountOf(r)));
        }
        return out;
    }

    /** «Входящий платеж» (paymentin) — Klik/bank kirimlari; status nomi state'da. */
    public List<MsExpense> fetchPaymentsIn(LocalDateTime updatedFrom) {
        List<MsExpense> out = new ArrayList<>();
        for (JsonNode r : rows("paymentin", "&expand=agent,state", updatedFrom)) {
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
                    r.path("state").path("name").asText(""),
                    currencyOf(r), rateOf(r),
                    r.path("applicable").asBoolean(true), accountOf(r)));
        }
        return out;
    }

    /** «Исходящий платеж» (paymentout) — Klik/bank chiqimlari; status nomi state'da
     *  (masalan «Клик» — kassa Click hisobidan chiqim, boshqalari bank o'tkazmasi). */
    public List<MsExpense> fetchPaymentsOut(LocalDateTime updatedFrom) {
        List<MsExpense> out = new ArrayList<>();
        for (JsonNode r : rows("paymentout", "&expand=agent,state", updatedFrom)) {
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
                    r.path("state").path("name").asText(""),
                    currencyOf(r), rateOf(r),
                    r.path("applicable").asBoolean(true), accountOf(r)));
        }
        return out;
    }

    /**
     * Входящий/Исходящий платеж — TO'LIQ TARIX (sana filtrisiz, hisob yaratilgandan
     * hoziргача). Faqat Click hisoblari balansini MoySklad bilan TO'LIQ solishtirish
     * (audit) uchun ishlatiladi — oddiy 30 soniyalik sinxronda ishlatilmaydi (og'ir).
     */
    public List<MsExpense> fetchAllPaymentsIn() {
        return expensesFromRows(rowsAll("paymentin", "&expand=agent,state"));
    }

    /** @see #fetchAllPaymentsIn() — xuddi shu, «Исходящий платеж» uchun. */
    public List<MsExpense> fetchAllPaymentsOut() {
        return expensesFromRows(rowsAll("paymentout", "&expand=agent,state"));
    }

    private List<MsExpense> expensesFromRows(List<JsonNode> rows) {
        List<MsExpense> out = new ArrayList<>();
        for (JsonNode r : rows) {
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
                    r.path("state").path("name").asText(""),
                    currencyOf(r), rateOf(r),
                    r.path("applicable").asBoolean(true), accountOf(r)));
        }
        return out;
    }

    /** MoySklad kontragenti (qarz daftari uchun). */
    public record MsAgent(String id, String name, String phone, String inn) {}

    /** Kontragent qidiruvi (nom/telefon/INN bo'yicha). Xatoda bo'sh ro'yxat. */
    public List<MsAgent> searchAgents(String query, int limit) {
        List<MsAgent> out = new ArrayList<>();
        try {
            String url = props.getMoysklad().getBaseUrl() + "/entity/counterparty?limit=" + limit
                    + "&search=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
            JsonNode root = getJson(url);
            if (root != null)
                for (JsonNode r : root.path("rows"))
                    out.add(new MsAgent(
                            r.path("id").asText(),
                            r.path("name").asText(""),
                            r.path("phone").asText(""),
                            r.path("inn").asText("")));
        } catch (Exception e) {
            log.warn("Kontragent qidiruvi xatosi: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Kontragent balansi (Взаиморасчёты), SO'MDA. null — olinmadi (ruxsat/tarmoq).
     * Ushbu MoySklad hisobida: manfiy — kontragent bizga qarzdor, musbat — biz unga
     * qarzdormiz (MoySklad'ning o'z "Баланс" ustuni bilan bir xil ishora — tekshirilgan).
     */
    public Long fetchAgentBalanceSom(String agentId) {
        try {
            JsonNode root = getJson(props.getMoysklad().getBaseUrl()
                    + "/report/counterparty/" + agentId);
            if (root == null) return null;
            return root.path("balance").asLong(0) / 100;
        } catch (Exception e) {
            log.warn("Kontragent balansi o'qilmadi: {}", e.getMessage());
            return null;
        }
    }

    /** MoySklad xodimi (Владелец-сотрудник). */
    public record MsEmployee(String name, String phone) {}

    /** Xodimlar (Владелец-сотрудник) ro'yxati. Xatoda bo'sh ro'yxat. */
    public List<MsEmployee> fetchEmployees() {
        List<MsEmployee> out = new ArrayList<>();
        try {
            JsonNode root = getJson(props.getMoysklad().getBaseUrl() + "/entity/employee?limit=100");
            if (root != null)
                for (JsonNode r : root.path("rows")) {
                    String name = r.path("name").asText("");
                    if (name.isBlank()) continue;
                    out.add(new MsEmployee(name, r.path("phone").asText("")));
                }
        } catch (Exception e) {
            log.warn("Xodimlar ro'yxati o'qilmadi: {}", e.getMessage());
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
                    r.path("state").path("name").asText(""),
                    currencyOf(r), rateOf(r), true, accountOf(r)));
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
    private List<JsonNode> rowsAll(String entity, String extraQuery) {
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
     * Bitta hujjatning hozirgi holati: OK (o'tkazilgan), UNAPPLIED (bekor qilingan),
     * DELETED (o'chirilgan), UNKNOWN (ruxsat/tarmoq xatosi — hech narsa qilinmaydi).
     * Reconcile o'chirilgan hujjatlarni faqat shu tasdiq bilan STORNO qiladi.
     */
    public DocStatus fetchDocStatus(String entity, String id) {
        try {
            String url = props.getMoysklad().getBaseUrl() + "/entity/" + entity + "/" + id;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + currentToken())
                    .header("Accept", "application/json;charset=utf-8")
                    .header("Accept-Encoding", "gzip")
                    .GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 404 || resp.statusCode() == 410) return DocStatus.DELETED;
            if (resp.statusCode() != 200) return DocStatus.UNKNOWN;
            JsonNode r = om.readTree(decodeBody(resp));
            return r.path("applicable").asBoolean(true) ? DocStatus.OK : DocStatus.UNAPPLIED;
        } catch (Exception e) {
            return DocStatus.UNKNOWN;
        }
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
                    .header("Authorization", "Bearer " + currentToken())
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

    /** To'lov qabul qilingan tashkilot hisobi (organizationAccount) UUID si. */
    private String accountOf(JsonNode r) {
        return lastSegment(r.path("organizationAccount").path("meta").path("href").asText(""));
    }

    /** Hujjat valyutasi UUID si (rate.currency). */
    private String currencyOf(JsonNode r) {
        return lastSegment(r.path("rate").path("currency").path("meta").path("href").asText(""));
    }

    /** Hujjatda kiritilgan valyuta kursi (rate.value); yo'q bo'lsa 0. */
    private double rateOf(JsonNode r) {
        return r.path("rate").path("value").asDouble(0);
    }

    /**
     * 💰 Pul hisoboti (/report/money/byaccount): har bir tashkilot hisobi
     * (organizationAccount UUID) bo'yicha MoySklad ko'rsatayotgan JORIY qoldiq,
     * so'mda. Naqd (hisobga bog'lanmagan) qator "CASH" kaliti bilan qaytadi.
     * Ruxsat yo'q/xato — bo'sh map.
     */
    public java.util.Map<String, Long> fetchAccountBalances() {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        JsonNode root = getJson(props.getMoysklad().getBaseUrl() + "/report/money/byaccount");
        if (root == null) return out;
        for (JsonNode r : root.path("rows")) {
            String id = lastSegment(r.path("account").path("meta").path("href").asText(""));
            long bal = Math.round(r.path("balance").asDouble(0) / 100.0);
            out.merge(id.isEmpty() ? "CASH" : id, bal, Long::sum);
        }
        return out;
    }

    /** Valyutalar: UUID -> ISO kod (UZS, USD...). Xatoda bo'sh map. */
    public java.util.Map<String, String> fetchCurrencies() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        try {
            JsonNode root = getJson(props.getMoysklad().getBaseUrl() + "/entity/currency?limit=100");
            if (root != null)
                for (JsonNode r : root.path("rows"))
                    out.put(r.path("id").asText(), r.path("isoCode").asText(""));
        } catch (Exception e) {
            log.warn("Valyutalar o'qilmadi: {}", e.getMessage());
        }
        return out;
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
