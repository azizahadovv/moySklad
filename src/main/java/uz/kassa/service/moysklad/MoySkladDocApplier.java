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
import static uz.kassa.service.moysklad.MoySkladSyncService.*;
import uz.kassa.service.moysklad.SyncSupport.Ctx;

/**
 * MoySklad hujjatlarini ledger'ga qo'llash: sotuv/vozvrat, Выплата денег, Расходный/Приходный ордер, Входящий/Исходящий платеж.
 * (MoySkladSyncService dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MoySkladDocApplier {

    private final LedgerService ledger;
    private final KassaRepo kassaRepo;
    private final OperationRepo opRepo;
    private final SettingsService settings;
    private final NotificationService notify;

    /** Klik'ga o'xshagan, lekin sozlangan nomga mos kelmagan statuslar — bir marta ogohlantiriladi. */
    private final java.util.Set<String> unknownStateWarned =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final SyncSupport sup;


    String klikState() {
        return settings.get(KLIK_STATE_KEY).filter(v -> !v.isBlank()).orElse("Клик");
    }


    String terminalState() {
        return settings.get(TERMINAL_STATE_KEY).filter(v -> !v.isBlank()).orElse("Картадан тулов");
    }


    MoneyType paymentMt(String state) {
        return state.equalsIgnoreCase(klikState()) ? MoneyType.KLIK
                : state.equalsIgnoreCase(terminalState()) ? MoneyType.TERMINAL
                : null;
    }


    boolean dispatchPaymentIn(MoySkladClient.MsExpense e, Ctx ctx) {
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
            if (sup.skipNewPreEpoch(e.date(), existing)) return false;
            return existing.isPresent()
                    && sup.storno(existing.get(), "status «" + e.state() + "»ga o'zgargan", sup.docInfo(e));
        }
        return applyIncome(e, mt, "pi:" + e.id(), ctx);
    }


    /* ==================== SOTUV / VOZVRAT (retail) ==================== */

    boolean applySale(MoySkladClient.MsDoc d, Ctx ctx) {
        Long kassaId = ctx.storeToKassa().get(d.storeId());
        boolean a = syncRetailPart(kassaId, MoneyType.NAQD, d.cashTiyin() / 100, d, "rd:" + d.id() + ":n", true);
        boolean b = syncRetailPart(kassaId, MoneyType.TERMINAL, d.noCashTiyin() / 100, d, "rd:" + d.id() + ":b", true);
        return a || b;
    }


    boolean applyReturn(MoySkladClient.MsDoc d, Ctx ctx) {
        Long kassaId = ctx.storeToKassa().get(d.storeId());
        boolean a = syncRetailPart(kassaId, MoneyType.NAQD, d.cashTiyin() / 100, d, "rr:" + d.id() + ":n", false);
        boolean b = syncRetailPart(kassaId, MoneyType.TERMINAL, d.noCashTiyin() / 100, d, "rr:" + d.id() + ":b", false);
        // TZ 14: vozvrat balansni manfiyga tushirsa — buxgalterga signal.
        if (a && kassaId != null) sup.checkNegative(OwnerType.KASSA, kassaId, "Vozvrat");
        return a || b;
    }


    /** Sotuv/vozvratning bitta bo'lagi (naqd yoki karta): STORNO / summa tuzatish / yangi yozuv. */
    boolean syncRetailPart(Long kassaId, MoneyType mt, long sum,
                                   MoySkladClient.MsDoc d, String msId, boolean isSale) {
        Optional<Operation> existing = opRepo.findByMoyskladId(msId);
        if (sup.skipNewPreEpoch(d.date(), existing)) return false;

        if (!d.applicable() || sum <= 0) {
            return existing.isPresent()
                    && sup.storno(existing.get(),
                        !d.applicable() ? "hujjat o'tkazilishi bekor qilingan" : "summa nolga tushirilgan", "");
        }

        if (existing.isPresent()) {
            Operation op = existing.get();
            if (!op.getOpDate().equals(d.date())) {
                if (!sup.storno(op, "hujjat sanasi o'zgargan — qayta yoziladi", "")) return false;
                // pastda yangi sana bilan qayta yoziladi
            } else {
                if (op.getAmount() != sum && ledger.updateSyncAmount(op, sum)) {
                    if (sup.loudFix(d.date()))
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
    boolean applyDrawerExpense(MoySkladClient.MsExpense e, Ctx ctx) {
        long sum = sup.somSum(e);   // valyuta hujjati MoySklad kursi bilan so'mga o'giriladi
        String msId = "dc:" + e.id();
        String fx = sup.fxNote(e);
        String comment = sup.joinNote(e.expenseItem(), e.description());
        if (!fx.isEmpty()) comment = comment.isEmpty() ? fx : fx + " · " + comment;
        Optional<Operation> existing = opRepo.findByMoyskladId(msId);
        if (sup.skipNewPreEpoch(e.date(), existing)) return false;

        if (sum < 0) {   // valyuta hujjati, kurs kiritilmagan — taxmin qilinmaydi
            sup.warnNoRate(e);
            return existing.isPresent()
                    && sup.storno(existing.get(), "valyuta kursi kiritilmagan", sup.docInfo(e));
        }
        if (!e.applicable() || sum == 0) {
            return existing.isPresent()
                    && sup.storno(existing.get(),
                        !e.applicable() ? "hujjat o'tkazilishi bekor qilingan" : "summa nolga tushirilgan", "");
        }

        if (existing.isPresent()) {
            Operation op = existing.get();
            if (!op.getOpDate().equals(e.date())) {
                if (!sup.storno(op, "hujjat sanasi o'zgargan — qayta yoziladi", "")) return false;
            } else {
                if (op.getAmount() != sum) {
                    long old = op.getAmount();
                    if (ledger.updateSyncAmount(op, sum)) {
                        if (sup.loudFix(e.date()))
                            notify.toBuxgalteriya("✏️ MoySklad rasxod summasi o'zgargan — avtomatik tuzatildi:\n"
                                    + "<b>" + TextUtil.esc(sup.ownerName(op)) + "</b>: " + TextUtil.fmt(old)
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
                sum, e.date(), msId, sup.matchCat(ctx.catByName(), e.expenseItem()), comment);
        if (posted) {
            String kassaName = kassaRepo.findById(kassaId).map(Kassa::getName).orElse("Kassa #" + kassaId);
            notify.toBuxgalteriya("💸 MoySklad rasxodi: <b>" + TextUtil.esc(kassaName)
                    + "</b> — <b>" + TextUtil.fmt(sum) + "</b> so'm (💵 Naqd)"
                    + (comment.isEmpty() ? "" : "\n" + TextUtil.esc(comment)), null);
            sup.checkNegative(OwnerType.KASSA, kassaId, "Rasxod");
        }
        return posted;
    }


    /**
     * «Расходный ордер» — otdel (group) bog'langan kassaga, aks holda BUXGALTERIYAGA yoziladi.
     * O'zgarishlar avtomatik tuzatiladi: summa (delta), otdel (reroute), sana (storno+qayta).
     */
    boolean applyCashout(MoySkladClient.MsExpense e, Ctx ctx) {
        long sum = sup.somSum(e);   // valyuta hujjati MoySklad kursi bilan so'mga o'giriladi
        String msId = "co:" + e.id();
        String fx = sup.fxNote(e);
        String comment = sup.joinNote(e.expenseItem(), e.description());
        if (!fx.isEmpty()) comment = comment.isEmpty() ? fx : fx + " · " + comment;
        Long kassaId = ctx.groupToKassa().get(e.groupId());
        OwnerType wantOt = kassaId != null ? OwnerType.KASSA : OwnerType.BUXGALTERIYA;
        Long wantOid = kassaId != null ? kassaId : LedgerService.BUX_ID;
        Optional<Operation> existing = opRepo.findByMoyskladId(msId);
        if (sup.skipNewPreEpoch(e.date(), existing)) return false;

        if (sum < 0) {   // valyuta hujjati, kurs kiritilmagan — taxmin qilinmaydi
            sup.warnNoRate(e);
            return existing.isPresent()
                    && sup.storno(existing.get(), "valyuta kursi kiritilmagan", sup.docInfo(e));
        }
        if (!e.applicable() || sum == 0) {
            return existing.isPresent()
                    && sup.storno(existing.get(),
                        !e.applicable() ? "hujjat o'tkazilishi bekor qilingan"
                                : "summa nolga tushirilgan",
                        sup.docInfo(e));
        }

        if (existing.isPresent()) {
            Operation op = existing.get();
            if (!op.getOpDate().equals(e.date())) {
                if (!sup.storno(op, "hujjat sanasi o'zgargan — qayta yoziladi", sup.docInfo(e))) return false;
                // pastda yangi sana bilan qayta yoziladi
            } else {
                boolean changed = false;
                if (op.getAmount() != sum) {
                    long old = op.getAmount();
                    if (ledger.updateSyncAmount(op, sum)) {
                        if (sup.loudFix(e.date()))
                            notify.toBuxgalteriya("✏️ MoySklad rasxod summasi o'zgargan — avtomatik tuzatildi:\n"
                                    + sup.docInfo(e) + "\n<b>" + TextUtil.esc(sup.ownerName(op)) + "</b>: "
                                    + TextUtil.fmt(old) + " → " + TextUtil.fmt(sum) + " so'm (💵 Naqd)", null);
                        changed = true;
                    }
                }
                // Dublikat-otdel (bir otdel bir nechta kassada): egasi noaniq — ko'chirilmaydi
                if ((op.getFromOwnerType() != wantOt
                        || !java.util.Objects.equals(op.getFromOwnerId(), wantOid))
                        && !ctx.dupGroups().contains(e.groupId())) {
                    String oldName = sup.ownerName(op);
                    if (ledger.rerouteRasxod(op, wantOt, wantOid)) {
                        String newName = wantOt == OwnerType.BUXGALTERIYA ? "Отдел Основной"
                                : kassaRepo.findById(wantOid).map(Kassa::getName).orElse("Kassa #" + wantOid);
                        if (sup.loudFix(e.date()))
                            notify.toBuxgalteriya("🔀 MoySklad hujjat otdeli o'zgartirilgan — chiqim ko'chirildi:\n"
                                    + sup.docInfo(e) + "\n<b>" + TextUtil.esc(oldName) + "</b> → <b>"
                                    + TextUtil.esc(newName) + "</b> · " + TextUtil.fmt(sum) + " so'm (💵 Naqd)", null);
                        log.info("Rasxod qayta yo'naltirildi: {} {} -> {}", msId, oldName, newName);
                        changed = true;
                    }
                }
                return changed;
            }
        }

        boolean posted = ledger.postRasxodSync(wantOt, wantOid, MoneyType.NAQD,
                sum, e.date(), msId, sup.matchCat(ctx.catByName(), e.expenseItem()), comment);
        if (posted && sup.shouldNotify(e)) {
            if (wantOt == OwnerType.KASSA) {
                String kassaName = kassaRepo.findById(wantOid).map(Kassa::getName).orElse("Kassa #" + wantOid);
                String text = "💸 MoySklad rasxodi: <b>" + TextUtil.esc(kassaName)
                        + "</b> — <b>" + TextUtil.fmt(sum) + "</b> so'm (💵 Naqd)"
                        + "\n" + sup.docInfo(e)
                        + (comment.isEmpty() ? "" : "\n" + TextUtil.esc(comment));
                notify.toKassa(wantOid, text, null);
                notify.toBuxgalteriya(text, null);
            } else {
                String otdel = ctx.groupNames().getOrDefault(e.groupId(), "");
                notify.toBuxgalteriya("💸 MoySklad rasxodi (Расходный ордер): <b>Отдел Основной</b>"
                        + (otdel.isEmpty() ? "" : " · " + TextUtil.esc(otdel))
                        + " — <b>" + TextUtil.fmt(sum) + "</b> so'm (💵 Naqd)"
                        + "\n" + sup.docInfo(e)
                        + (comment.isEmpty() ? "" : "\n" + TextUtil.esc(comment)), null);
            }
            sup.checkNegative(wantOt, wantOid, "Rasxod");
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
    boolean applyIncome(MoySkladClient.MsExpense e, MoneyType mt, String msId, Ctx ctx) {
        long sum = sup.somSum(e);   // valyuta hujjati MoySklad kursi bilan so'mga o'giriladi
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
        String fx = sup.fxNote(e);
        String comment = sup.joinNote(e.agent(), e.description());
        if (!fx.isEmpty()) comment = comment.isEmpty() ? fx : fx + " · " + comment;
        String mtLabel = sup.mtLabel(mt);
        Optional<Operation> existing = opRepo.findByMoyskladId(msId);
        if (sup.skipNewPreEpoch(e.date(), existing)) return false;

        if (sum < 0) {   // valyuta hujjati, kurs kiritilmagan — taxmin qilinmaydi
            sup.warnNoRate(e);
            return existing.isPresent()
                    && sup.storno(existing.get(), "valyuta kursi kiritilmagan", sup.docInfo(e));
        }
        if (!e.applicable() || sum == 0) {
            return existing.isPresent()
                    && sup.storno(existing.get(),
                        !e.applicable() ? "hujjat o'tkazilishi bekor qilingan"
                                : "summa nolga tushirilgan",
                        sup.docInfo(e));
        }

        if (existing.isPresent()) {
            Operation op = existing.get();
            if (op.getMoneyType() != mt || !op.getOpDate().equals(e.date())) {
                if (!sup.storno(op, "pul turi/sana o'zgargan — qayta yoziladi", sup.docInfo(e))) return false;
                // pastda yangi qiymatlar bilan qayta yoziladi
            } else {
                boolean changed = false;
                if (op.getAmount() != sum) {
                    long old = op.getAmount();
                    if (ledger.updateSyncAmount(op, sum)) {
                        if (sup.loudFix(e.date()))
                            notify.toBuxgalteriya("✏️ MoySklad kirim summasi o'zgargan — avtomatik tuzatildi:\n"
                                    + sup.docInfo(e) + "\n<b>" + TextUtil.esc(sup.ownerName(op)) + "</b>: "
                                    + TextUtil.fmt(old) + " → " + TextUtil.fmt(sum)
                                    + " so'm (" + mtLabel + ")", null);
                        changed = true;
                    }
                }
                // Dublikat-otdel (bir otdel bir nechta kassada): egasi noaniq — ko'chirilmaydi
                if ((op.getToOwnerType() != wantOt
                        || !java.util.Objects.equals(op.getToOwnerId(), wantOid))
                        && !ctx.dupGroups().contains(e.groupId())) {
                    String oldName = sup.ownerName(op);
                    if (ledger.reroutePrixod(op, wantOt, wantOid)) {
                        String newName = sup.ownerDisplayName(wantOt, wantOid);
                        if (sup.loudFix(e.date()))
                            notify.toBuxgalteriya("🔀 MoySklad hujjat otdeli o'zgartirilgan — kirim ko'chirildi:\n"
                                    + sup.docInfo(e) + "\n<b>" + TextUtil.esc(oldName) + "</b> → <b>"
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
        if (posted && sup.shouldNotify(e)) {
            if (wantOt == OwnerType.KASSA) {
                String kassaName = sup.ownerDisplayName(wantOt, wantOid);
                String text = "💰 MoySklad kirim: <b>" + TextUtil.esc(kassaName)
                        + "</b> — <b>" + TextUtil.fmt(sum) + "</b> so'm (" + mtLabel + ")"
                        + "\n" + sup.docInfo(e);
                notify.toKassa(wantOid, text, null);
                notify.toBuxgalteriya(text, null);
            } else if (wantOt == OwnerType.CLICK) {
                notify.toBuxgalteriya("💰 MoySklad kirim: <b>" + TextUtil.esc(sup.ownerDisplayName(wantOt, wantOid))
                        + "</b> — <b>" + TextUtil.fmt(sum) + "</b> so'm (" + mtLabel + ")"
                        + "\n" + sup.docInfo(e), null);
            } else {
                String otdel = ctx.groupNames().getOrDefault(e.groupId(), "");
                notify.toBuxgalteriya("💰 MoySklad kirim: <b>Отдел Основной</b>"
                        + (otdel.isEmpty() ? "" : " · " + TextUtil.esc(otdel))
                        + " — <b>" + TextUtil.fmt(sum) + "</b> so'm (" + mtLabel + ")"
                        + "\n" + sup.docInfo(e), null);
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
    boolean dispatchPaymentOut(MoySkladClient.MsExpense e, Ctx ctx) {
        if (!ctx.accountToClick().containsKey(e.accountId())) return false;
        return applyPaymentOutKlik(e, ctx);
    }


    /** «Исходящий платеж» — faqat ro'yxatdagi Click hisobiga (organizationAccount) yozilgan chiqim. */
    boolean applyPaymentOutKlik(MoySkladClient.MsExpense e, Ctx ctx) {
        long sum = sup.somSum(e);
        String msId = "po:" + e.id();
        String fx = sup.fxNote(e);
        String comment = sup.joinNote(e.agent(), e.description());
        if (!fx.isEmpty()) comment = comment.isEmpty() ? fx : fx + " · " + comment;

        Long kassaId = ctx.groupToKassa().get(e.groupId());
        OwnerType wantOt = kassaId != null ? OwnerType.KASSA : OwnerType.BUXGALTERIYA;
        Long wantOid = kassaId != null ? kassaId : LedgerService.BUX_ID;
        Long clickId = ctx.accountToClick().get(e.accountId());
        if (clickId != null) { wantOt = OwnerType.CLICK; wantOid = clickId; }

        Optional<Operation> existing = opRepo.findByMoyskladId(msId);
        if (sup.skipNewPreEpoch(e.date(), existing)) return false;

        if (sum < 0) {   // valyuta hujjati, kurs kiritilmagan — taxmin qilinmaydi
            sup.warnNoRate(e);
            return existing.isPresent()
                    && sup.storno(existing.get(), "valyuta kursi kiritilmagan", sup.docInfo(e));
        }
        if (!e.applicable() || sum == 0) {
            return existing.isPresent()
                    && sup.storno(existing.get(),
                        !e.applicable() ? "hujjat o'tkazilishi bekor qilingan"
                                : "summa nolga tushirilgan",
                        sup.docInfo(e));
        }

        if (existing.isPresent()) {
            Operation op = existing.get();
            if (!op.getOpDate().equals(e.date())) {
                if (!sup.storno(op, "hujjat sanasi o'zgargan — qayta yoziladi", sup.docInfo(e))) return false;
            } else {
                boolean changed = false;
                if (op.getAmount() != sum) {
                    long old = op.getAmount();
                    if (ledger.updateSyncAmount(op, sum)) {
                        if (sup.loudFix(e.date()))
                            notify.toBuxgalteriya("✏️ MoySklad Klik rasxodi summasi o'zgargan — avtomatik tuzatildi:\n"
                                    + sup.docInfo(e) + "\n<b>" + TextUtil.esc(sup.ownerName(op)) + "</b>: "
                                    + TextUtil.fmt(old) + " → " + TextUtil.fmt(sum) + " so'm (📲 Klik)", null);
                        changed = true;
                    }
                }
                if ((op.getFromOwnerType() != wantOt
                        || !java.util.Objects.equals(op.getFromOwnerId(), wantOid))
                        && !ctx.dupGroups().contains(e.groupId())) {
                    String oldName = sup.ownerName(op);
                    if (ledger.rerouteRasxod(op, wantOt, wantOid)) {
                        String newName = sup.ownerDisplayName(wantOt, wantOid);
                        if (sup.loudFix(e.date()))
                            notify.toBuxgalteriya("🔀 MoySklad hujjat otdeli o'zgartirilgan — Klik chiqim ko'chirildi:\n"
                                    + sup.docInfo(e) + "\n<b>" + TextUtil.esc(oldName) + "</b> → <b>"
                                    + TextUtil.esc(newName) + "</b> · " + TextUtil.fmt(sum) + " so'm (📲 Klik)", null);
                        changed = true;
                    }
                }
                return changed;
            }
        }

        boolean posted = ledger.postRasxodSync(wantOt, wantOid, MoneyType.KLIK,
                sum, e.date(), msId, sup.matchCat(ctx.catByName(), e.expenseItem()), comment);
        if (posted && sup.shouldNotify(e)) {
            String text = "💸 MoySklad Klik rasxodi: <b>" + TextUtil.esc(sup.ownerDisplayName(wantOt, wantOid))
                    + "</b> — <b>" + TextUtil.fmt(sum) + "</b> so'm (📲 Klik)"
                    + "\n" + sup.docInfo(e)
                    + (comment.isEmpty() ? "" : "\n" + TextUtil.esc(comment));
            if (wantOt == OwnerType.KASSA) notify.toKassa(wantOid, text, null);
            notify.toBuxgalteriya(text, null);
            sup.checkNegative(wantOt, wantOid, MoneyType.KLIK, "Klik rasxod");
        }
        return posted;
    }

}
