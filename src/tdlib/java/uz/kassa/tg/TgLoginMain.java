package uz.kassa.tg;

import it.tdlight.Init;
import it.tdlight.client.APIToken;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.SimpleAuthenticationSupplier;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientBuilder;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import it.tdlight.jni.TdApi;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 📨 Bir martalik interaktiv login (Python «reader.py login» ekvivalenti). Terminaldan telefon/kod/2FA parol so'raladi;
 * sessiya {@code SESSIONS_DIR}/&lt;phone&gt; ga saqlanadi va ilova (TgTdlibService) shuni ishlatadi.
 * <pre>
 * docker compose run --rm --entrypoint java app -cp /app/app.jar uz.kassa.tg.TgLoginMain +998901234567
 * </pre>
 * TG_API_ID / TG_API_HASH .env dan (my.telegram.org). Faqat «tdlib» profilida kompilyatsiya bo'ladi.
 */
public final class TgLoginMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || !args[0].startsWith("+")) {
            System.err.println("Foydalanish: TgLoginMain +998XXXXXXXXX");
            System.exit(2);
        }
        String phone = args[0].trim();
        int apiId = Integer.parseInt(env("TG_API_ID", "0"));
        String apiHash = env("TG_API_HASH", "");
        String sessionsDir = env("SESSIONS_DIR", "/sessions");
        if (apiId == 0 || apiHash.isBlank()) {
            System.err.println("TG_API_ID / TG_API_HASH yo'q — .env ga my.telegram.org dan yozing");
            System.exit(2);
        }
        Init.init();
        try (SimpleTelegramClientFactory factory = new SimpleTelegramClientFactory()) {
            TDLibSettings settings = TDLibSettings.create(new APIToken(apiId, apiHash));
            Path dir = Paths.get(sessionsDir, phone);
            settings.setDatabaseDirectoryPath(dir.resolve("data"));
            settings.setDownloadedFilesDirectoryPath(dir.resolve("downloads"));
            SimpleTelegramClientBuilder builder = factory.builder(settings);
            CountDownLatch ready = new CountDownLatch(1);
            builder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, u -> {
                if (u.authorizationState instanceof TdApi.AuthorizationStateReady) {
                    System.out.println("✅ Kirildi. Sessiya: " + dir);
                    ready.countDown();
                } else if (u.authorizationState instanceof TdApi.AuthorizationStateClosed) {
                    ready.countDown();
                }
            });
            // Interaktiv: telefon (shu +998...), kod, 2FA parol terminaldan so'raladi
            SimpleAuthenticationSupplier<?> auth = AuthenticationSupplier.consoleLogin();
            try (SimpleTelegramClient client = builder.build(auth)) {
                if (!ready.await(5, TimeUnit.MINUTES)) System.err.println("⏳ Login vaqti tugadi (5 daqiqa).");
                try { client.send(new TdApi.GetMe()).get(20, TimeUnit.SECONDS); } catch (Exception ignored) { }
                client.sendClose();
            }
        }
        System.out.println("Tayyor. Endi ilova sessiyani o'zi ishlatadi (docker compose restart app).");
        System.exit(0);
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v.trim();
    }
}
