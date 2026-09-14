package uz.kassa.service.ombor;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.OmborSorov;
import uz.kassa.repo.OmborSorovRepo;
import uz.kassa.service.AuditService;
import uz.kassa.service.moysklad.MoySkladClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * 🏬 B4: do'kon so'rovlari (sabab kodi bilan). Hamkor do'konlar (xarid intervali) bo'limi olib tashlangan (2026-09-12).
 * (kassa 0, product = "agent|tovar", qiymat = necha kun o'tib ketgan).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OmborSorovService {

    private final OmborSorovRepo repo;
    private final MoySkladClient ms;
    private final OmborMetrics metrics;
    private final OmborConfig cfg;
    private final AuditService audit;

    public OmborSorov create(AppUser by, Long kassaId, String productMsId, String text, BigDecimal qty, String reason) {
        OmborSorov s = repo.save(OmborSorov.builder().kassaId(kassaId).productMsId(productMsId).text(text == null ? "" : text)
                .qty(qty == null ? BigDecimal.ONE : qty).reason(reason).byUserId(by.getId()).build());
        audit.log(by.getId(), "OMBOR_SOROV", "ombor_sorov", s.getId(), reason + " " + (productMsId == null ? text : productMsId));
        return s;
    }

    public OmborSorov answer(long id, AppUser by, String status, String note) {
        OmborSorov s = repo.findById(id).orElse(null);
        if (s == null) return null;
        s.setStatus(status); s.setAnsweredBy(by.getId()); s.setAnsweredAt(Instant.now());
        if (note != null) s.setAnswer(note);
        audit.log(by.getId(), "OMBOR_SOROV_" + status, "ombor_sorov", id, "");
        return repo.save(s);
    }

    public List<OmborSorov> open() { return repo.findByStatusInOrderByCreatedAtDesc(List.of("YANGI", "KORILDI")); }
    public List<OmborSorov> forDraft() { return repo.findByStatusOrderByCreatedAtAsc("QORALAMADA"); }
    public OmborSorovRepo repo() { return repo; }

}
