package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * 🏬 Ombor qoidasi — tekshiruv KOD emas, QATOR (docs/OMBOR-TZ.md §5).
 * checker — umumiy tekshiruvchi nomi, params — JSON matn, severity — INFO/OGOH/MUHIM,
 * to_role — XODIM/ZAVSKLAD/ZAKUPSHIK/RAHBAR/DIREKTOR/ADMIN.
 */
@Entity @Table(name = "ombor_qoida")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OmborQoida {
    @Id private String code;
    @Builder.Default @Column(nullable = false) private String title = "";
    @Column(nullable = false) private String checker;
    @Builder.Default @Column(nullable = false, columnDefinition = "text") private String params = "{}";
    @Builder.Default @Column(nullable = false) private String severity = "OGOH";
    @Builder.Default @Column(name = "to_role", nullable = false) private String toRole = "ZAVSKLAD";
    @Builder.Default @Column(name = "esc1_min", nullable = false) private int esc1Min = 240;
    @Builder.Default @Column(name = "esc2_min", nullable = false) private int esc2Min = 1440;
    @Builder.Default @Column(nullable = false) private boolean enabled = true;
    @Builder.Default @Column(nullable = false) private int sort = 100;

    public boolean silent() { return "INFO".equals(severity); }
    public String emoji() { return switch (severity) { case "MUHIM" -> "🔴"; case "OGOH" -> "🟠"; default -> "ℹ️"; }; }
}
