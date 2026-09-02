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

    private static final String LAST_SYNC_KEY = "moysklad.lastSync";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter D_UZ = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final MoySkladClient client;
    private final LedgerService ledger;
    private final KassaRepo kassaRepo;
    private final CategoryRepo categoryRepo;
    private final ClickAccountRepo clickRepo;
    private final OperationRepo opRepo;
    private final SettingsService settings;
    private final NotificationService notify;
    private final AppProps props;

    private volatile long lastAttempt = 0;

    /**
     * Xabar toshqiniga qarshi: BUGUNGI hujjat tuzatishlari alohida xabar bilan,
     * ESKI (o'tgan kunlardagi) hujjat tuzatishlari esa faqat sanab boriladi va
     * sikl oxirida bitta umumlashma xabar yuboriladi. sync()/reconcile()
     * synchronized bo'lgani uchun oddiy int yetarli.
     */
    private int quietFixes = 0;

    /** 📥 Qayta yuklash davomida hujjatma-hujjat xabarlar butunlay o'chiriladi. */
    private volatile boolean quietReload = false;

    /** Tuzatish xabarini yuborish kerakmi: bugungi hujjat — ha; eski — sanaladi. */
    private boolean loudFix(LocalDate docDate) {
        if (!quietReload && docDate.equals(ledger.today())) return true;
        quietFixes++;
        return false;
    }

    /** Sikl oxirida jim tuzatishlar bo'yicha bitta umumlashma xabar. */
    private void flushQuietFixes(String source) {
        int n = quietFixes;
        quietFixes = 0;
        if (n > 0) notify.toBuxgalteriya("🔧 MoySklad " + source + ": o'tgan kunlardagi <b>" + n
                + "</b> ta hujjat o'zgarishi (otdel/summa/storno) avtomatik tuzatildi. "
                + "Tafsilotlar: Настройка → 📋 Аудит.", null);
    }

    /** So'm (UZS) valyuta UUID lari — boshqa valyutadagi hujjatlar kurs bilan so'mga o'giriladi. */
    private volatile java.util.Set<String> somCurrencyIds = java.util.Set.of();

    /** Valyuta UUID -> ISO kod (USD, RUB...) — xabar/izohlarda ko'rsatish uchun. */
    private volatile java.util.Map<String, String> currencyIso = java.util.Map.of();

    /** «Kurs kiritilmagan» ogohlantirishi har hujjat uchun bir marta yuboriladi. */
    private final java.util.Set<String> noRateWarned =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Bir siklda qayta ishlatiladigan xaritalar. */
    private record Ctx(Map<String, Long> storeToKassa, Map<String, Long> groupToKassa,
                       Map<String, String> groupNames, Map<String, Long> catByName,
                       java.util.Set<String> dupGroups, Map<String, Long> accountToClick) {}

    /** Dublikat-otdel ogohlantirishi oxirgi yuborilgan vaqt (6 soatda 1 marta). */
    private volatile long lastDupWarnAt = 0;

    private Ctx buildCtx() {
        Map<String, Long> storeToKassa = new HashMap<>();
        Map<String, Long> groupToKassa = new HashMap<>();
        // Bitta otdel bir nechta faol kassaga biriktirilgan bo'lsa — sozlash xatosi:
        // hujjatlar kassalar orasida sakramasligi uchun ENG BIRINCHI (kichik id)
        // kassa qoladi, otdel-o'zgarish (reroute) esa bu guruh uchun to'xtatiladi.
        java.util.Set<String> dupGroups = new java.util.HashSet<>();
        Map<String, java.util.List<String>> dupNames = new HashMap<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.getMoyskladStoreId() != null && !k.getMoyskladStoreId().isBlank())
                storeToKassa.put(k.getMoyskladStoreId(), k.getId());
            if (k.getMoyskladGroupId() != null && !k.getMoyskladGroupId().isBlank()) {
                String g = k.getMoyskladGroupId();
                if (groupToKassa.containsKey(g)) {
                    dupGroups.add(g);
                    dupNames.computeIfAbsent(g, x -> new java.util.ArrayList<>(java.util.List.of(
                            kassaRepo.findById(groupToKassa.get(g)).map(Kassa::getName).orElse("?"))))
                            .add(k.getName());
                } else groupToKassa.put(g, k.getId());
            }
        }
        Map<String, String> groupNames = client.fetchGroups();

        if (!dupGroups.isEmpty() && System.currentTimeMillis() - lastDupWarnAt > 6 * 3600_000L) {
            lastDupWarnAt = System.currentTimeMillis();
            StringBuilder sb = new StringBuilder("⚠️ <b>SOZLASH XATOSI</b> — bitta MoySklad otdeli "
                    + "bir nechta kassaga biriktirilgan:\n");
            for (String g : dupGroups)
                sb.append("\n• <b>").append(TextUtil.esc(groupNames.getOrDefault(g, g)))
                  .append("</b>: ").append(TextUtil.esc(String.join(", ", dupNames.get(g))));
            sb.append("\n\nHujjatlar eng birinchi kassaga yoziladi, otdel-ko'chirishlar esa "
                    + "TO'XTATILDI (xabar yog'ilib ketmasligi uchun). Sheets «Kassalar» varag'ida "
                    + "yoki bazada otdelni faqat bitta kassada qoldiring.");
            notify.toRole(uz.kassa.domain.Role.SUPERADMIN, sb.toString(), null);
        }

        java.util.Map<String, String> isoMap = client.fetchCurrencies();
        if (!isoMap.isEmpty()) {   // xatoda eski ro'yxat saqlanadi — filtr o'chib qolmasin
            java.util.Set<String> som = new java.util.HashSet<>();
            isoMap.forEach((id, iso) -> {
                if (iso.equalsIgnoreCase("UZS")) som.add(id);
            });
            somCurrencyIds = som;
            currencyIso = isoMap;
        }

        // «Статья расходов» nomi tizim kategoriyasiga mos kelsa — avtomatik biriktiriladi
        Map<String, Long> catByName = new HashMap<>();
        for (Category c : categoryRepo.findByActiveTrueOrderByIdAsc())
            catByName.put(c.getName().trim().toLowerCase(), c.getId());

        // MoySklad "organizationAccount" -> Click hisobi: shu hisobga tushgan Клик
        // to'lovlari otdel/kassa o'rniga aynan shu hisobga alohida yoziladi.
        Map<String, Long> accountToClick = new HashMap<>();
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc())
            if (c.getMoyskladAccountId() != null && !c.getMoyskladAccountId().isBlank())
                accountToClick.put(c.getMoyskladAccountId(), c.getId());

        return new Ctx(storeToKassa, groupToKassa, groupNames, catByName, dupGroups, accountToClick);
    }

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

    private LocalDate epoch() {
        String s = settings.get(LEDGER_START_KEY).orElse("").trim();
        if (s.isBlank()) s = props.getMoysklad().getLedgerStartDate();
        if (s == null || s.isBlank()) return LocalDate.MIN;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            log.warn("ledger-start-date noto'g'ri format: {}", s);
            return LocalDate.MIN;
        }
    }

    /** Amaldagi ledger boshlanish sanasi (admin panel ko'rsatishi uchun). MIN — cheklov yo'q. */
    public LocalDate effectiveEpoch() { return epoch(); }

    /** Kalibratsiyadan oldingi, ledger'da yo'q hujjat — yangidan yozilmaydi. */
    private boolean skipNewPreEpoch(LocalDate docDate, Optional<Operation> existing) {
        return existing.isEmpty() && docDate.isBefore(epoch());
    }

    /** So'mda emasligini aniqlash (hujjat valyutasi UZS emas). */
    private boolean notSom(String currencyId) {
        return !somCurrencyIds.isEmpty() && !currencyId.isEmpty()
                && !somCurrencyIds.contains(currencyId);
    }

    /**
     * Hujjat summasi SO'MDA: so'm hujjat — o'z summasi; valyuta hujjat —
     * MoySklad'da kiritilgan kurs (rate.value) bilan so'mga o'giriladi
     * (masalan $1 000 × 12 000 = 12 000 000). Kurs KIRITILMAGAN valyuta
     * hujjatida -1 qaytadi — tizim o'zidan kurs taxmin qilmaydi.
     */
    private long somSum(MoySkladClient.MsExpense e) {
        if (!notSom(e.currencyId())) return e.sumTiyin() / 100;
        if (e.rateValue() <= 0) return -1;
        return Math.round(e.sumTiyin() / 100.0 * e.rateValue());
    }

    /** Valyuta hujjati izohi uchun belgi: «💱 1 000 USD × 12 000». */
    private String fxNote(MoySkladClient.MsExpense e) {
        if (!notSom(e.currencyId())) return "";
        String iso = currencyIso.getOrDefault(e.currencyId(), "valyuta");
        return "💱 " + TextUtil.fmt(e.sumTiyin() / 100) + " " + iso + " × " + trimRate(e.rateValue());
    }

    private String trimRate(double v) {
        return v == Math.rint(v) ? TextUtil.fmt((long) v) : String.valueOf(v);
    }

    /** Kurs kiritilmagan valyuta hujjati — bir marta ogohlantirish. */
    private void warnNoRate(MoySkladClient.MsExpense e) {
        if (!noRateWarned.add(e.id())) return;
        String iso = currencyIso.getOrDefault(e.currencyId(), "valyuta");
        notify.toBuxgalteriya("💱⚠️ MoySklad hujjatida <b>valyuta kursi kiritilmagan</b> — "
                + "tizimga o'tkazilmadi:\n" + docInfo(e)
                + "\nSumma: <b>" + TextUtil.fmt(e.sumTiyin() / 100) + " " + TextUtil.esc(iso)
                + "</b>\nMoySklad'da kursni kiriting — keyingi sinxronda avtomatik kiradi.", null);
    }

    /** Fon sinxroni uchun alohida oqim — foydalanuvchi so'rovini BLOKLAMAYDI. */
    private final java.util.concurrent.ExecutorService syncExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ms-sync");
                t.setDaemon(true);
                return t;
            });

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

        quietFixes = 0;
        int n = 0;
        try {
            Ctx ctx = buildCtx();

            for (MoySkladClient.MsDoc d : client.fetchSales("retaildemand", from))
                if (applySale(d, ctx)) n++;
            for (MoySkladClient.MsDoc d : client.fetchSales("retailsalesreturn", from))
                if (applyReturn(d, ctx)) n++;
            for (MoySkladClient.MsExpense e : client.fetchDrawerCashouts(from))
                if (applyDrawerExpense(e, ctx)) n++;
            for (MoySkladClient.MsExpense e : client.fetchCashins(from))
                if (applyIncome(e, MoneyType.NAQD, "ci:" + e.id(), ctx)) n++;
            for (MoySkladClient.MsExpense e : client.fetchPaymentsIn(from))
                if (dispatchPaymentIn(e, ctx)) n++;
            for (MoySkladClient.MsExpense e : client.fetchCashouts(from))
                if (applyCashout(e, ctx)) n++;
            for (MoySkladClient.MsExpense e : client.fetchPaymentsOut(from))
                if (dispatchPaymentOut(e, ctx)) n++;
        } catch (Exception e) {
            // Watermark SURILMAYDI — o'qilmagan hujjatlar keyingi siklda qayta so'raladi
            log.warn("MoySklad sinxron uzildi (watermark saqlanmadi): {}", e.getMessage());
            flushQuietFixes("sinxron");
            return;
        }

        settings.set(LAST_SYNC_KEY, now.format(FMT));
        flushQuietFixes("sinxron");
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

    /** Token-huquq ogohlantirishi oxirgi yuborilgan vaqt (24 soatda 1 marta). */
    private volatile long lastPermWarnAt = 0;

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
        LocalDate ep = epoch();
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
        LocalDate ep = epoch();
        if (ep.equals(LocalDate.MIN)) return -1;
        String token = client.currentToken();
        if (token == null || token.isBlank()) return -2;
        quietReload = true;   // hujjatma-hujjat xabarlar yuborilmaydi — faqat yakuniy xulosa
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
            quietReload = false;
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

        quietFixes = 0;
        int n = 0;
        try {
            Ctx ctx = buildCtx();
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
                        case "cashin" -> applyIncome(e, MoneyType.NAQD, "ci:" + e.id(), ctx);
                        case "paymentin" -> dispatchPaymentIn(e, ctx);
                        case "paymentout" -> dispatchPaymentOut(e, ctx);
                        default -> applyCashout(e, ctx);
                    };
                    if (changed) n++;
                }

                for (Operation op : opRepo.findByOpDateBetweenAndMoyskladIdStartingWith(
                        from, to, ent.prefix())) {
                    String uuid = op.getMoyskladId().substring(ent.prefix().length());
                    if (apiIds.contains(uuid)) continue;
                    switch (client.fetchDocStatus(ent.entity(), uuid)) {
                        case DELETED -> { if (storno(op, "hujjat MoySkladdan o'chirilgan", "")) n++; }
                        case UNAPPLIED -> { if (storno(op, "hujjat o'tkazilishi bekor qilingan", "")) n++; }
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
                if (applySale(d, ctx)) n++;
            }
            n += stornoRetailOrphans("retaildemand", "rd:", idsOfSales(rdDocs), from, to);

            java.util.Set<String> rrIds = new java.util.HashSet<>();
            for (MoySkladClient.MsDoc d : client.fetchSalesByMoment("retailsalesreturn", from, to)) {
                rrIds.add(d.id());
                if (applyReturn(d, ctx)) n++;
            }
            n += stornoRetailOrphans("retailsalesreturn", "rr:", rrIds, from, to);

            java.util.Set<String> dcIds = new java.util.HashSet<>();
            for (MoySkladClient.MsExpense e : client.fetchDrawerCashoutsByMoment(from, to)) {
                dcIds.add(e.id());
                if (applyDrawerExpense(e, ctx)) n++;
            }
            for (Operation op : opRepo.findByOpDateBetweenAndMoyskladIdStartingWith(from, to, "dc:")) {
                String uuid = op.getMoyskladId().substring(3);
                if (dcIds.contains(uuid)) continue;
                switch (client.fetchDocStatus("retaildrawercashout", uuid)) {
                    case DELETED -> { if (storno(op, "hujjat MoySkladdan o'chirilgan", "")) n++; }
                    case UNAPPLIED -> { if (storno(op, "hujjat o'tkazilishi bekor qilingan", "")) n++; }
                    default -> { }
                }
            }
        } catch (Exception e) {
            log.warn("MoySklad reconcile uzildi: {}", e.getMessage());
            flushQuietFixes("tekshiruv");
            return n;
        }
        flushQuietFixes("tekshiruv");
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
                case DELETED -> { if (storno(op, "hujjat MoySkladdan o'chirilgan", "")) n++; }
                case UNAPPLIED -> { if (storno(op, "hujjat o'tkazilishi bekor qilingan", "")) n++; }
                default -> { }   // OK (sanasi ko'chgan) yoki ruxsat/tarmoq — tegilmaydi
            }
        }
        return n;
    }

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

    private String klikState() {
        return settings.get(KLIK_STATE_KEY).filter(v -> !v.isBlank()).orElse("Клик");
    }

    private String terminalState() {
        return settings.get(TERMINAL_STATE_KEY).filter(v -> !v.isBlank()).orElse("Картадан тулов");
    }

    /** Klik'ga o'xshagan, lekin sozlangan nomga mos kelmagan statuslar — bir marta ogohlantiriladi. */
    private final java.util.Set<String> unknownStateWarned =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private MoneyType paymentMt(String state) {
        return state.equalsIgnoreCase(klikState()) ? MoneyType.KLIK
                : state.equalsIgnoreCase(terminalState()) ? MoneyType.TERMINAL
                : null;
    }

    private boolean dispatchPaymentIn(MoySkladClient.MsExpense e, Ctx ctx) {
        MoneyType mt = paymentMt(e.state());
        if (mt == null) {
            // C1: status Klik'ga O'XSHAYDI-yu sozlangan nomga mos emas — ehtimol
            // MoySklad'da status QAYTA NOMLANGAN. Jimgina o'tkazib yubormaymiz —
            // aks holda Click kirimlar indamay to'xtab qolardi.
            String low = e.state().toLowerCase();
            if ((low.contains("клик") || low.contains("klik") || low.contains("click"))
                    && unknownStateWarned.add(e.state())) {
                notify.toRole(uz.kassa.domain.Role.SUPERADMIN,
                        "⚠️ MoySklad'da Входящий платеж statusi «" + TextUtil.esc(e.state())
                        + "» uchradi — Klik'ga o'xshaydi, lekin sozlangan nom «"
                        + TextUtil.esc(klikState()) + "»ga mos EMAS.\n"
                        + "Status qayta nomlangan bo'lsa, bu to'lovlar tizimga KIRMAYAPTI!\n"
                        + "Yechim: MoySklad'da statusni eski nomiga qaytaring, yoki bazadagi "
                        + "settings «" + KLIK_STATE_KEY + "» qiymatini yangi nomga o'zgartiring.", null);
            }
            // Status endi Klik/Kartadan emas — avval yozilgan bo'lsa STORNO
            Optional<Operation> existing = opRepo.findByMoyskladId("pi:" + e.id());
            if (skipNewPreEpoch(e.date(), existing)) return false;
            return existing.isPresent()
                    && storno(existing.get(), "status «" + e.state() + "»ga o'zgargan", docInfo(e));
        }
        return applyIncome(e, mt, "pi:" + e.id(), ctx);
    }

    /* ==================== SOTUV / VOZVRAT (retail) ==================== */

    private boolean applySale(MoySkladClient.MsDoc d, Ctx ctx) {
        Long kassaId = ctx.storeToKassa().get(d.storeId());
        boolean a = syncRetailPart(kassaId, MoneyType.NAQD, d.cashTiyin() / 100, d, "rd:" + d.id() + ":n", true);
        boolean b = syncRetailPart(kassaId, MoneyType.TERMINAL, d.noCashTiyin() / 100, d, "rd:" + d.id() + ":b", true);
        return a || b;
    }

    private boolean applyReturn(MoySkladClient.MsDoc d, Ctx ctx) {
        Long kassaId = ctx.storeToKassa().get(d.storeId());
        boolean a = syncRetailPart(kassaId, MoneyType.NAQD, d.cashTiyin() / 100, d, "rr:" + d.id() + ":n", false);
        boolean b = syncRetailPart(kassaId, MoneyType.TERMINAL, d.noCashTiyin() / 100, d, "rr:" + d.id() + ":b", false);
        // TZ 14: vozvrat balansni manfiyga tushirsa — buxgalterga signal.
        if (a && kassaId != null) checkNegative(OwnerType.KASSA, kassaId, "Vozvrat");
        return a || b;
    }

    /** Sotuv/vozvratning bitta bo'lagi (naqd yoki karta): STORNO / summa tuzatish / yangi yozuv. */
    private boolean syncRetailPart(Long kassaId, MoneyType mt, long sum,
                                   MoySkladClient.MsDoc d, String msId, boolean isSale) {
        Optional<Operation> existing = opRepo.findByMoyskladId(msId);
        if (skipNewPreEpoch(d.date(), existing)) return false;

        if (!d.applicable() || sum <= 0) {
            return existing.isPresent()
                    && storno(existing.get(),
                        !d.applicable() ? "hujjat o'tkazilishi bekor qilingan" : "summa nolga tushirilgan", "");
        }

        if (existing.isPresent()) {
            Operation op = existing.get();
            if (!op.getOpDate().equals(d.date())) {
                if (!storno(op, "hujjat sanasi o'zgargan — qayta yoziladi", "")) return false;
                // pastda yangi sana bilan qayta yoziladi
            } else {
                if (op.getAmount() != sum && ledger.updateSyncAmount(op, sum)) {
                    if (loudFix(d.date()))
                        notify.toBuxgalteriya("✏️ MoySklad hujjat summasi o'zgargan — avtomatik tuzatildi: "
                                + msId + " — yangi: " + TextUtil.fmt(sum) + " so'm", null);
                    return true;
                }
                return false;
            }
        }

        if (kassaId == null) {
            log.debug("Noma'lum MoySklad store {} — {} o'tkazib yuborildi", d.storeId(), msId);
            return false;
        }
        return isSale ? ledger.postPrixod(kassaId, mt, sum, d.date(), msId)
                      : ledger.postVozvrat(kassaId, mt, sum, d.date(), msId);
    }

    /* ==================== RASXODLAR (TZ v1.1) ==================== */

    /** «Выплата денег» — savdo nuqtasi kassasidan NAQD chiqim (fakt, tasdiqsiz). */
    private boolean applyDrawerExpense(MoySkladClient.MsExpense e, Ctx ctx) {
        long sum = somSum(e);   // valyuta hujjati MoySklad kursi bilan so'mga o'giriladi
        String msId = "dc:" + e.id();
        String fx = fxNote(e);
        String comment = joinNote(e.expenseItem(), e.description());
        if (!fx.isEmpty()) comment = comment.isEmpty() ? fx : fx + " · " + comment;
        Optional<Operation> existing = opRepo.findByMoyskladId(msId);
        if (skipNewPreEpoch(e.date(), existing)) return false;

        if (sum < 0) {   // valyuta hujjati, kurs kiritilmagan — taxmin qilinmaydi
            warnNoRate(e);
            return existing.isPresent()
                    && storno(existing.get(), "valyuta kursi kiritilmagan", docInfo(e));
        }
        if (!e.applicable() || sum == 0) {
            return existing.isPresent()
                    && storno(existing.get(),
                        !e.applicable() ? "hujjat o'tkazilishi bekor qilingan" : "summa nolga tushirilgan", "");
        }

        if (existing.isPresent()) {
            Operation op = existing.get();
            if (!op.getOpDate().equals(e.date())) {
                if (!storno(op, "hujjat sanasi o'zgargan — qayta yoziladi", "")) return false;
            } else {
                if (op.getAmount() != sum) {
                    long old = op.getAmount();
                    if (ledger.updateSyncAmount(op, sum)) {
                        if (loudFix(e.date()))
                            notify.toBuxgalteriya("✏️ MoySklad rasxod summasi o'zgargan — avtomatik tuzatildi:\n"
                                    + "<b>" + TextUtil.esc(ownerName(op)) + "</b>: " + TextUtil.fmt(old)
                                    + " → " + TextUtil.fmt(sum) + " so'm (💵 Naqd)", null);
                        return true;
                    }
                }
                return false;
            }
        }

        Long kassaId = ctx.storeToKassa().get(e.storeId());
        if (kassaId == null) {
            log.debug("Noma'lum store {} — Выплата {} o'tkazib yuborildi", e.storeId(), e.id());
            return false;
        }
        boolean posted = ledger.postRasxodSync(OwnerType.KASSA, kassaId, MoneyType.NAQD,
                sum, e.date(), msId, matchCat(ctx.catByName(), e.expenseItem()), comment);
        if (posted) {
            String kassaName = kassaRepo.findById(kassaId).map(Kassa::getName).orElse("Kassa #" + kassaId);
            notify.toBuxgalteriya("💸 MoySklad rasxodi: <b>" + TextUtil.esc(kassaName)
                    + "</b> — <b>" + TextUtil.fmt(sum) + "</b> so'm (💵 Naqd)"
                    + (comment.isEmpty() ? "" : "\n" + TextUtil.esc(comment)), null);
            checkNegative(OwnerType.KASSA, kassaId, "Rasxod");
        }
        return posted;
    }

    /**
     * «Расходный ордер» — otdel (group) bog'langan kassaga, aks holda BUXGALTERIYAGA yoziladi.
     * O'zgarishlar avtomatik tuzatiladi: summa (delta), otdel (reroute), sana (storno+qayta).
     */
    private boolean applyCashout(MoySkladClient.MsExpense e, Ctx ctx) {
        long sum = somSum(e);   // valyuta hujjati MoySklad kursi bilan so'mga o'giriladi
        String msId = "co:" + e.id();
        String fx = fxNote(e);
        String comment = joinNote(e.expenseItem(), e.description());
        if (!fx.isEmpty()) comment = comment.isEmpty() ? fx : fx + " · " + comment;
        Long kassaId = ctx.groupToKassa().get(e.groupId());
        OwnerType wantOt = kassaId != null ? OwnerType.KASSA : OwnerType.BUXGALTERIYA;
        Long wantOid = kassaId != null ? kassaId : LedgerService.BUX_ID;
        Optional<Operation> existing = opRepo.findByMoyskladId(msId);
        if (skipNewPreEpoch(e.date(), existing)) return false;

        if (sum < 0) {   // valyuta hujjati, kurs kiritilmagan — taxmin qilinmaydi
            warnNoRate(e);
            return existing.isPresent()
                    && storno(existing.get(), "valyuta kursi kiritilmagan", docInfo(e));
        }
        if (!e.applicable() || sum == 0) {
            return existing.isPresent()
                    && storno(existing.get(),
                        !e.applicable() ? "hujjat o'tkazilishi bekor qilingan"
                                : "summa nolga tushirilgan",
                        docInfo(e));
        }

        if (existing.isPresent()) {
            Operation op = existing.get();
            if (!op.getOpDate().equals(e.date())) {
                if (!storno(op, "hujjat sanasi o'zgargan — qayta yoziladi", docInfo(e))) return false;
                // pastda yangi sana bilan qayta yoziladi
            } else {
                boolean changed = false;
                if (op.getAmount() != sum) {
                    long old = op.getAmount();
                    if (ledger.updateSyncAmount(op, sum)) {
                        if (loudFix(e.date()))
                            notify.toBuxgalteriya("✏️ MoySklad rasxod summasi o'zgargan — avtomatik tuzatildi:\n"
                                    + docInfo(e) + "\n<b>" + TextUtil.esc(ownerName(op)) + "</b>: "
                                    + TextUtil.fmt(old) + " → " + TextUtil.fmt(sum) + " so'm (💵 Naqd)", null);
                        changed = true;
                    }
                }
                // Dublikat-otdel (bir otdel bir nechta kassada): egasi noaniq — ko'chirilmaydi
                if ((op.getFromOwnerType() != wantOt
                        || !java.util.Objects.equals(op.getFromOwnerId(), wantOid))
                        && !ctx.dupGroups().contains(e.groupId())) {
                    String oldName = ownerName(op);
                    if (ledger.rerouteRasxod(op, wantOt, wantOid)) {
                        String newName = wantOt == OwnerType.BUXGALTERIYA ? "Отдел Основной"
                                : kassaRepo.findById(wantOid).map(Kassa::getName).orElse("Kassa #" + wantOid);
                        if (loudFix(e.date()))
                            notify.toBuxgalteriya("🔀 MoySklad hujjat otdeli o'zgartirilgan — chiqim ko'chirildi:\n"
                                    + docInfo(e) + "\n<b>" + TextUtil.esc(oldName) + "</b> → <b>"
                                    + TextUtil.esc(newName) + "</b> · " + TextUtil.fmt(sum) + " so'm (💵 Naqd)", null);
                        log.info("Rasxod qayta yo'naltirildi: {} {} -> {}", msId, oldName, newName);
                        changed = true;
                    }
                }
                return changed;
            }
        }

        boolean posted = ledger.postRasxodSync(wantOt, wantOid, MoneyType.NAQD,
                sum, e.date(), msId, matchCat(ctx.catByName(), e.expenseItem()), comment);
        if (posted && shouldNotify(e)) {
            if (wantOt == OwnerType.KASSA) {
                String kassaName = kassaRepo.findById(wantOid).map(Kassa::getName).orElse("Kassa #" + wantOid);
                String text = "💸 MoySklad rasxodi: <b>" + TextUtil.esc(kassaName)
                        + "</b> — <b>" + TextUtil.fmt(sum) + "</b> so'm (💵 Naqd)"
                        + "\n" + docInfo(e)
                        + (comment.isEmpty() ? "" : "\n" + TextUtil.esc(comment));
                notify.toKassa(wantOid, text, null);
                notify.toBuxgalteriya(text, null);
            } else {
                String otdel = ctx.groupNames().getOrDefault(e.groupId(), "");
                notify.toBuxgalteriya("💸 MoySklad rasxodi (Расходный ордер): <b>Отдел Основной</b>"
                        + (otdel.isEmpty() ? "" : " · " + TextUtil.esc(otdel))
                        + " — <b>" + TextUtil.fmt(sum) + "</b> so'm (💵 Naqd)"
                        + "\n" + docInfo(e)
                        + (comment.isEmpty() ? "" : "\n" + TextUtil.esc(comment)), null);
            }
            checkNegative(wantOt, wantOid, "Rasxod");
        }
        return posted;
    }

    /* ==================== KIRIMLAR ==================== */

    /**
     * Kirim (Приходный ордер — NAQD, Входящий платеж «Клик»/«Картадан тулов»):
     * otdel bog'langan kassaga, aks holda BUXGALTERIYAGA.
     * O'zgarishlar avtomatik tuzatiladi: summa (delta), otdel (reroute),
     * sana/pul turi (storno + qayta yozish), bekor qilingan hujjat (storno).
     */
    private boolean applyIncome(MoySkladClient.MsExpense e, MoneyType mt, String msId, Ctx ctx) {
        long sum = somSum(e);   // valyuta hujjati MoySklad kursi bilan so'mga o'giriladi
        Long kassaId = ctx.groupToKassa().get(e.groupId());
        OwnerType wantOt = kassaId != null ? OwnerType.KASSA : OwnerType.BUXGALTERIYA;
        Long wantOid = kassaId != null ? kassaId : LedgerService.BUX_ID;
        // Клик to'lovi — MoySklad hisobi (organizationAccount) bo'yicha nomlangan Click
        // hisobiga bog'langan bo'lsa, otdel/kassa o'rniga aynan shu hisobga yoziladi
        // (bitta otdelda bir nechta klik hisobi bo'lishi mumkin — aralashib ketmasin).
        if (mt == MoneyType.KLIK) {
            Long clickId = ctx.accountToClick().get(e.accountId());
            if (clickId != null) { wantOt = OwnerType.CLICK; wantOid = clickId; }
        }
        String fx = fxNote(e);
        String comment = joinNote(e.agent(), e.description());
        if (!fx.isEmpty()) comment = comment.isEmpty() ? fx : fx + " · " + comment;
        String mtLabel = mtLabel(mt);
        Optional<Operation> existing = opRepo.findByMoyskladId(msId);
        if (skipNewPreEpoch(e.date(), existing)) return false;

        if (sum < 0) {   // valyuta hujjati, kurs kiritilmagan — taxmin qilinmaydi
            warnNoRate(e);
            return existing.isPresent()
                    && storno(existing.get(), "valyuta kursi kiritilmagan", docInfo(e));
        }
        if (!e.applicable() || sum == 0) {
            return existing.isPresent()
                    && storno(existing.get(),
                        !e.applicable() ? "hujjat o'tkazilishi bekor qilingan"
                                : "summa nolga tushirilgan",
                        docInfo(e));
        }

        if (existing.isPresent()) {
            Operation op = existing.get();
            if (op.getMoneyType() != mt || !op.getOpDate().equals(e.date())) {
                if (!storno(op, "pul turi/sana o'zgargan — qayta yoziladi", docInfo(e))) return false;
                // pastda yangi qiymatlar bilan qayta yoziladi
            } else {
                boolean changed = false;
                if (op.getAmount() != sum) {
                    long old = op.getAmount();
                    if (ledger.updateSyncAmount(op, sum)) {
                        if (loudFix(e.date()))
                            notify.toBuxgalteriya("✏️ MoySklad kirim summasi o'zgargan — avtomatik tuzatildi:\n"
                                    + docInfo(e) + "\n<b>" + TextUtil.esc(ownerName(op)) + "</b>: "
                                    + TextUtil.fmt(old) + " → " + TextUtil.fmt(sum)
                                    + " so'm (" + mtLabel + ")", null);
                        changed = true;
                    }
                }
                // Dublikat-otdel (bir otdel bir nechta kassada): egasi noaniq — ko'chirilmaydi
                if ((op.getToOwnerType() != wantOt
                        || !java.util.Objects.equals(op.getToOwnerId(), wantOid))
                        && !ctx.dupGroups().contains(e.groupId())) {
                    String oldName = ownerName(op);
                    if (ledger.reroutePrixod(op, wantOt, wantOid)) {
                        String newName = ownerDisplayName(wantOt, wantOid);
                        if (loudFix(e.date()))
                            notify.toBuxgalteriya("🔀 MoySklad hujjat otdeli o'zgartirilgan — kirim ko'chirildi:\n"
                                    + docInfo(e) + "\n<b>" + TextUtil.esc(oldName) + "</b> → <b>"
                                    + TextUtil.esc(newName) + "</b> · " + TextUtil.fmt(sum)
                                    + " so'm (" + mtLabel + ")", null);
                        log.info("Kirim qayta yo'naltirildi: {} {} -> {}", msId, oldName, newName);
                        changed = true;
                    }
                }
                return changed;
            }
        }

        boolean posted = ledger.postPrixodSync(wantOt, wantOid, mt, sum, e.date(), msId, comment);
        if (posted && shouldNotify(e)) {
            if (wantOt == OwnerType.KASSA) {
                String kassaName = ownerDisplayName(wantOt, wantOid);
                String text = "💰 MoySklad kirim: <b>" + TextUtil.esc(kassaName)
                        + "</b> — <b>" + TextUtil.fmt(sum) + "</b> so'm (" + mtLabel + ")"
                        + "\n" + docInfo(e);
                notify.toKassa(wantOid, text, null);
                notify.toBuxgalteriya(text, null);
            } else if (wantOt == OwnerType.CLICK) {
                notify.toBuxgalteriya("💰 MoySklad kirim: <b>" + TextUtil.esc(ownerDisplayName(wantOt, wantOid))
                        + "</b> — <b>" + TextUtil.fmt(sum) + "</b> so'm (" + mtLabel + ")"
                        + "\n" + docInfo(e), null);
            } else {
                String otdel = ctx.groupNames().getOrDefault(e.groupId(), "");
                notify.toBuxgalteriya("💰 MoySklad kirim: <b>Отдел Основной</b>"
                        + (otdel.isEmpty() ? "" : " · " + TextUtil.esc(otdel))
                        + " — <b>" + TextUtil.fmt(sum) + "</b> so'm (" + mtLabel + ")"
                        + "\n" + docInfo(e), null);
            }
        }
        return posted;
    }

    /* ==================== KLIK RASXOD (Исходящий платеж) ==================== */

    /**
     * «Исходящий платеж» — bu hujjat turi BUTUN GURUH (14 ta tashkilot) umumiy
     * moliyaviy oqimi uchun ishlatiladi (bank o'tkazmalari, tashkilotlararo
     * hisob-kitob va h.k.) — kassir Click hisoblariga DEXLI aloqasi yo'q hujjatlar
     * ham shu yerdan o'tadi. Shuning uchun faqat MoySklad hisobi (organizationAccount)
     * bizning ro'yxatdagi Click hisobiga (click_accounts) ANIQ bog'langan hujjatlar
     * qabul qilinadi — aks holda hujjat butunlay e'tiborsiz qoldiriladi (otdel/
     * Buxgalteriyaga TUSHIRILMAYDI, applyCashout'dan farqli — u yerda hujjat bizning
     * o'z savdo nuqtalarimizga tegishli, bu yerda esa yo'q).
     */
    private boolean dispatchPaymentOut(MoySkladClient.MsExpense e, Ctx ctx) {
        if (!ctx.accountToClick().containsKey(e.accountId())) return false;
        return applyPaymentOutKlik(e, ctx);
    }

    /** «Исходящий платеж» — faqat ro'yxatdagi Click hisobiga (organizationAccount) yozilgan chiqim. */
    private boolean applyPaymentOutKlik(MoySkladClient.MsExpense e, Ctx ctx) {
        long sum = somSum(e);
        String msId = "po:" + e.id();
        String fx = fxNote(e);
        String comment = joinNote(e.agent(), e.description());
        if (!fx.isEmpty()) comment = comment.isEmpty() ? fx : fx + " · " + comment;

        Long kassaId = ctx.groupToKassa().get(e.groupId());
        OwnerType wantOt = kassaId != null ? OwnerType.KASSA : OwnerType.BUXGALTERIYA;
        Long wantOid = kassaId != null ? kassaId : LedgerService.BUX_ID;
        Long clickId = ctx.accountToClick().get(e.accountId());
        if (clickId != null) { wantOt = OwnerType.CLICK; wantOid = clickId; }

        Optional<Operation> existing = opRepo.findByMoyskladId(msId);
        if (skipNewPreEpoch(e.date(), existing)) return false;

        if (sum < 0) {   // valyuta hujjati, kurs kiritilmagan — taxmin qilinmaydi
            warnNoRate(e);
            return existing.isPresent()
                    && storno(existing.get(), "valyuta kursi kiritilmagan", docInfo(e));
        }
        if (!e.applicable() || sum == 0) {
            return existing.isPresent()
                    && storno(existing.get(),
                        !e.applicable() ? "hujjat o'tkazilishi bekor qilingan"
                                : "summa nolga tushirilgan",
                        docInfo(e));
        }

        if (existing.isPresent()) {
            Operation op = existing.get();
            if (!op.getOpDate().equals(e.date())) {
                if (!storno(op, "hujjat sanasi o'zgargan — qayta yoziladi", docInfo(e))) return false;
            } else {
                boolean changed = false;
                if (op.getAmount() != sum) {
                    long old = op.getAmount();
                    if (ledger.updateSyncAmount(op, sum)) {
                        if (loudFix(e.date()))
                            notify.toBuxgalteriya("✏️ MoySklad Klik rasxodi summasi o'zgargan — avtomatik tuzatildi:\n"
                                    + docInfo(e) + "\n<b>" + TextUtil.esc(ownerName(op)) + "</b>: "
                                    + TextUtil.fmt(old) + " → " + TextUtil.fmt(sum) + " so'm (📲 Klik)", null);
                        changed = true;
                    }
                }
                if ((op.getFromOwnerType() != wantOt
                        || !java.util.Objects.equals(op.getFromOwnerId(), wantOid))
                        && !ctx.dupGroups().contains(e.groupId())) {
                    String oldName = ownerName(op);
                    if (ledger.rerouteRasxod(op, wantOt, wantOid)) {
                        String newName = ownerDisplayName(wantOt, wantOid);
                        if (loudFix(e.date()))
                            notify.toBuxgalteriya("🔀 MoySklad hujjat otdeli o'zgartirilgan — Klik chiqim ko'chirildi:\n"
                                    + docInfo(e) + "\n<b>" + TextUtil.esc(oldName) + "</b> → <b>"
                                    + TextUtil.esc(newName) + "</b> · " + TextUtil.fmt(sum) + " so'm (📲 Klik)", null);
                        changed = true;
                    }
                }
                return changed;
            }
        }

        boolean posted = ledger.postRasxodSync(wantOt, wantOid, MoneyType.KLIK,
                sum, e.date(), msId, matchCat(ctx.catByName(), e.expenseItem()), comment);
        if (posted && shouldNotify(e)) {
            String text = "💸 MoySklad Klik rasxodi: <b>" + TextUtil.esc(ownerDisplayName(wantOt, wantOid))
                    + "</b> — <b>" + TextUtil.fmt(sum) + "</b> so'm (📲 Klik)"
                    + "\n" + docInfo(e)
                    + (comment.isEmpty() ? "" : "\n" + TextUtil.esc(comment));
            if (wantOt == OwnerType.KASSA) notify.toKassa(wantOid, text, null);
            notify.toBuxgalteriya(text, null);
            checkNegative(wantOt, wantOid, MoneyType.KLIK, "Klik rasxod");
        }
        return posted;
    }

    /* ==================== CLICK BALANS AUDITI (to'liq tarix) ==================== */

    /**
     * Click balanslari MoySklad'ning JORIY qoldiqlariga (/report/money/byaccount)
     * muntazam tenglashtiriladi — har bog'langan Click hisobi to'liq. Hujjatma-hujjat
     * solishtiruv «farq qaysi hujjatdan» ma'lumoti uchun saqlangan. NAQD bu yerda
     * tenglashtirilMAYDI (2026-09-02, pastdagi izohga qarang). Jobs: soatda 1 yoki /auditclick.
     */
    public synchronized void auditClickAccounts() {
        String token = client.currentToken();
        if (token == null || token.isBlank()) return;
        sync();   // avval oxirgi hujjatlar olinadi — «yo'ldagi» hujjat tenglashtirishni adashtirmasin

        List<ClickAccount> accounts = clickRepo.findByActiveTrueOrderByIdAsc();
        Map<String, Long> accountToClick = new HashMap<>();
        for (ClickAccount c : accounts)
            if (c.getMoyskladAccountId() != null && !c.getMoyskladAccountId().isBlank())
                accountToClick.put(c.getMoyskladAccountId(), c.getId());
        if (accountToClick.isEmpty()) return;

        Map<Long, Long> trueBalance = new HashMap<>();
        for (Long id : accountToClick.values()) trueBalance.put(id, 0L);

        // «Aybdorni topish» uchun: har hisob bo'yicha MoySklad hujjatlari
        // (bot jurnalidagi msId -> ishorali summa va inson o'qiy oladigan ta'rif)
        record MsDocRef(long signedSum, String label) {}
        Map<Long, Map<String, MsDocRef>> msDocs = new HashMap<>();
        for (Long id : accountToClick.values()) msDocs.put(id, new HashMap<>());

        // Ledger boshlanish sanasidan OLDINGI hujjatlar SANALMAYDI — u davr
        // kalibratsiya (qo'lda kiritilgan qoldiq) ichida, hujjatma-hujjat emas.
        LocalDate ep = epoch();
        try {
            for (MoySkladClient.MsExpense e : client.fetchAllPaymentsIn()) {
                Long clickId = accountToClick.get(e.accountId());
                if (clickId == null || !e.applicable() || !e.state().equalsIgnoreCase(klikState())) continue;
                if (e.date().isBefore(ep)) continue;
                long sum = somSum(e);
                if (sum > 0) {
                    trueBalance.merge(clickId, sum, Long::sum);
                    msDocs.get(clickId).put("pi:" + e.id(),
                            new MsDocRef(sum, "№" + e.docNo() + " · " + e.date().format(D_UZ)));
                }
            }
            for (MoySkladClient.MsExpense e : client.fetchAllPaymentsOut()) {
                Long clickId = accountToClick.get(e.accountId());
                if (clickId == null || !e.applicable()) continue;
                if (e.date().isBefore(ep)) continue;
                long sum = somSum(e);
                if (sum > 0) {
                    trueBalance.merge(clickId, -sum, Long::sum);
                    msDocs.get(clickId).put("po:" + e.id(),
                            new MsDocRef(-sum, "№" + e.docNo() + " · " + e.date().format(D_UZ)));
                }
            }
        } catch (Exception ex) {
            log.warn("Click balans auditi uzildi: {}", ex.getMessage());
            return;
        }

        // NISHON — MoySklad'ning o'zi ko'rsatayotgan JORIY qoldiq (to'liq tarix,
        // epoch'dan oldingi davr bilan birga). Farq TO'LIQ yopiladi.
        Map<String, Long> msBal;
        try { msBal = client.fetchAccountBalances(); }
        catch (Exception ex) {
            log.warn("MoySklad pul hisoboti o'qilmadi: {}", ex.getMessage());
            return;
        }
        if (msBal.isEmpty()) {
            log.warn("MoySklad pul hisoboti bo'sh/ruxsat yo'q — tenglashtirish o'tkazildi");
            return;
        }

        for (ClickAccount c : accounts) {
            String accId = c.getMoyskladAccountId();
            if (accId == null || accId.isBlank() || !msBal.containsKey(accId)) continue;
            long target = msBal.get(accId);
            long bot = ledger.view(OwnerType.CLICK, c.getId(), MoneyType.KLIK).getAmount();
            long diff = target - bot;
            if (diff == 0) continue;

            // AYBDOR HUJJATLAR: MoySklad ro'yxati bilan bot jurnalini hujjatma-hujjat
            // solishtirib, farq aynan qaysi hujjat(lar)dan chiqqanini ko'rsatamiz.
            StringBuilder det = new StringBuilder();
            int shownDocs = 0;
            Map<String, MsDocRef> expect = msDocs.getOrDefault(c.getId(), Map.of());
            Map<String, Long> botOps = new HashMap<>();
            for (Operation op : opRepo.syncOpsOf(OwnerType.CLICK, c.getId(), MoneyType.KLIK)) {
                long signed = op.getToOwnerType() == OwnerType.CLICK ? op.getAmount() : -op.getAmount();
                botOps.merge(op.getMoyskladId(), signed, Long::sum);
            }
            for (var en : expect.entrySet()) {
                Long got = botOps.get(en.getKey());
                if (got != null && got == en.getValue().signedSum()) continue;
                if (shownDocs++ >= 8) { det.append("…\n"); break; }
                if (got == null)
                    det.append("• MoySklad'da bor, botda YO'Q: ")
                       .append(TextUtil.esc(en.getValue().label())).append(" · ")
                       .append(TextUtil.fmt(en.getValue().signedSum())).append(" so'm\n");
                else
                    det.append("• Summa farq: ").append(TextUtil.esc(en.getValue().label()))
                       .append(" · bot ").append(TextUtil.fmt(got)).append(" / MS ")
                       .append(TextUtil.fmt(en.getValue().signedSum())).append(" so'm\n");
            }
            if (shownDocs <= 8)
                for (var en : botOps.entrySet()) {
                    if (expect.containsKey(en.getKey())) continue;
                    if (shownDocs++ >= 8) { det.append("…\n"); break; }
                    det.append("• Botda bor, MoySklad ro'yxatida YO'Q: <code>")
                       .append(TextUtil.esc(en.getKey())).append("</code> · ")
                       .append(TextUtil.fmt(en.getValue())).append(" so'm\n");
                }

            ledger.postAdjustment(OpType.KORREKTIROVKA, OwnerType.CLICK, c.getId(), MoneyType.KLIK,
                    diff, "MoySklad joriy qoldig'iga tenglashtirish (doim bir xil siyosati)",
                    null, ledger.today());
            notify.toBuxgalteriya("🔄 <b>Click avto-tenglashtirish</b>: <b>" + TextUtil.esc(c.getName())
                    + "</b> — MoySklad joriy qoldig'i bilan farq topildi, tenglashtirildi:\n"
                    + TextUtil.fmt(bot) + " → <b>" + TextUtil.fmt(bot + diff) + "</b> so'm ("
                    + (diff > 0 ? "+" : "") + TextUtil.fmt(diff) + ")"
                    + (det.length() == 0 ? "" : "\n\n<b>Farq bergan hujjatlar:</b>\n" + det), null);
            log.info("Click audit tuzatish: {} {} -> {} (farq {})", c.getName(), bot, bot + diff, diff);
        }

        // NAQD tenglashtirilMAYDI (foydalanuvchi qarori, 2026-09-02). Avval jami naqd
        // (Основной + kassalar) MoySklad CASH bilan solishtirilib, farq Основнойga
        // KORREKTIROVKA sifatida yozilar edi. Bu (a) MoySklad pul hisoboti bilan
        // inkremental sinxron o'rtasidagi poyga tufayli (hujjat hisobotda bor, botga
        // hali tushmagan) har soat +X, keyingi soat -X korrektirovka aylanasini
        // berar, (b) «Nol boshlash»dan keyin butun tarixni Основнойga to'kib
        // yuborar edi. Farq endi faqat hujjat darajasida (sync/reconcile) yopiladi.
    }

    /* ==================== NAQD TEKSHIRUVI (faqat xabar) ==================== */

    private static final String NAQD_AUDIT_KEY = "naqd.audit.lastReport";

    /**
     * 🔍 Jami NAQD (Основной + kassalar) MoySklad CASH bilan solishtiriladi.
     * HECH NARSA YOZILMAYDI (foydalanuvchi qarori, 2026-09-02): farq topilsa
     * buxgalteriyaga SABAB (hujjatma-hujjat), SUMMA va TUZATISH YO'LI bilan xabar
     * boradi. Bir xil farq 24 soatda bir marta takrorlanadi; farq o'zgarsa darhol;
     * farq yopilsa bir marta «✅ yopildi» xabari. Jobs: soatda 1 yoki /auditclick.
     */
    public synchronized void auditNaqd() {
        String token = client.currentToken();
        if (token == null || token.isBlank()) return;
        long[] m = measureNaqd();
        if (m == null) return;
        long msCash = m[0], osn = m[1], kassalar = m[2], diff = m[3];

        String prev = settings.get(NAQD_AUDIT_KEY).orElse("");
        if (diff == 0) {
            if (!prev.isBlank()) {
                settings.set(NAQD_AUDIT_KEY, "");
                notify.toBuxgalteriya("✅ <b>Naqd tekshiruvi</b>: farq yopildi — MoySklad CASH va bot "
                        + "jami naqd teng: <b>" + TextUtil.fmt(msCash) + "</b> so'm", null);
            }
            return;
        }
        long nowMs = System.currentTimeMillis();
        String[] pp = prev.split("\\|");
        if (pp.length == 2) {
            try {
                if (Long.parseLong(pp[0]) == diff && nowMs - Long.parseLong(pp[1]) < 24 * 3600_000L) {
                    log.info("Naqd tekshiruvi: farq {} o'zgarmagan — xabar takrorlanmadi", diff);
                    return;
                }
            } catch (NumberFormatException ignore) { }
        }

        // QAYTA TEKSHIRUV (foydalanuvchi qarori, 02.09.2026): hujjat MoySklad'da qayta
        // rasmiylashtirilayotgan paytdagi (12:03 hodisasi: eski chiqim bekor, yangisi
        // hali yozilmagan) VAQTINCHALIK farq xabar bermasin. 3 daqiqadan keyin alohida
        // oqimda qayta o'lchanadi (qulfsiz — sinxron to'xtab qolmaydi); farq aynan shu
        // qiymatda QOLSAGINA buxgalteriyaga xabar ketadi.
        log.info("Naqd tekshiruvi: MS {} bot {} farq {} — 3 daqiqadan keyin qayta tekshiriladi",
                msCash, total(osn, kassalar), diff);
        final long first = diff;
        Thread t = new Thread(() -> {
            try { Thread.sleep(180_000L); } catch (InterruptedException e) { return; }
            confirmNaqd(first);
        }, "naqd-confirm");
        t.setDaemon(true);
        t.start();
    }

    private static long total(long osn, long kassalar) { return osn + kassalar; }

    /** MoySklad CASH, Основной, kassalar jami, farq — yoki null (hisobot o'qilmadi). */
    private long[] measureNaqd() {
        Map<String, Long> msBal;
        try { msBal = client.fetchAccountBalances(); }
        catch (Exception ex) {
            log.warn("Naqd tekshiruvi: MoySklad pul hisoboti o'qilmadi: {}", ex.getMessage());
            return null;
        }
        Long msCash = msBal.get("CASH");
        if (msCash == null) {
            log.warn("Naqd tekshiruvi: MoySklad hisobotida CASH qatori yo'q/ruxsat yo'q — o'tkazildi");
            return null;
        }
        long osn = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD).getAmount();
        long kassalar = 0;
        for (Kassa k : kassaRepo.findAll())
            kassalar += ledger.view(OwnerType.KASSA, k.getId(), MoneyType.NAQD).getAmount();
        return new long[]{msCash, osn, kassalar, msCash - osn - kassalar};
    }

    /** 3 daqiqadan keyingi qayta o'lchov: farq o'zgarmagan bo'lsa — xabar; o'zgargan/yopilgan — jim. */
    private void confirmNaqd(long first) {
        try {
            long[] m = measureNaqd();
            if (m == null) return;
            long msCash = m[0], osn = m[1], kassalar = m[2], diff = m[3];
            if (diff == 0) {
                log.info("Naqd tekshiruvi: farq {} vaqtinchalik edi — 3 daqiqada yopildi, xabar yo'q", first);
                return;
            }
            if (diff != first) {
                log.info("Naqd tekshiruvi: farq {} → {} o'zgardi (hujjat harakati) — xabar keyingi tekshiruvda",
                        first, diff);
                return;
            }
            String prev = settings.get(NAQD_AUDIT_KEY).orElse("");
            String[] pp = prev.split("\\|");
            if (pp.length == 2) {
                try {
                    if (Long.parseLong(pp[0]) == diff
                            && System.currentTimeMillis() - Long.parseLong(pp[1]) < 24 * 3600_000L) return;
                } catch (NumberFormatException ignore) { }
            }
            long total = osn + kassalar;
            StringBuilder sb = new StringBuilder();
            sb.append("🔍 <b>Naqd tekshiruvi — FARQ TOPILDI</b> <i>(3 daqiqada qayta tasdiqlandi)</i>\n")
              .append("MoySklad CASH: <b>").append(TextUtil.fmt(msCash)).append("</b> so'm\n")
              .append("Bot jami naqd: <b>").append(TextUtil.fmt(total)).append("</b> so'm (Основной ")
              .append(TextUtil.fmt(osn)).append(" · kassalar ").append(TextUtil.fmt(kassalar)).append(")\n")
              .append("Farq: <b>").append(diff > 0 ? "+" : "").append(TextUtil.fmt(diff)).append("</b> so'm — ")
              .append(diff > 0 ? "MoySklad'da KO'P (botga hujjat tushmagan yoki qoldiq kam)"
                               : "botda KO'P (ortiqcha yozuv yoki qoldiq ko'p)")
              .append("\n\n")
              .append(naqdCauses(diff))
              .append("\nℹ️ Bot hech narsani O'ZI YOZMAYDI. Xabar farq o'zgarganda darhol, "
                    + "aks holda 24 soatda bir keladi. Qo'lda tekshirish: /auditclick");
            settings.set(NAQD_AUDIT_KEY, diff + "|" + System.currentTimeMillis());
            notify.toBuxgalteriya(sb.toString(), null);
            log.info("Naqd tekshiruvi: MS {} bot {} farq {} — xabar yuborildi", msCash, total, diff);
        } catch (Exception e) {
            log.warn("Naqd qayta tekshiruv xatosi: {}", e.getMessage());
        }
    }

    /**
     * Farqning SABABI va TUZATISH YO'LI: ledger boshlanish sanasidan beri MoySklad
     * naqd hujjatlari (cashin/cashout/sotuv/vozvrat/Выплата денег) bot jurnali bilan
     * hujjatma-hujjat solishtiriladi; qolgan (hujjatsiz) farq alohida ko'rsatiladi.
     */
    private String naqdCauses(long diff) {
        record Ref(long signed, String label) {}
        LocalDate ep = epoch();
        LocalDate to = LocalDate.now(props.zoneId());
        StringBuilder sb = new StringBuilder("<b>Sabablar</b>");
        if (ep.equals(LocalDate.MIN)) {
            sb.append(":\n• Ledger boshlanish sanasi belgilanmagan — hujjatma-hujjat solishtiruv "
                    + "imkonsiz.\n\n<b>Tuzatish yo'li</b>:\n• Настройка bo'limida ledger boshlanish "
                    + "sanasini belgilang, keyin /auditclick.\n");
            return sb.toString();
        }
        sb.append(" (hujjatlar ").append(ep.format(D_UZ)).append(" dan beri):\n");

        // MoySklad tomoni: msId -> ishorali summa
        Map<String, Ref> expect = new java.util.LinkedHashMap<>();
        long t0 = System.currentTimeMillis();
        String fetchErr = "";
        try {
            for (MoySkladClient.MsExpense e : client.fetchDocsByMoment("cashin", ep, to)) {
                long sum = somSum(e);
                if (sum > 0) expect.put("ci:" + e.id(), new Ref(sum,
                        "Приходный №" + e.docNo() + " · " + e.date().format(D_UZ)
                        + (e.agent().isEmpty() ? "" : " · " + e.agent())));
            }
            for (MoySkladClient.MsExpense e : client.fetchDocsByMoment("cashout", ep, to)) {
                long sum = somSum(e);
                if (sum > 0) expect.put("co:" + e.id(), new Ref(-sum,
                        "Расходный №" + e.docNo() + " · " + e.date().format(D_UZ)
                        + (e.agent().isEmpty() ? "" : " · " + e.agent())));
            }
            for (MoySkladClient.MsDoc d : client.fetchSalesByMoment("retaildemand", ep, to)) {
                long sum = d.cashTiyin() / 100;
                if (d.applicable() && sum > 0)
                    expect.put("rd:" + d.id() + ":n", new Ref(sum, "Sotuv (naqd) · " + d.date().format(D_UZ)));
            }
            for (MoySkladClient.MsDoc d : client.fetchSalesByMoment("retailsalesreturn", ep, to)) {
                long sum = d.cashTiyin() / 100;
                if (d.applicable() && sum > 0)
                    expect.put("rr:" + d.id() + ":n", new Ref(-sum, "Vozvrat (naqd) · " + d.date().format(D_UZ)));
            }
            for (MoySkladClient.MsExpense e : client.fetchDrawerCashoutsByMoment(ep, to)) {
                long sum = somSum(e);
                if (e.applicable() && sum > 0)
                    expect.put("dc:" + e.id(), new Ref(-sum,
                            "Выплата денег " + e.docNo() + " · " + e.date().format(D_UZ)));
            }
        } catch (Exception ex) {
            fetchErr = ex.getMessage() == null ? "xato" : ex.getMessage();
            log.warn("Naqd tekshiruvi: hujjatlar o'qilmadi: {}", fetchErr);
        }
        boolean perm403 = client.last403At() >= t0;

        // Bot tomoni: sinxron (moysklad_id bor) NAQD yozuvlari — msId -> ishorali summa
        Map<String, Long> bot = new HashMap<>();
        for (String pref : List.of("ci:", "co:", "rd:", "rr:", "dc:"))
            for (Operation op : opRepo.findByOpDateBetweenAndMoyskladIdStartingWith(ep, to, pref)) {
                if (op.getMoneyType() != MoneyType.NAQD || op.getStatus() != OpStatus.TASDIQLANGAN) continue;
                long signed = op.getToOwnerType() != null ? op.getAmount() : -op.getAmount();
                bot.merge(op.getMoyskladId(), signed, Long::sum);
            }

        // Toifalar
        List<String> msOnly = new java.util.ArrayList<>(), botOnly = new java.util.ArrayList<>(),
                     sumDiff = new java.util.ArrayList<>();
        long sumMsOnly = 0, sumBotOnly = 0, sumSumDiff = 0;
        for (var en : expect.entrySet()) {
            Long got = bot.get(en.getKey());
            long ms = en.getValue().signed();
            if (got == null) {
                sumMsOnly += ms;
                msOnly.add("   – " + TextUtil.esc(en.getValue().label()) + " · " + signedFmt(ms));
            } else if (got != ms) {
                sumSumDiff += ms - got;
                sumDiff.add("   – " + TextUtil.esc(en.getValue().label()) + " · MS " + signedFmt(ms)
                        + " / bot " + signedFmt(got));
            }
        }
        if (!perm403 && fetchErr.isEmpty())
            for (var en : bot.entrySet()) {
                if (expect.containsKey(en.getKey())) continue;
                sumBotOnly += en.getValue();
                botOnly.add("   – <code>" + TextUtil.esc(en.getKey()) + "</code> · " + signedFmt(en.getValue()));
            }

        // Bot qo'lda yozuvlari (MoySklad'da yo'q): korrektirovka + boshlang'ich qoldiq
        long manual = 0; int manualN = 0;
        for (OpType t : List.of(OpType.KORREKTIROVKA, OpType.BOSHLANGICH))
            for (Operation op : opRepo.findByStatusAndType(OpStatus.TASDIQLANGAN, t)) {
                if (op.getMoneyType() != MoneyType.NAQD) continue;
                manual += op.getToOwnerType() != null ? op.getAmount() : -op.getAmount();
                manualN++;
            }

        long explained = sumMsOnly - sumBotOnly + sumSumDiff;
        long rest = diff - explained;

        int reconcileDays = props.getMoysklad().getReconcileDays();
        if (!fetchErr.isEmpty())
            sb.append("• ⚠️ MoySklad hujjatlari to'liq o'qilmadi: ").append(TextUtil.esc(fetchErr)).append("\n");
        if (perm403)
            sb.append("• ⚠️ Token HUQUQI yetmadi (403) — retail (sotuv/vozvrat/Выплата) hujjatlar "
                    + "solishtirilmadi, ular botga umuman tushmayotgan bo'lishi mumkin.\n");
        appendCat(sb, "📥 MoySklad'da bor, botda YO'Q", msOnly, sumMsOnly);
        appendCat(sb, "📤 Botda bor, MoySklad'da YO'Q (o'chirilgan/bekor qilingan?)", botOnly, sumBotOnly);
        appendCat(sb, "✏️ Summasi farq qiladigan hujjatlar", sumDiff, sumSumDiff);
        if (manualN > 0)
            sb.append("• 🧾 Bot qo'lda yozuvlari (korrektirovka/boshlang'ich, MoySklad'da yo'q): ")
              .append(manualN).append(" ta, jami ").append(signedFmt(manual)).append("\n");
        if (rest != 0)
            sb.append("• ❓ Hujjatlar bilan tushuntirilmagan qoldiq: <b>").append(signedFmt(rest))
              .append("</b> — bu ").append(ep.format(D_UZ))
              .append(" gacha bo'lgan davr (MoySklad tarixi) bilan botdagi boshlang'ich qoldiq/"
                    + "korrektirovkalar o'rtasidagi farq, yoki eski hujjat keyin tahrirlangan.\n");
        if (msOnly.isEmpty() && botOnly.isEmpty() && sumDiff.isEmpty() && rest == 0)
            sb.append("• Hujjatlar teng — farq faqat qoldiq darajasida.\n");

        sb.append("\n<b>Tuzatish yo'li</b>:\n");
        if (!msOnly.isEmpty() || !sumDiff.isEmpty())
            sb.append("• Tushmagan/farqli hujjatlar: 10 daqiqalik avto-tekshiruv (reconcile) oxirgi ")
              .append(reconcileDays).append(" kunni o'zi yopadi — kuting va xabarni qayta tekshiring. ")
              .append("Undan eski hujjat bo'lsa — Настройка → «Qayta yuklash» (to'liq).\n");
        if (!botOnly.isEmpty())
            sb.append("• Ortiqcha yozuvlar: hujjat MoySklad'da o'chirilgan/bekor qilingan bo'lsa reconcile ")
              .append("STORNO qiladi (oxirgi ").append(reconcileDays)
              .append(" kun); eskiroq bo'lsa — Настройка → «Qayta yuklash».\n");
        if (perm403)
            sb.append("• MoySklad kabinetida token egasiga «Розничная торговля» ko'rish huquqini bering, "
                    + "so'ng /auditclick.\n");
        if (rest != 0)
            sb.append("• Tushuntirilmagan ").append(signedFmt(rest))
              .append(": haqiqiy pulni sanab, Настройка → 🛠 Корректировка orqali kerakli otdelga "
                    + "(odatda Основной) shu summani ± yozing yoki boshlang'ich qoldiqni to'g'rilang.\n");
        return sb.toString();
    }

    private void appendCat(StringBuilder sb, String title, List<String> lines, long sum) {
        if (lines.isEmpty()) return;
        sb.append("• ").append(title).append(": ").append(lines.size()).append(" ta, jami ")
          .append(signedFmt(sum)).append("\n");
        int shown = 0;
        for (String l : lines) {
            if (shown++ >= 5) { sb.append("   … yana ").append(lines.size() - 5).append(" ta\n"); break; }
            sb.append(l).append("\n");
        }
    }

    private static String signedFmt(long v) {
        return (v > 0 ? "+" : "") + TextUtil.fmt(v) + " so'm";
    }

    /* ==================== yordamchi ==================== */

    /** Sinxron opni STORNO qilib buxgalteriyaga xabar berish. */
    private boolean storno(Operation op, String reason, String info) {
        String owner = ownerName(op);
        long amount = op.getAmount();
        MoneyType mt = op.getMoneyType();
        String kind = op.getType() == OpType.RASXOD ? "chiqim"
                : op.getType() == OpType.VOZVRAT ? "vozvrat" : "kirim";
        String msId = op.getMoyskladId();
        LocalDate opDate = op.getOpDate();
        if (!ledger.reverseSyncOp(op, reason)) return false;
        if (loudFix(opDate))
            notify.toBuxgalteriya("♻️ MoySklad STORNO — " + TextUtil.esc(reason) + ":\n"
                    + "<b>" + TextUtil.esc(owner) + "</b> — " + TextUtil.fmt(amount)
                    + " so'm (" + mtLabel(mt) + ", " + kind + ")"
                    + (info.isEmpty() ? "" : "\n" + info), null);
        log.info("STORNO {}: {} — {} so'm {}", msId, reason, amount, mt);
        return true;
    }

    /** Operatsiya egasining nomi (kirimda to-, chiqim/vozvratda from-tomon). */
    private String ownerName(Operation op) {
        OwnerType ot = op.getType() == OpType.PRIXOD ? op.getToOwnerType() : op.getFromOwnerType();
        Long oid = op.getType() == OpType.PRIXOD ? op.getToOwnerId() : op.getFromOwnerId();
        return ownerDisplayName(ot, oid);
    }

    /** Egasi nomi: Отдел Основной / kassa nomi / Click hisobi nomi. */
    private String ownerDisplayName(OwnerType ot, Long oid) {
        if (ot == OwnerType.BUXGALTERIYA) return "Отдел Основной";
        if (ot == OwnerType.CLICK)
            return clickRepo.findById(oid).map(ClickAccount::getName).orElse("Klik #" + oid);
        return kassaRepo.findById(oid).map(Kassa::getName).orElse("Kassa #" + oid);
    }

    private String mtLabel(MoneyType mt) {
        return switch (mt) {
            case KLIK -> "📲 Klik"; case TERMINAL -> "💳 Terminal"; default -> "💵 Naqd";
        };
    }

    /** Xabar matni uchun hujjat rekvizitlari: №raqam · sana · status · kontragent. */
    private String docInfo(MoySkladClient.MsExpense e) {
        StringBuilder sb = new StringBuilder("📄 №" + TextUtil.esc(e.docNo())
                + " · " + e.date().format(D_UZ));
        if (!e.state().isBlank()) sb.append(" · ").append(TextUtil.esc(e.state()));
        if (!e.agent().isBlank()) sb.append("\n👤 ").append(TextUtil.esc(e.agent()));
        return sb.toString();
    }

    /**
     * Faqat bugungi hujjatlar haqida xabar yuboriladi — tarixiy backfill paytida
     * yuzlab eski hujjat spam bo'lib ketmasligi uchun (ular baribir bazaga yoziladi).
     */
    private boolean shouldNotify(MoySkladClient.MsExpense e) {
        return !quietReload && e.date().equals(ledger.today());
    }

    private void checkNegative(OwnerType ot, Long oid, String sabab) {
        checkNegative(ot, oid, MoneyType.NAQD, sabab);
    }

    private void checkNegative(OwnerType ot, Long oid, MoneyType mt, String sabab) {
        if (quietReload) return;
        long bal = ledger.view(ot, oid, mt).getAmount();
        if (bal < 0) {
            notify.toBuxgalteriya("⚠️ " + sabab + " natijasida <b>" + TextUtil.esc(ownerDisplayName(ot, oid))
                    + "</b> " + mtLabel(mt) + " balansi manfiy: " + TextUtil.fmt(bal)
                    + " so'm. Korrektirovka talab qilinadi.", null);
        }
    }

    private Long matchCat(Map<String, Long> cats, String expenseItem) {
        if (expenseItem == null || expenseItem.isBlank()) return null;
        return cats.get(expenseItem.trim().toLowerCase());
    }

    /** «Статья расходов» + izohni birlashtirish, 450 belgigacha qisqartirish. */
    private String joinNote(String expenseItem, String description) {
        String a = expenseItem == null ? "" : expenseItem.trim();
        String b = description == null ? "" : description.trim();
        String s = a.isEmpty() ? b : (b.isEmpty() ? a : a + " — " + b);
        return s.length() > 450 ? s.substring(0, 450) + "…" : s;
    }
}
