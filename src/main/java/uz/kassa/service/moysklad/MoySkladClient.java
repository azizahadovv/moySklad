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

    private final AppProps props;

    static final DateTimeFormatter FILTER_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter MOMENT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final MoySkladHttp api;


    /** Sotuv/vozvrat hujjati: summalar TIYINDA keladi (so'mga /100).
     *  applicable=false — hujjat o'tkazilmagan (bekor qilingan) — yozuv STORNO qilinadi. */
    /* ---- token / 403 holati — tashqi chaqiruvchilar uchun (transport MoySkladHttp'da) ---- */
    public long last403At() { return api.last403At(); }
    public String last403Url() { return api.last403Url(); }
    public String currentToken() { return api.currentToken(); }
    public void updateToken(String token) { api.updateToken(token); }
    public boolean testToken(String token) { return api.testToken(token); }

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
        for (JsonNode r : api.rows(entity, "", updatedFrom)) {
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
        for (JsonNode r : api.rows("retaildrawercashout", "&expand=retailShift", updatedFrom)) {
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
        for (JsonNode r : api.rows("cashout", "&expand=expenseItem,agent,state", updatedFrom)) {
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
        for (JsonNode r : api.rows("cashin", "&expand=agent,state", updatedFrom)) {
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
        for (JsonNode r : api.rows("paymentin", "&expand=agent,state", updatedFrom)) {
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
        for (JsonNode r : api.rows("paymentout", "&expand=agent,state", updatedFrom)) {
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
        return expensesFromRows(api.rowsAll("paymentin", "&expand=agent,state"));
    }


    /** @see #fetchAllPaymentsIn() — xuddi shu, «Исходящий платеж» uchun. */
    public List<MsExpense> fetchAllPaymentsOut() {
        return expensesFromRows(api.rowsAll("paymentout", "&expand=agent,state"));
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
            JsonNode root = api.getJson(url);
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
            JsonNode root = api.getJson(props.getMoysklad().getBaseUrl()
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
            JsonNode root = api.getJson(props.getMoysklad().getBaseUrl() + "/entity/employee?limit=100");
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


    /**
     * Tashkilot hisoblari (organizationAccount): UUID -> ko'rinadigan nom.
     * MoySklad'da hisob nomi accountNumber maydonida saqlanadi
     * (masalan «NSB click Samoyiddin»). Xatoda bo'sh map.
     */
    public java.util.LinkedHashMap<String, String> fetchAccounts() {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        try {
            JsonNode orgs = api.getJson(props.getMoysklad().getBaseUrl() + "/entity/organization?limit=100");
            if (orgs == null) return out;
            for (JsonNode o : orgs.path("rows")) {
                String href = o.path("meta").path("href").asText("");
                if (href.isBlank()) continue;
                JsonNode accs = api.getJson(href + "/accounts?limit=100");
                if (accs == null) continue;
                for (JsonNode a : accs.path("rows")) {
                    String id = a.path("id").asText("");
                    String name = a.path("accountNumber").asText("");
                    if (!id.isBlank()) out.put(id, name.isBlank() ? id : name);
                }
            }
        } catch (Exception e) {
            log.warn("Tashkilot hisoblari o'qilmadi: {}", e.getMessage());
        }
        return out;
    }


    /** Otdellar (Владелец-отдел) ro'yxati: UUID -> nomi. Xatoda bo'sh map. */
    public java.util.LinkedHashMap<String, String> fetchGroups() {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        JsonNode root = api.getJson(props.getMoysklad().getBaseUrl() + "/entity/group?limit=100");
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
        for (JsonNode r : api.rowsFiltered(entity, expand, filter)) {
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


    /**
     * Davr bo'yicha sotuv/vozvrat (retaildemand / retailsalesreturn) — reconcile
     * uchun. applicable=false hujjatlar HAM qaytariladi — chaqiruvchi STORNO qiladi.
     */
    public List<MsDoc> fetchSalesByMoment(String entity, LocalDate from, LocalDate to) {
        String filter = URLEncoder.encode(
                "moment>=" + from + " 00:00:00;moment<=" + to + " 23:59:59",
                StandardCharsets.UTF_8);
        List<MsDoc> out = new ArrayList<>();
        for (JsonNode r : api.rowsFiltered(entity, "", filter)) {
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


    /** Davr bo'yicha «Выплата денег» (retaildrawercashout) — reconcile uchun. */
    public List<MsExpense> fetchDrawerCashoutsByMoment(LocalDate from, LocalDate to) {
        String filter = URLEncoder.encode(
                "moment>=" + from + " 00:00:00;moment<=" + to + " 23:59:59",
                StandardCharsets.UTF_8);
        List<MsExpense> out = new ArrayList<>();
        for (JsonNode r : api.rowsFiltered("retaildrawercashout", "&expand=retailShift", filter)) {
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


    /**
     * Bitta hujjatning hozirgi holati: OK (o'tkazilgan), UNAPPLIED (bekor qilingan),
     * DELETED (o'chirilgan), UNKNOWN (ruxsat/tarmoq xatosi — hech narsa qilinmaydi).
     * Reconcile o'chirilgan hujjatlarni faqat shu tasdiq bilan STORNO qiladi.
     */
    public DocStatus fetchDocStatus(String entity, String id) {
        try {
            String url = props.getMoysklad().getBaseUrl() + "/entity/" + entity + "/" + id;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + api.currentToken())
                    .header("Accept", "application/json;charset=utf-8")
                    .header("Accept-Encoding", "gzip")
                    .GET().build();
            HttpResponse<byte[]> resp = api.http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 404 || resp.statusCode() == 410) return DocStatus.DELETED;
            if (resp.statusCode() != 200) return DocStatus.UNKNOWN;
            JsonNode r = api.om.readTree(api.decodeBody(resp));
            return r.path("applicable").asBoolean(true) ? DocStatus.OK : DocStatus.UNAPPLIED;
        } catch (Exception e) {
            return DocStatus.UNKNOWN;
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
        for (var e : fetchAccountBalancesTiyin().entrySet())
            out.put(e.getKey(), Math.round(e.getValue() / 100.0));
        return out;
    }


    /** Xuddi shu hisobot, lekin TIYINDA (yaxlitlanmagan) — Click hisobotida karta
     *  qoldig'i bilan tiyingacha solishtirish uchun. */
    public java.util.Map<String, Long> fetchAccountBalancesTiyin() {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        JsonNode root = api.getJson(props.getMoysklad().getBaseUrl() + "/report/money/byaccount");
        if (root == null) return out;
        for (JsonNode r : root.path("rows")) {
            String id = lastSegment(r.path("account").path("meta").path("href").asText(""));
            long bal = Math.round(r.path("balance").asDouble(0));
            out.merge(id.isEmpty() ? "CASH" : id, bal, Long::sum);
        }
        return out;
    }


    /** Valyutalar: UUID -> ISO kod (UZS, USD...). Xatoda bo'sh map. */
    public java.util.Map<String, String> fetchCurrencies() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        try {
            JsonNode root = api.getJson(props.getMoysklad().getBaseUrl() + "/entity/currency?limit=100");
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


    private LocalDate date(JsonNode r) {
        return LocalDateTime.parse(r.path("moment").asText(), MOMENT_FMT).toLocalDate();
    }


    private String lastSegment(String href) {
        return href.isEmpty() ? "" : href.substring(href.lastIndexOf('/') + 1);
    }


    /* ==================== 🕵️ NAZORAT: kontragent / otgruzka / xodim ==================== */

    /** Kontragent — nazorat qoidalari uchun to'liq maydonlar. attributes: nom -> ko'rinadigan qiymat. */
    public record MsAgentFull(String id, String name, String phone, String email, String companyType,
                              String inn, String legalTitle, String legalAddress, List<String> tags,
                              String stateName, String ownerId, String ownerName, String ownerUid,
                              String groupId, LocalDateTime created, LocalDateTime updated,
                              boolean archived, java.util.Map<String, String> attributes) {
        public boolean isLegal() { return companyType != null && companyType.startsWith("legal"); }
        public boolean isEntrepreneur() { return companyType != null && companyType.startsWith("entrepreneur"); }
        public boolean isIndividual() { return companyType == null || companyType.startsWith("individual"); }
    }


    /** Otgruzka (demand). Summalar SO'MDA. dueAt — «Тўлов муддати», masul — «Масъул». */
    public record MsDemand(String id, String docNo, LocalDateTime moment, LocalDateTime created,
                           LocalDateTime updated, long sumSom, long payedSom, String agentId,
                           String agentName, String agentPhone, String agentType, String ownerId, String ownerName,
                           String ownerUid, String groupId, String stateName, String description,
                           LocalDate dueAt, String masul, boolean applicable) {}


    /** MoySklad xodimi: UUID, login (uid), nom, telefon, otdel (group), lavozim (position). */
    public record MsEmployeeFull(String id, String uid, String name, String phone,
                                 String groupId, String groupName, String position, boolean archived) {}


    private static final String AGENT_EXPAND = "&expand=owner,state";
    private static final String DEMAND_EXPAND = "&expand=agent,owner,state";


    /** updated>=from o'zgargan/yangi kontragentlar (nazorat polling). */
    public List<MsAgentFull> fetchAgentsUpdated(LocalDateTime from) {
        List<MsAgentFull> out = new ArrayList<>();
        for (JsonNode r : api.rows("counterparty", AGENT_EXPAND, from)) out.add(agentFullOf(r));
        return out;
    }


    /** Barcha kontragentlar (dublikat indeksi uchun, bir marta; expand'siz — yengil). */
    public List<MsAgentFull> fetchAgentsAll() {
        List<MsAgentFull> out = new ArrayList<>();
        for (JsonNode r : api.rowsAll("counterparty", "")) out.add(agentFullOf(r));
        return out;
    }


    /** Bitta kontragent; null — o'chirilgan (404) yoki ruxsat yo'q. */
    public MsAgentFull fetchAgent(String id) {
        JsonNode r = api.getJsonOr404(props.getMoysklad().getBaseUrl()
                + "/entity/counterparty/" + id + "?expand=owner,state");
        return r == null ? null : agentFullOf(r);
    }


    /** Kontragentni kim yaratgan — MoySklad audit (login/uid). Topilmasa "". */
    public String fetchAgentCreatorUid(String id) {
        try {
            JsonNode root = api.getJsonOr404(props.getMoysklad().getBaseUrl()
                    + "/entity/counterparty/" + id + "/audit");
            if (root == null) return "";
            String any = "";
            for (JsonNode r : root.path("rows")) {
                String uid = r.path("uid").asText("");
                if (uid.isBlank()) continue;
                if ("create".equalsIgnoreCase(r.path("eventType").asText(""))) return uid;
                any = uid;   // eng eski yozuv (ro'yxat yangidan eskiga)
            }
            return any;
        } catch (Exception e) {
            log.warn("Kontragent auditi o'qilmadi ({}): {}", id, e.getMessage());
            return "";
        }
    }


    private MsAgentFull agentFullOf(JsonNode r) {
        List<String> tags = new ArrayList<>();
        for (JsonNode t : r.path("tags")) tags.add(t.asText(""));
        JsonNode owner = r.path("owner");
        return new MsAgentFull(
                r.path("id").asText(""),
                r.path("name").asText(""),
                r.path("phone").asText(""),
                r.path("email").asText(""),
                r.path("companyType").asText(""),
                r.path("inn").asText(""),
                r.path("legalTitle").asText(""),
                r.path("legalAddress").asText(""),
                tags,
                r.path("state").path("name").asText(""),
                owner.path("id").asText(lastSegment(owner.path("meta").path("href").asText(""))),
                owner.path("name").asText(""),
                owner.path("uid").asText(""),
                groupOf(r),
                dt(r, "created"),
                dt(r, "updated"),
                r.path("archived").asBoolean(false),
                attributesOf(r));
    }


    /** updated>=from o'zgargan/yangi otgruzkalar. */
    public List<MsDemand> fetchDemandsUpdated(LocalDateTime from) {
        List<MsDemand> out = new ArrayList<>();
        for (JsonNode r : api.rows("demand", DEMAND_EXPAND, from)) out.add(demandOf(r));
        return out;
    }


    /** Hujjat sanasi (moment) bo'yicha otgruzkalar — eski qarzlarni yuklash uchun. */
    public List<MsDemand> fetchDemandsByMoment(LocalDateTime from, LocalDateTime to) {
        String filter = java.net.URLEncoder.encode(
                "moment>=" + api.toMs(from).format(FILTER_FMT) + ";moment<=" + api.toMs(to).format(FILTER_FMT),
                java.nio.charset.StandardCharsets.UTF_8);
        List<MsDemand> out = new ArrayList<>();
        for (JsonNode r : api.rowsFiltered("demand", DEMAND_EXPAND, filter)) out.add(demandOf(r));
        return out;
    }


    /** Bitta otgruzka; null — MoySklad'da o'chirilgan (404) yoki ruxsat yo'q. */
    public MsDemand fetchDemand(String id) {
        JsonNode r = api.getJsonOr404(props.getMoysklad().getBaseUrl()
                + "/entity/demand/" + id + "?expand=agent,owner,state");
        return r == null ? null : demandOf(r);
    }


    private MsDemand demandOf(JsonNode r) {
        JsonNode agent = r.path("agent"), owner = r.path("owner");
        LocalDate due = null;
        String masul = "";
        for (JsonNode a : r.path("attributes")) {
            String n = normAttr(a.path("name").asText(""));
            if (n.contains("тулов") && n.contains("муддат")) {
                String v = a.path("value").asText("");
                if (v.length() >= 10) try { due = LocalDate.parse(v.substring(0, 10)); } catch (Exception ignored) { }
            } else if (n.equals("масъул") || n.equals("масул") || n.equals("masul")) {
                JsonNode v = a.path("value");
                masul = v.isObject() ? v.path("name").asText("") : v.asText("");
            }
        }
        return new MsDemand(
                r.path("id").asText(""),
                r.path("name").asText(""),
                dt(r, "moment"),
                dt(r, "created"),
                dt(r, "updated"),
                Math.round(r.path("sum").asDouble(0) / 100.0),
                Math.round(r.path("payedSum").asDouble(0) / 100.0),
                agent.path("id").asText(lastSegment(agent.path("meta").path("href").asText(""))),
                agent.path("name").asText(""),
                agent.path("phone").asText(""),
                agent.path("companyType").asText(""),
                owner.path("id").asText(lastSegment(owner.path("meta").path("href").asText(""))),
                owner.path("name").asText(""),
                owner.path("uid").asText(""),
                groupOf(r),
                r.path("state").path("name").asText(""),
                r.path("description").asText(""),
                due, masul,
                r.path("applicable").asBoolean(true));
    }


    /** Xodimlar: UUID, login, nom, telefon. Xatoda bo'sh ro'yxat. */
    public List<MsEmployeeFull> fetchEmployeesFull() {
        List<MsEmployeeFull> out = new ArrayList<>();
        try {
            JsonNode root = api.getJson(props.getMoysklad().getBaseUrl() + "/entity/employee?limit=100&expand=group");
            if (root != null)
                for (JsonNode r : root.path("rows")) {
                    JsonNode g = r.path("group");
                    out.add(new MsEmployeeFull(r.path("id").asText(""), r.path("uid").asText(""),
                            r.path("name").asText(""), r.path("phone").asText(""),
                            g.path("id").asText(lastSegment(g.path("meta").path("href").asText(""))),
                            g.path("name").asText(""), r.path("position").asText(""),
                            r.path("archived").asBoolean(false)));
                }
        } catch (Exception e) {
            log.warn("Xodimlar (to'liq) ro'yxati o'qilmadi: {}", e.getMessage());
        }
        return out;
    }


    /**
     * Bir nechta kontragent balansi (so'm, manfiy = bizga qarzdor): POST /report/counterparty
     * 100 tadan; POST ishlamasa — har biri alohida GET. Olinmaganlari map'da bo'lmaydi.
     */
    public java.util.Map<String, Long> fetchAgentBalancesSom(java.util.Collection<String> ids) {
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        List<String> list = new ArrayList<>(new java.util.LinkedHashSet<>(ids));
        String base = props.getMoysklad().getBaseUrl();
        for (int i = 0; i < list.size(); i += 100) {
            List<String> chunk = list.subList(i, Math.min(list.size(), i + 100));
            boolean ok = false;
            try {
                StringBuilder body = new StringBuilder("{\"counterparties\":[");
                for (int j = 0; j < chunk.size(); j++) {
                    if (j > 0) body.append(',');
                    body.append("{\"counterparty\":{\"meta\":{\"href\":\"").append(base)
                        .append("/entity/counterparty/").append(chunk.get(j))
                        .append("\",\"type\":\"counterparty\",\"mediaType\":\"application/json\"}}}");
                }
                body.append("]}");
                JsonNode root = api.postJson(base + "/report/counterparty", body.toString());
                if (root != null) {
                    for (JsonNode r : root.path("rows")) {
                        String id = lastSegment(r.path("counterparty").path("meta").path("href").asText(""));
                        if (!id.isBlank()) out.put(id, Math.round(r.path("balance").asDouble(0) / 100.0));
                    }
                    ok = true;
                }
            } catch (Exception e) {
                log.warn("Balanslar (POST) o'qilmadi, alohida so'raladi: {}", e.getMessage());
            }
            if (!ok)
                for (String id : chunk) {
                    Long b = fetchAgentBalanceSom(id);
                    if (b != null) out.put(id, b);
                    try { Thread.sleep(80); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return out; }
                }
        }
        return out;
    }


    private java.util.Map<String, String> attributesOf(JsonNode r) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (JsonNode a : r.path("attributes")) {
            JsonNode v = a.path("value");
            String s = v.isObject() ? v.path("name").asText("") : v.asText("");
            m.put(a.path("name").asText(""), s);
        }
        return m;
    }


    /** Attribut nomini solishtirish uchun: kichik harf, ў→у, қ→к, ғ→г, ҳ→х. */
    public static String normAttr(String s) {
        return s == null ? "" : s.toLowerCase().replace('ў', 'у').replace('қ', 'к')
                .replace('ғ', 'г').replace('ҳ', 'х').trim();
    }


    private static final DateTimeFormatter DT_FMT =
            new java.time.format.DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd HH:mm:ss")
                    .optionalStart().appendPattern(".SSS").optionalEnd().toFormatter();

    /** MoySklad vaqt maydoni (Moskva, UTC+3) → bot vaqti (Toshkent). */
    private LocalDateTime dt(JsonNode r, String field) {
        String v = r.path(field).asText("");
        if (v.isBlank()) return null;
        try { return api.fromMs(LocalDateTime.parse(v, DT_FMT)); } catch (Exception e) { return null; }
    }

}
