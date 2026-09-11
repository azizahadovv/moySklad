package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 🏬 Ombor kamchiligi — har qanday qoida topilmasi bitta hayot sikli:
 * ochildi (since) → xabar (notified_at) → rahbar (esc1_at) → admin (esc2_at) → yopildi (resolved_at).
 * resolved_by NULL — qoida o'zi topmay qo'ydi (avto), aks holda odam; answer — TUZATDIM / ETIBORSIZ / …
 */
@Entity @Table(name = "ombor_kamchilik")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OmborKamchilik {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "rule_code", nullable = false) private String ruleCode;
    @Column(name = "subject_type", nullable = false) private String subjectType;
    @Column(name = "subject_key", nullable = false) private String subjectKey;
    @Column(name = "kassa_id") private Long kassaId;
    @Column(name = "owner_user_id") private Long ownerUserId;
    @Builder.Default @Column(nullable = false) private String title = "";
    @Builder.Default @Column(nullable = false, columnDefinition = "text") private String detail = "";
    @Builder.Default @Column(nullable = false) private Instant since = Instant.now();
    @Column(name = "notified_at") private Instant notifiedAt;
    @Column(name = "esc1_at") private Instant esc1At;
    @Column(name = "esc2_at") private Instant esc2At;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @Column(name = "resolved_by") private Long resolvedBy;
    private String answer;

    public boolean open() { return resolvedAt == null; }
}
