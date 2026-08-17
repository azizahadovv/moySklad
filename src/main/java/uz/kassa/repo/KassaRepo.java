package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.Kassa;
import java.util.List;
import java.util.Optional;

public interface KassaRepo extends JpaRepository<Kassa, Long> {
    List<Kassa> findByActiveTrueOrderByIdAsc();
    Optional<Kassa> findByMoyskladStoreId(String storeId);
}
