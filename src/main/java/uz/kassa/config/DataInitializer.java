package uz.kassa.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Role;
import uz.kassa.repo.AppUserRepo;

/** Ilk ishga tushishda .env dagi SuperAdminni yaratadi. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer {

    private final AppUserRepo userRepo;
    private final AppProps props;

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        Long tgId = props.getSuperadmin().getTelegramId();
        if (tgId == null || tgId <= 0) return;
        if (userRepo.findByTelegramId(tgId).isPresent()) return;
        userRepo.save(AppUser.builder()
                .telegramId(tgId)
                .fullName(props.getSuperadmin().getName())
                .role(Role.SUPERADMIN)
                .active(true)
                .build());
        log.info("SuperAdmin yaratildi: {} ({})", props.getSuperadmin().getName(), tgId);
    }
}
