package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import uz.kassa.domain.OmborSanoq;
import java.time.LocalDate;
import java.util.List;

public interface OmborSanoqRepo extends JpaRepository<OmborSanoq, Long> {
    List<OmborSanoq> findByKassaIdAndPlanDateOrderByAbcAscIdAsc(Long kassaId, LocalDate planDate);
    List<OmborSanoq> findByKassaIdAndStatusNotAndPlanDateLessThanEqualOrderByPlanDateAscIdAsc(Long kassaId, String status, LocalDate d);
    List<OmborSanoq> findByStatusNotAndPlanDateLessThanEqual(String status, LocalDate d);
    long countByKassaIdAndStatusNotAndPlanDateLessThanEqual(Long kassaId, String status, LocalDate d);
    long countByStatusNotAndPlanDateLessThanEqual(String status, LocalDate d);
    long countByKassaIdAndPlanDate(Long kassaId, LocalDate d);
    List<OmborSanoq> findByStatusAndAtAfter(String status, java.time.Instant after);
    /** Do'kon bo'yicha oxirgi sanalgan sana (tovar → sana). */
    @Query("select s.productMsId, max(s.planDate) from OmborSanoq s where s.kassaId = :kassaId and s.status = 'TASDIQ' group by s.productMsId")
    List<Object[]> lastCounted(Long kassaId);
    long countByStatusAndPlanDateGreaterThanEqual(String status, LocalDate d);
}
