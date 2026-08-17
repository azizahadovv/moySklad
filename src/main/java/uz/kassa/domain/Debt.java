package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** Qarz registri: debtor -> creditor (TZ 7.4). */
@Entity @Table(name = "debts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Debt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING) @Column(name = "debtor_type", nullable = false)
    private OwnerType debtorType;
    @Column(name = "debtor_id", nullable = false)
    private Long debtorId;

    @Enumerated(EnumType.STRING) @Column(name = "creditor_type", nullable = false)
    private OwnerType creditorType;
    @Column(name = "creditor_id", nullable = false)
    private Long creditorId;

    @Enumerated(EnumType.STRING) @Column(name = "money_type", nullable = false)
    private MoneyType moneyType;

    private long amount;

    @Builder.Default
    private long repaid = 0;

    @Builder.Default
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private DebtStatus status = DebtStatus.OCHIQ;

    private String reason;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "closed_at")
    private Instant closedAt;

    public long remain() { return amount - repaid; }
}
