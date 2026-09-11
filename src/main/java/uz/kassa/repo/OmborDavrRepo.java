package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.OmborDavr;
import java.time.LocalDate;
import java.util.List;

public interface OmborDavrRepo extends JpaRepository<OmborDavr, Long> {
    List<OmborDavr> findAllByOrderByFromDateDesc();
    List<OmborDavr> findByKindAndToDateBefore(String kind, LocalDate before);
    List<OmborDavr> findByKindInAndFromDateLessThanEqualAndToDateGreaterThanEqual(List<String> kinds, LocalDate d1, LocalDate d2);
    boolean existsByKindAndProductMsIdAndFromDate(String kind, String productMsId, LocalDate fromDate);
    boolean existsByKindAndFolderNameAndFromDate(String kind, String folderName, LocalDate fromDate);
}
