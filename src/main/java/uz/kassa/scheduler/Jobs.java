package uz.kassa.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.kassa.bot.TextUtil;
import uz.kassa.domain.*;
import uz.kassa.repo.DayRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.service.DayService;
import uz.kassa.service.LedgerService;
import uz.kassa.service.NotificationService;
import uz.kassa.service.SubmissionService;
import uz.kassa.service.moysklad.MoySkladSyncService;

import java.time.LocalDate;
import java.util.List;

/** Rejalashtirilgan ishlar (TZ 12): sinxron 5 daq, kun yopilishi 00:00, eslatma 21:00. */
@Component
@RequiredArgsConstructor
@Slf4j
public class Jobs {

    private final MoySkladSyncService syncService;
    private final uz.kassa.service.ReminderService reminderService;
    private final uz.kassa.gsheets.SheetsSyncService sheetsSync;
    private final DayService dayService;
    private final SubmissionService submissionService;
    private final LedgerService ledger;
    private final KassaRepo kassaRepo;
    private final DayRepo dayRepo;
    private final NotificationService notify;
    private final uz.kassa.bot.NameService names;
    private final uz.kassa.repo.ClickAccountRepo clickRepo;
    private final uz.kassa.service.SettingsService settings;
    private final uz.kassa.bot.Sender sender;

    /**
     * Click qoldiqlari soatlik hisoboti yuboriladigan guruh/kanallar — vergul bilan
     * ajratilgan chat ID ro'yxati (eski bitta-ID format ham o'qiladi).
     * /setclickgroup buyrug'i yoki admin panel (📣 Гуруҳлар/Каналлар) orqali boshqariladi.
     */
    public static final String CLICK_GROUP_KEY = "notify.clickGroupChatId";

    /** Ro'yxatdagi barcha chat ID'lar (bo'sh yoki yaroqsiz yozuvlar tashlab yuboriladi). */
    public java.util.List<Long> clickChatIds() {
        String raw = settings.get(CLICK_GROUP_KEY).orElse("");
        java.util.List<Long> out = new java.util.ArrayList<>();
        for (String p : raw.split(",")) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            try {
                long v = Long.parseLong(t);
                if (!out.contains(v)) out.add(v);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    public void addClickChat(long chatId) {
        var ids = clickChatIds();
        if (!ids.contains(chatId)) ids.add(chatId);
        saveClickChats(ids);
    }

    public void removeClickChat(long chatId) {
        var ids = clickChatIds();
        ids.remove(Long.valueOf(chatId));
        saveClickChats(ids);
    }

    private void saveClickChats(java.util.List<Long> ids) {
        settings.set(CLICK_GROUP_KEY, ids.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",")));
    }

    /* ------- Hisobot jadvali: necha soatda bir va qaysi soatlar oralig'ida ------- */

    /** Necha soatda bir yuborilsin (1/2/3/4/6/12/24). */
    public static final String CLICK_EVERY_KEY = "notify.clickEveryHours";
    /** Yuborish oynasi: boshlanish soati (0-23). */
    public static final String CLICK_FROM_KEY = "notify.clickFromHour";
    /** Yuborish oynasi: tugash soati (0-23, shu soat ham kiradi). */
    public static final String CLICK_TO_KEY = "notify.clickToHour";

    public int clickEvery() { return intSetting(CLICK_EVERY_KEY, 1, 1, 24); }
    public int clickFrom() { return intSetting(CLICK_FROM_KEY, 0, 0, 23); }
    public int clickTo() { return intSetting(CLICK_TO_KEY, 23, 0, 23); }

    private int intSetting(String key, int def, int min, int max) {
        try {
            int v = Integer.parseInt(settings.get(key).orElse("").trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Tez sinxron — realtime'ga yaqin: har 30 soniyada yangi/o'zgargan hujjatlar. */
    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT15S")
    public void moyskladSync() {
        try {
            syncService.sync();
        } catch (Exception e) {
            log.error("Sinxron xatosi: {}", e.getMessage(), e);
        }
    }

    /**
     * Chuqur solishtiruv — oxirgi N kun API bilan to'liq tekshiriladi:
     * o'chirilgan/bekor qilingan hujjatlar STORNO, tushib qolganlari yoziladi.
     * Ishga tushgandan 45 soniya o'tib birinchi marta — bot to'xtab turgan
     * davrdagi barcha o'zgarishlar shu yerdan tiklanadi.
     */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT45S")
    public void moyskladReconcile() {
        try {
            syncService.reconcile();
        } catch (Exception e) {
            log.error("Reconcile xatosi: {}", e.getMessage(), e);
        }
    }

    /**
     * Qarz eslatmalari: 09:00 dan keyin tekshiriladi, har eslatma kuniga bir marta —
     * tanlangan kunlarda, muddat kunida va muddati o'tganda yuboriladi.
     */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT2M")
    public void reminderTick() {
        try {
            reminderService.tick();
        } catch (Exception e) {
            log.warn("Eslatma tick xatosi: {}", e.getMessage());
        }
    }

    /**
     * ✅ Balans yaxlitligi: har bir balans qatorini operatsiyalar tarixidan qayta
     * hisoblab, saqlangan qiymat bilan solishtiradi — kod xatosi yoki qo'lda
     * (SQL) tuzatishdan qolgan nomuvofiqlikni ushlab, buxgalteriya/SuperAdmin'ga
     * xabar beradi. Mos bo'lsa — jim (spam bo'lmasin).
     */
    @Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT3M")
    public void ledgerIntegrity() {
        try {
            List<LedgerService.Mismatch> issues = ledger.verifyIntegrity();
            if (issues.isEmpty()) return;
            StringBuilder sb = new StringBuilder("⚠️ <b>Balans nomuvofiqligi topildi!</b>\n"
                    + "Saqlangan qiymat operatsiyalar tarixiga mos kelmayapti:\n");
            for (LedgerService.Mismatch m : issues) {
                String owner = m.ownerType() == OwnerType.BUXGALTERIYA
                        ? "Отдел Основной" : names.owner(m.ownerType(), m.ownerId());
                String mt = switch (m.moneyType()) {
                    case KLIK -> "📲 Klik"; case TERMINAL -> "💳 Terminal"; default -> "💵 Naqd";
                };
                sb.append("\n<b>").append(TextUtil.esc(owner)).append("</b> (").append(mt).append("): ")
                  .append("kutilgan ").append(TextUtil.fmt(m.expected()))
                  .append(" · haqiqiy ").append(TextUtil.fmt(m.actual()))
                  .append(" · farq <b>").append(TextUtil.fmt(m.diff())).append("</b> so'm");
            }
            notify.toRole(Role.SUPERADMIN, sb.toString(), null);
            log.warn("Balans nomuvofiqligi: {} ta qator", issues.size());
        } catch (Exception e) {
            log.error("Balans tekshiruvi xatosi: {}", e.getMessage(), e);
        }
    }

    /**
     * 📲 Click qoldiqlari — jadval bo'yicha ro'yxatdagi barcha guruh/kanallarga
     * yuboriladi. Cron har soat :00 da uyg'onadi, lekin haqiqiy yuborish admin
     * sozlagan interval (clickEvery) va soat oynasi (clickFrom..clickTo) ga qarab
     * o'tkaziladi yoki tashlab yuboriladi. Ro'yxat bo'sh bo'lsa — jim o'tadi.
     */
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Tashkent")
    public void clickHourlyReport() {
        int h = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Tashkent")).getHour();
        int from = clickFrom(), to = clickTo();
        if (h < from || h > to) return;
        if ((h - from) % clickEvery() != 0) return;
        clickReportNow();
    }

    /** Jadvalga qaramasdan darhol yuborish — 🧪 test tugmasi/buyrug'i uchun. */
    public void clickReportNow() {
        try {
            java.util.List<Long> chatIds = clickChatIds();
            if (chatIds.isEmpty()) return;
            List<ClickAccount> accounts = clickRepo.findByActiveTrueOrderByIdAsc();
            if (accounts.isEmpty()) return;

            StringBuilder sb = new StringBuilder("📲 <b>Click қолдиқлари</b>\n📅 "
                    + java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Tashkent"))
                            .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                    + "\n\n");
            long total = 0;
            for (ClickAccount c : accounts) {
                long bal = ledger.view(OwnerType.CLICK, c.getId(), MoneyType.KLIK).getAmount();
                total += bal;
                sb.append("• <b>").append(TextUtil.esc(c.getName())).append("</b>: ")
                  .append(TextUtil.fmt(bal)).append(" so'm\n");
            }
            sb.append("\n➕ <b>Жами: ").append(TextUtil.fmt(total)).append("</b> so'm");
            // Guruhda hech qanday menyu/klaviatura ko'rinmasligi kerak — faqat shu hisobot.
            // Bitta chatga yuborishda xato bo'lsa (bot chiqarilgan va h.k.) qolganlariga baribir ketadi.
            for (long chatId : chatIds) {
                var chat = sender.getChat(chatId);
                if (chat != null && chat.isChannelChat()) {
                    // Kanalda reply-klaviatura bo'lmaydi — Telegram markupli xabarni rad etadi.
                    sender.send(chatId, sb.toString());
                } else {
                    sender.send(chatId, sb.toString(),
                            org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove.builder()
                                    .removeKeyboard(true).build());
                }
            }
        } catch (Exception e) {
            log.warn("Click soatlik hisobot xatosi: {}", e.getMessage());
        }
    }

    /**
     * 📲 Click balans auditi: har bir Click hisobining MoySklad'dagi TO'LIQ tarix
     * bo'yicha haqiqiy qoldig'i qayta hisoblanadi va bot balansi bilan solishtiriladi —
     * farq (masalan MoySklad'da qo'lda korrektirovka qilingan bo'lsa) avtomatik
     * tuzatiladi. Og'ir operatsiya (butun tarix) — kam-kam ishga tushadi.
     */
    @Scheduled(fixedDelayString = "PT3H", initialDelayString = "PT5M")
    public void clickAccountAudit() {
        try {
            syncService.auditClickAccounts();
        } catch (Exception e) {
            log.warn("Click balans auditi xatosi: {}", e.getMessage());
        }
    }

    /** Google Sheets ikki tomonlama sinxron (sozlangan bo'lsa). */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT75S")
    public void googleSheets() {
        try {
            sheetsSync.sync();
        } catch (Exception e) {
            log.warn("Sheets sinxron xatosi: {}", e.getMessage());
        }
    }

    /** НАСТРОЙКА varaqlari (Foydalanuvchilar/Kassalar) — tez sikl, tahrir 1 daqiqada qo'llanadi. */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT45S")
    public void googleSheetsNastroyka() {
        try {
            sheetsSync.syncNastroyka();
        } catch (Exception e) {
            log.warn("Sheets tez sinxron xatosi: {}", e.getMessage());
        }
    }

    /** 00:00 Asia/Tashkent — o'tgan kunlarni yopish (TZ 7.2). */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Tashkent")
    public void closeDays() {
        try {
            List<DayRecord> closed = dayService.closeOpenDaysBefore(ledger.today());
            if (!closed.isEmpty()) log.info("Kun yopilishi: {} ta yozuv yopildi", closed.size());
        } catch (Exception e) {
            log.error("Kun yopishda xato: {}", e.getMessage(), e);
        }
    }

    /**
     * Kun yopilishini QOPLASH: 00:00 dagi cron faqat bot o'sha paytda ishlab
     * turganda otiladi — bot yarim tunda o'chiq bo'lsa, kunlar OCHIQ qolib,
     * kassir hisobot topshira olmay qolardi. Ishga tushgach 30 soniyada va
     * keyin har soatda o'tgan kunlar yopib boriladi (idempotent).
     */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT30S")
    public void closeDaysCatchup() {
        closeDays();
    }

    /** Har kuni app.reminder-hour (standart 21:00) — kassirlarga eslatma (TZ 7.2). */
    @Scheduled(cron = "0 0 ${app.reminder-hour:21} * * *", zone = "Asia/Tashkent")
    public void reminder() {
        LocalDate today = ledger.today();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            try {
                DayRecord d = dayRepo.findByKassaIdAndDate(k.getId(), today).orElse(null);
                List<DayRecord> pending = submissionService.submittableDays(k.getId());
                long availNaqd = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.NAQD).available();
                long availKlik = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.KLIK).available();

                boolean quiet = d == null && pending.isEmpty() && availNaqd == 0 && availKlik == 0;
                if (quiet) continue;

                StringBuilder sb = new StringBuilder("🔔 <b>Kunlik eslatma</b>\n\n");
                if (d != null) sb.append("Bugungi kirim: Naqd ").append(TextUtil.fmt(d.getPrixodNaqd()))
                        .append(" · Click ").append(TextUtil.fmt(d.getPrixodKlik()))
                        .append(" · Terminal ").append(TextUtil.fmt(d.getPrixodTerminal())).append(" so'm\n");
                if (!pending.isEmpty())
                    sb.append("Topshirilmagan kunlar: <b>").append(pending.size()).append("</b> ta\n");
                sb.append("Qo'lingizdagi qoldiq: Naqd ").append(TextUtil.fmt(availNaqd))
                        .append(" · Click ").append(TextUtil.fmt(availKlik)).append(" so'm");

                notify.toKassa(k.getId(), sb.toString(), null);
            } catch (Exception e) {
                log.warn("Eslatma ({}): {}", k.getName(), e.getMessage());
            }
        }
    }
}
