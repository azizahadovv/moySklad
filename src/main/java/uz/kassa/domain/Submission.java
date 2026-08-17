package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Hisobot topshirish (achot) — bir yoki bir necha kun uchun (TZ 7.5). */
@Entity @Table(name = "submissions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Submission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kassa_id", nullable = false)
    private Long kassaId;

    @Builder.Default private long naqd = 0;
    @Builder.Default private long klik = 0;

    @Column(name = "accepted_naqd") private Long acceptedNaqd;
    @Column(name = "accepted_klik") private Long acceptedKlik;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private SubmissionStatus status;

    @Column(name = "submitted_by", nullable = false)
    private Long submittedBy;

    @Column(name = "decided_by")
    private Long decidedBy;

    private String comment;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "submission_days", joinColumns = @JoinColumn(name = "submission_id"))
    @Column(name = "day_id")
    private List<Long> dayIds = new ArrayList<>();
}
