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

    /** MoySklad "organizationAccount" UUID — shu hisobga tushgan Klik to'lovlari avtomatik biriktiriladi. */
    @Column(name = "moysklad_account_id")
    private String moyskladAccountId;

    /** Qaysi otdel (kassa)ga tegishli — balans/hisobotlarda shu kesimda guruhlanadi. */
    @Column(name = "kassa_id")
    private Long kassaId;

    /** true — nom qo'lda qo'yilgan, MoySklad nom-yangilashi unga tegmaydi. */
    @Builder.Default
    @Column(name = "name_locked", nullable = false)
    private boolean nameLocked = false;

    @Builder.Default
    private boolean active = true;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
