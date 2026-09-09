package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
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
    List<Shipment> findByIssuesNotAndIssuesNotifiedAtIsNotNull(String issues);
    long countByIssuesNot(String issues);
    List<Shipment> findByControlStatusAndClosedAtAfter(Shipment.Status status, Instant after);

    /* xodim ↔ MoySklad bog'lanishi o'zgarganda: eski otgruzkalar yangi foydalanuvchiga ko'chadi */
    @Modifying(clearAutomatically = true) @Transactional
    @Query("update Shipment s set s.ownerUserId = :userId where s.ownerMsId = :msId and (s.ownerUserId is null or s.ownerUserId <> :userId)")
    int remapOwnerByMsId(String msId, Long userId);

    @Modifying(clearAutomatically = true) @Transactional
    @Query("update Shipment s set s.ownerUserId = :userId where lower(s.ownerUid) = lower(:uid) and (s.ownerUserId is null or s.ownerUserId <> :userId)")
    int remapOwnerByUid(String uid, Long userId);

    @Modifying(clearAutomatically = true) @Transactional
    @Query("update Shipment s set s.ownerUserId = null where s.ownerUserId = :userId")
    int clearOwner(Long userId);

    /** Dublikat birlashtirish: egasi from → to. */
    @Modifying(clearAutomatically = true) @Transactional
    @Query("update Shipment s set s.ownerUserId = :to where s.ownerUserId = :from")
    int remapOwnerUser(Long from, Long to);

    @Modifying(clearAutomatically = true) @Transactional
    @Query("update Shipment s set s.masulUserId = :to where s.masulUserId = :from")
    int remapMasulUser(Long from, Long to);
}
