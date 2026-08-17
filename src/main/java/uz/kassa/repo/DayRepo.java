package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
