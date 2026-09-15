package uz.kassa.tg;

import it.tdlight.Init;
import it.tdlight.client.APIToken;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.ClientInteraction;
import it.tdlight.client.InputParameter;
import it.tdlight.client.ParameterInfo;
import it.tdlight.client.SimpleAuthenticationSupplier;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientBuilder;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import it.tdlight.jni.TdApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import uz.kassa.service.tg.TgAccountGateway;
import uz.kassa.service.tg.TgReaderService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 📨 To'liq Java TDLib (userbot) mijozi — akkauntga kirib FAQAT bitta botning ({@code TG_SOURCE_BOT}) xabarlarini
 * o'qiydi va {@link TgReaderService#ingest} ga uzatadi (parsing/balans/hisobot Java'da). Hech narsa yozmaydi.
 * Sessiyalar {@code SESSIONS_DIR}/&lt;telefon&gt;. Faqat «tdlib» Maven profilida kompilyatsiya bo'ladi
 * (src/tdlib/java) — standart build bunga UMUMAN murojaat qilmaydi ({@code Dockerfile}dagi {@code ARG TDLIB}
 * bo'sh, sabab: mvn.mchv.eu tarmoqdan yopiq). Aktiv login-darvoza endi {@code TgReaderHttpGateway} (Python/
 * Telethon, {@code tg-reader} xizmati) — shu klass DORMANT, faqat mavjud (avtorizatsiya qilingan) sessiyalarni
 * avto-ulash/pauza/uzish/test uchun saqlanadi.
 * <p><b>DIQQAT — QR-login shu klassda AMALGA OSHIRILMAGAN:</b> {@link #startQrLogin}/{@link #pollQr}/
 * {@link #submitPassword} atayin {@link UnsupportedOperationException} otadi. Sabab: TDLib'ning QR-login API'si
 * ({@code TdApi.RequestQrCodeAuthentication}, {@code AuthorizationStateWaitOtherDeviceConfirmation.link})
 * bu yerda lokal kompilyatsiya/tekshirish imkoni bo'lmagani uchun yozilmadi (mvn.mchv.eu network bloklangan).
 * Agar bu profil kelajakda qayta jonlantirilsa — server build orqali TDLib QR API'sini shu yerga qo'shish kerak;
 * aks holda (ehtimolroq) bu fayl butunlay o'chirilishi mumkin, chunki Python xizmati uni to'liq almashtirgan.
 */
@Component
@Slf4j
public class TgTdlibService implements TgAccountGateway {

    private final TgReaderService reader;
    private final uz.kassa.service.tg.TgReaderConfig cfg;

    @Value("${SESSIONS_DIR:/sessions}") private String sessionsDir;

    private SimpleTelegramClientFactory factory;
    private final Map<String, SimpleTelegramClient> clients = new ConcurrentHashMap<>();
    private final Map<String, Long> botChatId = new ConcurrentHashMap<>();
    private final ScheduledExecutorService beat = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "tg-heartbeat"); t.setDaemon(true); return t; });

    public TgTdlibService(TgReaderService reader, uz.kassa.service.tg.TgReaderConfig cfg) { this.reader = reader; this.cfg = cfg; }

    /** Kalitlar (sozlama/.env) bor va TDLib native ishga tushgan mi. Lazy: birinchi kerak bo'lganda init qiladi. */
    @Override public synchronized boolean available() {
        if (!cfg.hasApi()) return false;
        if (factory != null) return true;
        try { Init.init(); factory = new SimpleTelegramClientFactory(); return true; }
        catch (Throwable e) { log.error("📨 TDLib native init xatosi: {}", e.toString()); return false; }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!available()) { log.info("📨 TDLib: kalitlar yo'q (⚙️ 📨 → 🔑 API) — kutilmoqda"); return; }
        for (String phone : sessions()) {
            if (!reader.isActive(phone)) { log.info("📨 TDLib {}: o'chirilgan — o'tkazildi", phone); continue; }
            try { connect(phone); }
            catch (Exception e) { log.error("📨 TDLib {} ulanmadi: {}", phone, e.toString()); reader.heartbeat(phone, "ulanmadi: " + e.getMessage()); }
        }
        beat.scheduleAtFixedRate(this::heartbeat, 60, 60, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    private List<String> sessions() {
        Path base = Paths.get(sessionsDir);
        if (!Files.isDirectory(base)) return List.of();
        try (Stream<Path> s = Files.list(base)) {
            return s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).filter(n -> n.startsWith("+")).sorted().toList();
        } catch (Exception e) { return List.of(); }
    }

    /* ==================== TgAccountGateway (bot login) — DORMANT, qarang klass javadoc'i ==================== */

    @Override
    public Result startQrLogin(long userId) {
        throw new UnsupportedOperationException("QR-login TDLib (tdlib profil) mijozida amalga oshirilmagan — tg-reader (Python) ishlatiladi");
    }

    @Override
    public Result pollQr(long userId) {
        throw new UnsupportedOperationException("QR-login TDLib (tdlib profil) mijozida amalga oshirilmagan — tg-reader (Python) ishlatiladi");
    }

    @Override
    public Result submitPassword(long userId, String password) {
        throw new UnsupportedOperationException("QR-login TDLib (tdlib profil) mijozida amalga oshirilmagan — tg-reader (Python) ishlatiladi");
    }

    @Override
    public synchronized void cancelLogin(long userId) {
        // login boshlanmagan — hech narsa qilinmaydi
    }

    @Override
    public synchronized void pause(String phone) {
        close(phone);
        reader.setActive(phone, false);
    }

    @Override
    public synchronized void disconnect(String phone) {
        close(phone);
        deleteDir(Paths.get(sessionsDir, phone));
        reader.setActive(phone, false);
    }

    @Override
    public synchronized boolean reconnect(String phone) {
        if (!available()) return false;
        if (!Files.isDirectory(Paths.get(sessionsDir, phone))) return false;
        reader.setActive(phone, true);
        if (clients.containsKey(phone)) return true;
        try { connect(phone); return true; }
        catch (Exception e) { log.error("📨 TDLib {} qayta ulanmadi: {}", phone, e.toString()); return false; }
    }

    @Override
    public TestResult test(String phone) {
        SimpleTelegramClient client = clients.get(phone);
        if (client == null) return new TestResult(false, "Ulanmagan — avval «▶️ Yoqish» bosing");
        try {
            TdApi.User me = client.send(new TdApi.GetMe()).get(15, TimeUnit.SECONDS);
            String text = "✅ NSB bot — ulanish tekshiruvi\n🕒 "
                    + java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Tashkent"))
                            .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));
            // DIQQAT: SendMessage/InputMessageText maydon tartibi TDLib versiyasiga bog'liq — bu loyihada
            // «tdlib» profili faqat serverda build bo'ladi (mvn.mchv.eu bu yerdan ochilmaydi), shuning
            // uchun bu qator lokal kompilyatsiya qilinmadi. Server build xato bersa — shu ikki qatorni
            // joriy tdlight-java TdApi.java (target/generated yoki jar ichida) bilan solishtirib to'g'rilang.
            TdApi.InputMessageContent content = new TdApi.InputMessageText(new TdApi.FormattedText(text, null), null, false);
            client.send(new TdApi.SendMessage(me.id, 0, null, null, null, content)).get(15, TimeUnit.SECONDS);
            return new TestResult(true, "Saqlangan xabarlarga test xabar yuborildi ✅");
        } catch (Exception e) {
            return new TestResult(false, msg(e));
        }
    }

    @Override
    public List<PendingLogin> pendingLogins() {
        return List.of();  // QR-login shu klassda amalga oshirilmagan — hech qachon "jarayonda" bo'lmaydi
    }

    /* ==================== ulanish (faqat mavjud, avtorizatsiya qilingan sessiyalarni avto-ulash) ==================== */

    private void connect(String phone) {
        TDLibSettings settings = TDLibSettings.create(new APIToken(cfg.apiId(), cfg.apiHash()));
        Path dir = Paths.get(sessionsDir, phone);
        settings.setDatabaseDirectoryPath(dir.resolve("data"));
        settings.setDownloadedFilesDirectoryPath(dir.resolve("downloads"));
        SimpleTelegramClientBuilder builder = factory.builder(settings);
        builder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, u -> onAuth(phone, u));
        builder.addUpdateHandler(TdApi.UpdateNewMessage.class, u -> onMessage(phone, u));
        builder.setClientInteraction(new AutoInteraction(phone));
        SimpleAuthenticationSupplier<?> auth = AuthenticationSupplier.user(phone);
        SimpleTelegramClient client = builder.build(auth);
        clients.put(phone, client);
        log.info("📨 TDLib {}: sessiya ochildi (avto)", phone);
    }

    /** Faqat mavjud sessiyani ochadi — kod/parol so'ralsa (avtorizatsiya tugallanmagan) yopib qo'yadi, chunki bu klassda interaktiv login yo'q. */
    private final class AutoInteraction implements ClientInteraction {
        private final String phone;
        AutoInteraction(String phone) { this.phone = phone; }

        @Override
        public CompletableFuture<String> onParameterRequest(InputParameter parameter, ParameterInfo info) {
            switch (parameter) {
                case TERMS_OF_SERVICE:
                case NOTIFY_LINK:
                    return CompletableFuture.completedFuture("");
                default:
                    // ASK_CODE / ASK_PASSWORD / ASK_FIRST_NAME … — sessiya avtorizatsiyalanmagan, yopamiz
                    reader.heartbeat(phone, "login kerak (sessiya tugallanmagan)");
                    CompletableFuture<String> f = new CompletableFuture<>();
                    f.completeExceptionally(new IllegalStateException("login kerak"));
                    closeAsync(phone);
                    return f;
            }
        }
    }

    private void onAuth(String phone, TdApi.UpdateAuthorizationState u) {
        TdApi.AuthorizationState st = u.authorizationState;
        if (st instanceof TdApi.AuthorizationStateReady) {
            resolveBotAndAccount(phone);
        } else if (st instanceof TdApi.AuthorizationStateClosed) {
            clients.remove(phone);
            botChatId.remove(phone);
        }
    }

    private void resolveBotAndAccount(String phone) {
        SimpleTelegramClient client = clients.get(phone);
        if (client == null) return;
        try {
            TdApi.User me = client.send(new TdApi.GetMe()).get(30, TimeUnit.SECONDS);
            String name = ((me.firstName == null ? "" : me.firstName) + " " + (me.lastName == null ? "" : me.lastName)).trim();
            String uname = me.usernames != null && me.usernames.activeUsernames.length > 0 ? me.usernames.activeUsernames[0] : "";
            reader.upsertAccount(phone, name, me.id, uname, cfg.sourceBot());
        } catch (Exception e) { log.warn("📨 TDLib {}: getMe: {}", phone, e.toString()); }
        try {
            TdApi.Chat chat = client.send(new TdApi.SearchPublicChat(cfg.sourceBot())).get(30, TimeUnit.SECONDS);
            botChatId.put(phone, chat.id);
            log.info("📨 TDLib {}: @{} topildi (chat {}), tinglanmoqda", phone, cfg.sourceBot(), chat.id);
        } catch (Exception e) {
            log.error("📨 TDLib {}: @{} topilmadi ({}) — akkaunt bu bot bilan yozishmagan bo'lishi mumkin", phone, cfg.sourceBot(), e.toString());
            reader.heartbeat(phone, "@" + cfg.sourceBot() + " topilmadi");
        }
    }

    private void onMessage(String phone, TdApi.UpdateNewMessage u) {
        TdApi.Message m = u.message;
        Long want = botChatId.get(phone);
        if (want == null || m.chatId != want || m.isOutgoing) return;
        String text = "", media = "";
        if (m.content instanceof TdApi.MessageText mt) text = mt.text.text;
        else if (m.content instanceof TdApi.MessagePhoto mp) { media = "photo"; if (mp.caption != null) text = mp.caption.text; }
        else if (m.content instanceof TdApi.MessageDocument md) { media = "document"; if (md.caption != null) text = md.caption.text; }
        else media = m.content.getClass().getSimpleName();
        String iso = Instant.ofEpochSecond(m.date).atOffset(ZoneOffset.UTC).toString();
        try { reader.ingest(phone, List.of(new TgReaderService.Msg(m.id, iso, text, media, cfg.sourceBot()))); }
        catch (Exception e) { log.warn("📨 TDLib {}: xabar {} ingest: {}", phone, m.id, e.toString()); }
    }

    private void heartbeat() { for (String phone : clients.keySet()) reader.heartbeat(phone, null); }

    private void closeAsync(String phone) { beat.schedule(() -> close(phone), 100, TimeUnit.MILLISECONDS); }

    private void close(String phone) {
        SimpleTelegramClient c = clients.remove(phone);
        botChatId.remove(phone);
        if (c != null) try { c.sendClose(); } catch (Exception ignored) { }
    }

    public void stop() {
        for (String p : List.copyOf(clients.keySet())) close(p);
        if (factory != null) try { factory.close(); } catch (Exception ignored) { }
        beat.shutdownNow();
    }

    /* ==================== yordamchi ==================== */

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) { } });
        } catch (Exception ignored) { }
    }

    private static String msg(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return m == null || m.isBlank() ? c.getClass().getSimpleName() : m;
    }
}
