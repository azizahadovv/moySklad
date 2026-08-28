package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "kassa")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Kassa {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "moysklad_store_id")
    private String moyskladStoreId;

    /** MoySklad otdel (group) UUID — Приходный/Расходный ордер shu bog'lanish orqali kassaga yoziladi. */
    @Column(name = "moysklad_group_id")
    private String moyskladGroupId;

    @Builder.Default
    private boolean active = true;

    /** Haqiqiy naqd kassa emas (masalan «Отдел Али» — faqat kontragent xodimlari guruhi).
     *  Pul hisobotlarida (Бугунги тушум, Баланс) ko'rsatilmaydi. */
    @Builder.Default
    private boolean cashless = false;

    /** true — nom qo'lda qo'yilgan, MoySklad nom-yangilashi unga tegmaydi. */
    @Builder.Default
    @Column(name = "name_locked", nullable = false)
    private boolean nameLocked = false;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
