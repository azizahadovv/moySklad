package uz.kassa.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uz.kassa.domain.AuditLog;
import uz.kassa.repo.AuditRepo;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditRepo auditRepo;

    public void log(Long userId, String action, String entity, Long entityId, String payload) {
        auditRepo.save(AuditLog.builder()
                .userId(userId).action(action)
                .entity(entity).entityId(entityId)
                .payload(payload)
                .build());
    }
}
