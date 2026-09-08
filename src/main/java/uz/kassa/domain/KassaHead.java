package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;

/** Otdel (kassa) rahbari — o'z otdeli qarzdorlari va kontragent xatolarini oladi/ko'radi. */
@Entity @Table(name = "kassa_heads", uniqueConstraints = @UniqueConstraint(columnNames = {"kassa_id", "user_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class KassaHead {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kassa_id", nullable = false)
    private Long kassaId;

    @Column(name = "user_id", nullable = false)
    private Long userId;
}
