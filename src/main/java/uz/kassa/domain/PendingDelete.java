package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Belgilangan vaqtda o'chiriladigan Telegram xabari (restartdan keyin ham saqlanadi). */
@Entity @Table(name = "pending_deletes")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PendingDelete {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private long chatId;

    @Column(name = "message_id", nullable = false)
    private int messageId;

    @Column(name = "delete_at", nullable = false)
    private Instant deleteAt;
}
