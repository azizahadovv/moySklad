package uz.kassa.service.adesk;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uz.kassa.domain.AdeskLink;
import uz.kassa.repo.AdeskLinkRepo;
import uz.kassa.service.adesk.AdeskClient.*;
import uz.kassa.service.adesk.AdeskMsReader.*;
import uz.kassa.webapp.ExcelReportService.SheetDef;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static uz.kassa.bot.TextUtil.esc;
import static uz.kassa.bot.TextUtil.fmtTiyin;
import static uz.kassa.domain.AdeskLink.*;

/**
 * 📒 Solishtirish (docs/ADESK.md §7 — 6-bosqich «tekshiruv»): Adesk'dagi ma'lumot MoySklad bilan bir xilmi?
 *  1) har hisob qoldig'i davr oxiriga (MoySklad: joriy qoldiq − keyingi harakat; Adesk: joriy qoldiq − keyingi operatsiyalar);
 *  2) pul harakati (ДДС) statyalar bo'yicha kirim/chiqim;
 *  3) hujjatlar soni: pul hujjatlari, otgruzka/priyomkalar, kontragentlar, tovarlar; Adesk'da qo'lda kiritilganlar; xatolar.
 * Hamma raqam tiyinda hisoblanadi. Natija — Telegram matni (kirill, avtomatik hisobot qatlami) + Excel varaqlari.
 */
@Service
@RequiredArgsConstructor
public class AdeskCheckService {

    private final AdeskConfig cfg;
    private final AdeskClient ad;
    private final AdeskMsReader msr;
    private final AdeskLinkRepo repo;

    private static final String RULE = "━━━━━━━━━━━━━━━━━━━━";
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final int SHOW = 8;

    public record Result(String html, List<SheetDef> sheets, boolean equal, String shortLine) {}

    public Result check() {
        LocalDate from = cfg.start(), to = cfg.effectiveEnd(), today = cfg.today();
        Map<String, String> cur = msr.currencies();
        List<MsOrg> orgs = msr.orgs();
        Map<String, MsOrg> orgById = new HashMap<>();
        orgs.forEach(o -> orgById.put(o.id(), o));
        List<MsAccount> accs = msr.accounts(orgs);
        AdeskMsReader.MoneySnap snap = msr.moneySnapshot(from, today, cur);   // qoldiq va hujjatlar bir suratda
        Map<String, Long> bal = snap.balances();
        List<MsMoneyDoc> docs = snap.docs().stream().filter(d -> d.applicable() && d.sumTiyin() > 0).toList();
        Map<String, String> expense = msr.expenseItems();

        List<AdAccount> adAccs = ad.bankAccounts();
        Map<Long, AdAccount> adAccById = new HashMap<>();
        adAccs.forEach(a -> adAccById.put(a.id(), a));
        List<AdTx> adTx = ad.transactions(from, today);
        Map<Long, String> catName = new HashMap<>();
        ad.categories().forEach(c -> catName.put(c.id(), c.name()));

        Map<String, AdeskLink> accLinks = new HashMap<>();
        repo.findByKind(ACCOUNT).forEach(l -> accLinks.put(l.getMsKey(), l));
        Set<Long> moneyIds = new HashSet<>();
        for (AdeskLink l : repo.findByKind(MONEY)) if (AdeskSyncService.linked(l)) moneyIds.add(l.getAdeskId());

        boolean equal = true;
        StringBuilder sb = new StringBuilder();
        sb.append("📒 <b>ADESK ↔ MOYSKLAD</b>\n");
        sb.append("Давр: ").append(from.format(DF)).append(" – ").append(to.format(DF))
          .append(" · текширув ").append(LocalDateTime.now(cfg.zone()).format(DT)).append("\n\n");

        /* ---------- 1. hisoblar qoldig'i ---------- */
        Map<String, Long> msAfter = new HashMap<>();
        for (MsMoneyDoc d : docs) if (d.date().isAfter(to)) msAfter.merge(d.accountKey(), d.signedTiyin(), Long::sum);
        Map<Long, Long> adAfter = new HashMap<>();
        for (AdTx t : adTx) if (t.date() != null && t.date().isAfter(to) && t.accountId() != null) adAfter.merge(t.accountId(), t.signedTiyin(), Long::sum);
        Map<String, Integer> nameCnt = new HashMap<>();
        for (MsAccount a : accs) if (a.accountId() != null) nameCnt.merge(AdeskSyncService.norm(a.rawName()), 1, Integer::sum);

        List<Object[]> accRows = new ArrayList<>();
        List<String> accBad = new ArrayList<>();
        int accOk = 0;
        long msTotal = 0, adTotal = 0;
        Set<Long> linkedAcc = new HashSet<>();
        for (MsAccount a : accs) {
            String name = AdeskSyncService.accountName(a, orgById.get(a.orgId()), nameCnt);
            long ms = bal.getOrDefault(a.key(), 0L) - msAfter.getOrDefault(a.key(), 0L);
            AdeskLink l = accLinks.get(a.key());
            AdAccount aa = l != null && l.getAdeskId() != null ? adAccById.get(l.getAdeskId()) : null;
            if (aa == null) {
                accRows.add(new Object[]{name, som(ms), "", som(ms), "Adesk'да йўқ"});
                accBad.add("• " + esc(name) + " — Adesk'да йўқ (MoySklad <b>" + fmtTiyin(ms) + "</b>)");
                msTotal += ms;
                continue;
            }
            linkedAcc.add(aa.id());
            long adv = AdeskSyncService.tiyin(aa.amount()) - adAfter.getOrDefault(aa.id(), 0L);
            msTotal += ms; adTotal += adv;
            accRows.add(new Object[]{name, som(ms), som(adv), som(ms - adv), ms == adv ? "тенг" : "фарқ"});
            if (ms == adv) accOk++;
            else accBad.add("• " + esc(name) + " — MoySklad <b>" + fmtTiyin(ms) + "</b> · Adesk <b>" + fmtTiyin(adv) + "</b> · фарқ " + sign(ms - adv));
        }
        List<String> adExtra = new ArrayList<>();
        for (AdAccount a : adAccs)
            if (!linkedAcc.contains(a.id()) && !"closed".equalsIgnoreCase(a.status())) {
                long adv = AdeskSyncService.tiyin(a.amount()) - adAfter.getOrDefault(a.id(), 0L);
                accRows.add(new Object[]{a.name(), "", som(adv), som(-adv), "MoySklad'да йўқ (Adesk'да ортиқча)"});
                adExtra.add(a.name());
                adTotal += adv;
            }
        sb.append("<b>Ҳисоблар қолдиғи</b> (").append(to.format(DF)).append(" ҳолатига)\n");
        sb.append("✅ тенг: ").append(accOk).append(" та");
        if (!accBad.isEmpty()) sb.append(" · ⚠️ фарқ: ").append(accBad.size()).append(" та");
        sb.append("\nЖами: MoySklad <b>").append(fmtTiyin(msTotal)).append("</b> · Adesk <b>").append(fmtTiyin(adTotal)).append("</b> сўм\n");
        accBad.stream().limit(SHOW).forEach(x -> sb.append(x).append("\n"));
        if (accBad.size() > SHOW) sb.append("   … яна ").append(accBad.size() - SHOW).append(" та (Excel'да)\n");
        if (!adExtra.isEmpty()) sb.append("⚠️ Adesk'да ортиқча ҳисоблар: ").append(adExtra.size()).append(" та — ")
                .append(esc(String.join(", ", adExtra.stream().limit(5).toList()))).append(adExtra.size() > 5 ? " …" : "").append("\n");
        if (!accBad.isEmpty() || !adExtra.isEmpty()) equal = false;

        /* ---------- 2. pul harakati statyalar bo'yicha ---------- */
        Map<String, long[]> byCat = new TreeMap<>();   // "Кирим · X" → [ms, ad]
        long msIn = 0, msOut = 0, adIn = 0, adOut = 0;
        for (MsMoneyDoc d : docs) {
            if (d.date().isAfter(to)) continue;
            String c;
            if (d.income()) {
                boolean tr = d.incomeTransfer();
                c = "Кирим · " + (tr ? cfg.catTransfer() : cfg.catIncome());
                msIn += d.sumTiyin();
            } else {
                String n = expense.getOrDefault(d.expenseItemId(), "Прочие расходы");
                if (AdeskSyncService.norm(n).equals(AdeskSyncService.norm(cfg.catTransfer())) && !"organization".equals(d.agentType())) n = "Прочие расходы";
                c = "Чиқим · " + (n.isBlank() ? "Прочие расходы" : n);
                msOut += d.sumTiyin();
            }
            byCat.computeIfAbsent(c, k -> new long[2])[0] += d.sumTiyin();
        }
        int manual = 0;
        long manualSum = 0;
        List<Object[]> manualRows = new ArrayList<>();
        for (AdTx t : adTx) {
            if (t.date() == null || t.date().isAfter(to) || t.planned()) continue;
            long v = Math.abs(t.signedTiyin());
            // Adesk perevodi legi — MoySklad tomonidagi kabi «Перемещение» (perevod MoySklad'da chiqim «Перемещение» + kirim bo'lib turadi)
            String n = t.transfer() ? cfg.catTransfer() : catName.getOrDefault(t.categoryId(), t.categoryName().isBlank() ? "Статьясиз" : t.categoryName());
            String c = (t.income() ? "Кирим · " : "Чиқим · ") + n;
            byCat.computeIfAbsent(c, k -> new long[2])[1] += v;
            if (t.income()) adIn += v; else adOut += v;
            if (!moneyIds.contains(t.id())) {
                manual++; manualSum += t.signedTiyin();
                manualRows.add(new Object[]{t.id(), t.date() == null ? "" : t.date().format(DF), t.income() ? "кирим" : "чиқим",
                        som(Math.abs(t.signedTiyin())), t.transfer() ? "Перевод (Adesk)" : n, t.contractorName(), t.description()});
            }
        }
        List<Object[]> catRows = new ArrayList<>();
        List<String> catBad = new ArrayList<>();
        for (var e : byCat.entrySet()) {
            long[] v = e.getValue();
            catRows.add(new Object[]{e.getKey(), som(v[0]), som(v[1]), som(v[0] - v[1]), v[0] == v[1] ? "тенг" : "фарқ"});
            if (v[0] != v[1]) catBad.add("• " + esc(e.getKey()) + ": " + fmtTiyin(v[0]) + " · " + fmtTiyin(v[1]) + " · фарқ " + sign(v[0] - v[1]));
        }
        sb.append(RULE).append("\n<b>Пул ҳаракати (ДДС)</b>\n");
        sb.append("Кирим: MoySklad <b>").append(fmtTiyin(msIn)).append("</b> · Adesk <b>").append(fmtTiyin(adIn)).append("</b> ").append(msIn == adIn ? "✅" : "⚠️").append("\n");
        sb.append("Чиқим: MoySklad <b>").append(fmtTiyin(msOut)).append("</b> · Adesk <b>").append(fmtTiyin(adOut)).append("</b> ").append(msOut == adOut ? "✅" : "⚠️").append("\n");
        if (!catBad.isEmpty()) {
            sb.append("⚠️ Статьялар бўйича фарқ: ").append(catBad.size()).append(" та\n");
            catBad.stream().limit(SHOW).forEach(x -> sb.append(x).append("\n"));
            equal = false;
        }
        if (!today.isAfter(to) && !today.isBefore(from)) {
            long tin = 0, tout = 0;
            for (MsMoneyDoc d : docs) if (d.date().equals(today)) { if (d.income()) tin += d.sumTiyin(); else tout += d.sumTiyin(); }
            sb.append("Бугун: кирим <b>").append(fmtTiyin(tin)).append("</b> · чиқим <b>").append(fmtTiyin(tout)).append("</b> сўм\n");
        }

        /* ---------- 3. hujjatlar va ma'lumotnomalar ---------- */
        long msDocs = docs.stream().filter(d -> !d.date().isAfter(to)).count();
        long adLinked = adTx.stream().filter(t -> t.date() != null && !t.date().isAfter(to) && moneyIds.contains(t.id())).count();
        sb.append(RULE).append("\n<b>Ҳужжатлар</b>\n");
        sb.append(line("Пул ҳужжатлари", msDocs, adLinked));
        if (msDocs != adLinked) equal = false;
        if (manual > 0) {
            sb.append("⚠️ Adesk'да қўлда киритилган (MoySklad'да йўқ): ").append(manual).append(" та · <b>").append(fmtTiyin(manualSum)).append("</b>\n");
            equal = false;
        }

        List<AdCommit> commits = ad.commitments(from, to);
        long msOutDocs = msr.countDocs("demand", from, to) + msr.countDocs("purchasereturn", from, to);
        long msInDocs = msr.countDocs("supply", from, to) + msr.countDocs("salesreturn", from, to);
        long adOutC = commits.stream().filter(c -> c.type() == 2).count();
        long adInC = commits.stream().filter(c -> c.type() == 1).count();
        // bo'sh hujjatlar (0 so'm, pozitsiyasiz) Adesk'ka o'tmaydi — MoySklad tomonidan ayiriladi; xatolilar alohida ko'rsatiladi
        Set<String> outTypes = Set.of("demand", "purchasereturn");
        long emptyOut = 0, emptyIn = 0, errOut = 0, errIn = 0;
        for (AdeskLink l : repo.findByKind(COMMIT)) {
            if (l.getDocDate() == null || l.getDocDate().isBefore(from) || l.getDocDate().isAfter(to)) continue;
            boolean out = outTypes.contains(l.getMsType());
            if (SKIP.equals(l.getStatus())) { if (out) emptyOut++; else emptyIn++; }
            else if (ERROR.equals(l.getStatus())) { if (out) errOut++; else errIn++; }
        }
        msOutDocs -= emptyOut;
        msInDocs -= emptyIn;
        sb.append(line("Отгрузка + возврат поставщику", msOutDocs, adOutC).replace("\n", errOut > 0 ? " (хато " + errOut + ")\n" : "\n"));
        sb.append(line("Приёмка + возврат покупателя", msInDocs, adInC).replace("\n", errIn > 0 ? " (хато " + errIn + ")\n" : "\n"));
        if (emptyOut + emptyIn > 0)
            sb.append("ℹ️ Бўш ҳужжатлар (0 сўм, позициясиз): ").append(emptyOut + emptyIn).append(" та — ўтказилмайди\n");
        if (msOutDocs != adOutC || msInDocs != adInC) equal = false;

        long msCp = msr.count("entity/counterparty");
        long adCp = repo.countByKindAndStatus(CONTRACTOR, OK);
        long msPr = msr.count("entity/product") + msr.count("entity/service");
        long adPr = repo.countByKindAndStatus(PRODUCT, OK);
        sb.append(line("Контрагентлар", msCp, adCp, adCp >= msCp));
        sb.append(line("Товар ва хизматлар", msPr, adPr, adPr >= msPr));
        if (adCp < msCp || adPr < msPr) equal = false;

        int loss = msr.countDocs("loss", from, to), enter = msr.countDocs("enter", from, to);
        if (loss + enter > 0)
            sb.append("ℹ️ Списание ").append(loss).append(" та · оприходование ").append(enter).append(" та — Adesk API'да йўқ, ўтказилмайди\n");

        List<AdeskLink> errs = repo.findByStatusOrderByUpdatedAtDesc(ERROR);
        if (!errs.isEmpty()) {
            sb.append("⚠️ Хатолар: ").append(errs.size()).append(" та (📒 Adesk → ⚠️ Хатолар)\n");
            equal = false;
        }

        sb.append("\n").append(equal ? "✅ <b>Интеграция тўғри: ҳамма кўрсаткичлар тенг.</b>" : "⚠️ <b>Фарқлар бор — батафсил Excel'да.</b>");

        List<Object[]> errRows = new ArrayList<>();
        for (AdeskLink l : errs) errRows.add(new Object[]{l.getKind(), l.getMsType(), l.getName(), l.getDocDate() == null ? "" : l.getDocDate().format(DF),
                l.getSumTiyin() == null ? "" : som(l.getSumTiyin()), l.getError(), msLink(l)});

        /* ---------- ўтказмалар жуфти: ҳар ой кирим ва чиқим ўтказмаси суммалари бўйича жуфтлаштирилади ---------- */
        Map<String, List<MsMoneyDoc>> trIn = new TreeMap<>(), trOut = new TreeMap<>();
        for (MsMoneyDoc d : docs) {
            if (d.date().isAfter(to)) continue;
            String ym = d.date().toString().substring(0, 7);
            if (d.income()) { if (d.incomeTransfer()) trIn.computeIfAbsent(ym, k -> new ArrayList<>()).add(d); }
            else {
                String n = expense.getOrDefault(d.expenseItemId(), "");
                if (AdeskSyncService.norm(n).equals(AdeskSyncService.norm(cfg.catTransfer())) && "organization".equals(d.agentType()))
                    trOut.computeIfAbsent(ym, k -> new ArrayList<>()).add(d);
            }
        }
        List<Object[]> trRows = new ArrayList<>();
        Set<String> months = new TreeSet<>(trIn.keySet());
        months.addAll(trOut.keySet());
        for (String ym : months) {
            Map<Long, Deque<MsMoneyDoc>> pool = new HashMap<>();
            for (MsMoneyDoc d : trOut.getOrDefault(ym, List.of())) pool.computeIfAbsent(d.sumTiyin(), k -> new ArrayDeque<>()).add(d);
            for (MsMoneyDoc d : trIn.getOrDefault(ym, List.of())) {
                Deque<MsMoneyDoc> q = pool.get(d.sumTiyin());
                if (q != null && !q.isEmpty()) q.poll();
                else trRows.add(new Object[]{ym, "Кирим — чиқим жуфти йўқ", d.date().format(DF), ruDoc(d.entity()), d.number(), som(d.sumTiyin()),
                        d.description().isBlank() ? d.purpose() : d.description(),
                        "Чиқим томонини «Перемещение» қилинг (ёки кирим ўтказма эмас)", msLink(d.entity(), d.id())});
            }
            for (Deque<MsMoneyDoc> q : pool.values()) for (MsMoneyDoc d : q)
                trRows.add(new Object[]{ym, "Чиқим — кирим жуфти йўқ", d.date().format(DF), ruDoc(d.entity()), d.number(), som(d.sumTiyin()),
                        d.description(), "Қабул қилувчи фирма кирими киритилмаган ёки сумма фарқ қилади", msLink(d.entity(), d.id())});
        }
        if (!trRows.isEmpty()) equal = false;   // (xulosa matni quyida qayta yig'iladi)

        /* ---------- MoySklad'да бор, Adesk'га ўтмаган пул ҳужжатлари ---------- */
        Map<String, AdeskLink> moneyLinks = new HashMap<>();
        for (AdeskLink l : repo.findByKind(MONEY)) moneyLinks.put(l.getMsKey(), l);
        List<Object[]> missRows = new ArrayList<>();
        for (MsMoneyDoc d : docs) {
            if (d.date().isAfter(to)) continue;
            AdeskLink l = moneyLinks.get(d.id());
            if (AdeskSyncService.linked(l) && !ERROR.equals(l.getStatus())) continue;
            missRows.add(new Object[]{d.date().format(DF), ruDoc(d.entity()), d.number(), som(d.sumTiyin()),
                    l == null ? "ҳали юборилмаган" : "хато: " + l.getError(), msLink(d.entity(), d.id())});
        }

        /* ---------- ўтказилмайдиган (бўш) товар ҳужжатлари ---------- */
        List<Object[]> skipRows = new ArrayList<>();
        for (AdeskLink l : repo.findByKind(COMMIT))
            if (SKIP.equals(l.getStatus()) && l.getDocDate() != null && !l.getDocDate().isBefore(from) && !l.getDocDate().isAfter(to))
                skipRows.add(new Object[]{l.getDocDate().format(DF), ruDoc(l.getMsType()), l.getName(), l.getSumTiyin() == null ? "" : som(l.getSumTiyin()),
                        l.getError(), msLink(l)});

        List<SheetDef> sheets = List.of(
                new SheetDef("Ҳисоблар", new String[]{"Ҳисоб", "MoySklad", "Adesk", "Фарқ", "Ҳолат"}, accRows),
                new SheetDef("ДДС статьялар", new String[]{"Статья", "MoySklad", "Adesk", "Фарқ", "Ҳолат"}, catRows),
                new SheetDef("Ўтказма фарқи", new String[]{"Ой", "Муаммо", "Сана", "Ҳужжат", "№", "Сумма", "Изоҳ", "Нима қилиш керак", "MoySklad ҳавола"}, trRows),
                new SheetDef("Ўтмаган ҳужжатлар", new String[]{"Сана", "Ҳужжат", "№", "Сумма", "Сабаб", "MoySklad ҳавола"}, missRows),
                new SheetDef("Хатолар", new String[]{"Тур", "Ҳужжат", "Номи/№", "Сана", "Сумма", "Сабаб", "MoySklad ҳавола"}, errRows),
                new SheetDef("Бўш ҳужжатлар", new String[]{"Сана", "Ҳужжат", "№", "Сумма", "Сабаб", "MoySklad ҳавола"}, skipRows),
                new SheetDef("Adesk қўлда", new String[]{"Adesk ID", "Сана", "Тур", "Сумма", "Статья", "Контрагент", "Изоҳ"}, manualRows));
        String shortLine = (equal ? "✅ тенг" : "⚠️ фарқ") + " · ҳисоблар " + accOk + "/" + (accOk + accBad.size())
                + " · ҳужжатлар " + adLinked + "/" + msDocs + (errs.isEmpty() ? "" : " · хато " + errs.size());
        String extra = "";
        if (!trRows.isEmpty()) extra += "\n⚠️ Ўтказмада жуфти йўқ ҳужжатлар: " + trRows.size() + " та (Excel: «Ўтказма фарқи»)";
        if (!missRows.isEmpty()) extra += "\n⚠️ Adesk'га ўтмаган пул ҳужжатлари: " + missRows.size() + " та (Excel: «Ўтмаган ҳужжатлар»)";
        String html = sb.toString() + extra;
        if (html.length() > 4000) html = html.substring(0, 3990) + "…";
        return new Result(html, sheets, equal, shortLine);
    }

    private static final String MS_APP = "https://online.moysklad.ru/app/#";

    /** MoySklad ҳужжатини очиш ҳаволаси. */
    static String msLink(String entity, String id) { return MS_APP + entity + "/edit?id=" + id; }

    static String msLink(AdeskLink l) {
        String e = l.getMsType() != null ? l.getMsType() : switch (l.getKind()) {
            case CONTRACTOR -> "counterparty";
            case PRODUCT -> "product";
            case EMPLOYEE -> "employee";
            case ORG, ORGC -> "organization";
            default -> null;
        };
        return e == null || l.getMsKey() == null || l.getMsKey().contains(":") ? "" : msLink(e, l.getMsKey());
    }

    private static String ruDoc(String entity) { return AdeskSyncService.ruName(entity); }

    private static String line(String title, long ms, long adv) { return line(title, ms, adv, ms == adv); }

    /** ok — tenglik sharti (kontragent/tovarda Adesk'da arxivdagilar ham bo'lishi mumkin — «≥»). */
    private static String line(String title, long ms, long adv, boolean ok) {
        return title + ": MoySklad " + ms + " · Adesk " + adv + " " + (ok ? "✅" : "⚠️") + "\n";
    }

    private static String sign(long v) { return (v > 0 ? "+" : v < 0 ? "−" : "") + fmtTiyin(Math.abs(v)); }

    private static double som(long tiyin) { return tiyin / 100.0; }
}
