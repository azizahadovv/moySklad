package uz.kassa.bot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import uz.kassa.bot.handlers.AdminHandler;
import uz.kassa.bot.handlers.BuxgalterHandler;
import uz.kassa.bot.handlers.KassirHandler;
import uz.kassa.domain.*;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.OperationRepo;
import uz.kassa.repo.SubmissionRepo;
import uz.kassa.service.*;
import java.util.Optional;
import static uz.kassa.bot.TextUtil.*;

/**
 * Rolga mos asosiy reply-menyu va otdel/rol yorliqlari.
 * (Router dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MenuSupport {

    private final NameService names;
    private final PermService permSvc;
    private final uz.kassa.service.notify.NotifyService notifySvc;
    private final uz.kassa.config.AppProps props;


    /** Salomlashishda rol o'rniga foydalanuvchining O'Z otdeli ko'rsatiladi. */
    String otdelLabel(AppUser u) {
        if (u.getKassaId() != null)
            return "🏪 Отдел " + esc(names.owner(OwnerType.KASSA, u.getKassaId()));
        return "🏪 Отдел основной";
    }


    /* ============================ Yordamchi ============================ */

    public ReplyKeyboardMarkup menuFor(AppUser user) {
        java.util.function.Predicate<String> vis = c -> permSvc.visible(user, c);
        // 🔘 Admin sozlagan shablon tugmalari — mavjud tugmalardan keyin qo'shiladi
        java.util.List<String> extra = notifySvc.buttonLabelsFor(user.getRole());
        return switch (user.getRole()) {
            case KASSIR -> Keyboards.kassirMenu(vis, extra);
            case BUXGALTER, SUPERADMIN -> Keyboards.buxMenu(vis, extra,
                    user.getRole() == Role.SUPERADMIN, props.getWebappUrl());
        };
    }


    String roleLabel(Role r) {
        return switch (r) {
            case KASSIR -> "Kassir";
            case BUXGALTER -> "Buxgalter";
            case SUPERADMIN -> "SuperAdmin";
        };
    }

}
