package uz.kassa.service.adesk;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uz.kassa.service.moysklad.MoySkladClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * 📒 Adesk integratsiyasi uchun MoySklad'dan o'qish (docs/ADESK.md §3). Hamma so'rov {@link MoySkladClient}
 * (umumiy tezlik darvozasi) orqali. Vaqtlar: MoySklad — Moskva, bu yerdan chiqadigan sana/vaqt — bot (Toshkent).
 * Summalar TIYINDA; valyutadagi hujjat kurs bo'yicha bazaviy valyutaga o'giriladi.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdeskMsReader {

    private final MoySkladClient ms;

    public static final String CASH = "CASH";
    static final String TRANSFER = "Перемещение собственных средств";
    public static final List<String> MONEY_ENTITIES = List.of("cashin", "cashout", "paymentin", "paymentout");
    /** Tovar hujjatlari va Adesk majburiyat yo'nalishi: in — kontragentdan oldik (to'lashimiz kerak), out — berdik. */
    public static final Map<String, String> GOODS_ENTITIES = new LinkedHashMap<>();
    static {
        GOODS_ENTITIES.put("supply", "in");
        GOODS_ENTITIES.put("demand", "out");
        GOODS_ENTITIES.put("salesreturn", "in");
        GOODS_ENTITIES.put("purchasereturn", "out");
    }

    /* ==================== obyektlar ==================== */

    public record MsOrg(String id, String name, String legalTitle, String inn, String shortName) {}

    /** Pul hisobi. key = "<orgId>:CASH" (tashkilot kassasi) yoki "<orgId>:<accountId>". isDefault — tashkilotning asosiy hisobi (р/с). */
    public record MsAccount(String key, String orgId, String accountId, String rawName, String bankName, String number, boolean cash, boolean isDefault) {}

    public record MsMoneyDoc(String id, String entity, String number, LocalDate date, LocalDateTime updated,
                             long sumTiyin, long origTiyin, String currencyIso, double rate, String orgId, String accountKey,
                             String agentId, String agentType, String expenseItemId, String description, String purpose,
                             boolean applicable) {
        /** Kirim: boshqa o'z firmamizdan kelgan (agent — boshqa tashkilot) yoki «Перемещение собственных средств» maqsadli = o'tkazma. */
        public boolean incomeTransfer() {
            if (!income()) return false;
            return purpose.startsWith(TRANSFER) || description.startsWith(TRANSFER)
                    || ("organization".equals(agentType) && !agentId.isEmpty() && !agentId.equals(orgId));
        }
        public boolean income() { return entity.equals("cashin") || entity.equals("paymentin"); }
        public long signedTiyin() { return income() ? sumTiyin : -sumTiyin; }
    }

    public record MsCounterparty(String id, String name, String phone, String email, String inn, String code, boolean archived, String address, String comment) {}

    public record MsProduct(String id, String type, String name, String code, String article, String uomId, String supplierId) {
        public boolean service() { return "service".equals(type); }
    }

    /** Pozitsiya: narx — chegirmadan keyin, bitta birlik uchun (tiyin). */
    public record MsPos(String assortmentId, String assortmentType, double quantity, long priceTiyin) {}

    /** state — MoySklad hujjat statusi nomi («Карз перечисление», «Накд», «Клик» …), bo'lmasa bo'sh. */
    public record MsGoodsDoc(String id, String entity, String number, LocalDate date, long sumTiyin, String orgId,
                             String agentId, String agentType, String description, boolean applicable, List<MsPos> positions,
                             String state) {
        public MsGoodsDoc(String id, String entity, String number, LocalDate date, long sumTiyin, String orgId,
                          String agentId, String agentType, String description, boolean applicable, List<MsPos> positions) {
            this(id, entity, number, date, sumTiyin, orgId, agentId, agentType, description, applicable, positions, "");
        }
    }

    public record MsStock(String productId, double qty, long costTiyin) {}

    /* ==================== ma'lumotnomalar ==================== */

    public List<MsOrg> orgs() {
        List<MsOrg> out = new ArrayList<>();
        for (JsonNode r : ms.listAll("entity/organization?limit=100", 5)) {
            String name = r.path("name").asText("");
            out.add(new MsOrg(r.path("id").asText(), name, r.path("legalTitle").asText(""), r.path("inn").asText(""), shortName(name)));
        }
        return out;
    }

    /** Har tashkilot: kassa (CASH) + uning hisoblari. Nomi — MoySklad'dagi «accountNumber» (bot boshqa joylarda ham shunday ataydi). */
    public List<MsAccount> accounts(List<MsOrg> orgs) {
        List<MsAccount> out = new ArrayList<>();
        for (MsOrg o : orgs) {
            out.add(new MsAccount(o.id() + ":" + CASH, o.id(), null, "Касса", "", "", true, false));
            for (JsonNode a : ms.listAll("entity/organization/" + o.id() + "/accounts?limit=100", 3)) {
                String num = a.path("accountNumber").asText("").trim();
                String id = a.path("id").asText();
                String digits = num.replaceAll("\\D", "");
                out.add(new MsAccount(o.id() + ":" + id, o.id(), id, num.isEmpty() ? id : num,
                        a.path("bankName").asText("").trim(), digits.length() >= 16 ? digits : "", looksCash(num),
                        a.path("isDefault").asBoolean(false)));
            }
        }
        return out;
    }

    /** Joriy qoldiqlar (Деньги → Остатки), kalit "<orgId>:CASH" / "<orgId>:<accountId>", TIYIN. */
    public Map<String, Long> balancesTiyin() {
        Map<String, Long> out = new LinkedHashMap<>();
        JsonNode root = ms.fetchJson("report/money/byaccount");
        if (root == null) throw new IllegalStateException("MoySklad pul qoldiqlari hisoboti o'qilmadi (ruxsat yo'q)");
        for (JsonNode r : root.path("rows")) {
            String org = ms.idOf(r.path("organization"));
            String acc = r.path("account").isMissingNode() || r.path("account").isNull() ? "" : ms.idOf(r.path("account"));
            out.merge(org + ":" + (acc.isEmpty() ? CASH : acc), Math.round(r.path("balance").asDouble(0)), Long::sum);
        }
        return out;
    }

    /** Qoldiqlar va davr boshidan bugungacha pul hujjatlari — bir-biriga mos surat. stable=false — 3 urinishda ham harakat to'xtamadi. */
    public record MoneySnap(Map<String, Long> balances, List<MsMoneyDoc> docs, boolean stable) {}

    /**
     * MoySklad jonli: qoldiqlar va hujjatlar alohida so'rovlarda o'qiladi, oradagi soniyalarda yangi hujjat tushsa
     * «qoldiq − harakat» hisobi o'sha hujjat summasiga siljiydi (2026-10-05 mashqida 1 320 000 farq shundan chiqdi).
     * Shuning uchun: qoldiq → hujjatlar → qoldiq; ikki qoldiq teng bo'lsa surat izchil, aks holda qayta (3 martagacha).
     */
    public MoneySnap moneySnapshot(LocalDate from, LocalDate to, Map<String, String> currencies) {
        Map<String, Long> b1 = balancesTiyin();
        for (int attempt = 1; ; attempt++) {
            List<MsMoneyDoc> docs = moneyDocs(from, to, null, currencies);
            Map<String, Long> b2 = balancesTiyin();
            if (b1.equals(b2)) return new MoneySnap(b2, docs, true);
            if (attempt >= 3) {
                log.info("MoySklad pul qoldiqlari o'qish davomida o'zgardi (3 urinish) — oxirgi surat olindi");
                return new MoneySnap(b2, docs, false);
            }
            b1 = b2;
        }
    }

    public Map<String, String> expenseItems() {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode r : ms.listAll("entity/expenseitem?limit=1000", 5)) out.put(r.path("id").asText(), r.path("name").asText(""));
        return out;
    }

    public Map<String, String> employees() {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode r : ms.listAll("entity/employee?limit=1000", 5)) out.put(r.path("id").asText(), r.path("name").asText(""));
        return out;
    }

    public Map<String, String> uoms() {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode r : ms.listAll("entity/uom?limit=1000", 5)) out.put(r.path("id").asText(), r.path("name").asText(""));
        return out;
    }

    /** Valyutalar: id → ISO; bazaviy valyuta "BASE" kaliti ostida (id). */
    public Map<String, String> currencies() {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode r : ms.listAll("entity/currency?limit=100", 2)) {
            out.put(r.path("id").asText(), r.path("isoCode").asText(""));
            if (r.path("default").asBoolean(false)) out.put("BASE", r.path("id").asText());
        }
        return out;
    }

    /* ==================== kontragent / tovar ==================== */

    /** Hamma kontragentlar yoki updatedFrom (bot vaqti) dan keyin o'zgarganlar. */
    public List<MsCounterparty> counterparties(LocalDateTime updatedFrom) {
        String q = "entity/counterparty?limit=1000" + (updatedFrom == null ? "" : "&filter=" + enc("updated>=" + ms.filterTime(updatedFrom)));
        List<MsCounterparty> out = new ArrayList<>();
        for (JsonNode r : ms.listAll(q, 200)) out.add(cp(r));
        return out;
    }

    /** Obyektlar soni (meta.size) — masalan "entity/counterparty". */
    public int count(String path) {
        JsonNode r = ms.fetchJson(path + (path.contains("?") ? "&" : "?") + "limit=1");
        return r == null ? 0 : r.path("meta").path("size").asInt(0);
    }

    public MsCounterparty counterparty(String id) {
        JsonNode r = ms.fetchJson("entity/counterparty/" + id);
        return r == null || r.path("id").asText("").isEmpty() ? null : cp(r);
    }

    private MsCounterparty cp(JsonNode r) {
        return new MsCounterparty(r.path("id").asText(), r.path("name").asText(""), r.path("phone").asText(""),
                r.path("email").asText(""), r.path("inn").asText(""), r.path("code").asText(""), r.path("archived").asBoolean(false),
                r.path("actualAddress").asText(r.path("legalAddress").asText("")).trim(), r.path("description").asText("").trim());
    }

    public List<MsProduct> products(LocalDateTime updatedFrom) {
        List<MsProduct> out = new ArrayList<>();
        String f = updatedFrom == null ? "" : "&filter=" + enc("updated>=" + ms.filterTime(updatedFrom));
        for (String e : List.of("product", "service"))
            for (JsonNode r : ms.listAll("entity/" + e + "?limit=1000" + f, 200))
                out.add(new MsProduct(r.path("id").asText(), e, r.path("name").asText(""), r.path("code").asText(""),
                        r.path("article").asText(""), ms.idOf(r.path("uom")), ms.idOf(r.path("supplier"))));
        return out;
    }

    public MsProduct product(String type, String id) {
        JsonNode r = ms.fetchJson("entity/" + type + "/" + id);
        if (r == null || r.path("id").asText("").isEmpty()) return null;
        return new MsProduct(r.path("id").asText(), type, r.path("name").asText(""), r.path("code").asText(""),
                r.path("article").asText(""), ms.idOf(r.path("uom")), ms.idOf(r.path("supplier")));
    }

    /** Sana boshidagi (00:00, bot vaqti) ombor qoldig'i: faqat musbat qoldiqli tovarlar, tannarx bilan. Manfiylar soni — negOut[0]. */
    public List<MsStock> stockAt(LocalDate date, int[] negOut) {
        String moment = ms.filterTime(date.atStartOfDay());
        List<MsStock> out = new ArrayList<>();
        int neg = 0;
        for (JsonNode r : ms.listAll("report/stock/all?limit=1000&filter=" + enc("moment=" + moment), 200)) {
            if (!"product".equals(r.path("meta").path("type").asText(""))) continue;
            double q = r.path("stock").asDouble(0);
            if (q < 0) { neg++; continue; }
            if (q == 0) continue;
            out.add(new MsStock(ms.idOf(r), q, Math.round(r.path("price").asDouble(0))));
        }
        if (negOut != null && negOut.length > 0) negOut[0] = neg;
        return out;
    }

    /* ==================== hujjatlar ==================== */

    /** Pul hujjatlari: sana [from, to] (bot vaqti, kun bo'yicha), ixtiyoriy updatedFrom. to == null — yuqori chegarasiz. */
    public List<MsMoneyDoc> moneyDocs(LocalDate from, LocalDate to, LocalDateTime updatedFrom, Map<String, String> currencies) {
        List<MsMoneyDoc> out = new ArrayList<>();
        String base = currencies.getOrDefault("BASE", "");
        for (String e : MONEY_ENTITIES) {
            for (JsonNode r : ms.listAll("entity/" + e + "?limit=1000&filter=" + enc(period(from, to, updatedFrom)), 400)) {
                String org = ms.idOf(r.path("organization"));
                String acc = r.path("organizationAccount").isMissingNode() ? "" : ms.idOf(r.path("organizationAccount"));
                String cur = ms.idOf(r.path("rate").path("currency"));
                double rate = r.path("rate").path("value").asDouble(0);
                long orig = Math.round(r.path("sum").asDouble(0));
                boolean foreign = !cur.isEmpty() && !base.isEmpty() && !cur.equals(base);
                long sum = foreign && rate > 0 ? Math.round(orig * rate) : orig;
                LocalDateTime m = ms.dtOf(r, "moment");
                out.add(new MsMoneyDoc(r.path("id").asText(), e, r.path("name").asText(""),
                        m == null ? from : m.toLocalDate(), ms.dtOf(r, "updated"), sum, orig,
                        foreign ? currencies.getOrDefault(cur, "") : "", foreign ? rate : 1,
                        org, org + ":" + (acc.isEmpty() ? CASH : acc),
                        ms.idOf(r.path("agent")), r.path("agent").path("meta").path("type").asText(""),
                        r.path("expenseItem").isMissingNode() ? "" : ms.idOf(r.path("expenseItem")),
                        r.path("description").asText("").trim(), r.path("paymentPurpose").asText("").trim(),
                        r.path("applicable").asBoolean(true)));
            }
        }
        return out;
    }

    /** Tovar hujjatlari (pozitsiyalari bilan): supply / demand / salesreturn / purchasereturn. */
    public List<MsGoodsDoc> goodsDocs(String entity, LocalDate from, LocalDate to, LocalDateTime updatedFrom) {
        List<MsGoodsDoc> out = new ArrayList<>();
        for (JsonNode r : ms.listAll("entity/" + entity + "?limit=100&expand=positions,state&filter=" + enc(period(from, to, updatedFrom)), 2000)) {
            JsonNode pos = r.path("positions");
            List<JsonNode> rows = new ArrayList<>();
            pos.path("rows").forEach(rows::add);
            if (pos.path("meta").path("size").asInt(rows.size()) > rows.size())   // 1000+ pozitsiya — alohida o'qiladi
                rows = ms.listAll("entity/" + entity + "/" + r.path("id").asText() + "/positions?limit=1000", 20);
            List<MsPos> ps = new ArrayList<>();
            for (JsonNode p : rows) {
                double disc = p.path("discount").asDouble(0);
                long price = Math.round(p.path("price").asDouble(0) * (1 - disc / 100.0));
                ps.add(new MsPos(ms.idOf(p.path("assortment")), p.path("assortment").path("meta").path("type").asText(""),
                        p.path("quantity").asDouble(0), price));
            }
            LocalDateTime m = ms.dtOf(r, "moment");
            out.add(new MsGoodsDoc(r.path("id").asText(), entity, r.path("name").asText(""), m == null ? from : m.toLocalDate(),
                    Math.round(r.path("sum").asDouble(0)), ms.idOf(r.path("organization")), ms.idOf(r.path("agent")),
                    r.path("agent").path("meta").path("type").asText(""), r.path("description").asText("").trim(),
                    r.path("applicable").asBoolean(true), ps, r.path("state").path("name").asText("").trim()));
        }
        return out;
    }

    /** Davrdagi o'tkazilgan hujjatlar soni (solishtirish va Adesk'ka o'tmaydigan списание/оприходование uchun). */
    public int countDocs(String entity, LocalDate from, LocalDate to) {
        JsonNode r = ms.fetchJson("entity/" + entity + "?limit=1&filter=" + enc(period(from, to, null) + ";applicable=true"));
        return r == null ? 0 : r.path("meta").path("size").asInt(0);
    }

    /** MoySklad filtri: moment kun oralig'i (bot vaqti → Moskva) va ixtiyoriy updated>=. */
    private String period(LocalDate from, LocalDate to, LocalDateTime updatedFrom) {
        StringBuilder f = new StringBuilder("moment>=" + ms.filterTime(from.atStartOfDay()));
        if (to != null) f.append(";moment<=").append(ms.filterTime(to.atTime(LocalTime.of(23, 59, 59))));
        if (updatedFrom != null) f.append(";updated>=").append(ms.filterTime(updatedFrom));
        return f.toString();
    }

    /* ==================== yordamchilar ==================== */

    /** «001 NSB New Star Bukhara МЧЖ 302037932» → «001 NSB». */
    static String shortName(String name) {
        String[] t = name.replace("\"", "").replace("'", "").trim().split("\\s+");
        if (t.length >= 2) return t[0] + " " + t[1];
        return name.trim();
    }

    /** Hisob nomidan naqd ekanini taxmin qilish («Kassa 1 Naqd UZS», «Касса Доллар USD»). */
    static boolean looksCash(String name) {
        String n = name.toLowerCase();
        return n.contains("naqd") || n.contains("касса") || n.contains("kassa") || n.contains("налич") || n.contains("нақд");
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
