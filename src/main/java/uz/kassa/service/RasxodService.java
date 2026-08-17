package uz.kassa.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.kassa.domain.*;
import uz.kassa.repo.OperationRepo;

import java.time.Instant;

/** Rasxod jarayoni (TZ 7.3 va 7.7). */
@Service
@RequiredArgsConstructor
public class RasxodService {

    private final LedgerService ledger;
    private final OperationRepo opRepo;
    private final DayService dayService;
    private final AuditService audit;

    /** Kassir so'rovi: summa REZERVGA olinadi, buxgalter tasdig'i kutiladi. */
    @Transactional
    public Operation createRequest(AppUser kassir, MoneyType mt, long amount, Long categoryId, String comment) {
        if (mt == MoneyType.TERMINAL)
            throw new BusinessException("Terminal pulidan rasxod qilib bo'lmaydi — u firma hisobida");
        if (amount <= 0) throw new BusinessException("Summa noldan katta bo'lishi kerak");
        if (kassir.getKassaId() == null) throw new BusinessException("Sizga kassa biriktirilmagan");

        ledger.reserve(OwnerType.KASSA, kassir.getKassaId(), mt, amount);

        Operation op = opRepo.save(Operation.builder()
                .type(OpType.RASXOD).moneyType(mt).amount(amount)
                .fromOwnerType(OwnerType.KASSA).fromOwnerId(kassir.getKassaId())
                .status(OpStatus.KUTILMOQDA)
                .categoryId(categoryId).comment(comment)
                .opDate(ledger.today()).createdBy(kassir.getId())
                .build());
        audit.log(kassir.getId(), "RASXOD_SOROV", "operation", op.getId(), mt + " " + amount);
        return op;
    }

    @Transactional
    public Operation approve(Long opId, AppUser by) {
        Operation op = opRepo.findById(opId)
                .orElseThrow(() -> new BusinessException("So'rov topilmadi"));
        if (op.getStatus() != OpStatus.KUTILMOQDA)
            throw new BusinessException("Bu so'rov allaqachon ko'rib chiqilgan");

        ledger.commitReserved(op.getFromOwnerType(), op.getFromOwnerId(), op.getMoneyType(), op.getAmount());
        dayService.addRasxod(op.getFromOwnerId(), ledger.today(), op.getMoneyType(), op.getAmount());

        op.setStatus(OpStatus.TASDIQLANGAN);
        op.setDecidedBy(by.getId());
        op.setDecidedAt(Instant.now());
        audit.log(by.getId(), "RASXOD_TASDIQ", "operation", op.getId(), String.valueOf(op.getAmount()));
        return opRepo.save(op);
    }

    @Transactional
    public Operation reject(Long opId, AppUser by, String reason) {
        Operation op = opRepo.findById(opId)
                .orElseThrow(() -> new BusinessException("So'rov topilmadi"));
        if (op.getStatus() != OpStatus.KUTILMOQDA)
            throw new BusinessException("Bu so'rov allaqachon ko'rib chiqilgan");

        ledger.unreserve(op.getFromOwnerType(), op.getFromOwnerId(), op.getMoneyType(), op.getAmount());

        op.setStatus(OpStatus.RAD_ETILGAN);
        op.setRejectReason(reason);
        op.setDecidedBy(by.getId());
        op.setDecidedAt(Instant.now());
        audit.log(by.getId(), "RASXOD_RAD", "operation", op.getId(), reason);
        return opRepo.save(op);
    }

    /** Buxgalterning O'Z rasxodi — dialogda tasdiqlangach darhol bajariladi (TZ 7.7). */
    @Transactional
    public Operation direct(AppUser bux, MoneyType mt, long amount, Long categoryId, String comment) {
        if (mt == MoneyType.TERMINAL)
            throw new BusinessException("Terminal pulidan rasxod qilib bo'lmaydi");
        if (amount <= 0) throw new BusinessException("Summa noldan katta bo'lishi kerak");

        ledger.debit(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, mt, amount);

        Operation op = opRepo.save(Operation.builder()
                .type(OpType.RASXOD).moneyType(mt).amount(amount)
                .fromOwnerType(OwnerType.BUXGALTERIYA).fromOwnerId(LedgerService.BUX_ID)
                .status(OpStatus.TASDIQLANGAN)
                .categoryId(categoryId).comment(comment)
                .opDate(ledger.today())
                .createdBy(bux.getId()).decidedBy(bux.getId()).decidedAt(Instant.now())
                .build());
        audit.log(bux.getId(), "BUX_RASXOD", "operation", op.getId(), mt + " " + amount);
        return op;
    }
}
