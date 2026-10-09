package uz.kassa.service.adesk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;

/**
 * 📒 Adesk API'ning integratsiyaga kerakli qismi (docs/ADESK.md §2): yuridik shaxslar, hisoblar, statyalar,
 * kontragentlar, tovar/xizmatlar, operatsiyalar (v2 to'plamli), majburiyatlar (начисления/отгрузки).
 * Ro'yxatlar {@code start/length} bilan sahifalab, id bo'yicha takrorsiz o'qiladi.
 */
@Component
@RequiredArgsConstructor
public class AdeskClient {

    private final AdeskHttp http;

    public static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter RU = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final int PAGE = 100;

    /* ==================== obyektlar ==================== */

    public record AdLegal(long id, String name, String inn) {}
    public record AdAccount(long id, String name, String type, Long legalEntityId, BigDecimal amount,
                            BigDecimal initialAmount, LocalDate initialDate, String status, String currency) {}
    public record AdCategory(long id, String name, int type, boolean ownerTransfer, boolean archived) {}
    public record AdContractor(long id, String name, String phone) {}
    public record AdUnit(long id, String name, String symbol) {}
    public record AdProduct(long id, String name, String sku, int type) {}
    public record AdTx(long id, int type, BigDecimal amount, LocalDate date, Long accountId, Long categoryId,
                       Long contractorId, String description, boolean transfer, boolean planned, String importedId,
                       String categoryName, String contractorName, Long projectId, String accountName) {
        public boolean income() { return type == 1; }
        public long signedTiyin() { long t = amount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValue(); return income() ? t : -t; }
    }
    public record AdCommit(long id, int type, BigDecimal amount, LocalDate date, Long contractorId, String description) {}

    /* ==================== yuridik shaxslar ==================== */

    public List<AdLegal> legalEntities() {
        return listAll("legal-entities", "legalEntities", Map.of(), false,
                j -> new AdLegal(j.path("id").asLong(), j.path("name").asText(""), j.path("inn").asText("")));
    }

    public AdLegal createLegalEntity(String name, String fullName, String inn) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("name", cut(name, 210));
        if (fullName != null && !fullName.isBlank()) p.put("full_name", cut(fullName, 255));
        if (inn != null && !inn.isBlank()) p.put("inn", cut(inn, 20));
        JsonNode j = http.postForm("legal-entity", p).path("legalEntity");
        return new AdLegal(j.path("id").asLong(), j.path("name").asText(name), j.path("inn").asText(""));
    }

    /* ==================== hisoblar ==================== */

    public List<AdAccount> bankAccounts() {
        return listAll("bank-accounts", "bankAccounts", Map.of(), false, this::account);
    }

    /** type: 1 — naqd/kassa, 2 — bank hisobi. initialAmount so'mda (2 xona). */
    public AdAccount createBankAccount(String name, String currency, long legalEntity, int type, String number,
                                       String bankName, BigDecimal initialAmount, LocalDate initialDate) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("name", cut(name, 210));
        p.put("currency", currency);
        p.put("legal_entity", String.valueOf(legalEntity));
        p.put("type", String.valueOf(type));
        if (number != null && !number.isBlank()) p.put("number", number);
        if (bankName != null && !bankName.isBlank()) p.put("bank_name", cut(bankName, 210));
        if (initialAmount != null) {
            p.put("initial_amount", initialAmount.toPlainString());
            p.put("initial_amount_date", initialDate.format(ISO));
        }
        return account(http.postForm("bank-account", p).path("bankAccount"));
    }

    /** Boshlang'ich qoldiqni (va nom/turini) yangilash. Adesk talabi: name har doim yuboriladi. */
    public void updateBankAccount(long id, String name, long legalEntity, int type, BigDecimal initialAmount, LocalDate initialDate) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("name", cut(name, 210));
        p.put("legal_entity", String.valueOf(legalEntity));
        p.put("type", String.valueOf(type));
        p.put("initial_amount", initialAmount.toPlainString());
        p.put("initial_amount_date", initialDate.format(ISO));
        http.postForm("bank-account/" + id, p);
    }

    private AdAccount account(JsonNode j) {
        Long le = j.path("legalEntity").path("id").isNumber() ? j.path("legalEntity").path("id").asLong() : null;
        return new AdAccount(j.path("id").asLong(), j.path("name").asText(""), j.path("type").asText(""), le,
                dec(j.path("amount")), dec(j.path("initialAmount")), date(j.path("initialAmountDate").asText("")),
                j.path("status").asText("open"), j.path("currency").asText(""));
    }

    /* ==================== statyalar ==================== */

    public List<AdCategory> categories() {
        JsonNode j = http.get("transactions/categories", Map.of());
        List<AdCategory> out = new ArrayList<>();
        for (JsonNode c : j.path("categories"))
            out.add(new AdCategory(c.path("id").asLong(), c.path("name").asText(""), c.path("type").asInt(0),
                    c.path("isOwnerTransfer").asBoolean(false), c.path("isArchived").asBoolean(false)));
        return out;
    }

    /** type: 1 — kirim, 2 — chiqim; kind: 1 — operatsion, 2 — investitsion, 3 — moliyaviy. */
    public AdCategory createCategory(String name, int type, int kind, boolean ownerTransfer) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("name", cut(name, 210));
        p.put("type", String.valueOf(type));
        p.put("kind", String.valueOf(kind));
        p.put("is_owner_transfer", String.valueOf(ownerTransfer));
        JsonNode c = http.postForm("transactions/category", p).path("category");
        return new AdCategory(c.path("id").asLong(), c.path("name").asText(name), c.path("type").asInt(type), ownerTransfer, false);
    }

    /* ==================== kontragentlar ==================== */

    public List<AdContractor> contractors() {
        return listAll("contractors", "contractors", Map.of(), false,
                j -> new AdContractor(j.path("id").asLong(), j.path("name").asText(""), j.path("phoneNumber").asText("")));
    }

    public AdContractor createContractor(String name, String phone, String email, String description) {
        JsonNode j = http.postForm("contractor", contractorParams(name, phone, email, description)).path("contractor");
        return new AdContractor(j.path("id").asLong(), j.path("name").asText(name), j.path("phoneNumber").asText(""));
    }

    public void updateContractor(long id, String name, String phone, String email, String description) {
        http.postForm("contractor/" + id, contractorParams(name, phone, email, description));
    }

    private Map<String, String> contractorParams(String name, String phone, String email, String description) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("name", cut(name, 210));
        p.put("phone_number", cut(phone == null ? "" : phone, 32));
        p.put("email", email == null ? "" : email.trim());
        p.put("description", cut(description == null ? "" : description, 510));
        return p;
    }

    /* ==================== tovar va xizmatlar ==================== */

    public List<AdUnit> units() {
        JsonNode j = http.get("warehouse/units", Map.of());
        List<AdUnit> out = new ArrayList<>();
        for (JsonNode u : j.path("units"))
            out.add(new AdUnit(u.path("id").asLong(), u.path("name").asText(""), u.path("symbol").asText("")));
        return out;
    }

    public List<AdProduct> products() {
        return listAll("warehouse/products", "products", Map.of(), false,
                j -> new AdProduct(j.path("id").asLong(), j.path("name").asText(""), j.path("sku").asText(""), j.path("type").asInt(1)));
    }

    /** params — Adesk form maydonlari (type, name, sku, unit/unit_name, with_initial_batch, initial_batch_*). */
    public AdProduct createProduct(Map<String, String> params) {
        JsonNode j = http.postForm("warehouse/product", params).path("product");
        return new AdProduct(j.path("id").asLong(), j.path("name").asText(params.get("name")), j.path("sku").asText(""), j.path("type").asInt(1));
    }

    public void updateProduct(long id, Map<String, String> params) {
        http.postForm("warehouse/product/" + id, params);
    }

    /* ==================== operatsiyalar ==================== */

    /** v2 to'plamli yaratish. Har elementda importedId bo'lishi shart — javob shu kalit bo'yicha moslanadi. importedId → Adesk id. */
    public Map<String, Long> createTransactions(List<ObjectNode> txs) {
        ObjectNode body = http.om.createObjectNode();
        ArrayNode arr = body.putArray("transactions");
        txs.forEach(arr::add);
        body.put("applyImportRules", false);
        body.put("applyImportAutomation", false);
        JsonNode j = http.postJson("transactions/create", body);
        Map<String, Long> out = new LinkedHashMap<>();
        JsonNode list = j.path("data").path("transactions");
        for (int i = 0; i < list.size(); i++) {
            JsonNode t = list.get(i);
            String imp = t.path("importedId").asText("");
            if (imp.isBlank() && i < txs.size()) imp = txs.get(i).path("importedId").asText("");
            out.put(imp, t.path("id").asLong());
        }
        return out;
    }

    public void updateTransactions(List<ObjectNode> txs) {
        ObjectNode body = http.om.createObjectNode();
        ArrayNode arr = body.putArray("transactions");
        txs.forEach(arr::add);
        http.postJson("transactions/update", body);
    }

    public void removeTransactions(Collection<Long> ids) {
        if (ids.isEmpty()) return;
        ObjectNode body = http.om.createObjectNode();
        ArrayNode arr = body.putArray("transactionsIds");
        ids.forEach(arr::add);
        http.postJson("transactions/remove", body);
    }

    /** Davrdagi fakt operatsiyalar (rejalashtirilganlarsiz). */
    public List<AdTx> transactions(LocalDate from, LocalDate to) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("range", "custom");
        p.put("range_start", from.format(ISO));
        p.put("range_end", to.format(ISO));
        p.put("status", "completed");
        return listAll("transactions", "transactions", p, false, this::tx);
    }

    private AdTx tx(JsonNode j) {
        LocalDate d = date(j.path("dateIso").asText(""));
        if (d == null) d = date(j.path("date").asText(""));
        return new AdTx(j.path("id").asLong(), j.path("type").asInt(0), dec(j.path("amount")), d,
                idOf(j.path("bankAccount")), idOf(j.path("category")), idOf(j.path("contractor")),
                j.path("description").asText(""), j.path("isTransfer").asBoolean(false),
                j.path("isPlanned").asBoolean(false), j.path("importedId").asText(""),
                j.path("category").path("name").asText(""), j.path("contractor").path("name").asText(""),
                idOf(j.path("project")), j.path("bankAccount").path("name").asText(""));
    }

    public record AdProject(long id, String name) {}

    /** Proyektlar (arxivdagilar ham). */
    public List<AdProject> projects() {
        return listAll("projects", "projects", Map.of("status", "all"), false,
                j -> new AdProject(j.path("id").asLong(), j.path("name").asText("")));
    }

    public AdTx transaction(long id) {
        return tx(http.get("transaction/" + id, Map.of()).path("transaction"));
    }

    /* ==================== majburiyatlar (начисления / отгрузки) ==================== */

    public long createCommitment(Map<String, String> params) {
        return http.postForm("commitment", params).path("commitment").path("id").asLong();
    }

    public void removeCommitment(long id) {
        http.postForm("commitment/" + id + "/remove", Map.of());
    }

    public List<AdCommit> commitments(LocalDate from, LocalDate to) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("range", "custom");
        p.put("range_start", from.format(ISO));
        p.put("range_end", to.format(ISO));
        return listAll("commitments", "commitments", p, true,
                j -> new AdCommit(j.path("id").asLong(), j.path("type").asInt(0), dec(j.path("amount")),
                        date(j.path("date").asText("")), idOf(j.path("contractor")), j.path("description").asText("")));
    }

    /* ==================== yordamchilar ==================== */

    /** Sahifalab o'qish (start/length). Sahifalash qo'llab-quvvatlanmasa takroriy id'lar bo'yicha to'xtaydi. */
    private <T> List<T> listAll(String path, String field, Map<String, String> params, boolean post, Function<JsonNode, T> map) {
        List<T> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        long total = -1;
        for (int page = 0; page < 2000; page++) {
            Map<String, String> p = new LinkedHashMap<>(params);
            p.put("start", String.valueOf(page * PAGE));
            p.put("length", String.valueOf(PAGE));
            JsonNode j = post ? http.postForm(path, p) : http.get(path, p);
            JsonNode arr = j.path(field);
            int fresh = 0;
            for (JsonNode x : arr) {
                long id = x.path("id").asLong();
                if (seen.add(id)) { out.add(map.apply(x)); fresh++; }
            }
            total = j.path("recordsFiltered").asLong(total);
            if (arr.size() == 0 || fresh == 0 || arr.size() < PAGE) break;
            if (total >= 0 && out.size() >= total) break;
        }
        // Himoya: ro'yxat chala o'qilsa (sahifalash ishlamasa) — «yo'q» deb qayta yaratish dublikat beradi
        // jonli ro'yxatda sahifalash vaqtida 1–2 ta yozuv siljishi mumkin (2026-10-06: 8760/8761 yuklashni to'xtatdi) — kichik farq kechiriladi
        if (total > 0 && out.size() < total - Math.max(5, total / 100))
            throw new IllegalStateException("Adesk ro'yxati (" + path + ") to'liq o'qilmadi: " + out.size() + " / " + total);
        return out;
    }

    static Long idOf(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.isNumber()) return n.asLong();
        JsonNode id = n.path("id");
        return id.isNumber() || id.isTextual() && !id.asText().isBlank() ? id.asLong() : null;
    }

    static BigDecimal dec(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return BigDecimal.ZERO;
        try { return new BigDecimal(n.asText("0").trim().replace(" ", "").replace(',', '.')); }
        catch (Exception e) { return BigDecimal.ZERO; }
    }

    /** «2026-09-05», «2026.09.05», «05.09.2026» yoki «05.09.2026 12:00» → sana. */
    static LocalDate date(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        if (t.length() > 10) t = t.substring(0, 10);
        try {
            if (t.matches("\\d{4}[-.]\\d{2}[-.]\\d{2}")) return LocalDate.parse(t.replace('.', '-'));
            if (t.matches("\\d{2}\\.\\d{2}\\.\\d{4}")) return LocalDate.parse(t, RU);
        } catch (Exception ignored) { }
        return null;
    }

    static String cut(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
