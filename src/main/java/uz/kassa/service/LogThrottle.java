package uz.kassa.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Har daqiqa takrorlanadigan bir xil ogohlantirish jurnalni to'ldirmasin (2026-11-04: 2 haftada 54 000 Sheets va 8 600 Adesk qatori
 * haqiqiy xatolarni ko'mib tashlagan). Kalit bo'yicha davrda bir marta ruxsat beradi.
 */
public final class LogThrottle {

    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();
    private static final int MAX_KEYS = 1000;

    private LogThrottle() {}

    /** true — shu kalit bo'yicha {@code everyMs} ichida birinchi marta: log yozish mumkin. */
    public static boolean allow(String key, long everyMs) {
        long now = System.currentTimeMillis();
        if (LAST.size() > MAX_KEYS) LAST.clear();
        Long prev = LAST.get(key);
        if (prev != null && now - prev < everyMs) return false;
        LAST.put(key, now);
        return true;
    }
}
