package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 🏬 MoySklad ombor hujjati (registry orqali sinxron). Summalar TIYINDA. */
@Entity @Table(name = "ombor_hujjat")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OmborHujjat {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "ms_id", nullable = false, unique = true) private String msId;
    @Column(nullable = false) private String type;
    @Builder.Default @Column(name = "doc_no", nullable = false) private String docNo = "";
    private LocalDateTime moment;
    @Column(name = "store_ms_id") private String storeMsId;
    @Column(name = "target_store_ms_id") private String targetStoreMsId;
    @Column(name = "kassa_id") private Long kassaId;
    @Column(name = "target_kassa_id") private Long targetKassaId;
    @Column(name = "agent_ms_id") private String agentMsId;
    @Builder.Default @Column(name = "agent_name", nullable = false) private String agentName = "";
    @Builder.Default @Column(nullable = false) private String state = "";
    private Boolean applicable;
    @Builder.Default @Column(nullable = false) private long sum = 0;
    @Builder.Default @Column(name = "payed_sum", nullable = false) private long payedSum = 0;
    @Column(name = "owner_uid") private String ownerUid;
    @Builder.Default @Column(name = "owner_name", nullable = false) private String ownerName = "";
    @Column(name = "owner_user_id") private Long ownerUserId;
    @Builder.Default @Column(nullable = false, columnDefinition = "text") private String links = "";
    @Builder.Default @Column(name = "positions_n", nullable = false) private int positionsN = 0;
    @Builder.Default @Column(name = "corrections_n", nullable = false) private int correctionsN = 0;
    @Builder.Default @Column(nullable = false) private boolean deleted = false;
    @Column(name = "ms_created") private LocalDateTime msCreated;
    @Column(name = "ms_updated") private LocalDateTime msUpdated;
    @Column(name = "sync_at") private Instant syncAt;

    public List<String> linkList() {
        List<String> out = new ArrayList<>();
        for (String s : links.split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    public static String typeTitle(String t) {
        return switch (t) {
            case "move" -> "Ko'chirish"; case "supply" -> "Priyomka"; case "purchaseorder" -> "Yetkazuvchiga buyurtma";
            case "invoicein" -> "Yetkazuvchi hisobi"; case "purchasereturn" -> "Yetkazuvchiga qaytarish";
            case "inventory" -> "Inventarizatsiya"; case "loss" -> "Spisaniya"; case "enter" -> "Oprixodovaniye";
            case "salesreturn" -> "Mijoz qaytarishi"; case "retailsalesreturn" -> "Chakana qaytarish"; case "demand" -> "Otgruzka";
            default -> t;
        };
    }

    public String url() { return "https://online.moysklad.ru/app/#" + type + "/edit?id=" + msId; }
}
