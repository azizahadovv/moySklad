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
 * Guruh/kanal a'zolari va mehmonlarni eslab qolish: kanal postlari, guruhga qo'shilganlar, kontakt ulashish.
 * (Router dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MembershipTracker {

    private final uz.kassa.config.AppProps props;
    private final AppUserRepo userRepo;
    private final uz.kassa.repo.GuestRepo guestRepo;
    private final Sender sender;
    private final NotificationService notify;
    private final uz.kassa.scheduler.Jobs jobs;
    private final uz.kassa.repo.GroupMemberRepo groupMemberRepo;
    private final uz.kassa.service.DailyReportService dailyReport;
    private final MenuSupport menus;


    /**
     * KANAL posti: kanalda yozgan odam noma'lum (from yo'q), shuning uchun faqat Click
     * guruh/kanallar ro'yxatidagi kanalda va faqat /kunlik [sana] buyrug'i ishlaydi —
     * jadval (rasm + Excel) shu kanalga yuboriladi.
     */
    void onChannelPost(Message m) {
        try {
            if (m.getText() == null || !m.getText().trim().startsWith("/kunlik")) return;
            long chatId = m.getChatId();
            if (!jobs.clickChatIds().contains(chatId)) return;
            String[] kp = m.getText().trim().split("\\s+");
            java.time.LocalDate d = java.time.LocalDate.now(props.zoneId());
            if (kp.length >= 2) {
                try { d = java.time.LocalDate.parse(kp[1], java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")); }
                catch (Exception e) { sender.send(chatId, "Sana formati: <code>/kunlik 02.09.2026</code>"); return; }
            }
            final java.time.LocalDate dd = d;
            new Thread(() -> {
                try { dailyReport.sendTo(chatId, dd); }
                catch (Exception ex) { log.warn("Kanal /kunlik ({}): {}", chatId, ex.getMessage()); }
            }, "daily-report-channel").start();
        } catch (Exception e) {
            log.debug("Kanal posti: {}", e.getMessage());
        }
    }


    /**
     * Guruh a'zolari registri ({hamma} shabloni uchun): Bot API to'liq a'zolar
     * ro'yxatini bermaydi, shuning uchun guruhda YOZGAN yoki QO'SHILGAN har bir
     * odam eslab qolinadi, chiqib ketgani o'chiriladi. Hech qanday javob yozilmaydi.
     */
    void trackGroupMembers(Message m) {
        try {
            if (!m.getChat().isGroupChat() && !m.getChat().isSuperGroupChat()) return;
            long chatId = m.getChatId();
            if (m.getFrom() != null) rememberMember(chatId, m.getFrom());
            if (m.getNewChatMembers() != null)
                for (var nu : m.getNewChatMembers()) rememberMember(chatId, nu);
            if (m.getLeftChatMember() != null)
                groupMemberRepo.findByChatIdAndUserId(chatId, m.getLeftChatMember().getId())
                        .ifPresent(groupMemberRepo::delete);
        } catch (Exception e) {
            log.debug("Guruh a'zo kuzatish: {}", e.getMessage());
        }
    }


    void rememberMember(long chatId, org.telegram.telegrambots.meta.api.objects.User u) {
        if (Boolean.TRUE.equals(u.getIsBot())) return;
        var existing = groupMemberRepo.findByChatIdAndUserId(chatId, u.getId()).orElse(null);
        String un = u.getUserName(), fn = u.getFirstName();
        if (existing == null) {
            groupMemberRepo.save(GroupMember.builder()
                    .chatId(chatId).userId(u.getId()).username(un).firstName(fn).build());
        } else if (!java.util.Objects.equals(existing.getUsername(), un)
                || !java.util.Objects.equals(existing.getFirstName(), fn)) {
            existing.setUsername(un);
            existing.setFirstName(fn);
            existing.setLastSeen(java.time.Instant.now());
            groupMemberRepo.save(existing);
        }
    }


    /** «📱 Telefon raqamni yuborish» tugmasi orqali kelgan kontakt. */
    void onContact(Message m) {
        long chatId = m.getChatId();
        long tgId = m.getFrom().getId();
        Optional<AppUser> uo = userRepo.findByTelegramId(tgId);
        if (uo.isPresent() && uo.get().isActive()) {
            sender.send(chatId, "✅ Raqamingiz allaqachon tizimda", menus.menuFor(uo.get()));
            return;
        }
        rememberGuest(m);
        Guest g = guestRepo.findById(tgId).orElse(null);
        if (g != null) {
            g.setPhone(m.getContact().getPhoneNumber());
            guestRepo.save(g);
        }
        // Jadvaldan (Sheets) telefon bilan oldindan yaratilgan foydalanuvchi bo'lsa — darhol ulaymiz.
        // Moslik faqat TO'LIQ raqam bo'yicha — suffiks (oxirgi 7 raqam) mosligi begona
        // odamni birovning akkauntiga (roli bilan!) ulab yuborishi mumkin edi.
        String contactPhone = m.getContact().getPhoneNumber();
        if (!TextUtil.normPhone(contactPhone).isEmpty()) {
            for (AppUser cand : userRepo.findAll()) {
                if (cand.getTelegramId() == null && cand.getPhone() != null
                        && TextUtil.phoneEq(cand.getPhone(), contactPhone)) {
                    cand.setTelegramId(tgId);
                    userRepo.save(cand);
                    sender.send(chatId, "✅ Xush kelibsiz, <b>" + esc(cand.getFullName())
                            + "</b>!\n" + menus.otdelLabel(cand), menus.menuFor(cand));
                    notify.toRole(Role.SUPERADMIN, "🔗 <b>" + esc(cand.getFullName())
                            + "</b> botga ulandi (telefon mos keldi: <code>"
                            + esc(m.getContact().getPhoneNumber()) + "</code>)", null);
                    return;
                }
            }
        }
        sender.send(chatId, "✅ Telefon raqamingiz qabul qilindi: <b>"
                + esc(m.getContact().getPhoneNumber()) + "</b>\n\n"
                + "SuperAdmin sizni shu raqam orqali topib tizimga qo'shadi.");
        String who = m.getFrom().getFirstName() == null ? "" : m.getFrom().getFirstName();
        if (m.getFrom().getLastName() != null) who += " " + m.getFrom().getLastName();
        notify.toRole(Role.SUPERADMIN, "📱 <b>Yangi kontakt:</b> " + esc(who.trim())
                + (m.getFrom().getUserName() == null ? "" : " (@" + esc(m.getFrom().getUserName()) + ")")
                + "\nTelefon: <code>" + esc(m.getContact().getPhoneNumber()) + "</code>"
                + "\nTelegramID: <code>" + tgId + "</code>\n\n"
                + "Jadvalda shu odam qatoriga Telefon yoki TelegramID ni yozsangiz — ulanadi.", null);
    }


    /** Notanish foydalanuvchini eslab qolish — admin keyin ro'yxatdan tanlab qo'shadi. */
    void rememberGuest(Message m) {
        try {
            long tgId = m.getFrom().getId();
            String fn = m.getFrom().getFirstName() == null ? "" : m.getFrom().getFirstName();
            String ln = m.getFrom().getLastName() == null ? "" : m.getFrom().getLastName();
            String name = (fn + " " + ln).trim();
            if (name.length() > 150) name = name.substring(0, 150);
            Guest g = guestRepo.findById(tgId).orElseGet(() ->
                    Guest.builder().telegramId(tgId).build());
            g.setName(name.isEmpty() ? null : name);
            g.setUsername(m.getFrom().getUserName());
            g.setLastSeen(java.time.Instant.now());
            guestRepo.save(g);
        } catch (Exception e) {
            log.warn("Guest yozishda xato: {}", e.getMessage());
        }
    }

}
