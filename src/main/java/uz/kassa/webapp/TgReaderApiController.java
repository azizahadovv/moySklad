package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import uz.kassa.service.tg.TgReaderService;

import java.util.List;
import java.util.Map;

/**
 * 📨 Python {@code tg-reader} (Telethon) xizmatidan keladigan ma'lumotlar: akkaunt holati, heartbeat, xabarlar.
 * {@link uz.kassa.service.tg.TgReaderHttpGateway} — teskari yo'nalish (bot → tg-reader, login boshqaruvi).
 * Umumiy sirli kalit ({@code TGREADER_SECRET}) bilan himoyalangan — ikkala xizmat ham bitta Docker
 * tarmog'ida, tashqariga chiqarilmaydi.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class TgReaderApiController {

    private final TgReaderService reader;

    @Value("${TGREADER_SECRET:}") private String secret;

    public record AccountReq(String phone, String name, Long tgUserId, String username, String sourceBot) {}
    public record HeartbeatReq(String phone, String error) {}
    public record PhoneReq(String phone) {}
    public record MsgReq(long msgId, String date, String text, String media, String sourceBot) {}
    public record MessagesReq(String phone, List<MsgReq> messages) {}

    @PostMapping("/api/tgreader/account")
    public ResponseEntity<?> account(@RequestHeader(name = "X-Reader-Secret", required = false) String key, @RequestBody AccountReq r) {
        if (!authorized(key)) return forbidden();
        reader.upsertAccount(r.phone(), r.name(), r.tgUserId(), r.username(), r.sourceBot());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/api/tgreader/heartbeat")
    public ResponseEntity<?> heartbeat(@RequestHeader(name = "X-Reader-Secret", required = false) String key, @RequestBody HeartbeatReq r) {
        if (!authorized(key)) return forbidden();
        reader.heartbeat(r.phone(), r.error());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/api/tgreader/state")
    public ResponseEntity<?> state(@RequestHeader(name = "X-Reader-Secret", required = false) String key, @RequestBody PhoneReq r) {
        if (!authorized(key)) return forbidden();
        return ResponseEntity.ok(Map.of("lastMsgId", reader.lastMsgId(r.phone())));
    }

    @PostMapping("/api/tgreader/messages")
    public ResponseEntity<?> messages(@RequestHeader(name = "X-Reader-Secret", required = false) String key, @RequestBody MessagesReq r) {
        if (!authorized(key)) return forbidden();
        List<TgReaderService.Msg> msgs = r.messages() == null ? List.of() : r.messages().stream()
                .map(m -> new TgReaderService.Msg(m.msgId(), m.date(), m.text(), m.media(), m.sourceBot())).toList();
        try {
            reader.ingest(r.phone(), msgs);
        } catch (Exception e) {
            log.error("tg-reader ingest ({}): {}", r.phone(), e.toString());
            return ResponseEntity.internalServerError().body(Map.of("ok", false, "error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private boolean authorized(String key) { return secret.isBlank() || secret.equals(key); }
    private ResponseEntity<?> forbidden() { return ResponseEntity.status(403).body(Map.of("error", "forbidden")); }
}
