package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/** 🏬 Qoralama qatori: tovar, miqdor, narx (tiyin), landed narx, asos matni, flaglar. */
@Entity @Table(name = "ombor_qoralama_qator")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OmborQoralamaQator {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "qoralama_id", nullable = false) private Long qoralamaId;
    @Column(name = "product_ms_id", nullable = false) private String productMsId;
    @Builder.Default @Column(nullable = false) private BigDecimal qty = BigDecimal.ZERO;
    @Builder.Default @Column(nullable = false) private long price = 0;
    @Builder.Default @Column(name = "landed_price", nullable = false) private long landedPrice = 0;
    @Builder.Default @Column(nullable = false) private String basis = "";
    @Builder.Default @Column(nullable = false) private String flags = "";
    @Column(name = "sorov_id") private Long sorovId;

    public boolean has(String flag) { return ("," + flags + ",").contains("," + flag + ","); }
    public long lineTotal() { return qty.multiply(BigDecimal.valueOf(landedPrice)).longValue(); }
}
