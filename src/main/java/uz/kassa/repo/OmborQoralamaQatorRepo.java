package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.OmborQoralamaQator;
import java.util.List;

public interface OmborQoralamaQatorRepo extends JpaRepository<OmborQoralamaQator, Long> {
    List<OmborQoralamaQator> findByQoralamaIdOrderByIdAsc(Long qoralamaId);
    void deleteByQoralamaId(Long qoralamaId);
}
