package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.Debt;
import uz.kassa.domain.DebtStatus;
import uz.kassa.domain.OwnerType;
import java.util.List;

public interface DebtRepo extends JpaRepository<Debt, Long> {
    List<Debt> findByStatusOrderByIdAsc(DebtStatus status);
    List<Debt> findByDebtorTypeAndDebtorIdAndStatus(OwnerType t, Long id, DebtStatus status);
    List<Debt> findByCreditorTypeAndCreditorIdAndStatus(OwnerType t, Long id, DebtStatus status);
}
