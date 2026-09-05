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
    private final uz.kassa.repo.KassaRepo kassaRepo;
    private final OperationRepo opRepo;
    private final SubmissionRepo subRepo;
    private final SessionStore sessions;
    private final Sender sender;
    private final uz.kassa.service.notify.NotifyService notifySvc;
    private final NameService names;
    private final LabelService labelSvc;
    private final PermService permSvc;
    private final NotificationService notify;
    private final TransferService transferService;
    private final SubmissionService submissionService;
    private final KassirHandler kassir;
    private final BuxgalterHandler bux;
    private final AdminHandler admin;
    private final uz.kassa.bot.handlers.KontragentHandler kontragent;
    private final AuditService audit;
    private final uz.kassa.service.SettingsService settings;
    private final uz.kassa.scheduler.Jobs jobs;
    private final uz.kassa.service.moysklad.MoySkladSyncService syncService;
    private final uz.kassa.service.moysklad.MoySkladAuditService auditSvc;
    private final uz.kassa.service.DailyReportService dailyReport;
    private final MenuSupport menus;
    private final MembershipTracker members;
    private final CardCaptureHandler cardCapture;
    private final CardCommandHandler cardCmd;


    public void route(Update u) {
        if (u.hasChannelPost()) { members.onChannelPost(u.getChannelPost()); return; }
        if (u.hasMessage()) members.trackGroupMembers(u.getMessage());
        if (u.hasCallbackQuery()) onCallback(u.getCallbackQuery());
        else if (u.hasMessage() && u.getMessage().hasContact()) members.onContact(u.getMessage());
        else if (u.hasMessage() && u.getMessage().hasText()) onMessage(u.getMessage());
        else if (u.hasMessage() && (u.getMessage().hasPhoto() || u.getMessage().hasDocument())
                && (u.getMessage().getChat().isGroupChat() || u.getMessage().getChat().isSuperGroupChat())) {
            Message pm = u.getMessage();
            // DIAGNOSTIKA: Telegram xabar vaqti bilan bot qo'lga olgan vaqt farqi —
            // «bot rasmni kech ko'rdi» shikoyatida sabab Telegram/navbat ekanini ajratadi
            long ageSec = pm.getDate() == null ? -1 : (System.currentTimeMillis() / 1000 - pm.getDate());
            log.info("Guruh rasm: chat {}, from {}, msg {}, kechikish {}s, photo={}, doc={}",
                    pm.getChatId(), pm.getFrom() == null ? null : pm.getFrom().getId(),
                    pm.getMessageId(), ageSec, pm.hasPhoto(), pm.hasDocument());
            if (pm.getCaption() != null && !pm.getCaption().isBlank())
                // Skrinshot + izoh — izoh matnidan karta qoldig'i ushlanadi
                cardCapture.tryGroupCardCapture(pm, pm.getCaption());
            else
                // Izohsiz skrinshot (photo yoki fayl sifatida yuborilgan rasm) —
                // rasmning O'ZI o'qiladi (OCR, Tesseract). HAR yuborilgan rasm alohida
                // qayta ishlanadi — 50 marta yuborilsa 50 marta o'qiladi.
                cardCapture.ocrCardCapture(pm);
        }
    }


    /* ============================ MATN ============================ */

    private void onMessage(Message m) {
        long chatId = m.getChatId();
        long tgId = m.getFrom().getId();
        // Tugma nomi o'zgartirilgan bo'lsa — kanonik nomga qaytariladi,
        // shunda barcha navigatsiya mosligi buzilmaydi.
        String text = labelSvc.canonical(m.getText().trim());

        // Guruhda buyruq odatda "/cmd@botnomi" ko'rinishida keladi — @qismi olib tashlanadi.
        if (text.startsWith("/")) {
            int at = text.indexOf('@');
            if (at > 0 && text.substring(at + 1).equalsIgnoreCase(props.getBot().getUsername()))
                text = text.substring(0, at);
        }

        // Guruh/superguruh chatlarida bot FAQAT SuperAdmin'ning guruh sozlash buyruqlariga
        // javob beradi. Qolgan hamma narsa — menyu, bo'limlar, oddiy foydalanuvchi (kassir)
        // yuborgan buyruqlar — JIM e'tiborsiz qoldiriladi: guruhga bot hech narsa yozmaydi.
        if (m.getChat().isGroupChat() || m.getChat().isSuperGroupChat()) {
            boolean cfgCmd = text.equals("/setclickgroup") || text.equals("/testclickgroup")
                    || text.startsWith("/kartamas") || text.startsWith("/dukon") || text.equals("/auditclick");
            boolean kunlikCmd = text.startsWith("/kunlik");   // SuperAdmin yoki buxgalter — guruhga jadval
            boolean kartaCmd = !cfgCmd && !kunlikCmd && text.startsWith("/karta");
            if (!cfgCmd && !kartaCmd && !kunlikCmd) {
                // Buyruq emas — lekin karta qoldig'i yozilgan oddiy xabar bo'lishi
                // mumkin (mas'ullar skrinshot + matn tashlab boradi): ushlab ko'riladi
                cardCapture.tryGroupCardCapture(m, text);
                return;
            }
            if (cfgCmd) {
                boolean superadmin = userRepo.findByTelegramId(tgId)
                        .filter(AppUser::isActive)
                        .map(x -> x.getRole() == Role.SUPERADMIN).orElse(false);
                if (!superadmin) return;
            } else if (kunlikCmd) {
                boolean ok = userRepo.findByTelegramId(tgId).filter(AppUser::isActive)
                        .map(x -> x.getRole() == Role.SUPERADMIN || x.getRole() == Role.BUXGALTER).orElse(false);
                if (!ok) return;   // begonalar/kassirlar — JIM
            } else {
                // /karta — ro'yxatdagi faol xodim kifoya; begonalar JIM e'tiborsiz
                if (userRepo.findByTelegramId(tgId).filter(AppUser::isActive).isEmpty()) return;
            }
        }

        Optional<AppUser> uo = userRepo.findByTelegramId(tgId);
        if (uo.isEmpty() || !uo.get().isActive()) {
            members.rememberGuest(m);
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

        // 🔘 Shablon tugmasi (admin 🔔 Билдиришномалар'da sozlagan): foydalanuvchi kontekstida
        // jonli render qilinadi. Mavjud bo'limlardan OLDIN tekshiriladi, lekin matn mavjud
        // menyu tugmasiga teng bo'lolmaydi (saqlashda taqiqlanadi) — hech narsa buzilmaydi.
        var tplBtn = notifySvc.buttonByLabel(user.getRole(), text);
        if (tplBtn.isPresent()) {
            s.reset();
            sender.deleteMessage(chatId, m.getMessageId());
            var r = notifySvc.renderForUser(tplBtn.get(), user);
            Integer sentId = sender.sendId(chatId, r.text().isBlank() ? "<i>(shablon bo'sh)</i>" : r.text(),
                    menus.menuFor(user));
            if (tplBtn.get().getAutoDeleteMin() > 0)
                notifySvc.scheduleDelete(chatId, sentId, tplBtn.get().getAutoDeleteMin());
            return;
        }

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

        if (text.equals("/setclickgroup")) {
            if (user.getRole() != Role.SUPERADMIN) {
                sender.send(chatId, "⚠️ Bu buyruq faqat SuperAdmin uchun");
                return;
            }
            jobs.addClickChat(chatId);
            audit.log(user.getId(), "CLICK_GROUP_SET", "chat", chatId,
                    user.getFullName() + " Click qoldiqlari guruhlariga chat qo'shdi");
            sender.send(chatId, "✅ Shu chat Click qoldiqlari hisobotini (har soat boshida) "
                    + "qabul qiladigan guruhlar ro'yxatiga qo'shildi.\nChat ID: <code>" + chatId + "</code>"
                    + "\n\nBu guruhda hech qanday menyu/bo'lim ishlamaydi. Ro'yxatni admin paneldagi "
                    + "⚙️ Настройка → 📣 Гуруҳлар/Каналлар bo'limida boshqarish mumkin.",
                    org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove.builder()
                            .removeKeyboard(true).build());
            return;
        }

        if (text.equals("/testclickgroup")) {
            if (user.getRole() != Role.SUPERADMIN) {
                sender.send(chatId, "⚠️ Bu buyruq faqat SuperAdmin uchun");
                return;
            }
            jobs.clickReportNow();
            sender.send(chatId, "✅ Test yuborildi (sozlangan barcha guruh/kanallarga).");
            return;
        }

        // 💳 Karta (Click ilovasidagi haqiqiy) qoldig'i: /karta — ro'yxat,
        // /karta <ID> <summa> — kiritish. Guruhda ham, shaxsiyda ham ishlaydi.
        if (text.equals("/karta") || text.startsWith("/karta ")) {
            cardCmd.handleKarta(user, text, chatId);
            return;
        }
        if (text.startsWith("/kartamas")) {
            if (user.getRole() != Role.SUPERADMIN) {
                sender.send(chatId, "⚠️ Bu buyruq faqat SuperAdmin uchun");
                return;
            }
            cardCmd.handleKartaMas(user, text, chatId);
            return;
        }

        if (text.startsWith("/kunlik")) {
            // 📋 /kunlik [dd.MM.yyyy] — kunlik kassa solishtirish jadvali (shu chatga);
            //    /kunlik vaqt HH:MM — avtomatik yuborish vaqti (SuperAdmin)
            if (user.getRole() != Role.SUPERADMIN && user.getRole() != Role.BUXGALTER) {
                sender.send(chatId, "⚠️ Bu buyruq SuperAdmin va buxgalter uchun");
                return;
            }
            String[] kp = text.trim().split("\\s+");
            if (kp.length >= 3 && kp[1].equalsIgnoreCase("vaqt")) {
                if (user.getRole() != Role.SUPERADMIN) { sender.send(chatId, "⚠️ Vaqtni faqat SuperAdmin o'zgartiradi"); return; }
                if (!kp[2].matches("\\d{2}:\\d{2}")) { sender.send(chatId, "Format: <code>/kunlik vaqt 22:00</code> (5 daqiqaga karrali)"); return; }
                settings.set(uz.kassa.service.DailyReportService.TIME_KEY, kp[2]);
                sender.send(chatId, "✅ Kunlik hisobot vaqti: <b>" + kp[2] + "</b> (Click guruhlari + SuperAdmin + buxgalter)");
                return;
            }
            java.time.LocalDate d = java.time.LocalDate.now(props.zoneId());
            if (kp.length >= 2) {
                try { d = java.time.LocalDate.parse(kp[1], java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")); }
                catch (Exception e) { sender.send(chatId, "Sana formati: <code>/kunlik 02.09.2026</code>"); return; }
            }
            final java.time.LocalDate dd = d;
            new Thread(() -> {
                try { dailyReport.sendTo(chatId, dd); }
                catch (Exception ex) { sender.send(chatId, "⚠️ Hisobot xatosi: " + esc(String.valueOf(ex.getMessage()))); }
            }, "daily-report-cmd").start();
            return;
        }

        if (text.startsWith("/dukon")) {
            // 🏪 /dukon <kassaId> <nom> — otdel yonidagi do'kon/xizmat nomi
            // (Click hisoboti: «ОТДЕЛ ЗУФАР | Компьютер дукон»); «-» — o'chirish.
            if (user.getRole() != Role.SUPERADMIN) {
                sender.send(chatId, "⚠️ Bu buyruq faqat SuperAdmin uchun");
                return;
            }
            String[] dp = text.trim().split("\\s+", 3);
            if (dp.length < 3) {
                StringBuilder lb = new StringBuilder("🏪 <b>Otdel yonidagi do'kon nomi</b>\n"
                        + "Kiritish: <code>/dukon &lt;id&gt; &lt;nom&gt;</code>, o'chirish: <code>/dukon &lt;id&gt; -</code>\n\n");
                for (var k : kassaRepo.findByActiveTrueOrderByIdAsc())
                    lb.append(k.getId()).append(" — ").append(esc(k.getName()))
                      .append(k.getShopLabel() == null || k.getShopLabel().isBlank() ? "" : " | " + esc(k.getShopLabel()))
                      .append("\n");
                sender.send(chatId, lb.toString());
                return;
            }
            try {
                long kid = Long.parseLong(dp[1]);
                var ko = kassaRepo.findById(kid);
                if (ko.isEmpty()) { sender.send(chatId, "⚠️ Kassa topilmadi: " + kid); return; }
                String label = dp[2].trim();
                ko.get().setShopLabel(label.equals("-") ? null : label);
                kassaRepo.save(ko.get());
                sender.send(chatId, "✅ " + esc(ko.get().getName())
                        + (label.equals("-") ? " — do'kon nomi o'chirildi" : " | " + esc(label)));
            } catch (NumberFormatException e) {
                sender.send(chatId, "⚠️ Kassa ID raqam bo'lishi kerak: <code>/dukon 1 Компьютер дукон</code>");
            }
            return;
        }

        if (text.equals("/auditclick")) {
            if (user.getRole() != Role.SUPERADMIN) {
                sender.send(chatId, "⚠️ Bu buyruq faqat SuperAdmin uchun");
                return;
            }
            sender.send(chatId, "⏳ Click hisoblari va jami NAQD MoySklad bilan solishtirilmoqda "
                    + "(butun tarix — bir necha o'n soniya davom etishi mumkin)...");
            new Thread(() -> {
                try {
                    auditSvc.auditClickAccounts();
                    auditSvc.auditNaqd();
                    sender.send(chatId, "✅ Audit tugadi. Farq topilgan Click hisoblari va naqd "
                            + "farqi haqida buxgalteriyaga alohida xabar keladi (agar bo'lsa).");
                } catch (Exception ex) {
                    sender.send(chatId, "⚠️ Audit xatosi: " + esc(ex.getMessage()));
                }
            }, "click-audit-cmd").start();
            return;
        }

        if (text.equals("/start") || text.equals("/menu")) {
            s.reset();
            sender.send(chatId, "Assalomu alaykum, <b>" + esc(user.getFullName()) + "</b>!\n"
                    + menus.otdelLabel(user), menus.menuFor(user));
            // 🌐 Админ панел (Mini App): chat menyu tugmasi (≡) + inline tugma. Reply-klaviatura
            // tugmasi ishlatilmaydi — u orqali ochilganda Telegram initData bermaydi (2026-09-04 test).
            String wa = props.getWebappUrl();
            if (wa != null && !wa.isBlank() && user.getRole() != Role.KASSIR) {
                sender.setMenuButton(chatId, "🌐 Админ панел", wa);
                sender.sendWebAppButton(chatId, "🌐 <b>Админ панел</b> — веб кўриниш: бугун, кассалар, "
                        + "ҳисоботлар, созламалар. Пастдаги ≡ тугмаси орқали ҳам очилади.", "🌐 Админ панелни очиш", wa);
            }
            return;
        }

        // Bo'lim huquqi: umumiy o'chirilgan yoki shu user/otdel uchun taqiqlangan bo'lsa —
        // kira olmaydi (eski klaviaturada tugma qolib ketgan bo'lishi mumkin)
        if (LabelService.RENAMABLE.contains(text) && !permSvc.visible(user, text)) {
            sender.send(chatId, "⚠️ Bu bo'lim siz uchun ochiq emas (SuperAdmin sozlagan)",
                    menus.menuFor(user));
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
            sender.send(chatId, "⚠️ " + esc(e.getMessage()), menus.menuFor(user));
            return;
        }
        if (!handled) sender.send(chatId, "Quyidagi menyudan bo'limni tanlang 👇", menus.menuFor(user));
    }


    /* ============================ CALLBACK ============================ */

    private void onCallback(CallbackQuery cb) {
        sender.answer(cb.getId());
        long tgId = cb.getFrom().getId();
        long chatId = cb.getMessage().getChatId();
        int msgId = cb.getMessage().getMessageId();
        String data = cb.getData();

        // 💳 Skrinshot (OCR) tasdiqlash tugmalari — GURUHDA bosiladi va yuboruvchi
        // botda ro'yxatdan o'tmagan bo'lishi ham mumkin, shuning uchun umumiy
        // to'siqlardan OLDIN qayta ishlanadi (ichida o'z himoyasi bor).
        if (data.startsWith("kc:")) { cardCmd.kartaOcrDecision(cb, data, chatId, msgId); return; }
        if (data.startsWith("dr:ok:")) {   // 📋 kunlik hisobot — moliya menejeri tasdig'i
            try {
                var uo = userRepo.findByTelegramId(cb.getFrom().getId()).filter(AppUser::isActive);
                if (uo.isEmpty() || (uo.get().getRole() != Role.SUPERADMIN && uo.get().getRole() != Role.BUXGALTER)) {
                    sender.answerAlert(cb.getId(), "⛔ Tasdiqlashni faqat buxgalter yoki SuperAdmin bosa oladi.");
                    return;
                }
                java.time.LocalDate d = java.time.LocalDate.parse(data.substring(6));
                boolean fresh = dailyReport.confirm(d, uo.get());
                var c = dailyReport.confirmRepoView(d);
                sender.editCaption(chatId, msgId, dailyReport.caption(d, dailyReport.rows(d), c), null);
                sender.answerAlert(cb.getId(), fresh ? "✅ Tasdiqlandi" : "Bu kun allaqachon tasdiqlangan");
                audit.log(uo.get().getId(), "KUNLIK_TASDIQ", "daily", null, uo.get().getFullName() + " " + d + " kunlik hisobotni tasdiqladi");
            } catch (Exception e) {
                log.warn("Kunlik tasdiq xatosi: {}", e.getMessage());
            }
            return;
        }
        if (data.startsWith("kb:") || data.startsWith("ks:")
                || data.startsWith("ku:") || data.startsWith("kx:")
                || data.startsWith("kf:") || data.startsWith("km:")
                || data.startsWith("kq:") || data.startsWith("kv:")
                || data.startsWith("kp:")) {
            cardCmd.kartaPickDecision(cb, data, chatId, msgId);
            return;
        }

        // Guruh/superguruh chatlarida inline tugmalar ham ishlamaydi (menyu Kassalar/Kontragent guruhga
        // umuman chiqmaydi, lekin himoya sifatida bu yerda ham to'siladi). Telegram guruh/superguruh
        // chat ID'lari doim manfiy, shaxsiy chatlar doim musbat.
        if (chatId < 0) return;

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
                        + "\n\n✅ <b>To'liq qabul qilindi</b> — " + esc(user.getFullName())
                        + (sub.getKlik() > 0
                            ? "\nℹ️ Click summasi kassaning o'z hisobida qoladi — buxgalteriyaga o'tkazilmaydi."
                            : ""));
                notify.toKassa(sub.getKassaId(), "✅ Hisobot #" + sub.getId()
                        + " to'liq qabul qilindi: Naqd <b>" + fmt(sub.getNaqd())
                        + "</b> · Click <b>" + fmt(sub.getKlik()) + "</b> so'm"
                        + (sub.getKlik() > 0
                            ? "\nℹ️ Click pulingiz o'z hisobingizda qoladi." : ""), null);
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

}
