package uz.kassa.service.adesk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import uz.kassa.config.AppProps;
import uz.kassa.domain.AdeskLink;
import uz.kassa.repo.AdeskLinkRepo;
import uz.kassa.repo.SettingRepo;
import uz.kassa.service.SettingsService;
import uz.kassa.service.moysklad.MoySkladClient;
import uz.kassa.service.moysklad.MoySkladHttp;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 📒 Sentabr yuklashining to'liq mashqi: HAQIQIY MoySklad (faqat o'qish) → SOXTA Adesk server (shu test ichida,
 * hujjatlangan API shaklida). Tekshiriladi: to'liq yurish, solishtirish «тенг», ikkinchi yurishda dublikat yo'q,
 * Adesk'da o'chirilgan operatsiya tiklanadi, qo'lda kiritilgan operatsiya farq sifatida ko'rinadi.
 * Faqat ADESK_E2E=1 va MOYSKLAD_TOKEN berilganda ishlaydi.
 */
@EnabledIfEnvironmentVariable(named = "ADESK_E2E", matches = "1")
class AdeskEndToEndLiveTest {

    static final ObjectMapper OM = new ObjectMapper();
    static final DateTimeFormatter RU = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /* ==================== soxta Adesk ==================== */

    static class FakeAdesk {
        final AtomicLong seq = new AtomicLong(1000);
        final Map<Long, ObjectNode> legal = new ConcurrentHashMap<>(), accounts = new ConcurrentHashMap<>(), cats = new ConcurrentHashMap<>(),
                contractors = new ConcurrentHashMap<>(), products = new ConcurrentHashMap<>(), txs = new ConcurrentHashMap<>(), commits = new ConcurrentHashMap<>();
        final AtomicLong calls = new AtomicLong();
        HttpServer server;

        int start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
            server.start();
            return server.getAddress().getPort();
        }

        void handle(HttpExchange ex) throws IOException {
            calls.incrementAndGet();
            String path = ex.getRequestURI().getPath();
            Map<String, String> q = parse(ex.getRequestURI().getRawQuery());
            byte[] body = ex.getRequestBody().readAllBytes();
            String ct = Optional.ofNullable(ex.getRequestHeaders().getFirst("Content-Type")).orElse("");
            Map<String, String> f = ct.contains("x-www-form-urlencoded") ? parse(new String(body, StandardCharsets.UTF_8)) : Map.of();
            ObjectNode out;
            try {
                out = route(ex.getRequestMethod(), path, q, f, ct.contains("json") ? OM.readTree(body) : null);
            } catch (Exception e) {
                out = OM.createObjectNode().put("success", false).put("message", "fake: " + e.getMessage());
            }
            byte[] b = OM.writeValueAsBytes(out);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        }

        synchronized ObjectNode route(String m, String path, Map<String, String> q, Map<String, String> f, JsonNode json) {
            ObjectNode ok = OM.createObjectNode().put("success", true);
            if (path.equals("/v1/legal-entities")) return page(ok, "legalEntities", legal.values(), q);
            if (path.equals("/v1/legal-entity")) { long id = seq.incrementAndGet(); ObjectNode o = OM.createObjectNode().put("id", id).put("name", req(f, "name")).put("inn", f.getOrDefault("inn", "")); legal.put(id, o); return ok.set("legalEntity", o); }
            if (path.equals("/v1/bank-accounts")) {
                List<ObjectNode> list = new ArrayList<>();
                for (ObjectNode a : accounts.values()) {
                    BigDecimal amt = new BigDecimal(a.path("initialAmount").asText("0"));
                    for (ObjectNode t : txs.values()) if (t.path("bankAccount").path("id").asLong() == a.path("id").asLong())
                        amt = t.path("type").asInt() == 1 ? amt.add(new BigDecimal(t.path("amount").asText())) : amt.subtract(new BigDecimal(t.path("amount").asText()));
                    list.add(a.deepCopy().put("amount", amt.toPlainString()));
                }
                return page(ok, "bankAccounts", list, q);
            }
            if (path.equals("/v1/bank-account")) {
                long le = Long.parseLong(req(f, "legal_entity"));
                if (!legal.containsKey(le)) throw new IllegalStateException("legal_entity yo'q");
                long id = seq.incrementAndGet();
                ObjectNode o = OM.createObjectNode().put("id", id).put("name", req(f, "name")).put("currency", req(f, "currency"))
                        .put("type", "1".equals(f.get("type")) ? "Cash" : "Bank").put("status", "open")
                        .put("initialAmount", f.getOrDefault("initial_amount", "0"))
                        .put("initialAmountDate", f.containsKey("initial_amount_date") ? LocalDate.parse(f.get("initial_amount_date")).format(RU) : "");
                o.putObject("legalEntity").put("id", le);
                accounts.put(id, o);
                return ok.set("bankAccount", o);
            }
            if (path.startsWith("/v1/bank-account/")) {
                ObjectNode o = accounts.get(Long.parseLong(path.substring(17)));
                o.put("initialAmount", f.get("initial_amount")).put("initialAmountDate", LocalDate.parse(f.get("initial_amount_date")).format(RU));
                return ok;
            }
            if (path.equals("/v1/transactions/categories")) return ok.set("categories", OM.valueToTree(cats.values()));
            if (path.equals("/v1/transactions/category")) {
                long id = seq.incrementAndGet();
                ObjectNode o = OM.createObjectNode().put("id", id).put("name", req(f, "name")).put("type", Integer.parseInt(req(f, "type")))
                        .put("isOwnerTransfer", Boolean.parseBoolean(f.get("is_owner_transfer"))).put("isArchived", false);
                cats.put(id, o);
                return ok.set("category", o);
            }
            if (path.equals("/v1/contractors")) return page(ok, "contractors", contractors.values(), q);
            if (path.equals("/v1/contractor")) { long id = seq.incrementAndGet(); ObjectNode o = OM.createObjectNode().put("id", id).put("name", req(f, "name")).put("phoneNumber", f.getOrDefault("phone_number", "")); contractors.put(id, o); return ok.set("contractor", o); }
            if (path.startsWith("/v1/contractor/")) { contractors.get(Long.parseLong(path.substring(15))).put("name", req(f, "name")); return ok; }
            if (path.equals("/v1/warehouse/units")) return ok.set("units", OM.createArrayNode().add(OM.createObjectNode().put("id", 1).put("name", "Штука").put("symbol", "шт")));
            if (path.equals("/v1/warehouse/products")) return page(ok, "products", products.values(), q);
            if (path.equals("/v1/warehouse/product")) {
                if ("1".equals(f.get("type")) && !f.containsKey("unit") && !f.containsKey("unit_name")) throw new IllegalStateException("unit kerak");
                if ("true".equals(f.get("with_initial_batch")) && !legal.containsKey(Long.parseLong(req(f, "initial_batch_legal_entity")))) throw new IllegalStateException("partiya LE yo'q");
                long id = seq.incrementAndGet();
                ObjectNode o = OM.createObjectNode().put("id", id).put("name", req(f, "name")).put("sku", f.getOrDefault("sku", "")).put("type", Integer.parseInt(req(f, "type")))
                        .put("batchQty", f.getOrDefault("initial_batch_quantity", "0"));
                products.put(id, o);
                return ok.set("product", o);
            }
            if (path.startsWith("/v1/warehouse/product/")) { products.get(Long.parseLong(path.substring(22))).put("name", req(f, "name")); return ok; }
            if (path.equals("/v2/transactions/create")) {
                ArrayNode res = OM.createArrayNode();
                for (JsonNode t : json.path("transactions")) {
                    long acc = t.path("bankAccountId").asLong();
                    if (!accounts.containsKey(acc)) throw new IllegalStateException("bankAccountId yo'q");
                    if (new BigDecimal(t.path("amount").asText()).signum() <= 0) throw new IllegalStateException("amount");
                    if (t.hasNonNull("categoryId") && !cats.containsKey(t.path("categoryId").asLong())) throw new IllegalStateException("categoryId");
                    if (t.hasNonNull("contractorId") && !contractors.containsKey(t.path("contractorId").asLong())) throw new IllegalStateException("contractorId");
                    long id = seq.incrementAndGet();
                    txs.put(id, txOf(id, t));
                    res.add(OM.createObjectNode().put("id", id).put("importedId", t.path("importedId").asText("")));
                }
                ObjectNode r = OM.createObjectNode().put("success", true);
                r.putObject("data").set("transactions", res);
                return r;
            }
            if (path.equals("/v2/transactions/update")) {
                for (JsonNode t : json.path("transactions")) {
                    ObjectNode cur = txs.get(t.path("id").asLong());
                    if (cur == null) throw new IllegalStateException("tx yo'q");
                    ObjectNode merged = OM.createObjectNode();
                    merged.put("type", cur.path("type").asInt() == 1 ? "income" : "outcome");
                    merged.put("bankAccountId", t.has("bankAccountId") ? t.path("bankAccountId").asLong() : cur.path("bankAccount").path("id").asLong());
                    merged.put("amount", t.has("amount") ? t.path("amount").asText() : cur.path("amount").asText());
                    merged.put("date", t.has("date") ? t.path("date").asText() : LocalDate.parse(cur.path("date").asText(), RU).toString());
                    merged.set("categoryId", t.has("categoryId") ? t.get("categoryId") : cur.path("category").path("id"));
                    merged.set("contractorId", t.has("contractorId") ? t.get("contractorId") : cur.path("contractor").path("id"));
                    merged.put("description", t.has("description") ? t.path("description").asText() : cur.path("description").asText());
                    txs.put(t.path("id").asLong(), txOf(t.path("id").asLong(), merged));
                }
                return ok;
            }
            if (path.equals("/v2/transactions/remove")) { for (JsonNode id : json.path("transactionsIds")) txs.remove(id.asLong()); return ok; }
            if (path.equals("/v1/transactions")) {
                LocalDate a = LocalDate.parse(q.get("range_start")), b = LocalDate.parse(q.get("range_end"));
                List<ObjectNode> l = txs.values().stream().filter(t -> { LocalDate d = LocalDate.parse(t.path("date").asText(), RU); return !d.isBefore(a) && !d.isAfter(b); })
                        .sorted(Comparator.comparingLong(t -> t.path("id").asLong())).collect(Collectors.toList());
                return page(ok, "transactions", l, q);
            }
            if (path.equals("/v1/commitment")) {
                if (!contractors.containsKey(Long.parseLong(req(f, "contractor")))) throw new IllegalStateException("contractor yo'q");
                if (!legal.containsKey(Long.parseLong(req(f, "legal_entity")))) throw new IllegalStateException("legal_entity yo'q");
                for (int i = 0; f.containsKey("product-" + i + "-product_id"); i++)
                    if (!products.containsKey(Long.parseLong(f.get("product-" + i + "-product_id")))) throw new IllegalStateException("product yo'q");
                long id = seq.incrementAndGet();
                ObjectNode o = OM.createObjectNode().put("id", id).put("type", "in".equals(req(f, "type")) ? 1 : 2).put("amount", req(f, "amount"))
                        .put("date", LocalDate.parse(req(f, "date")).format(RU));
                o.putObject("contractor").put("id", Long.parseLong(f.get("contractor")));
                commits.put(id, o);
                return ok.set("commitment", o);
            }
            if (path.startsWith("/v1/commitment/") && path.endsWith("/remove")) { commits.remove(Long.parseLong(path.substring(15, path.length() - 7))); return ok; }
            if (path.equals("/v1/commitments")) {
                LocalDate a = LocalDate.parse(f.get("range_start")), b = LocalDate.parse(f.get("range_end"));
                List<ObjectNode> l = commits.values().stream().filter(t -> { LocalDate d = LocalDate.parse(t.path("date").asText(), RU); return !d.isBefore(a) && !d.isAfter(b); })
                        .sorted(Comparator.comparingLong(t -> t.path("id").asLong())).collect(Collectors.toList());
                return page(ok, "commitments", l, f);
            }
            throw new IllegalStateException("noma'lum yo'l " + m + " " + path);
        }

        ObjectNode txOf(long id, JsonNode t) {
            ObjectNode o = OM.createObjectNode().put("id", id).put("type", "income".equals(t.path("type").asText()) ? 1 : 2)
                    .put("amount", t.path("amount").asText()).put("date", LocalDate.parse(t.path("date").asText()).format(RU))
                    .put("dateIso", t.path("date").asText().replace('-', '.')).put("description", t.path("description").asText(""))
                    .put("isTransfer", false).put("isPlanned", false);
            o.putObject("bankAccount").put("id", t.path("bankAccountId").asLong());
            if (t.hasNonNull("categoryId") && t.path("categoryId").asLong() > 0) o.putObject("category").put("id", t.path("categoryId").asLong())
                    .put("name", cats.get(t.path("categoryId").asLong()).path("name").asText());
            if (t.hasNonNull("contractorId") && t.path("contractorId").asLong() > 0) o.putObject("contractor").put("id", t.path("contractorId").asLong());
            return o;
        }

        static ObjectNode page(ObjectNode ok, String field, Collection<ObjectNode> all, Map<String, String> q) {
            List<ObjectNode> l = new ArrayList<>(all);
            l.sort(Comparator.comparingLong(x -> x.path("id").asLong()));
            int s = Integer.parseInt(q.getOrDefault("start", "0")), n = Integer.parseInt(q.getOrDefault("length", "100000"));
            ok.set(field, OM.valueToTree(l.subList(Math.min(s, l.size()), Math.min(l.size(), s + n))));
            ok.put("recordsTotal", l.size()).put("recordsFiltered", l.size());
            return ok;
        }

        static String req(Map<String, String> f, String k) {
            String v = f.get(k);
            if (v == null || v.isBlank()) throw new IllegalStateException(k + ": Обязательное поле.");
            return v;
        }

        static Map<String, String> parse(String s) {
            Map<String, String> m = new LinkedHashMap<>();
            if (s == null || s.isBlank()) return m;
            for (String p : s.split("&")) {
                int i = p.indexOf('=');
                m.put(URLDecoder.decode(i < 0 ? p : p.substring(0, i), StandardCharsets.UTF_8), i < 0 ? "" : URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8));
            }
            return m;
        }
    }

    /* ==================== xotiradagi adesk_link ==================== */

    static AdeskLinkRepo memRepo() {
        Map<Long, AdeskLink> db = new ConcurrentHashMap<>();
        AtomicLong ids = new AtomicLong();
        AdeskLinkRepo r = mock(AdeskLinkRepo.class);
        when(r.save(any())).thenAnswer(i -> { AdeskLink l = i.getArgument(0); if (l.getId() == null) l.setId(ids.incrementAndGet()); db.put(l.getId(), l); return l; });
        when(r.findByKind(anyString())).thenAnswer(i -> db.values().stream().filter(l -> l.getKind().equals(i.getArgument(0))).collect(Collectors.toList()));
        when(r.findByKindAndMsKey(anyString(), anyString())).thenAnswer(i -> db.values().stream().filter(l -> l.getKind().equals(i.getArgument(0)) && l.getMsKey().equals(i.getArgument(1))).findFirst());
        when(r.findByKindAndAdeskId(anyString(), any())).thenAnswer(i -> db.values().stream().filter(l -> l.getKind().equals(i.getArgument(0)) && Objects.equals(l.getAdeskId(), i.getArgument(1))).collect(Collectors.toList()));
        when(r.countByKindAndStatus(anyString(), anyString())).thenAnswer(i -> db.values().stream().filter(l -> l.getKind().equals(i.getArgument(0)) && l.getStatus().equals(i.getArgument(1))).count());
        when(r.findByStatusOrderByUpdatedAtDesc(anyString())).thenAnswer(i -> db.values().stream().filter(l -> l.getStatus().equals(i.getArgument(0))).collect(Collectors.toList()));
        return r;
    }

    @Test
    void septemberRehearsal() throws Exception {
        FakeAdesk fake = new FakeAdesk();
        int port = fake.start();
        try {
            AppProps props = new AppProps();
            props.getMoysklad().setToken(System.getenv("MOYSKLAD_TOKEN"));
            MoySkladClient ms = new MoySkladClient(props, new MoySkladHttp(props, new SettingsService(mock(SettingRepo.class))));
            AdeskMsReader msr = new AdeskMsReader(ms);

            AdeskConfig cfg = mock(AdeskConfig.class);
            when(cfg.zone()).thenReturn(ZoneId.of("Asia/Tashkent"));
            when(cfg.baseUrl()).thenReturn("http://127.0.0.1:" + port);
            when(cfg.token()).thenReturn("test");
            when(cfg.hasToken()).thenReturn(true);
            when(cfg.rps()).thenReturn(1000);
            when(cfg.start()).thenReturn(LocalDate.of(2026, 9, 1));
            when(cfg.end()).thenReturn(LocalDate.of(2026, 9, 30));
            when(cfg.effectiveEnd()).thenReturn(LocalDate.of(2026, 9, 30));
            when(cfg.today()).thenReturn(LocalDate.now(ZoneId.of("Asia/Tashkent")));
            when(cfg.openingDate()).thenReturn(LocalDate.of(2026, 8, 31));
            when(cfg.currency()).thenReturn("UZS");
            when(cfg.catIncome()).thenReturn("Выручка");
            when(cfg.catTransfer()).thenReturn("Перемещение");
            when(cfg.stockOrg()).thenReturn("");
            when(cfg.project()).thenReturn("-");
            when(cfg.get(anyString())).thenReturn(Optional.empty());

            AdeskHttp http = new AdeskHttp(cfg);
            AdeskClient ad = new AdeskClient(http);
            AdeskLinkRepo repo = memRepo();
            AdeskReverseService reverse = new AdeskReverseService(cfg, ms, repo, props);
            AdeskSyncService sync = new AdeskSyncService(cfg, ad, msr, repo, reverse);
            AdeskCheckService check = new AdeskCheckService(cfg, ad, msr, repo);
            AdeskRunner runner = new AdeskRunner(cfg, sync, check, null, null, null, null);

            long t0 = System.currentTimeMillis();
            AdeskRun r1 = new AdeskRun(true, LocalDateTime.now(ZoneId.of("Asia/Tashkent")), new AtomicBoolean(false));
            sync.run(r1);
            System.out.println("=== 1-yurish (" + (System.currentTimeMillis() - t0) / 1000 + " s, Adesk so'rovlari " + fake.calls.get() + ")\n" + runner.summary(r1));
            assertNull(r1.fatal, r1.fatal);
            int txAfter1 = fake.txs.size(), cmAfter1 = fake.commits.size(), ctAfter1 = fake.contractors.size(), prAfter1 = fake.products.size();
            System.out.printf("Adesk: LE=%d hisob=%d statya=%d kontragent=%d tovar=%d (partiyali %d) operatsiya=%d majburiyat=%d%n",
                    fake.legal.size(), fake.accounts.size(), fake.cats.size(), ctAfter1, prAfter1,
                    fake.products.values().stream().filter(p -> new BigDecimal(p.path("batchQty").asText("0")).signum() > 0).count(), txAfter1, cmAfter1);

            AdeskCheckService.Result c1 = check.check();
            System.out.println("=== Solishtirish:\n" + c1.html().replaceAll("<[^>]+>", ""));
            List<Object[]> errRows = c1.sheets().get(3).rows();
            errRows.stream().limit(10).forEach(x -> System.out.println("XATO: " + Arrays.toString(x)));

            // 2-yurish: hech narsa o'zgarmagan — dublikat bo'lmasligi kerak
            AdeskRun r2 = new AdeskRun(false, LocalDateTime.now(ZoneId.of("Asia/Tashkent")), new AtomicBoolean(false));
            sync.run(r2);
            System.out.println("=== 2-yurish:\n" + runner.summary(r2));
            assertEquals(txAfter1, fake.txs.size(), "operatsiyalar dublikatsiz");
            assertEquals(cmAfter1, fake.commits.size(), "majburiyatlar dublikatsiz");
            // MoySklad jonli: yurishlar orasida xodimlar yangi kontragent/tovar qo'shishi mumkin — faqat ular qo'shiladi
            assertEquals(ctAfter1 + r2.get("ct.created"), fake.contractors.size());
            assertEquals(prAfter1 + r2.get("pr.created"), fake.products.size());
            assertEquals(0, r2.get("ct.linked") + r2.get("pr.linked"), "qayta bog'lash (dublikat belgisi) yo'q");

            // Adesk'da bitta operatsiya o'chirildi, bittasining summasi o'zgartirildi, bitta qo'lda kiritildi
            Iterator<Long> it = new TreeSet<>(fake.txs.keySet()).iterator();
            long del = it.next(), chg = it.next();
            fake.txs.remove(del);
            fake.txs.get(chg).put("amount", "1.00");
            long acc = fake.accounts.keySet().iterator().next();
            ObjectNode manual = OM.createObjectNode().put("type", "outcome").put("bankAccountId", acc).put("amount", "5000.00").put("date", "2026-09-15").put("description", "qo'lda");
            fake.txs.put(999_999L, fake.txOf(999_999L, manual));
            AdeskRun r3 = new AdeskRun(true, LocalDateTime.now(ZoneId.of("Asia/Tashkent")), new AtomicBoolean(false));
            sync.run(r3);
            System.out.println("=== 3-yurish (to'liq):\n" + runner.summary(r3));
            assertEquals(1, r3.get("tx.restored"));
            assertEquals(1, r3.get("tx.fixed"));
            assertEquals(1, r3.get("ad.manual"));
            assertEquals(txAfter1 + 1, fake.txs.size());
            AdeskCheckService.Result c3 = check.check();
            System.out.println("=== Solishtirish 2:\n" + c3.html().replaceAll("<[^>]+>", ""));
            assertTrue(c3.html().contains("қўлда киритилган"));
        } finally {
            fake.server.stop(0);
        }
    }
}
