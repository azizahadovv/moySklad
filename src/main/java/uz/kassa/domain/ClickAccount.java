package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** Alohida Click hisobi — qoldig'i balances(owner_type=CLICK) da yuritiladi. */
@Entity @Table(name = "click_accounts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ClickAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Builder.Default
    private boolean active = true;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
