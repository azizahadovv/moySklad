package uz.kassa.service.ombor.checks;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.kassa.domain.OmborHujjat;
import uz.kassa.domain.OmborQoida;
import uz.kassa.repo.OmborHujjatRepo;
import uz.kassa.service.ombor.OmborChecker;
import uz.kassa.service.ombor.OmborConfig;
import uz.kassa.service.ombor.OmborDocSync;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import static uz.kassa.bot.TextUtil.esc;
import static uz.kassa.bot.TextUtil.fmtTiyin;

/**
 * HUJJAT_OTKAZILMAGAN — applicable=false hujjatlar: yoshi age_min dan katta; until_hour berilsa bugungi hujjatlar
 * faqat shu soatdan keyin (kun oxiri tekshiruvi), oldingi kunlarniki doim. O'chirilganlar API bilan tekshirilib chiqariladi.
 */
@Component
@RequiredArgsConstructor
public class HujjatOtkazilmaganChecker implements OmborChecker {

    private final OmborHujjatRepo repo;
    private final OmborDocSync docs;
    private final OmborConfig cfg;

    @Override public String code() { return "HUJJAT_OTKAZILMAGAN"; }
    @Override public String help() { return "O'tkazilmagan hujjatlar. params: {\"types\":\"move,supply,loss,enter\",\"age_min\":120,\"until_hour\":18}"; }

    @Override
    public List<Found> run(OmborQoida rule, JsonNode p) {
        Set<String> types = new LinkedHashSet<>();
        for (String t : p.path("types").asText("move,supply,loss,enter,salesreturn").split(",")) if (!t.isBlank()) types.add(t.trim());
        int ageMin = p.path("age_min").asInt(120);
        int untilHour = p.path("until_hour").asInt(-1);
        LocalDateTime now = LocalDateTime.now(cfg.zone());
        LocalDate today = now.toLocalDate();
        List<Found> out = new ArrayList<>();
        for (OmborHujjat h : repo.findByTypeInAndApplicableFalseAndDeletedFalse(types)) {
            LocalDateTime created = h.getMsCreated() != null ? h.getMsCreated() : h.getMoment();
            if (created == null || created.plusMinutes(ageMin).isAfter(now)) continue;
            if (untilHour >= 0 && created.toLocalDate().equals(today) && now.toLocalTime().isBefore(LocalTime.of(untilHour, 0))) continue;
            if (!docs.stillExists(h) || !Boolean.FALSE.equals(h.getApplicable())) continue;
            out.add(new Found("hujjat", h.getMsId(), h.getKassaId(), h.getOwnerUserId(),
                    OmborHujjat.typeTitle(h.getType()) + " №" + h.getDocNo() + " · " + fmtTiyin(h.getSum()),
                    "<b>" + OmborHujjat.typeTitle(h.getType()) + " №" + esc(h.getDocNo()) + "</b> — o'tkazilmagan (проведён emas)\n"
                    + (h.getMoment() == null ? "" : "📅 " + h.getMoment().toLocalDate() + " · ") + "💵 " + fmtTiyin(h.getSum())
                    + (h.getAgentName().isBlank() ? "" : " · " + esc(h.getAgentName()))
                    + (h.getOwnerName().isBlank() ? "" : "\n👤 " + esc(h.getOwnerName()))
                    + "\n<a href=\"" + h.url() + "\">MoySklad'da ochish</a> — o'tkazing yoki o'chiring."));
        }
        return out;
    }
}
