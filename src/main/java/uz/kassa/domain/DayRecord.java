package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;

/** Kassaning bir kunlik yozuvi. KUN_SOF = prixod - vozvrat + kirim - chiqim - rasxod. */
@Entity @Table(name = "days")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DayRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kassa_id", nullable = false)
    private Long kassaId;

    @Column(nullable = false)
    private LocalDate date;

    @Builder.Default @Column(name = "prixod_naqd")     private long prixodNaqd = 0;
    @Builder.Default @Column(name = "prixod_klik")     private long prixodKlik = 0;
    @Builder.Default @Column(name = "prixod_terminal") private long prixodTerminal = 0;
    @Builder.Default @Column(name = "vozvrat_naqd")    private long vozvratNaqd = 0;
    @Builder.Default @Column(name = "vozvrat_klik")    private long vozvratKlik = 0;
    @Builder.Default @Column(name = "rasxod_naqd")     private long rasxodNaqd = 0;
    @Builder.Default @Column(name = "rasxod_klik")     private long rasxodKlik = 0;
    @Builder.Default @Column(name = "kirim_naqd")      private long kirimNaqd = 0;
    @Builder.Default @Column(name = "kirim_klik")      private long kirimKlik = 0;
    @Builder.Default @Column(name = "chiqim_naqd")     private long chiqimNaqd = 0;
    @Builder.Default @Column(name = "chiqim_klik")     private long chiqimKlik = 0;
    @Builder.Default @Column(name = "covered_naqd")    private long coveredNaqd = 0;
    @Builder.Default @Column(name = "covered_klik")    private long coveredKlik = 0;

    @Builder.Default
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private DayStatus status = DayStatus.OCHIQ;

    public long netNaqd() { return prixodNaqd - vozvratNaqd + kirimNaqd - chiqimNaqd - rasxodNaqd; }
    public long netKlik() { return prixodKlik - vozvratKlik + kirimKlik - chiqimKlik - rasxodKlik; }
    public long remainNaqd() { return netNaqd() - coveredNaqd; }
    public long remainKlik() { return netKlik() - coveredKlik; }
}
