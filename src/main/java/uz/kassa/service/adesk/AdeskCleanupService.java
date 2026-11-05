package uz.kassa.service.adesk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.domain.AdeskLink;
import uz.kassa.repo.AdeskLinkRepo;
import uz.kassa.service.adesk.AdeskClient.AdCommit;
import uz.kassa.service.adesk.AdeskHttp.AdeskException;
import uz.kassa.webapp.ExcelReportService.SheetDef;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import static uz.kassa.bot.TextUtil.esc;
import static uz.kassa.bot.TextUtil.fmtTiyin;

/**
 * 🧹 Adesk'dagi ortiqcha начислениялар (docs/ADESK.md §10). 2026-10-07…09 va 27–28.10 da kompyuterdagi ikkinchi bot nusxasi o'z eski bazasi
 * bilan ishlab, serverda allaqachon bor otgruzka/priyomkalarni qayta yaratgan: Adesk'da 4 384 ta ortiqcha yozuv, oktyabr Выручка 3,94 mlrd,
 * sentabr 808 mln oshgan. Bu yozuvlar serverning {@code adesk_link} jadvalida yo'q, shuning uchun bot ularni o'zi ko'rmas edi.
 *
 * Qoida: faqat otgruzka/priyomka formatidagi («MS Отгрузка №…») va bog'lanmagan yozuv ko'riladi. Hujjatning bog'langan nusxasi bor bo'lsa
 * yoki MoySklad'da hujjat yo'q bo'lsa — o'chiriladi. MoySklad'da bor, lekin bog'lanmagani tegilmaydi (keyingi sinxron unga bog'lanadi).
 * Pul operatsiyasi asosidagi Adesk yozuvlari (isShipment=false) hech qachon tanlanmaydi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdeskCleanupService {

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final int SHOW_ERRORS = 5;

    private final AdeskConfig cfg;
    private final AdeskClient ad;
    private final AdeskMsReader msr;
    private final AdeskLinkRepo repo;
    private final AdeskSyncService sync;

    public record Item(AdCommit commit, String head, String reason) {}

    /** blocked — nima uchun ro'yxat tuzilmadi (bo'lsa, o'chirishga ruxsat yo'q). */
    public record Plan(List<Item> remove, List<Item> keep, String blocked) {
        public long sumTiyin(int type) {
            return remove.stream().filter(i -> i.commit().type() == type).mapToLong(i -> AdeskSyncService.tiyin(i.commit().amount())).sum();
        }
        public long count(int type) { return remove.stream().filter(i -> i.commit().type() == type).count(); }
    }

    public record Outcome(int removed, int failed, List<String> errors, boolean aborted, String message) {}

    /** Bog'lanmagan otgruzka/priyomka yozuvlari: pul operatsiyasi asosidagilar va boshqa formatdagilar kirmaydi. */
    static List<AdCommit> orphans(List<AdCommit> all, Set<Long> linked) {
        return all.stream().filter(c -> c.shipment() && AdeskSyncService.goodsHead(c.description()) != null && !linked.contains(c.id())).toList();
    }

    private Set<Long> linkedIds() {
        Set<Long> s = new HashSet<>();
        for (AdeskLink l : repo.findByKind(AdeskLink.COMMIT)) if (AdeskSyncService.linked(l)) s.add(l.getAdeskId());
        return s;
    }

    private static Plan blocked(String why) { return new Plan(List.of(), List.of(), why); }

    /** Faqat o'qiydi: Adesk'ga hech narsa yozmaydi. */
    public Plan plan() {
        // Eskirgan bazada serverning to'g'ri yozuvlari «bog'lanmagan» bo'lib ko'rinadi — bunday nusxadan o'chirish taqiqlanadi
        String stale = sync.staleInstance();
        if (stale != null) return blocked(stale);
        Set<Long> linked = linkedIds();
        if (linked.isEmpty()) return blocked("bog'lanishlar jadvali bo'sh — ortiqcha yozuvni aniqlab bo'lmaydi");

        List<AdCommit> all = ad.commitments(cfg.start(), cfg.today());
        Set<String> linkedHeads = new HashSet<>();
        for (AdCommit c : all) {
            String h = AdeskSyncService.goodsHead(c.description());
            if (h != null && linked.contains(c.id())) linkedHeads.add(h);
        }
        Set<String> msHeads = new HashSet<>();
        for (String e : AdeskMsReader.GOODS_ENTITIES.keySet())
            for (AdeskMsReader.MsGoodsDoc d : msr.goodsDocs(e, cfg.start(), cfg.today(), null))
                msHeads.add(AdeskSyncService.commitHead(d));

        List<AdCommit> orphans = orphans(all, linked);
        if (!orphans.isEmpty() && msHeads.isEmpty())
            return blocked("MoySklad hujjatlari bo'sh o'qildi — o'chirishga asos yo'q");

        List<Item> remove = new ArrayList<>(), keep = new ArrayList<>();
        for (AdCommit c : orphans) {
            String h = AdeskSyncService.goodsHead(c.description());
            if (linkedHeads.contains(h)) remove.add(new Item(c, h, "Такрор: шу ҳужжатнинг боғланган нусхаси бор"));
            else if (!msHeads.contains(h)) remove.add(new Item(c, h, "MoySklad'да бу ҳужжат йўқ"));
            else keep.add(new Item(c, h, "MoySklad'да бор, боғланмаган — тегилмайди"));
        }
        return new Plan(remove, keep, null);
    }

    /**
     * Ro'yxat qayta tuziladi va u {@code expected} dan katta bo'lsa hech narsa o'chirilmaydi: SuperAdmin ko'rmagan yozuv o'chmasin.
     * Avval otgruzkalar, keyin priyomkalar — priyomka partiyasini ortiqcha otgruzka band qilib turmasin.
     */
    public Outcome execute(int expected, BooleanSupplier stop, IntConsumer progress) {
        Plan p = plan();
        if (p.blocked() != null) return new Outcome(0, 0, List.of(), true, p.blocked());
        if (p.remove().size() > expected)
            return new Outcome(0, 0, List.of(), true, "ro'yxat o'zgardi: tasdiqlangan " + expected + " ta, hozir " + p.remove().size() + " ta — qayta ko'rib chiqing");
        List<Item> order = new ArrayList<>(p.remove());
        order.sort(Comparator.comparingInt((Item i) -> i.commit().type() == 2 ? 0 : 1).thenComparingLong(i -> i.commit().id()));
        int removed = 0, failed = 0, done = 0;
        List<String> errors = new ArrayList<>();
        for (Item i : order) {
            if (stop.getAsBoolean()) break;
            try {
                ad.removeCommitment(i.commit().id());
                removed++;
            } catch (AdeskException e) {
                if (e.fatal) throw e;
                if (e.getMessage() != null && e.getMessage().contains("Не удается найти")) removed++;
                else {
                    failed++;
                    errors.add("#" + i.commit().id() + " " + i.head() + " — " + e.getMessage());
                    log.warn("Adesk ortiqcha majburiyati #{} o'chirilmadi: {}", i.commit().id(), e.getMessage());
                }
            }
            progress.accept(++done);
        }
        return new Outcome(removed, failed, errors, false, "");
    }

    /* ==================== matn va Excel ==================== */

    public String previewText(Plan p) {
        return "🧹 <b>Adesk: ortiqcha yozuvlar</b>\n\n"
                + "O'chiriladi: <b>" + p.remove().size() + "</b> ta\n"
                + "• Otgruzka: <b>" + p.count(2) + "</b> ta · <b>" + fmtTiyin(p.sumTiyin(2)) + "</b> so'm\n"
                + "• Priyomka: <b>" + p.count(1) + "</b> ta · <b>" + fmtTiyin(p.sumTiyin(1)) + "</b> so'm\n"
                + (p.keep().isEmpty() ? "" : "Tegilmaydi: <b>" + p.keep().size() + "</b> ta (MoySklad'da bor, bog'lanmagan)\n")
                + "━━━━━━━━━━━━━━━━━━━━\n"
                + "<i>Har hujjatning bog'langan nusxasi Adesk'da qoladi. To'liq ro'yxat Excel'da.</i>\n"
                + "O'chirilsinmi?";
    }

    public String resultText(Outcome o) {
        if (o.aborted()) return "⚠️ Hech narsa o'chirilmadi — " + esc(o.message());
        StringBuilder sb = new StringBuilder("✅ <b>Tozalash tugadi</b>\n\nO'chirildi: <b>" + o.removed() + "</b> ta");
        if (o.failed() > 0) {
            sb.append("\n⚠️ O'chirilmadi: <b>").append(o.failed()).append("</b> ta — qayta bosib ko'ring\n");
            o.errors().stream().limit(SHOW_ERRORS).forEach(e -> sb.append("• ").append(esc(e)).append("\n"));
        }
        return sb.append("\n<i>Natijani 🧮 Solishtirish bilan tekshiring.</i>").toString();
    }

    public List<SheetDef> sheets(Plan p) {
        String[] cols = {"Adesk ID", "Сана", "Тур", "Сумма", "Ҳужжат", "Проект", "Сабаб", "Изоҳ"};
        return List.of(new SheetDef("Ўчириладиган", cols, rows(p.remove())), new SheetDef("Тегилмайдиган", cols, rows(p.keep())));
    }

    private static List<Object[]> rows(List<Item> items) {
        List<Object[]> out = new ArrayList<>();
        for (Item i : items) {
            AdCommit c = i.commit();
            out.add(new Object[]{c.id(), c.date() == null ? "" : c.date().format(DF), c.type() == 2 ? "отгрузка" : "приёмка",
                    AdeskSyncService.tiyin(c.amount()) / 100.0, i.head(), c.projectId() == null ? "йўқ" : "бор", i.reason(), c.description()});
        }
        return out;
    }
}
