package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/** 🏬 MoySklad tovari (assortment: product / variant / bundle). Narxlar TIYINDA. */
@Entity @Table(name = "ombor_tovar")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OmborTovar {

    @Id @Column(name = "ms_id") private String msId;
    @Builder.Default @Column(nullable = false) private String type = "product";
    @Builder.Default @Column(nullable = false) private String name = "";
    @Builder.Default @Column(name = "name_norm", nullable = false) private String nameNorm = "";
    @Builder.Default @Column(nullable = false) private String article = "";
    @Builder.Default @Column(nullable = false) private String code = "";
    @Builder.Default @Column(nullable = false) private String barcode = "";
    @Builder.Default @Column(name = "folder_name", nullable = false) private String folderName = "";
    @Builder.Default @Column(nullable = false) private String uom = "";
    @Builder.Default @Column(name = "buy_price", nullable = false) private long buyPrice = 0;
    @Builder.Default @Column(name = "sale_price", nullable = false) private long salePrice = 0;
    @Builder.Default @Column(name = "min_balance", nullable = false) private BigDecimal minBalance = BigDecimal.ZERO;
    @Builder.Default private boolean archived = false;
    @Column(name = "ms_updated") private LocalDateTime msUpdated;
    @Column(name = "sync_at") private Instant syncAt;

    /** Dublikat va qidiruv uchun normallashgan nom: kichik harf, ё→е, faqat harf/raqam. */
    public static String norm(String s) {
        if (s == null) return "";
        String t = s.toLowerCase().replace('ё', 'е').replace('ў', 'у').replace('қ', 'к').replace('ғ', 'г').replace('ҳ', 'х');
        return t.replaceAll("[^\\p{L}\\p{N}]+", "");
    }
}
