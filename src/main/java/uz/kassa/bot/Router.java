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
 * Barcha update'lar shu yerdan o'tadi:
 *  - autentifikatsiya (faqat bazadagi faol foydalanuvchilar — TZ 13);
 *  - matnlar rol bo'yicha handlerga;
 *  - qaror-callbacklar (rx/tr/sb) — server tomonida rol/egalik tekshiruvi bilan.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class Router {

    private final uz.kassa.config.AppProps props;
    private final AppUserRepo userRepo;
    private final uz.kassa.repo.GuestRepo guestRepo;
    private final uz.kassa.repo.KassaRepo kassaRepo;
    private final OperationRepo opRepo;
    private final SubmissionRepo subRepo;
    private final SessionStore sessions;
    private final Sender sender;
    private final NameService names;
    private final LabelService labelSvc;
    private final PermService permSvc;
    private final NotificationService notify;
    private final RasxodService rasxodService;
    private final TransferService transferService;
    private final SubmissionService submissionService;
    private final KassirHandler kassir;
    private final BuxgalterHandler bux;
    private final AdminHandler admin;
    private final uz.kassa.bot.handlers.KontragentHandler kontragent;
    private final AuditService audit;

    public void route(Update u) {
        if (u.hasCallbackQuery()) onCallback(u.getCallbackQuery());
        else if (u.hasMessage() && u.getMessage().hasContact()) onContact(u.getMessage());
        else if (u.hasMessage() && u.getMessage().hasText()) onMessage(u.getMessage());
    }

    /** «📱 Telefon raqamni yuborish» tugmasi orqali kelgan kontakt. */
    private void onContact(Message m) {
        long chatId = m.getChatId();
        long tgId = m.getFrom().getId();
        Optional<AppUser> uo = userRepo.findByTelegramId(tgId);
        if (uo.isPresent() && uo.get().isActive()) {
            sender.send(chatId, "✅ Raqamingiz allaqachon tizimda", menuFor(uo.get()));
            return;
        }
        rememberGuest(m);
        Guest g = guestRepo.findById(tgId).orElse(null);
        if (g != null) {
            g.setPhone(m.getContact().getPhoneNumber());
            guestRepo.save(g);
        }
        // Jadvaldan (Sheets) telefon bilan oldindan yaratilgan foydalanuvchi bo'lsa — darhol ulaymiz
        String d = m.getContact().getPhoneNumber().replaceAll("\\D", "");
        if (d.length() >= 7) {
            for (AppUser cand : userRepo.findAll()) {
                String p = cand.getPhone() == null ? "" : cand.getPhone().replaceAll("\\D", "");
                if (cand.getTelegramId() == null && !p.isEmpty()
                        && (p.endsWith(d) || d.endsWith(p))) {
                    cand.setTelegramId(tgId);
                    userRepo.save(cand);
                    sender.send(chatId, "✅ Xush kelibsiz, <b>" + esc(cand.getFullName())
                            + "</b>!\n" + otdelLabel(cand), menuFor(cand));
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

    /** Salomlashishda rol o'rniga foydalanuvchining O'Z otdeli ko'rsatiladi. */
    private String otdelLabel(AppUser u) {
        if (u.getKassaId() != null)
            return "🏪 Отдел " + esc(names.owner(OwnerType.KASSA, u.getKassaId()));
        return "🏪 Отдел основной";
    }

    /* ============================ MATN ============================ */

    private void onMessage(Message m) {
        long chatId = m.getChatId();
        long tgId = m.getFrom().getId();
        // Tugma nomi o'zgartirilgan bo'lsa — kanonik nomga qaytariladi,
        // shunda barcha navigatsiya mosligi buzilmaydi.
        String text = labelSvc.canonical(m.getText().trim());

        Optional<AppUser> uo = userRepo.findByTelegramId(tgId);
        if (uo.isEmpty() || !uo.get().isActive()) {
            rememberGuest(m);
            var shareBtn = new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton(
                    "📱 Telefon raqamni yuborish");
            shareBtn.setRequestContact(true);
            var row = new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow();
            row.add(shareBtn);
            var kb = new ReplyKeyboardMarkup();
            kb.setKeyboard(java.util.List.of(row));
            kb.setResizeKeyboard(true);
            kb.setOneTimeKeyboard(true);
            sender.send(chatId, "⛔ Sizga hali ruxsat berilmagan.\n\n"
                    + "Pastdagi tugma orqali <b>telefon raqamingizni yuboring</b> — "
                    + "SuperAdmin sizni raqam orqali topib tizimga qo'shadi.", kb);
            return;
        }
        AppUser user = uo.get();
        Session s = sessions.get(tgId);

        // Menyu tugmasi bosilsa — tugallanmagan dialog (FSM) bekor qilinadi
        if (Keyboards.isMenuLabel(text)) s.reset();

        // Panel ichida yurilganda foydalanuvchi bosgan tugma xabari o'chiriladi — chat toza qoladi
        boolean inPanel = s.data.get("nav") != null || s.data.get("knav") != null
                || Keyboards.isMenuLabel(text);
        if (inPanel && !text.startsWith("/")) sender.deleteMessage(chatId, m.getMessageId());

        if (text.equals("/clear")) {
            s.reset();
            // Audit: kim qachon chatni tozalagani yoziladi (Telegram o'chirilgan
            // xabarlar MATNINI bermaydi — faqat fakt qayd etiladi)
            audit.log(user.getId(), "CHAT_TOZALANDI", "chat", chatId,
                    user.getFullName() + " chatni to'liq tozaladi (barcha yozishmalar)");
            sender.clearChat(chatId, m.getMessageId());
            sender.send(chatId, "🏦 <b>NSB bot</b>\n\n"
                    + "Ushbu bot <b>NewStarBukhara</b> kompaniyasi uchun kassa va "
                    + "kontragentlar hisobini yuritish maqsadida yaratilgan.\n\n"
                    + "Davom etish uchun /start tugmasini bosing.");
            return;
        }

        if (text.equals("/start") || text.equals("/menu")) {
            s.reset();
            sender.send(chatId, "Assalomu alaykum, <b>" + esc(user.getFullName()) + "</b>!\n"
                    + otdelLabel(user), menuFor(user));
            String wa = props.getWebappUrl();
            if (wa != null && !wa.isBlank()) {
                var btn = org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
                        .builder().text("📱 Mini App — barcha bo'limlar")
                        .webApp(org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo
                                .builder().url(wa).build())
                        .build();
                sender.send(chatId, "Ilovada: balanslar, tranzaksiyalar tarixi (sana bo'yicha) "
                        + "va kutilayotgan qarorlar 👇",
                        Keyboards.inline(java.util.List.of(Keyboards.irow(btn))));
            }
            return;
        }

        // Bo'lim huquqi: umumiy o'chirilgan yoki shu user/otdel uchun taqiqlangan bo'lsa —
        // kira olmaydi (eski klaviaturada tugma qolib ketgan bo'lishi mumkin)
        if (LabelService.RENAMABLE.contains(text) && !permSvc.visible(user, text)) {
            sender.send(chatId, "⚠️ Bu bo'lim siz uchun ochiq emas (SuperAdmin sozlagan)",
                    menuFor(user));
            return;
        }

        boolean handled;
        try {
            handled = switch (user.getRole()) {
                case KASSIR -> kontragent.onText(user, s, text, chatId)
                        || kassir.onText(user, s, text, chatId);
                case BUXGALTER -> kontragent.onText(user, s, text, chatId)
                        || admin.onText(user, s, text, chatId)   // 📊 ПАНЕЛ (rol kesimida)
                        || bux.onText(user, s, text, chatId);
                case SUPERADMIN -> kontragent.onText(user, s, text, chatId)
                        || admin.onText(user, s, text, chatId)
                        || bux.onText(user, s, text, chatId);
            };
        } catch (BusinessException e) {
            s.reset();
            sender.send(chatId, "⚠️ " + esc(e.getMessage()), menuFor(user));
            return;
        }
        if (!handled) sender.send(chatId, "Quyidagi menyudan bo'limni tanlang 👇", menuFor(user));
    }

    /** Notanish foydalanuvchini eslab qolish — admin keyin ro'yxatdan tanlab qo'shadi. */
    private void rememberGuest(Message m) {
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

    /* ============================ CALLBACK ============================ */

    private void onCallback(CallbackQuery cb) {
        sender.answer(cb.getId());
        long tgId = cb.getFrom().getId();
        long chatId = cb.getMessage().getChatId();
        int msgId = cb.getMessage().getMessageId();
        String data = cb.getData();

        Optional<AppUser> uo = userRepo.findByTelegramId(tgId);
        if (uo.isEmpty() || !uo.get().isActive()) return;
        AppUser user = uo.get();
        Session s = sessions.get(tgId);

        try {
            if (data.equals("cx")) {
                s.reset();
                sender.edit(chatId, msgId, "❌ Bekor qilindi");
                return;
            }
            if (data.startsWith("rx:")) { decisionRasxod(user, s, data, chatId, msgId); return; }
            if (data.startsWith("tr:")) { decisionTransfer(user, data, chatId, msgId); return; }
            if (data.startsWith("sb:")) { decisionSubmission(user, s, data, chatId, msgId); return; }
            if (data.startsWith("kg:")) { kontragent.onCallback(user, s, data, chatId, msgId); return; }

            boolean handled = switch (user.getRole()) {
                case KASSIR -> kassir.onCallback(user, s, data, chatId, msgId);
                case BUXGALTER -> admin.onCallback(user, s, data, chatId, msgId)   // pul qabul qilish
                        || bux.onCallback(user, s, data, chatId, msgId);
                case SUPERADMIN -> admin.onCallback(user, s, data, chatId, msgId)
                        || bux.onCallback(user, s, data, chatId, msgId);
            };
            if (!handled) log.debug("Noma'lum callback: {}", data);
        } catch (BusinessException e) {
            sender.send(chatId, "⚠️ " + esc(e.getMessage()));
        }
    }

    /* -------- Rasxod qarori: faqat Buxgalter/SuperAdmin (TZ 3) -------- */

    private void decisionRasxod(AppUser user, Session s, String data, long chatId, int msgId) {
        if (user.getRole() == Role.KASSIR) {
            sender.send(chatId, "⚠️ Bu amal faqat buxgalter uchun");
            return;
        }
        String[] p = data.split(":");
        long opId = Long.parseLong(p[2]);

        if (p[1].equals("a")) {
            Operation op = rasxodService.approve(opId, user);
            sender.edit(chatId, msgId, "💸 Rasxod #" + op.getId() + " — <b>"
                    + fmt(op.getAmount()) + "</b> so'm (" + mtLabel(op.getMoneyType()) + ")\n\n"
                    + "✅ <b>Tasdiqlandi</b> — " + esc(user.getFullName()));
            notify.toKassa(op.getFromOwnerId(), "✅ Rasxod so'rovingiz tasdiqlandi: <b>"
                    + fmt(op.getAmount()) + "</b> so'm (" + mtLabel(op.getMoneyType()) + ")", null);
        } else if (p[1].equals("r")) {
            s.state = Session.State.RJ_RASXOD_REASON;
            s.data.put("opId", opId);
            s.data.put("srcChat", chatId);
            s.data.put("srcMsg", msgId);
            sender.send(chatId, "✍️ Rad etish sababini yozing:");
        }
    }

    /* -------- O'tkazma qarori: faqat QABUL QILUVCHI tomon (TZ 5) -------- */

    private void decisionTransfer(AppUser user, String data, long chatId, int msgId) {
        String[] p = data.split(":");
        long opId = Long.parseLong(p[2]);
        Operation op = opRepo.findById(opId)
                .orElseThrow(() -> new BusinessException("O'tkazma topilmadi"));

        boolean receiverOk;
        if (op.getToOwnerType() == OwnerType.KASSA) {
            receiverOk = user.getRole() == Role.KASSIR
                    && op.getToOwnerId().equals(user.getKassaId());
        } else {
            receiverOk = user.getRole() == Role.BUXGALTER || user.getRole() == Role.SUPERADMIN;
        }
        if (!receiverOk) {
            sender.send(chatId, "⚠️ Bu o'tkazmani faqat qabul qiluvchi tomon tasdiqlay oladi");
            return;
        }

        String head = "🔁 O'tkazma #" + op.getId() + ": "
                + esc(names.owner(op.getFromOwnerType(), op.getFromOwnerId())) + " → "
                + esc(names.owner(op.getToOwnerType(), op.getToOwnerId()))
                + "\n<b>" + fmt(op.getAmount()) + "</b> so'm (" + mtLabel(op.getMoneyType()) + ")";

        if (p[1].equals("a")) {
            op = transferService.accept(opId, user);
            String extra = op.getTransferKind() == TransferKind.QARZ_BERISH
                    ? "\n🧾 Qarz registriga yozildi (#" + op.getDebtId() + ")"
                    : (op.getTransferKind() == TransferKind.QARZ_QAYTARISH ? "\n🧾 Qarz qaytarildi" : "");
            sender.edit(chatId, msgId, head + "\n\n✅ <b>Qabul qilindi</b> — " + esc(user.getFullName()) + extra);
            notifySenderSide(op, "✅ O'tkazmangiz qabul qilindi: <b>" + fmt(op.getAmount())
                    + "</b> so'm — " + esc(names.owner(op.getToOwnerType(), op.getToOwnerId())) + extra);
        } else if (p[1].equals("r")) {
            op = transferService.reject(opId, user);
            sender.edit(chatId, msgId, head + "\n\n❌ <b>Rad etildi</b> — pul yuboruvchida qoldi");
            notifySenderSide(op, "❌ O'tkazmangiz rad etildi: <b>" + fmt(op.getAmount())
                    + "</b> so'm — " + esc(names.owner(op.getToOwnerType(), op.getToOwnerId()))
                    + ". Pul balansingizga qaytdi.");
        }
    }

    private void notifySenderSide(Operation op, String text) {
        if (op.getFromOwnerType() == OwnerType.KASSA) notify.toKassa(op.getFromOwnerId(), text, null);
        else notify.toBuxgalteriya(text, null);
    }

    /* -------- Hisobot qarori: Buxgalter/SuperAdmin (TZ 7.5/7.6) -------- */

    private void decisionSubmission(AppUser user, Session s, String data, long chatId, int msgId) {
        if (user.getRole() == Role.KASSIR) {
            sender.send(chatId, "⚠️ Bu amal faqat buxgalter uchun");
            return;
        }
        String[] p = data.split(":");
        long subId = Long.parseLong(p[2]);

        switch (p[1]) {
            case "f" -> {
                Submission sub = submissionService.acceptFull(subId, user);
                sender.edit(chatId, msgId, "📤 Hisobot #" + sub.getId() + " — "
                        + esc(names.owner(OwnerType.KASSA, sub.getKassaId()))
                        + "\nNaqd <b>" + fmt(sub.getNaqd()) + "</b> · Click <b>" + fmt(sub.getKlik()) + "</b> so'm"
                        + "\n\n✅ <b>To'liq qabul qilindi</b> — " + esc(user.getFullName()));
                notify.toKassa(sub.getKassaId(), "✅ Hisobot #" + sub.getId()
                        + " to'liq qabul qilindi: Naqd <b>" + fmt(sub.getNaqd())
                        + "</b> · Click <b>" + fmt(sub.getKlik()) + "</b> so'm", null);
            }
            case "p" -> {
                Submission sub = subRepo.findById(subId)
                        .orElseThrow(() -> new BusinessException("Hisobot topilmadi"));
                if (sub.getStatus() != SubmissionStatus.KUTILMOQDA)
                    throw new BusinessException("Bu hisobot allaqachon ko'rib chiqilgan");
                s.state = Session.State.SBP_NAQD;
                s.data.put("subId", subId);
                s.data.put("maxN", sub.getNaqd());
                s.data.put("maxK", sub.getKlik());
                s.data.put("srcChat", chatId);
                s.data.put("srcMsg", msgId);
                sender.send(chatId, "✍️ Haqiqatda olingan <b>NAQD</b> summani kiriting "
                        + "(0 dan " + fmt(sub.getNaqd()) + " gacha):");
            }
            case "r" -> {
                s.state = Session.State.RJ_SUB_REASON;
                s.data.put("subId", subId);
                s.data.put("srcChat", chatId);
                s.data.put("srcMsg", msgId);
                sender.send(chatId, "✍️ Hisobotni rad etish sababini yozing:");
            }
        }
    }

    /* ============================ Yordamchi ============================ */

    public ReplyKeyboardMarkup menuFor(AppUser user) {
        java.util.function.Predicate<String> vis = c -> permSvc.visible(user, c);
        return switch (user.getRole()) {
            case KASSIR -> Keyboards.kassirMenu(vis);
            case BUXGALTER, SUPERADMIN -> Keyboards.buxMenu(vis);
        };
    }

    private String roleLabel(Role r) {
        return switch (r) {
            case KASSIR -> "Kassir";
            case BUXGALTER -> "Buxgalter";
            case SUPERADMIN -> "SuperAdmin";
        };
    }
}
