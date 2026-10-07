package uz.kassa.service.adesk;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.domain.AdeskLink;
import uz.kassa.repo.AdeskLinkRepo;
import uz.kassa.service.adesk.AdeskClient.*;
import uz.kassa.service.adesk.AdeskHttp.AdeskException;
import uz.kassa.service.adesk.AdeskMsReader.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static uz.kassa.domain.AdeskLink.*;

/**
 * 📒 MoySklad → Adesk sinxroni (docs/ADESK.md §3–§4). Bosqichlar tartibi qat'iy:
 * yuridik shaxslar → hisoblar (boshlang'ich qoldiq bilan) → statyalar → kontragentlar → tovar/xizmatlar
 * (boshlang'ich partiya bilan) → pul hujjatlari (operatsiyalar) → otgruzka/priyomka/vozvratlar (majburiyatlar).
 *
 * Har obyekt yaratilishi bilan {@code adesk_link} ga yoziladi — yurish to'xtasa (restart, limit) keyingisi
 * qolgan joyidan davom etadi, dublikat yaratilmaydi. To'liq yurish (full) qo'shimcha ravishda MoySklad'da
 * o'chirilgan/davrdan chiqqan hujjatlarni Adesk'dan oladi, Adesk'da o'zgartirilgan/o'chirilganlarini qayta
 * tiklaydi (MoySklad — asosiy manba) va Adesk'da qo'lda kiritilganlarini {@link AdeskReverseService} ga beradi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdeskSyncService {

    private final AdeskConfig cfg;
    private final AdeskClient ad;
    private final AdeskMsReader msr;
    private final AdeskLinkRepo repo;
    private final AdeskReverseService reverse;

    private static final int BATCH = 50;
    private static final long OVERLAP_MIN = 15;
    private static final String OTHER_EXPENSE = "Прочие расходы";
    private static final JsonNodeFactory JN = JsonNodeFactory.instance;

    /** Barcha bosqichlar. Fatal Adesk xatosi (token/obuna) — {@link AdeskException} tashqariga chiqadi. */
    public void run(AdeskRun r) {
        r.stage = "ma'lumotnomalar";
        r.currencies = msr.currencies();
        r.orgs = msr.orgs();
        r.expenseItems = msr.expenseItems();
        r.employees = msr.employees();
        String conflict = existingConflict(r);
        if (conflict != null) { r.fatal = conflict; r.stage = "to'xtadi"; return; }
        if (r.full) snapshot(r);
        r.projectId = resolveProject(r);
        step(r, "yuridik shaxslar", () -> orgs(r));
        step(r, "hisoblar", () -> accounts(r));
        step(r, "statyalar", () -> categories(r));
        step(r, "kontragentlar", () -> contractors(r));
        step(r, "tovar va xizmatlar", () -> products(r));
        step(r, "pul hujjatlari", () -> money(r));
        step(r, "otgruzka va priyomkalar", () -> commitments(r));
        r.stage = r.stopped() ? "to'xtatildi" : "tugadi";
        r.progress = "";
    }

    private void step(AdeskRun r, String name, Runnable body) {
        if (r.stopped()) return;
        r.stage = name;
        r.progress = "";
        body.run();
    }

    /* ==================== 0. birinchi ulanish himoyasi ==================== */

    /**
     * Birinchi yurishdan oldin (hali bitta ham hisob bog'lanmagan va bog'lash tasdiqlanmagan): Adesk'da MoySklad
     * nomlariga mos kelmaydigan hisob bo'lsa — to'xtash. Aks holda bot ularning yoniga MoySklad'dagi 49 hisobni
     * yaratib dublikat qiladi (2026-10-05: haqiqiy Adesk'da qo'lda ochilgan 25 hisob topildi). Yo'l — SuperAdmin
     * «🔗 Hisoblarni bog'lash» ekranida har MoySklad hisobini Adesk hisobiga bog'lab tasdiqlaydi ({@link AdeskAccountMapper}).
     * null — davom etish mumkin; matn — to'xtash sababi.
     */
    String existingConflict(AdeskRun r) {
        if (cfg.accountsConfirmed()) return null;
        Set<Long> linkedIds = usedIds(links(r, ACCOUNT));   // bot o'zi yaratgan/bog'lagan hisoblar — begona emas
        List<AdAccount> ads = ad.bankAccounts().stream().filter(a -> !"closed".equalsIgnoreCase(a.status()) && !linkedIds.contains(a.id())).toList();
        if (ads.isEmpty()) return null;
        Map<String, MsOrg> orgById = new HashMap<>();
        for (MsOrg o : r.orgs) orgById.put(o.id(), o);
        List<MsAccount> accs = msr.accounts(r.orgs);
        Map<String, Integer> nameCnt = new HashMap<>();
        for (MsAccount a : accs) if (a.accountId() != null) nameCnt.merge(norm(a.rawName()), 1, Integer::sum);
        Set<String> names = new HashSet<>();
        for (MsAccount a : accs) { names.add(norm(accountName(a, orgById.get(a.orgId()), nameCnt))); names.add(norm(a.rawName())); }
        List<String> foreign = ads.stream().filter(a -> !names.contains(norm(a.name()))).map(AdAccount::name).toList();
        if (foreign.isEmpty()) return null;
        r.note("Adesk hisoblari: " + String.join(", ", foreign.stream().limit(12).toList()) + (foreign.size() > 12 ? " …" : ""));
        return "Adesk'da oldindan " + foreign.size() + " ta hisob bor, ular MoySklad nomlariga mos kelmadi. "
                + "Dublikat bo'lmasligi uchun hech narsa yuborilmadi. Avval ularni bog'lang: 📒 Adesk → 🔗 Hisoblarni bog'lash";
    }

    /* ==================== 1. yuridik shaxslar ==================== */

    /** Yuridik shaxs Adesk'da yaratilmay asosiy yuridik shaxsga biriktirilgan bog'lanish belgisi (hash). */
    static final String LE_FALLBACK = "fallback";

    /** «Asosiy» proyekt id'si (nom bo'yicha); topilmasa — eslatma va proyektsiz. */
    private Long resolveProject(AdeskRun r) {
        String name = cfg.project();
        if (name == null || name.equals("-")) return null;
        try {
            for (AdeskClient.AdProject p : ad.projects()) if (norm(p.name()).equals(norm(name))) return p.id();
        } catch (AdeskException e) { if (e.fatal) throw e; }
        r.note("Adesk'da «" + name + "» proyekti topilmadi — operatsiyalar proyektsiz yoziladi");
        return null;
    }

    /** Bitta yuridik shaxs rejimi: hamma firma asosiy (001) yuridik shaxs ostida; qolgan Adesk yuridik shaxslariga tegilmaydi. */
    private void orgsSingle(AdeskRun r, List<AdLegal> les, Map<Long, AdLegal> byId, Map<String, AdeskLink> L) {
        MsOrg main = r.orgs.stream().filter(o -> o.name().startsWith("001")).findFirst().orElse(r.orgs.isEmpty() ? null : r.orgs.get(0));
        if (main == null) return;
        AdeskLink ml = L.get(main.id());
        Long mainLe = linked(ml) && byId.containsKey(ml.getAdeskId()) && !LE_FALLBACK.equals(ml.getHash()) ? ml.getAdeskId() : null;
        if (mainLe == null) {
            AdLegal m = les.stream().filter(x -> leMatches(x, main)).findFirst().orElse(null);
            try {
                if (m == null) { m = ad.createLegalEntity(main.name(), main.legalTitle(), main.inn()); r.inc("le.created"); }
                mainLe = m.id();
            } catch (AdeskException e) {
                if (e.fatal) throw e;
                err(r, ORG, main.id(), main.name(), e.getMessage());
                return;
            }
        }
        for (MsOrg o : r.orgs) {
            r.orgLe.put(o.id(), mainLe);
            AdeskLink l = L.get(o.id());
            String h = o.id().equals(main.id()) ? null : LE_FALLBACK;
            if (!linked(l) || !mainLe.equals(l.getAdeskId()) || !Objects.equals(h, l.getHash())) ok(r, ORG, o.id(), mainLe, o.name(), h);
        }
        r.stockLe = mainLe;
    }

    void orgs(AdeskRun r) {
        List<AdLegal> les = ad.legalEntities();
        Map<Long, AdLegal> byId = new HashMap<>();
        les.forEach(x -> byId.put(x.id(), x));
        Map<String, AdeskLink> L = links(r, ORG);
        if (cfg.singleLe()) { orgsSingle(r, les, byId, L); return; }
        Set<Long> used = usedIds(L);
        // asosiy (001) tashkilot birinchi — boshqa firmalar zarurat bo'lsa uning yuridik shaxsiga biriktiriladi
        List<MsOrg> ordered = new ArrayList<>(r.orgs);
        ordered.sort(Comparator.comparing((MsOrg o) -> !o.name().startsWith("001")));
        boolean noted = false;
        for (MsOrg o : ordered) {
            AdeskLink l = L.get(o.id());
            boolean fb = l != null && LE_FALLBACK.equals(l.getHash()) && linked(l);
            if (linked(l) && byId.containsKey(l.getAdeskId()) && !(fb && r.full)) { r.orgLe.put(o.id(), l.getAdeskId()); continue; }
            AdLegal m = fb ? null : les.stream().filter(x -> !used.contains(x.id()) && leMatches(x, o)).findFirst().orElse(null);
            try {
                if (m == null) { m = ad.createLegalEntity(o.name(), o.legalTitle(), o.inn()); r.inc("le.created"); }
                else r.inc("le.linked");
                used.add(m.id());
                ok(r, ORG, o.id(), m.id(), o.name(), null);
                r.orgLe.put(o.id(), m.id());
            } catch (AdeskException e) {
                if (e.fatal) throw e;
                // Adesk tarifi yuridik shaxslar sonini cheklaydi («Бизнес» — 3 ta; 2026-10-06 haqiqiy akkauntda 4-chisi
                // yaratilmadi va 11 firmaning hisob/operatsiyalari o'tmay qoldi). Firma asosiy yuridik shaxsga
                // biriktiriladi (user Adesk'ni o'zi ham shunday — hamma hisob bitta yuridik shaxsda — ochgan edi);
                // har to'liq sinxronda alohida yaratish qayta uriniladi (tarif oshirilsa firma o'z yuridik shaxsiga ko'chadi).
                if (fb) { r.orgLe.put(o.id(), l.getAdeskId()); continue; }
                Long def = mainLe(r, les);
                if (def == null) { err(r, ORG, o.id(), o.name(), e.getMessage()); continue; }
                ok(r, ORG, o.id(), def, o.name(), LE_FALLBACK);
                r.orgLe.put(o.id(), def);
                r.inc("le.fallback");
                if (!noted) {
                    String dn = byId.containsKey(def) ? byId.get(def).name() : String.valueOf(def);
                    r.note("Adesk yangi yuridik shaxs qo'shmadi (" + e.getMessage() + ") — bunday firmalar «" + dn + "» ga biriktirildi");
                    noted = true;
                }
            }
        }
        String want = cfg.stockOrg();
        String pick = r.orgLe.containsKey(want) ? want : r.orgs.stream().filter(o -> o.name().startsWith("001"))
                .map(MsOrg::id).filter(r.orgLe::containsKey).findFirst()
                .orElse(r.orgs.stream().map(MsOrg::id).filter(r.orgLe::containsKey).findFirst().orElse(null));
        r.stockLe = pick == null ? null : r.orgLe.get(pick);
    }

    /** Asosiy yuridik shaxs: 001 tashkilotiniki, bo'lmasa Adesk'dagi birinchisi. */
    private static Long mainLe(AdeskRun r, List<AdLegal> les) {
        for (MsOrg o : r.orgs) if (o.name().startsWith("001") && r.orgLe.containsKey(o.id())) return r.orgLe.get(o.id());
        return les.isEmpty() ? null : les.get(0).id();
    }

    /**
     * Adesk yuridik shaxsi MoySklad tashkilotigami: INN teng, yoki nom teng (to'liq/qisqa), yoki Adesk nomi MoySklad
     * nomi ichida bor («New Star Bukhara» ⊂ «001 NSB New Star Bukhara МЧЖ 302037932»; kamida 8 belgi — tasodifiy moslik bo'lmasin).
     */
    static boolean leMatches(AdLegal x, MsOrg o) {
        if (!digits(o.inn()).isEmpty() && digits(o.inn()).equals(digits(x.inn()))) return true;
        String n = norm(x.name());
        if (n.isEmpty()) return false;
        return n.equals(norm(o.name())) || n.equals(norm(o.shortName())) || (n.length() >= 8 && norm(o.name()).contains(n));
    }

    /* ==================== 2. hisoblar ==================== */

    void accounts(AdeskRun r) {
        List<MsAccount> accs = msr.accounts(r.orgs);
        Map<String, MsOrg> orgById = new HashMap<>();
        r.orgs.forEach(o -> orgById.put(o.id(), o));
        Map<String, Integer> nameCnt = new HashMap<>();
        for (MsAccount a : accs) if (!a.cash() || a.accountId() != null) nameCnt.merge(norm(a.rawName()), 1, Integer::sum);
        List<AdAccount> ads = ad.bankAccounts();
        Map<Long, AdAccount> byId = new HashMap<>();
        ads.forEach(x -> byId.put(x.id(), x));
        Map<String, AdeskLink> L = links(r, ACCOUNT);
        Set<Long> used = usedIds(L);
        Map<String, Long> opening = null;
        LocalDate od = cfg.openingDate();

        for (MsAccount a : accs) {
            if (r.stopped()) return;
            MsOrg org = orgById.get(a.orgId());
            String name = accountName(a, org, nameCnt);
            int type = a.cash() ? 1 : 2;
            Long le = r.orgLe.get(a.orgId());
            if (le == null) { err(r, ACCOUNT, a.key(), name, "yuridik shaxs Adesk'da yo'q"); continue; }
            AdeskLink l = L.get(a.key());
            AdAccount cur = linked(l) ? byId.get(l.getAdeskId()) : null;
            try {
                if (cur == null) {
                    if (opening == null) opening = openings(r);
                    long want = opening.getOrDefault(a.key(), 0L);
                    AdAccount m = ads.stream().filter(x -> !used.contains(x.id()) && (norm(x.name()).equals(norm(name))
                            || (norm(x.name()).equals(norm(a.rawName())) && Objects.equals(x.legalEntityId(), le)))).findFirst().orElse(null);
                    if (m == null) {
                        m = ad.createBankAccount(name, cfg.currency(), le, type, a.number(), a.bankName(), som(want), od);
                        r.inc("acc.created");
                    } else {
                        r.inc("acc.linked");
                        if (tiyin(m.initialAmount()) != want || !od.equals(m.initialDate())) {
                            ad.updateBankAccount(m.id(), m.name(), le, type, som(want), od);
                            r.inc("acc.opening");
                        }
                    }
                    used.add(m.id());
                    AdeskLink s = ok(r, ACCOUNT, a.key(), m.id(), name, String.valueOf(want));
                    s.setAccountKey(a.key());
                    repo.save(s);
                    cur = m;
                } else {
                    // Bog'langan hisob: nomi MoySklad nomiga, yuridik shaxsi tashkilotiga moslanadi (user qarori 2026-10-05),
                    // to'liq yurishda boshlang'ich qoldiq tekshiriladi. Turi (naqd/bank) Adesk'dagicha qoladi.
                    boolean rename = !norm(cur.name()).equals(norm(name)) || (cur.legalEntityId() != null && !cur.legalEntityId().equals(le));
                    if (rename || r.full) {
                        if (opening == null) opening = openings(r);
                        long want = opening.getOrDefault(a.key(), 0L);
                        boolean openDiff = tiyin(cur.initialAmount()) != want || !od.equals(cur.initialDate());
                        if (rename || openDiff) {
                            ad.updateBankAccount(cur.id(), name, le, adType(cur, type), som(want), od);
                            if (rename) { r.inc("acc.renamed"); r.note("Hisob: «" + cur.name() + "» → «" + name + "»"); }
                            if (openDiff) r.inc("acc.opening");
                            l.setHash(String.valueOf(want));
                            l.setName(name);
                            save(r, l);
                        }
                    }
                }
                r.account.put(a.key(), cur.id());
                r.accountByAd.put(cur.id(), a.key());
            } catch (AdeskException e) {
                if (e.fatal) throw e;
                err(r, ACCOUNT, a.key(), name, e.getMessage());
            }
        }
    }

    /** Adesk hisobining joriy turi: 1 — naqd, 2 — bank («Cash»/«Bank» yoki «1»/«2» keladi); bo'lmasa MoySklad'dagi. */
    static int adType(AdAccount a, int fallback) {
        String t = a.type() == null ? "" : a.type().trim();
        if (t.equalsIgnoreCase("cash") || t.equals("1")) return 1;
        if (t.equalsIgnoreCase("bank") || t.equals("2")) return 2;
        return fallback;
    }

    /** Hisob nomi: MoySklad'dagi nom; bir necha tashkilotda takrorlansa (masalan «р/с») — « · <tashkilot>». */
    static String accountName(MsAccount a, MsOrg org, Map<String, Integer> nameCnt) {
        String sh = org == null ? "" : org.shortName();
        if (a.accountId() == null) return "Касса · " + sh;
        boolean dup = nameCnt.getOrDefault(norm(a.rawName()), 0) > 1;
        return dup ? a.rawName() + " · " + sh : a.rawName();
    }

    /** Davr boshidagi qoldiq har hisob uchun: joriy qoldiq − (davr boshidan bugungacha harakat). TIYIN. */
    private Map<String, Long> openings(AdeskRun r) {
        if (r.balances == null || r.allMoney == null) snapshot(r);
        Map<String, Long> out = new HashMap<>(r.balances);
        for (MsMoneyDoc d : r.allMoney) if (d.applicable()) out.merge(d.accountKey(), -d.signedTiyin(), Long::sum);
        return out;
    }

    /** Qoldiq va hujjatlarni bir suratda o'qish (oradagi yangi hujjat boshlang'ich qoldiqni siljitmasin). */
    private void snapshot(AdeskRun r) {
        AdeskMsReader.MoneySnap s = msr.moneySnapshot(cfg.start(), cfg.today(), r.currencies);
        r.allMoney = s.docs();
        r.balances = s.balances();
        if (!s.stable()) r.note("MoySklad'da pul harakati to'xtamadi — boshlang'ich qoldiq keyingi to'liq sinxronda qayta tekshiriladi");
    }

    /* ==================== 3. statyalar ==================== */

    void categories(AdeskRun r) {
        List<AdCategory> cats = ad.categories();
        Map<Long, AdCategory> byId = new HashMap<>();
        cats.forEach(c -> byId.put(c.id(), c));
        Map<String, AdeskLink> L = links(r, CATEGORY);
        Set<String> outNames = new LinkedHashSet<>(r.expenseItems.values());
        outNames.add(OTHER_EXPENSE);
        outNames.add(cfg.catTransfer());
        for (String n : outNames) category(r, 2, n, cats, byId, L);
        category(r, 1, cfg.catIncome(), cats, byId, L);
        category(r, 1, cfg.catTransfer(), cats, byId, L);
    }

    private Long category(AdeskRun r, int type, String name, List<AdCategory> cats, Map<Long, AdCategory> byId, Map<String, AdeskLink> L) {
        String key = (type == 1 ? "in:" : "out:") + name;
        Map<String, Long> target = type == 1 ? r.catIn : r.catOut;
        AdeskLink l = L.get(key);
        if (linked(l) && (byId == null || byId.containsKey(l.getAdeskId()))) { target.put(name, l.getAdeskId()); return l.getAdeskId(); }
        try {
            AdCategory m = cats == null ? null : cats.stream()
                    .filter(c -> c.type() == type && norm(c.name()).equals(norm(name)))
                    .min(Comparator.comparing(AdCategory::archived)).orElse(null);
            boolean owner = norm(name).equals(norm(cfg.catTransfer()));
            if (m == null) { m = ad.createCategory(name, type, owner ? 3 : kindOf(name), owner); r.inc("cat.created"); }
            else r.inc("cat.linked");
            ok(r, CATEGORY, key, m.id(), name, null);
            target.put(name, m.id());
            return m.id();
        } catch (AdeskException e) {
            if (e.fatal) throw e;
            err(r, CATEGORY, key, name, e.getMessage());
            return null;
        }
    }

    /** Statya turi (Adesk «вид деятельности»): 2 — investitsion, 3 — moliyaviy, 1 — operatsion. */
    static int kindOf(String name) {
        String n = name.toLowerCase();
        if (n.contains("основных средств") || n.contains("нма")) return 2;
        if (n.contains("кредит") || n.contains("займ") || n.contains("дивиденд") || n.contains("вывод прибыли")) return 3;
        return 1;
    }

    /* ==================== 4. kontragentlar ==================== */

    void contractors(AdeskRun r) {
        Map<String, AdeskLink> L = links(r, CONTRACTOR);
        LocalDateTime cur = cursor("contractor");
        boolean all = r.full || cur == null || L.isEmpty();
        List<MsCounterparty> cps = msr.counterparties(all ? null : cur.minusMinutes(OVERLAP_MIN));
        Map<String, Deque<Long>> byName = null;
        if (all) {
            Set<Long> used = new HashSet<>(usedIds(L));
            used.addAll(usedIds(links(r, EMPLOYEE)));
            used.addAll(usedIds(links(r, ORGC)));
            byName = new HashMap<>();
            for (AdContractor c : ad.contractors())
                if (!used.contains(c.id())) byName.computeIfAbsent(norm(c.name()), k -> new ArrayDeque<>()).add(c.id());
        }
        int i = 0;
        for (MsCounterparty cp : cps) {
            if (r.stopped()) return;
            r.progress = (++i) + "/" + cps.size();
            String hash = sha(cp.name(), cp.phone(), cp.email(), cp.inn(), cp.code());
            AdeskLink l = L.get(cp.id());
            try {
                if (linked(l)) {
                    if (!hash.equals(l.getHash()) || ERROR.equals(l.getStatus())) {
                        ad.updateContractor(l.getAdeskId(), cp.name(), cp.phone(), cp.email(), cpDesc(cp));
                        l.setHash(hash); l.setName(cut(cp.name())); l.setStatus(OK); l.setError(null);
                        save(r, l);
                        r.inc("ct.updated");
                    }
                    continue;
                }
                Deque<Long> q = byName == null ? null : byName.get(norm(cp.name()));
                Long id = q == null ? null : q.poll();
                if (id == null) { id = ad.createContractor(cp.name(), cp.phone(), cp.email(), cpDesc(cp)).id(); r.inc("ct.created"); }
                else r.inc("ct.linked");
                ok(r, CONTRACTOR, cp.id(), id, cp.name(), hash);
            } catch (AdeskException e) {
                if (e.fatal) throw e;
                err(r, CONTRACTOR, cp.id(), cp.name(), e.getMessage());
                r.inc("ct.error");
            }
        }
        if (!r.stopped()) setCursor("contractor", r.startedAt);
    }

    private static String cpDesc(MsCounterparty cp) {
        StringBuilder sb = new StringBuilder("MoySklad");
        if (!cp.inn().isBlank()) sb.append(" · ИНН ").append(cp.inn());
        if (!cp.code().isBlank()) sb.append(" · код ").append(cp.code());
        if (!cp.address().isBlank()) sb.append(" · манзил: ").append(cp.address());
        if (!cp.comment().isBlank()) sb.append(" · ").append(cp.comment().replaceAll("\s+", " "));
        return AdeskClient.cut(sb.toString(), 510);
    }

    /**
     * Hujjat agenti → Adesk kontragenti (kerak bo'lsa yaratiladi). null — kontragent yo'q:
     * agent hujjat egasi bo'lgan tashkilotning o'zi (MoySklad'da «kontragentsiz» chiqim shunday yoziladi).
     */
    Long contractorFor(AdeskRun r, String agentType, String agentId, String docOrgId) {
        return contractorFor(r, agentType, agentId, docOrgId, false);
    }

    /** allowSelf — agent hujjat egasining o'zi bo'lsa ham kontragent yaratiladi (majburiyat kontragentsiz bo'lmaydi). */
    Long contractorFor(AdeskRun r, String agentType, String agentId, String docOrgId, boolean allowSelf) {
        if (agentId == null || agentId.isBlank()) return null;
        switch (agentType) {
            case "counterparty" -> {
                AdeskLink l = links(r, CONTRACTOR).get(agentId);
                if (linked(l)) return l.getAdeskId();
                MsCounterparty cp = msr.counterparty(agentId);
                if (cp == null) throw new IllegalStateException("kontragent MoySklad'da topilmadi");
                long id = ad.createContractor(cp.name(), cp.phone(), cp.email(), cpDesc(cp)).id();
                ok(r, CONTRACTOR, cp.id(), id, cp.name(), sha(cp.name(), cp.phone(), cp.email(), cp.inn(), cp.code()));
                r.inc("ct.created");
                return id;
            }
            case "employee" -> {
                AdeskLink l = links(r, EMPLOYEE).get(agentId);
                if (linked(l)) return l.getAdeskId();
                String name = r.employees.getOrDefault(agentId, "Ходим");
                long id = ad.createContractor(name, "", "", "MoySklad ходими").id();
                ok(r, EMPLOYEE, agentId, id, name, null);
                r.inc("ct.created");
                return id;
            }
            case "organization" -> {
                if (agentId.equals(docOrgId) && !allowSelf) return null;
                AdeskLink l = links(r, ORGC).get(agentId);
                if (linked(l)) return l.getAdeskId();
                String name = r.orgs.stream().filter(o -> o.id().equals(agentId)).map(MsOrg::name).findFirst().orElse("Ташкилот");
                long id = ad.createContractor(name, "", "", "Ўз юридик шахсимиз (MoySklad)").id();
                ok(r, ORGC, agentId, id, name, null);
                r.inc("ct.created");
                return id;
            }
            default -> { return null; }
        }
    }

    /* ==================== 5. tovar va xizmatlar ==================== */

    /** Bosqich ichida dangasa yuklanadigan narsalar. */
    private static final class ProdCtx {
        List<AdUnit> units;
        Map<String, String> uoms;
        Map<String, MsStock> stock;
        int negative;
    }

    void products(AdeskRun r) {
        Map<String, AdeskLink> L = links(r, PRODUCT);
        LocalDateTime cur = cursor("product");
        boolean all = r.full || cur == null || L.isEmpty();
        List<MsProduct> ps = msr.products(all ? null : cur.minusMinutes(OVERLAP_MIN));
        ProdCtx pc = new ProdCtx();
        Map<String, Deque<Long>> bySku = null, byName = null;
        if (all && ps.stream().anyMatch(p -> !linked(L.get(p.id())))) {
            Set<Long> used = usedIds(L);
            bySku = new HashMap<>();
            byName = new HashMap<>();
            for (AdProduct x : ad.products()) {
                if (used.contains(x.id())) continue;
                if (!x.sku().isBlank()) bySku.computeIfAbsent(norm(x.sku()), k -> new ArrayDeque<>()).add(x.id());
                byName.computeIfAbsent(norm(x.name()), k -> new ArrayDeque<>()).add(x.id());
            }
        }
        int i = 0;
        for (MsProduct p : ps) {
            if (r.stopped()) return;
            r.progress = (++i) + "/" + ps.size();
            String hash = sha(p.type(), p.name(), sku(p), p.uomId());
            AdeskLink l = L.get(p.id());
            try {
                if (linked(l)) {
                    if (!hash.equals(l.getHash()) || ERROR.equals(l.getStatus())) {
                        Map<String, String> u = new LinkedHashMap<>();
                        u.put("name", AdeskClient.cut(p.name(), 250));
                        u.put("sku", AdeskClient.cut(sku(p), 250));
                        ad.updateProduct(l.getAdeskId(), u);
                        l.setHash(hash); l.setName(cut(p.name())); l.setStatus(OK); l.setError(null);
                        save(r, l);
                        r.inc("pr.updated");
                    }
                    continue;
                }
                Long id = null;
                if (bySku != null && !sku(p).isBlank()) { Deque<Long> q = bySku.get(norm(sku(p))); if (q != null) id = q.poll(); }
                if (id == null && byName != null) { Deque<Long> q = byName.get(norm(p.name())); if (q != null) id = q.poll(); }
                if (id != null) {
                    r.inc("pr.linked");
                    if (stockOf(pc, p) != null) { r.inc("pr.batchMissed"); }
                } else id = createProduct(r, pc, p);
                ok(r, PRODUCT, p.id(), id, p.name(), hash);
            } catch (AdeskException e) {
                if (e.fatal) throw e;
                err(r, PRODUCT, p.id(), p.name(), e.getMessage());
                r.inc("pr.error");
            }
        }
        if (pc.negative > 0) r.note("MoySklad'da davr boshida manfiy qoldiqli tovarlar: " + pc.negative + " ta — Adesk'ka boshlang'ich partiyasiz o'tdi");
        if (r.get("pr.batchMissed") > 0) r.note("Adesk'da oldindan bor " + r.get("pr.batchMissed") + " ta tovarga boshlang'ich partiya qo'yilmadi (API faqat yaratishda qabul qiladi)");
        if (!r.stopped()) setCursor("product", r.startedAt);
    }

    private long createProduct(AdeskRun r, ProdCtx pc, MsProduct p) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("type", p.service() ? "2" : "1");
        m.put("name", AdeskClient.cut(p.name(), 250));
        if (!sku(p).isBlank()) m.put("sku", AdeskClient.cut(sku(p), 250));
        String sup = p.supplierId() == null || p.supplierId().isBlank() ? null
                : Optional.ofNullable(links(r, CONTRACTOR).get(p.supplierId())).map(AdeskLink::getName).orElse(null);
        m.put("description", AdeskClient.cut("MoySklad" + (p.code().isBlank() ? "" : " · код " + p.code())
                + (sup == null ? "" : " · етказиб берувчи: " + sup), 500));
        if (!p.service()) {
            if (pc.units == null) pc.units = ad.units();
            if (pc.uoms == null) pc.uoms = msr.uoms();
            String uom = pc.uoms.getOrDefault(p.uomId(), "Штука");
            if (uom.isBlank()) uom = "Штука";
            final String un = uom;
            AdUnit u = pc.units.stream().filter(x -> norm(x.name()).equals(norm(un)) || norm(x.symbol()).equals(norm(un))
                    || norm(x.symbol()).equals(norm(unitSymbol(un)))).findFirst().orElse(null);
            if (u != null) m.put("unit", String.valueOf(u.id()));
            else { m.put("unit_name", AdeskClient.cut(un, 100)); m.put("unit_symbol", unitSymbol(un)); }
            MsStock st = stockOf(pc, p);
            if (st != null && r.stockLe != null) {
                m.put("with_initial_batch", "true");
                m.put("initial_batch_date", cfg.openingDate().toString());
                m.put("initial_batch_quantity", qty(st.qty()));
                m.put("initial_batch_price", som(st.costTiyin()).toPlainString());
                m.put("initial_batch_currency", cfg.currency());
                m.put("initial_batch_legal_entity", String.valueOf(r.stockLe));
                r.inc("pr.batch");
            }
        }
        long id = ad.createProduct(m).id();
        r.inc("pr.created");
        return id;
    }

    private MsStock stockOf(ProdCtx pc, MsProduct p) {
        if (p.service()) return null;
        if (pc.stock == null) {
            int[] neg = new int[1];
            pc.stock = new HashMap<>();
            for (MsStock s : msr.stockAt(cfg.start(), neg)) pc.stock.put(s.productId(), s);
            pc.negative = neg[0];
        }
        return pc.stock.get(p.id());
    }

    /** Pozitsiyadagi tovar → Adesk id (bog'lanmagan bo'lsa MoySklad'dan o'qib yaratiladi). */
    private Long productFor(AdeskRun r, ProdCtx pc, String type, String id) {
        AdeskLink l = links(r, PRODUCT).get(id);
        if (linked(l)) return l.getAdeskId();
        MsProduct p = msr.product(type, id);
        if (p == null) throw new IllegalStateException("tovar MoySklad'da topilmadi");
        long aid = createProduct(r, pc, p);
        ok(r, PRODUCT, p.id(), aid, p.name(), sha(p.type(), p.name(), sku(p), p.uomId()));
        return aid;
    }

    private static String sku(MsProduct p) { return !p.article().isBlank() ? p.article() : p.code(); }

    static String unitSymbol(String uom) {
        return switch (uom.trim().toLowerCase()) {
            case "штука", "шт" -> "шт";
            case "килограмм", "кг" -> "кг";
            case "грамм", "г" -> "г";
            case "метр", "м" -> "м";
            case "литр", "л" -> "л";
            case "комплект" -> "компл";
            case "упаковка" -> "упак";
            case "пара" -> "пар";
            case "рулон" -> "рул";
            case "метр квадратный", "квадратный метр" -> "м2";
            default -> AdeskClient.cut(uom, 10);
        };
    }

    /* ==================== 6. pul hujjatlari → operatsiyalar ==================== */

    /** Bitta MoySklad hujjati uchun Adesk operatsiyasi (kerakli holat). */
    record TxWant(MsMoneyDoc d, ObjectNode node, String hash, long acc, Long cat, Long ctr, boolean origAd) {
        String importedId() { return "ms:" + d.id(); }
    }

    void money(AdeskRun r) {
        LocalDate from = cfg.start(), to = cfg.effectiveEnd();
        if (to.isBefore(from)) return;
        Map<String, AdeskLink> L = links(r, MONEY);
        LocalDateTime cur = cursor("money");
        boolean all = r.full || cur == null || L.isEmpty();
        List<MsMoneyDoc> docs;
        if (all) {
            if (r.allMoney == null) r.allMoney = msr.moneyDocs(from, cfg.today(), null, r.currencies);
            docs = r.allMoney.stream().filter(d -> !d.date().isBefore(from) && !d.date().isAfter(to)).toList();
        } else docs = msr.moneyDocs(from, to, cur.minusMinutes(OVERLAP_MIN), r.currencies);

        Map<String, TxWant> wants = new LinkedHashMap<>();
        List<TxWant> create = new ArrayList<>(), update = new ArrayList<>();
        List<AdeskLink> remove = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (MsMoneyDoc d : docs) {
            if (r.stopped()) return;
            r.progress = "tayyorlanmoqda " + (++i) + "/" + docs.size();
            seen.add(d.id());
            AdeskLink l = L.get(d.id());
            if (!d.applicable() || d.sumTiyin() <= 0) {
                if (linked(l)) remove.add(l);
                else if (l != null && ERROR.equals(l.getStatus())) { l.setStatus(SKIP); save(r, l); }   // xatoli edi, endi o'tkazilmagan — ro'yxatdan chiqadi
                continue;
            }
            TxWant w;
            try { w = want(r, d, l); }
            catch (AdeskException e) { if (e.fatal) throw e; moneyErr(r, d, l, e.getMessage()); continue; }
            catch (Exception e) { moneyErr(r, d, l, e.getMessage()); continue; }
            wants.put(d.id(), w);
            if (!linked(l)) create.add(w);
            else if (!w.hash().equals(l.getHash()) || ERROR.equals(l.getStatus())) update.add(w);
        }
        if (all) for (AdeskLink l : L.values())
            if (linked(l) && !seen.contains(l.getMsKey())) remove.add(l);

        r.progress = "o'chirish " + remove.size();
        removeTx(r, remove);
        r.progress = "yaratish " + create.size();
        createTx(r, create);
        r.progress = "yangilash " + update.size();
        updateTx(r, update);
        if (all && !r.stopped()) restore(r, from, to, wants);
        if (!r.stopped()) setCursor("money", r.startedAt);
    }

    private TxWant want(AdeskRun r, MsMoneyDoc d, AdeskLink l) {
        Long acc = r.account.get(d.accountKey());
        if (acc == null) throw new IllegalStateException("hisob Adesk'da bog'lanmagan (" + d.accountKey() + ")");
        String expense = d.income() ? null : r.expenseItems.getOrDefault(d.expenseItemId(), OTHER_EXPENSE);
        if (expense != null && expense.isBlank()) expense = OTHER_EXPENSE;
        // Chiqim «Перемещение» faqat qabul qiluvchi o'z firmamiz bo'lsa (garov, qarz, kontragentga berilgan pul — o'tkazma emas)
        if (expense != null && norm(expense).equals(norm(cfg.catTransfer())) && !"organization".equals(d.agentType())) expense = OTHER_EXPENSE;
        boolean transfer = d.income()
                ? d.incomeTransfer()
                : norm(expense).equals(norm(cfg.catTransfer()));
        String catName = d.income() ? (transfer ? cfg.catTransfer() : cfg.catIncome()) : expense;
        Map<String, Long> cats = d.income() ? r.catIn : r.catOut;
        Long cat = cats.get(catName);
        if (cat == null) cat = category(r, d.income() ? 1 : 2, catName, ad.categories(), null, links(r, CATEGORY));
        Long ctr = transfer ? null : contractorFor(r, d.agentType(), d.agentId(), d.orgId());
        boolean origAd = l != null && FROM_AD.equals(l.getOrigin());
        String amount = som(d.sumTiyin()).toPlainString();
        String desc = txDesc(d);

        ObjectNode n = JN.objectNode();
        n.put("type", d.income() ? "income" : "outcome");
        n.put("bankAccountId", acc);
        n.put("amount", amount);
        n.put("date", d.date().toString());
        n.put("description", desc);
        if (cat != null) n.put("categoryId", cat); else n.putNull("categoryId");
        if (ctr != null) n.put("contractorId", ctr); else n.putNull("contractorId");
        n.put("isCommitment", ctr != null);
        n.put("importedId", "ms:" + d.id());
        if (r.projectId != null) n.put("projectId", r.projectId); else n.putNull("projectId");
        String hash = origAd ? sha("AD", String.valueOf(acc), amount, d.date().toString(), String.valueOf(r.projectId))
                : sha(n.path("type").asText(), String.valueOf(acc), amount, d.date().toString(), desc, String.valueOf(cat), String.valueOf(ctr), String.valueOf(r.projectId));
        return new TxWant(d, n, hash, acc, cat, ctr, origAd);
    }

    /** Izoh: MoySklad izohi (bo'lmasa to'lov maqsadi) + hujjat turi va raqami (+ valyuta). Max 510. */
    static String txDesc(MsMoneyDoc d) {
        String base = !d.description().isBlank() ? d.description() : d.purpose();
        String ref = "MS " + ruName(d.entity()) + " №" + d.number();
        if (!d.currencyIso().isBlank())
            ref += " · " + som(d.origTiyin()).stripTrailingZeros().toPlainString() + " " + d.currencyIso() + " × " + BigDecimal.valueOf(d.rate()).stripTrailingZeros().toPlainString();
        String s = base.isBlank() ? ref : base.replaceAll("\\s+", " ") + " · " + ref;
        return s.length() > 510 ? s.substring(0, 507) + "…" : s;
    }

    static String ruName(String entity) {
        return switch (entity) {
            case "cashin" -> "Приходный ордер";
            case "cashout" -> "Расходный ордер";
            case "paymentin" -> "Входящий платёж";
            case "paymentout" -> "Исходящий платёж";
            case "supply" -> "Приёмка";
            case "demand" -> "Отгрузка";
            case "salesreturn" -> "Возврат покупателя";
            case "purchasereturn" -> "Возврат поставщику";
            default -> entity;
        };
    }

    private void createTx(AdeskRun r, List<TxWant> list) { createTx(r, list, "tx.created"); }

    private void createTx(AdeskRun r, List<TxWant> list, String counter) {
        for (int i = 0; i < list.size(); i += BATCH) {
            if (r.stopped()) return;
            List<TxWant> chunk = list.subList(i, Math.min(list.size(), i + BATCH));
            r.progress = "yaratish " + Math.min(list.size(), i + BATCH) + "/" + list.size();
            try {
                Map<String, Long> ids = ad.createTransactions(chunk.stream().map(TxWant::node).toList());
                for (TxWant w : chunk) {
                    Long id = ids.get(w.importedId());
                    if (id == null || id == 0) id = existingTx(r, w);   // Adesk importedId takror bo'lsa yangisini yaratmay javobdan tushirib qoldiradi
                    if (id == null || id == 0) moneyErr(r, w.d(), links(r, MONEY).get(w.d().id()), "Adesk javobida operatsiya yo'q");
                    else { moneyOk(r, w, id); r.inc(counter); }
                }
            } catch (AdeskException e) {
                if (e.fatal) throw e;
                if (chunk.size() == 1) { moneyErr(r, chunk.get(0).d(), links(r, MONEY).get(chunk.get(0).d().id()), e.getMessage()); continue; }
                for (TxWant w : chunk) createTx(r, List.of(w), counter);   // xatoli hujjatni ajratish
            }
        }
    }

    /**
     * Adesk'da allaqachon bor operatsiya — bog'lanishi yo'qolgan bo'lsa qayta bog'lanadi, dublikat yaratilmaydi.
     * Adesk v1 ro'yxati importedId'ni qaytarmaydi, shuning uchun izoh («MS Приходный ордер №07318 …») + hisob + summa + sana bo'yicha.
     */
    private Long existingTx(AdeskRun r, TxWant w) {
        if (r.existingTx == null) {
            r.existingTx = new HashMap<>();
            LocalDate to = cfg.effectiveEnd().isBefore(cfg.today()) ? cfg.today() : cfg.effectiveEnd();
            for (AdTx t : ad.transactions(cfg.start().minusDays(1), to))
                r.existingTx.putIfAbsent(txKey(t.description(), t.accountId(), tiyin(t.amount()), t.date()), t.id());
        }
        Long id = r.existingTx.get(txKey(w.node().path("description").asText(""), w.acc(), w.d().sumTiyin(), w.d().date()));
        if (id != null) r.inc("tx.relinked");
        return id;
    }

    private static String txKey(String desc, Long acc, long tiyin, LocalDate date) {
        return (desc == null ? "" : desc.trim()) + "|" + acc + "|" + tiyin + "|" + date;
    }

    private void updateTx(AdeskRun r, List<TxWant> list) { updateTx(r, list, "tx.updated"); }

    private void updateTx(AdeskRun r, List<TxWant> list, String counter) {
        for (int i = 0; i < list.size(); i += BATCH) {
            if (r.stopped()) return;
            List<TxWant> chunk = list.subList(i, Math.min(list.size(), i + BATCH));
            try {
                ad.updateTransactions(chunk.stream().map(w -> updNode(w, links(r, MONEY).get(w.d().id()).getAdeskId())).toList());
                for (TxWant w : chunk) { moneyOk(r, w, links(r, MONEY).get(w.d().id()).getAdeskId()); r.inc(counter); }
            } catch (AdeskException e) {
                if (e.fatal) throw e;
                if (chunk.size() == 1) { moneyErr(r, chunk.get(0).d(), links(r, MONEY).get(chunk.get(0).d().id()), e.getMessage()); continue; }
                for (TxWant w : chunk) updateTx(r, List.of(w), counter);
            }
        }
    }

    /** Yangilash tanasi. Adesk'da kiritilgan (origin AD) operatsiyada faqat summa/sana/hisob — statya, kontragent, izoh Adesk'dagicha qoladi. */
    private static ObjectNode updNode(TxWant w, long id) {
        ObjectNode n = w.node().deepCopy();
        n.remove("importedId");
        if (w.origAd()) { n.remove("description"); n.remove("categoryId"); n.remove("contractorId"); n.remove("isCommitment"); n.remove("projectId"); }
        n.put("id", id);
        return n;
    }

    private void removeTx(AdeskRun r, List<AdeskLink> list) {
        for (int i = 0; i < list.size(); i += BATCH) {
            if (r.stopped()) return;
            List<AdeskLink> chunk = list.subList(i, Math.min(list.size(), i + BATCH));
            try {
                ad.removeTransactions(chunk.stream().map(AdeskLink::getAdeskId).toList());
                for (AdeskLink l : chunk) { l.setStatus(DELETED); l.setError(null); save(r, l); r.inc("tx.removed"); }
            } catch (AdeskException e) {
                if (e.fatal) throw e;
                if (chunk.size() == 1) {   // Adesk'da allaqachon yo'q — bog'lanish baribir yopiladi
                    AdeskLink l = chunk.get(0);
                    l.setStatus(DELETED); l.setError("Adesk: " + e.getMessage()); save(r, l);
                    continue;
                }
                for (AdeskLink l : chunk) removeTx(r, List.of(l));
            }
        }
    }

    /**
     * To'liq yurishda Adesk tomonini tekshirish: bog'langan operatsiya Adesk'da o'chirilgan bo'lsa — qayta yaratiladi,
     * o'zgartirilgan bo'lsa (summa/sana/hisob/statya/kontragent) — MoySklad holatiga qaytariladi.
     * Bog'lanmagan (Adesk'da qo'lda kiritilgan) operatsiyalar {@link AdeskReverseService} ga beriladi.
     */
    private void restore(AdeskRun r, LocalDate from, LocalDate to, Map<String, TxWant> wants) {
        r.progress = "Adesk tomoni tekshirilmoqda";
        List<AdTx> txs = ad.transactions(from, to);
        Map<Long, AdTx> byId = new HashMap<>();
        txs.forEach(t -> byId.put(t.id(), t));
        Map<String, AdeskLink> L = links(r, MONEY);
        List<TxWant> recreate = new ArrayList<>(), fix = new ArrayList<>();
        for (TxWant w : wants.values()) {
            AdeskLink l = L.get(w.d().id());
            if (!linked(l) || ERROR.equals(l.getStatus())) continue;
            if (l.getUpdatedAt() != null && !l.getUpdatedAt().isBefore(r.startedInstant)) continue;   // shu yurishda yozilgan — ishonchli
            AdTx t = byId.get(l.getAdeskId());
            if (t == null) recreate.add(w);
            else if (differs(t, w)) fix.add(w);
        }
        if (recreate.size() > 20 && recreate.size() * 10 > wants.size()) {   // ro'yxat chala o'qilgan bo'lishi mumkin — dublikat xavfi
            r.note("Adesk operatsiyalar ro'yxatida " + recreate.size() + " ta bog'langan operatsiya ko'rinmadi — avtomatik tiklash o'tkazib yuborildi");
            recreate.clear();
        }
        for (TxWant w : recreate) { L.get(w.d().id()).setAdeskId(null); }
        createTx(r, recreate, "tx.restored");
        updateTx(r, fix, "tx.fixed");
        Set<Long> linkedIds = new HashSet<>();
        for (AdeskLink l : L.values()) if (linked(l)) linkedIds.add(l.getAdeskId());
        List<AdTx> manual = txs.stream().filter(t -> !t.planned() && !linkedIds.contains(t.id())).toList();
        if (!r.stopped()) reverse.handle(r, manual);
    }

    private static boolean differs(AdTx t, TxWant w) {
        if (t.type() != (w.d().income() ? 1 : 2)) return true;
        if (!Objects.equals(t.accountId(), w.acc())) return true;
        if (tiyin(t.amount()) != w.d().sumTiyin()) return true;
        if (!Objects.equals(t.date(), w.d().date())) return true;
        if (w.origAd()) return false;
        Long wp = w.node().hasNonNull("projectId") ? w.node().path("projectId").asLong() : null;
        return !Objects.equals(t.categoryId(), w.cat()) || !Objects.equals(t.contractorId(), w.ctr()) || !Objects.equals(t.projectId(), wp);
    }

    private void moneyOk(AdeskRun r, TxWant w, long adeskId) {
        MsMoneyDoc d = w.d();
        AdeskLink l = links(r, MONEY).get(d.id());
        if (l == null) l = AdeskLink.builder().kind(MONEY).msKey(d.id()).build();
        l.setAdeskId(adeskId); l.setHash(w.hash()); l.setStatus(OK); l.setError(null);
        l.setName(d.number()); l.setMsType(d.entity()); l.setDocDate(d.date()); l.setSumTiyin(d.signedTiyin()); l.setAccountKey(d.accountKey());
        save(r, l);
    }

    private void moneyErr(AdeskRun r, MsMoneyDoc d, AdeskLink l, String msg) {
        if (l == null) l = AdeskLink.builder().kind(MONEY).msKey(d.id()).build();
        l.setStatus(ERROR); l.setError(msg);
        l.setName(d.number()); l.setMsType(d.entity()); l.setDocDate(d.date()); l.setSumTiyin(d.signedTiyin()); l.setAccountKey(d.accountKey());
        save(r, l);
        r.inc("tx.error");
    }

    /* ==================== 7. tovar hujjatlari → majburiyatlar ==================== */

    void commitments(AdeskRun r) {
        LocalDate from = cfg.start(), to = cfg.effectiveEnd();
        if (to.isBefore(from)) return;
        Map<String, AdeskLink> L = links(r, COMMIT);
        LocalDateTime cur = cursor("commit");
        boolean all = r.full || cur == null || L.isEmpty();
        ProdCtx pc = new ProdCtx();
        List<MsGoodsDoc> docs = new ArrayList<>();
        for (String e : AdeskMsReader.GOODS_ENTITIES.keySet())
            docs.addAll(msr.goodsDocs(e, from, to, all ? null : cur.minusMinutes(OVERLAP_MIN)));
        Set<String> seen = new HashSet<>();
        Map<String, Map<String, String>> params = new HashMap<>();
        int i = 0;
        for (MsGoodsDoc d : docs) {
            if (r.stopped()) return;
            r.progress = (++i) + "/" + docs.size();
            seen.add(d.id());
            AdeskLink l = L.get(d.id());
            boolean empty = d.sumTiyin() <= 0;   // Adesk «amount ненулевым» talab qiladi — nol summali hujjat (tovarli bo'lsa ham) o'tmaydi
            if (!d.applicable() || empty) {
                if (linked(l)) removeCommit(r, l);
                if (d.applicable()) {   // bo'sh hujjat (0 so'm, pozitsiyasiz) — balansga ta'siri yo'q; solishtirishda alohida sanaladi
                    if (l == null) l = AdeskLink.builder().kind(COMMIT).msKey(d.id()).build();
                    if (!SKIP.equals(l.getStatus())) {
                        l.setStatus(SKIP); l.setError("бўш ҳужжат (0 сўм)");
                        l.setName(d.number()); l.setMsType(d.entity()); l.setDocDate(d.date()); l.setSumTiyin(0L);
                        save(r, l);
                    }
                } else if (l != null && ERROR.equals(l.getStatus())) { l.setStatus(SKIP); save(r, l); }
                continue;
            }
            try {
                Map<String, String> p = commitParams(r, pc, d);
                params.put(d.id(), p);
                String hash = sha(p.toString());
                if (linked(l) && hash.equals(l.getHash()) && !ERROR.equals(l.getStatus())) continue;
                boolean upd = linked(l);
                if (upd) dropCommit(l.getAdeskId());   // pozitsiyalar to'g'ri almashishi uchun: o'chirib qayta yaratiladi
                long id = ad.createCommitment(p);
                commitOk(r, d, l, id, hash);
                r.inc(upd ? "cm.updated" : "cm.created");
            } catch (AdeskException e) {
                if (e.fatal) throw e;
                commitErr(r, d, l, e.getMessage());
            } catch (Exception e) {
                commitErr(r, d, l, e.getMessage());
            }
        }
        if (all && !r.stopped()) {
            for (AdeskLink l : new ArrayList<>(L.values()))
                if (linked(l) && !seen.contains(l.getMsKey())) removeCommit(r, l);
            Set<Long> present = new HashSet<>();
            ad.commitments(from, to).forEach(c -> present.add(c.id()));
            long missing = docs.stream().map(d -> L.get(d.id())).filter(l -> linked(l) && !ERROR.equals(l.getStatus()) && !present.contains(l.getAdeskId())
                    && (l.getUpdatedAt() == null || l.getUpdatedAt().isBefore(r.startedInstant))).count();
            if (missing > 20 && missing * 10 > L.size()) {   // ro'yxat chala o'qilgan bo'lishi mumkin — dublikat yaratmaslik uchun tiklanmaydi
                r.note("Adesk majburiyatlar ro'yxatida " + missing + " ta bog'langan yozuv ko'rinmadi — avtomatik tiklash o'tkazib yuborildi");
                docs = List.of();
            }
            for (MsGoodsDoc d : docs) {
                AdeskLink l = L.get(d.id());
                Map<String, String> p = params.get(d.id());
                if (!linked(l) || ERROR.equals(l.getStatus()) || p == null || present.contains(l.getAdeskId())) continue;
                if (l.getUpdatedAt() != null && !l.getUpdatedAt().isBefore(r.startedInstant)) continue;   // shu yurishda yaratilgan
                try { commitOk(r, d, l, ad.createCommitment(p), l.getHash()); r.inc("cm.restored"); }
                catch (AdeskException e) { if (e.fatal) throw e; commitErr(r, d, l, e.getMessage()); }
            }
        }
        if (!r.stopped()) setCursor("commit", r.startedAt);
    }

    private Map<String, String> commitParams(AdeskRun r, ProdCtx pc, MsGoodsDoc d) {
        Long le = r.orgLe.get(d.orgId());
        if (le == null) throw new IllegalStateException("yuridik shaxs Adesk'da yo'q");
        Long ctr = contractorFor(r, d.agentType(), d.agentId(), d.orgId(), true);
        if (ctr == null) throw new IllegalStateException("kontragent sifatida tashkilotning o'zi turibdi — MoySklad'da xaridor/yetkazuvchini tanlang");
        Map<String, String> p = new LinkedHashMap<>();
        p.put("amount", som(d.sumTiyin()).toPlainString());
        p.put("type", AdeskMsReader.GOODS_ENTITIES.get(d.entity()));
        p.put("date", d.date().toString());
        p.put("contractor", String.valueOf(ctr));
        p.put("legal_entity", String.valueOf(le));
        p.put("currency", cfg.currency());
        String desc = "MS " + ruName(d.entity()) + " №" + d.number() + (d.description().isBlank() ? "" : " · " + d.description().replaceAll("\\s+", " "));
        p.put("description", desc.length() > 510 ? desc.substring(0, 507) + "…" : desc);
        // Adesk bitta hujjatda bir xil tovarni ikki qatorda qabul qilmaydi («identichnym id») — bir tovar qatorlari birlashtiladi:
        // soni yig'iladi, narx = jami summa / jami son (hujjat summasi o'zgarmaydi)
        Map<String, double[]> merged = new LinkedHashMap<>();   // "type:id" → {son, summa(tiyin)}
        Map<String, MsPos> first = new LinkedHashMap<>();
        for (MsPos ps : d.positions()) {
            if (!"product".equals(ps.assortmentType()) && !"service".equals(ps.assortmentType()))
                throw new IllegalStateException("pozitsiya turi qo'llanmaydi: " + ps.assortmentType());
            String k = ps.assortmentType() + ":" + ps.assortmentId();
            double[] a = merged.computeIfAbsent(k, x -> new double[2]);
            a[0] += ps.quantity();
            a[1] += ps.quantity() * ps.priceTiyin();
            first.putIfAbsent(k, ps);
        }
        int i = 0;
        for (var e : merged.entrySet()) {
            MsPos ps = first.get(e.getKey());
            double q = e.getValue()[0];
            long avg = q == 0 ? ps.priceTiyin() : Math.round(e.getValue()[1] / q);
            if (q != 0 && Math.abs(e.getValue()[1] / q - avg) > 0.0001)   // butun tiyinga bo'linmasa — 4 xonagacha aniq narx
                p.put("product-" + i + "-price", BigDecimal.valueOf(e.getValue()[1] / q / 100).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
            else p.put("product-" + i + "-price", som(avg).toPlainString());
            Long pid = productFor(r, pc, ps.assortmentType(), ps.assortmentId());
            p.put("product-" + i + "-product_id", String.valueOf(pid));
            p.put("product-" + i + "-quantity", qty(q));
            i++;
        }
        return p;
    }

    private void dropCommit(long id) {
        try { ad.removeCommitment(id); }
        catch (AdeskException e) { if (e.fatal) throw e; log.info("Adesk majburiyati {} o'chirilmadi (ehtimol allaqachon yo'q): {}", id, e.getMessage()); }
    }

    private void removeCommit(AdeskRun r, AdeskLink l) {
        dropCommit(l.getAdeskId());
        l.setStatus(DELETED); l.setError(null);
        save(r, l);
        r.inc("cm.removed");
    }

    private void commitOk(AdeskRun r, MsGoodsDoc d, AdeskLink l, long id, String hash) {
        if (l == null) l = AdeskLink.builder().kind(COMMIT).msKey(d.id()).build();
        l.setAdeskId(id); l.setHash(hash); l.setStatus(OK); l.setError(null);
        l.setName(d.number()); l.setMsType(d.entity()); l.setDocDate(d.date()); l.setSumTiyin(d.sumTiyin());
        save(r, l);
    }

    private void commitErr(AdeskRun r, MsGoodsDoc d, AdeskLink l, String msg) {
        if (l == null) l = AdeskLink.builder().kind(COMMIT).msKey(d.id()).build();
        l.setStatus(ERROR); l.setError(msg);
        l.setName(d.number()); l.setMsType(d.entity()); l.setDocDate(d.date()); l.setSumTiyin(d.sumTiyin());
        save(r, l);
        r.inc("cm.error");
    }

    /* ==================== bog'lanishlar ==================== */

    Map<String, AdeskLink> links(AdeskRun r, String kind) {
        Map<String, AdeskLink> m = r.links(kind);
        if (m == null) {
            m = new HashMap<>();
            for (AdeskLink l : repo.findByKind(kind)) m.put(l.getMsKey(), l);
            r.putLinks(kind, m);
        }
        return m;
    }

    /** Bog'langan va o'chirilmagan (Adesk'da obyekt bor deb hisoblanadi; ERROR — oxirgi yangilash o'tmagan). */
    static boolean linked(AdeskLink l) {
        return l != null && l.getAdeskId() != null && !DELETED.equals(l.getStatus()) && !SKIP.equals(l.getStatus());
    }

    private static Set<Long> usedIds(Map<String, AdeskLink> m) {
        Set<Long> s = new HashSet<>();
        for (AdeskLink l : m.values()) if (linked(l)) s.add(l.getAdeskId());
        return s;
    }

    AdeskLink save(AdeskRun r, AdeskLink l) {
        l.setUpdatedAt(Instant.now());
        AdeskLink s = repo.save(l);
        links(r, s.getKind()).put(s.getMsKey(), s);
        return s;
    }

    AdeskLink ok(AdeskRun r, String kind, String key, long adeskId, String name, String hash) {
        AdeskLink l = links(r, kind).get(key);
        if (l == null) l = AdeskLink.builder().kind(kind).msKey(key).build();
        l.setAdeskId(adeskId); l.setName(cut(name)); l.setHash(hash); l.setStatus(OK); l.setError(null);
        return save(r, l);
    }

    private void err(AdeskRun r, String kind, String key, String name, String msg) {
        AdeskLink l = links(r, kind).get(key);
        if (l == null) l = AdeskLink.builder().kind(kind).msKey(key).build();
        l.setName(cut(name)); l.setStatus(ERROR); l.setError(msg);
        save(r, l);
        r.inc("err." + kind);
        log.warn("Adesk {} {} ({}): {}", kind, key, name, msg);
    }

    private LocalDateTime cursor(String stage) {
        try { return cfg.get(AdeskConfig.CURSOR + stage).map(LocalDateTime::parse).orElse(null); }
        catch (Exception e) { return null; }
    }

    private void setCursor(String stage, LocalDateTime t) { cfg.set(AdeskConfig.CURSOR + stage, t.toString()); }

    /** Davr boshi o'zgarganda — keyingi yurish hammasini qaytadan solishtirsin. */
    public void resetCursors() {
        for (String s : List.of("contractor", "product", "money", "commit")) cfg.set(AdeskConfig.CURSOR + s, "");
    }

    /* ==================== yordamchilar ==================== */

    static BigDecimal som(long tiyin) { return BigDecimal.valueOf(tiyin, 2); }

    static long tiyin(BigDecimal som) {
        return som == null ? 0 : som.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValue();
    }

    static String qty(double q) { return BigDecimal.valueOf(q).stripTrailingZeros().toPlainString(); }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replace('ё', 'е').replaceAll("[\"'«»`]", "").replaceAll("\\s+", " ").trim();
    }

    static String digits(String s) { return s == null ? "" : s.replaceAll("\\D", ""); }

    private static String cut(String s) { return s == null ? null : s.length() > 300 ? s.substring(0, 300) : s; }

    static String sha(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            for (String p : parts) { md.update((p == null ? "∅" : p).getBytes(StandardCharsets.UTF_8)); md.update((byte) 1); }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
