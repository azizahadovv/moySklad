package uz.kassa.service.adesk;

import uz.kassa.domain.AdeskLink;
import uz.kassa.service.adesk.AdeskMsReader.MsOrg;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 📒 Bitta sinxron yurishi holati: keshlar (MoySklad ma'lumotnomalari, Adesk id xaritalari, bog'lanishlar),
 * hisoblagichlar, jarayon matni va to'xtatish bayrog'i. Bitta oqimda ishlatiladi; panel faqat o'qiydi.
 */
public class AdeskRun {

    public final boolean full;
    public final LocalDateTime startedAt;
    /** Yurish boshlangan lahza — shu yurishda yozilgan bog'lanishlar «yangi» hisoblanadi (Adesk ro'yxatida hali ko'rinmasa ham tiklanmaydi). */
    public final java.time.Instant startedInstant = java.time.Instant.now();
    private final AtomicBoolean stop;

    public volatile String stage = "boshlanmoqda";
    public volatile String progress = "";

    /* MoySklad ma'lumotnomalari */
    public Map<String, String> currencies = Map.of();
    public List<MsOrg> orgs = List.of();
    public Map<String, String> expenseItems = Map.of();   // id → nom
    public Map<String, String> employees = Map.of();      // id → ism
    /** Davr boshidan bugungacha BARCHA pul hujjatlari (to'liq yurishda bir marta o'qiladi; boshlang'ich qoldiq ham shundan). */
    public List<AdeskMsReader.MsMoneyDoc> allMoney;
    /** allMoney bilan bir suratda o'qilgan joriy qoldiqlar (boshlang'ich qoldiq hisobi uchun). */
    public Map<String, Long> balances;

    /* MoySklad → Adesk id */
    public final Map<String, Long> orgLe = new HashMap<>();          // orgId → legal entity
    public final Map<String, Long> account = new HashMap<>();        // "<orgId>:<acc|CASH>" → bank account
    public final Map<Long, String> accountByAd = new HashMap<>();    // bank account → kalit
    public final Map<String, Long> catIn = new HashMap<>();          // statya nomi → id (kirim)
    public final Map<String, Long> catOut = new HashMap<>();         // statya nomi → id (chiqim)
    public Long stockLe;
    /** Operatsiyalar yoziladigan Adesk proyekti (null — proyektsiz). */
    public Long projectId;
    /** Adesk'dagi operatsiyalar izoh|hisob|summa|sana → id (dangasa; mavjud operatsiyani qayta bog'lash uchun). */
    public Map<String, Long> existingTx;

    /* bog'lanishlar keshi: kind → (msKey → link) */
    private final Map<String, Map<String, AdeskLink>> links = new HashMap<>();

    /* hisoblagichlar va izohlar */
    public final Map<String, Integer> stats = new ConcurrentHashMap<>();
    public final List<String> notes = Collections.synchronizedList(new ArrayList<>());
    public volatile String fatal;

    public AdeskRun(boolean full, LocalDateTime startedAt, AtomicBoolean stop) {
        this.full = full;
        this.startedAt = startedAt;
        this.stop = stop;
    }

    public boolean stopped() { return stop.get() || fatal != null; }

    public void inc(String key) { inc(key, 1); }
    public void inc(String key, int n) { if (n != 0) stats.merge(key, n, Integer::sum); }
    public int get(String key) { return stats.getOrDefault(key, 0); }

    public void note(String s) { if (notes.size() < 40) notes.add(s); }

    public Map<String, AdeskLink> links(String kind) { return links.get(kind); }
    public void putLinks(String kind, Map<String, AdeskLink> m) { links.put(kind, m); }
    public boolean hasLinks(String kind) { return links.containsKey(kind); }
}
