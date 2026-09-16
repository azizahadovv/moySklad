package uz.kassa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 📨 tg-reader: akkaunt + manba (bot / guruh / kanal / «me») kesimida oxirgi o'qilgan xabar — backfill shu yerdan davom etadi (V42). */
@Entity @Table(name = "tg_manba_holat")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TgManbaHolat {
    @EmbeddedId private Id id;
    @Builder.Default @Column(name = "last_msg_id", nullable = false) private long lastMsgId = 0;
    @Column(name = "last_msg_at") private LocalDateTime lastMsgAt;

    public static TgManbaHolat of(String phone, String source) { return TgManbaHolat.builder().id(new Id(phone, source)).build(); }

    @Embeddable @Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class Id implements Serializable {
        @Column(length = 24) private String phone;
        @Column(length = 64) private String source;
    }
}
