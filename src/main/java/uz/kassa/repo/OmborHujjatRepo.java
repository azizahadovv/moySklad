package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import uz.kassa.domain.OmborHujjat;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OmborHujjatRepo extends JpaRepository<OmborHujjat, Long> {
    Optional<OmborHujjat> findByMsId(String msId);
    List<OmborHujjat> findByTypeInAndApplicableFalseAndDeletedFalse(Collection<String> types);
    List<OmborHujjat> findByTypeAndDeletedFalseAndMomentAfter(String type, LocalDateTime after);
    List<OmborHujjat> findByTypeInAndDeletedFalseAndMomentAfter(Collection<String> types, LocalDateTime after);
    List<OmborHujjat> findByTypeInAndLinksContaining(Collection<String> types, String msId);
    long countByType(String type);
    @Query("select h from OmborHujjat h where h.type = :type and h.deleted = false and h.agentMsId is not null and h.moment >= :after order by h.moment")
    List<OmborHujjat> supplies(String type, LocalDateTime after);
}
