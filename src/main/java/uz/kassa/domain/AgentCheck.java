package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 🕵️ Kontragent sifat tekshiruvi: MoySklad'da yaratilgan kontragent majburiy maydonlar
 * qoidalariga (K1..K8) tekshiriladi. Xato bo'lsa — yaratgan xodimga xabar, tuzatilguncha OCHIQ.
 */
@Entity @Table(name = "agent_checks")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AgentCheck {

    /** OCHIRILDI — kontragent MoySklad'da o'chirib tashlangan (xato yopiq, ro'yxatda ko'rinmaydi). */
    public enum Status { OK, OCHIQ, TUZATILDI, ETIBORSIZ, OCHIRILDI }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_ms_id", nullable = false, unique = true)
    private String agentMsId;

    @Builder.Default
    @Column(name = "agent_name", nullable = false)
    private String agentName = "";

    /** MoySklad login (audit uid), masalan zufar@newstarbukhara. */
    @Column(name = "created_uid")
    private String createdUid;

    @Column(name = "creator_user_id")
    private Long creatorUserId;

    @Column(name = "kassa_id")
    private Long kassaId;

    @Column(name = "ms_created_at")
    private LocalDateTime msCreatedAt;

    @Column(name = "ms_updated_at")
    private LocalDateTime msUpdatedAt;

    /** Xato kodlari CSV: "K1,K3". */
    @Builder.Default
    @Column(nullable = false, columnDefinition = "text")
    private String violations = "";

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false)
    private Status status = Status.OK;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Column(name = "last_daily")
    private LocalDate lastDaily;

    /** 1-bosqich: otdel rahbariga yuborilgan vaqt. */
    /** 1-bosqich: otdel rahbariga yuborilgan vaqt. */
    @Column(name = "escalated_at")
    private Instant escalatedAt;

    /** 2-bosqich: «tuzatilmadi» — admin + rahbarga yuborilgan vaqt. */
    @Column(name = "escalated2_at")
    private Instant escalated2At;


    @Column(name = "fixed_at")
    private Instant fixedAt;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public java.util.List<String> violationList() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : violations.split(",")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }
}
