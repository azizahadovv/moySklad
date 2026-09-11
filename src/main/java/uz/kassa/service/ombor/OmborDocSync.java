package uz.kassa.service.ombor;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.OmborHujjat;
import uz.kassa.domain.OmborPozitsiya;
import uz.kassa.domain.OmborSinxron;
import uz.kassa.repo.OmborHujjatRepo;
import uz.kassa.repo.OmborPozitsiyaRepo;
import uz.kassa.service.control.EmployeeLinkService;
import uz.kassa.service.moysklad.MoySkladClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 🏬 Hujjatlar sinxroni — bitta mapper hamma tur uchun (docs/OMBOR-TZ.md §4).
 * Tur ro'yxati {@link #TYPES}; birinchi yuklash moment>= (ombor.docs_days), keyin updated>= kursor.
 * Pozitsiyalar expand bilan (100 tagacha), ko'p bo'lsa /positions sahifalab. Bog'lanishlar (links):
 * purchaseorder.supplies/invoicesIn, invoicein.supplies, loss/enter.inventory, salesreturn.demand.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OmborDocSync {

    public static final List<String> TYPES = List.of("move", "supply", "purchaseorder", "invoicein", "purchasereturn",
            "inventory", "loss", "enter", "salesreturn", "retailsalesreturn");

    private final MoySkladClient ms;
    private final OmborHujjatRepo repo;
    private final OmborPozitsiyaRepo posRepo;
    private final OmborConfig cfg;
    private final EmployeeLinkService link;

    /** Bitta tur: qaytadi — o'qilgan hujjatlar soni. storeToKassa — ombor UUID → kassa. */
    public int syncType(String type, OmborSinxron st, Map<String, Long> storeToKassa) {
        LocalDateTime start = LocalDateTime.now(cfg.zone());
        String filter = st.getCursorAt() == null
                ? "moment>=" + ms.filterTime(start.minusDays(cfg.docsDays()))
                : "updated>=" + ms.filterTime(st.getCursorAt().minusMinutes(30));
        String q = "entity/" + type + "?limit=100&expand=positions,agent,owner&order=updated,asc&filter="
                + URLEncoder.encode(filter, StandardCharsets.UTF_8);
        int n = 0;
        for (JsonNode r : ms.listAll(q, 300)) {
            try { upsert(type, r, storeToKassa); n++; }
            catch (Exception e) { log.warn("Ombor hujjat {} {} yozilmadi: {}", type, r.path("name").asText(""), e.getMessage()); }
        }
        st.setCursorAt(start);
        return n;
    }

    private void upsert(String type, JsonNode r, Map<String, Long> s2k) {
        String id = r.path("id").asText("");
        if (id.isBlank()) return;
        OmborHujjat h = repo.findByMsId(id).orElse(OmborHujjat.builder().msId(id).type(type).build());
        h.setType(type);
        h.setDocNo(r.path("name").asText(""));
        h.setMoment(ms.dtOf(r, "moment"));
        h.setMsCreated(ms.dtOf(r, "created"));
        h.setMsUpdated(ms.dtOf(r, "updated"));
        boolean move = type.equals("move");
        h.setStoreMsId(ms.idOf(move ? r.path("sourceStore") : r.path("store")));
        h.setTargetStoreMsId(move ? ms.idOf(r.path("targetStore")) : null);
        h.setKassaId(s2k.get(h.getStoreMsId()));
        h.setTargetKassaId(h.getTargetStoreMsId() == null ? null : s2k.get(h.getTargetStoreMsId()));
        JsonNode agent = r.path("agent");
        h.setAgentMsId(agent.isMissingNode() ? null : ms.idOf(agent));
        h.setAgentName(agent.path("name").asText(""));
        h.setState(r.path("state").path("name").asText(""));
        h.setApplicable(r.has("applicable") && !r.path("applicable").isNull() ? r.path("applicable").asBoolean(true) : null);
        h.setSum(r.path("sum").asLong(0));
        h.setPayedSum(r.path("payedSum").asLong(0));
        JsonNode owner = r.path("owner");
        h.setOwnerUid(owner.path("uid").asText(""));
        h.setOwnerName(owner.path("name").asText(""));
        if (!h.getOwnerUid().isBlank() || !owner.isMissingNode()) {
            Optional<AppUser> u = link.resolve(ms.idOf(owner), h.getOwnerUid(), h.getOwnerName(), "");
            h.setOwnerUserId(u.map(AppUser::getId).orElse(null));
        }
        // bog'lanishlar
        List<String> links = new ArrayList<>();
        for (String f : List.of("supplies", "invoicesIn", "purchaseOrders", "enters", "losses", "demands", "customerOrders"))
            for (JsonNode x : r.path(f)) { String l = ms.idOf(x); if (!l.isBlank()) links.add(l); }
        for (String f : List.of("inventory", "demand", "purchaseOrder", "customerOrder", "invoiceIn"))
            if (r.has(f)) { String l = ms.idOf(r.path(f)); if (!l.isBlank()) links.add(l); }
        h.setLinks(String.join(",", links));
        h.setDeleted(false);
        h.setSyncAt(Instant.now());
        final OmborHujjat saved = repo.save(h);
        // pozitsiyalar
        JsonNode ps = r.path("positions");
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode p : ps.path("rows")) rows.add(p);
        int size = ps.path("meta").path("size").asInt(rows.size());
        if (size > rows.size()) rows = ms.listAll("entity/" + type + "/" + id + "/positions?limit=1000", 20);
        Map<String, OmborPozitsiya> agg = new LinkedHashMap<>();
        int corrections = 0;
        for (JsonNode p : rows) {
            String pid = ms.idOf(p.path("assortment"));
            if (pid.isBlank()) continue;
            OmborPozitsiya x = agg.computeIfAbsent(pid, k -> OmborPozitsiya.builder().hujjatId(saved.getId()).productMsId(pid).build());
            x.setQty(x.getQty().add(BigDecimal.valueOf(p.path("quantity").asDouble(0))));
            x.setPrice(p.path("price").asLong(0));
            if (p.has("calculatedQuantity")) {
                BigDecimal c = BigDecimal.valueOf(p.path("calculatedQuantity").asDouble(0));
                x.setCalculatedQty(x.getCalculatedQty() == null ? c : x.getCalculatedQty().add(c));
            }
        }
        for (OmborPozitsiya x : agg.values())
            if (x.getCalculatedQty() != null && x.getCalculatedQty().compareTo(x.getQty()) != 0) corrections++;
        posRepo.deleteByHujjat(saved.getId());
        if (!agg.isEmpty()) posRepo.saveAll(agg.values());
        saved.setPositionsN(agg.size());
        saved.setCorrectionsN(corrections);
        repo.save(saved);
    }

    /** Hujjat MoySklad'da hali bormi (o'chirilgan bo'lsa deleted=true). */
    public boolean stillExists(OmborHujjat h) {
        MoySkladClient.DocStatus st = ms.fetchDocStatus(h.getType(), h.getMsId());
        if (st == MoySkladClient.DocStatus.DELETED) { h.setDeleted(true); repo.save(h); return false; }
        if (st == MoySkladClient.DocStatus.OK && Boolean.FALSE.equals(h.getApplicable())) { h.setApplicable(true); repo.save(h); }
        return true;
    }
}
