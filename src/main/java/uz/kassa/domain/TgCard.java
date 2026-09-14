package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDateTime;

/** 💳 Karta qoldig'i — bot xabaridan avtomat olingan oxirgi holat (mask kesimida). Summalar TIYINDA. */
@Entity @Table(name = "tg_card")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TgCard {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Builder.Default @Column(name = "source_bot", nullable = false) private String sourceBot = "";
    @Column(nullable = false, length = 8) private String mask;
    @Builder.Default @Column(nullable = false, length = 64) private String name = "";
    @Column(length = 24) private String phone;
    @Column(name = "kassa_id") private Long kassaId;
    @Builder.Default @Column(nullable = false, length = 8) private String currency = "UZS";
    @Builder.Default @Column(nullable = false) private long balance = 0;
    @Column(name = "last_dir", length = 8) private String lastDir;
    @Column(name = "last_amount") private Long lastAmount;
    @Builder.Default @Column(name = "last_merchant", nullable = false, length = 200) private String lastMerchant = "";
    @Column(name = "last_txn_at") private LocalDateTime lastTxnAt;
    @Column(name = "updated_at") private Instant updatedAt;
}
