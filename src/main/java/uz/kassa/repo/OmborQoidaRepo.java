package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.OmborQoida;
import java.util.List;

public interface OmborQoidaRepo extends JpaRepository<OmborQoida, String> {
    List<OmborQoida> findAllByOrderBySortAscCodeAsc();
    List<OmborQoida> findByEnabledTrueOrderBySortAscCodeAsc();
}
