package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;

/** Ledger yozuvi — tizimdagi HAR QANDAY pul harakati shu jadvalda. */
@Entity @Table(name = "operations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Operation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private OpType type;

    @Enumerated(EnumType.STRING) @Column(name = "money_type", nullable = false)
    private MoneyType moneyType;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING) @Column(name = "from_owner_type")
    private OwnerType fromOwnerType;
    @Column(name = "from_owner_id")
    private Long fromOwnerId;

    @Enumerated(EnumType.STRING) @Column(name = "to_owner_type")
    private OwnerType toOwnerType;
    @Column(name = "to_owner_id")
    private Long toOwnerId;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private OpStatus status;

    @Column(name = "category_id")
    private Long categoryId;

    @Enumerated(EnumType.STRING) @Column(name = "transfer_kind")
    private TransferKind transferKind;

    @Column(name = "debt_id")
    private Long debtId;

    @Column(name = "submission_id")
    private Long submissionId;

    private String comment;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "op_date", nullable = false)
    private LocalDate opDate;

    @Column(name = "moysklad_id", unique = true)
    private String moyskladId;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "decided_at")
    private Instant decidedAt;
}
