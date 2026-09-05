package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.Sender;
import uz.kassa.bot.Session;
import uz.kassa.domain.*;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.GuestRepo;
import uz.kassa.service.*;
import uz.kassa.service.moysklad.MoySkladClient;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.*;

/**
 * Qarz daftari bo'limi uchun kichik yordamchilar (bugungi sana, orqaga tugmasi).
 * (KontragentHandler dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
public class KontragentSupport {

    private final uz.kassa.config.AppProps props;


    LocalDate today() { return LocalDate.now(props.zoneId()); }


    InlineKeyboardButton bk(String data) { return btn("⬅️ Orqaga", data); }


    MoySkladClient.MsAgent cachedAgent(Session s, String id) {
        Object o = s.data.get("kgAgents");
        if (o instanceof Map<?, ?> m && m.get(id) instanceof MoySkladClient.MsAgent a) return a;
        return null;
    }

}
