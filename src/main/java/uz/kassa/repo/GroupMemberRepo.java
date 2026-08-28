package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.GroupMember;

import java.util.List;
import java.util.Optional;

public interface GroupMemberRepo extends JpaRepository<GroupMember, Long> {
    List<GroupMember> findByChatIdOrderByIdAsc(Long chatId);
    Optional<GroupMember> findByChatIdAndUserId(Long chatId, Long userId);
}
