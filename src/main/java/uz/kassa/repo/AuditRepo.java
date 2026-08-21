package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.AuditLog;

public interface AuditRepo extends JpaRepository<AuditLog, Long> {

    java.util.List<AuditLog> findTop15ByOrderByIdDesc();

    java.util.List<AuditLog> findTop15ByUserIdOrderByIdDesc(Long userId);

    java.util.List<AuditLog> findTop5000ByOrderByIdDesc();

    java.util.List<AuditLog> findTop5000ByUserIdOrderByIdDesc(Long userId);
}
