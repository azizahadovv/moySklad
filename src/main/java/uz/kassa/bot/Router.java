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
    private final uz.kassa.repo.GroupMemberRepo groupMemberRepo;
    private final uz.kassa.repo.ClickAccountRepo clickRepo;

    public void route(Update u) {
        if (u.hasMessage()) trackGroupMembers(u.getMessage());
        if (u.hasCallbackQuery()) onCallback(u.getCallbackQuery());
        else if (u.hasMessage() && u.getMessage().hasContact()) onContact(u.getMessage());
        else if (u.hasMessage() && u.getMessage().hasText()) onMessage(u.getMessage());
        else if (u.hasMessage() && (u.getMessage().hasPhoto() || u.getMessage().hasDocument())
                && (u.getMessage().getChat().isGroupChat() || u.getMessage().getChat().isSuperGroupChat())) {
            Message pm = u.getMessage();
            if (pm.getCaption() != null && !pm.getCaption().isBlank())
                // Skrinshot + izoh — izoh matnidan karta qoldig'i ushlanadi
                tryGroupCardCapture(pm, pm.getCaption());
            else if (pm.hasPhoto())
                // Izohsiz skrinshot — rasmning O'ZI o'qiladi (OCR, Tesseract)
                ocrCardCapture(pm);
        }
    }

    /**
     * Guruh a'zolari registri ({hamma} shabloni uchun): Bot API to'liq a'zolar
     * ro'yxatini bermaydi, shuning uchun guruhda YOZGAN yoki QO'SHILGAN har bir
     * odam eslab qolinadi, chiqib ketgani o'chiriladi. Hech qanday javob yozilmaydi.
     */
    private void trackGroupMembers(Message m) {
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

    private void rememberMember(long chatId, org.telegram.telegrambots.meta.api.objects.User u) {
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

    /**
     * Izohsiz SKRINSHOTdan qoldiqni o'qish — OCR (Tesseract). Rasm yuklab olinadi,
     * matnga aylantiriladi va odatiy ajratgichdan o'tadi. Tesseract o'rnatilmagan
     * bo'lsa (lokal run) jim o'tadi — Docker image ichida o'rnatilgan.
     */
    private void ocrCardCapture(Message m) {
        if (!jobs.clickChatIds().contains(m.getChatId())) return;
        new Thread(() -> {
            java.io.File img = null;
            try {
                var sizes = m.getPhoto();
                var best = sizes.get(sizes.size() - 1);   // eng katta (aniq) o'lcham
                img = sender.downloadTgFile(best.getFileId());
                if (img == null) { log.info("OCR: rasm yuklab olinmadi (chat {})", m.getChatId()); return; }
                Process p = new ProcessBuilder("tesseract", img.getAbsolutePath(),
                        "stdout", "--psm", "6", "-l", "eng").start();
                String text = new String(p.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                p.waitFor();
                log.info("OCR o'qildi ({} belgi): {}", text.length(),
                        text.substring(0, Math.min(150, text.length())).replaceAll("\\s+", " "));
                if (!text.isBlank()) tryGroupCardCapture(m, "[OCR] " + text);
            } catch (java.io.IOException e) {
                log.info("Tesseract yo'q/ishlamadi: {}", e.getMessage());
            } catch (Exception e) {
                log.info("OCR capture xatosi: {}", e.getMessage());
            } finally {
                if (img != null) img.delete();
            }
        }, "ocr-capture").start();
    }

    /**
     * Guruhga tashlangan SKRINSHOT IZOHI, ODDIY MATN yoki OCR natijasidan karta
     * qoldig'ini avtomatik ushlash. Qoidalar (xato pul yozmaslik uchun qat'iy):
     *  - faqat Click hisobot guruhlarida ishlaydi;
     *  - sana (01.09.2026), vaqt (10:00) va 17-22 kabi oraliqlar summa emas;
     *  - 💰/«qoldiq/остаток/balans» belgili QATORDAGI summa — balans deb olinadi
     *    (➕ to'ldirish/oplata summalari chalg'itmaydi);
     *  - karta: (a) matnda hisob nomi to'liq kelsa, (b) nomdagi raqam-token
     *    (masalan «5344») kelsa, (c) yuboruvchi /kartamas bilan biriktirilgan
     *    YAGONA kartaning mas'uli bo'lsa; aniqlanmasa — JIM;
     *  - bitta summa — qabul; bir nechta — taxmin qilinmaydi, /karta so'raladi.
     */
    private void tryGroupCardCapture(Message m, String rawText) {
        try {
            if (rawText == null || rawText.isBlank()) return;
            boolean fromOcr = rawText.startsWith("[OCR] ");
            String text = fromOcr ? rawText.substring(6) : rawText;
            long chatId = m.getChatId();
            if (!jobs.clickChatIds().contains(chatId)) return;
            var from = m.getFrom();
            if (from == null) return;
            java.util.List<ClickAccount> cards = clickRepo.findByActiveTrueOrderByIdAsc();
            if (cards.isEmpty()) return;

            // 1) Sana/vaqt/oraliqlar tozalanadi, so'ng summalar ajratiladi
            String cleaned = text
                    .replaceAll("\\b\\d{1,2}[.,/]\\d{1,2}[.,/]\\d{2,4}\\b", " ")
                    .replaceAll("\\b\\d{1,2}:\\d{2}\\b", " ")
                    .replaceAll("\\b\\d{1,2}-\\d{1,2}\\b", " ");

            // Avval 💰/qoldiq belgili qatorlar — balans o'sha yerda
            java.util.LinkedHashSet<Long> balSums = new java.util.LinkedHashSet<>();
            for (String line : cleaned.split("\\r?\\n")) {
                String ll = line.toLowerCase();
                if (line.contains("💰") || ll.contains("баланс") || ll.contains("balans")
                        || ll.contains("остат") || ll.contains("qoldi") || ll.contains("qoldig")
                        || ll.contains("колдиг") || ll.contains("қолдиғ"))
                    balSums.addAll(extractSums(line));
            }
            java.util.LinkedHashSet<Long> allSums = extractSums(cleaned);
            java.util.List<Long> sums = new java.util.ArrayList<>(
                    balSums.size() == 1 ? balSums : allSums);
            if (sums.isEmpty()) {
                log.info("Karta capture: summa topilmadi (chat {}, ocr={})", chatId, fromOcr);
                return;
            }

            // 2) Kartani aniqlash: to'liq nom -> nom SO'ZLARI (Samoyiddin, Zufar...)
            //    -> nomdagi raqam-token (5344) -> mas'ul. Shablon talab qilinmaydi —
            //    skrinshot/matn qanday ko'rinishda bo'lsa ham nomdagi o'ziga xos so'z
            //    uchrasa taniladi (faqat BITTA kartaga mos kelgandagina — adashmaslik uchun).
            ClickAccount card = null;
            String low = text.toLowerCase();
            for (ClickAccount c : cards)
                if (low.contains(c.getName().toLowerCase())) { card = c; break; }
            if (card == null) {
                java.util.Set<String> generic = java.util.Set.of(
                        "nsb", "click", "klik", "клик", "karta", "карта", "card", "bank");
                java.util.List<ClickAccount> byWord = new java.util.ArrayList<>();
                for (ClickAccount c : cards) {
                    for (String w : c.getName().toLowerCase().split("[^\\p{L}\\p{N}]+")) {
                        if (w.length() < 4 || generic.contains(w)) continue;
                        boolean hit = w.matches("\\d+") ? text.contains(w) : low.contains(w);
                        if (hit) { byWord.add(c); break; }
                    }
                }
                if (byWord.size() == 1) card = byWord.get(0);
            }
            if (card == null) {
                java.util.List<ClickAccount> mine = new java.util.ArrayList<>();
                String uname = from.getUserName() == null ? "" : from.getUserName().toLowerCase();
                for (ClickAccount c : cards) {
                    String r = c.getCardResponsible() == null ? "" : c.getCardResponsible().toLowerCase();
                    if (r.isBlank()) continue;
                    if ((!uname.isEmpty() && r.contains("@" + uname))
                            || r.contains("id=" + from.getId())) mine.add(c);
                }
                if (mine.size() == 1) card = mine.get(0);
            }
            if (card == null) {
                log.info("Karta capture: karta aniqlanmadi (sums={}, from={} @{})",
                        sums, from.getId(), from.getUserName());
                // SHABLONSIZ oqim: karta noma'lum — TUGMALAR chiqadi, yuborgan odam
                // bir marta bosadi, bot o'sha zahoti O'RGANIB oladi (keyingi safar avtomatik).
                String who = displayName(from);
                if (sums.size() == 1) {
                    sender.send(chatId, "💳 " + esc(who) + " summa yubordi: <code>"
                            + fmt(sums.get(0)) + "</code> so'm\n"
                            + "QAYSI kartaning qoldig'i? (yuborgan odam tanlasin — bir marta "
                            + "tanlagach bot eslab qoladi, keyingi safar avtomatik)",
                            cardPickKb(sums.get(0), from.getId()));
                } else {
                    java.util.List<java.util.List<
                            org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton>>
                            rows = new java.util.ArrayList<>();
                    int shown = 0;
                    for (long v : sums) {
                        if (shown++ >= 4) break;
                        rows.add(Keyboards.irow(Keyboards.btn(fmt(v) + " so'm",
                                "ku:" + v + ":" + from.getId())));
                    }
                    rows.add(Keyboards.irow(Keyboards.btn("❌ Bekor", "kx:" + from.getId())));
                    sender.send(chatId, "💳 " + esc(who) + " yubordi — bir nechta summa ko'rindi.\n"
                            + "QAYSI BIRI karta qoldig'i? (yuborgan odam tanlasin)",
                            Keyboards.inline(rows));
                }
                return;
            }

            if (sums.size() > 1) {
                // Karta ma'lum, summa bir nechta — tugma bilan tanlanadi (shablonsiz)
                java.util.List<java.util.List<
                        org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton>>
                        rows = new java.util.ArrayList<>();
                int shown = 0;
                for (long v : sums) {
                    if (shown++ >= 4) break;
                    rows.add(Keyboards.irow(Keyboards.btn(fmt(v) + " so'm",
                            "ks:" + card.getId() + ":" + v + ":" + from.getId())));
                }
                rows.add(Keyboards.irow(Keyboards.btn("❌ Bekor", "kx:" + from.getId())));
                sender.send(chatId, "💳 <b>" + esc(card.getName()) + "</b> — bir nechta summa "
                        + "ko'rindi. QAYSI BIRI qoldiq? (yuborgan odam tanlasin)",
                        Keyboards.inline(rows));
                return;
            }

            long sum = sums.get(0);
            String who = displayName(from);

            if (fromOcr) {
                // OCR — mashina o'qigan raqam: DARHOL SAQLANMAYDI. Summa matn
                // ko'rinishida chiqadi, YUBORGAN ODAM ✅ bosgandagina yoziladi.
                sender.send(chatId, "🖼 <b>Skrinshotdan o'qildi</b> — tasdiqlang:\n"
                        + "💳 <b>" + esc(card.getName()) + "</b>\n"
                        + "Summa: <code>" + fmt(sum) + "</code> so'm\n"
                        + "Yuborgan: " + esc(who) + "\n\n"
                        + "To'g'ri bo'lsa YUBORGAN ODAM ✅ ni bossin; noto'g'ri bo'lsa "
                        + "<code>/karta " + card.getId() + " СУММА</code> deb yozing.",
                        Keyboards.inline(java.util.List.of(Keyboards.irow(
                                Keyboards.btn("✅ To'g'ri",
                                        "kc:y:" + card.getId() + ":" + sum + ":" + from.getId()),
                                Keyboards.btn("❌ Noto'g'ri",
                                        "kc:n:" + card.getId() + ":" + sum + ":" + from.getId())))));
                return;
            }

            // Qo'lda yozilgan matn — odam ataylab yozdi, darhol saqlanadi
            saveCardBalance(card, sum, who, from.getId(), chatId, 0, false);
        } catch (Exception e) {
            log.debug("Guruh karta capture xatosi: {}", e.getMessage());
        }
    }

    /** Telegram profilidan ko'rinadigan ism: "Ism Familiya @username". */
    private String displayName(org.telegram.telegrambots.meta.api.objects.User from) {
        String who = ((from.getFirstName() == null ? "" : from.getFirstName())
                + (from.getLastName() == null ? "" : " " + from.getLastName())).trim();
        if (from.getUserName() != null && !from.getUserName().isBlank())
            who = (who.isBlank() ? "" : who + " ") + "@" + from.getUserName();
        return who.isBlank() ? ("id " + from.getId()) : who;
    }

    /** Karta qoldig'ini YAKUNIY saqlash + guruhga tasdiq xabari (matn yoki OCR-tasdiq). */
    private void saveCardBalance(ClickAccount card, long sum, String who, long fromTgId,
                                 long chatId, int editMsgId, boolean ocrConfirmed) {
        card.setCardBalance(sum);
        card.setCardBalanceAt(java.time.Instant.now());
        card.setCardBalanceBy(ocrConfirmed ? who + " (tasdiqlangan)" : who);

        // AVTO-O'RGANISH: karta hali hech kimga biriktirilmagan bo'lsa, yuborgan
        // odam avtomatik mas'ul bo'lib olinadi — hech qanday buyruq shart emas.
        boolean learned = false;
        if (card.getCardResponsible() == null || card.getCardResponsible().isBlank()) {
            String nm = who.replaceAll(" ?\\(.*$", "").trim();
            card.setCardResponsible("{id=" + fromTgId + ";" + (nm.isBlank() ? "mas'ul" : nm) + "}");
            learned = true;
        }
        clickRepo.save(card);
        Long uid = userRepo.findByTelegramId(fromTgId).map(AppUser::getId).orElse(null);
        audit.log(uid, "KARTA_QOLDIQ", "click", card.getId(),
                card.getName() + " = " + sum + " (guruhdan: " + card.getCardBalanceBy() + ")"
                        + (learned ? " [mas'ul avto-biriktirildi]" : ""));
        String text = "✅ <b>" + esc(card.getName()) + "</b> карта қолдиғи қабул қилинди: <b>"
                + fmt(sum) + "</b> so'm — " + esc(card.getCardBalanceBy())
                + (learned ? "\n🔗 Bu karta endi sizga biriktirildi — keyingi safar shunchaki "
                    + "summa yoki skrinshot yuboring, bot o'zi taniydi." : "")
                + "\nKeyingi hisobotda MoySklad bilan solishtiriladi.";
        if (editMsgId > 0) sender.edit(chatId, editMsgId, text);
        else sender.send(chatId, text);
    }

    /** Karta tanlash klaviaturasi (2 tadan qator) — «kb:&lt;cardId&gt;:&lt;sum&gt;:&lt;sender&gt;». */
    private org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
            cardPickKb(long sum, long senderTg) {
        var cards = clickRepo.findByActiveTrueOrderByIdAsc();
        java.util.List<java.util.List<
                org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton>>
                rows = new java.util.ArrayList<>();
        for (int i = 0; i < cards.size(); i += 2) {
            if (i + 1 < cards.size())
                rows.add(Keyboards.irow(
                        Keyboards.btn(cards.get(i).getName(),
                                "kb:" + cards.get(i).getId() + ":" + sum + ":" + senderTg),
                        Keyboards.btn(cards.get(i + 1).getName(),
                                "kb:" + cards.get(i + 1).getId() + ":" + sum + ":" + senderTg)));
            else rows.add(Keyboards.irow(
                    Keyboards.btn(cards.get(i).getName(),
                            "kb:" + cards.get(i).getId() + ":" + sum + ":" + senderTg)));
        }
        rows.add(Keyboards.irow(Keyboards.btn("❌ Bekor", "kx:" + senderTg)));
        return Keyboards.inline(rows);
    }

    /**
     * Karta/summa tanlash tugmalari: kb (karta tanlandi), ks (summa tanlandi),
     * ku (summa tanlandi, endi karta so'raladi), kx (bekor). Oxirgi bo'lak — yuborgan
     * odamning tgId'si: faqat o'sha odam (yoki SuperAdmin) bosa oladi.
     */
    private void kartaPickDecision(CallbackQuery cb, String data, long chatId, int msgId) {
        try {
            String[] p = data.split(":");
            long presser = cb.getFrom().getId();
            long senderTg = Long.parseLong(p[p.length - 1]);
            boolean superadmin = userRepo.findByTelegramId(presser)
                    .filter(AppUser::isActive)
                    .map(x -> x.getRole() == Role.SUPERADMIN).orElse(false);
            if (presser != senderTg && !superadmin) return;
            switch (p[0]) {
                case "kx" -> sender.edit(chatId, msgId, "❌ Bekor qilindi");
                case "ku" -> {
                    long sum = Long.parseLong(p[1]);
                    sender.edit(chatId, msgId, "Summa: <code>" + fmt(sum)
                            + "</code> so'm\nQAYSI kartaning qoldig'i?", cardPickKb(sum, senderTg));
                }
                default -> {   // kb / ks — karta va summa aniq
                    long cardId = Long.parseLong(p[1]);
                    long sum = Long.parseLong(p[2]);
                    var co = clickRepo.findById(cardId);
                    if (co.isEmpty()) { sender.edit(chatId, msgId, "⚠️ Hisob topilmadi"); return; }
                    saveCardBalance(co.get(), sum, displayName(cb.getFrom()), presser,
                            chatId, msgId, true);
                }
            }
        } catch (Exception e) {
            log.debug("karta pick callback: {}", e.getMessage());
        }
    }

    /**
     * kc:y|n:&lt;cardId&gt;:&lt;sum&gt;:&lt;senderTgId&gt; — skrinshot (OCR) natijasini
     * tasdiqlash/rad etish. Faqat YUBORGAN ODAM (yoki SuperAdmin) bosa oladi —
     * begonalar bosishi jim e'tiborsiz qoldiriladi.
     */
    private void kartaOcrDecision(CallbackQuery cb, String data, long chatId, int msgId) {
        try {
            String[] p = data.split(":");
            boolean yes = p[1].equals("y");
            long cardId = Long.parseLong(p[2]);
            long sum = Long.parseLong(p[3]);
            long senderTg = Long.parseLong(p[4]);
            long presser = cb.getFrom().getId();
            boolean superadmin = userRepo.findByTelegramId(presser)
                    .filter(AppUser::isActive)
                    .map(x -> x.getRole() == Role.SUPERADMIN).orElse(false);
            if (presser != senderTg && !superadmin) return;
            var co = clickRepo.findById(cardId);
            if (co.isEmpty()) { sender.edit(chatId, msgId, "⚠️ Hisob topilmadi"); return; }
            if (!yes) {
                sender.edit(chatId, msgId, "❌ <b>" + esc(co.get().getName())
                        + "</b> — skrinshotdagi summa RAD etildi.\nTo'g'ri summani yozing: "
                        + "<code>/karta " + cardId + " СУММА</code>");
                return;
            }
            saveCardBalance(co.get(), sum, displayName(cb.getFrom()), presser, chatId, msgId, true);
        } catch (Exception e) {
            log.debug("kc callback: {}", e.getMessage());
        }
    }

    /** Matndan pul summalarini ajratish: «2.030.000,00», «24 936 377.74», «12804310»,
     *  hatto OCR ning «1382 270.25» kabi notekis guruhlashi ham. Usul: avval raqam
     *  bo'laklari orasidagi minglik ajratkichlar (bo'sh joy/nuqta + roppa-rosa 3 raqam)
     *  YOPISHTIRILADI, keyin yaxlit son o'qiladi. Tiyin tashlanadi; 10 000 so'mdan
     *  kichigi e'tiborsiz (karta raqami bo'laklari 9860/1947 ham shu filtrda qoladi). */
    private java.util.LinkedHashSet<Long> extractSums(String s) {
        java.util.LinkedHashSet<Long> out = new java.util.LinkedHashSet<>();
        String joined = s, prev;
        do {
            prev = joined;
            joined = joined.replaceAll("(?<=\\d)[ \\u00A0.](?=\\d{3}(?!\\d))", "");
        } while (!joined.equals(prev));
        var matcher = java.util.regex.Pattern
                .compile("\\d{4,}(?:[.,]\\d{1,2})?")
                .matcher(joined);
        while (matcher.find()) {
            String t = matcher.group().replaceAll("[.,]\\d{1,2}$", "");   // tiyin tashlanadi
            try {
                long v = Long.parseLong(t);
                if (v >= 10_000) out.add(v);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** 💳 /karta — hisoblar ro'yxati; /karta <id> <summa> — karta HAQIQIY qoldig'ini
     *  qayd etish (MoySklad'da bu raqam yo'q — faqat mas'ul kiritadi). */
    private void handleKarta(AppUser user, String text, long chatId) {
        String[] p = text.trim().split("\\s+");
        var list = clickRepo.findByActiveTrueOrderByIdAsc();
        if (p.length < 3) {
            StringBuilder b = new StringBuilder("💳 <b>Karta qoldig'ini kiritish</b>\n"
                    + "Format: <code>/karta ID SUMMA</code> — masalan <code>/karta 2 12804310</code>\n\n");
            for (var c : list)
                b.append(c.getId()).append(". ").append(esc(c.getName()))
                 .append(c.getCardBalance() == null ? " — <i>kiritilmagan</i>"
                        : " — " + uz.kassa.bot.TextUtil.fmt(c.getCardBalance()) + " so'm")
                 .append("\n");
            sender.send(chatId, b.toString());
            return;
        }
        long id;
        try { id = Long.parseLong(p[1]); }
        catch (NumberFormatException e) {
            sender.send(chatId, "⚠️ ID raqam bo'lishi kerak. <code>/karta</code> deb yozib "
                    + "ro'yxatni ko'ring.");
            return;
        }
        var co = clickRepo.findById(id).filter(uz.kassa.domain.ClickAccount::isActive);
        if (co.isEmpty()) {
            sender.send(chatId, "⚠️ Bunday hisob topilmadi. <code>/karta</code> — ro'yxat.");
            return;
        }
        String sumS = String.join(" ", java.util.Arrays.copyOfRange(p, 2, p.length));
        long sum = uz.kassa.bot.TextUtil.parseAmount(sumS);
        if (sum < 0) {
            sender.send(chatId, "⚠️ Summani BUTUN so'mda kiriting (tiyinsiz), "
                    + "masalan <code>12804310</code>");
            return;
        }
        var c = co.get();
        c.setCardBalance(sum);
        c.setCardBalanceAt(java.time.Instant.now());
        c.setCardBalanceBy(user.getFullName());
        clickRepo.save(c);
        audit.log(user.getId(), "KARTA_QOLDIQ", "click", id,
                user.getFullName() + ": " + c.getName() + " = " + sum);
        sender.send(chatId, "✅ <b>" + esc(c.getName()) + "</b> karta qoldig'i qayd etildi: <b>"
                + uz.kassa.bot.TextUtil.fmt(sum) + "</b> so'm\n"
                + "Keyingi hisobotda MoySklad bilan solishtirilib chiqadi.");
    }

    /** /kartamas <id> <matn> — hisob uchun mas'ulni o'rnatish ("@username" yoki
     *  "{id=123456;Ism}"); «-» — olib tashlash. Faqat SuperAdmin. */
    private void handleKartaMas(AppUser user, String text, long chatId) {
        String[] p = text.trim().split("\\s+", 3);
        if (p.length < 3) {
            sender.send(chatId, "Format: <code>/kartamas ID @username</code> yoki "
                    + "<code>/kartamas ID {id=123456;Ism}</code>\n«-» — mas'ulni olib tashlash.");
            return;
        }
        long id;
        try { id = Long.parseLong(p[1]); }
        catch (NumberFormatException e) { sender.send(chatId, "⚠️ ID raqam bo'lishi kerak"); return; }
        var co = clickRepo.findById(id);
        if (co.isEmpty()) { sender.send(chatId, "⚠️ Hisob topilmadi"); return; }
        var c = co.get();
        c.setCardResponsible("-".equals(p[2].trim()) ? null : p[2].trim());
        clickRepo.save(c);
        audit.log(user.getId(), "KARTA_MASUL", "click", id,
                c.getName() + " -> " + p[2].trim());
        sender.send(chatId, "✅ <b>" + esc(c.getName()) + "</b> uchun mas'ul: "
                + (c.getCardResponsible() == null ? "<i>olib tashlandi</i>" : esc(c.getCardResponsible())));
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
                    || text.startsWith("/kartamas");
            boolean kartaCmd = !cfgCmd && text.startsWith("/karta");
            if (!cfgCmd && !kartaCmd) {
                // Buyruq emas — lekin karta qoldig'i yozilgan oddiy xabar bo'lishi
                // mumkin (mas'ullar skrinshot + matn tashlab boradi): ushlab ko'riladi
                tryGroupCardCapture(m, text);
                return;
            }
            if (cfgCmd) {
                boolean superadmin = userRepo.findByTelegramId(tgId)
                        .filter(AppUser::isActive)
                        .map(x -> x.getRole() == Role.SUPERADMIN).orElse(false);
                if (!superadmin) return;
            } else {
                // /karta — ro'yxatdagi faol xodim kifoya; begonalar JIM e'tiborsiz
                if (userRepo.findByTelegramId(tgId).filter(AppUser::isActive).isEmpty()) return;
            }
        }

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
            handleKarta(user, text, chatId);
            return;
        }
        if (text.startsWith("/kartamas")) {
            if (user.getRole() != Role.SUPERADMIN) {
                sender.send(chatId, "⚠️ Bu buyruq faqat SuperAdmin uchun");
                return;
            }
            handleKartaMas(user, text, chatId);
            return;
        }

        if (text.equals("/auditclick")) {
            if (user.getRole() != Role.SUPERADMIN) {
                sender.send(chatId, "⚠️ Bu buyruq faqat SuperAdmin uchun");
                return;
            }
            sender.send(chatId, "⏳ Click hisoblari MoySklad bilan to'liq solishtirilmoqda "
                    + "(butun tarix — bir necha o'n soniya davom etishi mumkin)...");
            new Thread(() -> {
                try {
                    syncService.auditClickAccounts();
                    sender.send(chatId, "✅ Click auditi tugadi. Farq topilgan hisoblar haqida "
                            + "alohida xabar keladi (agar bo'lsa).");
                } catch (Exception ex) {
                    sender.send(chatId, "⚠️ Audit xatosi: " + esc(ex.getMessage()));
                }
            }, "click-audit-cmd").start();
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

        // 💳 Skrinshot (OCR) tasdiqlash tugmalari — GURUHDA bosiladi va yuboruvchi
        // botda ro'yxatdan o'tmagan bo'lishi ham mumkin, shuning uchun umumiy
        // to'siqlardan OLDIN qayta ishlanadi (ichida o'z himoyasi bor).
        if (data.startsWith("kc:")) { kartaOcrDecision(cb, data, chatId, msgId); return; }
        if (data.startsWith("kb:") || data.startsWith("ks:")
                || data.startsWith("ku:") || data.startsWith("kx:")) {
            kartaPickDecision(cb, data, chatId, msgId);
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
