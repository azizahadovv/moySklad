package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.Submission;
import uz.kassa.domain.SubmissionStatus;
import java.util.List;

public interface SubmissionRepo extends JpaRepository<Submission, Long> {
    List<Submission> findByStatusOrderByIdAsc(SubmissionStatus status);
    List<Submission> findByKassaIdAndStatusOrderByIdAsc(Long kassaId, SubmissionStatus status);
    List<Submission> findTop200ByKassaIdOrderByIdDesc(Long kassaId);

    /** Dublikat foydalanuvchi birlashtirish: from → to. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update Submission s set s.submittedBy = :to where s.submittedBy = :from")
    int remapSubmittedBy(@org.springframework.data.repository.query.Param("from") Long from,
                         @org.springframework.data.repository.query.Param("to") Long to);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update Submission s set s.decidedBy = :to where s.decidedBy = :from")
    int remapDecidedBy(@org.springframework.data.repository.query.Param("from") Long from,
                       @org.springframework.data.repository.query.Param("to") Long to);
}
