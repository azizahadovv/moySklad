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
import uz.kassa.service.NotifySwitches;
import uz.kassa.service.SubmissionService;
import uz.kassa.service.moysklad.MoySkladSyncService;

import java.time.LocalDate;
import java.util.List;

/** Rejalashtirilgan ishlar (TZ 12): sinxron 5 daq, kun yopilishi 00:00, eslatma 21:00. */
@Component
@RequiredArgsConstructor
@Slf4j
public class Jobs {

    private final uz.kassa.config.AppProps props;
    private final MoySkladSyncService syncService;
    private final uz.kassa.service.moysklad.MoySkladAuditService auditSvc;
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
    private final uz.kassa.repo.OperationRepo opRepo;
    private final uz.kassa.service.moysklad.MoySkladClient msClient;
    private final uz.kassa.service.SettingsService settings;
    private final NotifySwitches sw;
    private final uz.kassa.bot.Sender sender;
    private final uz.kassa.repo.AppUserRepo userRepo;
    private final uz.kassa.repo.GroupMemberRepo groupMemberRepo;
    private final uz.kassa.service.DailyReportService dailyReport;
    private final uz.kassa.service.notify.NotifyService notifySvc;
    private final uz.kassa.service.control.AgentCheckService agentCheckSvc;
    private final uz.kassa.service.control.ShipmentControlService shipmentSvc;
    private final uz.kassa.service.control.ControlConfig controlCfg;
    private final uz.kassa.service.control.EmployeeLinkService employeeLink;
    private final uz.kassa.service.control.ControlWelcomeService controlWelcome;
    private final uz.kassa.service.ombor.OmborSyncService omborSync;
    private final uz.kassa.service.ombor.OmborRuleEngine omborRules;
    private final uz.kassa.service.ombor.OmborSalesService omborSales;
    private final uz.kassa.service.ombor.OmborCalcService omborCalc;
    private final uz.kassa.service.ombor.OmborSanoqService omborSanoq;
    private final uz.kassa.service.ombor.OmborNarxService omborNarx;
    private final uz.kassa.service.ombor.OmborSorovService omborSorov;
    private final uz.kassa.service.ombor.OmborDraftService omborDraft;
    private volatile long lastBalanceTick = 0;

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

    /** Soat boshiga nisbatan MINUT siljishi (-20…+20, 5 ga karrali): +15 → 13:15, 14:15…;
     *  -10 → 12:50, 13:50… (nominal soat 13:00, 14:00 bo'lib qolaveradi). */
    public static final String CLICK_OFFSET_KEY = "notify.clickOffsetMin";
    public int clickOffsetMin() {
        int v = intSetting(CLICK_OFFSET_KEY, 0, -20, 20);
        return v - Math.floorMod(v, 5) + (Math.floorMod(v, 5) >= 3 ? 5 : 0);   // 5 ga yaxlitlash
    }

    /** Sozlamalar/hisobot uchun: «13:15» ko'rinishidagi misol vaqt. */
    public String clickTimeExample(int hour) {
        java.time.LocalTime t = java.time.LocalTime.of(hour, 0).plusMinutes(clickOffsetMin());
        return t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }

    /** Hisobot ostiga qo'shiladigan ixtiyoriy matn (masalan @username eslatmalar). */
    public static final String CLICK_FOOTER_KEY = "notify.clickFooter";

    public String clickFooter() { return settings.get(CLICK_FOOTER_KEY).orElse("").trim(); }

    /**
     * Footer HTML ko'rinishda, CHATGA XOS: @username Telegram'ning o'zida eslatma
     * (mention) bo'lib ketadi; {id=123456;Ism} — username'siz odamni ID orqali
     * belgilash; {adminlar} — shu guruh/kanal adminlari avtomatik; {xodimlar} —
     * botda ro'yxatdagi (faol, Telegram ulangan) va shu chatga A'ZO xodimlar
     * avtomatik. Mention faqat chat a'zolariga bildirishnoma beradi.
     */
    private String clickFooterHtml(long chatId) {
        String f = clickFooter();
        if (f.isEmpty()) return "";
        String out = TextUtil.esc(f);
        if (out.contains("{adminlar}")) {
            StringBuilder m = new StringBuilder();
            for (var u : sender.chatAdmins(chatId)) {
                if (u.getUserName() != null && !u.getUserName().isBlank())
                    m.append("@").append(u.getUserName()).append(" ");
                else m.append("<a href=\"tg://user?id=").append(u.getId()).append("\">")
                      .append(TextUtil.esc(u.getFirstName())).append("</a> ");
            }
            out = out.replace("{adminlar}", m.toString().trim());
        }
        if (out.contains("{hamma}")) {
            // Registrdagi (guruhda yozgan/qo'shilgan) hamma odam; entity limitidan
            // oshmaslik uchun 30 tadan keyin qisqartiriladi
            StringBuilder m = new StringBuilder();
            var members = groupMemberRepo.findByChatIdOrderByIdAsc(chatId);
            int shown = 0;
            for (var gm : members) {
                if (shown >= 30) break;
                if (gm.getUsername() != null && !gm.getUsername().isBlank())
                    m.append("@").append(gm.getUsername()).append(" ");
                else m.append("<a href=\"tg://user?id=").append(gm.getUserId()).append("\">")
                      .append(TextUtil.esc(gm.getFirstName() == null ? "user" : gm.getFirstName()))
                      .append("</a> ");
                shown++;
            }
            if (members.size() > shown) m.append("+").append(members.size() - shown).append(" boshqa");
            out = out.replace("{hamma}", m.toString().trim());
        }
        if (out.contains("{xodimlar}")) {
            StringBuilder m = new StringBuilder();
            for (var x : userRepo.findAll()) {
                if (!x.isActive() || x.getTelegramId() == null) continue;
                String st = sender.memberStatus(chatId, x.getTelegramId());
                if (!"member".equals(st) && !"administrator".equals(st) && !"creator".equals(st))
                    continue;
                m.append("<a href=\"tg://user?id=").append(x.getTelegramId()).append("\">")
                 .append(TextUtil.esc(x.getFullName())).append("</a> ");
            }
            out = out.replace("{xodimlar}", m.toString().trim());
        }
        out = out.replaceAll("\\{id=(\\d+);([^}]+)\\}", "<a href=\"tg://user?id=$1\">$2</a>");
        return "\n\n" + out;
    }

    private int intSetting(String key, int def, int min, int max) {
        try {
            int v = Integer.parseInt(settings.get(key).orElse("").trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * Ketma-ket xatolar hisoblagichi (T1): bitta rejali ish 10 marta KETMA-KET
     * yiqilsa — SuperAdmin'ga bir martalik ogohlantirish (ilgari doimiy buzilgan
     * job faqat log yozib, hech kim bilmay qolardi). Muvaffaqiyatda nolga qaytadi.
     */
    private final java.util.Map<String, Integer> jobFails =
            new java.util.concurrent.ConcurrentHashMap<>();

    private void jobOk(String job) { jobFails.remove(job); }

    private void jobFail(String job, Exception e) {
        int n = jobFails.merge(job, 1, Integer::sum);
        if (n == 10)
            notify.toRole(NotifySwitches.TEXNIK_OGOH, Role.SUPERADMIN, "🚨 <b>" + job + "</b> ishi 10 marta KETMA-KET "
                    + "xato bermoqda — tizim qisman ishlamayapti!\nOxirgi xato: "
                    + TextUtil.esc(String.valueOf(e.getMessage())), null);
    }

    /** Tez sinxron — realtime'ga yaqin: har 30 soniyada yangi/o'zgargan hujjatlar. */
    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT15S")
    public void moyskladSync() {
        try {
            syncService.sync();
            jobOk("MoySklad sinxron");
        } catch (Exception e) {
            jobFail("MoySklad sinxron", e);
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
            jobOk("MoySklad tekshiruv (reconcile)");
        } catch (Exception e) {
            jobFail("MoySklad tekshiruv (reconcile)", e);
            log.error("Reconcile xatosi: {}", e.getMessage(), e);
        }
    }

    /**
     * Qarz eslatmalari: 09:00 dan keyin tekshiriladi, har eslatma kuniga bir marta —
     * tanlangan kunlarda, muddat kunida va muddati o'tganda (3 kunda bir) yuboriladi.
     * Kontragent balans sinxroni faqat ish soatlarida (07:00–22:00) — tunda MoySklad'ni
     * har 10 daqiqada so'rashning ma'nosi yo'q (T4).
     */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT2M")
    public void reminderTick() {
        try {
            int h = java.time.LocalTime.now(props.zoneId()).getHour();
            if (h >= 7 && h < 22) reminderService.syncFromMoySklad();
        } catch (Exception e) {
            log.warn("Eslatma balans sinxroni xatosi: {}", e.getMessage());
        }
        try {
            reminderService.tick();
        } catch (Exception e) {
            log.warn("Eslatma tick xatosi: {}", e.getMessage());
        }
    }

    /** kassaId → {farq, ketma-ket necha tekshiruvda ko'rildi, oxirgi xabar vaqti (ms)}. */
    private final java.util.Map<Long, long[]> dayMismatchSeen = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DAY_ALERT_REPEAT_MS = 6 * 3600_000L;

    /**
     * Kunlar kesimi ≠ balans: sinxron/pul qabul o'rtasidagi bir lahzalik holat xabar bo'lmasin —
     * faqat BIR XIL farq ketma-ket 2 tekshiruvda (30 daqiqa) turganda; takror xabar 6 soatda bir;
     * tuzalganda «✅» bir marta. Xabarda qaysi kunlarda qoldiq turgani ko'rsatiladi.
     */
    private void dayMismatchAlert(List<LedgerService.DayMismatch> dayIssues) {
        long now = System.currentTimeMillis();
        java.util.Set<Long> current = new java.util.HashSet<>();
        StringBuilder sb = new StringBuilder();
        int alerts = 0;
        for (LedgerService.DayMismatch m : dayIssues) {
            current.add(m.kassaId());
            long[] st = dayMismatchSeen.get(m.kassaId());
            if (st == null || st[0] != m.diff()) { st = new long[]{m.diff(), 1, 0}; dayMismatchSeen.put(m.kassaId(), st); continue; }
            st[1]++;
            if (st[1] < 2) continue;
            if (st[2] != 0 && now - st[2] < DAY_ALERT_REPEAT_MS) continue;
            st[2] = now;
            alerts++;
            sb.append("\n<b>").append(TextUtil.esc(names.owner(OwnerType.KASSA, m.kassaId())))
              .append("</b>: balans ").append(TextUtil.fmt(m.balance()))
              .append(" · kunlar ").append(TextUtil.fmt(m.daysRemain()))
              .append(" · farq <b>").append(TextUtil.fmt(m.diff())).append("</b> so'm\n");
            int n = 0;
            for (uz.kassa.domain.DayRecord d : dayRepo.findByKassaIdAndStatusInOrderByDateAsc(m.kassaId(),
                    List.of(uz.kassa.domain.DayStatus.OCHIQ, uz.kassa.domain.DayStatus.YOPILGAN))) {
                if (d.remainNaqd() == 0) continue;
                if (++n > 8) { sb.append("   …\n"); break; }
                sb.append("   • ").append(d.getDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM")))
                  .append(": ").append(TextUtil.fmt(d.remainNaqd())).append(" so'm topshirilmagan\n");
            }
        }
        // tuzalganlar — bir marta ✅ (faqat oldin xabar berilgan bo'lsa)
        for (var it = dayMismatchSeen.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (current.contains(e.getKey())) continue;
            if (e.getValue()[2] != 0)
                notify.toRole(NotifySwitches.BAL_NOMUVOFIQ, Role.SUPERADMIN, "✅ <b>" + TextUtil.esc(names.owner(OwnerType.KASSA, e.getKey()))
                        + "</b>: kunlar kesimi balansga mos keldi.", null);
            it.remove();
        }
        if (alerts == 0) return;
        notify.toRole(NotifySwitches.BAL_NOMUVOFIQ, Role.SUPERADMIN, "⚠️ <b>Kunlar kesimi balansga mos emas!</b>\n"
                + "Kassa naqd balansi va kunlar qoldig'i yig'indisi 30 daqiqadan beri farq qiladi "
                + "(pul qabulida kunlarga tushmagan qoldiq yoki yo'qolgan yozuv):\n" + sb
                + "\nTuzatish: 🛠 Корректировка — farq summasini o'sha kun sanasi bilan kiriting "
                + "(shunda ham balans, ham o'sha kun birga o'zgaradi), yoki ♻️ Нол бошлаш. "
                + "Tekshiruv 30 daqiqada bir; tuzalsa «✅» keladi.", null);
        log.warn("Kunlar kesimi nomuvofiqligi: {} ta kassa", alerts);
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
            List<LedgerService.DayMismatch> dayIssues = ledger.verifyDays();
            if (issues.isEmpty() && dayIssues.isEmpty()) return;
            if (issues.isEmpty()) {
                dayMismatchAlert(dayIssues);
                return;
            }
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
                // «Aybdorni topish» yordami: oxirgi 14 kunda aynan |farq| summali
                // operatsiyalar — ehtimoliy sabab sifatida ko'rsatiladi (evristika)
                long ad = Math.abs(m.diff());
                int cn = 0;
                for (Operation o : opRepo.balanceOpsAfter(
                        m.ownerType(), m.ownerId(), m.moneyType(), ledger.today().minusDays(14))) {
                    if (o.getAmount() != ad) continue;
                    if (cn++ >= 3) { sb.append("\n   …yana bor"); break; }
                    sb.append("\n   ↳ ehtimoliy sabab: #").append(o.getId()).append(" ")
                      .append(o.getType()).append(" ").append(TextUtil.fmt(o.getAmount()))
                      .append(" so'm · ").append(o.getOpDate())
                      .append(o.getMoyskladId() == null ? " (qo'lda)" : " (MoySklad)");
                }
            }
            notify.toRole(NotifySwitches.BAL_NOMUVOFIQ, Role.SUPERADMIN, sb.toString(), null);
            log.warn("Balans nomuvofiqligi: {} ta qator", issues.size());
        } catch (Exception e) {
            log.error("Balans tekshiruvi xatosi: {}", e.getMessage(), e);
        }
    }

    /**
     * 📲 Click qoldiqlari — jadval bo'yicha ro'yxatdagi barcha guruh/kanallarga
     * yuboriladi. Cron har 5 daqiqada uyg'onadi; hozirgi vaqtdan minut siljishi
     * (clickOffsetMin) ayrilganda ROPPA-ROSA soat boshi chiqsa — o'sha NOMINAL soat
     * interval (clickEvery) va soat oynasi (clickFrom..clickTo) bo'yicha tekshiriladi.
     * Masalan siljish +15: 13:15 da nominal 13:00; siljish -10: 12:50 da nominal 13:00.
     * Ro'yxat bo'sh bo'lsa — jim o'tadi.
     */
    /** 📋 Kunlik kassa solishtirish hisoboti — sozlangan vaqtda (standart 22:00) bir marta. */
    @Scheduled(cron = "0 */5 * * * *", zone = "${app.zone:Asia/Tashkent}")
    public void dailyReportTick() {
        try { dailyReport.tick(); }
        catch (Exception e) { log.warn("Kunlik hisobot xatosi: {}", e.getMessage()); }
    }

    @Scheduled(cron = "0 */5 * * * *", zone = "${app.zone:Asia/Tashkent}")
    public void clickHourlyReport() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(props.zoneId());
        java.time.LocalDateTime nominal = now.minusMinutes(clickOffsetMin());
        if (nominal.getMinute() != 0) return;   // bu 5 daqiqalik uyg'onish bizniki emas
        int h = nominal.getHour();
        int from = clickFrom(), to = clickTo();
        if (h < from || h > to) return;
        if ((h - from) % clickEvery() != 0) return;
        if (!sw.on(NotifySwitches.CLICK_SOATLIK)) { log.info("Click hisobot: 🔕 o'chirilgan (Хабарномалар)"); return; }
        log.info("Click hisobot: nominal {}:00 (siljish {} min) — yuborilmoqda", h, clickOffsetMin());
        clickReportNow();
    }

    /** Jadvalga qaramasdan darhol yuborish — 🧪 test tugmasi/buyrug'i uchun. */
    public void clickReportNow() {
        try {
            java.util.List<Long> chatIds = clickChatIds();
            if (chatIds.isEmpty()) return;
            List<ClickAccount> accounts = clickRepo.findByActiveTrueOrderByIdAsc();
            if (accounts.isEmpty()) return;

            // MoySklad joriy qoldiqlari — har qator yonida AVTOMATIK solishtiruv uchun
            // (✅ teng / ⚠️ farq). O'qib bo'lmasa hisobot belgisiz chiqaveradi.
            java.util.Map<String, Long> ms;
            try { ms = msClient.fetchAccountBalancesTiyin(); }   // TIYINDA — tiyin farqi ham ko'rinsin
            catch (Exception e) { ms = java.util.Map.of(); }

            var zone = props.zoneId();
            var nowDt = java.time.LocalDateTime.now(zone);
            StringBuilder sb = new StringBuilder();
            sb.append("📲 <b>CLICK ҚОЛДИҚЛАРИ</b>\n")
              .append("📅 ").append(nowDt.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")))
              .append("  🕐 ").append(nowDt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))).append("\n")
              .append(RULE_TOP).append("\n\n");

            // Otdel (kassa) kesimida guruhlab chiqariladi; bog'lanmaganlar — «Бошқа»
            int[] stat = new int[3];   // 0 — тенг, 1 — фарқ, 2 — киритилмаган
            java.util.Set<Long> shown = new java.util.HashSet<>();
            boolean firstSection = true;
            for (uz.kassa.domain.Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
                List<ClickAccount> mine = accounts.stream()
                        .filter(c -> k.getId().equals(c.getKassaId())).toList();
                if (mine.isEmpty()) continue;
                if (!firstSection) sb.append(RULE_MID).append("\n");
                firstSection = false;
                sb.append("🏪 <b>").append(TextUtil.esc(k.getName().toUpperCase())).append("</b>");
                if (k.getShopLabel() != null && !k.getShopLabel().isBlank())
                    sb.append("  |  <i>").append(TextUtil.esc(k.getShopLabel())).append("</i>");
                sb.append("\n\n");
                for (ClickAccount c : mine) {
                    long bal = ledger.view(OwnerType.CLICK, c.getId(), MoneyType.KLIK).getAmount();
                    shown.add(c.getId());
                    sb.append(cardBlock(ms, c, bal, stat)).append("\n");
                }
            }
            List<ClickAccount> rest = accounts.stream()
                    .filter(c -> !shown.contains(c.getId())).toList();
            if (!rest.isEmpty()) {
                if (!firstSection) sb.append(RULE_MID).append("\n");
                sb.append("📌 <b>БОШҚА</b>\n\n");
                for (ClickAccount c : rest) {
                    long bal = ledger.view(OwnerType.CLICK, c.getId(), MoneyType.KLIK).getAmount();
                    sb.append(cardBlock(ms, c, bal, stat)).append("\n");
                }
            }
            sb.append(RULE_TOP).append("\n")
              .append("📊 <b>ХУЛОСА:</b>  ✅ тенг — ").append(stat[0])
              .append("   ⚠️ фарқ — ").append(stat[1])
              .append("   ❗️ киритилмаган — ").append(stat[2]).append("\n\n");
            // Foydalanuvchi qarori: ost qismda ЖАМИ ham KO'RSATILMAYDI — u bot
            // balanslari yig'indisi bo'lib, qatorlardagi MoySklad qiymatlari bilan
            // manba jihatdan farq qilib chalg'itardi (masalan 200 002 farq hodisasi).
            // Hisobot faqat kartalar kesimida.
            // Guruhda hech qanday menyu/klaviatura ko'rinmasligi kerak — faqat shu hisobot.
            // Bitta chatga yuborishda xato bo'lsa (bot chiqarilgan va h.k.) qolganlariga baribir ketadi.
            for (long chatId : chatIds) {
                // Ost matn chatga xos: {adminlar}/{xodimlar} shu chat bo'yicha hisoblanadi
                String footer = clickFooterHtml(chatId);
                String text = sb.toString()
                        + (footer.isEmpty() ? "" : "📣 <b>Карта қолдиқларини юборинг!</b>\n" + footer);
                var chat = sender.getChat(chatId);
                if (chat != null && chat.isChannelChat()) {
                    // Kanalda reply-klaviatura bo'lmaydi — Telegram markupli xabarni rad etadi.
                    sender.send(chatId, text);
                } else {
                    sender.send(chatId, text,
                            org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove.builder()
                                    .removeKeyboard(true).build());
                }
            }
        } catch (Exception e) {
            log.warn("Click soatlik hisobot xatosi: {}", e.getMessage());
        }
    }

    private static final java.time.format.DateTimeFormatter CARD_TF =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final String RULE_TOP = "━━━━━━━━━━━━━━━━━━━━";
    private static final String RULE_MID = "──────────────────────";

    /**
     * Bitta karta bloki (foydalanuvchi shabloni, 02.09.2026):
     *   💳 <b>Karta</b> — mas'ul
     *   📦 Мой склад қолдиғи HH:mm ҳолатида: <b>summa</b>
     *   💳 Карта қолдиғи dd.MM HH:mm ҳолатида: <b>summa</b> (kim ✔)
     *   ✅ Фарқ: 0 — тенг   |   ⚠️ Фарқ: X — Карта остаткасини юборинг ва текширинг!
     *   ❗️ Карта қолдиғи: киритилмаган — ... (/karta id СУММА)
     * stat[] — xulosa hisoblagichi: 0 тенг, 1 фарқ, 2 киритилмаган.
     */
    private String cardBlock(java.util.Map<String, Long> ms, ClickAccount c, long botBal, int[] stat) {
        var zone = props.zoneId();
        String now = java.time.LocalTime.now(zone)
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        StringBuilder b = new StringBuilder();
        b.append("💳 <b>").append(TextUtil.esc(c.getName())).append("</b>");
        if (c.getCardResponsible() != null && !c.getCardResponsible().isBlank())
            b.append(" — ").append(mention(c.getCardResponsible()));
        b.append("\n");
        String aid = c.getMoyskladAccountId();
        Long msv = (aid == null || aid.isBlank()) ? null : ms.get(aid);
        long msShown = msv != null ? msv : botBal * 100;   // TIYIN; MS o'qilmasa — bot (so'm) qiymati
        b.append("📦 Мой склад қолдиғи ").append(now).append(" ҳолатида: <b>")
         .append(TextUtil.fmtTiyin(msShown)).append("</b>\n");
        if (c.getCardBalance() == null) {
            stat[2]++;
            b.append("❗️ Карта қолдиғи: <b>киритилмаган</b>\n")
             .append("➡️ Карта остаткасини юборинг ва текширинг! ")
             .append("(<code>/karta ").append(c.getId()).append(" СУММА</code>)\n");
        } else {
            long kartaBal = c.getCardBalance();   // tiyin
            String at = c.getCardBalanceAt() == null ? "?"
                    : java.time.LocalDateTime.ofInstant(c.getCardBalanceAt(), zone).format(CARD_TF);
            String by = c.getCardBalanceBy() == null ? ""
                    : TextUtil.esc(c.getCardBalanceBy().replace("(tasdiqlangan)", "").trim()) + " ✔";
            b.append("💳 Карта қолдиғи ").append(at).append(" ҳолатида: <b>")
             .append(TextUtil.fmtTiyin(kartaBal)).append("</b>")
             .append(by.isEmpty() ? "" : " <i>(" + by + ")</i>")
             .append("\n");
            long farq = msShown - kartaBal;
            if (farq == 0) {
                stat[0]++;
                b.append("✅ Фарқ: <b>0</b> — тенг\n");
            } else {
                stat[1]++;
                b.append("⚠️ Фарқ: <b>").append(farq > 0 ? "+" : "").append(TextUtil.fmtTiyin(farq))
                 .append("</b> — Карта остаткасини юборинг ва текширинг!\n");
            }
        }
        return b.toString();
    }

    /** Mas'ul matnini mention'ga aylantirish: @username o'zi ishlaydi, {id=..;Ism} — havola. */
    private String mention(String r) {
        String out = TextUtil.esc(r.trim());
        return out.replaceAll("\\{id=(\\d+);([^}]+)\\}", "<a href=\"tg://user?id=$1\">$2</a>");
    }

    /**
     * 🔄 MoySklad bilan tenglashtirish: har soatda barcha Click hisoblari
     * MoySklad'ning joriy qoldiqlariga tenglashtiriladi. NAQD tenglashtirilMAYDI
     * (2026-09-02 dan o'chirildi — Основной/kassa qoldiqlarini buzar edi): jami naqd
     * MoySklad CASH bilan faqat SOLISHTIRILADI, farq bo'lsa sabab + summa + tuzatish
     * yo'li bilan xabar boradi (auditNaqd). Farq yo'q — jim.
     */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void clickAccountAudit() {
        try {
            auditSvc.auditClickAccounts();
        } catch (Exception e) {
            log.warn("Click balans auditi xatosi: {}", e.getMessage());
        }
        try {
            auditSvc.auditNaqd();   // faqat xabar: sabab + summa + tuzatish yo'li
        } catch (Exception e) {
            log.warn("Naqd tekshiruvi xatosi: {}", e.getMessage());
        }
    }

    /** Google Sheets ikki tomonlama sinxron (sozlangan bo'lsa). */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT75S")
    public void googleSheets() {
        try {
            sheetsSync.sync();
            jobOk("Google Sheets sinxron");
        } catch (Exception e) {
            jobFail("Google Sheets sinxron", e);
            log.warn("Sheets sinxron xatosi: {}", e.getMessage());
        }
    }

    /** НАСТРОЙКА varaqlari (Foydalanuvchilar/Kassalar) — tez sikl, tahrir 1 daqiqada qo'llanadi. */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT45S")
    public void googleSheetsNastroyka() {
        try {
            sheetsSync.syncNastroyka();
            jobOk("Sheets НАСТРОЙКА sinxron");
        } catch (Exception e) {
            jobFail("Sheets НАСТРОЙКА sinxron", e);
            log.warn("Sheets tez sinxron xatosi: {}", e.getMessage());
        }
    }

    /** 00:00 Asia/Tashkent — o'tgan kunlarni yopish (TZ 7.2). */
    @Scheduled(cron = "0 0 0 * * *", zone = "${app.zone:Asia/Tashkent}")
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

    /** 🔔 Bildirishnomalar (shablonli, jadvalli) — har daqiqa tekshiriladi. */
    @Scheduled(cron = "0 * * * * *", zone = "${app.zone:Asia/Tashkent}")
    public void notifyTick() {
        try { notifySvc.tick(); }
        catch (Exception e) { log.warn("Bildirishnoma tick xatosi: {}", e.getMessage()); }
    }

    /** Avto-o'chirish navbati (tasdiq xabarlari, bildirishnomalar) — har 30 soniyada. */
    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT20S")
    public void pendingDeleteTick() {
        try { notifySvc.deleteTick(); }
        catch (Exception e) { log.debug("Avto-o'chirish xatosi: {}", e.getMessage()); }
    }

    /** Har kuni app.reminder-hour (standart 21:00) — kassirlarga eslatma (TZ 7.2). */
    @Scheduled(cron = "0 0 ${app.reminder-hour:21} * * *", zone = "${app.zone:Asia/Tashkent}")
    public void reminder() {
        if (!sw.on(NotifySwitches.KASSIR_ESLATMA)) return;   // 🔕 Хабарномалар
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

                notify.toKassa(NotifySwitches.KASSIR_ESLATMA, k.getId(), sb.toString(), null);
            } catch (Exception e) {
                log.warn("Eslatma ({}): {}", k.getName(), e.getMessage());
            }
        }
    }

    /* ==================== 🕵️ КОНТРАГЕНТ НАЗОРАТИ ==================== */

    /** A-modul: yangi/o'zgargan kontragentlar — qoidalar, xabar, eskalatsiya (har 2 daqiqa). */
    @Scheduled(fixedDelayString = "PT2M", initialDelayString = "PT90S")
    public void controlAgents() {
        try { agentCheckSvc.tick(); }
        catch (Exception e) { log.warn("Kontragent nazorati xatosi: {}", e.getMessage()); }
    }

    /** B-modul: otgruzkalar — 2 soat tekshiruvi, QARZ/YOPILDI/BEKOR (har 2 daqiqa). */
    @Scheduled(fixedDelayString = "PT2M", initialDelayString = "PT100S")
    public void controlShipments() {
        try { shipmentSvc.tick(); }
        catch (Exception e) { log.warn("Otgruzka nazorati xatosi: {}", e.getMessage()); }
    }

    /** Telegram'ga endigina ulangan xodimga ochiq xatolari/qarzdorlari bir marta (har 2 daqiqa). */
    @Scheduled(fixedDelayString = "PT2M", initialDelayString = "PT2M")
    public void controlWelcome() {
        try { controlWelcome.tick(); }
        catch (Exception e) { log.warn("Nazorat xush kelibsiz xabari xatosi: {}", e.getMessage()); }
    }

    /** Qarzdorlar balansi — control.check_min (standart 20) daqiqada bir. */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT3M")
    public void controlBalances() {
        long now = System.currentTimeMillis();
        if (now - lastBalanceTick < controlCfg.checkMin() * 60_000L) return;
        lastBalanceTick = now;
        try { shipmentSvc.balanceTick(); }
        catch (Exception e) { log.warn("Qarz balans tekshiruvi xatosi: {}", e.getMessage()); }
    }

    /** 👔 MoySklad xodimlari → bot: otdelga avtomatik bo'lish, yangi xodim, rahbar (soatda bir). */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
    public void employeeSync() {
        if (!controlCfg.enabled()) return;
        try { employeeLink.syncEmployees(true, null); }
        catch (Exception e) { log.warn("Xodimlar sinxroni xatosi: {}", e.getMessage()); }
        try { employeeLink.reconcileLinks(); }
        catch (Exception e) { log.warn("Xodim yozuvlarini tekislash xatosi: {}", e.getMessage()); }
    }

    /** Kunlik jamlamalar (daily_time dan keyin, kuniga bir marta). */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT4M")
    public void controlDaily() {
        try { shipmentSvc.dailyTick(); }
        catch (Exception e) { log.warn("Qarz kunlik jamlama xatosi: {}", e.getMessage()); }
        try { shipmentSvc.remindTick(); }
        catch (Exception e) { log.warn("Qarzdor eslatmalari xatosi: {}", e.getMessage()); }
        try { agentCheckSvc.dailyTick(); }
        catch (Exception e) { log.warn("Kontragent kunlik jamlama xatosi: {}", e.getMessage()); }
    }

    /* ==================== 🏬 ОМБОР ==================== */

    /** Omborlar + tovarlar (inkremental, har 10 daqiqa). */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT150S")
    public void omborSync() {
        try { omborSync.tick(); jobOk("Ombor sinxron"); }
        catch (Exception e) { jobFail("Ombor sinxron", e); log.warn("Ombor sinxron xatosi: {}", e.getMessage()); }
    }

    /** Qoldiqlar (report/stock) — ombor.stock_time dan keyin kuniga bir marta (har 15 daqiqada tekshiriladi). */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT4M")
    public void omborStock() {
        boolean ran = false;
        try { ran = omborSync.stockTick(false); }
        catch (Exception e) { log.warn("Ombor qoldiq sinxroni xatosi: {}", e.getMessage()); }
        if (!ran) return;
        // tunlik zanjir: qoldiq → kecha/bugun sotuv → hisoblar (ABC, ROP, fill) → sanoq rejasi → hamkorlar
        try { omborSales.refreshRecent(); } catch (Exception e) { log.warn("Ombor sotuv (kecha/bugun): {}", e.getMessage()); }
        try { omborCalc.nightly(); } catch (Exception e) { log.warn("Ombor tunlik hisob: {}", e.getMessage()); }
        try { omborSanoq.planToday(); } catch (Exception e) { log.warn("Ombor sanoq rejasi: {}", e.getMessage()); }
        try { omborNarx.fromSupplies(); } catch (Exception e) { log.warn("Ombor narx (priyomka): {}", e.getMessage()); }
        try { omborNarx.pullSheets(null); } catch (Exception e) { log.warn("Ombor Sheets: {}", e.getMessage()); }
        try { var ids = omborSync.hamkorIds(); if (!ids.isEmpty()) omborSorov.hamkorIntervals(ids); } catch (Exception e) { log.warn("Ombor hamkorlar: {}", e.getMessage()); }
        try { omborRules.tick(); } catch (Exception e) { log.warn("Ombor qoidalar (tun): {}", e.getMessage()); }
        try { omborDraft.buildAll(); } catch (Exception e) { log.warn("Ombor qoralama: {}", e.getMessage()); }
    }

    /** Sotuv tarixini orqaga yuklash (kursor tugaguncha, har 15 daqiqada 20 kun). */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT6M")
    public void omborSalesBackfill() {
        try { if (!omborSales.backfillDone()) omborSales.backfillTick(); }
        catch (Exception e) { log.warn("Ombor sotuv tarixi xatosi: {}", e.getMessage()); }
    }

    /** Qoidalar → kamchiliklar → xabar → eskalatsiya (har 5 daqiqa). */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
    public void omborRules() {
        try { omborRules.tick(); jobOk("Ombor qoidalar"); }
        catch (Exception e) { jobFail("Ombor qoidalar", e); log.warn("Ombor qoidalar xatosi: {}", e.getMessage()); }
    }
}
