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

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

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

    public Reminder save(Reminder r) { return repo.save(r); }

    public List<Reminder> activeAll() {
        return repo.findByStatusOrderByDueDateAscIdAsc(Reminder.Status.FAOL);
    }

    public List<Reminder> activeForAgent(String agentMsId) {
        return repo.findByAgentMsIdAndStatusOrderByDueDateAsc(agentMsId, Reminder.Status.FAOL);
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
