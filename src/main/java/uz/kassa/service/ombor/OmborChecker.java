package uz.kassa.service.ombor;

import com.fasterxml.jackson.databind.JsonNode;
import uz.kassa.domain.OmborQoida;
import java.util.List;

/**
 * 🏬 Umumiy tekshiruvchi (docs/OMBOR-TZ.md §5). Qoida qatori (checker nomi + params) → topilmalar.
 * Tekshiruvchi hech kimga xabar yubormaydi va bazaga kamchilik yozmaydi — bu dvigatel ishi.
 */
public interface OmborChecker {

    /** Registry nomi (ombor_qoida.checker). */
    String code();

    /** Odam o'qiydigan tavsif va params namunasi (⚙️ sozlamada ko'rsatiladi). */
    String help();

    List<Found> run(OmborQoida rule, JsonNode params);

    /**
     * Topilma: subject (tur + kalit) kamchilik yagonaligi; kassaId — do'kon (null — umumiy);
     * ownerUserId — aniq mas'ul xodim (bo'lsa); title — qisqa; detail — batafsil (HTML esc qilingan).
     */
    record Found(String subjectType, String subjectKey, Long kassaId, Long ownerUserId, String title, String detail) {
        public static Found of(String type, String key, Long kassa, String title, String detail) {
            return new Found(type, key, kassa, null, title, detail);
        }
    }
}
