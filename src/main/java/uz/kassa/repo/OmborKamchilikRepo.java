package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.OmborKamchilik;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OmborKamchilikRepo extends JpaRepository<OmborKamchilik, Long> {
    List<OmborKamchilik> findByResolvedAtIsNullOrderBySinceDesc();
    List<OmborKamchilik> findByRuleCodeAndResolvedAtIsNull(String ruleCode);
    List<OmborKamchilik> findByResolvedAtIsNullAndNotifiedAtIsNull();
    List<OmborKamchilik> findByResolvedAtIsNullAndNotifiedAtIsNotNull();
    long countByResolvedAtIsNull();
    long countByResolvedAtIsNullAndKassaId(Long kassaId);
    /** Oxirgi «e'tiborsiz» javobi — qayta ochmaslik oynasi uchun. */
    Optional<OmborKamchilik> findFirstByRuleCodeAndSubjectTypeAndSubjectKeyAndAnswerAndResolvedAtAfterOrderByResolvedAtDesc(
            String rule, String type, String key, String answer, Instant after);
    List<OmborKamchilik> findByResolvedAtAfterOrderByResolvedAtDesc(Instant after);
    /** «once»: odam bir marta javob bergan subject qayta ochilmaydi. */
    boolean existsByRuleCodeAndSubjectTypeAndSubjectKeyAndResolvedByIsNotNull(String rule, String type, String key);
    long countByResolvedAtIsNullAndRuleCodeIn(java.util.Collection<String> rules);
    List<OmborKamchilik> findByResolvedAtIsNullAndOwnerUserIdOrderBySinceDesc(Long ownerUserId);
}
