package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** Guruhda ko'ringan (yozgan/qo'shilgan) odam — {hamma} shabloni shu registrdan oladi. */
@Entity @Table(name = "group_members",
        uniqueConstraints = @UniqueConstraint(columnNames = {"chat_id", "user_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GroupMember {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    private String username;

    @Column(name = "first_name")
    private String firstName;

    @Builder.Default
    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen = Instant.now();
}
