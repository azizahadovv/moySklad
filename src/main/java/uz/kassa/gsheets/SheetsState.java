package uz.kassa.gsheets;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.bot.NameService;
import uz.kassa.domain.*;
import uz.kassa.repo.*;
import uz.kassa.service.LedgerService;
import uz.kassa.service.moysklad.MoySkladClient;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sheets sinxron holati: snapshot xaritalari (DB-ustuvorlik uchun), chala satrlar, umumiy o'qish/yozish yordamchilari.
 * (SheetsSyncService dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SheetsState {

    private final KassaRepo kassaRepo;
    private final NameService names;
    private final GuestRepo guestRepo;
    private final uz.kassa.service.SettingsService settings;
    private final uz.kassa.config.AppProps props;

    /* ==================================================================
     * DB — YAGONA HAQIQAT MANBAI. Jadval unga ergashadi, aksincha emas.
     *
     * SNAPSHOT usuli: har push'da varaqqa YOZILGAN qiymatlar eslab qolinadi
     * (settings jadvalida — restartdan keyin ham saqlanadi). Pull paytida
     * varaqdagi qiymat faqat SNAPSHOT'DAN FARQ QILSA (ya'ni operator katakni
     * haqiqatan tahrirlagan bo'lsa) DB'ga qo'llanadi. Farq qilmasa — bu shunchaki
     * eski nusxa: bot tomonida qilingan o'zgarish (rol, kassa, ism, faol,
     * Telegram ulanishi) varaq tomonidan QAYTARIB YUBORILMAYDI, keyingi push
     * varaqni DB holatiga tekislaydi. Snapshot'i yo'q satr (bot o'chiq paytda
     * paydo bo'lgan va h.k.) DB'ga ta'sir qilmaydi.
     * ================================================================== */
    final java.util.Map<Long, String> userSnap =
            new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.Map<Long, String> kassaSnap =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 🔔 Shablon varag'i: id → tahrirlanadigan ustunlar hash'i. */
    final java.util.Map<Long, String> notifySnap =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean snapsLoaded = false;


    /** Asosiy (yaratuvchi) SuperAdmin (.env SUPERADMIN_TELEGRAM_ID) — varaq orqali ham
     *  roli pasaytirilmaydi/faolsizlantirilmaydi (tizim qulflanib qolmasin). */
    boolean isCreatorRow(AppUser x) {
        Long t = props.getSuperadmin().getTelegramId();
        return t != null && t > 0 && t.equals(x.getTelegramId());
    }


    void loadSnaps() {
        if (snapsLoaded) return;
        parseSnap(settings.get("sheets.snap.users").orElse(""), userSnap);
        parseSnap(settings.get("sheets.snap.kassa").orElse(""), kassaSnap);
        parseSnap(settings.get("sheets.snap.notify").orElse(""), notifySnap);
        snapsLoaded = true;
    }


    void parseSnap(String raw, java.util.Map<Long, String> into) {
        for (String line : raw.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            try { into.put(Long.parseLong(line.substring(0, eq)), line.substring(eq + 1)); }
            catch (NumberFormatException ignored) { }
        }
    }


    void saveSnap(String key, java.util.Map<Long, String> m) {
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        settings.set(key, sb.toString());
    }


    String norm(String s) {
        return s == null ? "" : s.trim().replace("|", "/").replace("\n", " ");
    }


    String digits(String s) { return s == null ? "" : s.replaceAll("\\D", ""); }


    /* ==================== SHEETS -> BOT (НАСТРОЙКА) ==================== */

    boolean bool(String v, boolean def) {
        if (v == null || v.isBlank()) return def;
        String s = v.trim().toUpperCase();
        return s.equals("TRUE") || s.equals("HA") || s.equals("ХА") || s.equals("1") || s.equals("+");
    }


    String cell(List<String> row, int i) {
        return i < row.size() ? row.get(i).trim() : "";
    }


    /** Rol nomini yumshoq o'qish: kassir/Kassir/KASSIR, admin -> SUPERADMIN. */
    Role parseRole(String s) {
        String v = s == null ? "" : s.trim().toUpperCase();
        if (v.startsWith("KASSIR") || v.startsWith("КАССИР")) return Role.KASSIR;
        if (v.startsWith("BUX") || v.startsWith("БУХ")) return Role.BUXGALTER;
        if (v.contains("ADMIN") || v.contains("АДМИН") || v.startsWith("SUPER")) return Role.SUPERADMIN;
        return null;
    }


    /** Kassani ID yoki NOM bo'yicha topish. */
    Kassa resolveKassa(String idS, String nomi) {
        if (idS != null && !idS.isBlank())
            try { return kassaRepo.findById(Long.parseLong(idS.trim())).orElse(null); }
            catch (NumberFormatException ignored) { }
        if (nomi != null && !nomi.isBlank())
            return kassaRepo.findAll().stream()
                    .filter(k -> k.getName().equalsIgnoreCase(nomi.trim())).findFirst().orElse(null);
        return null;
    }


    /** Telefon raqami bo'yicha mehmon (kontakt yuborganlar) Telegram ID sini topish.
     *  Faqat TO'LIQ moslik — suffiks-moslik begona odamni birovning akkauntiga ulardi. */
    Long guestByPhone(String tel) {
        if (uz.kassa.bot.TextUtil.normPhone(tel).isEmpty()) return null;
        for (Guest g : guestRepo.findAll())
            if (g.getPhone() != null && uz.kassa.bot.TextUtil.phoneEq(g.getPhone(), tel))
                return g.getTelegramId();
        return null;
    }


    /** Otdel (group) boshqa FAOL kassaga biriktirilganmi (o'zidan tashqari). */
    boolean groupTaken(String groupId, Long exceptKassaId) {
        return kassaRepo.findAll().stream().anyMatch(o -> o.isActive()
                && groupId.equals(o.getMoyskladGroupId())
                && (exceptKassaId == null || !o.getId().equals(exceptKassaId)));
    }


    /* ==================== BOT -> SHEETS ==================== */

    String owner(OwnerType t, Long id) {
        if (t == null) return "";
        return t == OwnerType.BUXGALTERIYA ? "Отдел Основной" : names.owner(t, id);
    }


    /* ==================== 🔔 SHABLON (bildirishnomalar) ==================== */

    /** Tahrirlanadigan ustunlar hash'i — snapshot uchun (shablonda yangi qatorlar bo'lgani uchun xom saqlanmaydi). */
    static String notifyHash(String nomi, String kimga, String jadval, String kunlar,
                                     String avto, String faol, String shablon, String tugma, String tugmaRol) {
        String all = nomi + "\u0001" + kimga + "\u0001" + jadval + "\u0001" + kunlar + "\u0001"
                + avto + "\u0001" + faol + "\u0001" + shablon + "\u0001" + tugma + "\u0001" + tugmaRol;
        return Integer.toHexString(all.hashCode()) + ":" + all.length();
    }


    static int parseIntSafe(String s, int def) {
        try { return Math.max(0, Math.min(1440, Integer.parseInt(s.replaceAll("\\D", "")))); }
        catch (NumberFormatException e) { return def; }
    }

}
