package uz.kassa.service.ombor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Kassa;
import uz.kassa.domain.OmborTovar;
import uz.kassa.repo.KassaRepo;
import uz.kassa.repo.OmborTovarRepo;
import uz.kassa.service.NotifySwitches;
import uz.kassa.webapp.ExcelReportService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.esc;
import static uz.kassa.bot.TextUtil.fmt;

/**
 * 🏆 Chempion tovarlar (2026-09-12 qarori): har do'kon (va kompaniya) uchun oxirgi 30 kunda eng ko'p sotilgan
 * ombor.chempion_soni ta tovar; ulardan nechtasi hozir javonda bor = do'kon fill rate (CHEMPION_FILL, kunlik, tunda).
 * 2026-09-14: sotuv = faqat NAQD otgruzkalar (SOTUV_*, {@link OmborSalesService}) — Перечисление va katta hujjatlar kirmaydi.
 * Kompaniya A sinfi bo'yicha FILL_RATE_30 qo'shimcha ko'rsatkich sifatida qoladi (OmborCalcService).
 * Ko'rinish: bot 🏬 Омбор → 🏆 Чемпионлар; ertalab (ombor.chempion_vaqt) zakupshik + rahbarga javonda YO'Q chempionlar;
 * haftalik sanoq Excel'ida «Чемпионлар» va «Fill rate» varaqlari.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OmborChempionService {

    public static final String CHEMPION_FILL = "CHEMPION_FILL";   // do'kon (kassa>0) va kompaniya (0): javonda bor % (kunlik)
    public static final int KUN = 30;
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String RULE_TOP = "━━━━━━━━━━━━━━━━━━━━";

    private final OmborMetrics metrics;
    private final KassaRepo kassaRepo;
    private final OmborTovarRepo tovarRepo;
    private final OmborConfig cfg;
    private final OmborRecipients rec;
    private final ExcelReportService excel;
    private final uz.kassa.repo.OmborStoreRepo storeRepo;
    private final OmborSalesService sales;

    /** Ekranlarda ombor nomi MoySklad'dagidek («Склад Зуфар»); bo'lim nomi boshqacha bo'lsa qavsda («Склад Шохрух (Отдел Камера)»). */
    public String storeName(long kassaId) {
        String kn = rec.notifier().kassaName(kassaId);
        return storeRepo.findFirstByKassaId(kassaId).map(s -> {
            String a = s.getName().replace("Склад ", "").trim(), b = kn.replace("Отдел ", "").trim();
            return a.equalsIgnoreCase(b) ? s.getName() : s.getName() + " (" + kn + ")";
        }).orElse(kn);
    }

    /** Barcha omborlar bo'yicha qisqa xulosa: «Склад Зуфар 30% · Склад Абдулло 80%» (null — ma'lumot yo'q). */
    public String fillSummary() {
        List<String> parts = new ArrayList<>();
        for (Kassa k : stores()) { Integer p = fillPct(list(k.getId())); if (p != null) parts.add(storeName(k.getId()) + " " + p + "%"); }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    /**
     * Bitta chempion (30 kun): sotildi (dona), tushum/tannarx/foyda (tiyin — MoySklad hujjat summalari, narx × dona emas),
     * qaytdi (dona), keldi (dona: priyomka + do'konga ko'chirish), 30 kun oldingi qoldiq (start — hujjatlardan tiklanadi), hozirgi qoldiq.
     * Balans: start + keldi + qaytdi − sotildi − boshqa = qoldiq; boshqa — bank/katta hujjat sotuvi, ko'chirish, spisaniya (qoldiq).
     */
    public record Row(int rank, String pid, String name, String article, BigDecimal qty, long summa, long tannarx, long foyda,
                      BigDecimal qaytdi, BigDecimal keldi, BigDecimal stock, BigDecimal start) {
        public boolean bor() { return stock != null && stock.signum() > 0; }
        /** Naqd sotuvdan tashqari chiqim (musbat) yoki kirim (manfiy): start + keldi + qaytdi − qty − stock. */
        public BigDecimal boshqa() { return start.add(keldi).add(qaytdi).subtract(qty).subtract(stock); }
        /** Foyda % (tushumga nisbatan), null — tushum 0. */
        public Integer marja() { return summa == 0 ? null : (int) Math.round(100.0 * foyda / summa); }
    }

    /** Tartib kalitlari: foyda (standart — eng ko'p foyda keltirganlar yuqorida), qty (dona), summa (tushum). */
    public static final String SORT_FOYDA = "foyda", SORT_QTY = "qty", SORT_SUMMA = "summa";
    public static String sortTitle(String s) { return switch (s == null ? "" : s) { case SORT_QTY -> "📦 dona"; case SORT_SUMMA -> "💵 tushum"; default -> "💰 foyda"; }; }
    public static String sortOf(String s) { return s == null || s.isBlank() ? SORT_FOYDA : s; }

    public List<Row> list(long kassaId) { return list(kassaId, SORT_FOYDA); }

    /** Do'kon (yoki 0 — kompaniya) chempionlari (top-N tanlangan kalit bo'yicha), qoldiq va kelgan miqdor bilan. */
    public List<Row> list(long kassaId, String sort) {
        LocalDate from = LocalDate.now(cfg.zone()).minusDays(KUN);
        Map<String, BigDecimal> stock = new HashMap<>();
        for (Map<String, Object> m : metrics.latest(OmborMetrics.QOLDIQ, "kassa_id = ? AND product_ms_id <> ''", kassaId))
            stock.put((String) m.get("product_ms_id"), (BigDecimal) m.get("value"));
        Map<String, BigDecimal> keldi = metrics.arrivedSince(kassaId, from);
        Map<String, BigDecimal> net = metrics.netFlowSince(kassaId, from);
        List<Row> out = new ArrayList<>();
        for (Object[] r : metrics.topSoldRows(kassaId, from, cfg.chempionSoni(), sortOf(sort))) {
            String pid = (String) r[0];
            OmborTovar t = tovarRepo.findById(pid).orElse(null);
            if (t != null && t.isArchived()) continue;
            out.add(new Row(out.size() + 1, pid, t == null ? pid : t.getName(), t == null ? "" : t.getArticle(),
                    (BigDecimal) r[1], ((BigDecimal) r[2]).longValue(), ((BigDecimal) r[3]).longValue(), ((BigDecimal) r[4]).longValue(),
                    (BigDecimal) r[5], keldi.getOrDefault(pid, BigDecimal.ZERO), stock.getOrDefault(pid, BigDecimal.ZERO),
                    stock.getOrDefault(pid, BigDecimal.ZERO).subtract(net.getOrDefault(pid, BigDecimal.ZERO))));
        }
        return out;
    }

    /** Jami: [sotildi, tushum, foyda]. */
    public static long[] totals(List<Row> rows) {
        long q = 0, s = 0, f = 0;
        for (Row r : rows) { q += r.qty().longValue(); s += r.summa(); f += r.foyda(); }
        return new long[]{q, s, f};
    }

    /** Javonda bor % (null — chempion yo'q). */
    public static Integer fillPct(List<Row> rows) {
        if (rows.isEmpty()) return null;
        return (int) Math.round(100.0 * rows.stream().filter(Row::bor).count() / rows.size());
    }

    /** Oxirgi 30 kun CHEMPION_FILL o'rtachasi (kunlik yozuvlardan), null — hali yo'q. */
    public Integer fillAvg30(long kassaId) {
        return metrics.avgSince(CHEMPION_FILL, kassaId, LocalDate.now(cfg.zone()).minusDays(KUN));
    }

    /** Kompaniya A sinfi bo'yicha fill rate (FILL_RATE_30), null — yo'q. */
    public BigDecimal fillA(long kassaId) {
        for (Map<String, Object> m : metrics.latest(OmborCalcService.FILL_RATE_30, "kassa_id = ? AND product_ms_id = ''", kassaId))
            return (BigDecimal) m.get("value");
        return null;
    }

    public List<Kassa> stores() {
        List<Kassa> out = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) if (k.getMoyskladWarehouseId() != null) out.add(k);
        return out;
    }

    /* ==================== tunlik: CHEMPION_FILL ==================== */

    /** Har do'kon va kompaniya uchun bugungi javonda-bor % ni yozish (tunlik zanjir, qoldiqdan keyin). */
    public int nightly() {
        LocalDate d = LocalDate.now(cfg.zone());
        List<OmborMetrics.Row> rows = new ArrayList<>();
        for (Kassa k : stores()) {
            Integer p = fillPct(list(k.getId()));
            if (p != null) rows.add(new OmborMetrics.Row(d, k.getId(), "", CHEMPION_FILL, BigDecimal.valueOf(p)));
        }
        metrics.clear(d, CHEMPION_FILL);
        metrics.upsert(rows);
        return rows.size();
    }

    /* ==================== bot matni ==================== */

    public static final int PAGE = 8;
    public static int pages(int n) { return Math.max(1, (n + PAGE - 1) / PAGE); }

    /** 🏆 ekran matni: sarlavha (javonda bor %, jami sotuv/tushum/foyda) + har tovar 3 qator: nom (to'liq) · dona · pul; PAGE tadan sahifalab. */
    public String screen(long kassaId, String sort, int page) {
        sort = sortOf(sort);
        LocalDate from = LocalDate.now(cfg.zone()).minusDays(KUN);
        List<Row> rows = list(kassaId, sort);
        StringBuilder sb = new StringBuilder("🏆 <b>Chempion tovarlar</b> — " + esc(storeName(kassaId)) + " · " + KUN + " kun\n<i>Tartib: ")
                .append(sortTitle(sort)).append(" bo'yicha, yuqoridan pastga</i>\n\n");
        if (rows.isEmpty()) { sb.append(sales.naqdReady() ? "Bu oynada naqd sotuv yo'q." : "⏳ Otgruzka tarixi yuklanmoqda — birinchi hisob tugagach ko'rinadi."); return sb.toString(); }
        long bor = rows.stream().filter(Row::bor).count();
        long[] t = totals(rows);
        sb.append("Javonda bor: <b>").append(bor).append("/").append(rows.size()).append("</b> (").append(fillPct(rows)).append("%)");
        Integer avg = fillAvg30(kassaId);
        if (avg != null) sb.append(" · 30 kun o'rtacha ").append(avg).append("%");
        sb.append("\nJami: sotildi <b>").append(fmt(t[0])).append("</b> dona · tushum <b>").append(pul(t[1])).append("</b> · foyda <b>").append(pulSign(t[2])).append("</b>")
          .append(t[1] > 0 ? " (" + Math.round(100.0 * t[2] / t[1]) + "%)" : "").append("\n");
        long[] ex = sales.excludedStats(kassaId, from);
        if (ex[1] + ex[2] > 0) sb.append("🚫 Tahlilga kirmagan: bank ").append(ex[1]).append(" ta · ").append(pul(ex[4]))
              .append(ex[2] > 0 ? " · katta hujjat " + ex[2] + " ta · " + pul(ex[5]) : "").append("\n");
        int pages = pages(rows.size());
        page = Math.max(0, Math.min(page, pages - 1));
        sb.append(RULE_TOP).append(pages > 1 ? " (" + (page + 1) + "/" + pages + ")" : "").append("\n");
        int shown = 0;
        for (Row r : rows.subList(page * PAGE, Math.min(rows.size(), (page + 1) * PAGE))) {
            String item = r.rank() + ". " + (r.bor() ? "🟢 " : "🔴 ") + "<b>" + esc(r.name()) + "</b>\n"
                    + "    " + balans(r) + "\n"
                    + "    tushum " + pul(r.summa()) + " · foyda <b>" + pulSign(r.foyda()) + "</b>" + (r.marja() == null ? "" : " (" + r.marja() + "%)") + "\n";
            if (sb.length() + item.length() > 3700) { sb.append("… yana ").append(rows.size() - page * PAGE - shown).append(" ta — 📥 Excel\n"); break; }
            sb.append(item); shown++;
        }
        sb.append(RULE_TOP).append("\n<i>");
        if (bor < rows.size()) sb.append("🔴 — javonda yo'q: buyurtma yoki ko'chirish kerak.\n");
        sb.append("Faqat naqd savdo (").append(esc(String.join(", ", cfg.naqdStates()))).append(")")
          .append(cfg.sotuvMaxHujjat() == 0 ? "" : ", bitta hujjat ≤ " + pul(cfg.sotuvMaxHujjat() * 100) + " so'm")
          .append("; manba — MoySklad otgruzka hujjatlari, tannarx — foyda hisoboti, qoldiq — report/stock, keldi — priyomka va ko'chirish; ")
          .append("«boshqa» — bank/katta hujjat sotuvi, ko'chirish, spisaniya. ").append(KUN).append(" kun.</i>");
        return sb.toString();
    }

    /* ==================== ertalabki xabar ==================== */

    /** Kuniga bir marta ombor.chempion_vaqt dan keyin: har do'kon uchun javonda yo'q chempionlar → zakupshik + do'kon rahbari. */
    public void tick() {
        if (!cfg.enabled()) return;
        LocalDate today = LocalDate.now(cfg.zone());
        if (LocalTime.now(cfg.zone()).isBefore(cfg.chempionVaqt())) return;
        if (today.toString().equals(cfg.get(OmborConfig.CHEMPION_SENT).orElse(""))) return;
        cfg.set(OmborConfig.CHEMPION_SENT, today.toString());
        try { sendDaily(today, null); } catch (Exception e) { log.warn("Chempion xabari: {}", e.getMessage()); }
    }

    /** Har do'kon uchun xabar. `only` — faqat shu odamga (admin sinovi). Qaytadi: yuborilgan do'konlar soni. */
    public int sendDaily(LocalDate today, AppUser only) {
        int sent = 0;
        for (Kassa k : stores()) {
            List<Row> rows = list(k.getId());
            if (rows.isEmpty()) continue;
            List<Row> yoq = rows.stream().filter(r -> !r.bor()).toList();
            StringBuilder sb = new StringBuilder("🏆 <b>Chempionlar</b> — " + esc(storeName(k.getId())) + " · " + DF.format(today) + "\n\n");
            sb.append("Javonda bor: <b>").append(rows.size() - yoq.size()).append("/").append(rows.size()).append("</b> (").append(fillPct(rows)).append("%)");
            Integer avg = fillAvg30(k.getId());
            if (avg != null) sb.append(" · 30 kun o'rtacha ").append(avg).append("%");
            long[] t = totals(rows);
            sb.append("\n").append(KUN).append(" kun: sotildi <b>").append(fmt(t[0])).append("</b> dona · tushum <b>").append(pul(t[1])).append("</b> · foyda <b>").append(pulSign(t[2])).append("</b>\n");
            if (yoq.isEmpty()) sb.append("Hamma chempion javonda ✅");
            else {
                sb.append(RULE_TOP).append("\n🔴 <b>Javonda yo'q</b> (").append(yoq.size()).append(" ta):\n");
                int n = 0;
                for (Row r : yoq) {
                    if (++n > 20 || sb.length() > 3400) { sb.append("… yana ").append(yoq.size() - n + 1).append(" ta — 🏆 Ro'yxat\n"); break; }
                    sb.append("• ").append(esc(r.name())).append(" — sotildi <b>").append(qty(r.qty())).append("</b> · foyda ").append(pulSign(r.foyda())).append("\n");
                }
                sb.append("<i>Buyurtma yoki boshqa do'kondan ko'chirish kerak.</i>");
            }
            var kb = inline(List.of(irow(btn("🏆 Ro'yxat", "om:ch:" + k.getId()), btn("🏬 Омбор", "om:m"))));
            if (only != null) { rec.notifier().sendOne(only, sb.toString(), kb); sent++; continue; }
            Set<AppUser> to = new LinkedHashSet<>();
            for (Long id : cfg.roleUsers("ZAKUPSHIK", k.getId())) rec.userRepo().findById(id).filter(AppUser::isActive).ifPresent(to::add);
            to.addAll(rec.notifier().heads(k.getId()));
            if (to.isEmpty()) to.addAll(rec.notifier().superadmins());
            rec.notifier().send(NotifySwitches.OM_CHEMPION_KUNLIK, to, sb.toString(), kb);
            sent++;
        }
        return sent;
    }

    /* ==================== Excel ==================== */

    /** «Чемпионлар» (do'kon kesimida top-N, qoldiq bilan) va «Fill rate» (do'kon: chempion bugun / 30 kun / A sinf) varaqlari. */
    public List<ExcelReportService.SheetDef> excelSheets() {
        List<Object[]> ch = new ArrayList<>(), fr = new ArrayList<>();
        List<Kassa> all = new ArrayList<>(stores());
        for (Kassa k : all) {
            List<Row> rows = list(k.getId());
            String sn = storeName(k.getId());
            for (Row r : rows) ch.add(xl(ch.size() + 1, sn, r));
            Integer avg = fillAvg30(k.getId()); BigDecimal a = fillA(k.getId());
            fr.add(new Object[]{sn, rows.size(), rows.stream().filter(Row::bor).count(), fillPct(rows), avg, a});
        }
        List<ExcelReportService.SheetDef> sheets = new ArrayList<>();
        sheets.add(new ExcelReportService.SheetDef("Чемпионлар", new String[]{"№", "Склад (MoySklad)", "Ўрин", "Товар", "Артикул", KUN + " кун олдин", "Келди", "Қайтди",
                "Сотилди нақд (" + KUN + " кун)", "Бошқа чиқим", "Қолдиқ", "Жавонда", "Тушум (сўм)", "Таннарх (сўм)", "Фойда (сўм)", "Фойда %"}, ch));
        sheets.add(new ExcelReportService.SheetDef("Fill rate", new String[]{"Склад (MoySklad)", "Чемпион сони", "Жавонда бор", "Бугун %", "30 кун ўртача %", "Компания A синфи %"}, fr));
        return sheets;
    }

    public byte[] excel() { return excel.buildSheets(excelSheets()); }

    /* ==================== yordamchi ==================== */

    private static Object[] xl(int n, String store, Row r) {
        return new Object[]{n, store, r.rank(), r.name(), r.article(), r.start(), r.keldi(), r.qaytdi(), r.qty(), r.boshqa(), r.stock(), r.bor() ? "бор" : "ЙЎҚ",
                r.summa() / 100.0, r.tannarx() / 100.0, r.foyda() / 100.0, r.marja()};
    }

    /** «30 kun oldin 3 + keldi 2 − sotildi 5 = qoldiq 0» (qaytdi va boshqa — bo'lsa). */
    private static String balans(Row r) {
        StringBuilder sb = new StringBuilder(KUN + " kun oldin <b>" + qty(r.start()) + "</b>");
        if (r.keldi().signum() != 0) sb.append(" + keldi <b>").append(qty(r.keldi())).append("</b>");
        if (r.qaytdi().signum() > 0) sb.append(" + qaytdi ").append(qty(r.qaytdi()));
        sb.append(" − sotildi <b>").append(qty(r.qty())).append("</b>");
        BigDecimal b = r.boshqa();
        if (b.signum() > 0) sb.append(" − boshqa ").append(qty(b));
        else if (b.signum() < 0) sb.append(" + boshqa ").append(qty(b.negate()));
        return sb.append(" = qoldiq <b>").append(qty(r.stock())).append("</b>").toString();
    }

    private static String qty(BigDecimal v) { return v == null ? "0" : v.stripTrailingZeros().toPlainString(); }
    /** Tiyin → o'qish oson pul: 1.2 mlrd · 38.3 mln · 655 000 (so'm). */
    public static String pul(long tiyin) {
        long som = Math.abs(tiyin) / 100;
        String s = som >= 1_000_000_000L ? String.format(java.util.Locale.ROOT, "%.2f mlrd", som / 1e9)
                : som >= 1_000_000L ? String.format(java.util.Locale.ROOT, "%.1f mln", som / 1e6) : fmt(som);
        return (tiyin < 0 ? "−" : "") + s;
    }
    private static String pulSign(long tiyin) { return tiyin < 0 ? pul(tiyin) : "+" + pul(tiyin); }
    private static String cut(String s, int n) { return s.length() > n ? s.substring(0, n - 1) + "…" : s; }
}
