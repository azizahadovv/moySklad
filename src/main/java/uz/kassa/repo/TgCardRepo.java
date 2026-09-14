package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.TgCard;
import java.util.List;
import java.util.Optional;

public interface TgCardRepo extends JpaRepository<TgCard, Long> {
    Optional<TgCard> findBySourceBotAndMask(String sourceBot, String mask);
    List<TgCard> findAllByOrderByNameAscMaskAsc();
}
