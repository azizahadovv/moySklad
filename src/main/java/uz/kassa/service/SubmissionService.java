package uz.kassa.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.kassa.domain.*;
import uz.kassa.repo.DayRepo;
import uz.kassa.repo.OperationRepo;
import uz.kassa.repo.SubmissionRepo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hisobot topshirish va qabul qilish (TZ 7.5, 7.6).
 * Bir nechta kun bitta hisobotda topshirilishi mumkin; kunlar FIFO — eng
 * eskisidan boshlab yopiladi. Qisman qabulda farq kassada qarzdorlik bo'lib qoladi.
 *
 * KUNLARNI QOPLASH QOIDASI (hamma joyda bir xil):
 *  1) manfiy qoldiqli kunlar (rasxod/storno prixoddan oshgan) avval YUTILADI —
 *     ular boshqa kunlar pulidan qoplangan, alohida «-» bo'lib osilib qolmasin;
 *  2) bevosita qabulda TANLANGAN SANA birinchi qoplanadi — buxgalter «05.09 uchun
 *     oldim» desa, 05.09 kuni yopilsin (avval eng eski kun yopilib, 05.09 to'liq
 *     qolib ketardi — «topshirilgan kun yana ko'rinadi, qayta qabul qilsa bo'ladi»);
 *  3) qolgani FIFO — eng eski kundan.
 * Kun qatorlari QULF ostida o'zgartiriladi (DayRepo.lock*) — sinxron bilan poyga yo'q.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubmissionService {

    private static final List<DayStatus> OPEN = List.of(DayStatus.YOPILGAN, DayStatus.OCHIQ);

    private final LedgerService ledger;
    private final DayRepo dayRepo;
    private final SubmissionRepo subRepo;
    private final OperationRepo opRepo;
    private final AuditService audit;
    private final uz.kassa.repo.AppUserRepo userRepo;

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

        // Qulf tartibi: avval balans, keyin kunlar (sinxron bilan bir xil tartib)
        ledger.lock(OwnerType.KASSA, kassaId, MoneyType.NAQD);
        ledger.lock(OwnerType.KASSA, kassaId, MoneyType.KLIK);
        List<DayRecord> all = dayRepo.lockByKassaIdAndStatusIn(kassaId, List.of(DayStatus.YOPILGAN));
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

        List<DayRecord> days = lockedDaysOf(sub);
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
     * kassa balansidan yechiladi, Buxgalteriyaga kiradi, kunlar qoplanadi
     * (tanlangan sana birinchi, qolgani FIFO).
     * HIMOYA: mavjud (available) qoldiqdan ko'p qabul qilib BO'LMAYDI — balans 0
     * bo'lsa kassada pul yo'q, «qayerdan beradi?»; kutilayotgan hisobot borida ham
     * taqiq — rezervdagi pul ikki marta yechilib ketmasin. TERMINAL — faqat
     * jurnalga yoziladi (terminal puli firma bank hisobida).
     */
    @Transactional
    public Operation directCollect(Long kassaId, MoneyType mt, long amount,
                                   AppUser by, String topshirgan) {
        return directCollect(kassaId, mt, amount, by, topshirgan, ledger.today());
    }

    /** date — pul haqiqatda qaysi kun uchun qabul qilingani (kalendar orqali tanlanadi). */
    @Transactional
    public Operation directCollect(Long kassaId, MoneyType mt, long amount,
                                   AppUser by, String topshirgan, LocalDate date) {
        return directCollect(kassaId, mt, amount, by, topshirgan, date, date);
    }

    /**
     * Davr uchun qabul: [from, to] oralig'idagi kunlar BIRINCHI yopiladi (eng eskisidan),
     * ortgani boshqa kunlardan FIFO. Operatsiya sanasi — davrning oxirgi kuni (to).
     */
    @Transactional
    public Operation directCollect(Long kassaId, MoneyType mt, long amount,
                                   AppUser by, String topshirgan, LocalDate from, LocalDate to) {
        if (amount <= 0) throw new BusinessException("Summa noldan katta bo'lishi kerak");
        if (from.isAfter(to)) { LocalDate x = from; from = to; to = x; }
        final LocalDate date = to;
        final LocalDate pFrom = from;
        // KLIK siyosati: buxgalteriya klik pulini QABUL QILMAYDI — klik har bir
        // kassaning o'z hisobida yig'iladi, hisobot esa «📤 Hisobot topshirish»
        // orqali topshiriladi va qabul qilinadi.
        if (mt == MoneyType.KLIK)
            throw new BusinessException("Klik puli qabul qilinmaydi — u kassaning o'z hisobida "
                    + "yig'iladi. Klik hisoboti kassir yuborgan hisobot orqali yopiladi.");
        // TERMINAL (karta) puli firma bank hisobida — buxgalteriya uni qabul qilmaydi
        // (foydalanuvchi qarori 2026-09-08: faqat NAQD qabul qilinadi).
        if (mt == MoneyType.TERMINAL)
            throw new BusinessException("Terminal puli qabul qilinmaydi — u bank hisobida. "
                    + "Faqat naqd qabul qilinadi.");

        if (mt != MoneyType.TERMINAL) {
            // Kutilayotgan hisobot borida bevosita qabul TAQIQ — o'sha pul allaqachon
            // rezervda, qabul qilinsa bir pul IKKI MARTA yechiladi (minus sababi edi).
            if (!subRepo.findByKassaIdAndStatusOrderByIdAsc(kassaId, SubmissionStatus.KUTILMOQDA).isEmpty())
                throw new BusinessException("Bu kassaning ko'rib chiqilmagan hisoboti bor — "
                        + "avval uni qabul qiling yoki rad eting (🏪 KASSA → 📥 Кутилаётганлар, "
                        + "yoki kassa kartasida 💵 Топширилмаган пул → 📥 Ҳисоботларни кўриш), "
                        + "keyin pul qabul qilinadi (aks holda bir pul ikki marta yechiladi).");
            // debit() mavjud qoldiqni QULF ostida tekshiradi — 0 balansdan qabul o'tmaydi.
            ledger.debit(OwnerType.KASSA, kassaId, mt, amount);
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
            List<DayRecord> days = dayRepo.lockByKassaIdAndStatusIn(kassaId, OPEN);
            Map<Long, Long> before = new HashMap<>();
            for (DayRecord d : days) before.put(d.getId(), d.getCoveredNaqd());
            long rem = absorbNegative(days, mt, amount);
            for (DayRecord d : days)                       // 2) tanlangan sana/davr birinchi (eng eskisidan)
                if (!d.getDate().isBefore(pFrom) && !d.getDate().isAfter(date)) rem = takeFrom(d, mt, rem);
            rem = fifo(days, mt, rem);                     // 3) qolgani eng eski kundan
            days.forEach(SubmissionService::closeIfCovered);
            dayRepo.saveAll(days);
            reportLeftover(by, kassaId, mt, amount, rem, "operation", op.getId());

            // Bevosita qabul ham HISOBOT sifatida yoziladi (avtomatik QABUL holatida) —
            // «Топширилган ҳисоботлар» ro'yxati/Excel'ida, kassir tarixida ko'rinadi;
            // kassir alohida hisobot topshirishi shart emas.
            Submission sub = Submission.builder()
                    .kassaId(kassaId).naqd(amount).klik(0)
                    .acceptedNaqd(amount).acceptedKlik(0L)
                    .status(SubmissionStatus.QABUL)
                    .submittedBy(submitterId(kassaId, topshirgan, by))
                    .decidedBy(by.getId()).decidedAt(Instant.now())
                    .comment(DIRECT_PREFIX + topshirgan + " · "
                            + (pFrom.equals(date) ? date.toString() : pFrom + " — " + date))
                    .build();
            for (DayRecord d : days)
                if (d.getCoveredNaqd() != before.getOrDefault(d.getId(), 0L)) sub.getDayIds().add(d.getId());
            sub = subRepo.save(sub);
            op.setSubmissionId(sub.getId());
            op = opRepo.save(op);
        }

        audit.log(by.getId(), "PUL_QABUL", "operation", op.getId(),
                "kassa=" + kassaId + " " + mt + " " + amount + " topshirdi=" + topshirgan
                        + " sana=" + (pFrom.equals(date) ? date.toString() : pFrom + ".." + date));
        return op;
    }

    /** Bevosita qabuldan avtomatik yaratilgan hisobot izohi shu bilan boshlanadi. */
    public static final String DIRECT_PREFIX = "Бевосита қабул — топширди: ";

    /** Avto-hisobot «kim topshirdi»: kassaning shu ismli faol xodimi, topilmasa qabul qilgan. */
    private Long submitterId(Long kassaId, String topshirgan, AppUser by) {
        if (topshirgan != null)
            for (AppUser x : userRepo.findByKassaIdAndActiveTrue(kassaId))
                if (topshirgan.trim().equalsIgnoreCase(x.getFullName())) return x.getId();
        return by.getId();
    }

    /* ------------------------ ichki ------------------------ */

    private Submission pending(Long subId) {
        Submission sub = subRepo.findById(subId)
                .orElseThrow(() -> new BusinessException("Hisobot topilmadi"));
        if (sub.getStatus() != SubmissionStatus.KUTILMOQDA)
            throw new BusinessException("Bu hisobot allaqachon ko'rib chiqilgan");
        return sub;
    }

    /** Hisobot kunlari — QULF ostida, sana bo'yicha o'sish tartibida. */
    private List<DayRecord> lockedDaysOf(Submission sub) {
        if (sub.getDayIds().isEmpty()) return new ArrayList<>();
        return new ArrayList<>(dayRepo.lockByIds(sub.getDayIds()));
    }

    private Submission decide(Submission sub, AppUser by, long accNaqd, long accKlik, String comment) {
        Long kassaId = sub.getKassaId();

        // Kassa NAQD: rezerv to'liq bo'shatiladi, faqat qabul qilingan qism ayriladi.
        if (sub.getNaqd() > 0)
            ledger.settle(OwnerType.KASSA, kassaId, MoneyType.NAQD, sub.getNaqd(), accNaqd);
        // KLIK siyosati: klik puli BUXGALTERIYAGA O'TMAYDI — har bir kassa o'z klik
        // hisobini o'zi jamlaydi. Hisobot qabulida faqat rezerv bo'shatiladi,
        // kunlar «hisobot topshirilgan» deb qoplanadi, pul kassada qoladi.
        if (sub.getKlik() > 0)
            ledger.unreserve(OwnerType.KASSA, kassaId, MoneyType.KLIK, sub.getKlik());

        // Buxgalteriya: faqat NAQD kiradi + TOPSHIRIQ operatsiyasi.
        if (accNaqd > 0) {
            ledger.credit(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD, accNaqd);
            opRepo.save(topshiriqOp(sub, MoneyType.NAQD, accNaqd, by));
        }

        // Hisobot kunlarini qoplash: manfiylar yutiladi, qolgani FIFO (eng eski kundan).
        List<DayRecord> days = lockedDaysOf(sub);
        long remN = fifo(days, MoneyType.NAQD, absorbNegative(days, MoneyType.NAQD, accNaqd));
        long remK = fifo(days, MoneyType.KLIK, absorbNegative(days, MoneyType.KLIK, accKlik));
        for (DayRecord d : days) {
            boolean fullyCovered = d.remainNaqd() == 0 && d.remainKlik() == 0;
            d.setStatus(fullyCovered ? DayStatus.QABUL_QILINGAN : DayStatus.YOPILGAN);
        }
        dayRepo.saveAll(days);

        // Hisobot topshirilgandan keyin uning kunlari kichrayib qolgan bo'lsa (MoySklad
        // storno/summa o'zgarishi) — ortgan pul kassaning boshqa ochiq kunlariga tushadi,
        // balans va kunlar kesimi ajralib ketmasin.
        if (remN > 0 || remK > 0) {
            Set<Long> own = new java.util.HashSet<>(sub.getDayIds());
            List<DayRecord> others = new ArrayList<>(dayRepo.lockByKassaIdAndStatusIn(kassaId, OPEN));
            others.removeIf(d -> own.contains(d.getId()));
            remN = fifo(others, MoneyType.NAQD, remN);
            remK = fifo(others, MoneyType.KLIK, remK);
            others.forEach(SubmissionService::closeIfCovered);
            dayRepo.saveAll(others);
            reportLeftover(by, kassaId, MoneyType.NAQD, accNaqd, remN, "submission", sub.getId());
            reportLeftover(by, kassaId, MoneyType.KLIK, accKlik, remK, "submission", sub.getId());
        }

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

    /* ------------------------ kunlarni qoplash yordamchilari ------------------------ */

    private static long remain(DayRecord d, MoneyType mt) {
        return mt == MoneyType.NAQD ? d.remainNaqd() : d.remainKlik();
    }

    private static void addCovered(DayRecord d, MoneyType mt, long x) {
        if (mt == MoneyType.NAQD) d.setCoveredNaqd(d.getCoveredNaqd() + x);
        else d.setCoveredKlik(d.getCoveredKlik() + x);
    }

    /**
     * Manfiy qoldiqli kunlar yutiladi: covered += remain (qoldiq 0 bo'ladi), qoplash
     * uchun qolgan summa shu miqdorga OSHADI — bu pul boshqa kunlar tushumidan
     * ketgan, ular endi shuncha ko'proq qoplanadi. Natijada kunlar yig'indisi
     * balans bilan teng qoladi, «-» kun esa abadiy osilib qolmaydi.
     */
    private static long absorbNegative(List<DayRecord> days, MoneyType mt, long rem) {
        for (DayRecord d : days) {
            long r = remain(d, mt);
            if (r < 0) { addCovered(d, mt, r); rem -= r; }
        }
        return rem;
    }

    /** Bitta kunni qoldig'igacha qoplash; qolgan summa qaytadi. */
    private static long takeFrom(DayRecord d, MoneyType mt, long rem) {
        long need = remain(d, mt);
        if (need <= 0 || rem <= 0) return rem;
        long take = Math.min(need, rem);
        addCovered(d, mt, take);
        return rem - take;
    }

    /** FIFO: ro'yxat tartibida (eng eski kundan) qoplash. */
    private static long fifo(List<DayRecord> days, MoneyType mt, long rem) {
        for (DayRecord d : days) {
            if (rem <= 0) break;
            rem = takeFrom(d, mt, rem);
        }
        return rem;
    }

    /** YOPILGAN kun to'liq qoplangan bo'lsa — QABUL_QILINGAN (OCHIQ bugungi kun ochiq qoladi). */
    private static void closeIfCovered(DayRecord d) {
        if (d.getStatus() == DayStatus.YOPILGAN && d.remainNaqd() == 0 && d.remainKlik() == 0)
            d.setStatus(DayStatus.QABUL_QILINGAN);
    }

    /**
     * Balansdan yechilgan summa kunlarga to'liq tushmadi — kunlar kesimi balansdan
     * kam edi (eski nomuvofiqlik). Jimgina yo'qotilmaydi: audit + log; yaxlitlik
     * tekshiruvi (Jobs.ledgerIntegrity → verifyDays) SuperAdmin'ga ko'rsatadi.
     */
    private void reportLeftover(AppUser by, Long kassaId, MoneyType mt, long amount, long rem,
                                String entity, Long entityId) {
        if (rem <= 0) return;
        String msg = "kassa=" + kassaId + " " + mt + " qabul=" + amount
                + " kunlarga tushmagan qoldiq=" + rem + " (kunlar kesimi balansdan kam)";
        log.warn("Kun qoplash qoldig'i: {}", msg);
        audit.log(by.getId(), "QOPLASH_QOLDIQ", entity, entityId, msg);
    }
}
