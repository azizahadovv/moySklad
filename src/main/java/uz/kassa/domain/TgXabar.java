package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDateTime;

/** 📨 Ulangan akkauntga botdan kelgan bitta xabar (jurnal + tekshiruv natijasi). */
@Entity @Table(name = "tg_xabar")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TgXabar {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 24) private String phone;
    @Builder.Default @Column(name = "source_bot", nullable = false) private String sourceBot = "";
    @Column(name = "msg_id", nullable = false) private long msgId;
    @Column(name = "msg_at") private LocalDateTime msgAt;
    @Builder.Default @Column(nullable = false, columnDefinition = "text") private String text = "";
    @Builder.Default @Column(nullable = false, length = 16) private String media = "";
    private Long amount;                 // xabardagi tranzaksiya summasi (tiyin)
    @Column(length = 8) private String dir;          // KIRIM | RASXOD
    private Long balance;                // pastdagi karta qoldig'i (tiyin)
    @Column(name = "card_mask", length = 8) private String cardMask;
    @Column(length = 200) private String merchant;
    /** YANGI — hali tekshirilmagan; OK — muammosiz; OGOH — kalit so'z/shablon; MOS/NOMOS — bizdagi yozuv bilan. */
    @Builder.Default @Column(nullable = false, length = 16) private String verdict = "YANGI";
    @Builder.Default @Column(nullable = false, length = 400) private String note = "";
    @Column(name = "notified_at") private LocalDateTime notifiedAt;
    @Column(name = "created_at") private Instant createdAt;
}
