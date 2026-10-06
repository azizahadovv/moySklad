package uz.kassa.service.adesk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.config.AppProps;
import uz.kassa.domain.AdeskLink;
import uz.kassa.repo.AdeskLinkRepo;
import uz.kassa.service.adesk.AdeskClient.AdTx;
import uz.kassa.service.moysklad.MoySkladClient;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static uz.kassa.domain.AdeskLink.*;

/**
 * 📒 Adesk → MoySklad (docs/ADESK.md §5): Adesk'da QO'LDA kiritilgan (MoySklad'dan kelmagan) operatsiyalar.
 *
 * Standart holatda o'chiq ({@link AdeskConfig#reverse()}): bunday operatsiyalar faqat sanaladi va kunlik
 * solishtirishda «farq» sifatida ko'rsatiladi. Yoqilsa — har biri uchun MoySklad'da hujjat yaratiladi:
 * kassa hisobi → Приходный/Расходный ордер, bank/karta hisobi → Входящий/Исходящий платёж; kontragent bog'langan
 * bo'lmasa MoySklad'da yaratiladi; chiqim statyasi MoySklad'da bo'lmasa — xuddi shu nom bilan «Статья расходов»
 * yaratiladi (statyalar ikki tizimda bir xil bo'lsin). Keyin hujjat oddiy MoySklad → Adesk sinxroniga qo'shiladi.
 * O'tkazmalar (Adesk «перевод») MoySklad'ga yozilmaydi — faqat sanaladi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdeskReverseService {

    private final AdeskConfig cfg;
    private final MoySkladClient ms;
    private final AdeskLinkRepo repo;
    private final AppProps props;

    private static final JsonNodeFactory JN = JsonNodeFactory.instance;
    private static final DateTimeFormatter MS_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public void handle(AdeskRun r, List<AdTx> manual) {
        List<AdTx> txs = manual.stream().filter(t -> !t.transfer()).toList();
        r.inc("ad.manual", txs.size());
        r.inc("ad.manualTransfer", manual.size() - txs.size());
        if (manual.size() > txs.size())
            r.note("Adesk'da qo'lda kiritilgan o'tkazmalar: " + (manual.size() - txs.size()) + " ta — MoySklad'ga yozilmaydi, u yerda «Перемещение» bilan kiriting");
        if (txs.isEmpty()) return;
        if (!cfg.reverse()) {
            r.note("Adesk'da qo'lda kiritilgan operatsiyalar: " + txs.size() + " ta — MoySklad'da yo'q (Adesk → MoySklad o'chiq)");
            return;
        }
        Map<String, String> expenseByName = new HashMap<>();
        r.expenseItems.forEach((id, name) -> expenseByName.put(AdeskSyncService.norm(name), id));
        for (AdTx t : txs) {
            if (r.stopped()) return;
            r.progress = "Adesk → MoySklad #" + t.id();
            try {
                String msId = createInMs(r, t, expenseByName);
                r.inc("ad.toMs");
                log.info("Adesk #{} MoySklad'ga yozildi: {}", t.id(), msId);
            } catch (Exception e) {
                r.inc("ad.toMsError");
                r.note("Adesk #" + t.id() + " MoySklad'ga yozilmadi — " + e.getMessage());
                log.warn("Adesk #{} MoySklad'ga yozilmadi: {}", t.id(), e.getMessage());
            }
        }
    }

    private String createInMs(AdeskRun r, AdTx t, Map<String, String> expenseByName) {
        String key = t.accountId() == null ? null : r.accountByAd.get(t.accountId());
        if (key == null) throw new IllegalStateException("hisob MoySklad hisobi bilan bog'lanmagan");
        String orgId = key.substring(0, key.indexOf(':'));
        String accId = key.substring(key.indexOf(':') + 1);
        boolean cash = AdeskMsReader.CASH.equals(accId);
        String entity = t.income() ? (cash ? "cashin" : "paymentin") : (cash ? "cashout" : "paymentout");
        long sum = Math.abs(t.signedTiyin());
        if (sum <= 0) throw new IllegalStateException("summa nol");

        ObjectNode b = JN.objectNode();
        b.set("organization", meta("organization", "organization/" + orgId));
        if (!cash) b.set("organizationAccount", meta("account", "organization/" + orgId + "/accounts/" + accId));
        b.set("agent", agent(r, t, orgId));
        b.put("sum", sum);
        b.put("moment", ms.toMoscow(t.date().atTime(LocalTime.NOON)).format(MS_TIME));
        b.put("applicable", true);
        String cat = t.categoryName() == null ? "" : t.categoryName().trim();
        String desc = (t.description() == null ? "" : t.description().trim());
        desc = (desc.isEmpty() ? "" : desc + " ") + "[Adesk #" + t.id() + (t.income() && !cat.isEmpty() ? " · статья: " + cat : "") + "]";
        b.put("description", desc);
        if (!t.income()) b.set("expenseItem", meta("expenseitem", "expenseitem/" + expenseId(r, t, cat, expenseByName)));

        JsonNode res = ms.postEntity("entity/" + entity, b.toString());
        if (res == null) throw new IllegalStateException("MoySklad ruxsat bermadi (token huquqi)");
        String id = res.path("id").asText("");
        if (id.isEmpty()) throw new IllegalStateException("MoySklad javobida id yo'q");

        AdeskLink l = AdeskLink.builder().kind(MONEY).msKey(id).adeskId(t.id()).origin(FROM_AD).status(OK)
                .name("Adesk #" + t.id()).msType(entity).docDate(t.date()).sumTiyin(t.signedTiyin()).accountKey(key)
                .updatedAt(Instant.now()).build();
        l = repo.save(l);
        if (r.hasLinks(MONEY)) r.links(MONEY).put(id, l);
        return id;
    }

    /** Agent: bog'langan kontragent/xodim/tashkilot; bog'lanmagan Adesk kontragenti — MoySklad'da yaratiladi; yo'q — tashkilotning o'zi. */
    private ObjectNode agent(AdeskRun r, AdTx t, String orgId) {
        if (t.contractorId() == null) return meta("organization", "organization/" + orgId);
        for (String kind : List.of(CONTRACTOR, EMPLOYEE, ORGC)) {
            Optional<AdeskLink> l = repo.findByKindAndAdeskId(kind, t.contractorId()).stream()
                    .filter(x -> OK.equals(x.getStatus())).findFirst();
            if (l.isPresent()) {
                String type = switch (kind) { case EMPLOYEE -> "employee"; case ORGC -> "organization"; default -> "counterparty"; };
                return meta(type, type + "/" + l.get().getMsKey());
            }
        }
        String name = t.contractorName() == null || t.contractorName().isBlank() ? "Adesk контрагент #" + t.contractorId() : t.contractorName().trim();
        ObjectNode body = JN.objectNode();
        body.put("name", name);
        body.put("description", "Adesk'дан (#" + t.contractorId() + ")");
        JsonNode res = ms.postEntity("entity/counterparty", body.toString());
        String id = res == null ? "" : res.path("id").asText("");
        if (id.isEmpty()) throw new IllegalStateException("MoySklad'da kontragent yaratilmadi");
        AdeskLink l = repo.save(AdeskLink.builder().kind(CONTRACTOR).msKey(id).adeskId(t.contractorId()).name(name)
                .origin(FROM_AD).status(OK).updatedAt(Instant.now()).build());
        if (r.hasLinks(CONTRACTOR)) r.links(CONTRACTOR).put(id, l);
        r.inc("ad.ctToMs");
        return meta("counterparty", "counterparty/" + id);
    }

    /** Chiqim statyasi → MoySklad «Статья расходов» id (yo'q bo'lsa xuddi shu nom bilan yaratiladi). */
    private String expenseId(AdeskRun r, AdTx t, String cat, Map<String, String> byName) {
        String name = cat.isEmpty() ? "Прочие расходы" : cat;
        String id = byName.get(AdeskSyncService.norm(name));
        if (id != null) return id;
        ObjectNode body = JN.objectNode();
        body.put("name", name);
        JsonNode res = ms.postEntity("entity/expenseitem", body.toString());
        id = res == null ? "" : res.path("id").asText("");
        if (id.isEmpty()) throw new IllegalStateException("MoySklad'da statya yaratilmadi: " + name);
        byName.put(AdeskSyncService.norm(name), id);
        if (t.categoryId() != null && repo.findByKindAndMsKey(CATEGORY, "out:" + name).isEmpty())
            repo.save(AdeskLink.builder().kind(CATEGORY).msKey("out:" + name).adeskId(t.categoryId()).name(name)
                    .origin(FROM_AD).status(OK).updatedAt(Instant.now()).build());
        r.inc("ad.catToMs");
        r.note("MoySklad'da yangi chiqim statyasi yaratildi: " + name);
        return id;
    }

    private ObjectNode meta(String type, String path) {
        ObjectNode m = JN.objectNode();
        m.put("href", props.getMoysklad().getBaseUrl() + "/entity/" + path);
        m.put("type", type);
        m.put("mediaType", "application/json");
        ObjectNode o = JN.objectNode();
        o.set("meta", m);
        return o;
    }
}
