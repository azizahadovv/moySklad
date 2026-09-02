package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;

/** Kunlik kassa solishtirish hisoboti — moliya menejeri (buxgalter/SuperAdmin) tasdig'i. */
@Entity @Table(name = "daily_report_confirm")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DailyReportConfirm {
    @Id @Column(name = "report_date")
    private LocalDate reportDate;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "user_name")
    private String userName;

    @Builder.Default
    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt = Instant.now();
}
