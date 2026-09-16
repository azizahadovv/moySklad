package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;

/**
 * ⚖️ Jarima — xodimga yozilgan ogohlantirish yoki jarima (docs/JARIMA.md). Summalar SO'MDA.
 * Birinchi holat(lar) — {@link Holat#OGOH} (summa 0), keyingilari — asosning foizi ({@link Holat#OCHIQ}),
 * admin yopadi ({@link Holat#YOPIQ}) yoki bekor qiladi ({@link Holat#BEKOR}).
 */
@Entity @Table(name = "jarima")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Jarima {

    public enum Tur { KARTA, KONTRAGENT, OTGRUZKA }
    public enum Holat { OGOH, OCHIQ, YOPIQ, BEKOR }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 12)
    private Tur tur;

    @Builder.Default
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8)
    private Holat holat = Holat.OCHIQ;

    /** Bot foydalanuvchisi (bog'lanmagan MoySklad xodimi bo'lsa null). */
    @Column(name = "user_id")
    private Long userId;

    /** "u:<users.id>" yoki "n:<ism>" — ogohlantirish sanog'i shu kalit + tur bo'yicha. */
    @Column(nullable = false, length = 160)
    private String kalit;

    /** Ko'rsatish uchun ism (yozilgan paytdagi). */
    @Builder.Default
    @Column(nullable = false, length = 200)
    private String xodim = "";

    @Column(name = "kassa_id")
    private Long kassaId;

    /** Manba kaliti: "click:<id>", "agent:<msId>", "demand:<msId>" — takror yozmaslik uchun. */
    @Builder.Default
    @Column(nullable = false, length = 80)
    private String manba = "";

    /** Karta nomi / kontragent nomi / «№doc · klient». */
    @Builder.Default
    @Column(name = "manba_nomi", nullable = false, length = 400)
    private String manbaNomi = "";

    /** Hisoblash asosi, so'm (karta qoldig'i / bazaviy summa / otgruzka summasi). */
    @Builder.Default
    @Column(nullable = false)
    private long asos = 0;

    @Builder.Default
    @Column(nullable = false)
    private double foiz = 1;

    /** Jarima summasi, so'm (OGOH — 0). */
    @Builder.Default
    @Column(nullable = false)
    private long summa = 0;

    /** To'liq sabab — «nimaga?» savoliga javob (kirill, xabarlarda aynan shu ko'rsatiladi). */
    @Builder.Default
    @Column(nullable = false, columnDefinition = "text")
    private String sabab = "";

    /** Shu xodimning shu turdagi nechanchi holati (ogohlantirishlar ham sanaladi). */
    @Builder.Default
    @Column(nullable = false)
    private int tartib = 1;

    /** Toshkent sanasi (kunlik jamlama shu bo'yicha). */
    @Column(nullable = false)
    private LocalDate sana;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** Xodimga darhol xabar yuborilgan vaqt. */
    @Column(name = "xabar_at")
    private Instant xabarAt;

    /** Kunlik jamlamaga kiritilgan vaqt. */
    @Column(name = "kunlik_at")
    private Instant kunlikAt;

    @Column(name = "yopilgan_at")
    private Instant yopilganAt;

    @Column(name = "yopgan_user_id")
    private Long yopganUserId;

    /** Yopish/bekor sababi (admin yozadi). */
    @Column(length = 400)
    private String izoh;

    public boolean isOchiq() { return holat == Holat.OCHIQ; }
}
