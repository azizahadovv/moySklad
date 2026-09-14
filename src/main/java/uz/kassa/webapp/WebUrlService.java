package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uz.kassa.config.AppProps;
import uz.kassa.service.SettingsService;

/**
 * 🌐 Панел манзили (Mini App ва браузер учун). Устунлик: `settings.webapp.url` →
 * .env даги WEBAPP_URL. Базадаги қиймат иловани қайта кўтармасдан ўзгартирилади —
 * тест туннели ҳар сафар янги манзил бергани учун шу муҳим (`/weburl` буйруғи ёки deploy.sh).
 */
@Service
@RequiredArgsConstructor
public class WebUrlService {

    public static final String KEY = "webapp.url";

    private final SettingsService settings;
    private final AppProps props;

    /** Жорий манзил (охирида «/» бўлмайди); созланмаган бўлса бўш сатр. */
    public String url() {
        String v = settings.get(KEY).orElse("");
        if (v.isBlank()) v = props.getWebappUrl() == null ? "" : props.getWebappUrl();
        return v.trim().replaceAll("/+$", "");
    }

    public boolean configured() { return !url().isBlank(); }

    /** Базага ёзиш (бўш — .env қийматига қайтиш). */
    public void set(String v) { settings.set(KEY, v == null ? "" : v.trim().replaceAll("/+$", "")); }

    /** Охирги марта Telegram'га қўйилган манзил (меню тугмаси) — ўзгаришни сезиш учун. */
    public String appliedMenuUrl() { return settings.get(APPLIED_KEY).orElse(""); }

    public void markMenuApplied(String url) { settings.set(APPLIED_KEY, url == null ? "" : url); }

    public static final String APPLIED_KEY = "webapp.url.menu_applied";
}
