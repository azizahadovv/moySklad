package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.KassaHead;
import java.util.List;
import java.util.Optional;

public interface KassaHeadRepo extends JpaRepository<KassaHead, Long> {
    List<KassaHead> findByKassaId(Long kassaId);
    List<KassaHead> findByUserId(Long userId);
    Optional<KassaHead> findByKassaIdAndUserId(Long kassaId, Long userId);
}
