package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.OmborSorov;
import java.util.Collection;
import java.util.List;

public interface OmborSorovRepo extends JpaRepository<OmborSorov, Long> {
    List<OmborSorov> findByStatusInOrderByCreatedAtDesc(Collection<String> statuses);
    List<OmborSorov> findByStatusOrderByCreatedAtAsc(String status);
    List<OmborSorov> findByByUserIdOrderByCreatedAtDesc(Long userId);
    List<OmborSorov> findByKassaIdOrderByCreatedAtDesc(Long kassaId);
    long countByStatus(String status);
}
