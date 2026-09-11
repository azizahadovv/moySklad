package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.OmborQoralama;
import java.util.Collection;
import java.util.List;

public interface OmborQoralamaRepo extends JpaRepository<OmborQoralama, Long> {
    List<OmborQoralama> findByStatusInOrderByUpdatedAtDesc(Collection<String> statuses);
    List<OmborQoralama> findByStatusAndKassaId(String status, Long kassaId);
    List<OmborQoralama> findByStatus(String status);
    long countByStatusIn(Collection<String> statuses);
    List<OmborQoralama> findTop30ByOrderByUpdatedAtDesc();
}
