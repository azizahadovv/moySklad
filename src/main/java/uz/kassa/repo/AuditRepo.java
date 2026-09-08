package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.AuditLog;

public interface AuditRepo extends JpaRepository<AuditLog, Long> {
    /** 📊 Nazorat statistikasi: foydalanuvchi + amal kesimida soni (davr). */
    @org.springframework.data.jpa.repository.Query("select a.userId, a.action, count(a) from AuditLog a "
            + "where a.action in :actions and a.createdAt >= :from group by a.userId, a.action")
    java.util.List<Object[]> countByUserAndAction(
            @org.springframework.data.repository.query.Param("actions") java.util.Collection<String> actions,
            @org.springframework.data.repository.query.Param("from") java.time.Instant from);

    java.util.List<AuditLog> findTop15ByOrderByIdDesc();

    java.util.List<AuditLog> findTop15ByUserIdOrderByIdDesc(Long userId);

    java.util.List<AuditLog> findTop5000ByOrderByIdDesc();

    java.util.List<AuditLog> findTop5000ByUserIdOrderByIdDesc(Long userId);
}
