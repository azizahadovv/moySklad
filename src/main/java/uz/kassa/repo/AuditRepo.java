package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.AuditLog;

public interface AuditRepo extends JpaRepository<AuditLog, Long> {
}
