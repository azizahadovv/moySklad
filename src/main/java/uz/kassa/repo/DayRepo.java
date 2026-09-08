package uz.kassa.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.kassa.domain.DayRecord;
import uz.kassa.domain.DayStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DayRepo extends JpaRepository<DayRecord, Long> {
    Optional<DayRecord> findByKassaIdAndDate(Long kassaId, LocalDate date);
    List<DayRecord> findByKassaIdAndStatusOrderByDateAsc(Long kassaId, DayStatus status);
    List<DayRecord> findByKassaIdAndStatusInOrderByDateAsc(Long kassaId, Collection<DayStatus> statuses);
    List<DayRecord> findByStatusAndDateBefore(DayStatus status, LocalDate before);
    /** 🔔 Bildirishnoma shablonlari: davr bo'yicha kun yozuvlari (MoySklad o'qilmasa zaxira). */
    List<DayRecord> findByKassaIdAndDateBetween(Long kassaId, LocalDate from, LocalDate to);

    /* ---------- QULFLI o'qish (SELECT ... FOR UPDATE) ----------
     * Kun yozuvi bir vaqtda ikki tranzaksiyadan (MoySklad sinxron + pul qabul /
     * hisobot qabul) o'zgartirilganda «yo'qolgan yozuv» (lost update) bo'lmasligi
     * uchun: o'zgartiruvchi oqimlar kun qatorini avval qulflab oladi.
     * Balans qatori (BalanceRepo.lock) bilan bir xil printsip. Qulf tartibi:
     * avval balans, keyin kun — deadlock bo'lmasin. */

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DayRecord d where d.kassaId = :kassaId and d.date = :date")
    Optional<DayRecord> lockByKassaIdAndDate(@Param("kassaId") Long kassaId, @Param("date") LocalDate date);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DayRecord d where d.kassaId = :kassaId and d.status in :statuses order by d.date asc")
    List<DayRecord> lockByKassaIdAndStatusIn(@Param("kassaId") Long kassaId,
                                             @Param("statuses") Collection<DayStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DayRecord d where d.id in :ids order by d.date asc")
    List<DayRecord> lockByIds(@Param("ids") Collection<Long> ids);

    /** Qabulni bekor qilish: naqd qoplangan kunlar, eng yangisidan (qoplash teskari yechiladi). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DayRecord d where d.kassaId = :kassaId and d.coveredNaqd <> 0 order by d.date desc")
    List<DayRecord> lockCoveredNaqdByKassa(@Param("kassaId") Long kassaId);

    /** Yaxlitlik: kassaning BARCHA kunlari bo'yicha topshirilmagan naqd yig'indisi
     *  (status'dan qat'i nazar) — kassa NAQD balansi bilan teng bo'lishi kerak. */
    @Query("""
        select coalesce(sum(d.prixodNaqd - d.vozvratNaqd + d.kirimNaqd - d.chiqimNaqd
                            - d.rasxodNaqd - d.coveredNaqd), 0)
        from DayRecord d where d.kassaId = :kassaId
        """)
    long sumRemainNaqd(@Param("kassaId") Long kassaId);
}
