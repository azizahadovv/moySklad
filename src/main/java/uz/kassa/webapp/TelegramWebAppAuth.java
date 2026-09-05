package uz.kassa.webapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.kassa.config.AppProps;
import uz.kassa.domain.AppUser;
import uz.kassa.repo.AppUserRepo;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Telegram Mini App initData tekshiruvi (rasmiy hujjat bo'yicha):
 *   secret_key = HMAC_SHA256(key="WebAppData", data=bot_token)
 *   hash == hex(HMAC_SHA256(key=secret_key, data=data_check_string))
 * Tekshiruvdan o'tsa — user.id bo'yicha tizimdagi faol foydalanuvchi qaytadi.
 */
@Component
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class TelegramWebAppAuth {

    private static final long MAX_AGE_SECONDS = 24 * 3600;

    private final AppProps props;
    private final AppUserRepo userRepo;
    private final ObjectMapper om = new ObjectMapper();

    /** initData yaroqli bo'lsa faol AppUser, aks holda null. */
    public AppUser authenticate(String initData) {
        try {
            if (initData == null || initData.isBlank()) { log.info("WebApp auth: initData bo'sh"); return null; }

            Map<String, String> params = new TreeMap<>();
            for (String pair : initData.split("&")) {
                int i = pair.indexOf('=');
                if (i < 0) continue;
                params.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
            String hash = params.remove("hash");
            if (hash == null) { log.info("WebApp auth: hash yo'q"); return null; }

            StringBuilder dcs = new StringBuilder();
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (dcs.length() > 0) dcs.append('\n');
                dcs.append(e.getKey()).append('=').append(e.getValue());
            }

            byte[] secret = hmac("WebAppData".getBytes(StandardCharsets.UTF_8),
                    props.getBot().getToken().getBytes(StandardCharsets.UTF_8));
            byte[] check = hmac(secret, dcs.toString().getBytes(StandardCharsets.UTF_8));
            if (!hex(check).equalsIgnoreCase(hash)) { log.warn("WebApp auth: imzo mos emas (kalitlar: {})", params.keySet()); return null; }

            String authDate = params.get("auth_date");
            if (authDate != null
                    && System.currentTimeMillis() / 1000 - Long.parseLong(authDate) > MAX_AGE_SECONDS) {
                log.info("WebApp auth: eskirgan auth_date={}", authDate); return null; }

            long tgId = om.readTree(params.get("user")).path("id").asLong(0);
            if (tgId == 0) { log.info("WebApp auth: user.id yo'q"); return null; }
            AppUser u = userRepo.findByTelegramId(tgId).filter(AppUser::isActive).orElse(null);
            if (u == null) log.info("WebApp auth: tg {} tizimda yo'q yoki faol emas", tgId);
            return u;
        } catch (Exception e) {
            log.warn("WebApp auth xatosi: {}", e.toString());
            return null;
        }
    }

    private byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
