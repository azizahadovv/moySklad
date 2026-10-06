package uz.kassa.service.adesk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.domain.AdeskLink;
import uz.kassa.repo.AdeskLinkRepo;
import uz.kassa.service.adesk.AdeskClient.AdAccount;
import uz.kassa.service.adesk.AdeskMsReader.MsAccount;
import uz.kassa.service.adesk.AdeskMsReader.MsOrg;

import java.time.Instant;
import java.util.*;

/**
 * 📒 Hisoblarni bog'lash (docs/ADESK.md §3.1, user qarori 2026-10-05): Adesk'da qo'lda ochilgan hisoblar MoySklad
 * hisoblariga BIR-BIRGA bog'lanadi. Bot o'xshashini taklif qiladi (nom so'zlari: kirill/lotin farqisiz, bitta harf
 * xatosi bilan), SuperAdmin botda tasdiqlaydi yoki o'zgartiradi. Tasdiqlangach ACCOUNT bog'lanishlari yoziladi;
 * birinchi sinxron bog'langan hisob nomini MoySklad nomiga o'zgartiradi, bog'lanmagan MoySklad hisoblarini yaratadi.
 * Qoralama settings «adesk.accMap» da (JSON {msKey: adeskId, 0 — yangi}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdeskAccountMapper {

    private final AdeskConfig cfg;
    private final AdeskClient ad;
    private final AdeskMsReader msr;
    private final AdeskLinkRepo repo;
    private final ObjectMapper om = new ObjectMapper();

    /** Bitta MoySklad hisobi va tanlangan Adesk hisobi (null — yangi yaratiladi). */
    public record Row(String msKey, String msName, String orgShort, boolean cash, Long adeskId) {}

    /** Ekran uchun: qatorlar, Adesk hisoblari va qaysi biri taklif (avtomatik) ekani. */
    public record View(List<Row> rows, List<AdAccount> adesk, Set<String> suggested) {
        public AdAccount adesk(Long id) { return id == null ? null : adesk.stream().filter(a -> a.id() == id).findFirst().orElse(null); }
        public List<AdAccount> unmapped() {
            Set<Long> used = new HashSet<>();
            rows.forEach(r -> { if (r.adeskId() != null) used.add(r.adeskId()); });
            return adesk.stream().filter(a -> !used.contains(a.id())).toList();
        }
        public long mapped() { return rows.stream().filter(r -> r.adeskId() != null).count(); }
    }

    private volatile List<MsAccount> msCache;
    private volatile Map<String, MsOrg> orgCache;
    private volatile List<AdAccount> adCache;
    private volatile long cachedAt;

    /** Joriy holat: qoralama bo'lsa — u, bo'lmasa avtomatik taklif. refresh — MoySklad va Adesk qaytadan o'qiladi. */
    public synchronized View view(boolean refresh) {
        if (refresh || msCache == null || System.currentTimeMillis() - cachedAt > 30 * 60_000L) load();
        Map<String, Integer> nameCnt = new HashMap<>();
        for (MsAccount a : msCache) if (a.accountId() != null) nameCnt.merge(AdeskSyncService.norm(a.rawName()), 1, Integer::sum);
        Map<String, Long> draft = draft();
        Set<Long> adIds = new HashSet<>();
        adCache.forEach(a -> adIds.add(a.id()));
        // allaqachon bog'langanlar (oldingi sinxron bot o'zi yaratgan) — boshlang'ich holat; taklif faqat qolganlarga
        Map<String, Long> existing = new HashMap<>();
        for (AdeskLink l : repo.findByKind(AdeskLink.ACCOUNT))
            if (AdeskSyncService.linked(l) && adIds.contains(l.getAdeskId())) existing.put(l.getMsKey(), l.getAdeskId());
        Map<String, Long> sug = Map.of();
        if (draft.isEmpty()) {
            Set<Long> taken = new HashSet<>(existing.values());
            sug = suggest(msCache.stream().filter(a -> !existing.containsKey(a.key())).toList(), orgCache,
                    adCache.stream().filter(a -> !taken.contains(a.id())).toList());
        }
        List<Row> rows = new ArrayList<>();
        for (MsAccount a : msCache) {
            MsOrg org = orgCache.get(a.orgId());
            Long id = draft.isEmpty() ? existing.getOrDefault(a.key(), sug.get(a.key())) : draft.get(a.key());
            if (id != null && (id == 0 || !adIds.contains(id))) id = null;
            rows.add(new Row(a.key(), AdeskSyncService.accountName(a, org, nameCnt), org == null ? "" : org.shortName(), a.cash(), id));
        }
        return new View(rows, adCache, draft.isEmpty() ? sug.keySet() : Set.of());
    }

    /** Qatorga Adesk hisobini tanlash (null — yangi yaratiladi). Shu Adesk hisobi boshqa qatorda bo'lsa — u yerdan olinadi. */
    public synchronized void set(String msKey, Long adeskId) {
        View v = view(false);
        Map<String, Long> m = new LinkedHashMap<>();
        for (Row r : v.rows()) m.put(r.msKey(), r.adeskId() == null ? 0L : r.adeskId());
        if (adeskId != null) m.replaceAll((k, id) -> id.equals(adeskId) ? 0L : id);
        m.put(msKey, adeskId == null ? 0L : adeskId);
        saveDraft(m);
    }

    /** Qoralamani tashlash — yana avtomatik taklif. */
    public synchronized void reset() {
        cfg.set(AdeskConfig.ACC_MAP, "");
        view(true);
    }

    /** Tasdiqlash: tanlanganlar ACCOUNT bog'lanishi bo'lib yoziladi. Natija — bog'langan hisoblar soni. */
    public synchronized int confirm() {
        View v = view(false);
        int n = 0;
        for (Row r : v.rows()) {
            if (r.adeskId() == null) {   // «➕ yangi» tanlangan: oldingi bog'lanish bo'lsa yopiladi — sinxron yangi hisob yaratadi
                repo.findByKindAndMsKey(AdeskLink.ACCOUNT, r.msKey()).filter(AdeskSyncService::linked).ifPresent(l -> {
                    l.setStatus(AdeskLink.DELETED); l.setUpdatedAt(Instant.now()); repo.save(l);
                });
                continue;
            }
            AdeskLink l = repo.findByKindAndMsKey(AdeskLink.ACCOUNT, r.msKey()).orElse(AdeskLink.builder().kind(AdeskLink.ACCOUNT).msKey(r.msKey()).build());
            l.setAdeskId(r.adeskId());
            l.setName(r.msName());
            l.setAccountKey(r.msKey());
            l.setHash(null);
            l.setStatus(AdeskLink.OK);
            l.setError(null);
            l.setUpdatedAt(Instant.now());
            repo.save(l);
            n++;
        }
        cfg.set(AdeskConfig.ACC_CONFIRMED, "1");
        cfg.set(AdeskConfig.ACC_MAP, "");   // qoralama tozalanadi — keyingi ochilishda joriy bog'lanishlardan boshlanadi
        log.info("Adesk hisoblari bog'lash tasdiqlandi: {} ta bog'landi, {} ta yangi yaratiladi", n, v.rows().size() - n);
        return n;
    }

    private void load() {
        List<MsOrg> orgs = msr.orgs();
        Map<String, MsOrg> byId = new LinkedHashMap<>();
        orgs.forEach(o -> byId.put(o.id(), o));
        orgCache = byId;
        msCache = msr.accounts(orgs);
        adCache = ad.bankAccounts().stream().filter(a -> !"closed".equalsIgnoreCase(a.status())).toList();
        cachedAt = System.currentTimeMillis();
    }

    private Map<String, Long> draft() {
        String s = cfg.get(AdeskConfig.ACC_MAP).orElse("");
        if (s.isBlank()) return Map.of();
        try { return om.readValue(s, new TypeReference<LinkedHashMap<String, Long>>() {}); }
        catch (Exception e) { return Map.of(); }
    }

    private void saveDraft(Map<String, Long> m) {
        try { cfg.set(AdeskConfig.ACC_MAP, om.writeValueAsString(m)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /* ==================== taklif (o'xshashlik) ==================== */

    /** Umumiy so'zlar — o'xshashlikda hisobga olinmaydi (karta/kassa/firma turi so'zlari). */
    static final Set<String> STOP = Set.of("karta", "card", "click", "clik", "klik", "aka", "nsb", "kassa", "naqd", "nalichnye",
            "uzs", "usd", "dokon", "bank", "rs", "r", "s", "mchj", "ooo", "llc", "xk", "ip", "schet", "hisob", "prochie");

    /**
     * MoySklad hisobi → Adesk hisobi takliflari (bir-birga). O'xshashlik = mos so'zlar / ikki tomondan kattarog'i
     * (≥ 0.6). Ikki manba: hisobning o'z nomi («NSB click Zufar» ↔ «Karta Zufar aka») va tashkilotning asosiy hisobi uchun
     * tashkilot nomi («SHMF р/с» ↔ «Shofirkon Mega Fayz»). Bir xil tur (naqd/bank) ozgina ustun. Faqat «Kassa» deb
     * atalgan Adesk hisobi — asosiy (001) tashkilot kassasiga.
     */
    static Map<String, Long> suggest(List<MsAccount> accs, Map<String, MsOrg> orgs, List<AdAccount> ads) {
        record Cand(String msKey, long adId, double score) {}
        List<Cand> cands = new ArrayList<>();
        Map<String, Integer> nameCnt = new HashMap<>();
        for (MsAccount m : accs) if (m.accountId() != null) nameCnt.merge(AdeskSyncService.norm(m.rawName()), 1, Integer::sum);
        String mainOrg = orgs.values().stream().filter(o -> o.name().startsWith("001")).map(MsOrg::id).findFirst()
                .orElse(orgs.isEmpty() ? "" : orgs.keySet().iterator().next());
        for (AdAccount a : ads) {
            Set<String> ta = tokens(a.name());
            boolean aCash = AdeskSyncService.adType(a, 2) == 1;
            if (ta.isEmpty()) {
                if (translit(a.name()).trim().equals("kassa"))
                    cands.add(new Cand(mainOrg + ":" + AdeskMsReader.CASH, a.id(), 0.9));
                for (MsAccount m : accs)   // so'zsiz nom («NSB Clik Прочие») — faqat aynan mos bo'lsa
                    if (sameName(a, m, orgs, nameCnt)) cands.add(new Cand(m.key(), a.id(), 2.0));
                continue;
            }
            for (MsAccount m : accs) {
                if (sameName(a, m, orgs, nameCnt)) {   // aynan shu nom (qo'lda MoySklad nomi bilan ochilgan yoki bot oldin yaratgan)
                    cands.add(new Cand(m.key(), a.id(), 2.0));
                    continue;
                }
                double s = sim(ta, tokens(m.rawName()));
                MsOrg org = orgs.get(m.orgId());
                if (m.isDefault() && org != null) s = Math.max(s, sim(ta, tokens(org.name())));
                if (s < 0.6) continue;
                boolean mCash = m.cash() || AdeskMsReader.looksCash(m.rawName());
                cands.add(new Cand(m.key(), a.id(), s + (aCash == mCash ? 0.05 : -0.05)));
            }
        }
        cands.sort(Comparator.comparingDouble(Cand::score).reversed());
        Map<String, Long> out = new LinkedHashMap<>();
        Set<Long> usedAd = new HashSet<>();
        for (Cand c : cands) {
            if (out.containsKey(c.msKey()) || usedAd.contains(c.adId())) continue;
            out.put(c.msKey(), c.adId());
            usedAd.add(c.adId());
        }
        return out;
    }

    /** Adesk nomi MoySklad nomiga yoki bot beradigan nomga («Касса · 003 ITT», «р/с · 003 ITT») aynan teng. */
    private static boolean sameName(AdAccount a, MsAccount m, Map<String, MsOrg> orgs, Map<String, Integer> nameCnt) {
        String n = AdeskSyncService.norm(a.name());
        return n.equals(AdeskSyncService.norm(m.rawName()))
                || n.equals(AdeskSyncService.norm(AdeskSyncService.accountName(m, orgs.get(m.orgId()), nameCnt)));
    }

    /** Mos so'zlar soni / ikki to'plamdan kattasining hajmi. So'z mos: teng yoki (≥5 harf) bitta harf farqi. */
    static double sim(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int match = 0;
        for (String x : a) for (String y : b) if (wordEq(x, y)) { match++; break; }
        return match / (double) Math.max(a.size(), b.size());
    }

    static boolean wordEq(String x, String y) {
        if (x.equals(y)) return true;
        return x.length() >= 5 && y.length() >= 5 && lev1(x, y);
    }

    /** Levenshtein masofasi ≤ 1. */
    static boolean lev1(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 1) return false;
        int i = 0, j = 0, diff = 0;
        while (i < a.length() && j < b.length()) {
            if (a.charAt(i) == b.charAt(j)) { i++; j++; continue; }
            if (++diff > 1) return false;
            if (a.length() > b.length()) i++;
            else if (a.length() < b.length()) j++;
            else { i++; j++; }
        }
        return diff + (a.length() - i) + (b.length() - j) <= 1;
    }

    /** Nom → mazmunli so'zlar: kichik harf, kirill → lotin, apostroflarsiz; umumiy so'zlar, 1–3 xonali va INN kabi uzun raqamlar tashlanadi. */
    static Set<String> tokens(String name) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : translit(name).replaceAll("['’ʻ`\"«»]", "").split("[^a-z0-9]+")) {
            if (w.isEmpty() || STOP.contains(w)) continue;
            if (w.matches("\\d+") && (w.length() <= 3 || w.length() >= 7)) continue;
            out.add(w);
        }
        return out;
    }

    private static final Map<Character, String> TR = new HashMap<>();
    static {
        String[][] t = {{"а", "a"}, {"б", "b"}, {"в", "v"}, {"г", "g"}, {"д", "d"}, {"е", "e"}, {"ё", "yo"}, {"ж", "j"}, {"з", "z"},
                {"и", "i"}, {"й", "y"}, {"к", "k"}, {"л", "l"}, {"м", "m"}, {"н", "n"}, {"о", "o"}, {"п", "p"}, {"р", "r"}, {"с", "s"},
                {"т", "t"}, {"у", "u"}, {"ф", "f"}, {"х", "x"}, {"ц", "ts"}, {"ч", "ch"}, {"ш", "sh"}, {"щ", "sh"}, {"ъ", ""}, {"ы", "i"},
                {"ь", ""}, {"э", "e"}, {"ю", "yu"}, {"я", "ya"}, {"ў", "o"}, {"қ", "q"}, {"ғ", "g"}, {"ҳ", "h"}};
        for (String[] p : t) TR.put(p[0].charAt(0), p[1]);
    }

    static String translit(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : (s == null ? "" : s.toLowerCase()).toCharArray()) sb.append(TR.getOrDefault(c, String.valueOf(c)));
        return sb.toString();
    }
}
