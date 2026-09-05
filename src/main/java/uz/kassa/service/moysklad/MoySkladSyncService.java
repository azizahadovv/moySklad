package uz.kassa.service.moysklad;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.bot.TextUtil;
import uz.kassa.config.AppProps;
import uz.kassa.domain.*;
import uz.kassa.repo.CategoryRepo;
import uz.kassa.repo.ClickAccountRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.repo.OperationRepo;
import uz.kassa.service.LedgerService;
import uz.kassa.service.NotificationService;
import uz.kassa.service.SettingsService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uz.kassa.service.moysklad.SyncSupport.Ctx;

/**
 * MoySklad -> tizim inkremental sinxronizatsiyasi (TZ 7.1, 11; v1.1).
 * Idempotent: moysklad_id UNIQUE, qayta o'qish dublikat yaratmaydi.
 *
 * Sinxronlanadigan hujjatlar:
 *   retaildemand         -> PRIXOD  (kassa, savdo nuqtasi bo'yicha)
 *   retailsalesreturn    -> VOZVRAT (kassa, savdo nuqtasi bo'yicha)
 *   retaildrawercashout  -> RASXOD  (kassa, NAQD)              — «Выплата денег»
 *   cashin               -> PRIXOD NAQD (kassa yoki Buxgalteriya) — «Приходный ордер», otdel bo'yicha
 *   paymentin («Клик»)   -> PRIXOD KLIK (kassa yoki Buxgalteriya) — «Входящий платеж», faqat «Клик» statusi
 *   cashout              -> RASXOD NAQD (kassa yoki Buxgalteriya) — «Расходный ордер», otdel bo'yicha
 *
 * Otdel (Владелец-отдел, group) kassaga kassa.moysklad_group_id orqali bog'lanadi.
 * Bog'lanmagan otdel hujjatlari Buxgalteriyaga yoziladi.
 *
 * O'ZGARISHLAR AVTOMATIK TUZATILADI (API bilan farq yig'ilib qolmasligi uchun):
 *   - summa o'zgargan          -> operatsiya/balans/kun farq bilan yangilanadi;
 *   - otdel o'zgargan          -> kirim ham, chiqim ham yangi egaga ko'chiriladi;
 *   - sana/pul turi o'zgargan  -> STORNO + yangidan yoziladi;
 *   - hujjat bekor qilingan / o'chirilgan / valyutaga o'tkazilgan -> STORNO.
 * O'chirilganlarni updated-filtri ko'rmaydi — buni reconcile() qamrab oladi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MoySkladSyncService {

    private final MoySkladClient client;
    private final LedgerService ledger;
    private final ClickAccountRepo clickRepo;
    private final OperationRepo opRepo;
    private final SettingsService settings;
    private final NotificationService notify;
    private final AppProps props;

    private volatile long lastAttempt = 0;

    /** Fon sinxroni uchun alohida oqim — foydalanuvchi so'rovini BLOKLAMAYDI. */
    private final java.util.concurrent.ExecutorService syncExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ms-sync");
                t.setDaemon(true);
                return t;
            });

    /** Token-huquq ogohlantirishi oxirgi yuborilgan vaqt (24 soatda 1 marta). */
    private volatile long lastPermWarnAt = 0;

    private static final String LAST_SYNC_KEY = "moysklad.lastSync";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    static final DateTimeFormatter D_UZ = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * Ledger boshlanish sanasi (kalibratsiya): boshlang'ich qoldiqlar shu sanaga
     * kiritilgan. Bundan eski, ledger'da YO'Q hujjatlar yangidan YOZILMAYDI —
     * ular kalibratsiya ichida allaqachon hisobga olingan (aks holda ikki marta
     * hisoblanadi). Lekin ledger'da YOZUVI BOR hujjatning HAR QANDAY o'zgarishi
     * (summa/otdel/sana/status/bekor qilish) sanasidan qat'i nazar DOIM qo'llanadi —
     * farq (delta) bilan ishlangani uchun kalibratsiyani buzmaydi.
     */
    /** Ledger boshlanish sanasi sozlamasi — bot ichidan o'zgartirilsa shu ustuvor, .env zaxira. */
    public static final String LEDGER_START_KEY = "moysklad.ledgerStart";

    /* ==================== PAYMENTIN (status bo'yicha pul turi) ==================== */

    /**
     * Входящий платеж — status bo'yicha ALOHIDA pul turlari:
     *   «Клик»           -> KLIK (kassir balansiga)
     *   «Картадан тулов» -> TERMINAL (faqat kun hisobotida — firma hisobiga tushadi)
     *   boshqa statuslar — bank o'tkazmalari, kassir puliga tegmaydi.
     */
    /** Sozlanadigan status nomlari (C1): MoySklad'da status qayta nomlansa, kodni
     *  emas, settings jadvalidagi shu kalit qiymatini yangilash kifoya. */
    public static final String KLIK_STATE_KEY = "moysklad.state.klik";
    public static final String TERMINAL_STATE_KEY = "moysklad.state.terminal";

    /* ==================== NAQD TEKSHIRUVI (faqat xabar) ==================== */

    static final String NAQD_AUDIT_KEY = "naqd.audit.lastReport";
    private final SyncSupport sup;
    private final MoySkladDocApplier applier;


    /** Amaldagi ledger boshlanish sanasi (admin panel ko'rsatishi uchun). MIN — cheklov yo'q. */
    public LocalDate effectiveEpoch() { return sup.epoch(); }


    /**
     * So'ralganda yangilash: eskirgan bo'lsa sinxron FONDA ishga tushadi, ko'rinish
     * esa darhol joriy ma'lumot bilan ochiladi (30 soniyalik rejali sinxron baribir
     * ishlab turadi). Avval bu joyda sinxron chaqiruv bo'lib, bitta foydalanuvchi
     * pul ko'rinishini ochsa hamma 5-15 soniya kutib qolardi.
     */
    public void syncIfStale(long maxAgeSec) {
        if (System.currentTimeMillis() - lastAttempt < maxAgeSec * 1000) return;
        lastAttempt = System.currentTimeMillis();   // navbatda to'planib qolmasin
        syncExec.submit(this::sync);
    }


    public synchronized void sync() {
        String token = client.currentToken();
        if (token == null || token.isBlank()) return;   // token yo'q — sinxron o'chiq
        lastAttempt = System.currentTimeMillis();

        LocalDateTime now = LocalDateTime.now(props.zoneId());
        LocalDateTime from = settings.get(LAST_SYNC_KEY)
                .filter(v -> !v.isBlank())
                .map(v -> LocalDateTime.parse(v, FMT))
                .orElse(now.minusDays(2))
                .minusMinutes(props.getMoysklad().getSyncOverlapMinutes());

        sup.resetQuietFixes();
        int n = 0;
        try {
            Ctx ctx = sup.buildCtx();

            for (MoySkladClient.MsDoc d : client.fetchSales("retaildemand", from))
                if (applier.applySale(d, ctx)) n++;
            for (MoySkladClient.MsDoc d : client.fetchSales("retailsalesreturn", from))
                if (applier.applyReturn(d, ctx)) n++;
            for (MoySkladClient.MsExpense e : client.fetchDrawerCashouts(from))
                if (applier.applyDrawerExpense(e, ctx)) n++;
            for (MoySkladClient.MsExpense e : client.fetchCashins(from))
                if (applier.applyIncome(e, MoneyType.NAQD, "ci:" + e.id(), ctx)) n++;
            for (MoySkladClient.MsExpense e : client.fetchPaymentsIn(from))
                if (applier.dispatchPaymentIn(e, ctx)) n++;
            for (MoySkladClient.MsExpense e : client.fetchCashouts(from))
                if (applier.applyCashout(e, ctx)) n++;
            for (MoySkladClient.MsExpense e : client.fetchPaymentsOut(from))
                if (applier.dispatchPaymentOut(e, ctx)) n++;
        } catch (Exception e) {
            // Watermark SURILMAYDI — o'qilmagan hujjatlar keyingi siklda qayta so'raladi
            log.warn("MoySklad sinxron uzildi (watermark saqlanmadi): {}", e.getMessage());
            sup.flushQuietFixes("sinxron");
            return;
        }

        settings.set(LAST_SYNC_KEY, now.format(FMT));
        sup.flushQuietFixes("sinxron");
        if (n > 0) log.info("MoySklad sinxron: {} ta yozuv/tuzatish", n);

        // M4: token huquqi yetmagan (401/403) so'rovlar bo'lgan bo'lsa — jim qolmaymiz,
        // 24 soatda bir marta SuperAdmin ogohlantiriladi (hujjatlar kirmayotgani sababi shu)
        if (client.last403At() > 0
                && System.currentTimeMillis() - client.last403At() < 3600_000L
                && System.currentTimeMillis() - lastPermWarnAt > 24 * 3600_000L) {
            lastPermWarnAt = System.currentTimeMillis();
            notify.toRole(uz.kassa.domain.Role.SUPERADMIN,
                    "🔑⚠️ MoySklad token HUQUQI yetmayapti (401/403) — ba'zi hujjatlar "
                    + "tizimga KIRMAYAPTI.\nOxirgi rad etilgan so'rov:\n<code>"
                    + TextUtil.esc(client.last403Url()) + "</code>\n"
                    + "MoySklad kabinetida token huquqlarini tekshiring.", null);
        }
    }


    /**
     * CHUQUR TEKSHIRUV (reconcile): oxirgi N kun (app.moysklad.reconcile-days) hujjatlari
     * API bilan to'liq solishtiriladi:
     *   (a) API'dagi har bir hujjat qayta qo'llanadi — tushib qolganlari yoziladi,
     *       o'zgargan summa/otdel/sana/status tuzatiladi;
     *   (b) bazada bor, lekin API ro'yxatida yo'q yozuvlar hujjat holati bo'yicha
     *       tekshirilib, O'CHIRILGAN/BEKOR QILINGANLARI STORNO qilinadi.
     * Bot to'xtab turgan davrdagi har qanday o'zgarish (shu jumladan o'chirishlar,
     * updated-filtri ularni ko'rmaydi) shu yerdan tiklanadi.
     */
    public synchronized void reconcile() {
        LocalDate to = LocalDate.now(props.zoneId());
        LocalDate from = to.minusDays(props.getMoysklad().getReconcileDays());
        LocalDate ep = sup.epoch();
        if (ep.isAfter(from)) from = ep;   // kalibratsiyadan oldingi davrga kirilmaydi
        reconcileRange(from, to);
    }


    /**
     * 📥 TO'LIQ QAYTA YUKLASH: barcha moliyaviy ma'lumotlar (operatsiyalar, kunlar,
     * hisobotlar, balanslar) O'CHIRILADI va MoySklad'dan ledger boshlanish sanasidan
     * bugungacha bo'lgan hujjatlar qaytadan tortiladi. Ledger sanasi belgilanmagan
     * bo'lsa ishlamaydi (butun tarixni tortib yubormaslik uchun).
     * @return qayta yuklangan hujjatlar soni; -1 — ledger sanasi belgilanmagan,
     *         -2 — MoySklad tokeni yo'q (hech narsa O'CHIRILMAYDI ham).
     */
    public synchronized int fullReload() {
        LocalDate ep = sup.epoch();
        if (ep.equals(LocalDate.MIN)) return -1;
        String token = client.currentToken();
        if (token == null || token.isBlank()) return -2;
        sup.setQuietReload(true);   // hujjatma-hujjat xabarlar yuborilmaydi — faqat yakuniy xulosa
        try {
            // BOSHLANG'ICH QOLDIQLAR SAQLANADI (foydalanuvchi qarori, M2): wipe'dan
            // oldin yig'ib olinadi va tozalashdan keyin avtomatik qayta qo'llanadi —
            // qo'lda qayta kiritish shart emas, balanslar MoySklad + boshlang'ich
            // qoldiq yig'indisiga teng chiqadi.
            record SavedInit(OwnerType ot, Long oid, MoneyType mt, long signed,
                             String comment, Long by, LocalDate date) {}
            java.util.List<SavedInit> saved = new java.util.ArrayList<>();
            for (Operation o : opRepo.findByStatusAndType(OpStatus.TASDIQLANGAN, OpType.BOSHLANGICH)) {
                if (o.getToOwnerType() != null)
                    saved.add(new SavedInit(o.getToOwnerType(), o.getToOwnerId(), o.getMoneyType(),
                            o.getAmount(), o.getComment(), o.getCreatedBy(), o.getOpDate()));
                else if (o.getFromOwnerType() != null)
                    saved.add(new SavedInit(o.getFromOwnerType(), o.getFromOwnerId(), o.getMoneyType(),
                            -o.getAmount(), o.getComment(), o.getCreatedBy(), o.getOpDate()));
            }
            ledger.wipeAllFinancialData();
            log.warn("TO'LIQ TOZALASH: barcha operatsiya/kun/hisobot o'chirildi, balanslar 0");
            for (SavedInit sv : saved)
                ledger.postAdjustment(OpType.BOSHLANGICH, sv.ot(), sv.oid(), sv.mt(), sv.signed(),
                        sv.comment() == null || sv.comment().isBlank()
                                ? "Boshlang'ich qoldiq (qayta yuklashda saqlandi)" : sv.comment(),
                        sv.by(), sv.date());
            if (!saved.isEmpty())
                log.info("Boshlang'ich qoldiqlar qayta qo'llandi: {} ta yozuv", saved.size());
            LocalDate to = LocalDate.now(props.zoneId());
            int n = reconcileRange(ep, to);
            calibrateClickToMoySklad();
            // Watermark hozirga suriladi — inkremental sinxron shu yerdan davom etadi
            settings.set(LAST_SYNC_KEY, LocalDateTime.now(props.zoneId()).format(FMT));
            return n;
        } finally {
            sup.setQuietReload(false);
        }
    }


    /**
     * Click hisoblarini MoySklad'ning JORIY qoldiqlariga (/report/money/byaccount)
     * tenglashtirish. Hujjatlar faqat ledger sanasidan beri sanalgani uchun undan
     * OLDINGI davr qoldig'i shu korrektirovka bilan kiradi — natijada bot balansi
     * MoySklad ko'rsatayotgan summaga aynan teng bo'ladi. Korrektirovka moysklad_id'siz
     * yoziladi, shuning uchun 3 soatlik avto-audit unga tegmaydi.
     */
    private void calibrateClickToMoySklad() {
        try {
            Map<String, Long> ms = client.fetchAccountBalances();
            if (ms.isEmpty()) {
                log.warn("MoySklad pul hisoboti bo'sh/ruxsat yo'q — Click tenglashtirish o'tkazildi");
                return;
            }
            for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
                String accId = c.getMoyskladAccountId();
                if (accId == null || accId.isBlank() || !ms.containsKey(accId)) continue;
                long target = ms.get(accId);
                long cur = ledger.view(OwnerType.CLICK, c.getId(), MoneyType.KLIK).getAmount();
                long diff = target - cur;
                if (diff == 0) continue;
                ledger.postAdjustment(OpType.KORREKTIROVKA, OwnerType.CLICK, c.getId(),
                        MoneyType.KLIK, diff,
                        "Qayta yuklash: MoySklad joriy qoldig'iga tenglashtirish", null, ledger.today());
                log.info("Click tenglashtirildi: {} {} -> {}", c.getName(), cur, target);
            }
            // NAQD tenglashtirilMAYDI (foydalanuvchi qarori, 2026-09-02): MoySklad CASH
            // bilan farq Основнойga korrektirovka qilib yozilmaydi — u kassa/Основной
            // qoldiqlarini buzar va sinxron bilan poyga tufayli har soat +X/-X
            // korrektirovka aylanasini keltirib chiqarar edi.
        } catch (Exception ex) {
            log.warn("Click qoldiqlarini tenglashtirish xatosi: {}", ex.getMessage());
        }
    }


    private synchronized int reconcileRange(LocalDate from, LocalDate to) {
        String token = client.currentToken();
        if (token == null || token.isBlank()) return 0;

        sup.resetQuietFixes();
        int n = 0;
        try {
            Ctx ctx = sup.buildCtx();
            record Ent(String entity, String prefix) {}
            for (Ent ent : List.of(new Ent("cashin", "ci:"),
                                   new Ent("paymentin", "pi:"),
                                   new Ent("cashout", "co:"),
                                   new Ent("paymentout", "po:"))) {
                List<MoySkladClient.MsExpense> docs =
                        client.fetchDocsByMoment(ent.entity(), from, to);

                java.util.Set<String> apiIds = new java.util.HashSet<>();
                for (MoySkladClient.MsExpense e : docs) {
                    apiIds.add(e.id());
                    boolean changed = switch (ent.entity()) {
                        case "cashin" -> applier.applyIncome(e, MoneyType.NAQD, "ci:" + e.id(), ctx);
                        case "paymentin" -> applier.dispatchPaymentIn(e, ctx);
                        case "paymentout" -> applier.dispatchPaymentOut(e, ctx);
                        default -> applier.applyCashout(e, ctx);
                    };
                    if (changed) n++;
                }

                for (Operation op : opRepo.findByOpDateBetweenAndMoyskladIdStartingWith(
                        from, to, ent.prefix())) {
                    String uuid = op.getMoyskladId().substring(ent.prefix().length());
                    if (apiIds.contains(uuid)) continue;
                    switch (client.fetchDocStatus(ent.entity(), uuid)) {
                        case DELETED -> { if (sup.storno(op, "hujjat MoySkladdan o'chirilgan", "")) n++; }
                        case UNAPPLIED -> { if (sup.storno(op, "hujjat o'tkazilishi bekor qilingan", "")) n++; }
                        default -> {} // OK (sanasi davr tashqarisiga ko'chgan) yoki ruxsat/tarmoq — tegilmaydi
                    }
                }
            }

            // RETAIL hujjatlar (sotuv / vozvrat / Выплата денег) — ilgari reconcile
            // qamrovida YO'Q edi: MoySklad'dan O'CHIRILGAN sotuv botda abadiy qolib
            // ketardi (updated-filtri o'chirishni ko'rmaydi). Endi (a) davr hujjatlari
            // qayta qo'llanadi, (b) bazada bor-u API ro'yxatida yo'q yozuvlar hujjat
            // holati bo'yicha tekshirilib, o'chirilganlari STORNO qilinadi.
            List<MoySkladClient.MsDoc> rdDocs = client.fetchSalesByMoment("retaildemand", from, to);
            for (MoySkladClient.MsDoc d : rdDocs) {
                if (applier.applySale(d, ctx)) n++;
            }
            n += stornoRetailOrphans("retaildemand", "rd:", idsOfSales(rdDocs), from, to);

            java.util.Set<String> rrIds = new java.util.HashSet<>();
            for (MoySkladClient.MsDoc d : client.fetchSalesByMoment("retailsalesreturn", from, to)) {
                rrIds.add(d.id());
                if (applier.applyReturn(d, ctx)) n++;
            }
            n += stornoRetailOrphans("retailsalesreturn", "rr:", rrIds, from, to);

            java.util.Set<String> dcIds = new java.util.HashSet<>();
            for (MoySkladClient.MsExpense e : client.fetchDrawerCashoutsByMoment(from, to)) {
                dcIds.add(e.id());
                if (applier.applyDrawerExpense(e, ctx)) n++;
            }
            for (Operation op : opRepo.findByOpDateBetweenAndMoyskladIdStartingWith(from, to, "dc:")) {
                String uuid = op.getMoyskladId().substring(3);
                if (dcIds.contains(uuid)) continue;
                switch (client.fetchDocStatus("retaildrawercashout", uuid)) {
                    case DELETED -> { if (sup.storno(op, "hujjat MoySkladdan o'chirilgan", "")) n++; }
                    case UNAPPLIED -> { if (sup.storno(op, "hujjat o'tkazilishi bekor qilingan", "")) n++; }
                    default -> { }
                }
            }
        } catch (Exception e) {
            log.warn("MoySklad reconcile uzildi: {}", e.getMessage());
            sup.flushQuietFixes("tekshiruv");
            return n;
        }
        sup.flushQuietFixes("tekshiruv");
        if (n > 0) log.info("MoySklad reconcile: {} ta yozuv/tuzatish", n);
        return n;
    }


    private java.util.Set<String> idsOfSales(List<MoySkladClient.MsDoc> docs) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (MoySkladClient.MsDoc d : docs) ids.add(d.id());
        return ids;
    }


    /**
     * Retail (rd:/rr:) yozuvlari uchun orphan-STORNO. msId formati «rd:&lt;uuid&gt;:n|:b»
     * — bitta hujjatning naqd va karta bo'laklari; hujjat holati bir marta so'raladi
     * (kesh) va faqat O'CHIRILGAN/BEKOR tasdiqlangandagina STORNO qilinadi.
     */
    private int stornoRetailOrphans(String entity, String prefix,
                                    java.util.Set<String> apiIds, LocalDate from, LocalDate to) {
        int n = 0;
        Map<String, MoySkladClient.DocStatus> statusCache = new HashMap<>();
        for (Operation op : opRepo.findByOpDateBetweenAndMoyskladIdStartingWith(from, to, prefix)) {
            String tail = op.getMoyskladId().substring(prefix.length());
            int colon = tail.lastIndexOf(':');
            String uuid = colon > 0 ? tail.substring(0, colon) : tail;
            if (apiIds.contains(uuid)) continue;
            MoySkladClient.DocStatus st = statusCache.computeIfAbsent(uuid,
                    x -> client.fetchDocStatus(entity, x));
            switch (st) {
                case DELETED -> { if (sup.storno(op, "hujjat MoySkladdan o'chirilgan", "")) n++; }
                case UNAPPLIED -> { if (sup.storno(op, "hujjat o'tkazilishi bekor qilingan", "")) n++; }
                default -> { }   // OK (sanasi ko'chgan) yoki ruxsat/tarmoq — tegilmaydi
            }
        }
        return n;
    }

}
