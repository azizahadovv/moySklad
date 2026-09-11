package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;

/** 🏬 Davr: AKSIYA / MAVSUM / SOVISH / YANGI — tovar yoki guruh (folder_name) bo'yicha. */
@Entity @Table(name = "ombor_davr")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OmborDavr {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private String kind;
    @Column(name = "product_ms_id") private String productMsId;
    @Column(name = "folder_name") private String folderName;
    @Builder.Default @Column(nullable = false) private String code = "";
    @Column(name = "from_date", nullable = false) private LocalDate fromDate;
    @Column(name = "to_date", nullable = false) private LocalDate toDate;
    @Builder.Default @Column(nullable = false) private String note = "";
    @Column(name = "created_by") private Long createdBy;
    @Builder.Default @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
}
