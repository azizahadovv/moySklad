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
    private final uz.kassa.repo.DayRepo dayRepo;
    private final uz.kassa.repo.SubmissionRepo subRepo;

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

    /**
     * TASDIQLANGAN rasxodni bekor qilish (SuperAdmin): yechilgan summa balansga
     * QAYTARILADI, kun yozuvi tuzatiladi, status BEKOR bo'ladi.
     * Faqat bot orqali qilingan rasxodlar — MoySklad rasxodi MoySkladda o'zgartiriladi.
     */
    @Transactional
    public Operation cancelApproved(Long opId, AppUser by) {
        Operation op = opRepo.findById(opId)
                .orElseThrow(() -> new BusinessException("Rasxod topilmadi"));
        if (op.getType() != OpType.RASXOD || op.getStatus() != OpStatus.TASDIQLANGAN)
            throw new BusinessException("Faqat tasdiqlangan rasxodni bekor qilish mumkin");
        if (op.getMoyskladId() != null)
            throw new BusinessException("Bu MoySklad rasxodi — MoySkladning o'zida o'zgartiriladi");

        ledger.credit(op.getFromOwnerType(), op.getFromOwnerId(), op.getMoneyType(), op.getAmount());
        if (op.getFromOwnerType() == OwnerType.KASSA)
            dayService.addRasxod(op.getFromOwnerId(), op.getOpDate(), op.getMoneyType(), -op.getAmount());

        op.setStatus(OpStatus.BEKOR);
        op.setRejectReason("Bekor qildi: " + by.getFullName());
        op.setDecidedBy(by.getId());
        op.setDecidedAt(Instant.now());
        audit.log(by.getId(), "RASXOD_BEKOR", "operation", op.getId(),
                op.getMoneyType() + " " + op.getAmount() + " qaytarildi");
        return opRepo.save(op);
    }

    /**
     * TASDIQLANGAN rasxod summasini o'zgartirish (SuperAdmin):
     * farq (delta) balans va kun yozuviga qo'llanadi.
     */
    @Transactional
    public Operation editApprovedAmount(Long opId, long newAmount, AppUser by) {
        Operation op = opRepo.findById(opId)
                .orElseThrow(() -> new BusinessException("Rasxod topilmadi"));
        if (op.getType() != OpType.RASXOD || op.getStatus() != OpStatus.TASDIQLANGAN)
            throw new BusinessException("Faqat tasdiqlangan rasxodni tahrirlash mumkin");
        if (op.getMoyskladId() != null)
            throw new BusinessException("Bu MoySklad rasxodi — MoySkladning o'zida o'zgartiriladi");
        if (newAmount <= 0)
            throw new BusinessException("Summa noldan katta bo'lishi kerak");
        long delta = newAmount - op.getAmount();
        if (delta == 0) throw new BusinessException("Summa o'zgarmadi");

        // delta > 0: qo'shimcha yechiladi; delta < 0: ortiqcha qaytariladi
        ledger.credit(op.getFromOwnerType(), op.getFromOwnerId(), op.getMoneyType(), -delta);
        if (op.getFromOwnerType() == OwnerType.KASSA)
            dayService.addRasxod(op.getFromOwnerId(), op.getOpDate(), op.getMoneyType(), delta);

        long old = op.getAmount();
        op.setAmount(newAmount);
        op.setDecidedBy(by.getId());
        op.setDecidedAt(Instant.now());
        audit.log(by.getId(), "RASXOD_EDIT", "operation", op.getId(), old + " -> " + newAmount);
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

    /**
     * Buxgalter/Admin KASSA NOMIDAN rasxod kiritadi (hisobot qabulida yoki kassa
     * kartasidan): darhol TASDIQLANGAN, tanlangan sananing kun yozuviga tushadi,
     * kassa balansidan (naqd yoki kassaning o'z KLIK hisobidan) ayriladi —
     * pul allaqachon sarflangan fakt, shuning uchun balans manfiyga tushishi mumkin.
     * Agar shu kun KUTILAYOTGAN hisobot ichida bo'lsa — hisobot summasi va rezerv
     * mos ravishda kamaytiriladi (kun qoldig'i o'zgargani uchun).
     */
    @Transactional
    public Operation directForKassa(AppUser by, Long kassaId, MoneyType mt, long amount,
                                    Long categoryId, String comment, java.time.LocalDate date) {
        if (mt == MoneyType.TERMINAL)
            throw new BusinessException("Terminal pulidan rasxod qilib bo'lmaydi");
        if (amount <= 0) throw new BusinessException("Summa noldan katta bo'lishi kerak");

        // Rezervsiz, tekshiruvsiz ayirish — fakt qayd etiladi (manfiy bo'lishi mumkin)
        ledger.credit(OwnerType.KASSA, kassaId, mt, -amount);
        dayService.addRasxod(kassaId, date, mt, amount);

        // Kun kutilayotgan hisobotda bo'lsa — hisobot summasi va rezervni moslashtirish
        DayRecord day = dayRepo.findByKassaIdAndDate(kassaId, date).orElse(null);
        if (day != null && day.getStatus() == DayStatus.TOPSHIRILGAN) {
            for (Submission sub : subRepo.findByStatusOrderByIdAsc(SubmissionStatus.KUTILMOQDA)) {
                if (!sub.getKassaId().equals(kassaId) || !sub.getDayIds().contains(day.getId())) continue;
                long cut = Math.min(amount, mt == MoneyType.NAQD ? sub.getNaqd() : sub.getKlik());
                if (cut > 0) {
                    if (mt == MoneyType.NAQD) sub.setNaqd(sub.getNaqd() - cut);
                    else sub.setKlik(sub.getKlik() - cut);
                    ledger.unreserve(OwnerType.KASSA, kassaId, mt, cut);
                    subRepo.save(sub);
                }
                break;
            }
        }

        Operation op = opRepo.save(Operation.builder()
                .type(OpType.RASXOD).moneyType(mt).amount(amount)
                .fromOwnerType(OwnerType.KASSA).fromOwnerId(kassaId)
                .status(OpStatus.TASDIQLANGAN)
                .categoryId(categoryId).comment(comment)
                .opDate(date)
                .createdBy(by.getId()).decidedBy(by.getId()).decidedAt(Instant.now())
                .build());
        audit.log(by.getId(), "RASXOD_BUX_KASSA", "operation", op.getId(),
                "kassa=" + kassaId + " " + mt + " " + amount + " sana=" + date);
        return op;
    }
}
