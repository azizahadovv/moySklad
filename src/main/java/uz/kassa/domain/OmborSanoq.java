package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 🏬 Rotatsion sanoq topshirig'i: REJA → KIRITILDI (fakt) → TASDIQ. */
@Entity @Table(name = "ombor_sanoq")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OmborSanoq {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "kassa_id", nullable = false) private Long kassaId;
    @Column(name = "product_ms_id", nullable = false) private String productMsId;
    @Column(name = "plan_date", nullable = false) private LocalDate planDate;
    @Builder.Default @Column(nullable = false) private String abc = "C";
    @Builder.Default @Column(name = "system_qty", nullable = false) private BigDecimal systemQty = BigDecimal.ZERO;
    @Column(name = "fact_qty") private BigDecimal factQty;
    @Builder.Default @Column(nullable = false) private String status = "REJA";
    @Column(name = "by_user_id") private Long byUserId;
    private Instant at;

    public boolean diff() { return factQty != null && factQty.compareTo(systemQty) != 0; }
}
