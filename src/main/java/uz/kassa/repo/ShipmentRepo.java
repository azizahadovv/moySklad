package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.Shipment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ShipmentRepo extends JpaRepository<Shipment, Long> {
    Optional<Shipment> findByMsId(String msId);
    List<Shipment> findByControlStatusOrderByDueAtAscMomentAsc(Shipment.Status status);
    List<Shipment> findByControlStatusAndOwnerUserIdOrderByDueAtAscMomentAsc(Shipment.Status status, Long ownerUserId);
    List<Shipment> findByControlStatusAndKassaIdOrderByDueAtAscMomentAsc(Shipment.Status status, Long kassaId);
    List<Shipment> findByControlStatusAndMasulUserIdOrderByDueAtAscMomentAsc(Shipment.Status status, Long masulUserId);
    List<Shipment> findByControlStatusAndCheckAtBefore(Shipment.Status status, Instant before);
    Optional<Shipment> findFirstByReminderId(Long reminderId);
    long countByControlStatus(Shipment.Status status);
    List<Shipment> findByIssuesNotOrderByMomentDesc(String issues);
    List<Shipment> findByIssuesNotAndOwnerUserIdOrderByMomentDesc(String issues, Long ownerUserId);
    List<Shipment> findByIssuesNotAndMasulUserIdOrderByMomentDesc(String issues, Long masulUserId);
    List<Shipment> findByIssuesNotAndKassaIdOrderByMomentDesc(String issues, Long kassaId);
    List<Shipment> findByIssuesNotAndIssuesNotifiedAtIsNull(String issues);
    long countByIssuesNot(String issues);
    List<Shipment> findByControlStatusAndClosedAtAfter(Shipment.Status status, Instant after);
}
