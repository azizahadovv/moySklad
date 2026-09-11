package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** 🏬 MoySklad ombori (entity/store) ↔ bot kassasi (do'kon). */
@Entity @Table(name = "ombor_store")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OmborStore {
    @Id @Column(name = "ms_id") private String msId;
    @Builder.Default @Column(nullable = false) private String name = "";
    @Builder.Default private boolean archived = false;
    @Column(name = "kassa_id") private Long kassaId;
    @Column(name = "sync_at") private Instant syncAt;
}
