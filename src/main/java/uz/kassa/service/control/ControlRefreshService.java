package uz.kassa.service.control;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 🔄 Qo'lda yangilash (buxgalter, SuperAdmin, otdel rahbari): kontragentlar + otgruzkalar + balanslar
 * MoySklad'dan darhol o'qiladi. Bir vaqtda bitta, 60 soniya sovish vaqti — MoySklad limitiga tegmaslik uchun.
 * Avtomatik: kontragent/otgruzka har 2 daqiqada, balans control.check_min daqiqada.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ControlRefreshService {

    public static final long COOLDOWN_MS = 60_000L;

    private final AgentCheckService agents;
    private final ShipmentControlService ships;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long lastRun = 0;
    private volatile String lastResult = "";

    /** @return 0 — boshlandi (onDone alohida oqimda chaqiriladi); >0 — necha soniya kutish; -1 — allaqachon ishlayapti. */
    public long start(Runnable onDone) {
        long left = (lastRun + COOLDOWN_MS - System.currentTimeMillis()) / 1000;
        if (left > 0) return left;
        if (!running.compareAndSet(false, true)) return -1;
        Thread t = new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            long t0 = System.currentTimeMillis();
            try { agents.tick(); sb.append("kontragentlar ✔️ "); } catch (Exception e) { sb.append("kontragentlar ⚠️ ").append(e.getMessage()).append(' '); }
            try { ships.tick(); sb.append("otgruzkalar ✔️ "); } catch (Exception e) { sb.append("otgruzkalar ⚠️ ").append(e.getMessage()).append(' '); }
            try { ships.balanceTick(); sb.append("balanslar ✔️"); } catch (Exception e) { sb.append("balanslar ⚠️ ").append(e.getMessage()); }
            lastResult = sb.toString().trim() + " · " + ((System.currentTimeMillis() - t0) / 1000) + " s";
            lastRun = System.currentTimeMillis();
            running.set(false);
            try { onDone.run(); } catch (Exception e) { log.warn("Yangilashdan keyingi ko'rinish: {}", e.getMessage()); }
        }, "control-refresh");
        t.setDaemon(true);
        t.start();
        return 0;
    }

    public boolean isRunning() { return running.get(); }

    public String lastResult() { return lastResult; }
}
