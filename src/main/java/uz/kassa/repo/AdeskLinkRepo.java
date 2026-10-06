package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import uz.kassa.domain.AdeskLink;
import java.util.List;
import java.util.Optional;

public interface AdeskLinkRepo extends JpaRepository<AdeskLink, Long> {

    Optional<AdeskLink> findByKindAndMsKey(String kind, String msKey);

    List<AdeskLink> findByKind(String kind);

    List<AdeskLink> findByKindAndAdeskId(String kind, Long adeskId);

    long countByKindAndStatus(String kind, String status);

    List<AdeskLink> findByStatusOrderByUpdatedAtDesc(String status);

    @Query("select l.kind, l.status, count(l) from AdeskLink l group by l.kind, l.status")
    List<Object[]> countGrouped();
}
