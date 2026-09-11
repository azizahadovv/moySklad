package uz.kassa.service.ombor;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.domain.Kassa;
import uz.kassa.domain.OmborSinxron;
import uz.kassa.domain.OmborStore;
import uz.kassa.domain.OmborTovar;
import uz.kassa.repo.KassaRepo;
import uz.kassa.repo.OmborSinxronRepo;
import uz.kassa.repo.OmborStoreRepo;
import uz.kassa.repo.OmborTovarRepo;
import uz.kassa.service.moysklad.MoySkladClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 🏬 MoySklad → bot sinxroni, BITTA mexanizm (docs/OMBOR-TZ.md §4): har obyekt turi registry'dagi
 * {@link EntityDef} — endpoint, filtr, mapper. Yangi tur = yangi EntityDef, yangi metod emas.
 *   • STORE   — entity/store (+ entity/retailstore orqali kassa ↔ ombor bog'lanishi)
 *   • TOVAR   — entity/assortment (updated>= kursor; kursor yo'q — to'liq)
 *   • QOLDIQ  — report/stock/bystore + report/stock/all → ombor_korsatkich (kuniga bir, yoki qo'lda)
 * Bot MoySklad'ga hech narsa yozmaydi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OmborSyncService {

    public static final String E_STORE = "store", E_TOVAR = "assortment", E_QOLDIQ = "stock";

    private final MoySkladClient ms;
    private final OmborTovarRepo tovarRepo;
    private final OmborStoreRepo storeRepo;
    private final OmborSinxronRepo syncRepo;
    private final OmborMetrics metrics;
    private final KassaRepo kassaRepo;
    private final OmborConfig cfg;
    private final OmborDocSync docs;

    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    /** Registry elementi: nom, ish. */
    private record EntityDef(String entity, java.util.function.Function<OmborSinxron, Integer> run) {}

    private final List<EntityDef> registry = buildRegistry();

    private List<EntityDef> buildRegistry() {
        List<EntityDef> l = new ArrayList<>();
        l.add(new EntityDef(E_STORE, this::syncStores));
        l.add(new EntityDef(E_TOVAR, this::syncAssortment));
        for (String t : OmborDocSync.TYPES) l.add(new EntityDef(t, st -> docs.syncType(t, st, storeToKassa())));
        return l;
    }


    /* ==================== kirish nuqtalari ==================== */

    /** Har 10 daqiqa: omborlar + tovarlar (inkremental). */
    public void tick() {
        if (!cfg.enabled() || !lock.tryLock()) return;
        try { for (EntityDef d : registry) runOne(d); }
        finally { lock.unlock(); }
    }

    /** Tunlik (stock_time dan keyin kuniga bir) yoki qo'lda: qoldiqlar → ko'rsatkichlar. */
    public boolean stockTick(boolean force) {
        if (!cfg.enabled()) return false;
        LocalDate today = LocalDate.now(cfg.zone());
        if (!force) {
            if (java.time.LocalTime.now(cfg.zone()).isBefore(cfg.stockTime())) return false;
            if (today.toString().equals(cfg.get(OmborConfig.STOCK_DONE).orElse(""))) return false;
        }
        if (!lock.tryLock()) return false;
        try {
            String r = runOne(new EntityDef(E_QOLDIQ, s -> syncStock(today)));
            if (r.startsWith("⚠️")) return false;   // xato — keyingi tick qayta uradi
            cfg.set(OmborConfig.STOCK_DONE, today.toString());
            return true;
        } finally { lock.unlock(); }
    }

    /**
     * Hammasi ketma-ket (🔄 tugma). Natija — odam o'qiydigan matn: omborlar, tovarlar, hujjatlar (o'zgarganlar),
     * qoldiqlar; «0» — oxirgi sinxrondan beri MoySklad'da o'zgarish yo'q.
     */
    public String syncAll() {
        if (!lock.tryLock()) return "⏳ Sinxron allaqachon ketmoqda";
        try {
            Map<String, String> res = new LinkedHashMap<>();
            for (EntityDef d : registry) res.put(d.entity(), runOne(d));
            String stock = runOne(new EntityDef(E_QOLDIQ, s -> syncStock(LocalDate.now(cfg.zone()))));
            if (!stock.startsWith("⚠️")) cfg.set(OmborConfig.STOCK_DONE, LocalDate.now(cfg.zone()).toString());
            StringBuilder sb = new StringBuilder();
            List<String> errors = new ArrayList<>();
            sb.append("🏪 Omborlar: <b>").append(val(res.get(E_STORE), errors, "omborlar")).append("</b>\n");
            sb.append("🗂 Tovarlar: yangilangan <b>").append(val(res.get(E_TOVAR), errors, "tovarlar")).append("</b> (jami faol ")
              .append(tovarRepo.countByArchivedFalse()).append(")\n");
            List<String> changed = new ArrayList<>();
            int docTotal = 0;
            for (String t : OmborDocSync.TYPES) {
                String v = val(res.get(t), errors, t);
                int n = v.matches("\\d+") ? Integer.parseInt(v) : 0;
                if (n > 0) { changed.add(uz.kassa.domain.OmborHujjat.typeTitle(t) + " " + n); docTotal += n; }
            }
            sb.append("📄 Hujjatlar (o'zgargan): ").append(docTotal == 0 ? "o'zgarish yo'q" : "<b>" + docTotal + "</b> — " + String.join(", ", changed)).append("\n");
            long bound = kassaRepo.findByActiveTrueOrderByIdAsc().stream().filter(k -> !k.isCashless() && k.getMoyskladWarehouseId() != null).count();
            sb.append("📦 Qoldiqlar: <b>").append(val(stock, errors, "qoldiqlar")).append("</b> qator (").append(bound).append(" do'kon)\n");
            if (!errors.isEmpty()) sb.append("⚠️ Xato: ").append(String.join("; ", errors)).append("\n");
            sb.append("<i>0 — oxirgi sinxrondan beri MoySklad'da o'zgarish yo'q, avvalgi ma'lumot saqlanadi.</i>\n");
            return sb.toString();
        } finally { lock.unlock(); }
    }

    private static String val(String r, List<String> errors, String what) {
        if (r == null) return "0";
        if (r.startsWith("⚠️")) { errors.add(what + ": " + r.substring(2).trim()); return "xato"; }
        return r.replace(" ta", "").trim();
    }

    private String runOne(EntityDef d) {
        OmborSinxron st = syncRepo.findById(d.entity()).orElse(OmborSinxron.builder().entity(d.entity()).build());
        try {
            int n = d.run().apply(st);
            st.setLastOkAt(Instant.now());
            st.setLastError(null);
            st.setRowsN(n);
            syncRepo.save(st);
            return n + " ta";
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            st.setLastError(msg.length() > 500 ? msg.substring(0, 500) : msg);
            syncRepo.save(st);
            log.warn("Ombor sinxron {} xatosi: {}", d.entity(), msg);
            return "⚠️ " + msg;
        }
    }

    public List<OmborSinxron> status() { return syncRepo.findAll(); }

    /** 🤝 Hamkor do'konlar (kontragent tegi ombor.hamkor_tag) qarzi → HAMKOR_QARZ ko'rsatkichi (so'm, kassa 0, product = kontragent). */
    public int syncHamkorlar() { return hamkorIds().size(); }

    /** Hamkor kontragentlar ro'yxati + HAMKOR_QARZ ko'rsatkichi. */
    public List<String> hamkorIds() {
        String tag = cfg.hamkorTag();
        if (tag.isBlank()) return List.of();
        String norm = MoySkladClient.normAttr(tag);
        List<String> ids = new ArrayList<>();
        for (MoySkladClient.MsAgentFull a : ms.fetchAgentsAll()) {
            if (a.archived()) continue;
            boolean hit = false;
            for (String t : a.tags()) if (MoySkladClient.normAttr(t).equals(norm)) hit = true;
            if (!hit) continue;
            ids.add(a.id());
            uz.kassa.service.ombor.checks.KorsatkichChegaraChecker.hamkorNames.put(a.id(), a.name());
        }
        if (ids.isEmpty()) return ids;
        LocalDate d = LocalDate.now(cfg.zone());
        List<OmborMetrics.Row> rows = new ArrayList<>();
        for (var e : ms.fetchAgentBalancesSom(ids).entrySet())
            rows.add(new OmborMetrics.Row(d, 0, e.getKey(), "HAMKOR_QARZ", BigDecimal.valueOf(-e.getValue())));   // manfiy balans = bizga qarzdor
        metrics.clear(d, "HAMKOR_QARZ");
        metrics.upsert(rows);
        return ids;
    }


    /* ==================== STORE ==================== */

    private int syncStores(OmborSinxron st) {
        Map<String, OmborStore> known = new HashMap<>();
        for (OmborStore s : storeRepo.findAll()) known.put(s.getMsId(), s);
        int n = 0;
        for (JsonNode r : ms.listAll("entity/store?limit=100", 20)) {
            String id = r.path("id").asText("");
            if (id.isBlank()) continue;
            OmborStore s = known.getOrDefault(id, OmborStore.builder().msId(id).build());
            s.setName(r.path("name").asText(""));
            s.setArchived(r.path("archived").asBoolean(false));
            s.setSyncAt(Instant.now());
            known.put(id, s);
            n++;
        }
        // retailStore (savdo nuqtasi) → store: kassa.moysklad_store_id orqali avtomatik bog'lash
        Map<String, String> retailToStore = new HashMap<>();
        try {
            for (JsonNode r : ms.listAll("entity/retailstore?limit=100", 5))
                retailToStore.put(r.path("id").asText(""), ms.idOf(r.path("store")));
        } catch (Exception e) { log.warn("retailstore o'qilmadi: {}", e.getMessage()); }
        Set<String> taken = new HashSet<>();
        for (Kassa k : kassaRepo.findAll()) if (k.getMoyskladWarehouseId() != null) taken.add(k.getMoyskladWarehouseId());
        for (Kassa k : kassaRepo.findAll()) {
            String wh = k.getMoyskladWarehouseId();
            if ((wh == null || wh.isBlank()) && k.getMoyskladStoreId() != null && !k.getMoyskladStoreId().isBlank())
                wh = retailToStore.get(k.getMoyskladStoreId());   // savdo nuqtasi → ombor
            if (wh == null || wh.isBlank()) {
                // nom bo'yicha: «Отдел Зуфар» ↔ «Склад Зуфар» (2026-09-10: kassalarda retailStore bog'lanmagan)
                String kn = OmborTovar.norm(k.getName().replaceFirst("(?iu)^(отдел|otdel|kassa|касса)\\s*", ""));
                if (kn.length() >= 3) for (OmborStore s : known.values()) {
                    if (s.isArchived() || taken.contains(s.getMsId())) continue;
                    String sn = OmborTovar.norm(s.getName().replaceFirst("(?iu)^(склад|ombor|омбор)\\s*", ""));
                    if (sn.equals(kn)) { wh = s.getMsId(); break; }
                }
            }
            if (wh != null && !wh.isBlank() && !wh.equals(k.getMoyskladWarehouseId()) && !taken.contains(wh)) {
                k.setMoyskladWarehouseId(wh); kassaRepo.save(k); taken.add(wh);
                log.info("Ombor bog'landi: {} -> {}", k.getName(), known.containsKey(wh) ? known.get(wh).getName() : wh);
            }
            if (wh != null && known.containsKey(wh)) {
                OmborStore s = known.get(wh);
                if (s.getKassaId() == null) s.setKassaId(k.getId());
            }
        }
        storeRepo.saveAll(known.values());
        return n;
    }

    /** Ombor UUID → kassa id (bog'lanmagan — null). */
    public Map<String, Long> storeToKassa() {
        Map<String, Long> m = new HashMap<>();
        for (OmborStore s : storeRepo.findAll()) if (s.getKassaId() != null) m.put(s.getMsId(), s.getKassaId());
        return m;
    }

    public void bindStore(String storeMsId, Long kassaId) {
        storeRepo.findById(storeMsId).ifPresent(s -> {
            // bitta kassa — bitta ombor
            if (kassaId != null) for (OmborStore o : storeRepo.findAll())
                if (kassaId.equals(o.getKassaId()) && !o.getMsId().equals(storeMsId)) { o.setKassaId(null); storeRepo.save(o); }
            s.setKassaId(kassaId);
            storeRepo.save(s);
            kassaRepo.findById(kassaId == null ? -1L : kassaId).ifPresent(k -> { k.setMoyskladWarehouseId(storeMsId); kassaRepo.save(k); });
        });
    }


    /* ==================== TOVAR (assortment) ==================== */

    private int syncAssortment(OmborSinxron st) {
        LocalDateTime start = LocalDateTime.now(cfg.zone());
        String q = "entity/assortment?limit=100";
        boolean incremental = st.getCursorAt() != null;
        if (incremental)
            q += "&filter=" + URLEncoder.encode("updated>=" + ms.filterTime(st.getCursorAt().minusMinutes(30)), StandardCharsets.UTF_8);
        List<JsonNode> rows;
        try { rows = ms.listAll(q, 400); }
        catch (Exception e) {
            if (!incremental) throw e;
            log.warn("assortment inkremental o'qilmadi ({}), to'liq o'qiladi", e.getMessage());
            rows = ms.listAll("entity/assortment?limit=100", 400);
        }
        List<OmborTovar> batch = new ArrayList<>();
        for (JsonNode r : rows) {
            String id = r.path("id").asText("");
            if (id.isBlank()) continue;
            OmborTovar t = tovarRepo.findById(id).orElse(OmborTovar.builder().msId(id).build());
            t.setType(r.path("meta").path("type").asText("product"));
            t.setName(r.path("name").asText(""));
            t.setNameNorm(OmborTovar.norm(t.getName()));
            t.setArticle(r.path("article").asText(""));
            t.setCode(r.path("code").asText(""));
            t.setBarcode(firstBarcode(r));
            t.setFolderName(r.path("pathName").asText(""));
            t.setUom(r.path("uom").path("name").asText(""));
            t.setBuyPrice(r.path("buyPrice").path("value").asLong(0));
            long sale = 0;
            for (JsonNode p : r.path("salePrices")) { sale = p.path("value").asLong(0); if (sale > 0) break; }
            t.setSalePrice(sale);
            t.setMinBalance(BigDecimal.valueOf(r.path("minimumBalance").asDouble(0)));
            t.setArchived(r.path("archived").asBoolean(false));
            t.setMsUpdated(ms.dtOf(r, "updated"));
            t.setSyncAt(Instant.now());
            batch.add(t);
            if (batch.size() >= 200) { tovarRepo.saveAll(batch); batch.clear(); }
        }
        if (!batch.isEmpty()) tovarRepo.saveAll(batch);
        st.setCursorAt(start);
        return rows.size();
    }

    private static String firstBarcode(JsonNode r) {
        for (JsonNode b : r.path("barcodes")) {
            Iterator<String> it = b.fieldNames();
            if (it.hasNext()) { String v = b.path(it.next()).asText(""); if (!v.isBlank()) return v; }
        }
        return "";
    }


    /* ==================== QOLDIQ (report/stock) ==================== */

    private int syncStock(LocalDate day) {
        Map<String, Long> s2k = storeToKassa();
        List<OmborMetrics.Row> rows = new ArrayList<>();
        // do'kon kesimida
        for (JsonNode r : ms.listAll("report/stock/bystore?limit=1000", 100)) {
            String pid = ms.idOf(r);
            if (pid.isBlank()) continue;
            for (JsonNode sb : r.path("stockByStore")) {
                Long kassa = s2k.get(ms.idOf(sb));
                if (kassa == null) continue;
                add(rows, day, kassa, pid, OmborMetrics.QOLDIQ, sb.path("stock").asDouble(0));
                add(rows, day, kassa, pid, OmborMetrics.REZERV, sb.path("reserve").asDouble(0));
                add(rows, day, kassa, pid, OmborMetrics.YOLDA, sb.path("inTransit").asDouble(0));
            }
        }
        // kompaniya bo'yicha + harakatsiz kunlar
        for (JsonNode r : ms.listAll("report/stock/all?limit=1000", 100)) {
            String pid = ms.idOf(r);
            if (pid.isBlank()) continue;
            add(rows, day, 0, pid, OmborMetrics.QOLDIQ, r.path("stock").asDouble(0));
            add(rows, day, 0, pid, OmborMetrics.REZERV, r.path("reserve").asDouble(0));
            add(rows, day, 0, pid, OmborMetrics.YOLDA, r.path("inTransit").asDouble(0));
            add(rows, day, 0, pid, OmborMetrics.AYLANMA_KUN, r.path("stockDays").asDouble(0));
        }
        for (String c : List.of(OmborMetrics.QOLDIQ, OmborMetrics.REZERV, OmborMetrics.YOLDA, OmborMetrics.AYLANMA_KUN)) metrics.clear(day, c);
        int n = metrics.upsert(rows);
        metrics.prune(400);
        return n;
    }

    /** Nol qiymat yozilmaydi (hajm) — QOLDIQ bundan mustasno emas: 0 qoldiq ham ma'lumot, lekin faqat kompaniya darajasida. */
    private static void add(List<OmborMetrics.Row> rows, LocalDate d, long kassa, String pid, String code, double v) {
        if (v == 0 && !(kassa == 0 && code.equals(OmborMetrics.QOLDIQ))) return;
        rows.add(new OmborMetrics.Row(d, kassa, pid, code, BigDecimal.valueOf(v)));
    }
}
