package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.OmborStore;
import java.util.List;
import java.util.Optional;

public interface OmborStoreRepo extends JpaRepository<OmborStore, String> {
    List<OmborStore> findByArchivedFalseOrderByNameAsc();
    List<OmborStore> findAllByOrderByNameAsc();
    Optional<OmborStore> findFirstByKassaId(Long kassaId);
}
