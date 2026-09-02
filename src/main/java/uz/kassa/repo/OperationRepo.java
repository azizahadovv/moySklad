package uz.kassa.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.kassa.domain.*;
import java.util.List;
import java.util.Optional;

public interface OperationRepo extends JpaRepository<Operation, Long> {

    /** Kunlik hisobot: kassadan buxgalteriyaga TOPSHIRILGAN naqd (sana bo'yicha). */
    @Query("""
        select coalesce(sum(o.amount), 0) from Operation o
        where o.type = uz.kassa.domain.OpType.TOPSHIRIQ
          and o.moneyType = uz.kassa.domain.MoneyType.NAQD
          and o.status = uz.kassa.domain.OpStatus.TASDIQLANGAN
          and o.fromOwnerType = uz.kassa.domain.OwnerType.KASSA and o.fromOwnerId = :kassaId
          and o.opDate = :date
        """)
    long sumTopshiriqNaqd(@Param("kassaId") Long kassaId, @Param("date") java.time.LocalDate date);

    /** Kunlik hisobot: otdelga bog'langan CLICK hisoblariga shu kuni tushgan Klik kirimlar
     *  (PRIXOD) minus vozvratlar — kun yozuvi (days) ularni o'z ichiga olmaydi, chunki
     *  pul kassaga emas, Click hisobiga yoziladi. */
    @Query("""
        select coalesce(sum(case when o.type = uz.kassa.domain.OpType.PRIXOD then o.amount else -o.amount end), 0)
        from Operation o, ClickAccount c
        where c.kassaId = :kassaId and c.active = true
          and o.moneyType = uz.kassa.domain.MoneyType.KLIK
          and o.status = uz.kassa.domain.OpStatus.TASDIQLANGAN
          and o.opDate = :date
          and ((o.type = uz.kassa.domain.OpType.PRIXOD and o.toOwnerType = uz.kassa.domain.OwnerType.CLICK and o.toOwnerId = c.id)
            or (o.type = uz.kassa.domain.OpType.VOZVRAT and o.fromOwnerType = uz.kassa.domain.OwnerType.CLICK and o.fromOwnerId = c.id))
        """)
    long sumClickSalesNet(@Param("kassaId") Long kassaId, @Param("date") java.time.LocalDate date);

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

    /**
     * Sanadan KEYINGI, balansga ta'sir qilgan (TASDIQLANGAN) operatsiyalar —
     * «o'sha kun oxiridagi balans»ni hisoblash uchun (korrektirovka).
     */
    @Query("""
        select o from Operation o
        where o.opDate > :after and o.moneyType = :mt
          and o.status = uz.kassa.domain.OpStatus.TASDIQLANGAN
          and ((o.fromOwnerType = :ot and o.fromOwnerId = :oid)
            or (o.toOwnerType = :ot and o.toOwnerId = :oid))
        """)
    List<Operation> balanceOpsAfter(@Param("ot") OwnerType ot, @Param("oid") Long oid,
                                    @Param("mt") MoneyType mt,
                                    @Param("after") java.time.LocalDate after);

    /**
     * Egaga tegishli MoySklad'dan sinxronlangan (moysklad_id bor) TASDIQLANGAN
     * operatsiyalarning ishorali yig'indisi (kirim +, chiqim -). Click auditi shu
     * bilan solishtiradi — qo'lda kiritilgan korrektirovka/boshlang'ich qoldiqlar
     * hisobga OLINMAYDI, ya'ni audit ularni buzmaydi.
     */
    @Query("""
        select coalesce(sum(case when o.toOwnerType = :ot and o.toOwnerId = :oid then o.amount else 0 end), 0)
             - coalesce(sum(case when o.fromOwnerType = :ot and o.fromOwnerId = :oid then o.amount else 0 end), 0)
        from Operation o
        where o.moyskladId is not null and o.moneyType = :mt
          and o.status = uz.kassa.domain.OpStatus.TASDIQLANGAN
          and ((o.fromOwnerType = :ot and o.fromOwnerId = :oid)
            or (o.toOwnerType = :ot and o.toOwnerId = :oid))
        """)
    long syncNet(@Param("ot") OwnerType ot, @Param("oid") Long oid, @Param("mt") MoneyType mt);

    /** Egaga yuborilgan, qabul kutayotgan o'tkazmalar. */
    @Query("""
        select o from Operation o
        where o.type = uz.kassa.domain.OpType.OTKAZMA
          and o.status = uz.kassa.domain.OpStatus.YOLDA
          and o.toOwnerType = :ot and o.toOwnerId = :oid
        order by o.id asc
        """)
    List<Operation> incomingTransfers(@Param("ot") OwnerType ot, @Param("oid") Long oid);

    /** Egaga tegishli sinxron (moysklad_id bor) TASDIQLANGAN operatsiyalar —
     *  Click auditi «aynan qaysi hujjat farq beryapti»ni ko'rsatishi uchun. */
    @Query("""
        select o from Operation o
        where o.moyskladId is not null and o.moneyType = :mt
          and o.status = uz.kassa.domain.OpStatus.TASDIQLANGAN
          and ((o.fromOwnerType = :ot and o.fromOwnerId = :oid)
            or (o.toOwnerType = :ot and o.toOwnerId = :oid))
        """)
    List<Operation> syncOpsOf(@Param("ot") OwnerType ot, @Param("oid") Long oid,
                              @Param("mt") MoneyType mt);

    /** Yaxlitlik tekshiruvi: TASDIQLANGAN kirimlar (to-tomon) egalar kesimida —
     *  butun jadvalni xotiraga yuklamasdan (SQL GROUP BY). */
    @Query("""
        select o.toOwnerType, o.toOwnerId, o.moneyType, sum(o.amount)
        from Operation o
        where o.status = uz.kassa.domain.OpStatus.TASDIQLANGAN
          and o.moneyType <> uz.kassa.domain.MoneyType.TERMINAL
          and o.toOwnerType is not null
        group by o.toOwnerType, o.toOwnerId, o.moneyType
        """)
    List<Object[]> confirmedInSums();

    /** Yaxlitlik tekshiruvi: TASDIQLANGAN chiqimlar (from-tomon) egalar kesimida. */
    @Query("""
        select o.fromOwnerType, o.fromOwnerId, o.moneyType, sum(o.amount)
        from Operation o
        where o.status = uz.kassa.domain.OpStatus.TASDIQLANGAN
          and o.moneyType <> uz.kassa.domain.MoneyType.TERMINAL
          and o.fromOwnerType is not null
        group by o.fromOwnerType, o.fromOwnerId, o.moneyType
        """)
    List<Object[]> confirmedOutSums();
}
