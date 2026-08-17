package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Setting {
    @Id
    @Column(name = "key")
    private String key;

    @Column(name = "value", columnDefinition = "text")
    private String value;
}
