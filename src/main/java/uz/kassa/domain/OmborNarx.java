package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;

/** 🏬 Yetkazuvchi narxi (tiyin): PRIYOMKA (supply pozitsiyasi). QOLDA/SHEETS — eski yozuvlar, tahlilda ishlatilmaydi. */
@Entity @Table(name = "ombor_narx")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OmborNarx {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "agent_ms_id", nullable = false) private String agentMsId;
    @Column(name = "product_ms_id", nullable = false) private String productMsId;
    @Column(nullable = false) private long price;
    @Builder.Default @Column(nullable = false) private String currency = "UZS";
    @Column(name = "lead_days") private Integer leadDays;
    @Column(name = "at_date", nullable = false) private LocalDate atDate;
    @Builder.Default @Column(nullable = false) private String source = "PRIYOMKA";
    @Builder.Default @Column(nullable = false) private String note = "";
    @Column(name = "created_by") private Long createdBy;
}
