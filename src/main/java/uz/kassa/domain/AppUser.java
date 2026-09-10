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

    /** MoySklad xodimi (employee UUID) — otgruzka/kontragent egasini botdagi odamga bog'lash. */
    @Column(name = "ms_employee_id")
    private String msEmployeeId;

    /** MoySklad login (audit uid), masalan zufar@newstarbukhara. */
    @Column(name = "ms_uid")
    private String msUid;

    /** 🕵️ Назорат: Telegram ulangach ochiq xatolar bir marta yuborilgan vaqti (NULL — hali yuborilmagan). */
    @Column(name = "control_welcome_at")
    private Instant controlWelcomeAt;

    /** 🔗 Taklif havolasi tokeni (t.me/<bot>?start=inv_<token>) — bir martalik, ulangach NULL. */
    @Column(name = "invite_token")
    private String inviteToken;

    /** Taklif havolasi muddati (24 soat). */
    @Column(name = "invite_expires_at")
    private Instant inviteExpiresAt;

    /** 💼 Lavozim (qo'lda, faqat ko'rsatish uchun — huquqlarga ta'sir qilmaydi). */
    @Column(name = "job_title")
    private String jobTitle;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
