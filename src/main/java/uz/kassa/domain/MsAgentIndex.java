package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** MoySklad kontragentlari indeksi — dublikat (telefon/nom) tekshiruvi uchun mahalliy nusxa. */
@Entity @Table(name = "ms_agents")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MsAgentIndex {
    @Id
    @Column(name = "ms_id")
    private String msId;

    @Builder.Default
    @Column(nullable = false)
    private String name = "";

    @Builder.Default
    @Column(name = "phone_norm", nullable = false)
    private String phoneNorm = "";

    @Builder.Default
    @Column(nullable = false)
    private String inn = "";

    @Builder.Default
    @Column(nullable = false)
    private boolean archived = false;

    @Column(name = "ms_updated")
    private LocalDateTime msUpdated;
}
