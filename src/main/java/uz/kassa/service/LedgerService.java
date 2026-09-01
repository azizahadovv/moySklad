package uz.kassa.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.kassa.config.AppProps;
import uz.kassa.domain.*;
import uz.kassa.repo.BalanceRepo;
import uz.kassa.repo.OperationRepo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tizim yadrosi — LEDGER (TZ 5-bo'lim invariantlari):
 *  - har qanday pul harakati operations jadvaliga yoziladi;
 *  - balances — kesh, faqat shu servis orqali, qatorni qulflab yangilanadi;
 *  - MAVJUD = amount - reserved; rezervlangan puldan ikkinchi chiqim bo'lmaydi;
 *  - NAQD / KLIK / TERMINAL hech qachon aralashmaydi.
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

    /** Buxgalteriya markazi yagona: owner_id = 1 (TZ 9-bo'lim). */
    public static final long BUX_ID = 1L;

    private final BalanceRepo balanceRepo;
    private final OperationRepo opRepo;
    private final DayService dayService;
    private final AuditService audit;
    private final AppProps props;
    private final uz.kassa.repo.DayRepo dayRepo;
    private final uz.kassa.repo.SubmissionRepo subRepo;

    public LocalDate today() { return LocalDate.now(props.zoneId()); }

    /**
     * TO'LIQ TOZALASH (📥 Қайта юклаш): BARCHA operatsiyalar, kun yozuvlari va
     * hisobotlar o'chiriladi, balanslar (rezerv bilan birga) 0 ga tushiriladi.
     * Foydalanuvchi/kassa/Click hisoblari/qarz daftariga TEGILMAYDI.
     * Faqat MoySklad'dan qayta yuklashdan oldin chaqiriladi.
     */
    @Transactional
    public void wipeAllFinancialData() {
        opRepo.deleteAllInBatch();
        subRepo.deleteAll();          // submission_days bolalari bilan birga
        dayRepo.deleteAllInBatch();
        for (Balance b : balanceRepo.findAll()) {
            b.setAmount(0);
            b.setReserved(0);
        }
    }

    /* ============================ BALANS ============================ */

    /** Balans qatorini PESSIMISTIC_WRITE bilan qulflab olish; yo'q bo'lsa 0 bilan yaratish. */
    @Transactional
    public Balance lock(OwnerType ot, Long oid, MoneyType mt) {
        return balanceRepo.lock(ot, oid, mt).orElseGet(() -> {
            balanceRepo.saveAndFlush(Balance.builder()
                    .ownerType(ot).ownerId(oid).moneyType(mt).build());
            return balanceRepo.lock(ot, oid, mt).orElseThrow();
        });
    }

    /** Faqat o'qish uchun (qulfsiz). Qator bo'lmasa nol-balans qaytadi. */
    @Transactional(readOnly = true)
    public Balance view(OwnerType ot, Long oid, MoneyType mt) {
        return balanceRepo.findById(new Balance.Key(ot, oid, mt))
                .orElseGet(() -> Balance.builder().ownerType(ot).ownerId(oid).moneyType(mt).build());
    }

    @Transactional(readOnly = true)
    public List<Balance> balancesOf(OwnerType ot, Long oid) {
        return balanceRepo.findByOwnerTypeAndOwnerId(ot, oid);
    }

    private void touch(Balance b) {
        b.setUpdatedAt(Instant.now());
        balanceRepo.save(b);
    }

    /** Rezerv: mavjud qoldiq yetarli bo'lsa summani band qiladi. */
    @Transactional
    public void reserve(OwnerType ot, Long oid, MoneyType mt, long amount) {
        Balance b = lock(ot, oid, mt);
        if (b.available() < amount) {
            throw new BusinessException("Mavjud qoldiq yetarli emas. Mavjud: "
                    + b.available() + " so'm, so'ralgan: " + amount + " so'm");
        }
        b.setReserved(b.getReserved() + amount);
        touch(b);
    }

    /** Rezervni bekor qilish (rad etilganda). */
    @Transactional
    public void unreserve(OwnerType ot, Long oid, MoneyType mt, long amount) {
        Balance b = lock(ot, oid, mt);
        b.setReserved(b.getReserved() - amount);
        touch(b);
    }

    /** Rezervdagi summani yakuniy yechish (tasdiqlanganda). */
    @Transactional
    public void commitReserved(OwnerType ot, Long oid, MoneyType mt, long amount) {
        Balance b = lock(ot, oid, mt);
        b.setReserved(b.getReserved() - amount);
        b.setAmount(b.getAmount() - amount);
        touch(b);
    }

    /** Balansga qo'shish. */
    @Transactional
    public void credit(OwnerType ot, Long oid, MoneyType mt, long amount) {
        Balance b = lock(ot, oid, mt);
        b.setAmount(b.getAmount() + amount);
        touch(b);
    }

    /** Rezervsiz to'g'ridan-to'g'ri yechish (buxgalterning o'z rasxodi). */
    @Transactional
    public void debit(OwnerType ot, Long oid, MoneyType mt, long amount) {
        Balance b = lock(ot, oid, mt);
        if (b.available() < amount) {
            throw new BusinessException("Mavjud qoldiq yetarli emas. Mavjud: "
                    + b.available() + " so'm");
        }
        b.setAmount(b.getAmount() - amount);
        touch(b);
    }

    /**
     * Hisobot qabul qilinganida: rezerv to'liq bo'shatiladi (releaseReserved),
     * faqat qabul qilingan qism balansdan ayriladi (debitAmount).
     */
    @Transactional
    public void settle(OwnerType ot, Long oid, MoneyType mt, long releaseReserved, long debitAmount) {
        Balance b = lock(ot, oid, mt);
        b.setReserved(b.getReserved() - releaseReserved);
        b.setAmount(b.getAmount() - debitAmount);
        touch(b);
    }

    /* ===================== PRIXOD / VOZVRAT (MoySklad) ===================== */

    /**
     * Sotuv kirimi. Idempotent: moyskladId allaqachon bo'lsa hech narsa qilmaydi (false).
     * TERMINAL kassir balansiga tegmaydi — faqat kun hisobotiga yoziladi (TZ 4-bo'lim).
     */
    @Transactional
    public boolean postPrixod(Long kassaId, MoneyType mt, long amount, LocalDate date, String moyskladId) {
        if (amount <= 0) return false;
        if (moyskladId != null && opRepo.findByMoyskladId(moyskladId).isPresent()) return false;

        opRepo.save(Operation.builder()
                .type(OpType.PRIXOD).moneyType(mt).amount(amount)
                .toOwnerType(OwnerType.KASSA).toOwnerId(kassaId)
                .status(OpStatus.TASDIQLANGAN)
                .opDate(date).moyskladId(moyskladId)
                .build());

        if (mt != MoneyType.TERMINAL) {
            Balance b = lock(OwnerType.KASSA, kassaId, mt);
            b.setAmount(b.getAmount() + amount);
            touch(b);
        }
        dayService.addPrixod(kassaId, date, mt, amount);
        return true;
    }

    /**
     * Vozvrat. Balans MANFIYGA tushishi mumkin (TZ 14-bo'lim) — bu holat
     * chaqiruvchi tomonidan buxgalterga signal sifatida yuboriladi.
     * @return operatsiya yozildi-yo'qligi (idempotentlik).
     */
    @Transactional
    public boolean postVozvrat(Long kassaId, MoneyType mt, long amount, LocalDate date, String moyskladId) {
        if (amount <= 0) return false;
        if (moyskladId != null && opRepo.findByMoyskladId(moyskladId).isPresent()) return false;

        opRepo.save(Operation.builder()
                .type(OpType.VOZVRAT).moneyType(mt).amount(amount)
                .fromOwnerType(OwnerType.KASSA).fromOwnerId(kassaId)
                .status(OpStatus.TASDIQLANGAN)
                .opDate(date).moyskladId(moyskladId)
                .build());

        if (mt != MoneyType.TERMINAL) {
            Balance b = lock(OwnerType.KASSA, kassaId, mt);
            b.setAmount(b.getAmount() - amount);
            touch(b);
        }
        dayService.addVozvrat(kassaId, date, mt, amount);
        return true;
    }

    /**
     * MoySklad'dan kelgan kirim (Приходный ордер / to'lovlar) — FAKT sifatida yoziladi.
     * Otdel bog'langan bo'lsa kassaga, aks holda Buxgalteriyaga.
     * TERMINAL (karta) kassir balansiga QO'SHILMAYDI — faqat kun hisobotida,
     * pul firma bank hisobiga tushadi (TZ 4-bo'lim). Naqd/Klik balansga qo'shiladi.
     * Idempotent: moyskladId bo'yicha qayta yozilmaydi.
     */
    @Transactional
    public boolean postPrixodSync(OwnerType ot, Long oid, MoneyType mt, long amount,
                                  LocalDate date, String moyskladId, String comment) {
        if (amount <= 0) return false;
        if (moyskladId != null && opRepo.findByMoyskladId(moyskladId).isPresent()) return false;

        opRepo.save(Operation.builder()
                .type(OpType.PRIXOD).moneyType(mt).amount(amount)
                .toOwnerType(ot).toOwnerId(oid)
                .status(OpStatus.TASDIQLANGAN)
                .comment(comment)
                .opDate(date).moyskladId(moyskladId)
                .build());

        if (mt != MoneyType.TERMINAL) {
            Balance b = lock(ot, oid, mt);
            b.setAmount(b.getAmount() + amount);
            touch(b);
        }

        if (ot == OwnerType.KASSA) dayService.addPrixod(oid, date, mt, amount);
        return true;
    }

    /**
     * MoySklad hujjatining otdeli o'zgartirilgan — allaqachon yozilgan KIRIMNI
     * eski egadan yangisiga ko'chirish (balans + kun + operatsiya yozuvi).
     */
    @Transactional
    public boolean reroutePrixod(Operation op, OwnerType newOt, Long newOid) {
        OwnerType oldOt = op.getToOwnerType();
        Long oldOid = op.getToOwnerId();
        if (oldOt == newOt && java.util.Objects.equals(oldOid, newOid)) return false;
        MoneyType mt = op.getMoneyType();
        long amount = op.getAmount();

        if (mt != MoneyType.TERMINAL) {
            Balance from = lock(oldOt, oldOid, mt);
            from.setAmount(from.getAmount() - amount);
            touch(from);
            Balance to = lock(newOt, newOid, mt);
            to.setAmount(to.getAmount() + amount);
            touch(to);
        }
        if (oldOt == OwnerType.KASSA) dayService.addPrixod(oldOid, op.getOpDate(), mt, -amount);
        if (newOt == OwnerType.KASSA) dayService.addPrixod(newOid, op.getOpDate(), mt, amount);

        op.setToOwnerType(newOt);
        op.setToOwnerId(newOid);
        opRepo.save(op);
        audit.log(null, "REROUTE", "operation", op.getId(),
                oldOt + ":" + oldOid + " -> " + newOt + ":" + newOid + " " + mt + " " + amount);
        return true;
    }

    /**
     * MoySklad hujjati O'CHIRILGAN yoki BEKOR QILINGAN (провести olib tashlangan) —
     * yozilgan sinxron operatsiyani to'liq STORNO qilish: balans + kun qaytariladi,
     * operatsiya o'chiriladi (hujjat qayta paydo bo'lsa yana yoziladi — idempotent).
     */
    @Transactional
    public boolean reverseSyncOp(Operation op, String reason) {
        MoneyType mt = op.getMoneyType();
        long amount = op.getAmount();
        switch (op.getType()) {
            case PRIXOD -> {
                if (mt != MoneyType.TERMINAL) {
                    Balance b = lock(op.getToOwnerType(), op.getToOwnerId(), mt);
                    b.setAmount(b.getAmount() - amount);
                    touch(b);
                }
                if (op.getToOwnerType() == OwnerType.KASSA)
                    dayService.addPrixod(op.getToOwnerId(), op.getOpDate(), mt, -amount);
            }
            case VOZVRAT -> {
                if (mt != MoneyType.TERMINAL) {
                    Balance b = lock(op.getFromOwnerType(), op.getFromOwnerId(), mt);
                    b.setAmount(b.getAmount() + amount);
                    touch(b);
                }
                if (op.getFromOwnerType() == OwnerType.KASSA)
                    dayService.addVozvrat(op.getFromOwnerId(), op.getOpDate(), mt, -amount);
            }
            case RASXOD -> {
                Balance b = lock(op.getFromOwnerType(), op.getFromOwnerId(), mt);
                b.setAmount(b.getAmount() + amount);
                touch(b);
                if (op.getFromOwnerType() == OwnerType.KASSA)
                    dayService.addRasxod(op.getFromOwnerId(), op.getOpDate(), mt, -amount);
            }
            default -> { return false; }
        }
        audit.log(null, "SYNC_STORNO", "operation", op.getId(),
                op.getType() + " " + mt + " " + amount + " " + op.getMoyskladId() + " | " + reason);
        opRepo.delete(op);
        return true;
    }

    /**
     * MoySklad hujjatining SUMMASI o'zgartirilgan — operatsiya, balans va kun
     * yozuvi farq (delta) bilan yangi summaga moslashtiriladi.
     */
    @Transactional
    public boolean updateSyncAmount(Operation op, long newAmount) {
        long delta = newAmount - op.getAmount();
        if (delta == 0 || newAmount <= 0) return false;
        MoneyType mt = op.getMoneyType();
        switch (op.getType()) {
            case PRIXOD -> {
                if (mt != MoneyType.TERMINAL) {
                    Balance b = lock(op.getToOwnerType(), op.getToOwnerId(), mt);
                    b.setAmount(b.getAmount() + delta);
                    touch(b);
                }
                if (op.getToOwnerType() == OwnerType.KASSA)
                    dayService.addPrixod(op.getToOwnerId(), op.getOpDate(), mt, delta);
            }
            case VOZVRAT -> {
                if (mt != MoneyType.TERMINAL) {
                    Balance b = lock(op.getFromOwnerType(), op.getFromOwnerId(), mt);
                    b.setAmount(b.getAmount() - delta);
                    touch(b);
                }
                if (op.getFromOwnerType() == OwnerType.KASSA)
                    dayService.addVozvrat(op.getFromOwnerId(), op.getOpDate(), mt, delta);
            }
            case RASXOD -> {
                Balance b = lock(op.getFromOwnerType(), op.getFromOwnerId(), mt);
                b.setAmount(b.getAmount() - delta);
                touch(b);
                if (op.getFromOwnerType() == OwnerType.KASSA)
                    dayService.addRasxod(op.getFromOwnerId(), op.getOpDate(), mt, delta);
            }
            default -> { return false; }
        }
        long old = op.getAmount();
        op.setAmount(newAmount);
        opRepo.save(op);
        audit.log(null, "SYNC_AMOUNT", "operation", op.getId(),
                op.getType() + " " + mt + " " + old + " -> " + newAmount + " " + op.getMoyskladId());
        return true;
    }

    /**
     * MoySklad rasxod hujjatining otdeli o'zgartirilgan — allaqachon yozilgan CHIQIMNI
     * eski egadan yangisiga ko'chirish (reroutePrixod'ning rasxod ko'zgusi).
     */
    @Transactional
    public boolean rerouteRasxod(Operation op, OwnerType newOt, Long newOid) {
        OwnerType oldOt = op.getFromOwnerType();
        Long oldOid = op.getFromOwnerId();
        if (oldOt == newOt && java.util.Objects.equals(oldOid, newOid)) return false;
        MoneyType mt = op.getMoneyType();
        long amount = op.getAmount();

        Balance from = lock(oldOt, oldOid, mt);
        from.setAmount(from.getAmount() + amount);
        touch(from);
        Balance to = lock(newOt, newOid, mt);
        to.setAmount(to.getAmount() - amount);
        touch(to);
        if (oldOt == OwnerType.KASSA) dayService.addRasxod(oldOid, op.getOpDate(), mt, -amount);
        if (newOt == OwnerType.KASSA) dayService.addRasxod(newOid, op.getOpDate(), mt, amount);

        op.setFromOwnerType(newOt);
        op.setFromOwnerId(newOid);
        opRepo.save(op);
        audit.log(null, "REROUTE_RASXOD", "operation", op.getId(),
                oldOt + ":" + oldOid + " -> " + newOt + ":" + newOid + " " + mt + " " + amount);
        return true;
    }

    /* ==================== RASXOD (MoySklad sinxron, TZ v1.1) ==================== */

    /**
     * MoySklad'dan kelgan rasxod hujjati — FAKT sifatida yoziladi:
     * tasdiqlash bosqichi yo'q, chunki pul allaqachon sarflangan.
     * Balans MANFIYGA tushishi mumkin — chaqiruvchi buxgalterga signal yuboradi.
     * Idempotent: moyskladId bo'yicha qayta yozilmaydi.
     */
    @Transactional
    public boolean postRasxodSync(OwnerType ot, Long oid, MoneyType mt, long amount,
                                  LocalDate date, String moyskladId,
                                  Long categoryId, String comment) {
        if (amount <= 0) return false;
        if (moyskladId != null && opRepo.findByMoyskladId(moyskladId).isPresent()) return false;

        opRepo.save(Operation.builder()
                .type(OpType.RASXOD).moneyType(mt).amount(amount)
                .fromOwnerType(ot).fromOwnerId(oid)
                .status(OpStatus.TASDIQLANGAN)
                .categoryId(categoryId).comment(comment)
                .opDate(date).moyskladId(moyskladId)
                .build());

        Balance b = lock(ot, oid, mt);
        b.setAmount(b.getAmount() - amount);
        touch(b);

        if (ot == OwnerType.KASSA) dayService.addRasxod(oid, date, mt, amount);
        return true;
    }

    /* ============== BOSHLANG'ICH QOLDIQ / KORREKTIROVKA ============== */

    /**
     * Ishorali summa: musbat — qo'shish, manfiy — ayirish.
     * type: BOSHLANGICH yoki KORREKTIROVKA. Faqat SuperAdmin chaqiradi.
     */
    @Transactional
    public Operation postAdjustment(OpType type, OwnerType ot, Long oid, MoneyType mt,
                                    long signedAmount, String reason, Long byUserId) {
        return postAdjustment(type, ot, oid, mt, signedAmount, reason, byUserId, null);
    }

    /** date null bo'lsa — bugun. */
    @Transactional
    public Operation postAdjustment(OpType type, OwnerType ot, Long oid, MoneyType mt,
                                    long signedAmount, String reason, Long byUserId,
                                    java.time.LocalDate date) {
        if (signedAmount == 0) throw new BusinessException("Summa nolga teng bo'lishi mumkin emas");
        if (mt == MoneyType.TERMINAL) throw new BusinessException("Terminal balansda yuritilmaydi");

        Balance b = lock(ot, oid, mt);
        b.setAmount(b.getAmount() + signedAmount);
        touch(b);

        // Kassa korrektirovkasi VA boshlang'ich qoldig'i tanlangan SANANING kun
        // yozuviga ham tushadi — balans va kunlar kesimi (Баланс bo'limi)
        // bir-biridan uzoqlashib ketmasin (aks holda boshlang'ich qoldiq
        // «Баланс — НАҚД» ko'rinishida umuman ko'rinmay qolardi).
        LocalDate d = date == null ? today() : date;
        if ((type == OpType.KORREKTIROVKA || type == OpType.BOSHLANGICH) && ot == OwnerType.KASSA) {
            if (signedAmount > 0) dayService.addKirim(oid, d, mt, signedAmount);
            else dayService.addChiqim(oid, d, mt, -signedAmount);
            DayRecord day = dayService.getOrCreate(oid, d);
            if (day.getStatus() == DayStatus.QABUL_QILINGAN
                    && (day.remainNaqd() != 0 || day.remainKlik() != 0))
                day.setStatus(DayStatus.YOPILGAN);
        }

        Operation.OperationBuilder ob = Operation.builder()
                .type(type).moneyType(mt).amount(Math.abs(signedAmount))
                .status(OpStatus.TASDIQLANGAN)
                .comment(reason).opDate(d)
                .createdBy(byUserId).decidedBy(byUserId).decidedAt(Instant.now());
        if (signedAmount > 0) ob.toOwnerType(ot).toOwnerId(oid);
        else ob.fromOwnerType(ot).fromOwnerId(oid);

        Operation op = opRepo.save(ob.build());
        audit.log(byUserId, type.name(), "operation", op.getId(),
                ot + ":" + oid + " " + mt + " " + signedAmount + " | " + reason);
        return op;
    }

    /**
     * Balans TANLANGAN KUN OXIRIDA qancha bo'lgan: joriy balansdan o'sha kundan
     * KEYIN (op_date > date) o'tgan tasdiqlangan harakatlar ayirib tashlanadi.
     * O'tgan sana bilan korrektirovka qilishda ishlatiladi — bugungi
     * prixod-rasxodlar korrektirovka ichiga «yutilib» ketmasligi uchun.
     */
    @Transactional(readOnly = true)
    public long balanceAsOf(OwnerType ot, Long oid, MoneyType mt, LocalDate date) {
        long cur = view(ot, oid, mt).getAmount();
        long after = 0;
        for (Operation o : opRepo.balanceOpsAfter(ot, oid, mt, date)) {
            if (o.getToOwnerType() == ot && oid.equals(o.getToOwnerId())) after += o.getAmount();
            if (o.getFromOwnerType() == ot && oid.equals(o.getFromOwnerId())) after -= o.getAmount();
        }
        return cur - after;
    }

    /* ==================== ♻️ NOL BOSHLASH ==================== */

    /**
     * ♻️ Nol boshlash (faqat SuperAdmin): beforeExclusive dan OLDINGI barcha
     * OCHIQ/YOPILGAN kunlar QABUL_QILINGAN deb yopiladi, ularning qoldig'i kassa
     * balansidan KORREKTIROVKA operatsiyasi bilan chiqariladi — kassa hisobni
     * 0 dan boshlaydi. Bugungi (beforeExclusive) kun saqlanadi, tarix jurnalda qoladi.
     * @return {yopilgan naqd, yopilgan klik, kunlar soni}
     */
    @Transactional
    public long[] resetKassaBefore(Long kassaId, LocalDate beforeExclusive, Long byUserId) {
        List<DayRecord> days = dayRepo.findByKassaIdAndStatusInOrderByDateAsc(
                        kassaId, List.of(DayStatus.OCHIQ, DayStatus.YOPILGAN)).stream()
                .filter(d -> d.getDate().isBefore(beforeExclusive)).toList();
        long n = 0, k = 0;
        for (DayRecord d : days) {
            n += d.remainNaqd();
            k += d.remainKlik();
            d.setCoveredNaqd(d.netNaqd());
            d.setCoveredKlik(d.netKlik());
            d.setStatus(DayStatus.QABUL_QILINGAN);
        }
        dayRepo.saveAll(days);
        resetOp(kassaId, MoneyType.NAQD, n, beforeExclusive, byUserId);
        resetOp(kassaId, MoneyType.KLIK, k, beforeExclusive, byUserId);
        audit.log(byUserId, "NOL_BOSHLASH", "kassa", kassaId,
                "naqd=" + n + " klik=" + k + " kunlar=" + days.size()
                        + " before=" + beforeExclusive);
        return new long[]{n, k, days.size()};
    }

    /** Yopilgan kunlar qoldig'ini balansdan chiqarish + jurnal yozuvi. */
    private void resetOp(Long kassaId, MoneyType mt, long closedRemain,
                         LocalDate before, Long byUserId) {
        if (closedRemain == 0) return;
        Balance b = lock(OwnerType.KASSA, kassaId, mt);
        b.setAmount(b.getAmount() - closedRemain);
        touch(b);
        Operation.OperationBuilder ob = Operation.builder()
                .type(OpType.KORREKTIROVKA).moneyType(mt).amount(Math.abs(closedRemain))
                .status(OpStatus.TASDIQLANGAN)
                .comment("♻️ Nol boshlash: " + before + " gacha kunlar yopildi")
                .opDate(today())
                .createdBy(byUserId).decidedBy(byUserId).decidedAt(Instant.now());
        if (closedRemain > 0) ob.fromOwnerType(OwnerType.KASSA).fromOwnerId(kassaId);
        else ob.toOwnerType(OwnerType.KASSA).toOwnerId(kassaId);
        opRepo.save(ob.build());
    }

    /* ==================== ✅ YAXLITLIK TEKSHIRUVI ==================== */

    /** Balans qatoridagi nomuvofiqlik: saqlangan va operatsiyalardan qayta hisoblangan qiymat farq qiladi. */
    public record Mismatch(OwnerType ownerType, Long ownerId, MoneyType moneyType, long expected, long actual) {
        public long diff() { return actual - expected; }
    }

    /**
     * Har bir balans qatorini TASDIQLANGAN operatsiyalar tarixidan qayta hisoblab,
     * saqlangan Balance.amount bilan solishtiradi. Farq topilsa — o'sha qator
     * qaytariladi (bo'sh ro'yxat — hammasi mos). TERMINAL balansda yuritilmaydi,
     * shuning uchun hisobga olinmaydi.
     */
    @Transactional(readOnly = true)
    public List<Mismatch> verifyIntegrity() {
        // SQL GROUP BY — butun operations jadvalini xotiraga yuklamasdan
        Map<Balance.Key, Long> computed = new HashMap<>();
        for (Object[] r : opRepo.confirmedInSums())
            computed.merge(new Balance.Key((OwnerType) r[0], (Long) r[1], (MoneyType) r[2]),
                    (Long) r[3], Long::sum);
        for (Object[] r : opRepo.confirmedOutSums())
            computed.merge(new Balance.Key((OwnerType) r[0], (Long) r[1], (MoneyType) r[2]),
                    -((Long) r[3]), Long::sum);
        List<Mismatch> issues = new java.util.ArrayList<>();
        for (Balance b : balanceRepo.findAll()) {
            if (b.getMoneyType() == MoneyType.TERMINAL) continue;
            Balance.Key key = new Balance.Key(b.getOwnerType(), b.getOwnerId(), b.getMoneyType());
            long expected = computed.getOrDefault(key, 0L);
            if (expected != b.getAmount())
                issues.add(new Mismatch(b.getOwnerType(), b.getOwnerId(), b.getMoneyType(),
                        expected, b.getAmount()));
        }
        return issues;
    }
}
