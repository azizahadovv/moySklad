package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.AgentCheck;
import java.util.List;
import java.util.Optional;

public interface AgentCheckRepo extends JpaRepository<AgentCheck, Long> {
    Optional<AgentCheck> findByAgentMsId(String agentMsId);
    List<AgentCheck> findByStatusOrderByIdDesc(AgentCheck.Status status);
    List<AgentCheck> findByStatusAndCreatorUserIdOrderByIdDesc(AgentCheck.Status status, Long creatorUserId);
    List<AgentCheck> findByStatusAndKassaIdOrderByIdDesc(AgentCheck.Status status, Long kassaId);
    long countByStatus(AgentCheck.Status status);
}
