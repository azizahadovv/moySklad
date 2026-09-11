package uz.kassa.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import uz.kassa.bot.Sender;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Role;
import uz.kassa.repo.AppUserRepo;

/**
 * Rolga/kassaga/foydalanuvchiga xabar yuborish.
 * {@code code} bilan chaqirilsa — 🔕 Хабарномалар kaliti (tur + auditoriya) tekshiriladi:
 * tur o'chiq bo'lsa hech kimga, auditoriyasi o'chiq bo'lsa o'sha odamlarga ketmaydi.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final Sender sender;
    private final AppUserRepo userRepo;
    private final NotifySwitches sw;

    /** Buxgalter(lar) + SuperAdmin(lar)ga — qaror talab qiladigan xabarlar. */
    public void toBuxgalteriya(String text, InlineKeyboardMarkup kb) {
        toRole(Role.BUXGALTER, text, kb);
        toRole(Role.SUPERADMIN, text, kb);
    }

    public void toBuxgalteriya(String code, String text, InlineKeyboardMarkup kb) {
        toRole(code, Role.BUXGALTER, text, kb);
        toRole(code, Role.SUPERADMIN, text, kb);
    }

    public void toRole(Role role, String text, InlineKeyboardMarkup kb) {
        for (AppUser u : userRepo.findByRoleAndActiveTrue(role))
            if (u.getTelegramId() != null) sender.send(u.getTelegramId(), text, kb);
    }

    public void toRole(String code, Role role, String text, InlineKeyboardMarkup kb) {
        if (!sw.on(code)) return;
        for (AppUser u : userRepo.findByRoleAndActiveTrue(role))
            if (u.getTelegramId() != null && sw.allow(code, u)) sender.send(u.getTelegramId(), text, kb);
    }

    /** Kassaga biriktirilgan barcha faol kassirlarga (Telegram ulanganlariga). */
    public void toKassa(Long kassaId, String text, InlineKeyboardMarkup kb) {
        for (AppUser u : userRepo.findByKassaIdAndActiveTrue(kassaId))
            if (u.getTelegramId() != null) sender.send(u.getTelegramId(), text, kb);
    }

    public void toKassa(String code, Long kassaId, String text, InlineKeyboardMarkup kb) {
        if (!sw.on(code)) return;
        for (AppUser u : userRepo.findByKassaIdAndActiveTrue(kassaId))
            if (u.getTelegramId() != null && sw.allow(code, u)) sender.send(u.getTelegramId(), text, kb);
    }

    public void toUser(Long telegramId, String text) {
        if (telegramId != null) sender.send(telegramId, text, null);
    }

    public void toUser(String code, AppUser u, String text) {
        if (u != null && u.getTelegramId() != null && sw.allow(code, u)) sender.send(u.getTelegramId(), text, null);
    }
}
