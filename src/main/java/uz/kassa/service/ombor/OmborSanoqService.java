package uz.kassa.service.ombor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.Sender;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Kassa;
import uz.kassa.domain.OmborSanoq;
import uz.kassa.domain.OmborTovar;
import uz.kassa.repo.KassaRepo;
import uz.kassa.repo.OmborSanoqRepo;
import uz.kassa.repo.OmborTovarRepo;
import uz.kassa.service.AuditService;
import uz.kassa.service.NotifySwitches;
import uz.kassa.webapp.ExcelReportService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.esc;

/**
 * 🏬 Kunlik sanoq (docs/OMBOR-TZ.md §7.1, 2026-09-12 qarori). Har do'kon uchun 30 kunlik eng ko'p sotilgan tovarlar
 * havzasidan (ombor.sanoq_havza) tasodifiy N ta (ombor.sanoq_soni) tanlanadi; oxirgi 7 kunda rejaga tushganlar havza yetsa
 * takrorlanmaydi. Shu N ning ombor.sanoq_eski tasi 🐢 eski tovar (qoldig'i bor, 30 kunda sotilmagan): hech sanalmaganlar,
 * so'ng eng uzoq sanalmaganlar (abc = "E"). Ertalab (ombor.sanoq_vaqt) zavskladga so'rov; kladovchi fakt kiritadi (inventarizatsiya kabi: hisobda 15 →
 * fakt 10); kun yakunida (ombor.sanoq_yopish_vaqt) fakt kiritilganlar TASDIQ, kiritilmaganlar OTKAZILDI, rahbarga yakun;
 * haftada bir (ombor.sanoq_hafta_kun/vaqt) admin + hisobot oluvchilarga Excel. Farq — SANOQ_FARQ qoidasi;
 * tasdiqlanmagan — kun yopilmaydi (ombor.sanoq_majburiy).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OmborSanoqService {

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final String RULE_TOP = "━━━━━━━━━━━━━━━━━━━━";
    /** Sotuv oynasi (kun) va takrorlanmaslik oynasi (kun). */
    public static final int SOTUV_KUN = 30, TAKROR_KUN = 7;
    private static final int LIST_MAX = 25;

    private final OmborSanoqRepo repo;
    private final OmborMetrics metrics;
    private final KassaRepo kassaRepo;
    private final OmborTovarRepo tovarRepo;
    private final OmborConfig cfg;
    private final AuditService audit;
    private final OmborRecipients rec;
    private final NotifySwitches sw;
    private final Sender sender;
    private final ExcelReportService excel;
    private final OmborChempionService chempion;
    private final OmborTrendService trend;
    private final OmborPartiyaService partiya;

    /* ==================== reja ==================== */

    /** Bugungi reja (kuniga bir; allaqachon bo'lsa o'tkaziladi). Qaytadi: yangi topshiriqlar soni. */
    public int planToday() {
        if (!cfg.enabled()) return 0;
        LocalDate today = LocalDate.now(cfg.zone());
        int total = 0;
        Map<String, Integer> abc = new HashMap<>();
        for (Map<String, Object> m : metrics.latest(OmborCalcService.ABC, "kassa_id = 0"))
            abc.put((String) m.get("product_ms_id"), ((BigDecimal) m.get("value")).intValue());
        for (Kassa k : stores()) {
            if (repo.countByKassaIdAndPlanDate(k.getId(), today) > 0) continue;
            total += plan(k, today, abc).size();
        }
        if (total > 0) log.info("Ombor sanoq rejasi: {} ta tovar ({})", total, today);
        return total;
    }

    /**
     * Do'kon uchun bugungi reja: (1) 🐢 eski — qoldig'i bor, 30 kunda sotilmagan; hech sanalmaganlar, so'ng eng uzoq sanalmaganlar
     * (teng bo'lsa tasodifiy), ombor.sanoq_eski tagacha; (2) qolgani 30 kunlik top havzadan tasodifiy. Sotuv tarixi bo'lmasa —
     * qoldig'i bor tovarlardan tasodifiy.
     */
    private List<OmborSanoq> plan(Kassa k, LocalDate today, Map<String, Integer> abc) {
        Map<String, BigDecimal> stock = new HashMap<>();
        for (Map<String, Object> m : metrics.latest(OmborMetrics.QOLDIQ, "kassa_id = ? AND product_ms_id <> ''", k.getId()))
            stock.put((String) m.get("product_ms_id"), (BigDecimal) m.get("value"));
        Map<String, LocalDate> last = new HashMap<>();
        for (Object[] o : repo.lastPlanned(k.getId())) last.put((String) o[0], (LocalDate) o[1]);
        LocalDate from = today.minusDays(SOTUV_KUN);
        int wantAll = cfg.sanoqSoni();
        // 🐢 eski
        Set<String> sold = metrics.soldSince(k.getId(), from);
        List<String> eski = new ArrayList<>();
        for (var e : stock.entrySet()) if (e.getValue().signum() > 0 && !sold.contains(e.getKey()) && !archived(e.getKey())) eski.add(e.getKey());
        Collections.shuffle(eski);
        eski.sort(Comparator.comparing(p -> last.getOrDefault(p, LocalDate.MIN)));   // barqaror sort: tengida tasodifiy qoladi
        List<String> pickEski = new ArrayList<>(eski.subList(0, Math.min(Math.min(cfg.sanoqEski(), wantAll), eski.size())));
        // top-sotuvchilar
        List<String> pool = new ArrayList<>(metrics.topSold(k.getId(), from, cfg.sanoqHavza()));
        pool.removeIf(this::archived);
        if (pool.isEmpty()) pool = new ArrayList<>(stock.keySet());     // sotuv tarixi hali yuklanmagan
        pool.removeAll(pickEski);
        int want = Math.min(wantAll - pickEski.size(), pool.size());
        // oxirgi TAKROR_KUN kunda rejaga tushganlar chetga; havza yetmasa ular ham qo'shiladi
        List<String> fresh = new ArrayList<>(), recent = new ArrayList<>();
        for (String p : pool) (last.containsKey(p) && !last.get(p).isBefore(today.minusDays(TAKROR_KUN)) ? recent : fresh).add(p);
        Collections.shuffle(fresh); Collections.shuffle(recent);
        List<String> pick = new ArrayList<>(fresh.subList(0, Math.min(Math.max(want, 0), fresh.size())));
        for (String p : recent) { if (pick.size() >= want) break; pick.add(p); }
        List<OmborSanoq> out = new ArrayList<>();
        for (String pid : pickEski)
            out.add(OmborSanoq.builder().kassaId(k.getId()).productMsId(pid).planDate(today).abc("E").systemQty(stock.get(pid)).build());
        for (String pid : pick) {
            int cls = abc.getOrDefault(pid, 3);
            out.add(OmborSanoq.builder().kassaId(k.getId()).productMsId(pid).planDate(today)
                    .abc(cls == 1 ? "A" : cls == 2 ? "B" : "C").systemQty(stock.getOrDefault(pid, BigDecimal.ZERO)).build());
        }
        return out.isEmpty() ? List.of() : repo.saveAll(out);
    }

    private boolean archived(String pid) { return tovarRepo.findById(pid).map(OmborTovar::isArchived).orElse(true); }

    private List<Kassa> stores() {
        List<Kassa> out = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) if (k.getMoyskladWarehouseId() != null) out.add(k);
        return out;
    }

    /* ==================== ochiq topshiriqlar ==================== */

    /** Do'kon bo'yicha ochiq (REJA/KIRITILDI) topshiriqlar, bugungacha. */
    public List<OmborSanoq> open(Long kassaId) {
        return repo.findByKassaIdAndStatusInAndPlanDateLessThanEqualOrderByPlanDateAscIdAsc(kassaId, OmborSanoq.OPEN, LocalDate.now(cfg.zone()));
    }

    public long openCount(Long kassaId) {
        return repo.countByKassaIdAndStatusInAndPlanDateLessThanEqual(kassaId, OmborSanoq.OPEN, LocalDate.now(cfg.zone()));
    }

    public long openCountAll() {
        return repo.countByStatusInAndPlanDateLessThanEqual(OmborSanoq.OPEN, LocalDate.now(cfg.zone()));
    }

    public OmborSanoq enterFact(long id, BigDecimal fact, AppUser by) {
        OmborSanoq s = repo.findById(id).orElse(null);
        if (s == null || !OmborSanoq.OPEN.contains(s.getStatus())) return null;
        s.setFactQty(fact); s.setStatus("KIRITILDI"); s.setByUserId(by.getId()); s.setAt(Instant.now());
        return repo.save(s);
    }

    /** Fakt kiritilganlarni tasdiqlash (do'kon bo'yicha). Qaytadi: tasdiqlangan soni. */
    public int confirm(Long kassaId, AppUser by) {
        int n = 0;
        for (OmborSanoq s : open(kassaId)) {
            if (s.getFactQty() == null) continue;
            s.setStatus("TASDIQ"); s.setByUserId(by.getId()); s.setAt(Instant.now());
            repo.save(s); n++;
        }
        if (n > 0) audit.log(by.getId(), "OMBOR_SANOQ_TASDIQ", "kassa", kassaId, n + " ta tovar");
        return n;
    }

    /** Kun yopilishini to'sadigan sabab (null — to'siq yo'q). ombor.sanoq_majburiy=1 bo'lsa: ochiqlar + shu kuni sanalmaganlar. */
    public String blockReason(LocalDate day) {
        if (!cfg.enabled() || !cfg.sanoqMajburiy()) return null;
        long n = 0;
        List<String> parts = new ArrayList<>();
        for (Kassa k : stores()) {
            long c = repo.countByKassaIdAndStatusInAndPlanDateLessThanEqual(k.getId(), OmborSanoq.OPEN, day)
                    + repo.countByKassaIdAndPlanDateAndStatus(k.getId(), day, "OTKAZILDI");
            if (c > 0) { parts.add(k.getName() + " " + c); n += c; }
        }
        if (n == 0) return null;
        return "🔢 Sanoq tasdiqlanmagan: " + n + " ta (" + String.join(", ", parts) + "). Kladovchi 🏬 Омбор → 🔢 Санoq da tasdiqlasin.";
    }

    /* ==================== kunlik tsikl (Jobs, har 5 daqiqa) ==================== */

    /** Ertalabki so'rov → kun yakuni (rahbarga) → haftalik Excel (admin). Har biri kuniga bir marta, vaqt sozlamadan. */
    public void tick() {
        if (!cfg.enabled()) return;
        LocalDate today = LocalDate.now(cfg.zone());
        LocalTime now = LocalTime.now(cfg.zone());
        String d = today.toString();
        if (!now.isBefore(cfg.sanoqVaqt()) && !d.equals(cfg.get(OmborConfig.SANOQ_SOROV_SENT).orElse(""))) {
            cfg.set(OmborConfig.SANOQ_SOROV_SENT, d);
            try { planToday(); } catch (Exception e) { log.warn("Sanoq reja: {}", e.getMessage()); }
            try { askAll(today, null); } catch (Exception e) { log.warn("Sanoq so'rov: {}", e.getMessage()); }
        }
        if (!now.isBefore(cfg.sanoqYopishVaqt()) && !d.equals(cfg.get(OmborConfig.SANOQ_YOPISH_SENT).orElse(""))) {
            cfg.set(OmborConfig.SANOQ_YOPISH_SENT, d);
            try { closeDay(today); } catch (Exception e) { log.warn("Sanoq yakun: {}", e.getMessage()); }
        }
        if (today.getDayOfWeek().getValue() == cfg.sanoqHaftaKun() && !now.isBefore(cfg.sanoqHaftaVaqt())
                && !d.equals(cfg.get(OmborConfig.SANOQ_HAFTA_SENT).orElse(""))) {
            cfg.set(OmborConfig.SANOQ_HAFTA_SENT, d);
            try { sendWeekly(today.minusDays(7), today.minusDays(1), null); } catch (Exception e) { log.warn("Sanoq haftalik: {}", e.getMessage()); }
        }
    }

    /** Ertalabki so'rov: har do'konning bugungi ro'yxati → zavsklad (bo'lmasa rahbar). `only` — faqat shu odamga (admin sinovi). */
    public int askAll(LocalDate today, AppUser only) {
        int sent = 0;
        for (Kassa k : stores()) {
            List<OmborSanoq> list = repo.findByKassaIdAndPlanDateOrderByAbcAscIdAsc(k.getId(), today);
            if (list.isEmpty()) continue;
            long entered = list.stream().filter(x -> x.getFactQty() != null).count();
            StringBuilder sb = new StringBuilder("🔢 <b>Bugungi sanoq</b> — " + esc(k.getName()) + " · " + DF.format(today) + "\n\n");
            long eskiN = list.stream().filter(OmborSanoq::eski).count();
            sb.append("<b>").append(list.size()).append("</b> ta tovar (").append(SOTUV_KUN).append(" kunlik eng ko'p sotilganlardan, tasodifiy")
              .append(eskiN > 0 ? "; 🐢 " + eskiN + " ta eski qoldiq" : "").append(")");
            if (entered > 0) sb.append(" · kiritilgan ").append(entered);
            sb.append(":\n");
            int n = 0;
            for (OmborSanoq x : list) {
                if (++n > LIST_MAX) { sb.append("… yana ").append(list.size() - LIST_MAX).append(" ta\n"); break; }
                OmborTovar t = tovarRepo.findById(x.getProductMsId()).orElse(null);
                sb.append("• ").append(x.eski() ? "🐢 " : "").append(esc(t == null ? x.getProductMsId() : t.getName())).append(" — hisobda <b>").append(qty(x.getSystemQty())).append("</b>")
                  .append(t == null || t.getUom().isBlank() ? "" : " " + esc(t.getUom())).append("\n");
            }
            sb.append(RULE_TOP).append("\n<i>Har tovar uchun haqiqiy (sanalgan) miqdorni kiriting. Yakun ").append(cfg.sanoqYopishVaqt()).append(" da rahbarga yuboriladi.</i>");
            var kb = inline(List.of(irow(sbtn("🔢 Sanoqni boshlash", "om:sn:" + k.getId(), uz.kassa.bot.StyledButton.PRIMARY))));
            if (only != null) { rec.notifier().sendOne(only, sb.toString(), kb); sent++; continue; }
            Set<AppUser> to = new LinkedHashSet<>();
            for (Long id : cfg.roleUsers("ZAVSKLAD", k.getId())) rec.userRepo().findById(id).filter(AppUser::isActive).ifPresent(to::add);
            if (to.isEmpty()) to.addAll(rec.notifier().heads(k.getId()));
            if (to.isEmpty()) continue;
            rec.notifier().send(NotifySwitches.OM_SANOQ_SOROV, to, sb.toString(), kb);
            sent++;
        }
        return sent;
    }

    /** Kun yakuni: KIRITILDI → TASDIQ, REJA → OTKAZILDI (bugungacha hammasi); bugungi natija bo'lim rahbariga. */
    public int closeDay(LocalDate today) {
        int reports = 0;
        for (Kassa k : stores()) {
            for (OmborSanoq s : repo.findByKassaIdAndStatusInAndPlanDateLessThanEqualOrderByPlanDateAscIdAsc(k.getId(), OmborSanoq.OPEN, today)) {
                if (s.getFactQty() != null) { s.setStatus("TASDIQ"); if (s.getAt() == null) s.setAt(Instant.now()); }
                else { s.setStatus("OTKAZILDI"); s.setAt(Instant.now()); }
                repo.save(s);
            }
            List<OmborSanoq> list = repo.findByKassaIdAndPlanDateOrderByAbcAscIdAsc(k.getId(), today);
            if (list.isEmpty()) continue;
            Set<AppUser> to = new LinkedHashSet<>(rec.notifier().heads(k.getId()));
            if (to.isEmpty()) to.addAll(rec.notifier().superadmins());
            rec.notifier().send(NotifySwitches.OM_SANOQ_KUNLIK, to, dayReport(k, today, list), null);
            audit.log(null, "OMBOR_SANOQ_YAKUN", "kassa", k.getId(), DF.format(today) + ": " + list.size() + " ta, sanalmadi "
                    + list.stream().filter(x -> x.getFactQty() == null).count());
            reports++;
        }
        return reports;
    }

    /** Kun yakuni matni (rahbarga): jami · sanaldi · teng · farq (ro'yxat) · sanalmadi (ro'yxat). */
    public String dayReport(Kassa k, LocalDate day, List<OmborSanoq> list) {
        List<OmborSanoq> diff = new ArrayList<>(), miss = new ArrayList<>();
        int eq = 0;
        for (OmborSanoq x : list) { if (x.getFactQty() == null) miss.add(x); else if (x.diff()) diff.add(x); else eq++; }
        int counted = list.size() - miss.size();
        StringBuilder sb = new StringBuilder("🔢 <b>Sanoq yakuni</b> — " + esc(k.getName()) + " · " + DF.format(day) + "\n\n");
        sb.append("Reja <b>").append(list.size()).append("</b> · sanaldi <b>").append(counted).append("</b> · ✅ teng ").append(eq)
          .append(" · ⚠️ farq ").append(diff.size()).append(" · ❗️ sanalmadi ").append(miss.size()).append("\n");
        if (counted > 0) sb.append("Aniqlik: <b>").append(Math.round(100.0 * eq / counted)).append("%</b>");
        if (miss.isEmpty() && diff.isEmpty()) sb.append(" — hammasi teng ✅");
        sb.append("\n");
        if (!diff.isEmpty()) {
            sb.append(RULE_TOP).append("\n⚠️ <b>Farqlar</b>\n");
            int n = 0;
            for (OmborSanoq x : diff) {
                if (++n > 15) { sb.append("… yana ").append(diff.size() - 15).append(" ta (Excel)\n"); break; }
                BigDecimal d = x.getFactQty().subtract(x.getSystemQty());
                sb.append("• ").append(x.eski() ? "🐢 " : "").append(esc(name(x))).append(": hisob ").append(qty(x.getSystemQty())).append(" → fakt <b>").append(qty(x.getFactQty()))
                  .append("</b> · farq <b>").append(d.signum() > 0 ? "+" : "−").append(qty(d.abs())).append("</b>\n");
            }
        }
        if (!miss.isEmpty()) {
            sb.append(RULE_TOP).append("\n❗️ <b>Sanalmadi</b> (o'tkazildi)\n");
            int n = 0;
            for (OmborSanoq x : miss) {
                if (++n > 10) { sb.append("… yana ").append(miss.size() - 10).append(" ta\n"); break; }
                sb.append("• ").append(x.eski() ? "🐢 " : "").append(esc(name(x))).append("\n");
            }
        }
        Set<Long> who = new LinkedHashSet<>();
        for (OmborSanoq x : list) if (x.getByUserId() != null) who.add(x.getByUserId());
        if (!who.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Long id : who) names.add(rec.notifier().userName(id));
            sb.append("<i>Sanadi: ").append(esc(String.join(", ", names))).append("</i>");
        }
        return sb.toString();
    }

    /* ==================== haftalik Excel ==================== */

    /** [from..to] oralig'i: Хулоса (do'kon kesimida) · Санoq (barcha qatorlar) · Фарқлар. */
    public byte[] weeklyExcel(LocalDate from, LocalDate to) {
        List<OmborSanoq> all = repo.findByPlanDateBetweenOrderByKassaIdAscPlanDateAscIdAsc(from, to);
        Map<Long, int[]> sum = new LinkedHashMap<>();      // reja, sanaldi, teng, farq, sanalmadi
        for (Kassa k : stores()) sum.put(k.getId(), new int[5]);
        List<Object[]> rows = new ArrayList<>(), diffs = new ArrayList<>();
        for (OmborSanoq x : all) {
            int[] c = sum.computeIfAbsent(x.getKassaId(), i -> new int[5]);
            c[0]++;
            if (x.getFactQty() == null) c[4]++; else { c[1]++; if (x.diff()) c[3]++; else c[2]++; }
            OmborTovar t = tovarRepo.findById(x.getProductMsId()).orElse(null);
            BigDecimal d = x.getFactQty() == null ? null : x.getFactQty().subtract(x.getSystemQty());
            Object[] r = {rows.size() + 1, DF.format(x.getPlanDate()), rec.notifier().kassaName(x.getKassaId()), t == null ? x.getProductMsId() : t.getName(),
                    t == null ? "" : t.getCode(), t == null ? "" : t.getArticle(), x.eski() ? "🐢 eski" : x.getAbc(), x.getSystemQty(), x.getFactQty(), d,
                    OmborSanoq.statusTitle(x.getStatus()), x.getByUserId() == null ? "" : rec.notifier().userName(x.getByUserId()),
                    x.getAt() == null ? "" : DTF.format(x.getAt().atZone(cfg.zone()))};
            rows.add(r);
            if (d != null && d.signum() != 0) { Object[] r2 = r.clone(); r2[0] = diffs.size() + 1; diffs.add(r2); }
        }
        List<Object[]> s = new ArrayList<>();
        int[] tot = new int[5];
        for (var e : sum.entrySet()) {
            int[] c = e.getValue();
            if (c[0] == 0) continue;
            for (int i = 0; i < 5; i++) tot[i] += c[i];
            s.add(new Object[]{rec.notifier().kassaName(e.getKey()), c[0], c[1], c[2], c[3], c[4], pct(c[2], c[1]), pct(c[1], c[0])});
        }
        s.add(new Object[]{"ЖАМИ", tot[0], tot[1], tot[2], tot[3], tot[4], pct(tot[2], tot[1]), pct(tot[1], tot[0])});
        String[] cols = {"№", "Сана", "Дўкон", "Товар", "Код", "Артикул", "ABC", "Ҳисобда", "Фактда", "Фарқ", "Ҳолат", "Ким", "Вақт"};
        List<ExcelReportService.SheetDef> sheets = new ArrayList<>();
        sheets.add(new ExcelReportService.SheetDef("Хулоса", new String[]{"Дўкон", "Режа", "Саналди", "Тенг", "Фарқ", "Саналмади", "Аниқлик %", "Бажарилди %"}, s));
        sheets.add(new ExcelReportService.SheetDef("Санoq", cols, rows));
        if (!diffs.isEmpty()) sheets.add(new ExcelReportService.SheetDef("Фарқлар", cols, diffs));
        try { sheets.addAll(chempion.excelSheets()); } catch (Exception e) { log.warn("Chempion varaqlari: {}", e.getMessage()); }
        try { sheets.addAll(trend.excelSheets()); } catch (Exception e) { log.warn("Trend varag'i: {}", e.getMessage()); }
        try { sheets.addAll(partiya.excelSheets()); } catch (Exception e) { log.warn("Partiya varag'i: {}", e.getMessage()); }
        return excel.buildSheets(sheets);
    }

    /** Haftalik Excel: SuperAdmin + 📣 hisobot oluvchilar (🔕 kalit bilan). `only` — faqat shu odamga. Qaytadi: yuborilganlar soni. */
    public int sendWeekly(LocalDate from, LocalDate to, AppUser only) {
        Set<AppUser> users = new LinkedHashSet<>();
        if (only != null) users.add(only);
        else {
            users.addAll(rec.notifier().superadmins());
            for (Long id : cfg.hisobotIds()) rec.userRepo().findById(id).filter(AppUser::isActive).ifPresent(users::add);
        }
        List<AppUser> dest = only != null ? new ArrayList<>(users) : sw.filter(NotifySwitches.OM_SANOQ_HAFTALIK, users);
        if (dest.isEmpty()) return 0;
        byte[] data = weeklyExcel(from, to);
        long total = repo.findByPlanDateBetweenOrderByKassaIdAscPlanDateAscIdAsc(from, to).size();
        String caption = "📊 Sanoq haftalik hisoboti · " + DF.format(from) + " – " + DF.format(to) + " · " + total + " ta topshiriq";
        Set<Long> sent = new HashSet<>();
        int n = 0;
        for (AppUser u : dest) {
            if (u.getTelegramId() == null || !sent.add(u.getTelegramId())) continue;
            try { sender.sendDocument(u.getTelegramId(), data, "sanoq-hafta-" + from + "_" + to + ".xlsx", caption); n++; }
            catch (Exception e) { log.warn("Sanoq Excel yuborilmadi ({}): {}", u.getFullName(), e.getMessage()); }
        }
        return n;
    }

    /* ==================== yordamchi ==================== */

    private String name(OmborSanoq x) { return tovarRepo.findById(x.getProductMsId()).map(OmborTovar::getName).orElse(x.getProductMsId()); }
    public static String qty(BigDecimal v) { return v == null ? "—" : v.stripTrailingZeros().toPlainString(); }
    private static Object pct(int a, int b) { return b == 0 ? null : Math.round(100.0 * a / b); }

    public OmborSanoqRepo repo() { return repo; }
}
