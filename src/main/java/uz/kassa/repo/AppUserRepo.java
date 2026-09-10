package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Role;
import java.util.List;
import java.util.Optional;

public interface AppUserRepo extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByTelegramId(Long telegramId);
    List<AppUser> findByRoleAndActiveTrue(Role role);
    List<AppUser> findByKassaIdAndActiveTrue(Long kassaId);
    List<AppUser> findByActiveTrueOrderByRoleAscIdAsc();
    Optional<AppUser> findFirstByMsUidAndActiveTrue(String msUid);
    Optional<AppUser> findFirstByMsEmployeeIdAndActiveTrue(String msEmployeeId);
    Optional<AppUser> findFirstByInviteToken(String inviteToken);
    /** Telegram ulangan, lekin nazorat xabarlari hali yuborilmaganlar. */
    List<AppUser> findByActiveTrueAndTelegramIdIsNotNullAndControlWelcomeAtIsNull();
}
