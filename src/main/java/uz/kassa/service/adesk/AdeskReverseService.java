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
 * yaratiladi (statyalar ikki tizimda bir xil bo'lsin). Adesk «перевод» — MoySklad'da chiqim + kirim jufti.
 * Adesk'da keyin tahrirlansa — MoySklad hujjati ham o'zgartiriladi, o'chirilsa — MoySklad korzinasiga o'tadi ({@link #syncEdits}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdeskReverseService {

    private final AdeskConfig cfg;
    private final MoySkladClient ms;
    private final AdeskLinkRepo repo;
    private final AppProps props;
    private final AdeskClient adc;

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
        String key = keyOf(r, t);
        String entity = entityOf(t.income(), key);
        ObjectNode b = opBody(r, t, key, expenseByName);
        b.put("moment", msMoment(t.date()));
        b.put("applicable", true);
        String cat = t.categoryName() == null ? "" : t.categoryName().trim();
        ObjectNode st = stateMeta(entity, t.income() && AdeskSyncService.norm(cat).equals(AdeskSyncService.norm(cfg.catTransfer())));
        if (st != null) b.set("state", st);

        JsonNode res = ms.postEntity("entity/" + entity, b.toString());
        if (res == null) throw new IllegalStateException("MoySklad ruxsat bermadi (token huquqi)");
        String id = res.path("id").asText("");
        if (id.isEmpty()) throw new IllegalStateException("MoySklad javobida id yo'q");

        AdeskLink l = AdeskLink.builder().kind(MONEY).msKey(id).adeskId(t.id()).origin(FROM_AD).status(OK).hash(fp(t))
                .name("Adesk #" + t.id()).msType(entity).docDate(t.date()).sumTiyin(t.signedTiyin()).accountKey(key)
                .updatedAt(Instant.now()).build();
        l = repo.save(l);
        if (r.hasLinks(MONEY)) r.links(MONEY).put(id, l);
        return id;
    }

    /** Adesk hisobi → MoySklad hisob kaliti («<orgId>:<acc|CASH>»); bog'lanmagan bo'lsa — tushunarli xato. */
    private static String keyOf(AdeskRun r, AdTx t) {
        String key = t.accountId() == null ? null : r.accountByAd.get(t.accountId());
        if (key == null) throw new IllegalStateException("«" + t.accountName() + "» hisobi MoySklad hisobiga bog'lanmagan — ⚙️ Sozlamalar → 🔗 Hisoblarni bog'lash");
        return key;
    }

    private static String orgOf(String key) { return key.substring(0, key.indexOf(':')); }

    /** Kassa — Приходный/Расходный ордер, bank/karta — Входящий/Исходящий платёж. */
    static String entityOf(boolean income, String key) {
        boolean cash = AdeskMsReader.CASH.equals(key.substring(key.indexOf(':') + 1));
        return income ? (cash ? "cashin" : "paymentin") : (cash ? "cashout" : "paymentout");
    }

    /** Oddiy operatsiya hujjati maydonlari (vaqt/holat/status'siz — yaratishda ham, tahrirda ham). */
    private ObjectNode opBody(AdeskRun r, AdTx t, String key, Map<String, String> expenseByName) {
        String orgId = orgOf(key);
        String accId = key.substring(key.indexOf(':') + 1);
        boolean cash = AdeskMsReader.CASH.equals(accId);
        long sum = Math.abs(t.signedTiyin());
        if (sum <= 0) throw new IllegalStateException("summa nol");
        ObjectNode b = JN.objectNode();
        b.set("organization", meta("organization", "organization/" + orgId));
        if (!cash) b.set("organizationAccount", meta("account", "organization/" + orgId + "/accounts/" + accId));
        b.set("agent", agent(r, t, orgId));
        b.put("sum", sum);
        String cat = t.categoryName() == null ? "" : t.categoryName().trim();
        b.put("description", msDesc(t.id(), null, t.description(), t.income() && !cat.isEmpty() ? "статья: " + cat : null));
        if (!t.income()) b.set("expenseItem", meta("expenseitem", "expenseitem/" + expenseId(r, t, cat, expenseByName)));
        else if (AdeskSyncService.norm(cat).equals(AdeskSyncService.norm(cfg.catTransfer()))) b.put("paymentPurpose", AdeskConfig.TRANSFER_PURPOSE + " (Adesk)");
        return b;
    }

    /** Adesk operatsiyasi izi (turi, summa, sana, hisob, statya, kontragent, izoh): o'zgarsa — MoySklad hujjati yangilanadi. */
    static String fp(AdTx t) {
        return "AD:" + AdeskSyncService.sha(String.valueOf(t.type()), t.amount() == null ? "" : t.amount().stripTrailingZeros().toPlainString(),
                String.valueOf(t.date()), String.valueOf(t.accountId()), String.valueOf(t.categoryId()), String.valueOf(t.contractorId()),
                t.description() == null ? "" : t.description().trim());
    }

    /**
     * Adesk perevodi → MoySklad'da ikki hujjat: chiqim (kassa — Расходный ордер, bank/karta — Исходящий платёж; statya «Перемещение»,
     * kontragent — qabul qiluvchi o'z firmamiz) va kirim (Приходный ордер / Входящий платёж; maqsad «Перемещение собственных средств …»,
     * kontragent — chiqim firmasi). Ikki leg ham bog'lanadi — keyingi sinxronda qayta Adesk'ga o'tmaydi.
     */
    private void createTransferPair(AdeskRun r, AdTx out, AdTx in, Map<String, String> expenseByName) {
        TrDocs d = trDocs(r, out, in, expenseByName);
        String moment = msMoment(out.date());
        String idO = postMoney(r, out, d.ko(), d.out(), moment, false);
        try {
            postMoney(r, in, d.ki(), d.in(), moment, true);
        } catch (RuntimeException e) {
            throw new IllegalStateException("chiqim yozildi (" + idO + "), kirim yozilmadi: " + e.getMessage() + " — MoySklad'da kirimni qo'lda kiriting", e);
        }
    }

    /** Perevod hujjatlari tanasi (vaqt/holat/status'siz): ko/ki — chiqim/kirim hisob kalitlari. */
    record TrDocs(String ko, String ki, ObjectNode out, ObjectNode in) {}

    private TrDocs trDocs(AdeskRun r, AdTx out, AdTx in, Map<String, String> expenseByName) {
        String ko = out.accountId() == null ? null : r.accountByAd.get(out.accountId());
        String ki = in.accountId() == null ? null : r.accountByAd.get(in.accountId());
        if (ko == null || ki == null)
            throw new IllegalStateException("«" + (ko == null ? out.accountName() : in.accountName())
                    + "» hisobi MoySklad hisobiga bog'lanmagan — ⚙️ Sozlamalar → 🔗 Hisoblarni bog'lash");
        String orgO = orgOf(ko);
        String nameO = r.orgs.stream().filter(o -> o.id().equals(orgO)).map(AdeskMsReader.MsOrg::name).findFirst().orElse("");
        long sum = Math.abs(out.signedTiyin());
        String expId = expenseId(r, out, cfg.catTransfer(), expenseByName);
        // MoySklad'ning o'zida kiritilgan perevod kabi (2026-10-09, 02558/04400 bilan solishtirildi): ikkala tomonda bir xil
        // «Перемещение собственных средств <jo'natuvchi firma> от dd.MM.yyyy», qarshi tomon hisobi (agentAccount) aniq, vaqt bir xil
        String purpose = AdeskConfig.TRANSFER_PURPOSE + " " + nameO + " от " + out.date().format(RU_DATE);
        return new TrDocs(ko, ki,
                moneyBody(ko, ki, sum, msDesc(out.id(), "Перемещение", out.description(), null), purpose, expId),
                moneyBody(ki, ko, sum, msDesc(in.id(), "Перемещение", in.description(), null), purpose, null));
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

    /**
     * Perevod legi hujjati tanasi. key — o'z hisobi, agentKey — qarshi tomon hisobi («<orgId>:<acc|CASH>»): agent shu firma;
     * to'lov hujjatida (kassa emas) agentAccount ham qo'yiladi.
     */
    private ObjectNode moneyBody(String key, String agentKey, long sum, String desc, String purpose, String expenseId) {
        String orgId = orgOf(key), accId = key.substring(key.indexOf(':') + 1);
        boolean cash = AdeskMsReader.CASH.equals(accId);
        String agentOrg = orgOf(agentKey), agentAcc = agentKey.substring(agentKey.indexOf(':') + 1);
        ObjectNode b = JN.objectNode();
        b.set("organization", meta("organization", "organization/" + orgId));
        if (!cash) b.set("organizationAccount", meta("account", "organization/" + orgId + "/accounts/" + accId));
        b.set("agent", meta("organization", "organization/" + agentOrg));
        // MoySklad «Перемещение» tugmasidagidek: qarshi tomon bank hisobi; u kassa bo'lsa — hujjatning o'z hisobi (02588/07281)
        if (!cash) b.set("agentAccount", AdeskMsReader.CASH.equals(agentAcc)
                ? meta("account", "organization/" + orgId + "/accounts/" + accId)
                : meta("account", "organization/" + agentOrg + "/accounts/" + agentAcc));
        b.put("sum", sum);
        b.put("description", desc);
        if (purpose != null) b.put("paymentPurpose", purpose);
        if (expenseId != null) b.set("expenseItem", meta("expenseitem", "expenseitem/" + expenseId));
        return b;
    }

    private String postMoney(AdeskRun r, AdTx t, String key, ObjectNode b, String moment, boolean income) {
        String entity = entityOf(income, key);
        b.put("moment", moment);
        b.put("applicable", true);
        ObjectNode st = stateMeta(entity, true);
        if (st != null) b.set("state", st);
        JsonNode res = ms.postEntity("entity/" + entity, b.toString());
        String id = res == null ? "" : res.path("id").asText("");
        if (id.isEmpty()) throw new IllegalStateException("MoySklad javobida id yo'q (token huquqi?)");
        AdeskLink l = repo.save(AdeskLink.builder().kind(MONEY).msKey(id).adeskId(t.id()).origin(FROM_AD).status(OK).hash(fp(t))
                .name("Adesk #" + t.id()).msType(entity).docDate(t.date()).sumTiyin(t.signedTiyin()).accountKey(key)
                .updatedAt(Instant.now()).build());
        if (r.hasLinks(MONEY)) r.links(MONEY).put(id, l);
        return id;
    }

    /* ==================== Adesk'da tahrirlangan / o'chirilgan → MoySklad ==================== */

    /**
     * Adesk'da kiritilib MoySklad'ga yozilgan operatsiyalar (origin AD), 2026-10-09 user talabi: Adesk'da tahrirlansa —
     * MoySklad hujjati ham o'sha hujjatning o'zida o'zgartiriladi (raqami saqlanadi); o'chirilsa — MoySklad «Корзина» siga o'tadi.
     * txs — Adesk'ning [from, to] dagi operatsiyalari. Ro'yxatda ko'rinmagani id bo'yicha alohida so'raladi: faqat Adesk aniq
     * «topilmadi» desa o'chirilgan hisoblanadi (chala ro'yxat hujjat o'chirmaydi). Hisob turi (kassa ↔ bank) yoki firma
     * o'zgarsa — eski hujjat korzinaga, bog'lanish uziladi va yangisi shu yurishning o'zida qayta yoziladi ({@link #handle}).
     * Chaqiruvchi bog'lanmagan (qo'lda) operatsiyalar ro'yxatini shundan KEYIN tuzishi kerak.
     */
    public void syncEdits(AdeskRun r, List<AdTx> txs, java.time.LocalDate from, java.time.LocalDate to) {
        if (!cfg.reverse()) return;
        Map<String, AdeskLink> L = r.hasLinks(MONEY) ? r.links(MONEY) : null;
        Collection<AdeskLink> all = L != null ? L.values() : repo.findByKind(MONEY);
        Map<Long, AdTx> byId = new HashMap<>();
        txs.forEach(t -> byId.put(t.id(), t));
        List<AdeskLink> ad = new ArrayList<>();
        for (AdeskLink l : all)
            if (FROM_AD.equals(l.getOrigin()) && AdeskSyncService.linked(l)
                    && (byId.containsKey(l.getAdeskId()) || l.getDocDate() != null && !l.getDocDate().isBefore(from) && !l.getDocDate().isAfter(to)))
                ad.add(l);
        if (ad.isEmpty()) return;
        Map<Long, AdeskLink> byAd = new HashMap<>();
        ad.forEach(l -> byAd.put(l.getAdeskId(), l));
        Map<String, String> expenseByName = new HashMap<>();
        r.expenseItems.forEach((id, name) -> expenseByName.put(AdeskSyncService.norm(name), id));

        // 1. ro'yxatda yo'qlari — id bo'yicha: o'chirilganmi yoki sanasi davrdan chiqib ketganmi
        int asked = 0;
        for (AdeskLink l : ad) {
            if (r.stopped()) return;
            if (byId.containsKey(l.getAdeskId())) continue;
            if (asked++ >= 30) { r.note("Adesk'da ko'rinmagan operatsiyalar ko'p — qolgani keyingi tekshiruvda"); break; }
            try {
                Optional<AdTx> t = adc.transaction(l.getAdeskId());
                if (t.isPresent()) byId.put(l.getAdeskId(), t.get());
                else trashDoc(r, L, l);
            } catch (AdeskHttp.AdeskException e) {
                if (e.fatal) throw e;   // boshqa xato — o'chirilgan deb HISOBLANMAYDI
            }
        }
        // 2. o'zgarganlari
        Set<Long> done = new HashSet<>();
        for (AdeskLink l : ad) {
            if (r.stopped()) return;
            if (!AdeskSyncService.linked(l) || done.contains(l.getAdeskId())) continue;
            AdTx t = byId.get(l.getAdeskId());
            if (t == null) continue;
            String f = fp(t);
            if (f.equals(l.getHash())) continue;
            if (l.getHash() == null && sameBasics(r, l, t)) { l.setHash(f); save(L, l); continue; }   // eski bog'lanish — iz yoziladi
            try {
                if (t.transfer()) {
                    AdTx p = t.pairedId() == null ? null : byId.get(t.pairedId());
                    if (p == null && t.pairedId() != null) p = adc.transaction(t.pairedId()).orElse(null);
                    if (p == null) throw new IllegalStateException("perevodning ikkinchi tomoni Adesk'da topilmadi");
                    AdTx out = t.income() ? p : t, in = t.income() ? t : p;
                    editTransfer(r, L, out, in, byAd.get(out.id()), byAd.get(in.id()), expenseByName);
                    done.add(out.id()); done.add(in.id());
                    cleared(out); cleared(in);
                } else {
                    editOp(r, L, t, l, expenseByName);
                    cleared(t);
                }
            } catch (AdeskHttp.AdeskException e) {
                if (e.fatal) throw e;
                fail(r, t, "Adesk'dagi o'zgarish MoySklad'ga o'tmadi: " + e.getMessage());
            } catch (Exception e) {
                fail(r, t, "Adesk'dagi o'zgarish MoySklad'ga o'tmadi: " + e.getMessage());
                log.warn("Adesk #{} o'zgarishi MoySklad'ga o'tmadi: {}", t.id(), e.getMessage());
            }
        }
    }

    /** Izi yo'q (eski) bog'lanish: summa, sana, hisob yozilgandagidek bo'lsa — o'zgarmagan deb hisoblanadi. */
    private static boolean sameBasics(AdeskRun r, AdeskLink l, AdTx t) {
        return Objects.equals(l.getSumTiyin(), t.signedTiyin()) && Objects.equals(l.getDocDate(), t.date())
                && t.accountId() != null && Objects.equals(l.getAccountKey(), r.accountByAd.get(t.accountId()));
    }

    /** Oddiy operatsiya tahriri: hujjat turi va firma o'sha bo'lsa — joyida (PUT), aks holda korzinaga + qayta yoziladi. */
    private void editOp(AdeskRun r, Map<String, AdeskLink> L, AdTx t, AdeskLink l, Map<String, String> expenseByName) {
        String key = keyOf(r, t);
        String entity = entityOf(t.income(), key);
        if (!entity.equals(l.getMsType()) || !orgOf(key).equals(orgOf(l.getAccountKey()))) {
            trashDoc(r, L, l);   // bog'lanish uziladi — handle() shu yurishda yangi hujjat yozadi
            return;
        }
        ObjectNode b = opBody(r, t, key, expenseByName);
        if (!t.date().equals(l.getDocDate())) b.put("moment", msMoment(t.date()));
        put(entity, l.getMsKey(), b);
        relinked(L, l, t, key);
        r.inc("ad.edited");
        log.info("Adesk #{} tahriri MoySklad'ga o'tdi: {} {}", t.id(), entity, l.getMsKey());
    }

    /** Perevod tahriri: ikkala hujjat turi va firmasi o'sha bo'lsa — joyida, aks holda ikkalasi korzinaga + qayta yoziladi. */
    private void editTransfer(AdeskRun r, Map<String, AdeskLink> L, AdTx out, AdTx in, AdeskLink lo, AdeskLink li,
                              Map<String, String> expenseByName) {
        TrDocs d = trDocs(r, out, in, expenseByName);
        String eo = entityOf(false, d.ko()), ei = entityOf(true, d.ki());
        boolean inPlace = lo != null && li != null && eo.equals(lo.getMsType()) && ei.equals(li.getMsType())
                && orgOf(d.ko()).equals(orgOf(lo.getAccountKey())) && orgOf(d.ki()).equals(orgOf(li.getAccountKey()));
        if (!inPlace) {
            if (lo != null) trashDoc(r, L, lo);
            if (li != null) trashDoc(r, L, li);
            return;
        }
        if (!out.date().equals(lo.getDocDate())) {
            String moment = msMoment(out.date());
            d.out().put("moment", moment);
            d.in().put("moment", moment);
        }
        put(eo, lo.getMsKey(), d.out());
        put(ei, li.getMsKey(), d.in());
        relinked(L, lo, out, d.ko());
        relinked(L, li, in, d.ki());
        r.inc("ad.edited", 2);
        log.info("Adesk perevod #{} tahriri MoySklad'ga o'tdi: {} / {}", out.id(), lo.getMsKey(), li.getMsKey());
    }

    private void put(String entity, String id, ObjectNode b) {
        if (ms.putEntity("entity/" + entity + "/" + id, b.toString()) == null)
            throw new IllegalStateException("MoySklad ruxsat bermadi (token huquqi)");
    }

    private void relinked(Map<String, AdeskLink> L, AdeskLink l, AdTx t, String key) {
        l.setHash(fp(t)); l.setSumTiyin(t.signedTiyin()); l.setDocDate(t.date()); l.setAccountKey(key);
        l.setStatus(OK); l.setError(null);
        save(L, l);
    }

    /**
     * MoySklad hujjati korzinaga (qayta tiklash mumkin). Korzinaga o'tmasa — проведение olib tashlanadi (summa hisobdan chiqadi,
     * hujjat «❌ Adesk'da o'chirilgan» izohi bilan qoladi). MoySklad'da allaqachon yo'q (404) — bog'lanish yopiladi.
     */
    private void trashDoc(AdeskRun r, Map<String, AdeskLink> L, AdeskLink l) {
        String entity = l.getMsType(), id = l.getMsKey();
        try {
            ms.trashEntity(entity, id);
            r.inc("ad.deleted");
            log.info("Adesk #{} o'chirilgan — MoySklad {} {} korzinaga o'tkazildi", l.getAdeskId(), entity, id);
        } catch (Exception e) {
            // korzinaga o'tmadi — hujjat haqiqatan bormi? (404 «endpoint yo'q» ni «hujjat yo'q» deb adashtirmaslik uchun alohida GET)
            JsonNode doc;
            try {
                doc = ms.fetchJson("entity/" + entity + "/" + id);
                if (doc == null) {   // 401/403 — ruxsat yo'q: o'chirilgan deb hisoblanmaydi
                    failGone(r, l, "Adesk'da o'chirilgan, MoySklad'da o'chirilmadi: ruxsat yo'q (" + e.getMessage() + ") — qo'lda o'chiring");
                    return;
                }
            } catch (Exception ge) {
                if (!String.valueOf(ge.getMessage()).contains("HTTP 404")) {
                    failGone(r, l, "Adesk'da o'chirilgan, MoySklad'da o'chirilmadi: " + ge.getMessage() + " — qo'lda o'chiring");
                    return;
                }
                doc = null;
                log.info("Adesk #{} o'chirilgan — MoySklad {} {} allaqachon yo'q", l.getAdeskId(), entity, id);
            }
            if (doc != null) {
                try {
                    ObjectNode b = JN.objectNode();
                    b.put("applicable", false);
                    b.put("description", ("❌ Adesk'da o'chirilgan · " + doc.path("description").asText("")).trim());
                    put(entity, id, b);
                    r.inc("ad.unposted");
                    log.warn("Adesk #{} o'chirilgan — MoySklad {} {} korzinaga o'tmadi ({}), проведение olib tashlandi", l.getAdeskId(), entity, id, e.getMessage());
                } catch (Exception e2) {
                    failGone(r, l, "Adesk'da o'chirilgan, MoySklad'da o'chirilmadi: " + e2.getMessage() + " — qo'lda o'chiring");
                    return;
                }
            }
        }
        l.setStatus(DELETED);
        l.setError(null);
        save(L, l);
    }

    private void save(Map<String, AdeskLink> L, AdeskLink l) {
        l.setUpdatedAt(Instant.now());
        AdeskLink s = repo.save(l);
        if (L != null && s != null) L.put(s.getMsKey(), s);
    }

    /** O'chirilgan Adesk operatsiyasi bo'yicha xato (⚠️ Xatolar, ADESK turi «ad:<id>»). */
    private void failGone(AdeskRun r, AdeskLink link, String msg) {
        r.inc("ad.toMsError");
        AdeskLink l = repo.findByKindAndMsKey(ADESK, "ad:" + link.getAdeskId())
                .orElse(AdeskLink.builder().kind(ADESK).msKey("ad:" + link.getAdeskId()).build());
        l.setAdeskId(link.getAdeskId());
        l.setStatus(ERROR);
        l.setError(msg);
        l.setName("O'chirilgan · " + link.getMsType() + " " + (link.getName() == null ? "" : link.getName()));
        l.setDocDate(link.getDocDate());
        l.setSumTiyin(link.getSumTiyin());
        l.setUpdatedAt(Instant.now());
        repo.save(l);
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
