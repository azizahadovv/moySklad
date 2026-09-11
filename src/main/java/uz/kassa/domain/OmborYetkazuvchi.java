package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 🏬 Yetkazuvchi profili (MoySklad kontragent). Muddat: ertalab (cutoff_hour gacha) am, keyin pm. */
@Entity @Table(name = "ombor_yetkazuvchi")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OmborYetkazuvchi {
    @Id @Column(name = "agent_ms_id") private String agentMsId;
    @Builder.Default @Column(nullable = false) private String name = "";
    @Builder.Default @Column(nullable = false) private String country = "";
    @Builder.Default @Column(name = "lead_days_am", nullable = false) private int leadDaysAm = 1;
    @Builder.Default @Column(name = "lead_days_pm", nullable = false) private int leadDaysPm = 3;
    @Builder.Default @Column(name = "cutoff_hour", nullable = false) private int cutoffHour = 12;
    @Builder.Default @Column(name = "landed_coef", nullable = false) private BigDecimal landedCoef = BigDecimal.ONE;
    @Builder.Default @Column(name = "min_history", nullable = false) private int minHistory = 3;
    @Builder.Default @Column(nullable = false) private boolean active = true;
    @Builder.Default @Column(name = "supplies_n", nullable = false) private int suppliesN = 0;
    @Column(name = "last_supply") private LocalDate lastSupply;
    @Builder.Default @Column(nullable = false) private String note = "";

    public int leadDays(int hourNow) { return hourNow < cutoffHour ? leadDaysAm : leadDaysPm; }
    public boolean trusted() { return suppliesN >= minHistory; }
}
