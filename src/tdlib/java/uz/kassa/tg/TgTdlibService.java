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
 * <p><b>Login bot ichida</b> ({@link TgAccountGateway}): xodim telefon → kod → 2FA parolni botga kiritadi;
 * {@link ClientInteraction#onParameterRequest} orqali TDLib'ga uzatiladi. Sessiyalar {@code SESSIONS_DIR}/&lt;telefon&gt;.
 * Faqat «tdlib» Maven profilida kompilyatsiya bo'ladi (src/tdlib/java).
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
    private final Map<String, Login> logins = new ConcurrentHashMap<>();
    private final Map<String, Long> loginUserId = new ConcurrentHashMap<>();
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
            try { connect(phone, null); }
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

    /* ==================== TgAccountGateway (bot login) ==================== */

    /** Login holati: TDLib qaysi parametrni kutmoqda (pending) va botga signal (step). */
    private static final class Login {
        volatile CompletableFuture<String> pending;
        volatile CompletableFuture<TgAccountGateway.Step> step = new CompletableFuture<>();
    }

    @Override
    public synchronized Result startLogin(String phone, long userId) {
        if (!available()) return Result.error("TDLib o'chiq (TG_API_ID/HASH yo'q)");
        cancelLogin(phone);
        loginUserId.put(phone, userId);
        Login login = new Login();
        logins.put(phone, login);
        try {
            connect(phone, login);
            return await(login.step, phone, userId);
        } catch (Exception e) {
            logins.remove(phone);
            return Result.error(msg(e));
        }
    }

    @Override
    public synchronized Result submitCode(String phone, String code) {
        return submit(phone, code);
    }

    @Override
    public synchronized Result submitPassword(String phone, String password) {
        return submit(phone, password);
    }

    private Result submit(String phone, String value) {
        Login l = logins.get(phone);
        if (l == null || l.pending == null) return Result.error("Login sessiyasi topilmadi — qaytadan boshlang");
        CompletableFuture<Step> next = new CompletableFuture<>();
        l.step = next;
        l.pending.complete(value.trim());
        return await(next, phone, loginUserId.getOrDefault(phone, 0L));
    }

    /** Signalni kutish; CONNECTED bo'lsa akkauntni yakunlash. */
    private Result await(CompletableFuture<Step> step, String phone, long userId) {
        try {
            Step st = step.get(90, TimeUnit.SECONDS);
            if (st == Step.CONNECTED) { finishConnected(phone, userId); logins.remove(phone); }
            return Result.of(st);
        } catch (java.util.concurrent.TimeoutException te) {
            return Result.error("Telegram javob bermadi (90s) — qaytadan urinib ko'ring");
        } catch (Exception e) {
            return Result.error(msg(e));
        }
    }

    @Override
    public synchronized void cancelLogin(String phone) {
        logins.remove(phone);
        loginUserId.remove(phone);
        close(phone);
        // yakunlanmagan sessiya fayllarini o'chirish (keyingi login toza boshlansin)
        if (!isAuthorizedDir(phone)) deleteDir(Paths.get(sessionsDir, phone));
    }

    @Override
    public synchronized void disconnect(String phone) {
        logins.remove(phone);
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
        try { connect(phone, null); return true; }
        catch (Exception e) { log.error("📨 TDLib {} qayta ulanmadi: {}", phone, e.toString()); return false; }
    }

    /* ==================== ulanish ==================== */

    private void connect(String phone, Login login) {
        TDLibSettings settings = TDLibSettings.create(new APIToken(cfg.apiId(), cfg.apiHash()));
        Path dir = Paths.get(sessionsDir, phone);
        settings.setDatabaseDirectoryPath(dir.resolve("data"));
        settings.setDownloadedFilesDirectoryPath(dir.resolve("downloads"));
        SimpleTelegramClientBuilder builder = factory.builder(settings);
        builder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, u -> onAuth(phone, login, u));
        builder.addUpdateHandler(TdApi.UpdateNewMessage.class, u -> onMessage(phone, u));
        builder.setClientInteraction(new BotInteraction(phone, login));
        SimpleAuthenticationSupplier<?> auth = AuthenticationSupplier.user(phone);
        SimpleTelegramClient client = builder.build(auth);
        clients.put(phone, client);
        log.info("📨 TDLib {}: sessiya ochildi ({})", phone, login == null ? "avto" : "login");
    }

    /** Bot ichidan kod/parolni beruvchi ClientInteraction. login==null — avto ulanish (kod so'ralsa yopamiz). */
    private final class BotInteraction implements ClientInteraction {
        private final String phone;
        private final Login login;
        BotInteraction(String phone, Login login) { this.phone = phone; this.login = login; }

        @Override
        public CompletableFuture<String> onParameterRequest(InputParameter parameter, ParameterInfo info) {
            switch (parameter) {
                case ASK_CODE:
                    return need(Step.NEED_CODE);
                case ASK_PASSWORD:
                    return need(Step.NEED_PASSWORD);
                case TERMS_OF_SERVICE:
                case NOTIFY_LINK:
                    return CompletableFuture.completedFuture("");
                default:
                    // ASK_FIRST_NAME / EMAIL … — bu akkaunt Telegram'da ro'yxatdan o'tmagan; login mumkin emas
                    if (login != null && !login.step.isDone()) login.step.complete(Step.ERROR);
                    return CompletableFuture.completedFuture("");
            }
        }

        private CompletableFuture<String> need(Step step) {
            if (login == null) {   // avto ulanishda kod so'ralsa — sessiya avtorizatsiyalanmagan, yopamiz
                reader.heartbeat(phone, "login kerak (sessiya tugallanmagan)");
                CompletableFuture<String> f = new CompletableFuture<>();
                f.completeExceptionally(new IllegalStateException("login kerak"));
                closeAsync(phone);
                return f;
            }
            CompletableFuture<String> p = new CompletableFuture<>();
            login.pending = p;
            if (!login.step.isDone()) login.step.complete(step);
            return p;
        }
    }

    private void onAuth(String phone, Login login, TdApi.UpdateAuthorizationState u) {
        TdApi.AuthorizationState st = u.authorizationState;
        if (st instanceof TdApi.AuthorizationStateReady) {
            resolveBotAndAccount(phone);
            if (login != null && !login.step.isDone()) login.step.complete(Step.CONNECTED);
        } else if (st instanceof TdApi.AuthorizationStateClosed) {
            if (login != null && !login.step.isDone()) login.step.complete(Step.ERROR);
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

    private void finishConnected(String phone, long userId) {
        if (userId > 0) reader.linkUser(phone, userId);
        reader.setActive(phone, true);
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

    private boolean isAuthorizedDir(String phone) {
        // TDLib avtorizatsiyalangan sessiya data/ da binlog qoldiradi
        Path data = Paths.get(sessionsDir, phone, "data");
        if (!Files.isDirectory(data)) return false;
        try (Stream<Path> s = Files.list(data)) { return s.findAny().isPresent(); } catch (Exception e) { return false; }
    }

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
