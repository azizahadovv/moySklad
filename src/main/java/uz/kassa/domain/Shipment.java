package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 📦 Otgruzka (MoySklad demand) to'lov nazorati yozuvi.
 * KUTILMOQDA → (2 soat, to'lanmagan) → QARZ → (to'landi) → YOPILDI; BEKOR — MoySklad'da o'chirilgan/bekor.
 * Summalar SO'MDA.
 */
@Entity @Table(name = "shipments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Shipment {

    public enum Status { KUTILMOQDA, TOLANGAN, QARZ, YOPILDI, BEKOR }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ms_id", nullable = false, unique = true)
    private String msId;

    @Builder.Default
    @Column(name = "doc_no", nullable = false)
    private String docNo = "";

    private LocalDateTime moment;

    @Column(name = "ms_created")
    private LocalDateTime msCreated;

    @Column(name = "ms_updated")
    private LocalDateTime msUpdated;

    @Column(name = "agent_ms_id")
    private String agentMsId;

    @Builder.Default
    @Column(name = "agent_name", nullable = false)
    private String agentName = "";

    @Builder.Default
    @Column(name = "agent_phone", nullable = false)
    private String agentPhone = "";

    /** Qarzdor eslatmasi oxirgi yuborilgan kun (takror eslatmalar — kuniga bir). */
    @Column(name = "remind_sent")
    private LocalDate remindSent;

    /** MoySklad companyType: legal | entrepreneur | individual; "" — hali o'qilmagan. */
    @Builder.Default
    @Column(name = "agent_type", nullable = false)
    private String agentType = "";

    @Column(name = "owner_ms_id")
    private String ownerMsId;

    @Column(name = "owner_uid")
    private String ownerUid;

    @Builder.Default
    @Column(name = "owner_name", nullable = false)
    private String ownerName = "";

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    /** «Масъул» maydoni (MoySklad custom entity nomi). */
    @Builder.Default
    @Column(nullable = false)
    private String masul = "";

    @Column(name = "masul_user_id")
    private Long masulUserId;

    @Column(name = "ms_group_id")
    private String msGroupId;

    @Column(name = "kassa_id")
    private Long kassaId;

    @Builder.Default
    @Column(nullable = false)
    private long sum = 0;

    @Builder.Default
    @Column(name = "payed_sum", nullable = false)
    private long payedSum = 0;

    /** Kontragent MoySklad balansi (manfiy = bizga qarzdor), oxirgi tekshiruvda. */
    @Column(name = "agent_balance")
    private Long agentBalance;

    @Builder.Default
    @Column(nullable = false)
    private String state = "";

    @Column(name = "due_at")
    private LocalDate dueAt;

    @Column(columnDefinition = "text")
    private String comment;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "control_status", nullable = false)
    private Status controlStatus = Status.KUTILMOQDA;

    @Column(name = "check_at")
    private Instant checkAt;

    @Column(name = "debt_since")
    private Instant debtSince;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "close_reason")
    private String closeReason;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "last_daily")
    private LocalDate lastDaily;

    @Column(name = "reminder_id")
    private Long reminderId;

    /** Eski qarz — modul yoqilganda xabarsiz yuklangan. */
    @Builder.Default
    @Column(nullable = false)
    private boolean silent = false;

    /** Kamchilik kodlari CSV: O1 muddat yo'q · O2 Масъул yo'q · O3 status yo'q · O4 izoh yo'q · O5 kontragent telefoni yo'q. */
    @Builder.Default
    @Column(nullable = false)
    private String issues = "";

    @Column(name = "issues_since")
    private Instant issuesSince;

    /** Xodimga (guruhlangan) xabar berilgan vaqt; null — hali xabar yo'q. */
    @Column(name = "issues_notified_at")
    private Instant issuesNotifiedAt;

    public java.util.List<String> issueList() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : issues.split(",")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** Qoldiq (manfiy bo'lmaydi). */
    public long remain() { return Math.max(0, sum - payedSum); }

    /** «Карз» / «Карз перечисление» — xodim qarzga berganini belgilagan («Карз туланди» EMAS). */
    public boolean isDebtState() {
        String s = state.toLowerCase().replace('ў', 'у').replace('қ', 'к');
        return (s.startsWith("карз") || s.startsWith("qarz")) && !s.contains("туланди") && !s.contains("tulandi");
    }
}
