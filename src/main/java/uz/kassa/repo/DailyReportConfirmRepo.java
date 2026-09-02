package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.DailyReportConfirm;
import java.time.LocalDate;

public interface DailyReportConfirmRepo extends JpaRepository<DailyReportConfirm, LocalDate> {
}
