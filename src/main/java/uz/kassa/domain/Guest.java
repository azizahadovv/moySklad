package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** Botga yozgan, lekin hali tizimga qo'shilmagan foydalanuvchi. */
@Entity @Table(name = "guests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Guest {
    @Id @Column(name = "telegram_id")
    private Long telegramId;

    private String name;

    private String username;

    /** «Kontakt ulashish» tugmasi orqali kelgan telefon raqami. */
    private String phone;

    @Builder.Default
    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen = Instant.now();

    @Builder.Default
    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen = Instant.now();
}
