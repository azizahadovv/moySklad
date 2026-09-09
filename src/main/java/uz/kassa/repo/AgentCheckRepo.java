package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import uz.kassa.domain.AgentCheck;
import java.util.List;
import java.util.Optional;

public interface AgentCheckRepo extends JpaRepository<AgentCheck, Long> {
    Optional<AgentCheck> findByAgentMsId(String agentMsId);
    List<AgentCheck> findByStatusOrderByIdDesc(AgentCheck.Status status);
    List<AgentCheck> findByStatusAndCreatorUserIdOrderByIdDesc(AgentCheck.Status status, Long creatorUserId);
    List<AgentCheck> findByStatusAndKassaIdOrderByIdDesc(AgentCheck.Status status, Long kassaId);
    long countByStatus(AgentCheck.Status status);

    /* xodim ↔ MoySklad bog'lanishi o'zgarganda: eski tekshiruvlar yangi foydalanuvchiga ko'chadi */
    @Modifying(clearAutomatically = true) @Transactional
    @Query("update AgentCheck a set a.creatorUserId = :userId where lower(a.createdUid) = lower(:uid) and (a.creatorUserId is null or a.creatorUserId <> :userId)")
    int remapCreatorByUid(String uid, Long userId);

    @Modifying(clearAutomatically = true) @Transactional
    @Query("update AgentCheck a set a.creatorUserId = null where a.creatorUserId = :userId")
    int clearCreator(Long userId);
}
