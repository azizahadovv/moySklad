package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.time.Instant;

/** Kesh-balans. MAVJUD qoldiq = amount - reserved (TZ 6-bo'lim). */
@Entity @Table(name = "balances")
@IdClass(Balance.Key.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Balance {
    @Id @Enumerated(EnumType.STRING) @Column(name = "owner_type")
    private OwnerType ownerType;

    @Id @Column(name = "owner_id")
    private Long ownerId;

    @Id @Enumerated(EnumType.STRING) @Column(name = "money_type")
    private MoneyType moneyType;

    @Builder.Default
    private long amount = 0;

    @Builder.Default
    private long reserved = 0;

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public long available() { return amount - reserved; }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private OwnerType ownerType;
        private Long ownerId;
        private MoneyType moneyType;
    }
}
