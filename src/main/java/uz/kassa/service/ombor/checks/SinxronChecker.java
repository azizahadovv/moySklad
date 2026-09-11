package uz.kassa.service.ombor.checks;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.kassa.domain.OmborQoida;
import uz.kassa.domain.OmborSinxron;
import uz.kassa.repo.OmborSinxronRepo;
import uz.kassa.service.ombor.OmborChecker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static uz.kassa.bot.TextUtil.esc;

/** SINXRON — ombor_sinxron: oxirgi muvaffaqiyat max_age_min dan eski yoki xato bor. */
@Component
@RequiredArgsConstructor
public class SinxronChecker implements OmborChecker {

    private final OmborSinxronRepo repo;

    @Override public String code() { return "SINXRON"; }
    @Override public String help() { return "MoySklad sinxroni to'xtagan/xato. params: {\"max_age_min\":90}"; }

    @Override
    public List<Found> run(OmborQoida rule, JsonNode p) {
        int maxAge = p.path("max_age_min").asInt(90);
        List<Found> out = new ArrayList<>();
        Instant lim = Instant.now().minus(Duration.ofMinutes(maxAge));
        for (OmborSinxron s : repo.findAll()) {
            boolean stale = s.getLastOkAt() == null || s.getLastOkAt().isBefore(lim);
            boolean err = s.getLastError() != null && !s.getLastError().isBlank();
            if (!stale && !err) continue;
            String d = (s.getLastOkAt() == null ? "hali bir marta ham muvaffaqiyatli o'qilmagan"
                    : "oxirgi muvaffaqiyat: " + s.getLastOkAt().atZone(java.time.ZoneId.of("Asia/Tashkent")).toLocalDateTime().withNano(0))
                    + (err ? "\nXato: " + esc(s.getLastError()) : "");
            out.add(Found.of("sinxron", s.getEntity(), null, "Sinxron: " + s.getEntity(), d));
        }
        return out;
    }
}
