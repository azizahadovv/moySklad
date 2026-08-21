package uz.kassa.service.moysklad;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.bot.TextUtil;
import uz.kassa.config.AppProps;
import uz.kassa.domain.*;
import uz.kassa.repo.CategoryRepo;
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
    private final OperationRepo opRepo;
    private final SettingsService settings;
    private final NotificationService notify;
    private final AppProps props;

    private volatile long lastAttempt = 0;

    /** So'm (UZS) valyuta UUID lari — boshqa valyutadagi hujjatlar nazoratga olinmaydi. */
    private volatile java.util.Set<String> somCurrencyIds = java.util.Set.of();

    /** Bir siklda qayta ishlatiladigan xaritalar. */
    private record Ctx(Map<String, Long> storeToKassa, Map<String, Long> groupToKassa,
                       Map<String, String> groupNames, Map<String, Long> catByName) {}

    private Ctx buildCtx() {
        Map<String, Long> storeToKassa = new HashMap<>();
        Map<String, Long> groupToKassa = new HashMap<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.getMoyskladStoreId() != null && !k.getMoyskladStoreId().isBlank())
                storeToKassa.put(k.getMoyskladStoreId(), k.getId());
            if (k.getMoyskladGroupId() != null && !k.getMoyskladGroupId().isBlank())
                groupToKassa.put(k.getMoyskladGroupId(), k.getId());
        }
        Map<String, String> groupNames = client.fetchGroups();

        java.util.Set<String> som = new java.util.HashSet<>();
        client.fetchCurrencies().forEach((id, iso) -> {
            if (iso.equalsIgnoreCase("UZS")) som.add(id);
        });
        somCurrencyIds = som;

        // «Статья расходов» nomi tizim kategoriyasiga mos kelsa — avtomatik biriktiriladi
        Map<String, Long> catByName = new HashMap<>();
        for (Category c : categoryRepo.findByActiveTrueOrderByIdAsc())
            catByName.put(c.getName().trim().toLowerCase(), c.getId());

        return new Ctx(storeToKassa, groupToKassa, groupNames, catByName);
    }

    /**
     * Ledger boshlanish sanasi (kalibratsiya): boshlang'ich qoldiqlar shu sanaga
     * kiritilgan. Bundan eski, ledger'da YO'Q hujjatlar yangidan YOZILMAYDI —
     * ular kalibratsiya ichida allaqachon hisobga olingan (aks holda ikki marta
     * hisoblanadi). Lekin ledger'da YOZUVI BOR hujjatning HAR QANDAY o'zgarishi
     * (summa/otdel/sana/status/bekor qilish) sanasidan qat'i nazar DOIM qo'llanadi —
     * farq (delta) bilan ishlangani uchun kalibratsiyani buzmaydi.
     */
    private LocalDate epoch() {
        String s = props.getMoysklad().getLedgerStartDate();
        if (s == null || s.isBlank()) return LocalDate.MIN;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            log.warn("ledger-start-date noto'g'ri format: {}", s);
            return LocalDate.MIN;
        }
    }

    /** Kalibratsiyadan oldingi, ledger'da yo'q hujjat — yangidan yozilmaydi. */
    private boolean skipNewPreEpoch(LocalDate docDate, Optional<Operation> existing) {
        return existing.isEmpty() && docDate.isBefore(epoch());
    }

    /**
     * So'mda emasligini aniqlash: hujjat dollarga o'tkazilgan bo'lsa (masalan
     * postavchikga to'lov $ da qayta hisoblangan) — u tizim nazoratidan chiqadi.
     */
    private boolean notSom(String currencyId) {
        return !somCurrencyIds.isEmpty() && !currencyId.isEmpty()
                && !somCurrencyIds.contains(currencyId);
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
                .map(v -> LocalDateTime.parse(v, FMT))
                .orElse(now.minusDays(2))
                .minusMinutes(props.getMoysklad().getSyncOverlapMinutes());

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
        } catch (Exception e) {
            // Watermark SURILMAYDI — o'qilmagan hujjatlar keyingi siklda qayta so'raladi
            log.warn("MoySklad sinxron uzildi (watermark saqlanmadi): {}", e.getMessage());
            return;
        }

        settings.set(LAST_SYNC_KEY, now.format(FMT));
        if (n > 0) log.info("MoySklad sinxron: {} ta yozuv/tuzatish", n);
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
        String token = client.currentToken();
        if (token == null || token.isBlank()) return;
        LocalDate to = LocalDate.now(props.zoneId());
        LocalDate from = to.minusDays(props.getMoysklad().getReconcileDays());
        LocalDate ep = epoch();
        if (ep.isAfter(from)) from = ep;   // kalibratsiyadan oldingi davrga kirilmaydi

        int n = 0;
        try {
            Ctx ctx = buildCtx();
            record Ent(String entity, String prefix) {}
            for (Ent ent : List.of(new Ent("cashin", "ci:"),
                                   new Ent("paymentin", "pi:"),
                                   new Ent("cashout", "co:"))) {
                List<MoySkladClient.MsExpense> docs =
                        client.fetchDocsByMoment(ent.entity(), from, to);

                java.util.Set<String> apiIds = new java.util.HashSet<>();
                for (MoySkladClient.MsExpense e : docs) {
                    apiIds.add(e.id());
                    boolean changed = switch (ent.entity()) {
                        case "cashin" -> applyIncome(e, MoneyType.NAQD, "ci:" + e.id(), ctx);
                        case "paymentin" -> dispatchPaymentIn(e, ctx);
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
        } catch (Exception e) {
            log.warn("MoySklad reconcile uzildi: {}", e.getMessage());
            return;
        }
        if (n > 0) log.info("MoySklad reconcile: {} ta yozuv/tuzatish", n);
    }

    /* ==================== PAYMENTIN (status bo'yicha pul turi) ==================== */

    /**
     * Входящий платеж — status bo'yicha ALOHIDA pul turlari:
     *   «Клик»           -> KLIK (kassir balansiga)
     *   «Картадан тулов» -> TERMINAL (faqat kun hisobotida — firma hisobiga tushadi)
     *   boshqa statuslar — bank o'tkazmalari, kassir puliga tegmaydi.
     */
    private MoneyType paymentMt(String state) {
        return state.equalsIgnoreCase("Клик") ? MoneyType.KLIK
                : state.equalsIgnoreCase("Картадан тулов") ? MoneyType.TERMINAL
                : null;
    }

    private boolean dispatchPaymentIn(MoySkladClient.MsExpense e, Ctx ctx) {
        MoneyType mt = paymentMt(e.state());
        if (mt == null) {
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
        long sum = e.sumTiyin() / 100;
        String msId = "dc:" + e.id();
        String comment = joinNote(e.expenseItem(), e.description());
        Optional<Operation> existing = opRepo.findByMoyskladId(msId);
        if (skipNewPreEpoch(e.date(), existing)) return false;

        if (!e.applicable() || sum <= 0) {
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
        long sum = e.sumTiyin() / 100;
        String msId = "co:" + e.id();
        String comment = joinNote(e.expenseItem(), e.description());
        Long kassaId = ctx.groupToKassa().get(e.groupId());
        OwnerType wantOt = kassaId != null ? OwnerType.KASSA : OwnerType.BUXGALTERIYA;
        Long wantOid = kassaId != null ? kassaId : LedgerService.BUX_ID;
        Optional<Operation> existing = opRepo.findByMoyskladId(msId);
        if (skipNewPreEpoch(e.date(), existing)) return false;

        if (!e.applicable() || notSom(e.currencyId()) || sum <= 0) {
            return existing.isPresent()
                    && storno(existing.get(),
                        !e.applicable() ? "hujjat o'tkazilishi bekor qilingan"
                                : sum <= 0 ? "summa nolga tushirilgan" : "hujjat valyutaga o'tkazilgan",
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
                        notify.toBuxgalteriya("✏️ MoySklad rasxod summasi o'zgargan — avtomatik tuzatildi:\n"
                                + docInfo(e) + "\n<b>" + TextUtil.esc(ownerName(op)) + "</b>: "
                                + TextUtil.fmt(old) + " → " + TextUtil.fmt(sum) + " so'm (💵 Naqd)", null);
                        changed = true;
                    }
                }
                if (op.getFromOwnerType() != wantOt
                        || !java.util.Objects.equals(op.getFromOwnerId(), wantOid)) {
                    String oldName = ownerName(op);
                    if (ledger.rerouteRasxod(op, wantOt, wantOid)) {
                        String newName = wantOt == OwnerType.BUXGALTERIYA ? "Buxgalteriya"
                                : kassaRepo.findById(wantOid).map(Kassa::getName).orElse("Kassa #" + wantOid);
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
                notify.toBuxgalteriya("💸 MoySklad rasxodi (Расходный ордер): <b>Buxgalteriya</b>"
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
        long sum = e.sumTiyin() / 100;
        Long kassaId = ctx.groupToKassa().get(e.groupId());
        OwnerType wantOt = kassaId != null ? OwnerType.KASSA : OwnerType.BUXGALTERIYA;
        Long wantOid = kassaId != null ? kassaId : LedgerService.BUX_ID;
        String comment = joinNote(e.agent(), e.description());
        String mtLabel = mtLabel(mt);
        Optional<Operation> existing = opRepo.findByMoyskladId(msId);
        if (skipNewPreEpoch(e.date(), existing)) return false;

        if (!e.applicable() || notSom(e.currencyId()) || sum <= 0) {
            return existing.isPresent()
                    && storno(existing.get(),
                        !e.applicable() ? "hujjat o'tkazilishi bekor qilingan"
                                : sum <= 0 ? "summa nolga tushirilgan" : "hujjat valyutaga o'tkazilgan",
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
                        notify.toBuxgalteriya("✏️ MoySklad kirim summasi o'zgargan — avtomatik tuzatildi:\n"
                                + docInfo(e) + "\n<b>" + TextUtil.esc(ownerName(op)) + "</b>: "
                                + TextUtil.fmt(old) + " → " + TextUtil.fmt(sum)
                                + " so'm (" + mtLabel + ")", null);
                        changed = true;
                    }
                }
                if (op.getToOwnerType() != wantOt
                        || !java.util.Objects.equals(op.getToOwnerId(), wantOid)) {
                    String oldName = ownerName(op);
                    if (ledger.reroutePrixod(op, wantOt, wantOid)) {
                        String newName = wantOt == OwnerType.BUXGALTERIYA ? "Buxgalteriya"
                                : kassaRepo.findById(wantOid).map(Kassa::getName).orElse("Kassa #" + wantOid);
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
                String kassaName = kassaRepo.findById(wantOid).map(Kassa::getName).orElse("Kassa #" + wantOid);
                String text = "💰 MoySklad kirim: <b>" + TextUtil.esc(kassaName)
                        + "</b> — <b>" + TextUtil.fmt(sum) + "</b> so'm (" + mtLabel + ")"
                        + "\n" + docInfo(e);
                notify.toKassa(wantOid, text, null);
                notify.toBuxgalteriya(text, null);
            } else {
                String otdel = ctx.groupNames().getOrDefault(e.groupId(), "");
                notify.toBuxgalteriya("💰 MoySklad kirim: <b>Buxgalteriya</b>"
                        + (otdel.isEmpty() ? "" : " · " + TextUtil.esc(otdel))
                        + " — <b>" + TextUtil.fmt(sum) + "</b> so'm (" + mtLabel + ")"
                        + "\n" + docInfo(e), null);
            }
        }
        return posted;
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
        if (!ledger.reverseSyncOp(op, reason)) return false;
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
        if (ot == OwnerType.BUXGALTERIYA) return "Buxgalteriya";
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
        return e.date().equals(ledger.today());
    }

    private void checkNegative(OwnerType ot, Long oid, String sabab) {
        long bal = ledger.view(ot, oid, MoneyType.NAQD).getAmount();
        if (bal < 0) {
            String name = ot == OwnerType.BUXGALTERIYA ? "Buxgalteriya"
                    : kassaRepo.findById(oid).map(Kassa::getName).orElse("Kassa #" + oid);
            notify.toBuxgalteriya("⚠️ " + sabab + " natijasida <b>" + TextUtil.esc(name)
                    + "</b> NAQD balansi manfiy: " + TextUtil.fmt(bal)
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
