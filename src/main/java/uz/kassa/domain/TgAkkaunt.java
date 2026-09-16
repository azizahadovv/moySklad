package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDateTime;

/** 📨 tg-reader orqali ulangan Telegram akkaunt (bitta botni o'qiydi). */
@Entity @Table(name = "tg_akkaunt")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TgAkkaunt {
    @Id @Column(length = 24) private String phone;
    @Builder.Default @Column(nullable = false) private String name = "";
    @Column(name = "tg_user_id") private Long tgUserId;
    @Builder.Default @Column(nullable = false) private String username = "";
    @Builder.Default @Column(name = "source_bot", nullable = false) private String sourceBot = "";
    @Column(name = "user_id") private Long userId;
    @Column(name = "kassa_id") private Long kassaId;
    @Builder.Default @Column(name = "last_msg_id", nullable = false) private long lastMsgId = 0;
    @Column(name = "last_seen_at") private Instant lastSeenAt;
    @Column(name = "last_msg_at") private LocalDateTime lastMsgAt;
    @Column(name = "last_error") private String lastError;
    @Builder.Default @Column(nullable = false) private boolean active = true;
    @Column(name = "created_at") private Instant createdAt;
    /** 🔐 Xavfsizlik (securityTick): 2FA bor-yo'qligi, faol seanslar (JSON, tg-reader'dan) va tekshirilgan vaqt (V42). */
    @Column(name = "two_fa") private Boolean twoFa;
    @Column(name = "sessions_json", columnDefinition = "text") private String sessionsJson;
    @Column(name = "sessions_at") private Instant sessionsAt;
}
