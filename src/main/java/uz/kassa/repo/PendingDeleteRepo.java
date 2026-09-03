package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.PendingDelete;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PendingDeleteRepo extends JpaRepository<PendingDelete, Long> {
    Optional<PendingDelete> findByChatIdAndMessageId(long chatId, int messageId);
    List<PendingDelete> findByDeleteAtBefore(Instant t);
}
