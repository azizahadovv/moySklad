package uz.kassa.bot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import uz.kassa.domain.*;
import uz.kassa.repo.AppUserRepo;
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
    private final uz.kassa.service.control.EmployeeLinkService employeeLink;
    private final uz.kassa.service.control.InviteService invite;

    /** «📱 Telefon raqamni yuborish» tugmali klaviatura. */
    static ReplyKeyboardMarkup contactKb() {
        var shareBtn = new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton(
                "📱 Telefon raqamni yuborish");
        shareBtn.setRequestContact(true);
        var row = new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow();
        row.add(shareBtn);
        var kb = new ReplyKeyboardMarkup();
        kb.setKeyboard(java.util.List.of(row));
        kb.setResizeKeyboard(true);
        kb.setOneTimeKeyboard(true);
        return kb;
    }

    /**
     * 🔗 Taklif havolasi bilan kirdi (/start inv_<token>). Token mehmonda eslab qolinadi;
     * kontakt kelganda {@link #onContact} shu xodimga tasdiqsiz ulaydi.
     */
    void onStartInvite(Message m, String token) {
        long chatId = m.getChatId();
        long tgId = m.getFrom().getId();
        Optional<AppUser> owner = invite.byToken(token);
        if (owner.isEmpty()) {
            sender.send(chatId, "⚠️ Bu taklif havolasi <b>eskirgan yoki ishlatilgan</b> (24 soat, bir martalik).\n"
                    + "SuperAdmin'dan yangi havola so'rang — yoki pastdagi tugma orqali telefon raqamingizni "
                    + "yuboring: raqamingiz ro'yxatda bo'lsa o'zi ulanadi.", contactKb());
            return;
        }
        AppUser x = owner.get();
        if (x.getTelegramId() != null && !x.getTelegramId().equals(tgId)) {
            sender.send(chatId, "⚠️ Bu havola allaqachon boshqa Telegram hisobiga ishlatilgan. "
                    + "SuperAdmin'ga murojaat qiling.");
            return;
        }
        guestRepo.findById(tgId).ifPresent(g -> { g.setInviteToken(token); guestRepo.save(g); });
        boolean hasPhone = x.getPhone() != null && !TextUtil.normPhone(x.getPhone()).isEmpty();
        sender.send(chatId, "👋 Assalomu alaykum! Siz <b>" + esc(x.getFullName()) + "</b> sifatida taklif qilindingiz"
                + (x.getKassaId() == null ? "" : " · " + esc(menus.otdelLabel(x))) + ".\n\n"
                + "Pastdagi tugma orqali <b>telefon raqamingizni yuboring</b> — tasdiqsiz darhol kirasiz"
                + (hasPhone ? " (raqam kartangizdagi bilan bir xil bo'lishi kerak)." : "."), contactKb());
    }


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
        String contactPhone = m.getContact().getPhoneNumber();
        // 🔗 Taklif havolasi bilan kirgan — token egasiga tasdiqsiz ulanadi (faqat telefon tekshiriladi)
        if (g != null && g.getInviteToken() != null) {
            var out = invite.accept(g.getInviteToken(), tgId, contactPhone);
            AppUser x = out.user();
            switch (out.result()) {
                case LINKED -> {
                    sender.send(chatId, "✅ Xush kelibsiz, <b>" + esc(x.getFullName()) + "</b>!\n"
                            + menus.otdelLabel(x), menus.menuFor(x));
                    notify.toRole(Role.SUPERADMIN, "🔗 <b>" + esc(x.getFullName())
                            + "</b> taklif havolasi orqali botga ulandi · " + menus.otdelLabel(x)
                            + "\nTelefon: <code>" + esc(contactPhone) + "</code>", null);
                    return;
                }
                case PHONE_MISMATCH -> {
                    sender.send(chatId, "⚠️ Bu havola <b>" + esc(x.getFullName()) + "</b> uchun, lekin yuborgan "
                            + "raqamingiz kartadagi raqamga mos kelmadi — ulanmadingiz.\n"
                            + "SuperAdmin'ga xabar ketdi, u tekshiradi.");
                    notify.toRole(Role.SUPERADMIN, "⚠️ <b>" + esc(x.getFullName())
                            + "</b> taklif havolasi bilan kirdi, lekin telefon mos kelmadi.\n"
                            + "Kartada: <code>" + esc(x.getPhone()) + "</code>"
                            + " · yuborildi: <code>" + esc(contactPhone) + "</code>\n"
                            + "TelegramID: <code>" + tgId + "</code>\n\n"
                            + "Raqam o'zgargan bo'lsa — quyidan o'sha xodimni tanlab ulang.",
                            contactLinkKb(tgId));
                    return;
                }
                case BUSY -> {
                    sender.send(chatId, "⚠️ Bu Telegram hisobi boshqa xodimga ulangan — "
                            + "SuperAdmin'ga murojaat qiling.");
                    return;
                }
                case USED, EXPIRED -> {
                    g.setInviteToken(null);
                    guestRepo.save(g);
                    boolean used = out.result() == uz.kassa.service.control.InviteService.Result.USED;
                    String why = used ? "allaqachon ishlatilgan" : "eskirgan (24 soat)";
                    sender.send(chatId, "⚠️ Taklif havolasi " + why
                            + ". Raqamingiz ro'yxatda bo'lsa baribir ulanasiz…");
                }
            }
        }
        // Jadvaldan (Sheets) telefon bilan oldindan yaratilgan foydalanuvchi bo'lsa — darhol ulaymiz.
        // Moslik faqat TO'LIQ raqam bo'yicha — suffiks (oxirgi 7 raqam) mosligi begona
        // odamni birovning akkauntiga (roli bilan!) ulab yuborishi mumkin edi.
        if (!TextUtil.normPhone(contactPhone).isEmpty()) {
            for (AppUser cand : userRepo.findAll()) {
                if (cand.getTelegramId() == null && cand.getPhone() != null
                        && TextUtil.phoneEq(cand.getPhone(), contactPhone)) {
                    cand.setTelegramId(tgId);
                    userRepo.save(cand);
                    // MoySklad xodimi bo'lsa — otdeli va rahbarligi darhol MoySklad bo'yicha
                    try { employeeLink.applyDepartment(cand, null); } catch (Exception e) { log.warn("Otdel qo'llash: {}", e.getMessage()); }
                    sender.send(chatId, "✅ Xush kelibsiz, <b>" + esc(cand.getFullName())
                            + "</b>!\n" + menus.otdelLabel(cand), menus.menuFor(cand));
                    notify.toRole(Role.SUPERADMIN, "🔗 <b>" + esc(cand.getFullName())
                            + "</b> botga ulandi (telefon mos keldi: <code>"
                            + esc(m.getContact().getPhoneNumber()) + "</code>)", null);
                    return;
                }
            }
        }
        // 👔 MoySklad xodimlari ro'yxatida bor odam — avtomatik ro'yxatdan o'tadi (KASSIR, MoySklad otdeli, РОП → rahbar)
        if (!TextUtil.normPhone(contactPhone).isEmpty()) {
            try {
                Optional<AppUser> reg = employeeLink.registerByPhone(contactPhone, null);
                if (reg.isPresent()) {
                    AppUser u = reg.get();
                    if (!u.isActive()) {
                        sender.send(chatId, "⚠️ Hisobingiz faolsizlantirilgan — SuperAdmin'ga murojaat qiling.");
                        notify.toRole(Role.SUPERADMIN, "⚠️ Faolsizlantirilgan foydalanuvchi <b>" + esc(u.getFullName())
                                + "</b> kontakt yubordi (MoySklad xodimi). Kerak bo'lsa qayta faollashtiring.", null);
                        return;
                    }
                    if (u.getTelegramId() != null && !u.getTelegramId().equals(tgId)) {
                        sender.send(chatId, "⚠️ Bu telefon raqami boshqa Telegram hisobiga ulangan — SuperAdmin'ga murojaat qiling.");
                        notify.toRole(Role.SUPERADMIN, "⚠️ <b>" + esc(u.getFullName()) + "</b> raqami bilan boshqa Telegram (<code>"
                                + tgId + "</code>) kontakt yubordi — tekshiring.", null);
                        return;
                    }
                    u.setTelegramId(tgId);
                    userRepo.save(u);
                    sender.send(chatId, "✅ Xush kelibsiz, <b>" + esc(u.getFullName()) + "</b>!\n"
                            + "Siz MoySklad xodimlari ro'yxatida borsiz — avtomatik ro'yxatdan o'tdingiz.\n"
                            + menus.otdelLabel(u), menus.menuFor(u));
                    notify.toRole(Role.SUPERADMIN, "✅ <b>" + esc(u.getFullName()) + "</b> MoySklad xodimi sifatida avtomatik "
                            + "ro'yxatdan o'tdi · " + menus.otdelLabel(u) + "\nTelefon: <code>" + esc(contactPhone) + "</code>", null);
                    return;
                }
            } catch (Exception e) {
                log.warn("MoySklad bo'yicha avto-ro'yxat xatosi: {}", e.getMessage());
            }
        }
        sender.send(chatId, "📱 Raqamingiz qabul qilindi: <b>" + esc(m.getContact().getPhoneNumber()) + "</b>\n\n"
                + "⚠️ Bu raqam MoySklad xodimlari ro'yxatida topilmadi, shuning uchun avtomatik kira olmadingiz.\n"
                + "MoySklad'da xodim kartangizga aynan shu raqam yozilsa — tugmani qayta bosing, darhol kirasiz. "
                + "Yoki SuperAdmin sizni qo'lda qo'shadi — unga xabar ketdi.");
        String who = m.getFrom().getFirstName() == null ? "" : m.getFrom().getFirstName();
        if (m.getFrom().getLastName() != null) who += " " + m.getFrom().getLastName();
        notify.toRole(Role.SUPERADMIN, "📱 <b>Yangi kontakt:</b> " + esc(who.trim())
                + (m.getFrom().getUserName() == null ? "" : " (@" + esc(m.getFrom().getUserName()) + ")")
                + "\nTelefon: <code>" + esc(m.getContact().getPhoneNumber()) + "</code>"
                + "\nTelegramID: <code>" + tgId + "</code>\n\n"
                + "Raqam MoySklad xodimlarida yo'q. " + unlinkedEmployeesHint()
                + "\n<b>Bir tugma bilan:</b> quyidan MoySklad xodimini tanlang — shu odam o'sha xodim sifatida darhol kiradi "
                + "(otdeli MoySklad'dan). Ro'yxatda yo'q bo'lsa — 👤 mehmon sifatida qo'shing.", contactLinkKb(tgId));
    }

    /** Kontakt xabari ostidagi tugmalar: hali ulanmagan MoySklad xodimlari (a:ctlk) + mehmon sifatida qo'shish (a:ctgu). */
    private org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup contactLinkKb(long tgId) {
        java.util.List<java.util.List<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton>> rows = new java.util.ArrayList<>();
        try {
            int n = 0;
            for (var e : employeeLink.employees()) {
                if (e.archived() || e.name().isBlank()) continue;
                boolean linked = userRepo.findFirstByMsEmployeeIdAndActiveTrue(e.id())
                        .or(() -> userRepo.findFirstByMsUidAndActiveTrue(e.uid() == null ? "" : e.uid()))
                        .map(x -> x.getTelegramId() != null).orElse(false);
                if (linked) continue;
                if (n++ >= 10) break;
                String label = "🔗 " + uz.kassa.service.control.EmployeeLinkService.cleanName(e.name())
                        + (e.groupName().isBlank() ? "" : " · " + e.groupName().replace("Отдел ", ""));
                rows.add(uz.kassa.bot.Keyboards.irow(uz.kassa.bot.Keyboards.btn(
                        label.length() > 60 ? label.substring(0, 60) : label, "a:ctlk:" + tgId + "." + e.id())));
            }
        } catch (Exception e) {
            log.warn("Kontakt tugmalari: {}", e.getMessage());
        }
        rows.add(uz.kassa.bot.Keyboards.irow(uz.kassa.bot.Keyboards.btn("👤 Mehmon sifatida qo'shish (rol/otdel tanlab)", "a:ctgu:" + tgId)));
        return uz.kassa.bot.Keyboards.inline(rows);
    }


    /** Botga hali ulanmagan MoySklad xodimlari (telefonsiz — alohida): admin kimni bog'lashni bilsin. */
    private String unlinkedEmployeesHint() {
        try {
            java.util.List<String> noPhone = new java.util.ArrayList<>(), withPhone = new java.util.ArrayList<>();
            for (var e : employeeLink.employees()) {
                if (e.archived() || e.name().isBlank()) continue;
                boolean linked = userRepo.findFirstByMsEmployeeIdAndActiveTrue(e.id())
                        .or(() -> userRepo.findFirstByMsUidAndActiveTrue(e.uid() == null ? "" : e.uid()))
                        .map(x -> x.getTelegramId() != null).orElse(false);
                if (linked) continue;
                (TextUtil.normPhone(e.phone()).isEmpty() ? noPhone : withPhone)
                        .add(uz.kassa.service.control.EmployeeLinkService.cleanName(e.name()));
            }
            StringBuilder sb = new StringBuilder();
            if (!noPhone.isEmpty())
                sb.append("MoySklad'da <b>telefoni yo'q</b> xodimlar (kartasiga raqam yozilsa o'zi ulanadi): ")
                  .append(esc(String.join(", ", noPhone.subList(0, Math.min(10, noPhone.size()))))).append(".\n");
            if (!withPhone.isEmpty())
                sb.append("Telefoni bor, lekin hali ulanmaganlar: ")
                  .append(esc(String.join(", ", withPhone.subList(0, Math.min(10, withPhone.size()))))).append(".\n");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
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
