package uz.kassa.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.kassa.domain.*;
import uz.kassa.repo.DayRepo;
import uz.kassa.repo.OperationRepo;
import uz.kassa.repo.SubmissionRepo;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Hisobot topshirish va qabul qilish (TZ 7.5, 7.6).
 * Bir nechta kun bitta hisobotda topshirilishi mumkin; kunlar FIFO — eng
 * eskisidan boshlab yopiladi. Qisman qabulda farq kassada qarzdorlik bo'lib qoladi.
 */
@Service
@RequiredArgsConstructor
public class SubmissionService {

    private final LedgerService ledger;
    private final DayRepo dayRepo;
    private final SubmissionRepo subRepo;
    private final OperationRepo opRepo;
    private final AuditService audit;

    /** Topshirilishi mumkin bo'lgan (YOPILGAN) kunlar, eng eskisidan boshlab. */
    public List<DayRecord> submittableDays(Long kassaId) {
        return dayRepo.findByKassaIdAndStatusOrderByDateAsc(kassaId, DayStatus.YOPILGAN);
    }

    /**
     * Hisobot yaratish: eng eski YOPILGAN kunlardan boshlab birinchi firstN tasi olinadi
     * (FIFO buzilmasligi uchun faqat ketma-ket prefiks tanlanadi).
     */
    @Transactional
    public Submission create(AppUser kassir, int firstN) {
        Long kassaId = kassir.getKassaId();
        if (kassaId == null) throw new BusinessException("Sizga kassa biriktirilmagan");

        List<DayRecord> all = submittableDays(kassaId);
        if (all.isEmpty()) throw new BusinessException("Topshiriladigan yopilgan kun yo'q");
        if (firstN <= 0 || firstN > all.size()) firstN = all.size();
        List<DayRecord> days = all.subList(0, firstN);

        long naqd = 0, klik = 0;
        for (DayRecord d : days) {
            naqd += d.remainNaqd();
            klik += d.remainKlik();
        }
        if (naqd < 0 || klik < 0)
            throw new BusinessException("Tanlangan kunlar yig'indisi manfiy chiqdi — "
                    + "ko'proq kun tanlang yoki buxgalter bilan bog'laning");

        if (naqd > 0) ledger.reserve(OwnerType.KASSA, kassaId, MoneyType.NAQD, naqd);
        if (klik > 0) ledger.reserve(OwnerType.KASSA, kassaId, MoneyType.KLIK, klik);

        Submission sub = Submission.builder()
                .kassaId(kassaId).naqd(naqd).klik(klik)
                .status(SubmissionStatus.KUTILMOQDA)
                .submittedBy(kassir.getId())
                .build();
        for (DayRecord d : days) {
            d.setStatus(DayStatus.TOPSHIRILGAN);
            sub.getDayIds().add(d.getId());
        }
        dayRepo.saveAll(days);
        sub = subRepo.save(sub);
        audit.log(kassir.getId(), "HISOBOT_TOPSHIRILDI", "submission", sub.getId(),
                "naqd=" + naqd + " klik=" + klik + " kunlar=" + days.size());
        return sub;
    }

    @Transactional
    public Submission acceptFull(Long subId, AppUser by) {
        Submission sub = pending(subId);
        return decide(sub, by, sub.getNaqd(), sub.getKlik(), null);
    }

    /** Qisman qabul: haqiqatda olingan summalar kiritiladi (TZ 7.6). */
    @Transactional
    public Submission acceptPartial(Long subId, AppUser by, long accNaqd, long accKlik) {
        Submission sub = pending(subId);
        if (accNaqd < 0 || accNaqd > sub.getNaqd())
            throw new BusinessException("Naqd summa 0 dan " + sub.getNaqd() + " gacha bo'lishi kerak");
        if (accKlik < 0 || accKlik > sub.getKlik())
            throw new BusinessException("Click summa 0 dan " + sub.getKlik() + " gacha bo'lishi kerak");
        return decide(sub, by, accNaqd, accKlik, "Qisman qabul");
    }

    @Transactional
    public Submission reject(Long subId, AppUser by, String reason) {
        Submission sub = pending(subId);

        if (sub.getNaqd() > 0) ledger.unreserve(OwnerType.KASSA, sub.getKassaId(), MoneyType.NAQD, sub.getNaqd());
        if (sub.getKlik() > 0) ledger.unreserve(OwnerType.KASSA, sub.getKassaId(), MoneyType.KLIK, sub.getKlik());

        List<DayRecord> days = daysOf(sub);
        for (DayRecord d : days) d.setStatus(DayStatus.YOPILGAN);
        dayRepo.saveAll(days);

        sub.setStatus(SubmissionStatus.RAD);
        sub.setComment(reason);
        sub.setDecidedBy(by.getId());
        sub.setDecidedAt(Instant.now());
        audit.log(by.getId(), "HISOBOT_RAD", "submission", sub.getId(), reason);
        return subRepo.save(sub);
    }

    /**
     * Buxgalter/Admin pulni kassadan BEVOSITA qabul qiladi (hisobot kutmasdan):
     * kassa balansidan yechiladi (manfiyga tushishi mumkin — pul qo'lda olingan fakt),
     * Buxgalteriyaga kiradi, kunlar FIFO qoplanadi. TERMINAL — faqat jurnalga yoziladi
     * (terminal puli firma bank hisobida, kassir balansida yuritilmaydi).
     */
    @Transactional
    public Operation directCollect(Long kassaId, MoneyType mt, long amount,
                                   AppUser by, String topshirgan) {
        return directCollect(kassaId, mt, amount, by, topshirgan, ledger.today());
    }

    /** date — pul haqiqatda qaysi kun uchun qabul qilingani (kalendar orqali tanlanadi). */
    @Transactional
    public Operation directCollect(Long kassaId, MoneyType mt, long amount,
                                   AppUser by, String topshirgan, java.time.LocalDate date) {
        if (amount <= 0) throw new BusinessException("Summa noldan katta bo'lishi kerak");

        if (mt != MoneyType.TERMINAL) {
            ledger.settle(OwnerType.KASSA, kassaId, mt, 0, amount);
            ledger.credit(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, mt, amount);
        }

        Operation op = opRepo.save(Operation.builder()
                .type(OpType.TOPSHIRIQ).moneyType(mt).amount(amount)
                .fromOwnerType(OwnerType.KASSA).fromOwnerId(kassaId)
                .toOwnerType(OwnerType.BUXGALTERIYA).toOwnerId(LedgerService.BUX_ID)
                .status(OpStatus.TASDIQLANGAN)
                .comment("Topshirdi: " + topshirgan)
                .opDate(date)
                .createdBy(by.getId()).decidedBy(by.getId()).decidedAt(Instant.now())
                .build());

        if (mt != MoneyType.TERMINAL) {
            long rem = amount;
            List<DayRecord> days = new java.util.ArrayList<>();
            days.addAll(dayRepo.findByKassaIdAndStatusOrderByDateAsc(kassaId, DayStatus.YOPILGAN));
            days.addAll(dayRepo.findByKassaIdAndStatusOrderByDateAsc(kassaId, DayStatus.OCHIQ));
            for (DayRecord d : days) {
                if (rem <= 0) break;
                long need = mt == MoneyType.NAQD ? d.remainNaqd() : d.remainKlik();
                if (need <= 0) continue;
                long take = Math.min(need, rem);
                if (mt == MoneyType.NAQD) d.setCoveredNaqd(d.getCoveredNaqd() + take);
                else d.setCoveredKlik(d.getCoveredKlik() + take);
                rem -= take;
                if (d.getStatus() == DayStatus.YOPILGAN
                        && d.remainNaqd() == 0 && d.remainKlik() == 0)
                    d.setStatus(DayStatus.QABUL_QILINGAN);
            }
            dayRepo.saveAll(days);
        }

        audit.log(by.getId(), "PUL_QABUL", "operation", op.getId(),
                "kassa=" + kassaId + " " + mt + " " + amount + " topshirdi=" + topshirgan);
        return op;
    }

    /* ------------------------ ichki ------------------------ */

    private Submission pending(Long subId) {
        Submission sub = subRepo.findById(subId)
                .orElseThrow(() -> new BusinessException("Hisobot topilmadi"));
        if (sub.getStatus() != SubmissionStatus.KUTILMOQDA)
            throw new BusinessException("Bu hisobot allaqachon ko'rib chiqilgan");
        return sub;
    }

    private List<DayRecord> daysOf(Submission sub) {
        return dayRepo.findAllById(sub.getDayIds()).stream()
                .sorted(Comparator.comparing(DayRecord::getDate))
                .toList();
    }

    private Submission decide(Submission sub, AppUser by, long accNaqd, long accKlik, String comment) {
        Long kassaId = sub.getKassaId();

        // Kassa: rezerv to'liq bo'shatiladi, faqat qabul qilingan qism ayriladi.
        if (sub.getNaqd() > 0)
            ledger.settle(OwnerType.KASSA, kassaId, MoneyType.NAQD, sub.getNaqd(), accNaqd);
        if (sub.getKlik() > 0)
            ledger.settle(OwnerType.KASSA, kassaId, MoneyType.KLIK, sub.getKlik(), accKlik);

        // Buxgalteriya: qabul qilingan pul kiradi + TOPSHIRIQ operatsiyalari.
        if (accNaqd > 0) {
            ledger.credit(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD, accNaqd);
            opRepo.save(topshiriqOp(sub, MoneyType.NAQD, accNaqd, by));
        }
        if (accKlik > 0) {
            ledger.credit(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.KLIK, accKlik);
            opRepo.save(topshiriqOp(sub, MoneyType.KLIK, accKlik, by));
        }

        // Kunlarni FIFO qoplash: eng eski kundan boshlab.
        List<DayRecord> days = daysOf(sub);
        long remN = accNaqd, remK = accKlik;
        for (DayRecord d : days) {
            long needN = d.remainNaqd();
            if (needN <= remN) { d.setCoveredNaqd(d.netNaqd()); remN -= needN; }
            else { d.setCoveredNaqd(d.getCoveredNaqd() + remN); remN = 0; }

            long needK = d.remainKlik();
            if (needK <= remK) { d.setCoveredKlik(d.netKlik()); remK -= needK; }
            else { d.setCoveredKlik(d.getCoveredKlik() + remK); remK = 0; }

            boolean fullyCovered = d.remainNaqd() == 0 && d.remainKlik() == 0;
            d.setStatus(fullyCovered ? DayStatus.QABUL_QILINGAN : DayStatus.YOPILGAN);
        }
        dayRepo.saveAll(days);

        boolean full = accNaqd == sub.getNaqd() && accKlik == sub.getKlik();
        sub.setAcceptedNaqd(accNaqd);
        sub.setAcceptedKlik(accKlik);
        sub.setStatus(full ? SubmissionStatus.QABUL : SubmissionStatus.QISMAN_QABUL);
        sub.setComment(comment);
        sub.setDecidedBy(by.getId());
        sub.setDecidedAt(Instant.now());
        audit.log(by.getId(), full ? "HISOBOT_QABUL" : "HISOBOT_QISMAN",
                "submission", sub.getId(), "naqd=" + accNaqd + " klik=" + accKlik);
        return subRepo.save(sub);
    }

    private Operation topshiriqOp(Submission sub, MoneyType mt, long amount, AppUser by) {
        return Operation.builder()
                .type(OpType.TOPSHIRIQ).moneyType(mt).amount(amount)
                .fromOwnerType(OwnerType.KASSA).fromOwnerId(sub.getKassaId())
                .toOwnerType(OwnerType.BUXGALTERIYA).toOwnerId(LedgerService.BUX_ID)
                .status(OpStatus.TASDIQLANGAN)
                .submissionId(sub.getId())
                .opDate(ledger.today())
                .createdBy(sub.getSubmittedBy())
                .decidedBy(by.getId()).decidedAt(Instant.now())
                .build();
    }
}
