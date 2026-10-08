package uz.kassa.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Versiya raqami o'zi hisoblanadi: har yangi build (build-info vaqti o'zgargan) ishga tushganda hisoblagich 1 ga oshadi
 * va bazada (settings app.ver.n / app.ver.build) saqlanadi. Ko'rinishi: «v1.7 · 08.10 14:32» — qaysi versiya ishlayotganini aniq bilish uchun.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppVersion {

    private static final String N = "app.ver.n", BUILD = "app.ver.build";
    private final SettingsService settings;
    private final ObjectProvider<BuildProperties> build;
    private volatile String text = "v?";

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try {
            BuildProperties b = build.getIfAvailable();
            if (b == null) { text = "v? (dev)"; return; }
            String id = String.valueOf(b.getTime().toEpochMilli());
            String cur = settings.get(N).orElse("0").replaceAll("[^0-9]", "");
            int n = cur.isEmpty() ? 0 : Integer.parseInt(cur);
            if (!id.equals(settings.get(BUILD).orElse(""))) {
                n++;
                settings.set(N, String.valueOf(n));
                settings.set(BUILD, id);
            }
            String when = b.getTime().atZone(ZoneId.of("Asia/Tashkent")).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"));
            text = "v1." + n + " · " + when;
            log.info("Versiya: {}", text);
        } catch (Exception e) {
            log.warn("Versiya aniqlanmadi: {}", e.getMessage());
        }
    }

    public String text() { return text; }
}
