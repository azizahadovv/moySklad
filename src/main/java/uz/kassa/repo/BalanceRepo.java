package uz.kassa.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.kassa.domain.Balance;
import uz.kassa.domain.MoneyType;
import uz.kassa.domain.OwnerType;
import java.util.List;
import java.util.Optional;

public interface BalanceRepo extends JpaRepository<Balance, Balance.Key> {

    /** Poyga holatlarining oldini olish uchun qatorni qulflab olish (TZ 9-bo'lim). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Balance b where b.ownerType = :ot and b.ownerId = :oid and b.moneyType = :mt")
    Optional<Balance> lock(@Param("ot") OwnerType ot, @Param("oid") Long oid, @Param("mt") MoneyType mt);

    List<Balance> findByOwnerTypeAndOwnerId(OwnerType ot, Long oid);
}
