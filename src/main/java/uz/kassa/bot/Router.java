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
    private final OperationRepo opRepo;
    private final SubmissionRepo subRepo;
    private final SessionStore sessions;
    private final Sender sender;
    private final NameService names;
    private final NotificationService notify;
    private final RasxodService rasxodService;
    private final TransferService transferService;
    private final SubmissionService submissionService;
    private final KassirHandler kassir;
    private final BuxgalterHandler bux;
    private final AdminHandler admin;

    public void route(Update u) {
        if (u.hasCallbackQuery()) onCallback(u.getCallbackQuery());
        else if (u.hasMessage() && u.getMessage().hasText()) onMessage(u.getMessage());
    }

    /* ============================ MATN ============================ */

    private void onMessage(Message m) {
        long chatId = m.getChatId();
        long tgId = m.getFrom().getId();
        String text = m.getText().trim();

        Optional<AppUser> uo = userRepo.findByTelegramId(tgId);
        if (uo.isEmpty() || !uo.get().isActive()) {
            rememberGuest(m);
            sender.send(chatId, "⛔ Sizga hali ruxsat berilmagan.\n\n"
                    + "Sizning Telegram ID: <code>" + tgId + "</code>\n"
                    + "SuperAdmin sizni endi ro'yxatdan tanlab qo'sha oladi.");
            return;
        }
        AppUser user = uo.get();
        Session s = sessions.get(tgId);

        // Menyu tugmasi bosilsa — tugallanmagan dialog (FSM) bekor qilinadi
        if (Keyboards.isMenuLabel(text)) s.reset();

        if (text.equals("/start") || text.equals("/menu")) {
            s.reset();
            sender.send(chatId, "Assalomu alaykum, <b>" + esc(user.getFullName()) + "</b>!\n"
                    + "Rol: " + roleLabel(user.getRole()), menuFor(user));
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

        boolean handled;
        try {
            handled = switch (user.getRole()) {
                case KASSIR -> kassir.onText(user, s, text, chatId);
                case BUXGALTER -> bux.onText(user, s, text, chatId);
                case SUPERADMIN -> admin.onText(user, s, text, chatId)
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

            boolean handled = switch (user.getRole()) {
                case KASSIR -> kassir.onCallback(user, s, data, chatId, msgId);
                case BUXGALTER -> bux.onCallback(user, s, data, chatId, msgId);
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
        return switch (user.getRole()) {
            case KASSIR -> Keyboards.kassirMenu();
            case BUXGALTER -> Keyboards.buxMenu(false);
            case SUPERADMIN -> Keyboards.buxMenu(true);
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
