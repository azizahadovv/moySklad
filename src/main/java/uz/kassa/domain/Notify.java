package uz.kassa.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 🔔 Bildirishnoma: admin yaratadigan shablonli, jadval bo'yicha yuboriladigan xabar.
 * Qabul qiluvchilar va jadval matn ko'rinishida saqlanadi (Sheets bilan bir xil format).
 */
@Entity @Table(name = "notifies")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Notify {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** CSV: group:-100123, rol:KASSIR, user:5, kassa:2, karta_masul, click_chats, mehmonlar */
    @Builder.Default
    @Column(nullable = false, columnDefinition = "text")
    private String recipients = "";

    /** "every:2;from:9;to:21;off:0" yoki "times:09:00,13:00" */
    @Builder.Default
    @Column(nullable = false)
    private String schedule = "times:09:00";

    /** ISO hafta kunlari CSV (1=Du … 7=Ya); bo'sh = har kuni */
    @Builder.Default
    @Column(nullable = false)
    private String weekdays = "";

    @Builder.Default
    @Column(name = "auto_delete_min", nullable = false)
    private int autoDeleteMin = 0;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "text")
    private String template = "";

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    /** 🔘 Asosiy menyudagi tugma matni (bo'sh — tugma yo'q). */
    @Builder.Default
    @Column(name = "button_label", nullable = false)
    private String buttonLabel = "";

    /** Tugma ko'rinadigan rollar, CSV: KASSIR,BUXGALTER,SUPERADMIN. */
    @Builder.Default
    @Column(name = "button_roles", nullable = false)
    private String buttonRoles = "";

    @Column(name = "last_sent")
    private String lastSent;

    @Column(name = "last_error")
    private String lastError;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /* ---------- yordamchi ---------- */

    public Set<String> recipientSet() {
        Set<String> out = new LinkedHashSet<>();
        for (String p : recipients.split(","))
            if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    public void setRecipientSet(Set<String> set) {
        recipients = String.join(",", set);
    }

    public Set<Role> buttonRoleSet() {
        Set<Role> out = new LinkedHashSet<>();
        for (String p : buttonRoles.split(",")) {
            try { if (!p.trim().isEmpty()) out.add(Role.valueOf(p.trim().toUpperCase())); }
            catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    public void setButtonRoleSet(Set<Role> set) {
        List<String> l = new ArrayList<>();
        for (Role r : Role.values()) if (set.contains(r)) l.add(r.name());
        buttonRoles = String.join(",", l);
    }

    /** Tugma sifatida ko'rinadimi: faol, matni bor va shu rolga ruxsat. */
    public boolean isButtonFor(Role role) {
        return active && buttonLabel != null && !buttonLabel.isBlank() && buttonRoleSet().contains(role);
    }

    public Set<Integer> weekdaySet() {
        Set<Integer> out = new LinkedHashSet<>();
        for (String p : weekdays.split(",")) {
            try { int d = Integer.parseInt(p.trim()); if (d >= 1 && d <= 7) out.add(d); }
            catch (NumberFormatException ignored) { }
        }
        return out;
    }

    public void setWeekdaySet(Set<Integer> set) {
        List<String> l = new ArrayList<>();
        for (int d = 1; d <= 7; d++) if (set.contains(d)) l.add(String.valueOf(d));
        weekdays = String.join(",", l);
    }

    /** Jadval parametrini o'qish: key=every/from/to/off/times. */
    public String sched(String key) {
        for (String p : schedule.split(";")) {
            int i = p.indexOf(':');
            if (i > 0 && p.substring(0, i).trim().equals(key)) return p.substring(i + 1).trim();
        }
        return null;
    }

    public boolean isIntervalMode() { return sched("every") != null; }

    /** «once:2026-09-05T14:30» — bir marta, yuborilgach o'zi o'chadi. */
    public boolean isOnceMode() { return sched("once") != null; }

    public java.time.LocalDateTime onceAt() {
        try { return java.time.LocalDateTime.parse(sched("once")); }
        catch (Exception e) { return null; }
    }

    public int schedInt(String key, int def, int min, int max) {
        try {
            int v = Integer.parseInt(sched(key));
            return Math.max(min, Math.min(max, v));
        } catch (Exception e) { return def; }
    }
}
