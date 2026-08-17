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

/** Kunlik yozuvlar (TZ 7.2). Ledger bilan bir tranzaksiyada chaqiriladi. */
@Service
@RequiredArgsConstructor
public class DayService {

    private final DayRepo dayRepo;

    @Transactional
    public DayRecord getOrCreate(Long kassaId, LocalDate date) {
        return dayRepo.findByKassaIdAndDate(kassaId, date)
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
        dayRepo.save(d);
    }

    @Transactional
    public void addVozvrat(Long kassaId, LocalDate date, MoneyType mt, long amount) {
        DayRecord d = getOrCreate(kassaId, date);
        switch (mt) {
            case NAQD -> d.setVozvratNaqd(d.getVozvratNaqd() + amount);
            case KLIK -> d.setVozvratKlik(d.getVozvratKlik() + amount);
            case TERMINAL -> d.setPrixodTerminal(d.getPrixodTerminal() - amount);
        }
        dayRepo.save(d);
    }

    @Transactional
    public void addRasxod(Long kassaId, LocalDate date, MoneyType mt, long amount) {
        DayRecord d = getOrCreate(kassaId, date);
        if (mt == MoneyType.NAQD) d.setRasxodNaqd(d.getRasxodNaqd() + amount);
        else if (mt == MoneyType.KLIK) d.setRasxodKlik(d.getRasxodKlik() + amount);
        dayRepo.save(d);
    }

    @Transactional
    public void addKirim(Long kassaId, LocalDate date, MoneyType mt, long amount) {
        DayRecord d = getOrCreate(kassaId, date);
        if (mt == MoneyType.NAQD) d.setKirimNaqd(d.getKirimNaqd() + amount);
        else if (mt == MoneyType.KLIK) d.setKirimKlik(d.getKirimKlik() + amount);
        dayRepo.save(d);
    }

    @Transactional
    public void addChiqim(Long kassaId, LocalDate date, MoneyType mt, long amount) {
        DayRecord d = getOrCreate(kassaId, date);
        if (mt == MoneyType.NAQD) d.setChiqimNaqd(d.getChiqimNaqd() + amount);
        else if (mt == MoneyType.KLIK) d.setChiqimKlik(d.getChiqimKlik() + amount);
        dayRepo.save(d);
    }

    /**
     * 00:00 job (TZ 7.2): bugundan oldingi OCHIQ kunlarni yopish.
     * Sof hissasi 0 bo'lgan kun avtomatik QABUL_QILINGAN (topshiradigan narsa yo'q),
     * qolganlari YOPILGAN — topshirilishi kutiladi.
     */
    @Transactional
    public List<DayRecord> closeOpenDaysBefore(LocalDate today) {
        List<DayRecord> open = dayRepo.findByStatusAndDateBefore(DayStatus.OCHIQ, today);
        for (DayRecord d : open) {
            if (d.netNaqd() == 0 && d.netKlik() == 0) d.setStatus(DayStatus.QABUL_QILINGAN);
            else d.setStatus(DayStatus.YOPILGAN);
        }
        return dayRepo.saveAll(open);
    }
}
