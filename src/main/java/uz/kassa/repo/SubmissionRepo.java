package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.Submission;
import uz.kassa.domain.SubmissionStatus;
import java.util.List;

public interface SubmissionRepo extends JpaRepository<Submission, Long> {
    List<Submission> findByStatusOrderByIdAsc(SubmissionStatus status);
    List<Submission> findByKassaIdAndStatusOrderByIdAsc(Long kassaId, SubmissionStatus status);
}
