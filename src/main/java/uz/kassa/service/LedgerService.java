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
import java.util.List;

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

    public LocalDate today() { return LocalDate.now(props.zoneId()); }

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
        if (signedAmount == 0) throw new BusinessException("Summa nolga teng bo'lishi mumkin emas");
        if (mt == MoneyType.TERMINAL) throw new BusinessException("Terminal balansda yuritilmaydi");

        Balance b = lock(ot, oid, mt);
        b.setAmount(b.getAmount() + signedAmount);
        touch(b);

        Operation.OperationBuilder ob = Operation.builder()
                .type(type).moneyType(mt).amount(Math.abs(signedAmount))
                .status(OpStatus.TASDIQLANGAN)
                .comment(reason).opDate(today())
                .createdBy(byUserId).decidedBy(byUserId).decidedAt(Instant.now());
        if (signedAmount > 0) ob.toOwnerType(ot).toOwnerId(oid);
        else ob.fromOwnerType(ot).fromOwnerId(oid);

        Operation op = opRepo.save(ob.build());
        audit.log(byUserId, type.name(), "operation", op.getId(),
                ot + ":" + oid + " " + mt + " " + signedAmount + " | " + reason);
        return op;
    }
}
