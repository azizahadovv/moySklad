package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDateTime;

/** 🏬 Ombor sinxron holati — har obyekt turi (assortment, store, stock …) uchun bitta qator. */
@Entity @Table(name = "ombor_sinxron")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OmborSinxron {
    @Id private String entity;
    /** updated>= kursori (bot vaqti). */
    @Column(name = "cursor_at") private LocalDateTime cursorAt;
    @Column(name = "last_ok_at") private Instant lastOkAt;
    @Column(name = "last_error", columnDefinition = "text") private String lastError;
    @Builder.Default @Column(name = "rows_n", nullable = false) private int rowsN = 0;
}
