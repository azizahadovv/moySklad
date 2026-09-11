package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.OmborYetkazuvchi;
import java.util.List;
import java.util.Optional;

public interface OmborYetkazuvchiRepo extends JpaRepository<OmborYetkazuvchi, String> {
    List<OmborYetkazuvchi> findByActiveTrueOrderByNameAsc();
    List<OmborYetkazuvchi> findAllByOrderByNameAsc();
    Optional<OmborYetkazuvchi> findFirstByNameIgnoreCase(String name);
}
