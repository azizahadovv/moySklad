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
    private static final DateTimeFormatter RU_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Bot o'zi yozgan operatsiya izohi: «… · MS Приходный ордер №07318» — bog'lanishi yo'qolgan bo'lsa ham MoySklad'ga qaytarilmaydi (dublikat). */
    private static final java.util.regex.Pattern BOT_MARK = java.util.regex.Pattern.compile("MS (Приходный ордер|Расходный ордер|Входящий платёж|Исходящий платёж|Приёмка|Отгрузка|Возврат[^№]*) №");

    public void handle(AdeskRun r, List<AdTx> manual) {
        manual = manual.stream().filter(t -> t.description() == null || !BOT_MARK.matcher(t.description()).find()).toList();
        List<AdTx> transfers = manual.stream().filter(AdTx::transfer).toList();
        List<AdTx> txs = manual.stream().filter(t -> !t.transfer()).toList();
        r.inc("ad.manual", txs.size());
        r.inc("ad.manualTransfer", transfers.size());
        if (txs.isEmpty() && transfers.isEmpty()) return;
        if (!cfg.reverse()) {
            r.note("Adesk'da qo'lda kiritilgan: " + txs.size() + " ta operatsiya, " + transfers.size() + " ta o'tkazma leg'i — MoySklad'da yo'q (Adesk → MoySklad o'chiq)");
            return;
        }
        Map<String, String> expenseByName = new HashMap<>();
        r.expenseItems.forEach((id, name) -> expenseByName.put(AdeskSyncService.norm(name), id));
        // Adesk «перевод» — ikki leg (chiqim va kirim): Adesk bergan juft id (pairedTransactionId) bo'yicha, u yo'q bo'lsa
        // sana va summa bo'yicha juftlanadi (bir kunda bir xil summali ikki perevod adashmasin), MoySklad'ga ikki hujjat bo'lib yoziladi
        List<AdTx> outs = new ArrayList<>(transfers.stream().filter(t -> !t.income()).toList());
        List<AdTx> ins = new ArrayList<>(transfers.stream().filter(AdTx::income).toList());
        for (AdTx o : outs) {
            if (r.stopped()) return;
            AdTx pair = pairOf(o, ins);
            if (pair == null) { fail(r, o, "perevodning kirim tomoni topilmadi (sana va summa bir xil bo'lishi kerak)"); continue; }
            ins.remove(pair);
            try {
                createTransferPair(r, o, pair, expenseByName);
                r.inc("ad.toMs", 2);
                r.inc("ad.transferToMs");
                cleared(o);
                cleared(pair);
            } catch (Exception e) {
                fail(r, o, e.getMessage());
                log.warn("Adesk o'tkazma #{} yozilmadi: {}", o.id(), e.getMessage());
            }
        }
        for (AdTx i : ins) fail(r, i, "perevodning chiqim tomoni topilmadi (sana va summa bir xil bo'lishi kerak)");
        for (AdTx t : txs) {
            if (r.stopped()) return;
            r.progress = "Adesk → MoySklad #" + t.id();
            try {
                String msId = createInMs(r, t, expenseByName);
                r.inc("ad.toMs");
                cleared(t);
                log.info("Adesk #{} MoySklad'ga yozildi: {}", t.id(), msId);
            } catch (Exception e) {
                fail(r, t, e.getMessage());
                log.warn("Adesk #{} MoySklad'ga yozilmadi: {}", t.id(), e.getMessage());
            }
        }
    }

    /** Perevod chiqim legining kirim jufti: avval Adesk juft id'si, keyin (juft id'si boshqa legga ishora qilmagan) sana + summa. */
    static AdTx pairOf(AdTx out, List<AdTx> ins) {
        if (out.pairedId() != null)
            for (AdTx i : ins) if (i.id() == out.pairedId()) return i;
        for (AdTx i : ins)
            if ((out.pairedId() == null || i.pairedId() == null) && (i.pairedId() == null || i.pairedId() == out.id())
                    && i.date().equals(out.date()) && i.amount().compareTo(out.amount()) == 0) return i;
        return null;
    }

    /**
     * MoySklad hujjat vaqti. Adesk faqat sanani beradi: bugungi operatsiya — yozilgan payt (MoySklad ro'yxatida o'z vaqtida,
     * qo'lda kiritilgandek ko'rinadi; 2026-10-09: 12:00 da turgani uchun «yozilmayapti» deb o'ylangan), o'tgan sana — kun o'rtasi.
     */
    private String msMoment(java.time.LocalDate date) {
        java.time.LocalDateTime at = date.equals(cfg.today())
                ? java.time.LocalDateTime.now(cfg.zone()).withNano(0)
                : date.atTime(LocalTime.NOON);
        return ms.toMoscow(at).format(MS_TIME);
    }

    /**
     * MoySklad izohi: «[Adesk #N] <shablon> · <Adesk'da yozilgan izoh> · <qo'shimcha>». Belgi doim boshida — hujjat Adesk'dan
     * kelganini sinxron shundan taniydi (Adesk'ka qayta ko'chirilmaydi). Foydalanuvchi izohi yo'qolmaydi (2026-10-09: perevodda
     * Adesk izohi «1» tushib qolgan edi).
     */
    static String msDesc(long adeskId, String head, String userText, String extra) {
        List<String> parts = new ArrayList<>();
        if (head != null && !head.isBlank()) parts.add(head);
        if (userText != null && !userText.isBlank()) parts.add(userText.trim().replaceAll("\\s+", " "));
        if (extra != null && !extra.isBlank()) parts.add(extra);
        String s = "[Adesk #" + adeskId + "]" + (parts.isEmpty() ? "" : " " + String.join(" · ", parts));
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }

    /** Yozilmagan operatsiya ⚠️ Xatolar ro'yxatiga (ADESK turi, «ad:<id>») — sababi bilan; keyingi urinishda yangilanadi. */
    private void fail(AdeskRun r, AdTx t, String msg) {
        r.inc("ad.toMsError");
        AdeskLink l = repo.findByKindAndMsKey(ADESK, "ad:" + t.id())
                .orElse(AdeskLink.builder().kind(ADESK).msKey("ad:" + t.id()).build());
        l.setAdeskId(t.id());
        l.setStatus(ERROR);
        l.setError(msg);
        l.setName((t.transfer() ? "Перевод" : t.income() ? "Кирим" : "Чиқим") + " · " + (t.accountName() == null ? "" : t.accountName()));
        l.setDocDate(t.date());
        l.setSumTiyin(t.signedTiyin());
        l.setUpdatedAt(Instant.now());
        repo.save(l);
    }

    /** Muvaffaqiyatli yozildi — oldingi xato yozuvi ro'yxatdan chiqadi. */
    private void cleared(AdTx t) {
        repo.findByKindAndMsKey(ADESK, "ad:" + t.id()).ifPresent(l -> {
            l.setStatus(SKIP);
            l.setError(null);
            l.setUpdatedAt(Instant.now());
            repo.save(l);
        });
    }

    private String createInMs(AdeskRun r, AdTx t, Map<String, String> expenseByName) {
        String key = t.accountId() == null ? null : r.accountByAd.get(t.accountId());
        if (key == null) throw new IllegalStateException("«" + t.accountName() + "» hisobi MoySklad hisobiga bog'lanmagan — ⚙️ Sozlamalar → 🔗 Hisoblarni bog'lash");
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
        b.put("moment", msMoment(t.date()));
        b.put("applicable", true);
        String cat = t.categoryName() == null ? "" : t.categoryName().trim();
        b.put("description", msDesc(t.id(), null, t.description(), t.income() && !cat.isEmpty() ? "статья: " + cat : null));
        ObjectNode st = stateMeta(entity, t.income() && AdeskSyncService.norm(cat).equals(AdeskSyncService.norm(cfg.catTransfer())));
        if (st != null) b.set("state", st);
        if (!t.income()) b.set("expenseItem", meta("expenseitem", "expenseitem/" + expenseId(r, t, cat, expenseByName)));
        else if (AdeskSyncService.norm(cat).equals(AdeskSyncService.norm(cfg.catTransfer()))) b.put("paymentPurpose", AdeskConfig.TRANSFER_PURPOSE + " (Adesk)");

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

    /**
     * Adesk perevodi → MoySklad'da ikki hujjat: chiqim (kassa — Расходный ордер, bank/karta — Исходящий платёж; statya «Перемещение»,
     * kontragent — qabul qiluvchi o'z firmamiz) va kirim (Приходный ордер / Входящий платёж; maqsad «Перемещение собственных средств …»,
     * kontragent — chiqim firmasi). Ikki leg ham bog'lanadi — keyingi sinxronda qayta Adesk'ga o'tmaydi.
     */
    private void createTransferPair(AdeskRun r, AdTx out, AdTx in, Map<String, String> expenseByName) {
        String ko = out.accountId() == null ? null : r.accountByAd.get(out.accountId());
        String ki = in.accountId() == null ? null : r.accountByAd.get(in.accountId());
        if (ko == null || ki == null)
            throw new IllegalStateException("«" + (ko == null ? out.accountName() : in.accountName())
                    + "» hisobi MoySklad hisobiga bog'lanmagan — ⚙️ Sozlamalar → 🔗 Hisoblarni bog'lash");
        String orgO = ko.substring(0, ko.indexOf(':')), orgI = ki.substring(0, ki.indexOf(':'));
        String nameO = r.orgs.stream().filter(o -> o.id().equals(orgO)).map(AdeskMsReader.MsOrg::name).findFirst().orElse("");
        long sum = Math.abs(out.signedTiyin());
        String moment = msMoment(out.date());
        String expId = expenseId(r, out, cfg.catTransfer(), expenseByName);
        // MoySklad'ning o'zida kiritilgan perevod kabi (2026-10-09, 02558/04400 bilan solishtirildi): ikkala tomonda bir xil
        // «Перемещение собственных средств <jo'natuvchi firma> от dd.MM.yyyy», qarshi tomon hisobi (agentAccount) aniq, vaqt bir xil
        String purpose = AdeskConfig.TRANSFER_PURPOSE + " " + nameO + " от " + out.date().format(RU_DATE);
        String idO = postMoney(r, out, ko, orgO, ki, sum, moment, msDesc(out.id(), "Перемещение", out.description(), null), purpose, expId, false);
        try {
            postMoney(r, in, ki, orgI, ko, sum, moment, msDesc(in.id(), "Перемещение", in.description(), null), purpose, null, true);
        } catch (RuntimeException e) {
            throw new IllegalStateException("chiqim yozildi (" + idO + "), kirim yozilmadi: " + e.getMessage() + " — MoySklad'da kirimni qo'lda kiriting", e);
        }
    }

    /** MoySklad hujjat statusi (odatdagidek): kirim/chiqim turiga qarab; topilmasa null (status qo'yilmaydi). */
    private final Map<String, Map<String, String>> stateIds = new java.util.concurrent.ConcurrentHashMap<>();

    private ObjectNode stateMeta(String entity, boolean transfer) {
        String name = switch (entity) {
            case "paymentin" -> transfer ? "Перечисления" : "Тулов килинди";
            case "paymentout" -> "Пул кучирилди";
            case "cashin" -> "Олинди";
            case "cashout" -> "Туланди 100%";
            default -> null;
        };
        if (name == null) return null;
        try {
            Map<String, String> ids = stateIds.computeIfAbsent(entity, e -> {
                Map<String, String> m = new HashMap<>();
                JsonNode md = ms.fetchJson("entity/" + e + "/metadata");
                if (md != null) for (JsonNode s : md.path("states")) m.put(s.path("name").asText(), s.path("id").asText());
                return m;
            });
            String id = ids.get(name);
            return id == null ? null : meta("state", entity + "/metadata/states/" + id);
        } catch (Exception e) { return null; }
    }

    /** agentKey — qarshi tomon hisobi («<orgId>:<acc|CASH>»): agent shu firma; to'lov hujjatida (kassa emas) agentAccount ham qo'yiladi. */
    private String postMoney(AdeskRun r, AdTx t, String key, String orgId, String agentKey, long sum, String moment,
                             String desc, String purpose, String expenseId, boolean income) {
        String accId = key.substring(key.indexOf(':') + 1);
        boolean cash = AdeskMsReader.CASH.equals(accId);
        String entity = income ? (cash ? "cashin" : "paymentin") : (cash ? "cashout" : "paymentout");
        String agentOrg = agentKey.substring(0, agentKey.indexOf(':')), agentAcc = agentKey.substring(agentKey.indexOf(':') + 1);
        ObjectNode b = JN.objectNode();
        b.set("organization", meta("organization", "organization/" + orgId));
        if (!cash) b.set("organizationAccount", meta("account", "organization/" + orgId + "/accounts/" + accId));
        b.set("agent", meta("organization", "organization/" + agentOrg));
        // MoySklad «Перемещение» tugmasidagidek: qarshi tomon bank hisobi; u kassa bo'lsa — hujjatning o'z hisobi (02588/07281)
        if (!cash) b.set("agentAccount", AdeskMsReader.CASH.equals(agentAcc)
                ? meta("account", "organization/" + orgId + "/accounts/" + accId)
                : meta("account", "organization/" + agentOrg + "/accounts/" + agentAcc));
        b.put("sum", sum);
        b.put("moment", moment);
        b.put("applicable", true);
        b.put("description", desc);
        if (purpose != null) b.put("paymentPurpose", purpose);
        ObjectNode st = stateMeta(entity, true);
        if (st != null) b.set("state", st);
        if (expenseId != null) b.set("expenseItem", meta("expenseitem", "expenseitem/" + expenseId));
        JsonNode res = ms.postEntity("entity/" + entity, b.toString());
        String id = res == null ? "" : res.path("id").asText("");
        if (id.isEmpty()) throw new IllegalStateException("MoySklad javobida id yo'q (token huquqi?)");
        AdeskLink l = repo.save(AdeskLink.builder().kind(MONEY).msKey(id).adeskId(t.id()).origin(FROM_AD).status(OK)
                .name("Adesk #" + t.id()).msType(entity).docDate(t.date()).sumTiyin(t.signedTiyin()).accountKey(key)
                .updatedAt(Instant.now()).build());
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
