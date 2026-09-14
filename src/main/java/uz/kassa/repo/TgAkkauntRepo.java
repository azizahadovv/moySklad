package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.TgAkkaunt;
import java.util.List;

public interface TgAkkauntRepo extends JpaRepository<TgAkkaunt, String> {
    List<TgAkkaunt> findByActiveTrueOrderByPhoneAsc();
    long countByActiveTrue();
}
