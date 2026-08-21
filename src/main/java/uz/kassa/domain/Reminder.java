package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;

/** Kontragent qarz eslatmasi (qarz daftari yozuvi). */
@Entity @Table(name = "reminders")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Reminder {

    public enum Direction { BIZ_QARZDOR, U_QARZDOR }
    public enum Status { FAOL, BAJARILDI, BEKOR }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "creator_user_id", nullable = false)
    private Long creatorUserId;

    @Column(name = "agent_ms_id")
    private String agentMsId;

    @Column(name = "agent_name", nullable = false)
    private String agentName;

    @Column(name = "agent_info")
    private String agentInfo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    @Column(nullable = false)
    private long amount;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(columnDefinition = "text")
    private String comment;

    /** Necha kun oldin eslatish, CSV: "3,1". Muddat kuni har doim eslatiladi. */
    @Column(name = "remind_days", nullable = false)
    @Builder.Default
    private String remindDays = "";

    /** Xabar boradigan users.id lar, CSV. */
    @Column(nullable = false)
    @Builder.Default
    private String recipients = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.FAOL;

    @Column(name = "last_notified")
    private LocalDate lastNotified;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public java.util.Set<Integer> remindDaySet() {
        java.util.Set<Integer> out = new java.util.TreeSet<>();
        for (String p : remindDays.split(","))
            if (!p.isBlank()) try { out.add(Integer.parseInt(p.trim())); } catch (NumberFormatException ignored) {}
        return out;
    }

    public java.util.Set<Long> recipientSet() {
        java.util.Set<Long> out = new java.util.LinkedHashSet<>();
        for (String p : recipients.split(","))
            if (!p.isBlank()) try { out.add(Long.parseLong(p.trim())); } catch (NumberFormatException ignored) {}
        return out;
    }
}
