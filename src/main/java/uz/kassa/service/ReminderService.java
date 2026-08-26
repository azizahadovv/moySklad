package uz.kassa.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.kassa.bot.TextUtil;
import uz.kassa.config.AppProps;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Reminder;
import uz.kassa.domain.Role;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.ReminderRepo;
import uz.kassa.service.moysklad.MoySkladClient;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Qarz daftari eslatmalari (Отдел Али va barcha xodimlar):
 * tanlangan kunlarda (masalan 3-1 kun oldin) soat 09:00 da, muddat kunida
 * va muddati o'tganda HAR KUNI tanlangan xodimlarga xabar yuboriladi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReminderService {

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final LocalTime NOTIFY_AT = LocalTime.of(9, 0);

    private final ReminderRepo repo;
    private final AppUserRepo userRepo;
    private final NotificationService notify;
    private final AuditService audit;
    private final AppProps props;
    private final MoySkladClient msClient;

    public Reminder save(Reminder r) { return repo.save(r); }

    public Optional<Reminder> find(Long id) { return repo.findById(id); }

    public List<Reminder> activeAll() {
        return repo.findByStatusOrderByDueDateAscIdAsc(Reminder.Status.FAOL);
    }

    public List<Reminder> activeForAgent(String agentMsId) {
        return repo.findByAgentMsIdAndStatusOrderByDueDateAsc(agentMsId, Reminder.Status.FAOL);
    }

    /* ==================== 💵 QISMAN TO'LOV ==================== */

    /** Xodim tomonidan kiritilgan qo'lda to'lov — buxgalter/SuperAdmin tasdig'i kutiladi. */
    @Transactional
    public Reminder requestManualPayment(long id, AppUser requester, long amount) {
        Reminder r = repo.findById(id).orElseThrow(() -> new BusinessException("Eslatma topilmadi"));
        if (r.getStatus() != Reminder.Status.FAOL)
            throw new BusinessException("Bu eslatma allaqachon yopilgan");
        if (r.getPendingManualAmount() != null)
            throw new BusinessException("Bu eslatma uchun allaqachon tasdiq kutilmoqda");
        if (amount <= 0 || amount > r.remain())
            throw new BusinessException("Summa 1 dan " + TextUtil.fmt(r.remain()) + " so'mgacha bo'lishi kerak");
        r.setPendingManualAmount(amount);
        r.setPendingManualBy(requester.getId());
        audit.log(requester.getId(), "TOLOV_SORALDI", "reminder", r.getId(),
                r.getAgentName() + " " + amount);
        return repo.save(r);
    }

    /** Tasdiqlash: qo'lda to'lov repaidManual/repaid ga qo'shiladi, to'liq to'langan bo'lsa avtomatik yopiladi. */
    @Transactional
    public Reminder approveManualPayment(long id, AppUser approver) {
        requireApprover(approver);
        Reminder r = repo.findById(id).orElseThrow(() -> new BusinessException("Eslatma topilmadi"));
        Long pending = r.getPendingManualAmount();
        if (pending == null) throw new BusinessException("Tasdiq kutilayotgan to'lov topilmadi");
        r.setRepaidManual(r.getRepaidManual() + pending);
        r.setRepaid(Math.max(r.getRepaid(), r.getRepaidManual()));
        r.setPendingManualAmount(null);
        r.setPendingManualBy(null);
        if (r.remain() <= 0 && r.getStatus() == Reminder.Status.FAOL)
            r.setStatus(Reminder.Status.BAJARILDI);
        audit.log(approver.getId(), "TOLOV_TASDIQLANDI", "reminder", r.getId(),
                r.getAgentName() + " " + pending);
        return repo.save(r);
    }

    @Transactional
    public Reminder rejectManualPayment(long id, AppUser approver) {
        requireApprover(approver);
        Reminder r = repo.findById(id).orElseThrow(() -> new BusinessException("Eslatma topilmadi"));
        if (r.getPendingManualAmount() == null)
            throw new BusinessException("Tasdiq kutilayotgan to'lov topilmadi");
        r.setPendingManualAmount(null);
        r.setPendingManualBy(null);
        audit.log(approver.getId(), "TOLOV_RAD", "reminder", r.getId(), r.getAgentName());
        return repo.save(r);
    }

    private void requireApprover(AppUser u) {
        if (u.getRole() != Role.BUXGALTER && u.getRole() != Role.SUPERADMIN)
            throw new BusinessException("Faqat buxgalter yoki SuperAdmin tasdiqlay/rad eta oladi");
    }

    /**
     * MoySkladga bog'langan (agentMsId bor) eslatmalarni jonli kontragent balansi bilan
     * solishtirib, avtomatik to'langan qismini yangilaydi. Qo'lda tasdiqlangan to'lovdan
     * kichik bo'lsa — qo'lda kiritilgani ustun turadi (hech qachon kamaymaydi).
     * To'liq yopilgan (qoldiq 0) bo'lsa — eslatma avtomatik BAJARILDI qilinadi.
     */
    @Transactional
    public void syncFromMoySklad() {
        for (Reminder r : activeAll()) {
            if (r.getAgentMsId() == null || r.getAgentMsId().isBlank()) continue;
            try {
                Long bal = msClient.fetchAgentBalanceSom(r.getAgentMsId());
                if (bal == null) continue;
                // MoySklad ishorasi: manfiy — kontragent bizga qarzdor, musbat — biz unga qarzdormiz.
                long liveRemain = r.getDirection() == Reminder.Direction.U_QARZDOR
                        ? Math.max(0, -bal)
                        : Math.max(0, bal);
                long autoRepaid = Math.max(0, r.getAmount() - liveRemain);
                long newRepaid = Math.max(r.getRepaid(), Math.max(autoRepaid, r.getRepaidManual()));
                if (newRepaid == r.getRepaid()) continue;
                r.setRepaid(newRepaid);
                boolean closing = r.remain() <= 0 && r.getStatus() == Reminder.Status.FAOL;
                if (closing) r.setStatus(Reminder.Status.BAJARILDI);
                repo.save(r);
                if (closing) {
                    audit.log(r.getCreatorUserId(), "ESLATMA_AVTOYOPILDI", "reminder", r.getId(),
                            r.getAgentName() + " " + r.getAmount());
                    String text = "✅ Qarz eslatmasi avtomatik yopildi (MoySklad balansiga ko'ra):\n\n"
                            + render(r, false);
                    for (Long uid : r.recipientSet())
                        userRepo.findById(uid).ifPresent(x -> notify.toUser(x.getTelegramId(), text));
                }
            } catch (Exception e) {
                log.warn("Eslatma #{} MoySklad sinxron xatosi: {}", r.getId(), e.getMessage());
            }
        }
    }

    /** Foydalanuvchi ko'radigan eslatmalar: o'zi yaratgan yoki oluvchilar ichida bo'lganlari. */
    public List<Reminder> visibleFor(AppUser u) {
        if (u.getRole() == Role.SUPERADMIN) return activeAll();
        return activeAll().stream()
                .filter(r -> r.getCreatorUserId().equals(u.getId())
                        || r.recipientSet().contains(u.getId()))
                .toList();
    }

    /** Yopish: done=true — bajarildi, false — bekor. Faqat yaratuvchi yoki SuperAdmin. */
    @Transactional
    public Reminder close(Long id, AppUser by, boolean done) {
        Reminder r = repo.findById(id)
                .orElseThrow(() -> new BusinessException("Eslatma topilmadi"));
        if (r.getStatus() != Reminder.Status.FAOL)
            throw new BusinessException("Bu eslatma allaqachon yopilgan");
        if (!r.getCreatorUserId().equals(by.getId()) && by.getRole() != Role.SUPERADMIN)
            throw new BusinessException("Faqat yaratuvchi yoki SuperAdmin yopa oladi");
        r.setStatus(done ? Reminder.Status.BAJARILDI : Reminder.Status.BEKOR);
        audit.log(by.getId(), done ? "ESLATMA_BAJARILDI" : "ESLATMA_BEKOR",
                "reminder", r.getId(), r.getAgentName() + " " + r.getAmount());
        return repo.save(r);
    }

    /** Eslatma matni (ro'yxat va xabarlar uchun). */
    public String render(Reminder r, boolean full) {
        long left = ChronoUnit.DAYS.between(LocalDate.now(props.zoneId()), r.getDueDate());
        String when = left > 0 ? "⏳ " + left + " kun qoldi"
                : left == 0 ? "❗️ BUGUN" : "⚠️ " + (-left) + " kun O'TIB KETDI";
        String dir = r.getDirection() == Reminder.Direction.BIZ_QARZDOR
                ? "🔴 Biz to'lashimiz kerak" : "🟢 U bizga qaytarishi kerak";
        StringBuilder sb = new StringBuilder();
        sb.append("🤝 <b>").append(TextUtil.esc(r.getAgentName())).append("</b>");
        if (r.getAgentInfo() != null && !r.getAgentInfo().isBlank())
            sb.append(" · ").append(TextUtil.esc(r.getAgentInfo()));
        sb.append("\n").append(dir).append(": <b>").append(TextUtil.fmt(r.getAmount()))
          .append("</b> so'm\n📅 Muddat: <b>").append(r.getDueDate().format(DF))
          .append("</b> — ").append(when);
        if (r.getRepaid() > 0)
            sb.append("\n💵 To'landi: <b>").append(TextUtil.fmt(r.getRepaid()))
              .append("</b> · Qoldiq: <b>").append(TextUtil.fmt(r.remain())).append("</b> so'm");
        if (r.getPendingManualAmount() != null)
            sb.append("\n⏳ Tasdiq kutilmoqda: <b>").append(TextUtil.fmt(r.getPendingManualAmount()))
              .append("</b> so'm");
        if (full) {
            if (r.getComment() != null && !r.getComment().isBlank())
                sb.append("\n💬 ").append(TextUtil.esc(r.getComment()));
            if (!r.remindDaySet().isEmpty())
                sb.append("\n🔔 Eslatish: ").append(r.remindDaySet()).append(" kun oldin + muddat kuni");
            String who = String.join(", ", r.recipientSet().stream()
                    .map(id -> userRepo.findById(id).map(AppUser::getFullName).orElse("#" + id))
                    .toList());
            if (!who.isEmpty()) sb.append("\n👥 Xabar oladi: ").append(TextUtil.esc(who));
            sb.append("\n✍️ Kiritgan: ").append(TextUtil.esc(
                    userRepo.findById(r.getCreatorUserId()).map(AppUser::getFullName).orElse("?")));
        }
        return sb.toString();
    }

    /**
     * Har 10 daqiqada chaqiriladi: 09:00 dan keyin, har eslatma uchun kuniga BIR marta.
     * Yuboriladi: tanlangan kunlar (remind_days), muddat kuni va o'tib ketgan har kun.
     */
    public void tick() {
        syncFromMoySklad();
        LocalDate today = LocalDate.now(props.zoneId());
        if (LocalTime.now(props.zoneId()).isBefore(NOTIFY_AT)) return;
        for (Reminder r : activeAll()) {
            try {
                if (today.equals(r.getLastNotified())) continue;
                long left = ChronoUnit.DAYS.between(today, r.getDueDate());
                boolean send = left <= 0 || r.remindDaySet().contains((int) left);
                if (!send) continue;

                String text = "🔔 <b>Qarz eslatmasi</b>\n\n" + render(r, false)
                        + (r.getComment() == null || r.getComment().isBlank()
                            ? "" : "\n💬 " + TextUtil.esc(r.getComment()))
                        + "\n✍️ Kiritgan: " + TextUtil.esc(userRepo.findById(r.getCreatorUserId())
                            .map(AppUser::getFullName).orElse("?"));
                for (Long uid : r.recipientSet())
                    userRepo.findById(uid).ifPresent(x -> {
                        if (x.getTelegramId() != null) notify.toUser(x.getTelegramId(), text);
                    });
                r.setLastNotified(today);
                repo.save(r);
            } catch (Exception e) {
                log.warn("Eslatma #{} yuborishda xato: {}", r.getId(), e.getMessage());
            }
        }
    }
}
