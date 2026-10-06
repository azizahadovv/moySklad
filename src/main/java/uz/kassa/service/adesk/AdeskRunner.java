package uz.kassa.service.adesk;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.bot.Sender;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Role;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.service.NotifySwitches;
import uz.kassa.service.adesk.AdeskHttp.AdeskException;
import uz.kassa.webapp.ExcelReportService;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static uz.kassa.bot.TextUtil.esc;

/**
 * 📒 Adesk integratsiyasini yurituvchi (docs/ADESK.md §6). O'z oqimida ishlaydi — Spring scheduler 1 oqimli,
 * birinchi to'liq yuklash (minglab kontragent/tovar) uni soatlab band qilmasin.
 *  • har {@link AdeskConfig#intervalMin()} daqiqada — inkremental sinxron (MoySklad'da o'zgarganlar);
 *  • har kuni {@link AdeskConfig#reportTime()} da — to'liq sinxron + solishtirish + hisobot (Telegram + Excel);
 *  • paneldan qo'lda: sinxron / to'liq sinxron / solishtirish / to'xtatish.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdeskRunner {

    private final AdeskConfig cfg;
    private final AdeskSyncService sync;
    private final AdeskCheckService check;
    private final Sender sender;
    private final AppUserRepo userRepo;
    private final NotifySwitches sw;
    private final ExcelReportService excel;

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "adesk");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean queued = new AtomicBoolean(false);
    private final AtomicBoolean stop = new AtomicBoolean(false);
    private volatile AdeskRun current;
    private volatile long lastIncAt = 0;
    private volatile String fatalSentKey = "";

    @PostConstruct
    void start() { exec.scheduleWithFixedDelay(this::tick, 120, 60, TimeUnit.SECONDS); }

    @PreDestroy
    void shutdown() { stop.set(true); exec.shutdownNow(); }

    private void tick() {
        try {
            if (!cfg.enabled() || !cfg.hasToken()) return;
            LocalDateTime now = LocalDateTime.now(cfg.zone());
            String today = now.toLocalDate().toString();
            if (!now.toLocalTime().isBefore(cfg.reportTime()) && !today.equals(cfg.get(AdeskConfig.REPORT_SENT).orElse(""))) {
                cfg.set(AdeskConfig.REPORT_SENT, today);
                doRun(true, null, true);
                return;
            }
            if (System.currentTimeMillis() - lastIncAt >= cfg.intervalMin() * 60_000L) doRun(false, null, false);
        } catch (Throwable t) {
            log.warn("Adesk tick: {}", t.toString());
        }
    }

    /* ==================== panel uchun ==================== */

    public boolean running() { return busy.get() || queued.get(); }

    /** Joriy yurish holati (panel): bosqich va jarayon; ishlamayapti — null. */
    public String status() {
        AdeskRun r = current;
        if (r == null) return queued.get() ? "⏳ navbatda" : null;
        long sec = Duration.between(r.startedAt, LocalDateTime.now(cfg.zone())).toSeconds();
        return "⏳ " + (r.full ? "to'liq sinxron" : "sinxron") + " · " + r.stage + (r.progress.isBlank() ? "" : " · " + r.progress)
                + " · " + (sec / 60) + " daq " + (sec % 60) + " s";
    }

    /** Qo'lda ishga tushirish. false — allaqachon ishlayapti/navbatda. */
    public boolean requestRun(boolean full, long chatId) {
        if (running() || !queued.compareAndSet(false, true)) return false;
        exec.execute(() -> { queued.set(false); doRun(full, chatId, false); });
        return true;
    }

    public boolean requestCheck(long chatId) {
        if (running() || !queued.compareAndSet(false, true)) return false;
        exec.execute(() -> {
            queued.set(false);
            if (!busy.compareAndSet(false, true)) return;
            try { sendCheck(List.of(chatId), false); }
            catch (Exception e) { sender.send(chatId, "⚠️ Solishtirish bajarilmadi — " + esc(String.valueOf(e.getMessage()))); }
            finally { busy.set(false); }
        });
        return true;
    }

    public boolean requestStop() {
        if (current == null) return false;
        stop.set(true);
        return true;
    }

    /* ==================== yurish ==================== */

    private void doRun(boolean full, Long chatId, boolean report) {
        if (!busy.compareAndSet(false, true)) return;
        stop.set(false);
        AdeskRun r = new AdeskRun(full, LocalDateTime.now(cfg.zone()), stop);
        current = r;
        try {
            try {
                sync.run(r);
            } catch (AdeskException e) {
                r.fatal = e.getMessage();
                if (e.fatal) notifyFatal(e.getMessage());
                log.warn("Adesk sinxroni to'xtadi: {}", e.getMessage());
            } catch (Exception e) {
                r.fatal = String.valueOf(e.getMessage());
                log.warn("Adesk sinxroni yiqildi", e);
            } finally {
                lastIncAt = System.currentTimeMillis();
                current = null;
            }
            String summary = summary(r);
            cfg.set(AdeskConfig.LAST_RUN, summary);
            if (chatId != null) sender.send(chatId, summary);
            else if (r.fatal == null && (r.get("tx.error") + r.get("cm.error") > 0 || full))
                log.info("Adesk: {}", summary.replace('\n', ' '));
            if (report && r.fatal == null) sendCheck(recipients(), true);
            else if (chatId != null && full && r.fatal == null) sendCheck(List.of(chatId), false);
        } catch (Exception e) {
            log.warn("Adesk yurishi: {}", e.toString());
        } finally {
            busy.set(false);
        }
    }

    /** Solishtirish natijasi: matn + Excel. auto — kunlik hisobot (🔕 kaliti hisobga olinadi). */
    private void sendCheck(List<Long> chats, boolean auto) {
        AdeskCheckService.Result res = check.check();
        cfg.set(AdeskConfig.LAST_CHECK, LocalDateTime.now(cfg.zone()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")) + " · " + res.shortLine());
        byte[] xls = excel.buildSheets(res.sheets());
        String file = "adesk-solishtirish-" + LocalDate.now(cfg.zone()) + ".xlsx";
        for (Long c : chats) {
            sender.send(c, res.html());
            sender.sendDocument(c, xls, file, "📒 Adesk ↔ MoySklad — батафсил");
        }
        if (auto) log.info("Adesk kunlik solishtirish yuborildi: {} chat · {}", chats.size(), res.shortLine());
    }

    /** Kunlik hisobot oluvchilari: belgilangan chatlar + (🔕 ADESK_HISOBOT yoqiq bo'lsa) SuperAdmin/buxgalterlar. */
    private List<Long> recipients() {
        LinkedHashSet<Long> out = new LinkedHashSet<>(cfg.reportChats());
        if (sw.on(NotifySwitches.ADESK_HISOBOT))
            for (Role role : List.of(Role.SUPERADMIN, Role.BUXGALTER))
                for (AppUser u : userRepo.findByRoleAndActiveTrue(role))
                    if (u.getTelegramId() != null && sw.allow(NotifySwitches.ADESK_HISOBOT, u)) out.add(u.getTelegramId());
        return new ArrayList<>(out);
    }

    /** Token/obuna xatosi — SuperAdmin'larga kuniga bir marta. */
    private void notifyFatal(String msg) {
        String key = LocalDate.now(cfg.zone()) + "|" + msg;
        if (key.equals(fatalSentKey) || !sw.on(NotifySwitches.ADESK_XATO)) return;
        fatalSentKey = key;
        String text = "⚠️ <b>Adesk sinxroni to'xtadi</b> — " + esc(msg)
                + "\n\n<i>Tokenni tekshiring: ⚙️ Настройка → 🔗 MoySklad → 📒 Adesk → 🔑 Token.</i>";
        for (AppUser u : userRepo.findByRoleAndActiveTrue(Role.SUPERADMIN))
            if (u.getTelegramId() != null && sw.allow(NotifySwitches.ADESK_XATO, u)) sender.send(u.getTelegramId(), text);
    }

    /* ==================== natija matni ==================== */

    String summary(AdeskRun r) {
        long sec = Duration.between(r.startedAt, LocalDateTime.now(cfg.zone())).abs().toSeconds();
        StringBuilder sb = new StringBuilder();
        String head = r.fatal != null ? "⚠️ <b>Sinxron to'xtadi</b> — " + esc(r.fatal)
                : r.stage.equals("to'xtatildi") ? "❌ <b>Sinxron to'xtatildi</b>"
                : "✅ <b>Sinxron tugadi</b>" + (r.full ? " (to'liq)" : "");
        sb.append(head).append(" · ").append(r.startedAt.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")))
          .append(" · ").append(sec / 60).append(" daq ").append(sec % 60).append(" s\n");
        part(sb, r, "Yuridik shaxslar", "le.created", "yangi", "le.linked", "bog'landi");
        part(sb, r, "Hisoblar", "acc.created", "yangi", "acc.linked", "bog'landi", "acc.renamed", "nomi o'zgartirildi", "acc.opening", "qoldiq yangilandi");
        part(sb, r, "Statyalar", "cat.created", "yangi", "cat.linked", "bog'landi");
        part(sb, r, "Kontragentlar", "ct.created", "yangi", "ct.linked", "bog'landi", "ct.updated", "yangilandi", "ct.error", "xato");
        part(sb, r, "Tovar/xizmat", "pr.created", "yangi", "pr.batch", "boshlang'ich partiya", "pr.linked", "bog'landi", "pr.updated", "yangilandi", "pr.error", "xato");
        part(sb, r, "Operatsiyalar", "tx.created", "yangi", "tx.updated", "yangilandi", "tx.relinked", "qayta bog'landi", "tx.removed", "o'chirildi",
                "tx.restored", "tiklandi", "tx.fixed", "to'g'rilandi", "tx.error", "xato");
        part(sb, r, "Otgruzka/priyomka", "cm.created", "yangi", "cm.updated", "yangilandi", "cm.removed", "o'chirildi", "cm.restored", "tiklandi", "cm.error", "xato");
        part(sb, r, "Adesk'da qo'lda", "ad.manual", "operatsiya", "ad.toMs", "MoySklad'ga yozildi", "ad.toMsError", "yozilmadi", "ad.manualTransfer", "o'tkazma");
        boolean any = sb.indexOf("•") >= 0;
        if (!any && r.fatal == null) sb.append("O'zgarish yo'q — hammasi bir xil.\n");
        synchronized (r.notes) {
            for (String n : r.notes.stream().limit(8).toList()) sb.append("ℹ️ ").append(esc(n)).append("\n");
        }
        return sb.toString().trim();
    }

    private static void part(StringBuilder sb, AdeskRun r, String title, String... kv) {
        List<String> ps = new ArrayList<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            int n = r.get(kv[i]);
            if (n > 0) ps.add(n + " " + kv[i + 1]);
        }
        if (!ps.isEmpty()) sb.append("• ").append(title).append(": ").append(String.join(", ", ps)).append("\n");
    }
}
