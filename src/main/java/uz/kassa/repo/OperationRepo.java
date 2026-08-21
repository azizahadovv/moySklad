package uz.kassa.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.kassa.domain.*;
import java.util.List;
import java.util.Optional;

public interface OperationRepo extends JpaRepository<Operation, Long> {

    Optional<Operation> findByMoyskladId(String moyskladId);

    /** MoySklad reconcile: davr ichidagi sinxron yozuvlar (prefiks: ci:/pi:/co:/dc:). */
    List<Operation> findByOpDateBetweenAndMoyskladIdStartingWith(
            java.time.LocalDate from, java.time.LocalDate to, String prefix);

    /** Bekor qilish/tahrirlash uchun: bot orqali tasdiqlangan oxirgi rasxodlar. */
    List<Operation> findTop10ByTypeAndStatusAndMoyskladIdIsNullOrderByIdDesc(
            OpType type, OpStatus status);

    List<Operation> findByStatusAndType(OpStatus status, OpType type);

    /** Egaga tegishli oxirgi operatsiyalar (tarix uchun). */
    @Query("""
        select o from Operation o
        where (o.fromOwnerType = :ot and o.fromOwnerId = :oid)
           or (o.toOwnerType = :ot and o.toOwnerId = :oid)
        order by o.id desc
        """)
    List<Operation> history(@Param("ot") OwnerType ot, @Param("oid") Long oid, Pageable page);

    /** Davr oralig'idagi barcha operatsiyalar (mini app tarixi). */
    @Query("""
        select o from Operation o
        where o.opDate between :from and :to
        order by o.opDate desc, o.id desc
        """)
    List<Operation> byPeriod(@Param("from") java.time.LocalDate from,
                             @Param("to") java.time.LocalDate to);

    /** Egaga yuborilgan, qabul kutayotgan o'tkazmalar. */
    @Query("""
        select o from Operation o
        where o.type = uz.kassa.domain.OpType.OTKAZMA
          and o.status = uz.kassa.domain.OpStatus.YOLDA
          and o.toOwnerType = :ot and o.toOwnerId = :oid
        order by o.id asc
        """)
    List<Operation> incomingTransfers(@Param("ot") OwnerType ot, @Param("oid") Long oid);
}
