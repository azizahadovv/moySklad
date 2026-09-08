package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.MsAgentIndex;
import java.util.List;

public interface MsAgentRepo extends JpaRepository<MsAgentIndex, String> {
    List<MsAgentIndex> findByPhoneNormAndArchivedFalse(String phoneNorm);
    List<MsAgentIndex> findByNameIgnoreCaseAndArchivedFalse(String name);
}
