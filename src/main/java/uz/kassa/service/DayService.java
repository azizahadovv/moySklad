package uz.kassa.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.kassa.domain.DayRecord;
import uz.kassa.domain.DayStatus;
import uz.kassa.domain.MoneyType;
import uz.kassa.repo.DayRepo;

import java.time.LocalDate;
import java.util.List;

/**
 * Kunlik yozuvlar (TZ 7.2). Ledger bilan bir tranzaksiyada chaqiriladi.
 * Har bir o'zgartirish kun qatorini QULFLAB (FOR UPDATE) oladi — sinxron va
 * pul qabul bir vaqtda bitta kunga yozganda biri ikkinchisini o'chirib
 * yubormasin (aynan shu «150 000 ba'zida ko'p, ba'zida kam» sababi edi).
 */
@Service
@RequiredArgsConstructor
public class DayService {

    private final DayRepo dayRepo;

    /** Kun yozuvi — QULF ostida; yo'q bo'lsa yaratiladi. */
    @Transactional
    public DayRecord getOrCreate(Long kassaId, LocalDate date) {
        return dayRepo.lockByKassaIdAndDate(kassaId, date)
                .orElseGet(() -> dayRepo.save(DayRecord.builder().kassaId(kassaId).date(date).build()));
    }

    @Transactional
    public void addPrixod(Long kassaId, LocalDate date, MoneyType mt, long amount) {
        DayRecord d = getOrCreate(kassaId, date);
        switch (mt) {
            case NAQD -> d.setPrixodNaqd(d.getPrixodNaqd() + amount);
            case KLIK -> d.setPrixodKlik(d.getPrixodKlik() + amount);
            case TERMINAL -> d.setPrixodTerminal(d.getPrixodTerminal() + amount);
        }
        save(d);
    }

    @Transactional
    public void addVozvrat(Long kassaId, LocalDate date, MoneyType mt, long amount) {
        DayRecord d = getOrCreate(kassaId, date);
        switch (mt) {
            case NAQD -> d.setVozvratNaqd(d.getVozvratNaqd() + amount);
            case KLIK -> d.setVozvratKlik(d.getVozvratKlik() + amount);
            case TERMINAL -> d.setPrixodTerminal(d.getPrixodTerminal() - amount);
        }
        save(d);
    }

    @Transactional
    public void addRasxod(Long kassaId, LocalDate date, MoneyType mt, long amount) {
        DayRecord d = getOrCreate(kassaId, date);
        if (mt == MoneyType.NAQD) d.setRasxodNaqd(d.getRasxodNaqd() + amount);
        else if (mt == MoneyType.KLIK) d.setRasxodKlik(d.getRasxodKlik() + amount);
        save(d);
    }

    @Transactional
    public void addKirim(Long kassaId, LocalDate date, MoneyType mt, long amount) {
        DayRecord d = getOrCreate(kassaId, date);
        if (mt == MoneyType.NAQD) d.setKirimNaqd(d.getKirimNaqd() + amount);
        else if (mt == MoneyType.KLIK) d.setKirimKlik(d.getKirimKlik() + amount);
        save(d);
    }

    @Transactional
    public void addChiqim(Long kassaId, LocalDate date, MoneyType mt, long amount) {
        DayRecord d = getOrCreate(kassaId, date);
        if (mt == MoneyType.NAQD) d.setChiqimNaqd(d.getChiqimNaqd() + amount);
        else if (mt == MoneyType.KLIK) d.setChiqimKlik(d.getChiqimKlik() + amount);
        save(d);
    }

    /**
     * Saqlashdan oldin: allaqachon QABUL_QILINGAN kunga keyin (MoySklad'dan) yangi
     * hujjat/storno/summa o'zgarishi tushsa, qoldig'i 0 dan farq qilib qoladi —
     * lekin kun «qabul qilingan» bo'lgani uchun hech qayerda ko'rinmas, balans esa
     * o'zgargan bo'lardi (balans va kunlar kesimi ajralib ketardi). Bunday kun
     * YOPILGAN'ga qaytariladi — qoldiq yana ko'rinadi va qabul/hisobot orqali yopiladi.
     */
    private void save(DayRecord d) {
        if (d.getStatus() == DayStatus.QABUL_QILINGAN
                && (d.remainNaqd() != 0 || d.remainKlik() != 0))
            d.setStatus(DayStatus.YOPILGAN);
        dayRepo.save(d);
    }

    /**
     * 00:00 job (TZ 7.2): bugundan oldingi OCHIQ kunlarni yopish.
     * Topshiriladigan QOLDIG'I 0 bo'lgan kun avtomatik QABUL_QILINGAN (topshiradigan
     * narsa yo'q), qolganlari YOPILGAN — topshirilishi kutiladi.
     * (remain bo'yicha: kun ichida bevosita qabul qilingan bo'lsa net≠0, remain=0.)
     */
    @Transactional
    public List<DayRecord> closeOpenDaysBefore(LocalDate today) {
        List<DayRecord> open = dayRepo.findByStatusAndDateBefore(DayStatus.OCHIQ, today);
        for (DayRecord d : open) {
            if (d.remainNaqd() == 0 && d.remainKlik() == 0) d.setStatus(DayStatus.QABUL_QILINGAN);
            else d.setStatus(DayStatus.YOPILGAN);
        }
        return dayRepo.saveAll(open);
    }
}
