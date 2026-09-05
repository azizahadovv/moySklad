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
 * Guruhdagi karta qoldig'i: skrinshot/matndan summani olish, kartani tanish, saqlash/ko'chirish va tanlov klaviaturalari.
 * (Router dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CardCaptureHandler {

    private final AppUserRepo userRepo;
    private final Sender sender;
    private final AuditService audit;
    private final uz.kassa.scheduler.Jobs jobs;
    private final uz.kassa.repo.ClickAccountRepo clickRepo;
    private final uz.kassa.service.notify.NotifyService notifySvc;
    private final OcrEngine ocr;


    /**
     * Izohsiz SKRINSHOTdan qoldiqni o'qish — OCR (Tesseract). Rasm yuklab olinadi,
     * matnga aylantiriladi va odatiy ajratgichdan o'tadi. Tesseract o'rnatilmagan
     * bo'lsa (lokal run) jim o'tadi — Docker image ichida o'rnatilgan.
     */
    void ocrCardCapture(Message m) {
        if (!jobs.clickChatIds().contains(m.getChatId())) {
            log.info("OCR: chat {} Click guruhlari ro'yxatida yo'q — o'tkazildi", m.getChatId());
            return;
        }
        String fileId = null;
        if (m.hasPhoto()) {
            var sizes = m.getPhoto();
            fileId = sizes.get(sizes.size() - 1).getFileId();   // eng katta (aniq) o'lcham
        } else if (m.hasDocument() && m.getDocument().getMimeType() != null
                && m.getDocument().getMimeType().startsWith("image/")) {
            fileId = m.getDocument().getFileId();
        }
        if (fileId == null) return;
        final String fid = fileId;
        new Thread(() -> {
            java.io.File img = null;
            try {
                long t0 = System.currentTimeMillis();
                img = sender.downloadTgFile(fid);
                if (img == null) {
                    log.info("OCR: rasm yuklab olinmadi (chat {})", m.getChatId());
                    sender.reply(m.getChatId(), m.getMessageId(),
                            "🖼 Rasmni yuklab bo'lmadi — qayta yuboring yoki summani yozing "
                            + "(masalan: <code>Samoyiddin 250000</code>).", null);
                    return;
                }
                long t1 = System.currentTimeMillis();
                ocr.keepOcrSample(img, m.getMessageId());   // diagnostika: oxirgi 30 ta rasm saqlanadi
                String text = ocr.ocrMultiPass(img);
                long t2 = System.currentTimeMillis();
                log.info("OCR o'qildi ({} belgi, yuklash {}ms, ocr {}ms): {}", text.length(), t1 - t0, t2 - t1,
                        text.substring(0, Math.min(150, text.length())).replaceAll("\\s+", " "));
                if (!text.isBlank()) tryGroupCardCapture(m, "[OCR] " + text);
                else sender.reply(m.getChatId(), m.getMessageId(),
                        "🖼 Skrinshotdan matn o'qilmadi. Summani yozing: "
                        + "<code>KARTA_NOMI SUMMA</code> (masalan <code>Samoyiddin 250000</code>).", null);
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
    void tryGroupCardCapture(Message m, String rawText) {
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
            String[] lines = cleaned.split("\\r?\\n");
            for (int li = 0; li < lines.length; li++) {
                String line = lines[li];
                String ll = line.toLowerCase();
                if (line.contains("💰") || ll.contains("баланс") || ll.contains("balans")
                        || ll.contains("остат") || ll.contains("qoldi") || ll.contains("qoldig")
                        || ll.contains("колдиг") || ll.contains("қолдиғ")) {
                    var here = ocr.extractSumsTiyin(line);
                    // Balans qatorida 10 000 dan kichik summa ham bo'ladi — «0.00 UZS», «5 000 so'm»:
                    // avval valyuta bilan kelgani, bo'lmasa qatordagi yagona son (0 ham) olinadi.
                    if (here.isEmpty()) here = ocr.extractCurrencyTiyin(line);
                    if (here.isEmpty()) here = ocr.extractSoleNumberTiyin(line);
                    // Ilovalarda yorliq («Umumiy balans») raqamdan BIR QATOR YUQORIDA
                    // turadi — belgili qatorda raqam bo'lmasa keyingi qator olinadi.
                    if (here.isEmpty())
                        for (int k = li + 1; k < lines.length && k <= li + 2; k++) {
                            here = ocr.extractSumsTiyin(lines[k]);
                            if (here.isEmpty()) here = ocr.extractCurrencyTiyin(lines[k]);
                            if (!here.isEmpty() || !lines[k].isBlank()) break;
                        }
                    balSums.addAll(here);
                }
            }
            java.util.LinkedHashSet<Long> allSums = ocr.extractSumsTiyin(cleaned);
            // Skrinshotda balans belgisi bo'lmasa ham «0.00 UZS» kabi valyutali summa — qoldiq
            if (allSums.isEmpty()) allSums = ocr.extractCurrencyTiyin(cleaned);
            // Qo'lda yozilgan qisqa matn («Samoyiddin 0», «Zufar 5000») — istalgan yagona son
            if (allSums.isEmpty() && !fromOcr) allSums = ocr.extractSoleNumberTiyin(cleaned);
            java.util.List<Long> sums = new java.util.ArrayList<>(
                    balSums.size() == 1 ? balSums : allSums);
            if (sums.isEmpty()) {
                log.info("Karta capture: summa topilmadi (chat {}, ocr={})", chatId, fromOcr);
                if (fromOcr)
                    sender.reply(chatId, m.getMessageId(),
                            "🖼 Skrinshotdan summa o'qilmadi. Summani yozib yuboring: "
                            + "<code>KARTA_NOMI SUMMA</code> (masalan <code>Samoyiddin 250000</code>).", null);
                return;
            }

            // E'TIBORLILIK FILTRLARI (foydalanuvchi talabi):
            // 1) CHEK/tranzaksiya xabari (to'ldirish, o'tkazma, oplata...) — bu QOLDIQ
            //    emas; faqat ichida balans belgisi (💰/qoldiq/остаток) bo'lsa qabul.
            String lowAll = text.toLowerCase();
            boolean balanceMarked = !balSums.isEmpty();
            // Chek belgisi faqat SUMMA bilan bir qatorda kelsa hisobga olinadi —
            // bank ilovasining «Kartani to'ldirish» / «Kartadan o'tkazish» kabi
            // TUGMA yozuvlari (raqamsiz qator) chek emas.
            boolean receipt = text.contains("➕") || text.contains("➖");
            if (!receipt)
                for (String line : cleaned.split("\\r?\\n")) {
                    String ll = line.toLowerCase();
                    boolean kw = ll.matches(".*(to['’`‘]?ldirish|пополнен|перевод|оплат|o['’`‘]?tkazma"
                            + "|humo to|p2p|muvaffaqiyatli|успешн|перечисл|miqdor|сумма|списан|зачислен).*");
                    if (kw && !ocr.extractSums(line).isEmpty()) { receipt = true; break; }
                }
            if (receipt && !balanceMarked) {
                log.info("Karta capture: chek/tranzaksiya deb topildi — IGNOR (chat {})", chatId);
                if (fromOcr)
                    sender.reply(chatId, m.getMessageId(),
                            "🧾 Bu chek/tranzaksiya ko'rinishida — qoldiq sifatida olinmadi. "
                            + "Karta QOLDIG'I ekranini yuboring yoki yozing: "
                            + "<code>KARTA_NOMI SUMMA</code>.", null);
                return;
            }
            // 2) Oddiy yozilgan matn (OCR emas) balans belgisisiz UZUN GAP bo'lsa —
            //    bu guruh suhbati, aralashmaymiz (faqat «toza summa» qabul qilinadi).
            if (!fromOcr && !balanceMarked) {
                String leftover = lowAll
                        .replaceAll("so['’`]?m|сум|uzs", " ")
                        .replaceAll("[\\d ,.'’`\\u00A0:+-]+", " ").trim();
                if (leftover.length() > 15) {
                    log.info("Karta capture: balans belgisisiz erkin matn — IGNOR ({} harf qoldiq)",
                            leftover.length());
                    return;
                }
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
            boolean byName = card != null;   // rasm/matnning o'zida karta nomi bor
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
                            + fmtT(sums.get(0)) + "</code> so'm\n"
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
                        rows.add(Keyboards.irow(Keyboards.btn(fmtT(v) + " so'm",
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
                    rows.add(Keyboards.irow(Keyboards.btn(fmtT(v) + " so'm",
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

            if (fromOcr && byName) {
                // ISHONCHLI o'qish: rasmda BITTA summa (shu yergacha kelgan bo'lsa
                // sums.size()==1) va kartaning nomi rasmning o'zida bor — DARHOL
                // saqlanadi (foydalanuvchi qarori: rasm necha marta yuborilsa, qoldiq
                // shuncha marta yangilanadi, ✅ so'ralmaydi).
                // Xato bo'lsa ✏️ Tuzatish tugmasi bilan boshqa kartaga ko'chiriladi.
                saveCardBalance(card, sum, who + " (skrinshot)", from.getId(), chatId, 0, false);
                return;
            }
            if (fromOcr) {
                // OCR — mashina o'qigan raqam: DARHOL SAQLANMAYDI. Summa matn
                // ko'rinishida chiqadi, YUBORGAN ODAM ✅ bosgandagina yoziladi.
                sender.send(chatId, "🖼 <b>Skrinshotdan o'qildi</b> — tasdiqlang:\n"
                        + "💳 <b>" + esc(card.getName()) + "</b>\n"
                        + "Summa: <code>" + fmtT(sum) + "</code> so'm\n"
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


    /** Tiyindagi summa ko'rinishi (karta qoldig'i oqimi). */
    static String fmtT(long tiyin) { return uz.kassa.bot.TextUtil.fmtTiyin(tiyin); }


    /** Telegram profilidan ko'rinadigan ism: "Ism Familiya @username". */
    String displayName(org.telegram.telegrambots.meta.api.objects.User from) {
        String who = ((from.getFirstName() == null ? "" : from.getFirstName())
                + (from.getLastName() == null ? "" : " " + from.getLastName())).trim();
        if (from.getUserName() != null && !from.getUserName().isBlank())
            who = (who.isBlank() ? "" : who + " ") + "@" + from.getUserName();
        return who.isBlank() ? ("id " + from.getId()) : who;
    }


    /** Karta qoldig'ini YAKUNIY saqlash + guruhga tasdiq xabari (matn yoki OCR-tasdiq).
     *  sum — TIYINDA. */
    void saveCardBalance(ClickAccount card, long sum, String who, long fromTgId,
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
                + fmtT(sum) + "</b> so'm — " + esc(card.getCardBalanceBy())
                + (learned ? "\n🔗 Bu karta endi sizga biriktirildi — keyingi safar shunchaki "
                    + "summa yoki skrinshot yuboring, bot o'zi taniydi." : "")
                + "\nKeyingi hisobotda MoySklad bilan solishtiriladi.";
        // Adashib noto'g'ri otdel/karta tanlangan bo'lsa — orqaga yo'l: ✏️ Tuzatish
        // (yuborgan odam yoki SuperAdmin bosadi, to'g'ri kartaga ko'chiradi)
        var fixKb = Keyboards.inline(java.util.List.of(Keyboards.irow(
                Keyboards.btn("✏️ Tuzatish", "kf:" + card.getId() + ":" + sum + ":" + fromTgId))));
        Integer mid;
        if (editMsgId > 0) { sender.edit(chatId, editMsgId, text, fixKb); mid = editMsgId; }
        else mid = sender.sendId(chatId, text, fixKb);
        // 🔔 Tasdiq xabari N daqiqadan keyin o'chiriladi (sozlama: Билдиришномалар).
        // Tuzatishdan keyin qayta saqlanganda taymer 0 dan boshlanadi.
        notifySvc.scheduleDelete(chatId, mid, notifySvc.confirmDeleteMin());
    }


    /** Tuzatish klaviaturasi: eski (adashilgan) kartadan BOSHQA barcha kartalar. */
    org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
            movePickKb(long oldId, long sum, long senderTg) {
        var cards = clickRepo.findByActiveTrueOrderByIdAsc().stream()
                .filter(c -> c.getId() != oldId).toList();
        java.util.List<java.util.List<
                org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton>>
                rows = new java.util.ArrayList<>();
        for (int i = 0; i < cards.size(); i += 2) {
            if (i + 1 < cards.size())
                rows.add(Keyboards.irow(
                        Keyboards.btn(cards.get(i).getName(),
                                "km:" + cards.get(i).getId() + ":" + oldId + ":" + sum + ":" + senderTg),
                        Keyboards.btn(cards.get(i + 1).getName(),
                                "km:" + cards.get(i + 1).getId() + ":" + oldId + ":" + sum + ":" + senderTg)));
            else rows.add(Keyboards.irow(
                    Keyboards.btn(cards.get(i).getName(),
                            "km:" + cards.get(i).getId() + ":" + oldId + ":" + sum + ":" + senderTg)));
        }
        rows.add(Keyboards.irow(Keyboards.btn("⬅️ O'zgarishsiz qoldirish", "kq:" + senderTg)));
        return Keyboards.inline(rows);
    }


    /** Adashib tanlangan kartadan TO'G'RI kartaga ko'chirish: eski kartadagi xato
     *  yozuv (va o'sha yuboruvchiga adashib bog'langan mas'ullik) tozalanadi. */
    void moveCardBalance(long oldId, long newId, long sum, long senderTg,
                                 org.telegram.telegrambots.meta.api.objects.User presser,
                                 long chatId, int msgId) {
        var newCo = clickRepo.findById(newId);
        if (newCo.isEmpty()) { sender.edit(chatId, msgId, "⚠️ Hisob topilmadi"); return; }
        clickRepo.findById(oldId).ifPresent(oldC -> {
            if (oldC.getCardBalance() != null && oldC.getCardBalance() == sum) {
                oldC.setCardBalance(null);
                oldC.setCardBalanceAt(null);
                oldC.setCardBalanceBy(null);
            }
            String r = oldC.getCardResponsible();
            if (r != null && r.contains("id=" + senderTg)) oldC.setCardResponsible(null);
            clickRepo.save(oldC);
            Long uid0 = userRepo.findByTelegramId(presser.getId()).map(AppUser::getId).orElse(null);
            audit.log(uid0, "KARTA_TUZATISH", "click", oldId,
                    oldC.getName() + " dan olib tashlandi (adashib tanlangan edi, summa=" + sum + ")");
        });
        saveCardBalance(newCo.get(), sum, displayName(presser), presser.getId(), chatId, msgId, true);
    }


    /** Karta tanlash klaviaturasi (2 tadan qator) — «kb:&lt;cardId&gt;:&lt;sum&gt;:&lt;sender&gt;». */
    org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
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

}
