package uz.kassa.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import uz.kassa.bot.Sender;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Role;
import uz.kassa.repo.AppUserRepo;

/** Rolga/kassaga/foydalanuvchiga xabar yuborish. */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final Sender sender;
    private final AppUserRepo userRepo;

    /** Buxgalter(lar) + SuperAdmin(lar)ga — qaror talab qiladigan xabarlar. */
    public void toBuxgalteriya(String text, InlineKeyboardMarkup kb) {
        toRole(Role.BUXGALTER, text, kb);
        toRole(Role.SUPERADMIN, text, kb);
    }

    public void toRole(Role role, String text, InlineKeyboardMarkup kb) {
        for (AppUser u : userRepo.findByRoleAndActiveTrue(role))
            if (u.getTelegramId() != null) sender.send(u.getTelegramId(), text, kb);
    }

    /** Kassaga biriktirilgan barcha faol kassirlarga (Telegram ulanganlariga). */
    public void toKassa(Long kassaId, String text, InlineKeyboardMarkup kb) {
        for (AppUser u : userRepo.findByKassaIdAndActiveTrue(kassaId))
            if (u.getTelegramId() != null) sender.send(u.getTelegramId(), text, kb);
    }

    public void toUser(Long telegramId, String text) {
        if (telegramId != null) sender.send(telegramId, text, null);
    }
}
