package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import uz.kassa.service.tg.TgReaderConfig;
import uz.kassa.service.tg.TgReaderService;

import java.util.List;
import java.util.Map;

/**
 * 📨 Python {@code tg-reader} (Telethon) xizmatidan keladigan ma'lumotlar: akkaunt holati, heartbeat, xabarlar,
 * va u o'qiydigan sozlamalar (manbalar ro'yxati, rasm OCR). {@link uz.kassa.service.tg.TgReaderHttpGateway} —
 * teskari yo'nalish (bot → tg-reader: login boshqaruvi, qoldiq so'rovi, xavfsizlik).
 * Umumiy sirli kalit ({@code TGREADER_SECRET}) bilan himoyalangan — ikkala xizmat ham bitta Docker
 * tarmog'ida, tashqariga chiqarilmaydi.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class TgReaderApiController {

    private final TgReaderService reader;
    private final TgReaderConfig cfg;

    @Value("${TGREADER_SECRET:}") private String secret;

    public record AccountReq(String phone, String name, Long tgUserId, String username, String sourceBot) {}
    public record HeartbeatReq(String phone, String error) {}
    /** source — manba kaliti (bot/odam username @siz, «me», chat id); bo'sh — asosiy bot. */
    public record StateReq(String phone, String source) {}
    /** sourceBot — manba kaliti; sender — guruhda kim yozgani (bo'lmasa null); photoB64 — rasm (OCR yoqiq bo'lsa). */
    public record MsgReq(long msgId, String date, String text, String media, String sourceBot, String sender, String photoB64) {}
    public record MessagesReq(String phone, List<MsgReq> messages) {}

    /** tg-reader sozlamalarni shu yerdan oladi (har daqiqa): manbalar ro'yxati, asosiy bot, rasm OCR. */
    @GetMapping("/api/tgreader/config")
    public ResponseEntity<?> config(@RequestHeader(name = "X-Reader-Secret", required = false) String key) {
        if (!authorized(key)) return forbidden();
        return ResponseEntity.ok(Map.of("sources", cfg.sources(), "sourceBot", cfg.sourceBot(), "media", cfg.mediaOcr()));
    }

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

    /** Manba kesimida qayerdan davom etsin (backfill). */
    @PostMapping("/api/tgreader/state")
    public ResponseEntity<?> state(@RequestHeader(name = "X-Reader-Secret", required = false) String key, @RequestBody StateReq r) {
        if (!authorized(key)) return forbidden();
        return ResponseEntity.ok(Map.of("lastMsgId", reader.lastMsgId(r.phone(), r.source())));
    }

    @PostMapping("/api/tgreader/messages")
    public ResponseEntity<?> messages(@RequestHeader(name = "X-Reader-Secret", required = false) String key, @RequestBody MessagesReq r) {
        if (!authorized(key)) return forbidden();
        List<TgReaderService.Msg> msgs = r.messages() == null ? List.of() : r.messages().stream()
                .map(m -> new TgReaderService.Msg(m.msgId(), m.date(), m.text(), m.media(), m.sourceBot(), m.sender(), m.photoB64())).toList();
        try {
            reader.ingest(r.phone(), msgs);
        } catch (Exception e) {
            log.error("tg-reader ingest ({}): {}", r.phone(), e.toString());
            return ResponseEntity.internalServerError().body(Map.of("ok", false, "error", String.valueOf(e.getMessage())));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private boolean authorized(String key) { return secret.isBlank() || secret.equals(key); }
    private ResponseEntity<?> forbidden() { return ResponseEntity.status(403).body(Map.of("error", "forbidden")); }
}
