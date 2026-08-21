package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Telegram ulanmagan bo'lishi mumkin (Sheets'dan yaratilgan kassir) — keyin telefon orqali bog'lanadi. */
    @Column(name = "telegram_id", unique = true)
    private Long telegramId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "kassa_id")
    private Long kassaId;

    @Builder.Default
    private boolean active = true;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
