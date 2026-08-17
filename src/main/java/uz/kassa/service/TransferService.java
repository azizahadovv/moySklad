package uz.kassa.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.kassa.domain.*;
import uz.kassa.repo.DebtRepo;
import uz.kassa.repo.OperationRepo;

import java.time.Instant;

/** Kassalararo va kassa↔buxgalteriya o'tkazmalari + qarzlar (TZ 7.4). */
@Service
@RequiredArgsConstructor
public class TransferService {

    private final LedgerService ledger;
    private final OperationRepo opRepo;
    private final DebtRepo debtRepo;
    private final DayService dayService;
    private final AuditService audit;

    /** O'tkazma yaratish: summa yuboruvchida REZERVGA olinadi, holat — YO'LDA. */
    @Transactional
    public Operation create(AppUser byUser,
                            OwnerType fromT, Long fromId,
                            OwnerType toT, Long toId,
                            MoneyType mt, long amount,
                            TransferKind kind, Long debtId, String comment) {

        if (mt == MoneyType.TERMINAL)
            throw new BusinessException("Terminal puli o'tkazilmaydi — u firma hisobida");
        if (amount <= 0) throw new BusinessException("Summa noldan katta bo'lishi kerak");
        if (fromT == toT && fromId.equals(toId))
            throw new BusinessException("O'zingizga o'tkaza olmaysiz");

        Debt debt = null;
        if (kind == TransferKind.QARZ_QAYTARISH) {
            if (debtId == null) throw new BusinessException("Qaytariladigan qarz tanlanmagan");
            debt = debtRepo.findById(debtId)
                    .orElseThrow(() -> new BusinessException("Qarz topilmadi"));
            if (debt.getStatus() != DebtStatus.OCHIQ)
                throw new BusinessException("Bu qarz allaqachon yopilgan");
            boolean debtorOk = debt.getDebtorType() == fromT && debt.getDebtorId().equals(fromId);
            boolean creditorOk = debt.getCreditorType() == toT && debt.getCreditorId().equals(toId);
            if (!debtorOk || !creditorOk)
                throw new BusinessException("Qarz taraflari o'tkazmaga mos emas");
            if (debt.getMoneyType() != mt)
                throw new BusinessException("Qarz " + debt.getMoneyType() + " turida — pul turi mos emas");
            if (amount > debt.remain())
                throw new BusinessException("Qarz qoldig'i " + debt.remain() + " so'm — undan ko'p qaytarib bo'lmaydi");
        }

        ledger.reserve(fromT, fromId, mt, amount);

        Operation op = opRepo.save(Operation.builder()
                .type(OpType.OTKAZMA).moneyType(mt).amount(amount)
                .fromOwnerType(fromT).fromOwnerId(fromId)
                .toOwnerType(toT).toOwnerId(toId)
                .status(OpStatus.YOLDA)
                .transferKind(kind).debtId(debtId).comment(comment)
                .opDate(ledger.today()).createdBy(byUser.getId())
                .build());
        audit.log(byUser.getId(), "OTKAZMA_YARATILDI", "operation", op.getId(),
                fromT + ":" + fromId + " -> " + toT + ":" + toId + " " + mt + " " + amount + " " + kind);
        return op;
    }

    /** Qabul qiluvchi «Oldim» bosdi — pul haqiqatda ko'chadi («ikki qo'l» printsipi). */
    @Transactional
    public Operation accept(Long opId, AppUser by) {
        Operation op = opRepo.findById(opId)
                .orElseThrow(() -> new BusinessException("O'tkazma topilmadi"));
        if (op.getStatus() != OpStatus.YOLDA)
            throw new BusinessException("Bu o'tkazma allaqachon yakunlangan");

        ledger.commitReserved(op.getFromOwnerType(), op.getFromOwnerId(), op.getMoneyType(), op.getAmount());
        ledger.credit(op.getToOwnerType(), op.getToOwnerId(), op.getMoneyType(), op.getAmount());

        var today = ledger.today();
        if (op.getFromOwnerType() == OwnerType.KASSA)
            dayService.addChiqim(op.getFromOwnerId(), today, op.getMoneyType(), op.getAmount());
        if (op.getToOwnerType() == OwnerType.KASSA)
            dayService.addKirim(op.getToOwnerId(), today, op.getMoneyType(), op.getAmount());

        if (op.getTransferKind() == TransferKind.QARZ_BERISH) {
            Debt d = debtRepo.save(Debt.builder()
                    .debtorType(op.getToOwnerType()).debtorId(op.getToOwnerId())
                    .creditorType(op.getFromOwnerType()).creditorId(op.getFromOwnerId())
                    .moneyType(op.getMoneyType()).amount(op.getAmount())
                    .reason(op.getComment())
                    .build());
            op.setDebtId(d.getId());
        } else if (op.getTransferKind() == TransferKind.QARZ_QAYTARISH && op.getDebtId() != null) {
            Debt d = debtRepo.findById(op.getDebtId())
                    .orElseThrow(() -> new BusinessException("Qarz topilmadi"));
            d.setRepaid(d.getRepaid() + op.getAmount());
            if (d.getRepaid() >= d.getAmount()) {
                d.setStatus(DebtStatus.YOPILGAN);
                d.setClosedAt(Instant.now());
            }
            debtRepo.save(d);
        }

        op.setStatus(OpStatus.TASDIQLANGAN);
        op.setDecidedBy(by.getId());
        op.setDecidedAt(Instant.now());
        audit.log(by.getId(), "OTKAZMA_QABUL", "operation", op.getId(), String.valueOf(op.getAmount()));
        return opRepo.save(op);
    }

    /** Qabul qiluvchi rad etdi — rezerv bekor, pul yuboruvchida qoladi. */
    @Transactional
    public Operation reject(Long opId, AppUser by) {
        Operation op = opRepo.findById(opId)
                .orElseThrow(() -> new BusinessException("O'tkazma topilmadi"));
        if (op.getStatus() != OpStatus.YOLDA)
            throw new BusinessException("Bu o'tkazma allaqachon yakunlangan");

        ledger.unreserve(op.getFromOwnerType(), op.getFromOwnerId(), op.getMoneyType(), op.getAmount());

        op.setStatus(OpStatus.RAD_ETILGAN);
        op.setDecidedBy(by.getId());
        op.setDecidedAt(Instant.now());
        audit.log(by.getId(), "OTKAZMA_RAD", "operation", op.getId(), String.valueOf(op.getAmount()));
        return opRepo.save(op);
    }
}
