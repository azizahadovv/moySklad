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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
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

    /** So'ralganda yangilash: oxirgi urinishdan maxAgeSec o'tgan bo'lsa sinxron qilinadi. */
    public void syncIfStale(long maxAgeSec) {
        if (System.currentTimeMillis() - lastAttempt < maxAgeSec * 1000) return;
        sync();
    }

    public synchronized void sync() {
        String token = props.getMoysklad().getToken();
        if (token == null || token.isBlank()) return;   // token yo'q — sinxron o'chiq
        lastAttempt = System.currentTimeMillis();

        LocalDateTime now = LocalDateTime.now(props.zoneId());
        LocalDateTime from = settings.get(LAST_SYNC_KEY)
                .map(v -> LocalDateTime.parse(v, FMT))
                .orElse(now.minusDays(2))
                .minusMinutes(props.getMoysklad().getSyncOverlapMinutes());

        int nSale = 0, nRet = 0, nExp = 0, nIn = 0;
        try {
            Map<String, Long> storeToKassa = new HashMap<>();
            Map<String, Long> groupToKassa = new HashMap<>();
            for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
                if (k.getMoyskladStoreId() != null && !k.getMoyskladStoreId().isBlank())
                    storeToKassa.put(k.getMoyskladStoreId(), k.getId());
                if (k.getMoyskladGroupId() != null && !k.getMoyskladGroupId().isBlank())
                    groupToKassa.put(k.getMoyskladGroupId(), k.getId());
            }
            Map<String, String> groupNames = client.fetchGroups();

            // «Статья расходов» nomi tizim kategoriyasiga mos kelsa — avtomatik biriktiriladi
            Map<String, Long> catByName = new HashMap<>();
            for (Category c : categoryRepo.findByActiveTrueOrderByIdAsc())
                catByName.put(c.getName().trim().toLowerCase(), c.getId());

            for (MoySkladClient.MsDoc d : client.fetchSales("retaildemand", from))
                if (applySale(d, storeToKassa)) nSale++;
            for (MoySkladClient.MsDoc d : client.fetchSales("retailsalesreturn", from))
                if (applyReturn(d, storeToKassa)) nRet++;
            for (MoySkladClient.MsExpense e : client.fetchDrawerCashouts(from))
                if (applyDrawerExpense(e, storeToKassa, catByName)) nExp++;
            for (MoySkladClient.MsExpense e : client.fetchCashins(from))
                if (applyIncome(e, MoneyType.NAQD, "ci:" + e.id(), groupToKassa, groupNames)) nIn++;
            // Входящий платеж — status bo'yicha ALOHIDA pul turlari:
            //   «Клик»          -> KLIK (kassir balansiga)
            //   «Картадан тулов» -> TERMINAL (faqat kun hisobotida — firma hisobiga tushadi)
            //   «Перечисления»/«Накд тулов» — bank o'tkazmalari, kassir puliga tegmaydi.
            for (MoySkladClient.MsExpense e : client.fetchPaymentsIn(from)) {
                MoneyType mt = e.state().equalsIgnoreCase("Клик") ? MoneyType.KLIK
                        : e.state().equalsIgnoreCase("Картадан тулов") ? MoneyType.TERMINAL
                        : null;
                if (mt != null
                        && applyIncome(e, mt, "pi:" + e.id(), groupToKassa, groupNames)) nIn++;
            }
            for (MoySkladClient.MsExpense e : client.fetchCashouts(from))
                if (applyCashout(e, groupToKassa, groupNames, catByName)) nExp++;
        } catch (Exception e) {
            // Watermark SURILMAYDI — o'qilmagan hujjatlar keyingi siklda qayta so'raladi
            log.warn("MoySklad sinxron uzildi (watermark saqlanmadi): {}", e.getMessage());
            return;
        }

        settings.set(LAST_SYNC_KEY, now.format(FMT));
        if (nSale > 0 || nRet > 0 || nExp > 0 || nIn > 0)
            log.info("MoySklad sinxron: +{} sotuv, +{} vozvrat, +{} kirim, +{} rasxod",
                    nSale, nRet, nIn, nExp);
    }

    /* ------------------------------------------------------------------
     * TODO (TZ §17): To'lov turini ajratish qoidasi.
     * Hozircha: cashSum -> NAQD, noCashSum -> TERMINAL.
     * Buyurtmachi bazasida KLIK qanday belgilanishi aniqlangach
     * (alohida to'lov turi / atribut), shu ikki metodda noCashSum
     * KLIK va TERMINALga bo'linadi. Boshqa joyga tegilmaydi.
     * ------------------------------------------------------------------ */

    private boolean applySale(MoySkladClient.MsDoc d, Map<String, Long> stores) {
        Long kassaId = stores.get(d.storeId());
        if (kassaId == null) {
            log.debug("Noma'lum MoySklad store {} — hujjat {} o'tkazib yuborildi", d.storeId(), d.id());
            return false;
        }
        warnIfChanged("rd:" + d.id() + ":n", d.cashTiyin() / 100);
        warnIfChanged("rd:" + d.id() + ":b", d.noCashTiyin() / 100);

        boolean a = ledger.postPrixod(kassaId, MoneyType.NAQD, d.cashTiyin() / 100, d.date(), "rd:" + d.id() + ":n");
        boolean b = ledger.postPrixod(kassaId, MoneyType.TERMINAL, d.noCashTiyin() / 100, d.date(), "rd:" + d.id() + ":b");
        return a || b;
    }

    private boolean applyReturn(MoySkladClient.MsDoc d, Map<String, Long> stores) {
        Long kassaId = stores.get(d.storeId());
        if (kassaId == null) return false;

        boolean a = ledger.postVozvrat(kassaId, MoneyType.NAQD, d.cashTiyin() / 100, d.date(), "rr:" + d.id() + ":n");
        boolean b = ledger.postVozvrat(kassaId, MoneyType.TERMINAL, d.noCashTiyin() / 100, d.date(), "rr:" + d.id() + ":b");

        // TZ 14: vozvrat balansni manfiyga tushirsa — buxgalterga signal.
        if (a) checkNegative(OwnerType.KASSA, kassaId, "Vozvrat");
        return a || b;
    }

    /* ==================== RASXODLAR (TZ v1.1) ==================== */

    /** «Выплата денег» — savdo nuqtasi kassasidan NAQD chiqim (fakt, tasdiqsiz). */
    private boolean applyDrawerExpense(MoySkladClient.MsExpense e,
                                       Map<String, Long> stores, Map<String, Long> cats) {
        Long kassaId = stores.get(e.storeId());
        if (kassaId == null) {
            log.debug("Noma'lum store {} — Выплата {} o'tkazib yuborildi", e.storeId(), e.id());
            return false;
        }
        long sum = e.sumTiyin() / 100;
        String msId = "dc:" + e.id();
        warnIfChanged(msId, sum);
        String comment = joinNote(e.expenseItem(), e.description());

        boolean posted = ledger.postRasxodSync(OwnerType.KASSA, kassaId, MoneyType.NAQD,
                sum, e.date(), msId, matchCat(cats, e.expenseItem()), comment);
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
     * Kassaga yozilganda o'sha kassa kassirlariga ham xabar boradi.
     */
    private boolean applyCashout(MoySkladClient.MsExpense e, Map<String, Long> groups,
                                 Map<String, String> groupNames, Map<String, Long> cats) {
        long sum = e.sumTiyin() / 100;
        String msId = "co:" + e.id();
        warnIfChanged(msId, sum);
        String comment = joinNote(e.expenseItem(), e.description());
        Long kassaId = groups.get(e.groupId());

        if (kassaId != null) {
            boolean posted = ledger.postRasxodSync(OwnerType.KASSA, kassaId, MoneyType.NAQD,
                    sum, e.date(), msId, matchCat(cats, e.expenseItem()), comment);
            if (posted && shouldNotify(e)) {
                String kassaName = kassaRepo.findById(kassaId).map(Kassa::getName).orElse("Kassa #" + kassaId);
                String text = "💸 MoySklad rasxodi: <b>" + TextUtil.esc(kassaName)
                        + "</b> — <b>" + TextUtil.fmt(sum) + "</b> so'm (💵 Naqd)"
                        + "\n" + docInfo(e)
                        + (comment.isEmpty() ? "" : "\n" + TextUtil.esc(comment));
                notify.toKassa(kassaId, text, null);
                notify.toBuxgalteriya(text, null);
                checkNegative(OwnerType.KASSA, kassaId, "Rasxod");
            }
            return posted;
        }

        String otdel = groupNames.getOrDefault(e.groupId(), "");
        boolean posted = ledger.postRasxodSync(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID,
                MoneyType.NAQD, sum, e.date(), msId, matchCat(cats, e.expenseItem()), comment);
        if (posted && shouldNotify(e)) {
            notify.toBuxgalteriya("💸 MoySklad rasxodi (Расходный ордер): <b>Buxgalteriya</b>"
                    + (otdel.isEmpty() ? "" : " · " + TextUtil.esc(otdel))
                    + " — <b>" + TextUtil.fmt(sum) + "</b> so'm (💵 Naqd)"
                    + "\n" + docInfo(e)
                    + (comment.isEmpty() ? "" : "\n" + TextUtil.esc(comment)), null);
            checkNegative(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, "Rasxod");
        }
        return posted;
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

    /**
     * Kirim (Приходный ордер — NAQD, Входящий платеж «Клик» — KLIK):
     * otdel bog'langan kassaga, aks holda BUXGALTERIYAGA.
     * Kassaga yozilganda o'sha kassa kassirlariga ham xabar boradi.
     */
    private boolean applyIncome(MoySkladClient.MsExpense e, MoneyType mt, String msId,
                                Map<String, Long> groups, Map<String, String> groupNames) {
        long sum = e.sumTiyin() / 100;
        warnIfChanged(msId, sum);
        String comment = joinNote(e.agent(), e.description());
        String mtLabel = switch (mt) {
            case KLIK -> "📲 Klik"; case TERMINAL -> "💳 Terminal"; default -> "💵 Naqd";
        };
        Long kassaId = groups.get(e.groupId());

        if (kassaId != null) {
            boolean posted = ledger.postPrixodSync(OwnerType.KASSA, kassaId, mt,
                    sum, e.date(), msId, comment);
            if (posted && shouldNotify(e)) {
                String kassaName = kassaRepo.findById(kassaId).map(Kassa::getName).orElse("Kassa #" + kassaId);
                String text = "💰 MoySklad kirim: <b>" + TextUtil.esc(kassaName)
                        + "</b> — <b>" + TextUtil.fmt(sum) + "</b> so'm (" + mtLabel + ")"
                        + "\n" + docInfo(e);
                notify.toKassa(kassaId, text, null);
                notify.toBuxgalteriya(text, null);
            }
            return posted;
        }

        String otdel = groupNames.getOrDefault(e.groupId(), "");
        boolean posted = ledger.postPrixodSync(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID,
                mt, sum, e.date(), msId, comment);
        if (posted && shouldNotify(e)) {
            notify.toBuxgalteriya("💰 MoySklad kirim: <b>Buxgalteriya</b>"
                    + (otdel.isEmpty() ? "" : " · " + TextUtil.esc(otdel))
                    + " — <b>" + TextUtil.fmt(sum) + "</b> so'm (" + mtLabel + ")"
                    + "\n" + docInfo(e), null);
        }
        return posted;
    }

    /* ==================== yordamchi ==================== */

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

    /** Hujjat summasi o'zgargan bo'lsa — ogohlantirish (avtokorrektirovka 2-bosqichda). */
    private void warnIfChanged(String moyskladId, long newAmount) {
        if (newAmount <= 0) return;
        Optional<Operation> existing = opRepo.findByMoyskladId(moyskladId);
        if (existing.isPresent() && existing.get().getAmount() != newAmount) {
            notify.toBuxgalteriya("⚠️ MoySkladda hujjat o'zgartirilgan: " + moyskladId
                    + " — eski: " + TextUtil.fmt(existing.get().getAmount())
                    + ", yangi: " + TextUtil.fmt(newAmount)
                    + " so'm. Qo'lda korrektirovka kerak.", null);
        }
    }
}
