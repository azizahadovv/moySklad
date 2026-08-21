package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.ClickAccount;

import java.util.List;

public interface ClickAccountRepo extends JpaRepository<ClickAccount, Long> {
    List<ClickAccount> findByActiveTrueOrderByIdAsc();
}
