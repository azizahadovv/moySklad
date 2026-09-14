package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.math.BigDecimal;

/** 🏬 Hujjat pozitsiyasi (tovar, miqdor, narx tiyinda, chegirma %; inventory — hisobdagi miqdor). */
@Entity @Table(name = "ombor_pozitsiya")
@IdClass(OmborPozitsiya.Key.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OmborPozitsiya {
    @Id @Column(name = "hujjat_id") private Long hujjatId;
    @Id @Column(name = "product_ms_id") private String productMsId;
    @Builder.Default @Column(nullable = false) private BigDecimal qty = BigDecimal.ZERO;
    @Builder.Default @Column(nullable = false) private long price = 0;
    /** Chegirma % (demand): haqiqiy narx = price × (1 − discount/100). V36. */
    @Builder.Default @Column(nullable = false) private BigDecimal discount = BigDecimal.ZERO;
    @Column(name = "calculated_qty") private BigDecimal calculatedQty;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long hujjatId;
        private String productMsId;
    }
}
