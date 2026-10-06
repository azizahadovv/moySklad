package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 📒 Adesk ↔ MoySklad bog'lanishi (V46, docs/ADESK.md §3): MoySklad kaliti ↔ Adesk id.
 * Bitta (kind, msKey) — bitta qator; hash o'zgarmasa qayta yuborilmaydi.
 */
@Entity @Table(name = "adesk_link")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AdeskLink {

    public static final String ORG = "ORG", ACCOUNT = "ACCOUNT", CATEGORY = "CATEGORY", CONTRACTOR = "CONTRACTOR",
            EMPLOYEE = "EMPLOYEE", ORGC = "ORGC", PRODUCT = "PRODUCT", MONEY = "MONEY", COMMIT = "COMMIT";
    public static final String OK = "OK", ERROR = "ERROR", DELETED = "DELETED", SKIP = "SKIP";
    public static final String FROM_MS = "MS", FROM_AD = "AD";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 16) private String kind;
    @Column(name = "ms_key", nullable = false, length = 160) private String msKey;
    @Column(name = "adesk_id") private Long adeskId;
    @Column(length = 300) private String name;
    @Column(length = 64) private String hash;
    @Column(name = "ms_type", length = 24) private String msType;
    @Column(name = "doc_date") private LocalDate docDate;
    @Column(name = "sum_tiyin") private Long sumTiyin;
    @Column(name = "account_key", length = 100) private String accountKey;
    @Builder.Default @Column(nullable = false, length = 12) private String status = OK;
    @Column(columnDefinition = "text") private String error;
    @Builder.Default @Column(nullable = false, length = 4) private String origin = FROM_MS;
    @Builder.Default @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    public boolean ok() { return OK.equals(status) && adeskId != null; }
}
