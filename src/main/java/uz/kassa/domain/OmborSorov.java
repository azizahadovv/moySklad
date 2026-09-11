package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/** 🏬 Do'kon so'rovi: YANGI → KORILDI | QORALAMADA | RAD | BAJARILDI. reason: YOQ/KAM/MIJOZ/YANGI/HAMKOR/LOT. */
@Entity @Table(name = "ombor_sorov")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OmborSorov {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "kassa_id") private Long kassaId;
    @Column(name = "product_ms_id") private String productMsId;
    @Builder.Default @Column(nullable = false) private String text = "";
    @Builder.Default @Column(nullable = false) private BigDecimal qty = BigDecimal.ONE;
    @Builder.Default @Column(nullable = false) private String reason = "YOQ";
    @Builder.Default @Column(nullable = false) private String status = "YANGI";
    @Column(name = "by_user_id") private Long byUserId;
    @Builder.Default @Column(nullable = false) private String answer = "";
    @Column(name = "answered_by") private Long answeredBy;
    @Column(name = "answered_at") private Instant answeredAt;
    @Builder.Default @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    public static String reasonTitle(String r) {
        return switch (r) {
            case "YOQ" -> "❌ Tovar yo'q (mijoz so'radi)"; case "KAM" -> "📉 Kam qoldi"; case "MIJOZ" -> "🙋 Mijoz buyurtmasi";
            case "YANGI" -> "🆕 Yangi tovar (sinov partiyasi)"; case "HAMKOR" -> "🤝 Hamkor do'kon so'rovi"; case "LOT" -> "📑 Tender loti";
            default -> r;
        };
    }
    public static String statusTitle(String s) {
        return switch (s) {
            case "YANGI" -> "🆕 yangi"; case "KORILDI" -> "👀 ko'rildi"; case "QORALAMADA" -> "🧾 qoralamada"; case "RAD" -> "❌ rad"; case "BAJARILDI" -> "✅ bajarildi";
            default -> s;
        };
    }
}
