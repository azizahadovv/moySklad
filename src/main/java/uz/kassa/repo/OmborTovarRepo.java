package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import uz.kassa.domain.OmborTovar;
import java.util.List;

public interface OmborTovarRepo extends JpaRepository<OmborTovar, String> {
    long countByArchivedFalse();
    List<OmborTovar> findByArchivedFalse();
    @Query("select t from OmborTovar t where t.archived = false and (t.nameNorm like %:q% or lower(t.article) like %:q% or lower(t.code) like %:q% or t.barcode like %:q%) order by t.name")
    List<OmborTovar> search(String q);
}
