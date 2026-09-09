package uz.kassa.service.control;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Reminder;
import uz.kassa.domain.Role;
import uz.kassa.domain.Shipment;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.ReminderRepo;
import uz.kassa.repo.ShipmentRepo;
import uz.kassa.service.AuditService;
import uz.kassa.service.moysklad.MoySkladClient;
import uz.kassa.service.moysklad.MoySkladClient.MsDemand;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.esc;
import static uz.kassa.bot.TextUtil.fmt;

/**
 * B-modul: otgruzka to'lov nazorati (docs/KONTRAGENT-NAZORAT.md §3).
 *   T1 to'liq to'langan — jim; T2 kutish oynasi (created + grace_min);
 *   T3 tekshiruv: payedSum → balans → QARZ + xabar; T4 «Карз» statusi — darhol;
 *   T6 har check_min daqiqada balans; T7 kunlik jamlama; T8 qo'lda yopish; T9 bekor; T10 eski qarzlar.
 * Bot MoySklad'ga HECH NARSA YOZMAYDI.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShipmentControlService {

    static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final ShipmentRepo repo;
    private final ReminderRepo reminderRepo;
    private final AppUserRepo userRepo;
    private final MoySkladClient msClient;
    private final ControlConfig cfg;
    private final EmployeeLinkService link;
    private final ControlNotifier notifier;
    private final AuditService audit;

    /** balanceTick'da QARZ otgruzkalarni navbat bilan qayta o'qish (o'chirilganini sezish). */
    private int refreshCursor = 0;

    /** Bitta qulf: sxeduler oqimlari to'planib qolmasin — band bo'lsa tick/balanceTick o'tkazib yuboriladi
     *  (eski qarzlarni yuklash bir necha daqiqa olishi mumkin). UI amallari kutadi. */
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();


    /* ==================== POLLING (har 2 daqiqa) ==================== */

    public void tick() {
        if (!cfg.enabled()) return;
        String token = msClient.currentToken();
        if (token == null || token.isBlank()) return;
        if (!lock.tryLock()) return;   // oldingi tick (masalan eski qarzlar yuklanishi) hali tugamagan
        try { tickLocked(); } finally { lock.unlock(); }
    }


    private void tickLocked() {
        initialLoadIfNeeded();
        if (cfg.get(ControlConfig.ISSUES_BACKFILLED).isEmpty()) {   // V25: mavjud qarzlar uchun bir marta
            reevaluateAllIssues();
            cfg.set(ControlConfig.ISSUES_BACKFILLED, "1");
        }

        LocalDateTime now = LocalDateTime.now(cfg.zone());
        LocalDateTime from = cfg.lastDemandSync().orElse(now.minusHours(1)).minusMinutes(2);
        List<MsDemand> list;
        try {
            list = msClient.fetchDemandsUpdated(from);
        } catch (Exception e) {
            log.warn("Otgruzka nazorati: MoySklad o'qilmadi: {}", e.getMessage());
            return;
        }
        int n = 0;
        for (MsDemand d : list) {
            try { apply(d, false, null, false); n++; }
            catch (Exception e) { log.warn("Otgruzka №{} qayta ishlashda xato: {}", d.docNo(), e.getMessage()); }
        }
        cfg.set(ControlConfig.LAST_DEMAND_SYNC, now);
        if (n > 0) log.debug("Otgruzka nazorati: {} ta hujjat yangilandi", n);
        dueChecks(true);
        notifyIssues();
        escalateIssues();
    }


    /** Eski qarzlar (control.since dan): bir marta, XABARSIZ (silent). */
    private void initialLoadIfNeeded() {
        LocalDate since = cfg.since();
        if (since.toString().equals(cfg.get(ControlConfig.LOADED_SINCE).orElse(""))) return;
        LocalDateTime now = LocalDateTime.now(cfg.zone());
        log.info("Otgruzka nazorati: eski qarzlar yuklanmoqda ({} dan) …", since);
        try {
            LocalDateTime from = since.atStartOfDay();
            int total = 0;
            while (from.isBefore(now)) {
                LocalDateTime to = from.plusMonths(1).minusSeconds(1);
                if (to.isAfter(now)) to = now;
                for (MsDemand d : msClient.fetchDemandsByMoment(from, to)) {
                    try { apply(d, true, null, true); total++; }
                    catch (Exception e) { log.warn("Eski otgruzka №{}: {}", d.docNo(), e.getMessage()); }
                }
                from = from.plusMonths(1);
            }
            dueChecks(false);   // balanslar to'plam bilan, hujjatlar qayta o'qilmaydi
            cfg.set(ControlConfig.LOADED_SINCE, since.toString());
            cfg.set(ControlConfig.LAST_DEMAND_SYNC, now);
            log.info("Otgruzka nazorati: {} ta eski otgruzka yuklandi, qarzda {} ta", total,
                    repo.countByControlStatus(Shipment.Status.QARZ));
        } catch (Exception e) {
            log.warn("Eski qarzlarni yuklash uzildi (keyingi siklda davom etadi): {}", e.getMessage());
        }
    }


    /**
     * Bitta otgruzkani bot yozuviga qo'llash. silent — xabar yo'q (eski qarz);
     * balances — oldindan olingan balanslar (to'plam); defer — qarorni dueChecks'ga qoldirish.
     */
    void apply(MsDemand d, boolean silent, Map<String, Long> balances, boolean defer) {
        Shipment s = repo.findByMsId(d.id()).orElse(null);
        boolean isNew = s == null;
        if (isNew) {
            if (!d.applicable()) return;
            s = Shipment.builder().msId(d.id()).silent(silent).build();
        }
        fill(s, d);
        Instant now = Instant.now();

        if (!d.applicable()) {
            if (s.getControlStatus() == Shipment.Status.KUTILMOQDA || s.getControlStatus() == Shipment.Status.QARZ)
                cancel(s, "MoySklad'da o'tkazilmagan (Проведено olib tashlangan)", silent);
            else if (s.getControlStatus() == Shipment.Status.TOLANGAN) { s.setControlStatus(Shipment.Status.BEKOR); repo.save(s); }
            else repo.save(s);
            updateIssues(s);
            return;
        }
        boolean paid = s.getPayedSum() >= s.getSum();
        switch (s.getControlStatus()) {
            case KUTILMOQDA -> {
                if (paid) { s.setControlStatus(Shipment.Status.TOLANGAN); s.setCheckAt(null); repo.save(s); }
                else {
                    Instant due = graceEnd(s);
                    if (defer) { s.setCheckAt(now.isAfter(due) || s.isDebtState() ? now : due); repo.save(s); }
                    else if (s.isDebtState() || !now.isBefore(due)) decide(s, balances, silent);
                    else { s.setCheckAt(due); repo.save(s); }
                }
            }
            case TOLANGAN -> {
                if (!paid) {   // to'lov o'chirilgan/uzilgan — qayta kutishga
                    s.setControlStatus(Shipment.Status.KUTILMOQDA);
                    Instant due = graceEnd(s);
                    s.setCheckAt(now.isAfter(due) ? now.plusSeconds(60) : due);
                }
                repo.save(s);
            }
            case QARZ -> {
                if (paid) close(s, "MoySklad: otgruzka to'lovi to'liq", null, false);
                else { syncReminder(s); repo.save(s); }
            }
            case BEKOR -> {
                if (!paid) {   // qayta o'tkazilgan
                    s.setControlStatus(Shipment.Status.KUTILMOQDA);
                    s.setCheckAt(now.plusSeconds(60L * cfg.graceMin()));
                } else s.setControlStatus(Shipment.Status.TOLANGAN);
                repo.save(s);
            }
            case YOPILDI -> repo.save(s);
        }
        updateIssues(s);
    }


    private void fill(Shipment s, MsDemand d) {
        s.setDocNo(cut(d.docNo(), 80));
        s.setMoment(d.moment());
        s.setMsCreated(d.created());
        s.setMsUpdated(d.updated());
        s.setAgentMsId(d.agentId());
        s.setAgentName(cut(d.agentName(), 400));
        s.setAgentPhone(cut(d.agentPhone(), 160));
        if (d.agentType() != null && !d.agentType().isBlank()) s.setAgentType(cut(d.agentType(), 20));
        s.setOwnerMsId(d.ownerId());
        s.setOwnerUid(cut(d.ownerUid(), 80));
        s.setOwnerName(cut(d.ownerName(), 200));
        s.setMasul(cut(d.masul(), 200));
        s.setMsGroupId(d.groupId());
        s.setSum(d.sumSom());
        s.setPayedSum(d.payedSom());
        s.setState(cut(d.stateName(), 120));
        s.setDueAt(d.dueAt());
        s.setComment(d.description());
        if (s.getOwnerUserId() == null)
            link.resolve(d.ownerId(), d.ownerUid(), d.ownerName(), "").ifPresent(u -> s.setOwnerUserId(u.getId()));
        if (s.getMasulUserId() == null && !s.getMasul().isBlank())
            link.byName(s.getMasul()).ifPresent(u -> s.setMasulUserId(u.getId()));
        if (s.getKassaId() == null) {
            Long k = notifier.kassaByGroup(d.groupId());
            if (k == null && s.getOwnerUserId() != null)
                k = userRepo.findById(s.getOwnerUserId()).map(AppUser::getKassaId).orElse(null);
            s.setKassaId(k);
        }
    }


    /** Baza ustuni sig'imiga qisqartirish (MoySklad'da telefon/nom uzun bo'lishi mumkin). */
    static String cut(String v, int max) {
        if (v == null) return "";
        String t = v.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }


    private Instant graceEnd(Shipment s) {
        LocalDateTime base = s.getMsCreated() != null ? s.getMsCreated()
                : s.getMoment() != null ? s.getMoment() : LocalDateTime.now(cfg.zone());
        return base.atZone(cfg.zone()).toInstant().plusSeconds(60L * cfg.graceMin());
    }


    /** T3: otgruzka to'lovi to'liq emas — balans; balans ≥ 0 → to'langan, aks holda QARZ. */
    private void decide(Shipment s, Map<String, Long> balances, boolean silent) {
        Long bal = null;
        if (balances != null && s.getAgentMsId() != null) bal = balances.get(s.getAgentMsId());
        if (bal == null && s.getAgentMsId() != null && balances == null) {
            try { bal = msClient.fetchAgentBalanceSom(s.getAgentMsId()); } catch (Exception ignored) { }
        }
        if (bal == null) {   // olinmadi — 5 daqiqadan keyin qayta
            s.setCheckAt(Instant.now().plusSeconds(300));
            repo.save(s);
            return;
        }
        s.setAgentBalance(bal);
        if (bal >= 0) {
            s.setControlStatus(Shipment.Status.TOLANGAN);
            s.setCheckAt(null);
            repo.save(s);
        } else toDebt(s, silent);
    }


    private void toDebt(Shipment s, boolean silent) {
        s.setControlStatus(Shipment.Status.QARZ);
        s.setDebtSince(Instant.now());
        s.setCheckAt(null);
        // kamchiliklar (O1..O5) — qarz xabarining o'zida ko'rsatiladi; eski (silent) qarzlar uchun guruhlangan xabar keyin
        String iss = String.join(",", evaluateIssues(s));
        s.setIssues(iss);
        s.setIssuesSince(iss.isEmpty() ? null : Instant.now());
        boolean quiet = cfg.isQuietState(s.getState());   // Перечисление va h.k. — faqat ro'yxatda
        s.setIssuesNotifiedAt(!iss.isEmpty() && (quiet || (!silent && !s.isSilent())) ? Instant.now() : null);
        repo.save(s);
        syncReminder(s);
        repo.save(s);
        audit.log(s.getOwnerUserId(), "OTG_QARZ_QOSHILDI", "shipment", s.getId(),
                "№" + s.getDocNo() + " " + s.getAgentName() + " " + s.remain() + (quiet ? " (jim)" : ""));
        if (silent || s.isSilent() || quiet) return;
        String text = "🧾 <b>Отгрузка тўлови тўлиқ эмас — қарздорлар рўйхатига қўшилди</b>\n" + render(s, true);
        Set<AppUser> to = notifier.forShipment(s, true);
        AppUser owner = s.getOwnerUserId() == null ? null : userRepo.findById(s.getOwnerUserId()).orElse(null);
        if (owner == null || owner.getTelegramId() == null) {
            String warn = "\n\n⚠️ <i>Xodim " + (owner == null ? "botga bog'lanmagan: " + esc(s.getOwnerName())
                    : esc(owner.getFullName()) + " Telegram'ga ulanmagan") + " — pastdagi tugma bilan ulang.</i>";
            // xodimsiz oluvchilar — oddiy tugmalar; SuperAdmin'larga — ulash tugmasi bilan
            Set<AppUser> admins = notifier.superadmins();
            Set<Long> adminIds = new HashSet<>();
            for (AppUser a : admins) adminIds.add(a.getId());
            to.removeIf(x -> adminIds.contains(x.getId()));
            notifier.send(to, text, kb(s));
            notifier.send(admins, text + warn, ControlNotifier.withLink(kb(s), owner));
            return;
        }
        notifier.send(to, text, kb(s));
    }


    /** Yopish: to'lov (avtomatik) yoki qo'lda (by, reason). Hammaga «✅ to'landi». */
    private void close(Shipment s, String reason, AppUser by, boolean manual) {
        boolean wasDebt = s.getControlStatus() == Shipment.Status.QARZ;
        long days = s.getDebtSince() == null ? 0 : ChronoUnit.DAYS.between(s.getDebtSince(), Instant.now());
        s.setControlStatus(Shipment.Status.YOPILDI);
        s.setClosedAt(Instant.now());
        s.setCloseReason(reason);
        s.setClosedBy(by == null ? null : by.getId());
        s.setCheckAt(null);
        repo.save(s);
        updateIssues(s);
        reminderRepo.findFirstByShipmentId(s.getId()).ifPresent(r -> {
            if (r.getStatus() == Reminder.Status.FAOL) {
                r.setRepaid(Math.max(r.getRepaid(), r.getAmount()));
                r.setStatus(Reminder.Status.BAJARILDI);
                reminderRepo.save(r);
            }
        });
        audit.log(by == null ? s.getOwnerUserId() : by.getId(), manual ? "OTG_QOLDA_YOPILDI" : "OTG_QARZ_YOPILDI",
                "shipment", s.getId(), "№" + s.getDocNo() + " " + s.getAgentName() + " " + reason);
        if (!wasDebt || (!manual && cfg.isQuietState(s.getState()))) return;   // jim status: to'langani ham xabarsiz
        String text = "✅ <b>Qarz to'landi</b> — №" + esc(s.getDocNo()) + " · " + esc(s.getAgentName())
                + " · <b>" + fmt(s.getSum()) + "</b> so'm\n👤 Xodim: " + esc(ownerLabel(s))
                + " · 🕒 qarzda " + days + " kun"
                + (manual ? "\n✍️ Qo'lda yopildi: " + esc(by == null ? "" : by.getFullName())
                    + (reason == null || reason.isBlank() ? "" : " — " + esc(reason)) : "");
        notifier.send(notifier.forShipment(s, true), text, null);
    }


    private void cancel(Shipment s, String reason, boolean silent) {
        boolean wasDebt = s.getControlStatus() == Shipment.Status.QARZ;
        s.setControlStatus(Shipment.Status.BEKOR);
        s.setClosedAt(Instant.now());
        s.setCloseReason(reason);
        s.setCheckAt(null);
        repo.save(s);
        updateIssues(s);   // o'chirilgan/bekor otgruzka kamchiliklar ro'yxatida qolmasin
        reminderRepo.findFirstByShipmentId(s.getId()).ifPresent(r -> {
            if (r.getStatus() == Reminder.Status.FAOL) { r.setStatus(Reminder.Status.BEKOR); reminderRepo.save(r); }
        });
        audit.log(s.getOwnerUserId(), "OTG_QARZ_BEKOR", "shipment", s.getId(), "№" + s.getDocNo() + " " + reason);
        if (!wasDebt || silent || s.isSilent() || cfg.isQuietState(s.getState())) return;
        notifier.send(notifier.forShipment(s, false), "🚫 <b>Qarzdagi otgruzka bekor bo'ldi</b> — №"
                + esc(s.getDocNo()) + " · " + esc(s.getAgentName()) + " · " + fmt(s.getSum()) + " so'm\n"
                + esc(reason) + "\n👤 Xodim: " + esc(ownerLabel(s)), null);
    }


    /** Qarz daftari yozuvi (source=OTGRUZKA): yaratish/yangilash. */
    private void syncReminder(Shipment s) {
        Reminder r = s.getReminderId() == null ? null : reminderRepo.findById(s.getReminderId()).orElse(null);
        if (r == null) r = reminderRepo.findFirstByShipmentId(s.getId()).orElse(null);
        Set<Long> rec = new LinkedHashSet<>();
        for (AppUser u : notifier.forShipment(s, false)) rec.add(u.getId());
        String recipients = String.join(",", rec.stream().map(String::valueOf).toList());
        LocalDate due = s.getDueAt() != null ? s.getDueAt()
                : s.getMoment() != null ? s.getMoment().toLocalDate() : LocalDate.now(cfg.zone());
        if (r == null) {
            Long creator = s.getOwnerUserId();
            if (creator == null)
                creator = userRepo.findByRoleAndActiveTrue(Role.SUPERADMIN).stream().map(AppUser::getId).findFirst().orElse(null);
            if (creator == null) return;
            r = Reminder.builder().creatorUserId(creator).agentMsId(s.getAgentMsId()).agentName(s.getAgentName())
                    .agentInfo(s.getAgentPhone()).direction(Reminder.Direction.U_QARZDOR).amount(s.getSum())
                    .dueDate(due).comment(reminderComment(s)).remindDays("").recipients(recipients)
                    .source("OTGRUZKA").shipmentId(s.getId()).repaid(s.getPayedSum()).build();
        } else {
            r.setAmount(s.getSum());
            r.setRepaid(Math.max(r.getRepaid(), s.getPayedSum()));
            r.setDueDate(due);
            r.setAgentName(s.getAgentName());
            r.setAgentInfo(s.getAgentPhone());
            r.setComment(reminderComment(s));
            if (!recipients.isBlank()) r.setRecipients(recipients);
        }
        r = reminderRepo.save(r);
        s.setReminderId(r.getId());
    }

    private String reminderComment(Shipment s) {
        String c = "📦 Otgruzka №" + s.getDocNo() + (s.getMoment() == null ? "" : " · " + s.getMoment().format(DF))
                + (s.getState().isBlank() ? "" : " · " + s.getState());
        if (s.getComment() != null && !s.getComment().isBlank()) c += "\n💬 " + s.getComment();
        return c;
    }


    /** T3: kutish oynasi tugagan otgruzkalar. refresh — har birini MoySklad'dan qayta o'qish. */
    private void dueChecks(boolean refresh) {
        List<Shipment> due = repo.findByControlStatusAndCheckAtBefore(Shipment.Status.KUTILMOQDA, Instant.now());
        if (due.isEmpty()) return;
        if (refresh) {
            List<Shipment> still = new ArrayList<>();
            for (Shipment s : due) {
                try {
                    MsDemand d = msClient.fetchDemand(s.getMsId());
                    if (d == null) { cancel(s, "MoySklad'da o'chirilgan", s.isSilent()); continue; }
                    fill(s, d);
                    if (!d.applicable()) { cancel(s, "MoySklad'da o'tkazilmagan", s.isSilent()); continue; }
                    if (s.getPayedSum() >= s.getSum()) { s.setControlStatus(Shipment.Status.TOLANGAN); s.setCheckAt(null); repo.save(s); continue; }
                    still.add(s);
                } catch (Exception e) {
                    log.warn("Otgruzka №{} qayta o'qilmadi: {}", s.getDocNo(), e.getMessage());
                }
            }
            due = still;
        }
        Set<String> agents = new LinkedHashSet<>();
        for (Shipment s : due) if (s.getAgentMsId() != null) agents.add(s.getAgentMsId());
        Map<String, Long> balances = agents.isEmpty() ? Map.of() : msClient.fetchAgentBalancesSom(agents);
        for (Shipment s : due) {
            try { decide(s, balances, s.isSilent()); }
            catch (Exception e) { log.warn("Otgruzka №{} qarori: {}", s.getDocNo(), e.getMessage()); }
        }
    }


    /* ==================== BALANS TEKSHIRUVI (har check_min daqiqa) ==================== */

    public void balanceTick() {
        if (!cfg.enabled()) return;
        String token = msClient.currentToken();
        if (token == null || token.isBlank()) return;
        if (!lock.tryLock()) return;
        try { balanceTickLocked(); } finally { lock.unlock(); }
    }


    private void balanceTickLocked() {
        List<Shipment> debts = repo.findByControlStatusOrderByDueAtAscMomentAsc(Shipment.Status.QARZ);
        if (debts.isEmpty()) return;

        // 1) qayta o'qish — o'chirilgan/to'langan hujjatni sezish: avval OXIRGI 3 KUN otgruzkalari (yangi hujjat
        //    o'chirilishi/tuzatilishi ko'p — har tickda), so'ng eskilar navbat bilan; jami 40 ta (429 limiti)
        int n = debts.size();
        LocalDateTime recentFrom = LocalDateTime.now(cfg.zone()).minusDays(3);
        List<Shipment> batch = new ArrayList<>();
        Set<Long> picked = new HashSet<>();
        for (Shipment s : debts)
            if (s.getMsCreated() != null && s.getMsCreated().isAfter(recentFrom) && batch.size() < 20) { batch.add(s); picked.add(s.getId()); }
        int step = 0;
        for (int i = 0; i < n && batch.size() < 40; i++) {
            Shipment s = debts.get((refreshCursor + i) % n);
            step = i + 1;
            if (picked.add(s.getId())) batch.add(s);
        }
        for (Shipment s : batch) {
            try {
                MsDemand d = msClient.fetchDemand(s.getMsId());
                if (d == null) { cancel(s, "MoySklad'da o'chirilgan", false); continue; }
                apply(d, false, null, false);
            } catch (Exception e) {
                log.warn("Qarz №{} qayta o'qilmadi: {}", s.getDocNo(), e.getMessage());
                if (String.valueOf(e.getMessage()).contains("429")) break;   // limit — qolgani keyingi tick
            }
        }
        refreshCursor = (refreshCursor + Math.max(1, step)) % Math.max(1, n);

        // 2) balanslar — to'plam bilan
        debts = repo.findByControlStatusOrderByDueAtAscMomentAsc(Shipment.Status.QARZ);
        Set<String> agents = new LinkedHashSet<>();
        for (Shipment s : debts) if (s.getAgentMsId() != null) agents.add(s.getAgentMsId());
        if (agents.isEmpty()) return;
        Map<String, Long> balances;
        try { balances = msClient.fetchAgentBalancesSom(agents); }
        catch (Exception e) { log.warn("Balanslar o'qilmadi: {}", e.getMessage()); return; }
        int closed = 0;
        for (Shipment s : debts) {
            Long bal = s.getAgentMsId() == null ? null : balances.get(s.getAgentMsId());
            if (bal == null) continue;
            s.setAgentBalance(bal);
            if (bal >= 0) { close(s, "MoySklad: kontragent balansi 0", null, false); closed++; }
            else repo.save(s);
        }
        if (closed > 0) log.info("Otgruzka nazorati: {} ta qarz balans bo'yicha yopildi", closed);
    }


    /* ==================== KUNLIK JAMLAMA ==================== */

    public void dailyTick() {
        if (!cfg.enabled()) return;
        LocalDate today = LocalDate.now(cfg.zone());
        if (LocalTime.now(cfg.zone()).isBefore(cfg.dailyTime())) return;
        if (today.toString().equals(cfg.get(ControlConfig.DAILY_SENT).orElse(""))) return;
        cfg.set(ControlConfig.DAILY_SENT, today.toString());

        List<Shipment> debts = repo.findByControlStatusOrderByDueAtAscMomentAsc(Shipment.Status.QARZ);
        if (debts.isEmpty()) return;
        debts.sort(Comparator.comparing((Shipment s) -> s.getDueAt() == null ? LocalDate.MAX : s.getDueAt())
                .thenComparing(s -> s.getMoment() == null ? LocalDateTime.MIN : s.getMoment()));
        // jim statuslar (Перечисление …) — jamlamada faqat son/summa, qatorlarda yo'q
        Map<Long, long[]> quietByUser = new HashMap<>();
        long quietN = 0, quietSum = 0;
        List<Shipment> loud = new ArrayList<>();
        for (Shipment s : debts) {
            if (cfg.isQuietState(s.getState())) {
                quietN++; quietSum += s.remain();
                if (s.getOwnerUserId() != null) { long[] v = quietByUser.computeIfAbsent(s.getOwnerUserId(), k -> new long[2]); v[0]++; v[1] += s.remain(); }
            } else loud.add(s);
        }
        debts = loud;

        // xodim / Масъул — o'z qarzdorlari
        Map<Long, List<Shipment>> byUser = new LinkedHashMap<>();
        for (Shipment s : debts) {
            if (s.getOwnerUserId() != null) byUser.computeIfAbsent(s.getOwnerUserId(), k -> new ArrayList<>()).add(s);
            if (s.getMasulUserId() != null && !s.getMasulUserId().equals(s.getOwnerUserId()))
                byUser.computeIfAbsent(s.getMasulUserId(), k -> new ArrayList<>()).add(s);
        }
        Set<Long> sentUsers = new HashSet<>();
        for (var e : byUser.entrySet()) {
            AppUser u = userRepo.findById(e.getKey()).orElse(null);
            if (u == null || u.getTelegramId() == null) continue;
            long iss = e.getValue().stream().filter(x -> !x.getIssues().isEmpty()).count();
            long[] qv = quietByUser.get(e.getKey());
            String text = "🔔 <b>Qarzdorlaringiz</b> — " + today.format(DF) + digestLines(e.getValue(), 25, false)
                    + (qv == null ? "" : "🏦 Перечисление (bank): " + qv[0] + " ta · " + fmt(qv[1]) + " so'm — faqat ro'yxatda\n")
                    + (iss > 0 ? "\n📦 Kamchilikli otgruzkalar: <b>" + iss + "</b> ta (muddat/masul/telefon) — MoySklad'da to'ldiring\n" : "\n")
                    + "Ro'yxat: 🤝 КОНТРАГЕНТ → 🧾 Қарздорлар · ⚠️ Хатолар → 📦 Otgruzkalar";
            notifier.sendOne(u, text, null);
            sentUsers.add(u.getId());
        }
        // otdel rahbarlari — o'z otdeli
        Map<Long, List<Shipment>> byKassa = new LinkedHashMap<>();
        for (Shipment s : debts) byKassa.computeIfAbsent(s.getKassaId() == null ? -1L : s.getKassaId(), k -> new ArrayList<>()).add(s);
        for (var e : byKassa.entrySet()) {
            if (e.getKey() < 0) continue;
            String text = "🔔 <b>Otdel qarzdorlari</b> — " + esc(notifier.kassaName(e.getKey())) + " · " + today.format(DF)
                    + digestLines(e.getValue(), 30, true);
            Set<AppUser> to = new LinkedHashSet<>(notifier.heads(e.getKey()));
            for (AppUser u : notifier.designated(e.getKey())) if (u.getKassaId() != null) to.add(u);
            notifier.send(to, text, null);
        }
        // SuperAdmin + belgilanganlar (otdelsiz) — umumiy
        StringBuilder sb = new StringBuilder("🔔 <b>Qarzdorlar — umumiy</b> · " + today.format(DF) + "\n");
        long total = 0;
        for (Shipment s : debts) total += s.remain();
        sb.append("Jami: <b>").append(debts.size()).append("</b> ta otgruzka · <b>").append(fmt(total)).append("</b> so'm\n\n");
        for (var e : byKassa.entrySet()) {
            long sum = 0;
            Map<String, long[]> byEmp = new LinkedHashMap<>();
            for (Shipment s : e.getValue()) {
                sum += s.remain();
                long[] v = byEmp.computeIfAbsent(ownerLabel(s), k -> new long[2]);
                v[0]++; v[1] += s.remain();
            }
            sb.append("🏪 <b>").append(esc(e.getKey() < 0 ? "Otdel bog'lanmagan" : notifier.kassaName(e.getKey())))
              .append("</b>: ").append(e.getValue().size()).append(" ta · ").append(fmt(sum)).append(" so'm\n");
            for (var x : byEmp.entrySet())
                sb.append("   • ").append(esc(x.getKey())).append(": ").append(x.getValue()[0]).append(" ta · ")
                  .append(fmt(x.getValue()[1])).append("\n");
        }
        List<Shipment> overdue = debts.stream().filter(s -> s.getDueAt() != null && s.getDueAt().isBefore(today)).toList();
        if (!overdue.isEmpty()) sb.append("\n⚠️ <b>Muddati o'tganlar</b>:").append(digestLines(overdue, 15, true));
        long noDue = debts.stream().filter(s -> s.getDueAt() == null).count();
        if (noDue > 0) sb.append("\n❗ Muddati kiritilmagan: <b>").append(noDue).append("</b> ta");
        if (quietN > 0) sb.append("\n🏦 Перечисление (bank, jim): <b>").append(quietN).append("</b> ta · ").append(fmt(quietSum)).append(" so'm — faqat ro'yxatda");
        long issN = debts.stream().filter(s -> !s.getIssues().isEmpty()).count();
        if (issN > 0) sb.append("\n📦 Kamchilikli otgruzkalar: <b>").append(issN).append("</b> ta (⚠️ Хатолар → 📦 Otgruzkalar)");
        Set<AppUser> admins = new LinkedHashSet<>(notifier.superadmins());
        for (AppUser u : notifier.designated(null)) if (u.getKassaId() == null) admins.add(u);
        notifier.send(admins, sb.toString(), null);
    }


    /* ==================== 🔔 QARZDOR ESLATMALARI (takror, davr) ==================== */

    /**
     * Har 10 daqiqada (Jobs): remind_time dan keyin, kuniga bir. Har qarzdor otgruzka uchun eslatma kuni:
     * muddatdan N kun oldin (remind_before), muddat kuni, muddat o'tgach har remind_repeat kunda — to'languncha.
     * Muddatsiz otgruzkada hujjat sanasi muddat o'rnida. Xodim (ega + Масъул) ga kuniga BITTA guruhlangan xabar;
     * jim statuslar va Telegram'siz xodimlar o'tkaziladi (ular kunlik jamlamada).
     */
    public void remindTick() {
        if (!cfg.enabled()) return;
        LocalDate today = LocalDate.now(cfg.zone());
        if (LocalTime.now(cfg.zone()).isBefore(cfg.remindTime())) return;
        if (today.toString().equals(cfg.get(ControlConfig.REMIND_SENT).orElse(""))) return;
        cfg.set(ControlConfig.REMIND_SENT, today.toString());

        Set<Integer> before = cfg.remindBefore();
        int repeat = cfg.remindRepeatDays();
        Map<Long, List<Shipment>> byUser = new LinkedHashMap<>();
        for (Shipment s : repo.findByControlStatusOrderByDueAtAscMomentAsc(Shipment.Status.QARZ)) {
            if (s.isSilent() || cfg.isQuietState(s.getState())) continue;
            if (today.equals(s.getRemindSent())) continue;
            LocalDate due = s.getDueAt() != null ? s.getDueAt() : s.getMoment() != null ? s.getMoment().toLocalDate() : null;
            if (due == null) continue;
            long left = ChronoUnit.DAYS.between(today, due);
            boolean send = left == 0 || (left > 0 && before.contains((int) left))
                    || (left < 0 && repeat > 0 && (-left) % repeat == 0);
            if (!send) continue;
            boolean any = false;
            if (s.getOwnerUserId() != null) { byUser.computeIfAbsent(s.getOwnerUserId(), k -> new ArrayList<>()).add(s); any = true; }
            if (s.getMasulUserId() != null && !s.getMasulUserId().equals(s.getOwnerUserId())) {
                byUser.computeIfAbsent(s.getMasulUserId(), k -> new ArrayList<>()).add(s); any = true;
            }
            if (any) { s.setRemindSent(today); repo.save(s); }
        }
        int sent = 0;
        for (var e : byUser.entrySet()) {
            AppUser u = userRepo.findById(e.getKey()).orElse(null);
            if (u == null || u.getTelegramId() == null) continue;
            List<Shipment> list = e.getValue();
            long overdue = list.stream().filter(x -> x.getDueAt() != null && x.getDueAt().isBefore(today)).count();
            String text = "🔔 <b>Qarz eslatmasi</b> — " + today.format(DF) + digestLines(list, 30, false)
                    + (overdue > 0 ? "⚠️ Muddati o'tgan: <b>" + overdue + "</b> ta" + (repeat > 0 ? " — har " + repeat + " kunda eslatiladi" : "") + "\n" : "")
                    + "\nMijoz bilan bog'lanib to'lovni undiring. To'langach bot o'zi yopadi.\n"
                    + "Ro'yxat: 🤝 КОНТРАГЕНТ → 🧾 Қарздорлар";
            notifier.sendOne(u, text, null);
            sent++;
        }
        if (sent > 0) log.info("Qarzdor eslatmalari: {} xodimga yuborildi", sent);
    }


    /** Xodimning barcha ochiq qarzdorlari (ega + Масъул), kunlik jamlama ko'rinishida — Telegram ulanganda. Bo'sh — null. */
    public String debtorsDigest(AppUser u) {
        List<Shipment> list = new ArrayList<>(repo.findByControlStatusAndOwnerUserIdOrderByDueAtAscMomentAsc(Shipment.Status.QARZ, u.getId()));
        for (Shipment s : repo.findByControlStatusAndMasulUserIdOrderByDueAtAscMomentAsc(Shipment.Status.QARZ, u.getId()))
            if (list.stream().noneMatch(x -> x.getId().equals(s.getId()))) list.add(s);
        long quietN = 0, quietSum = 0;
        List<Shipment> loud = new ArrayList<>();
        for (Shipment s : list) {
            if (cfg.isQuietState(s.getState())) { quietN++; quietSum += s.remain(); } else loud.add(s);
        }
        if (loud.isEmpty() && quietN == 0) return null;
        loud.sort(Comparator.comparing((Shipment s) -> s.getDueAt() == null ? LocalDate.MAX : s.getDueAt())
                .thenComparing(s -> s.getMoment() == null ? LocalDateTime.MIN : s.getMoment()));
        LocalDate today = LocalDate.now(cfg.zone());
        return "🧾 <b>Qarzdorlaringiz</b> — " + today.format(DF)
                + (loud.isEmpty() ? " (0 ta)\n" : digestLines(loud, 25, false))
                + (quietN == 0 ? "" : "🏦 Перечисление (bank): " + quietN + " ta · " + fmt(quietSum) + " so'm — faqat ro'yxatda\n")
                + "\nEslatmalar: muddatdan " + (cfg.remindBefore().isEmpty() ? "" : cfg.remindBefore() + " kun oldin, ")
                + "muddat kuni" + (cfg.remindRepeatDays() > 0 ? ", o'tgach har " + cfg.remindRepeatDays() + " kunda" : "")
                + " · kunlik jamlama " + cfg.dailyTime() + "\nRo'yxat: 🤝 КОНТРАГЕНТ → 🧾 Қарздорлар";
    }


    private String digestLines(List<Shipment> list, int max, boolean withOwner) {
        StringBuilder sb = new StringBuilder();
        long total = 0;
        for (Shipment s : list) total += s.remain();
        sb.append(" (").append(list.size()).append(" ta, jami <b>").append(fmt(total)).append("</b> so'm)\n");
        LocalDate today = LocalDate.now(cfg.zone());
        int n = 0;
        for (Shipment s : list) {
            if (++n > max) { sb.append("… yana ").append(list.size() - max).append(" ta\n"); break; }
            sb.append(n).append(". ").append(esc(s.getAgentName())).append(" · №").append(esc(s.getDocNo()))
              .append(" · <b>").append(fmt(s.remain())).append("</b> · ").append(dueLabel(s, today));
            if (withOwner) sb.append(" · 👤 ").append(esc(ownerLabel(s)));
            sb.append("\n");
        }
        return sb.toString();
    }


    /* ==================== 📦 OTGRUZKA KAMCHILIKLARI (O1..O5) ==================== */

    /** Qarzdagi otgruzka uchun kamchilik kodlari (sozlamada yoqilgan qoidalar). */
    public List<String> evaluateIssues(Shipment s) {
        List<String> out = new ArrayList<>();
        if (cfg.ruleOn("O1") && s.getDueAt() == null && s.remain() > 0) out.add("O1");
        if (cfg.ruleOn("O2") && s.getMasul().isBlank()) out.add("O2");
        if (cfg.ruleOn("O3") && s.getState().isBlank()) out.add("O3");
        if (cfg.ruleOn("O4") && (s.getComment() == null || s.getComment().isBlank())) out.add("O4");
        if (cfg.ruleOn("O5") && s.getAgentPhone().isBlank()) out.add("O5");
        return out;
    }


    /** Faqat QARZ holatida tekshiriladi; o'zgarsa saqlanadi, yangi paydo bo'lsa xabar navbatiga (issues_notified_at=null). */
    private void updateIssues(Shipment s) {
        String codes = s.getControlStatus() == Shipment.Status.QARZ ? String.join(",", evaluateIssues(s)) : "";
        if (codes.equals(s.getIssues())) return;
        boolean wasEmpty = s.getIssues().isEmpty();
        String before = s.getIssues();
        s.setIssues(codes);
        if (codes.isEmpty()) { s.setIssuesSince(null); s.setIssuesNotifiedAt(null); s.setIssuesEscalatedAt(null); s.setIssuesEscalated2At(null); }
        else if (wasEmpty) { s.setIssuesSince(Instant.now()); s.setIssuesNotifiedAt(null); s.setIssuesEscalatedAt(null); s.setIssuesEscalated2At(null); }
        repo.save(s);
        // 📊 statistika uchun: kamchilik topildi / xodim tuzatdi (qarz yopilib ketgani tuzatish emas)
        if (wasEmpty && !codes.isEmpty())
            audit.log(s.getOwnerUserId(), "OTG_KAMCHILIK_TOPILDI", "shipment", s.getId(), "№" + s.getDocNo() + " " + codes);
        else if (!wasEmpty && codes.isEmpty() && s.getControlStatus() == Shipment.Status.QARZ)
            audit.log(s.getOwnerUserId(), "OTG_KAMCHILIK_TUZATILDI", "shipment", s.getId(), "№" + s.getDocNo() + " " + before);
    }


    /** Qoidalar o'zgarganda / V25 dan keyin: barcha qarzlar qayta baholanadi (MoySklad'siz). */
    public void reevaluateAllIssues() {
        int n = 0;
        for (Shipment s : repo.findByControlStatusOrderByDueAtAscMomentAsc(Shipment.Status.QARZ)) { updateIssues(s); n++; }
        for (Shipment s : repo.findByIssuesNotOrderByMomentDesc(""))
            if (s.getControlStatus() != Shipment.Status.QARZ) updateIssues(s);
        log.info("Otgruzka kamchiliklari qayta baholandi: {} ta qarz, kamchilikli {} ta", n, repo.countByIssuesNot(""));
    }


    /** Xabar berilmagan kamchiliklar — xodimga BITTA guruhlangan xabar; bog'lanmagan xodimlar — SuperAdmin'ga jamlab. */
    public void notifyIssues() {
        List<Shipment> found = repo.findByIssuesNotAndIssuesNotifiedAtIsNull("");
        if (found.isEmpty()) return;
        List<Shipment> list = new ArrayList<>();
        Instant mark = Instant.now();
        for (Shipment s : found) {
            if (cfg.isQuietState(s.getState())) { s.setIssuesNotifiedAt(mark); repo.save(s); }   // faqat ro'yxatda
            else list.add(s);
        }
        if (list.isEmpty()) return;
        Map<Long, List<Shipment>> byUser = new LinkedHashMap<>();
        Map<String, List<Shipment>> unlinked = new LinkedHashMap<>();
        for (Shipment s : list) {
            if (s.getOwnerUserId() != null) byUser.computeIfAbsent(s.getOwnerUserId(), k -> new ArrayList<>()).add(s);
            else unlinked.computeIfAbsent(s.getOwnerName().isBlank() ? "noma'lum" : s.getOwnerName(), k -> new ArrayList<>()).add(s);
        }
        Instant now = Instant.now();
        for (var e : byUser.entrySet()) {
            AppUser u = userRepo.findById(e.getKey()).orElse(null);
            if (u != null && u.getTelegramId() != null) notifier.sendOne(u, issuesMessage(e.getValue()), null);
            else notifier.send(notifier.superadmins(), "⚠️ <i>Xodim " + esc(u == null ? "#" + e.getKey() : u.getFullName())
                    + " Telegram'ga ulanmagan — pastdagi tugma bilan ulang.</i>\n\n" + issuesMessage(e.getValue()),
                    ControlNotifier.withLink(null, u));
            for (Shipment s : e.getValue()) { s.setIssuesNotifiedAt(now); repo.save(s); }
        }
        if (!unlinked.isEmpty()) {
            StringBuilder sb = new StringBuilder("📦 <b>Otgruzka kamchiliklari — botga bog'lanmagan xodimlar</b>\n");
            for (var e : unlinked.entrySet()) {
                sb.append("• ").append(esc(e.getKey())).append(": <b>").append(e.getValue().size()).append("</b> ta — ")
                  .append(issueSummary(e.getValue())).append("\n");
                for (Shipment s : e.getValue()) { s.setIssuesNotifiedAt(now); repo.save(s); }
            }
            sb.append("\nPastdagi tugma orqali bog'lang — xabar xodimning o'ziga boradi.");
            notifier.send(notifier.superadmins(), sb.toString(), ControlNotifier.withLink(null, null));
        }
    }


    /**
     * Ikki bosqichli eskalatsiya (user 09.09.2026): xodimga xabardan esc1 daqiqa o'tsa — otdel RAHBARI
     * (otdel kesimida bitta guruhlangan xabar); esc2 daqiqa o'tsa ham tuzatilmasa — «tuzatilmadi»
     * SuperAdmin + rahbar + belgilanganlar. Jim statuslar eskalatsiya qilinmaydi.
     */
    public void escalateIssues() {
        Instant lim1 = Instant.now().minusSeconds(cfg.esc1Min() * 60L);
        Instant lim2 = Instant.now().minusSeconds(cfg.esc2Min() * 60L);
        Map<Long, List<Shipment>> st1 = new LinkedHashMap<>(), st2 = new LinkedHashMap<>();
        for (Shipment s : repo.findByIssuesNotAndIssuesNotifiedAtIsNotNull("")) {
            if (cfg.isQuietState(s.getState())) continue;
            long k = s.getKassaId() == null ? -1 : s.getKassaId();
            if (s.getIssuesEscalatedAt() == null && !s.getIssuesNotifiedAt().isAfter(lim1))
                st1.computeIfAbsent(k, x -> new ArrayList<>()).add(s);
            if (s.getIssuesEscalated2At() == null && !s.getIssuesNotifiedAt().isAfter(lim2))
                st2.computeIfAbsent(k, x -> new ArrayList<>()).add(s);
        }
        Instant now = Instant.now();
        for (var e : st1.entrySet()) {
            for (Shipment s : e.getValue()) { s.setIssuesEscalatedAt(now); repo.save(s); }
            Long kassa = e.getKey() < 0 ? null : e.getKey();
            Set<AppUser> heads = notifier.heads(kassa);
            if (heads.isEmpty()) continue;   // rahbar yo'q — 2-bosqichda admin oladi
            notifier.send(heads, "\u23F0 <b>Otgruzka kamchiliklari " + cfg.esc1Min() + " daqiqadan beri tuzatilmadi</b> — "
                    + esc(kassa == null ? "otdel bog'lanmagan" : notifier.kassaName(kassa)) + "\n"
                    + escalationLines(e.getValue())
                    + "\nXodimlar tuzatishini nazorat qiling. " + cfg.esc2Min()
                    + " daqiqada tuzatilmasa admin'ga «tuzatilmadi» xabari boradi.", null);
            audit.log(null, "OTG_KAMCHILIK_ESKALATSIYA", "kassa", kassa, e.getValue().size() + " ta -> rahbar");
        }
        for (var e : st2.entrySet()) {
            for (Shipment s : e.getValue()) { s.setIssuesEscalated2At(now); repo.save(s); }
            Long kassa = e.getKey() < 0 ? null : e.getKey();
            notifier.send(notifier.escalation(kassa), "\u274C <b>TUZATILMADI — otgruzka kamchiliklari " + cfg.esc2Min()
                    + " daqiqadan beri ochiq</b> — " + esc(kassa == null ? "otdel bog'lanmagan" : notifier.kassaName(kassa)) + "\n"
                    + escalationLines(e.getValue())
                    + "\nXodim ham, otdel rahbari ham tuzatmadi.", null);
            audit.log(null, "OTG_KAMCHILIK_TUZATILMADI", "kassa", kassa, e.getValue().size() + " ta -> admin");
        }
    }

    /** Eskalatsiya xabari qatorlari: xodim kesimida otgruzkalar. */
    private String escalationLines(List<Shipment> list) {
        Map<String, List<Shipment>> byOwner = new LinkedHashMap<>();
        for (Shipment s : list) {
            String who = s.getOwnerUserId() != null ? notifier.userName(s.getOwnerUserId())
                    : (s.getOwnerName().isBlank() ? "noma'lum xodim" : s.getOwnerName());
            byOwner.computeIfAbsent(who, k -> new ArrayList<>()).add(s);
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (var e : byOwner.entrySet()) {
            sb.append("\uD83D\uDC64 <b>").append(esc(e.getKey())).append("</b> — ").append(e.getValue().size()).append(" ta\n");
            for (Shipment s : e.getValue()) {
                if (++n > 25) { sb.append("… yana ").append(list.size() - 25).append(" ta\n"); return sb.toString(); }
                sb.append("  • №").append(esc(s.getDocNo())).append(" · ").append(esc(s.getAgentName()))
                  .append(" · ").append(fmt(s.remain())).append(" · ").append(issueLabels(s.issueList())).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Xodimga tegishli (ega yoki Масъул) kamchilikli otgruzkalar — Telegram ulanganda bir marta yuborish uchun.
     * Jim statuslar chiqarib tashlanadi. Bo'sh bo'lsa null.
     */
    public String pendingIssuesText(AppUser u) {
        List<Shipment> list = new ArrayList<>(repo.findByIssuesNotAndOwnerUserIdOrderByMomentDesc("", u.getId()));
        for (Shipment s : repo.findByIssuesNotAndMasulUserIdOrderByMomentDesc("", u.getId()))
            if (list.stream().noneMatch(x -> x.getId().equals(s.getId()))) list.add(s);
        list.removeIf(s -> cfg.isQuietState(s.getState()));
        return list.isEmpty() ? null : issuesMessage(list);
    }

    /** Xodimning ochiq qarzdorlari (ega yoki Масъул): [soni, qoldiq summa]. */
    public long[] debtSummary(AppUser u) {
        List<Shipment> list = new ArrayList<>(repo.findByControlStatusAndOwnerUserIdOrderByDueAtAscMomentAsc(Shipment.Status.QARZ, u.getId()));
        for (Shipment s : repo.findByControlStatusAndMasulUserIdOrderByDueAtAscMomentAsc(Shipment.Status.QARZ, u.getId()))
            if (list.stream().noneMatch(x -> x.getId().equals(s.getId()))) list.add(s);
        long sum = 0;
        for (Shipment s : list) sum += s.remain();
        return new long[]{list.size(), sum};
    }


    private String issuesMessage(List<Shipment> list) {
        list.sort(Comparator.comparing((Shipment s) -> s.getMoment() == null ? LocalDateTime.MIN : s.getMoment()).reversed());
        StringBuilder sb = new StringBuilder("📦 <b>Otgruzkalarda kamchilik bor</b> — " + list.size() + " ta ("
                + issueSummary(list) + ")\n");
        int n = 0;
        for (Shipment s : list) {
            if (++n > 20) { sb.append("… yana ").append(list.size() - 20).append(" ta\n"); break; }
            sb.append(n).append(". №").append(esc(s.getDocNo())).append(" · ").append(esc(s.getAgentName()))
              .append(" · ").append(fmt(s.remain())).append(" · ").append(issueLabels(s.issueList())).append("\n");
        }
        sb.append("\nMoySklad'da otgruzkani ochib to'ldiring: «Тўлов муддати» — mijoz qachon to'laydi"
                + (cfg.ruleOn("O2") ? ", «Масъул»" : "") + ". Bot o'zi tekshiradi.\n"
                + "Ro'yxat: 🤝 КОНТРАГЕНТ → ⚠️ Хатолар → 📦 Otgruzkalar");
        return sb.toString();
    }


    /** "muddat 12 · telefon 3" ko'rinishida. */
    public static String issueSummary(List<Shipment> list) {
        Map<String, Integer> c = new LinkedHashMap<>();
        for (Shipment s : list) for (String code : s.issueList()) c.merge(code, 1, Integer::sum);
        List<String> parts = new ArrayList<>();
        for (var e : c.entrySet()) parts.add(issueShort(e.getKey()) + " " + e.getValue());
        return String.join(" · ", parts);
    }

    public static String issueLabels(List<String> codes) {
        return String.join(", ", codes.stream().map(ShipmentControlService::issueShort).toList());
    }

    public static String issueShort(String code) {
        return switch (code) {
            case "O1" -> "❗ muddat yo'q";
            case "O2" -> "👔 Масъул yo'q";
            case "O3" -> "🏷 status yo'q";
            case "O4" -> "💬 izoh yo'q";
            case "O5" -> "📞 kontragent telefoni yo'q";
            default -> code;
        };
    }


    /** Kamchilikli otgruzkalar: kassir — o'ziniki (+ Масъул, + rahbar bo'lgan otdeli); bux/SA — hammasi. */
    public List<Shipment> visibleIssuesFor(AppUser u, Long kassaFilter) {
        List<Shipment> all;
        if (u.getRole() == Role.SUPERADMIN || u.getRole() == Role.BUXGALTER) {
            all = kassaFilter == null ? repo.findByIssuesNotOrderByMomentDesc("")
                    : repo.findByIssuesNotAndKassaIdOrderByMomentDesc("", kassaFilter);
        } else {
            all = new ArrayList<>(repo.findByIssuesNotAndOwnerUserIdOrderByMomentDesc("", u.getId()));
            for (Shipment s : repo.findByIssuesNotAndMasulUserIdOrderByMomentDesc("", u.getId()))
                if (all.stream().noneMatch(x -> x.getId().equals(s.getId()))) all.add(s);
            for (Long k : notifier.headOf(u))
                for (Shipment s : repo.findByIssuesNotAndKassaIdOrderByMomentDesc("", k))
                    if (all.stream().noneMatch(x -> x.getId().equals(s.getId()))) all.add(s);
            if (kassaFilter != null) all.removeIf(s -> !kassaFilter.equals(s.getKassaId()));
        }
        return all;
    }

    public long issueCount() { return repo.countByIssuesNot(""); }


    /* ==================== UI / QO'LDA ==================== */

    /** Foydalanuvchi ko'radigan qarzlar: kassir — o'ziniki (+ rahbar bo'lgan otdeli); bux/SA — hammasi. */
    /** Status bo'yicha filtr bilan (null — hammasi). */
    public List<Shipment> visibleFor(AppUser u, Long kassaFilter, String state) {
        List<Shipment> all = visibleFor(u, kassaFilter);
        if (state == null || state.isBlank()) return all;
        String key = MoySkladClient.normAttr(state);
        return all.stream().filter(s -> MoySkladClient.normAttr(s.getState()).equals(key)).toList();
    }

    /** Kontragent turi filtri: 0 — hammasi, 1 — Юр. лицо, 2 — ИП, 3 — Физ. лицо. */
    public static final String[] TYPE_KEYS = {"", "legal", "entrepreneur", "individual"};

    public static String typeLabel(String companyType) {
        if (companyType == null) return "";
        if (companyType.startsWith("legal")) return "🏢 Юр. лицо";
        if (companyType.startsWith("entrepreneur")) return "🧑‍💼 ИП";
        if (companyType.startsWith("individual")) return "👤 Физ. лицо";
        return "";
    }

    public static boolean typeMatches(Shipment s, int type) {
        if (type <= 0 || type >= TYPE_KEYS.length) return true;
        return s.getAgentType().startsWith(TYPE_KEYS[type]);
    }

    public static long countType(List<Shipment> list, int type) {
        return list.stream().filter(s -> typeMatches(s, type)).count();
    }


    /** Ro'yxatdagi statuslar — soni bo'yicha kamayib (Перечисление, Карз, Накд …). */
    public static List<String> distinctStates(List<Shipment> list) {
        Map<String, Integer> c = new LinkedHashMap<>();
        for (Shipment s : list) c.merge(s.getState().isBlank() ? "—" : s.getState(), 1, Integer::sum);
        List<Map.Entry<String, Integer>> es = new ArrayList<>(c.entrySet());
        es.sort((a, b) -> b.getValue() - a.getValue());
        return es.stream().map(Map.Entry::getKey).toList();
    }

    public static long countState(List<Shipment> list, String state) {
        String key = MoySkladClient.normAttr(state.equals("—") ? "" : state);
        return list.stream().filter(s -> MoySkladClient.normAttr(s.getState()).equals(key)).count();
    }

    public boolean isQuietState(String state) { return cfg.isQuietState(state); }

    /** Barcha qarzdagi statuslar (sozlama ekrani uchun). */
    public List<String> allDebtStates() {
        return distinctStates(repo.findByControlStatusOrderByDueAtAscMomentAsc(Shipment.Status.QARZ));
    }

    public List<Shipment> visibleFor(AppUser u, Long kassaFilter) {
        List<Shipment> all;
        if (u.getRole() == Role.SUPERADMIN || u.getRole() == Role.BUXGALTER) {
            all = kassaFilter == null ? repo.findByControlStatusOrderByDueAtAscMomentAsc(Shipment.Status.QARZ)
                    : repo.findByControlStatusAndKassaIdOrderByDueAtAscMomentAsc(Shipment.Status.QARZ, kassaFilter);
        } else {
            all = new ArrayList<>(repo.findByControlStatusAndOwnerUserIdOrderByDueAtAscMomentAsc(Shipment.Status.QARZ, u.getId()));
            for (Shipment s : repo.findByControlStatusAndMasulUserIdOrderByDueAtAscMomentAsc(Shipment.Status.QARZ, u.getId()))
                if (all.stream().noneMatch(x -> x.getId().equals(s.getId()))) all.add(s);
            for (Long k : notifier.headOf(u))
                for (Shipment s : repo.findByControlStatusAndKassaIdOrderByDueAtAscMomentAsc(Shipment.Status.QARZ, k))
                    if (all.stream().noneMatch(x -> x.getId().equals(s.getId()))) all.add(s);
            if (kassaFilter != null) all.removeIf(s -> !kassaFilter.equals(s.getKassaId()));
        }
        all.sort(Comparator.comparing((Shipment s) -> s.getDueAt() == null ? LocalDate.MAX : s.getDueAt())
                .thenComparing(s -> s.getMoment() == null ? LocalDateTime.MIN : s.getMoment()));
        return all;
    }

    public boolean canSee(AppUser u, Shipment s) {
        if (u.getRole() == Role.SUPERADMIN || u.getRole() == Role.BUXGALTER) return true;
        if (u.getId().equals(s.getOwnerUserId()) || u.getId().equals(s.getMasulUserId())) return true;
        return s.getKassaId() != null && notifier.isHead(u, s.getKassaId());
    }

    public Optional<Shipment> find(long id) { return repo.findById(id); }

    public long debtCount() { return repo.countByControlStatus(Shipment.Status.QARZ); }

    public long debtSum() {
        long t = 0;
        for (Shipment s : repo.findByControlStatusOrderByDueAtAscMomentAsc(Shipment.Status.QARZ)) t += s.remain();
        return t;
    }


    /** 🔄 MoySklad'dan qayta o'qib qo'llash (UI). false — hujjat topilmadi. */
    public boolean refresh(Shipment s) {
        lock.lock();
        try { return refreshLocked(s); } finally { lock.unlock(); }
    }

    private boolean refreshLocked(Shipment s) {
        MsDemand d = msClient.fetchDemand(s.getMsId());
        if (d == null) { cancel(s, "MoySklad'da o'chirilgan", false); return false; }
        apply(d, false, null, false);
        if (s.getControlStatus() == Shipment.Status.QARZ && s.getAgentMsId() != null) {
            Long bal = msClient.fetchAgentBalanceSom(s.getAgentMsId());
            Shipment cur = repo.findById(s.getId()).orElse(s);
            if (bal != null) {
                cur.setAgentBalance(bal);
                if (bal >= 0) close(cur, "MoySklad: kontragent balansi 0", null, false);
                else repo.save(cur);
            }
        }
        return true;
    }


    /**
     * ✅ Yopish (T8): avval MoySklad balansi qayta o'qiladi — to'langan bo'lsa yopiladi (bux/SA).
     * Aks holda faqat SuperAdmin sabab bilan. Natija: "OK" | "NEED_REASON:<balans>" | "DENIED:<balans>" | "GONE".
     */
    public String closeManual(Shipment s, AppUser by, String reason) {
        lock.lock();
        try { return closeManualLocked(s, by, reason); } finally { lock.unlock(); }
    }

    private String closeManualLocked(Shipment s, AppUser by, String reason) {
        MsDemand d = msClient.fetchDemand(s.getMsId());
        if (d == null) { cancel(s, "MoySklad'da o'chirilgan", false); return "GONE"; }
        apply(d, false, null, false);
        Shipment cur = repo.findById(s.getId()).orElse(s);
        if (cur.getControlStatus() != Shipment.Status.QARZ) return "OK";
        Long bal = cur.getAgentMsId() == null ? null : msClient.fetchAgentBalanceSom(cur.getAgentMsId());
        if (bal != null) { cur.setAgentBalance(bal); repo.save(cur); }
        if (bal != null && bal >= 0) { close(cur, "MoySklad: kontragent balansi 0 (tekshirildi)", by, true); return "OK"; }
        String b = bal == null ? "?" : fmt(bal);
        if (by.getRole() != Role.SUPERADMIN) return "DENIED:" + b;
        if (reason == null || reason.isBlank()) return "NEED_REASON:" + b;
        close(cur, reason.trim(), by, true);
        return "OK";
    }


    /* ==================== MATN ==================== */

    /** Otgruzka kartasi (xabar va bo'lim uchun), TZ: xodim/klient/telefon/№/komentariya + summa, muddat, havola. */
    public String render(Shipment s, boolean full) {
        LocalDate today = LocalDate.now(cfg.zone());
        StringBuilder sb = new StringBuilder();
        sb.append("👤 Xodim: <b>").append(esc(ownerLabel(s))).append("</b>");
        if (s.getKassaId() != null) sb.append(" · ").append(esc(notifier.kassaName(s.getKassaId())));
        sb.append("\n🏢 Klient: <b>").append(esc(s.getAgentName())).append("</b>")
          .append(s.getAgentType().isBlank() ? "" : " · " + typeLabel(s.getAgentType())).append("\n");
        sb.append("📞 Telefon: ").append(s.getAgentPhone().isBlank() ? "—" : esc(s.getAgentPhone())).append("\n");
        sb.append("📦 Otgruzka: <b>№").append(esc(s.getDocNo())).append("</b>");
        if (s.getMoment() != null) sb.append(" · ").append(s.getMoment().format(DTF));
        if (!s.getState().isBlank()) sb.append(" · ").append(esc(s.getState()));
        sb.append("\n💰 Summa: <b>").append(fmt(s.getSum())).append("</b> · To'langan: ").append(fmt(s.getPayedSum()))
          .append(" · Qoldiq: <b>").append(fmt(s.remain())).append("</b> so'm\n");
        if (s.getAgentBalance() != null)
            sb.append("📊 Kontragent balansi: <b>").append(s.getAgentBalance() < 0 ? "−" : "")
              .append(fmt(Math.abs(s.getAgentBalance()))).append("</b> so'm\n");
        sb.append("📅 To'lov muddati: ").append(s.getDueAt() == null
                ? "❗ <b>kiritilmagan</b> — MoySklad'da «Тўлов муддати»ni to'ldiring"
                : "<b>" + s.getDueAt().format(DF) + "</b> — " + dueLabel(s, today)).append("\n");
        sb.append("👔 Масъул: ").append(s.getMasul().isBlank() ? "—" : esc(s.getMasul())).append("\n");
        sb.append("💬 Komentariya: ").append(s.getComment() == null || s.getComment().isBlank() ? "—" : esc(s.getComment()));
        if (!s.getIssues().isEmpty())
            sb.append("\n⚠️ Kamchilik: ").append(issueLabels(s.issueList())).append(" — MoySklad'da to'ldiring");
        if (full && s.getDebtSince() != null)
            sb.append("\n🕒 Qarzda: ").append(ChronoUnit.DAYS.between(s.getDebtSince(), Instant.now())).append(" kun");
        return sb.toString();
    }

    public InlineKeyboardMarkup kb(Shipment s) {
        List<InlineKeyboardButton> row = new ArrayList<>();
        row.add(ControlNotifier.urlBtn("🔗 Otgruzka", ControlNotifier.MS_DEMAND_URL + s.getMsId()));
        if (s.getAgentMsId() != null)
            row.add(ControlNotifier.urlBtn("🔗 Kontragent", ControlNotifier.MS_AGENT_URL + s.getAgentMsId()));
        return inline(List.of(row));
    }

    public String ownerLabel(Shipment s) {
        if (s.getOwnerUserId() != null) return notifier.userName(s.getOwnerUserId());
        return s.getOwnerName().isBlank() ? "noma'lum" : s.getOwnerName();
    }

    public static String dueLabel(Shipment s, LocalDate today) {
        if (s.getDueAt() == null) return "❗ muddat yo'q";
        long left = ChronoUnit.DAYS.between(today, s.getDueAt());
        if (left > 0) return "⏳ " + left + " kun qoldi";
        if (left == 0) return "❗ BUGUN";
        return "⚠️ " + (-left) + " kun O'TDI";
    }

    public static String statusLabel(Shipment.Status st) {
        return switch (st) {
            case KUTILMOQDA -> "⏳ kutilmoqda";
            case TOLANGAN -> "✅ to'langan";
            case QARZ -> "🧾 QARZ";
            case YOPILDI -> "✅ yopildi";
            case BEKOR -> "🚫 bekor";
        };
    }
}
