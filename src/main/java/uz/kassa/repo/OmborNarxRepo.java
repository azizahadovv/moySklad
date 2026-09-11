package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import uz.kassa.domain.OmborNarx;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface OmborNarxRepo extends JpaRepository<OmborNarx, Long> {
    Optional<OmborNarx> findFirstByAgentMsIdAndProductMsIdAndAtDateAndSource(String agent, String product, LocalDate d, String source);
    List<OmborNarx> findByProductMsIdOrderByAtDateDesc(String product);
    List<OmborNarx> findByAgentMsIdAndProductMsIdOrderByAtDateDescIdDesc(String agent, String product);
    List<OmborNarx> findByAtDateAfterOrderByAtDateDesc(LocalDate after);
    /** Har (yetkazuvchi, tovar) juftligi — oxirgi sana. */
    @Query("select n.agentMsId, n.productMsId from OmborNarx n group by n.agentMsId, n.productMsId")
    List<Object[]> pairs();
    long countByAgentMsId(String agent);
}
