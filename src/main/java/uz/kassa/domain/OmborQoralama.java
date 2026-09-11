package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** 🏬 Buyurtma qoralamasi: QORALAMA → ZAKUPSHIK → ZAVSKLAD → [DIREKTOR] → TASDIQ → YUBORILDI | BEKOR. Summalar TIYINDA. */
@Entity @Table(name = "ombor_qoralama")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OmborQoralama {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "kassa_id") private Long kassaId;
    @Column(name = "agent_ms_id") private String agentMsId;
    @Builder.Default @Column(name = "agent_name", nullable = false) private String agentName = "";
    @Builder.Default @Column(nullable = false) private String status = "QORALAMA";
    @Builder.Default @Column(nullable = false) private long total = 0;
    @Builder.Default @Column(name = "cash_available", nullable = false) private long cashAvailable = 0;
    @Builder.Default @Column(nullable = false, columnDefinition = "text") private String note = "";
    @Builder.Default @Column(name = "built_at", nullable = false) private Instant builtAt = Instant.now();
    @Builder.Default @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Column(name = "zakupshik_by") private Long zakupshikBy;
    @Column(name = "zavsklad_by") private Long zavskladBy;
    @Column(name = "direktor_by") private Long direktorBy;
    @Column(name = "sent_by") private Long sentBy;
    @Column(name = "sent_at") private Instant sentAt;

    public boolean open() { return !status.equals("YUBORILDI") && !status.equals("BEKOR"); }

    public static String statusTitle(String s) {
        return switch (s) {
            case "QORALAMA" -> "📝 qoralama (zakupshik tekshiradi)"; case "ZAKUPSHIK" -> "🛒 zakupshik tasdiqladi (zavsklad sanog'i)";
            case "ZAVSKLAD" -> "📦 zavsklad tasdiqladi (direktor)"; case "DIREKTOR" -> "👔 direktor tasdiqladi";
            case "TASDIQ" -> "✅ tasdiqlandi (yuborish mumkin)"; case "YUBORILDI" -> "📤 yuborildi"; case "BEKOR" -> "❌ bekor";
            default -> s;
        };
    }
}
