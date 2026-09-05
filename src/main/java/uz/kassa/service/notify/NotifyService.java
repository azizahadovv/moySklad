package uz.kassa.service.notify;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.kassa.bot.Sender;
import uz.kassa.bot.TextUtil;
import uz.kassa.config.AppProps;
import uz.kassa.domain.*;
import uz.kassa.repo.*;
import uz.kassa.service.SettingsService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 🔔 Bildirishnomalar: jadval bo'yicha shablonli xabarlarni userlar/guruhlar/kanallarga
 * yuborish + yuborilgan xabarlarni belgilangan daqiqadan keyin avtomatik o'chirish.
 * Mavjud Click/kunlik hisobotlarga tegmaydi — alohida tizim.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotifyService {

    private final NotifyRepo repo;
    private final PendingDeleteRepo deleteRepo;
    private final TemplateService templates;
    private final Sender sender;
    private final AppUserRepo userRepo;
    private final KassaRepo kassaRepo;
    private final ClickAccountRepo clickRepo;
    private final GuestRepo guestRepo;
    private final SettingsService settings;
    private final AppProps props;

    /** Guruhdagi «✅ … қабул қилинди» tasdiq xabari necha daqiqadan keyin o'chirilsin (0 — o'chirilmasin). */
    public static final String CONFIRM_DELETE_KEY = "notify.confirmDeleteMin";
    private static final String CLICK_GROUPS_KEY = "notify.clickGroupChatId";

    public int confirmDeleteMin() {
        try { return Math.max(0, Math.min(1440, Integer.parseInt(settings.get(CONFIRM_DELETE_KEY).orElse("5").trim()))); }
        catch (NumberFormatException e) { return 5; }
    }

    /* ==================================================================
     * AVTO-O'CHIRISH NAVBATI
     * ================================================================== */

    /** Xabarni N daqiqadan keyin o'chirishga qo'yish (bor bo'lsa — vaqti 0 dan qayta boshlanadi). */
    @Transactional
    public void scheduleDelete(long chatId, Integer messageId, int minutes) {
        if (messageId == null || minutes <= 0) return;
        PendingDelete pd = deleteRepo.findByChatIdAndMessageId(chatId, messageId)
                .orElse(PendingDelete.builder().chatId(chatId).messageId(messageId).build());
        pd.setDeleteAt(Instant.now().plus(minutes, ChronoUnit.MINUTES));
        deleteRepo.save(pd);
    }

    /** Taymerni to'xtatish (masalan ✏️ Tuzatish bosilganda — odam tanlayotganda o'chmasin). */
    @Transactional
    public void cancelDelete(long chatId, Integer messageId) {
        if (messageId == null) return;
        deleteRepo.findByChatIdAndMessageId(chatId, messageId).ifPresent(deleteRepo::delete);
    }

    /** Har 30 soniyada: muddati kelganlarni o'chirish. */
    @Transactional
    public void deleteTick() {
        for (PendingDelete pd : deleteRepo.findByDeleteAtBefore(Instant.now())) {
            sender.deleteMessage(pd.getChatId(), pd.getMessageId());
            deleteRepo.delete(pd);
        }
    }

    /* ==================================================================
     * JADVAL
     * ================================================================== */

    public List<Notify> all() { return repo.findAllByOrderByIdAsc(); }
    public Optional<Notify> find(long id) { return repo.findById(id); }
    public Notify save(Notify n) { return repo.save(n); }
    public void delete(long id) { repo.deleteById(id); }

    /** Har daqiqa chaqiriladi. */
    public void tick() {
        LocalDateTime now = LocalDateTime.now(props.zoneId()).truncatedTo(ChronoUnit.MINUTES);
        String key = now.toString();   // yyyy-MM-ddTHH:mm
        for (Notify n : repo.findByActiveTrueOrderByIdAsc()) {
            try {
                if (key.equals(n.getLastSent())) continue;
                if (!due(n, now)) continue;
                log.info("🔔 Bildirishnoma #{} «{}» — {} da yuborilmoqda", n.getId(), n.getName(), key);
                String err = send(n);
                n.setLastSent(key);
                n.setLastError(err);
                if (n.isOnceMode()) n.setActive(false);   // bir martalik — yuborilgach o'chadi
                repo.save(n);
            } catch (Exception e) {
                log.warn("Bildirishnoma #{} xatosi: {}", n.getId(), e.getMessage());
            }
        }
    }

    /** Shu daqiqada yuborish kerakmi. */
    public boolean due(Notify n, LocalDateTime now) {
        Set<Integer> days = n.weekdaySet();
        if (n.isOnceMode()) return now.equals(n.onceAt());
        if (!days.isEmpty() && !days.contains(now.getDayOfWeek().getValue())) return false;
        if (n.isIntervalMode()) {
            int every = n.schedInt("every", 1, 1, 24);
            int from = n.schedInt("from", 0, 0, 23);
            int to = n.schedInt("to", 23, 0, 23);
            int off = n.schedInt("off", 0, -59, 59);
            LocalDateTime nominal = now.minusMinutes(off);
            if (nominal.getMinute() != 0) return false;
            int h = nominal.getHour();
            if (h < from || h > to) return false;
            return (h - from) % every == 0;
        }
        String hm = now.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        for (String t : times(n)) if (t.equals(hm)) return true;
        return false;
    }

    /** "times:09:00,13:00" → tartiblangan HH:mm ro'yxat. */
    public List<String> times(Notify n) {
        String raw = n.sched("times");
        if (raw == null) return List.of();
        TreeSet<String> out = new TreeSet<>();
        for (String p : raw.split(",")) {
            String t = normTime(p);
            if (t != null) out.add(t);
        }
        return new ArrayList<>(out);
    }

    /** «9:00», «09.30», «1830» → «09:00»/«09:30»/«18:30»; yaroqsiz — null. */
    public static String normTime(String s) {
        if (s == null) return null;
        String t = s.trim().replace('.', ':').replace(' ', ':');
        Matcher m = Pattern.compile("^(\\d{1,2}):?(\\d{2})$").matcher(t);
        if (!m.matches()) return null;
        int h = Integer.parseInt(m.group(1)), mi = Integer.parseInt(m.group(2));
        if (h > 23 || mi > 59) return null;
        return String.format("%02d:%02d", h, mi);
    }

    /** Keyingi yuborish vaqti (kartada ko'rsatish uchun); 8 kun ichida topilmasa — null. */
    public LocalDateTime nextRun(Notify n) {
        LocalDateTime t = LocalDateTime.now(props.zoneId()).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        LocalDateTime end = t.plusDays(8);
        while (t.isBefore(end)) {
            if (due(n, t)) return t;
            t = t.plusMinutes(1);
        }
        return null;
    }

    /* ==================================================================
     * YUBORISH
     * ================================================================== */

    /** Qabul qiluvchi: chat + (xodim bo'lsa) uning kassasi — {kassa:mening…} uchun. */
    public record Target(long chatId, Long kassaId, String label) {}

    /** Barcha qabul qiluvchilarga yuborish; xatolar matni (yoki null). */
    public String send(Notify n) {
        List<Target> targets = resolveTargets(n);
        if (targets.isEmpty()) return "qabul qiluvchi yo'q";
        StringBuilder err = new StringBuilder();
        for (Target t : targets) {
            try {
                TemplateService.Result r = templates.render(n.getTemplate(), new TemplateService.Ctx(t.chatId(), t.kassaId()));
                if (r.text().isBlank()) continue;
                Integer id = sender.sendId(t.chatId(), r.text(), null);
                if (id == null) { err.append(t.label()).append(": yuborilmadi; "); continue; }
                if (n.getAutoDeleteMin() > 0) scheduleDelete(t.chatId(), id, n.getAutoDeleteMin());
            } catch (Exception e) {
                err.append(t.label()).append(": ").append(e.getMessage()).append("; ");
            }
        }
        String s = err.toString().trim();
        return s.isEmpty() ? null : (s.length() > 290 ? s.substring(0, 290) : s);
    }

    /** 🧪 Test: faqat so'ragan adminning o'ziga (shu chat kontekstida) render qilib yuboradi. */
    public TemplateService.Result preview(Notify n, long adminChatId) {
        return templates.render(n.getTemplate(), new TemplateService.Ctx(adminChatId, null));
    }

    /** Qabul qiluvchilar ro'yxatini chat ID'larga yoyish (takrorlar olib tashlanadi). */
    public List<Target> resolveTargets(Notify n) {
        LinkedHashMap<Long, Target> out = new LinkedHashMap<>();
        for (String r : n.recipientSet()) {
            try {
                if (r.startsWith("group:")) {
                    long id = Long.parseLong(r.substring(6));
                    out.putIfAbsent(id, new Target(id, null, "chat " + id));
                } else if (r.startsWith("rol:")) {
                    Role role = Role.valueOf(r.substring(4).toUpperCase());
                    for (AppUser u : userRepo.findByRoleAndActiveTrue(role)) addUser(out, u);
                } else if (r.startsWith("user:")) {
                    userRepo.findById(Long.parseLong(r.substring(5))).filter(AppUser::isActive)
                            .ifPresent(u -> addUser(out, u));
                } else if (r.startsWith("kassa:")) {
                    for (AppUser u : userRepo.findByKassaIdAndActiveTrue(Long.parseLong(r.substring(6)))) addUser(out, u);
                } else if (r.equals("karta_masul")) {
                    for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
                        if (c.getCardResponsible() == null) continue;
                        Matcher m = Pattern.compile("id=(\\d+)").matcher(c.getCardResponsible());
                        if (m.find()) {
                            long tg = Long.parseLong(m.group(1));
                            out.putIfAbsent(tg, new Target(tg, c.getKassaId(), "mas'ul " + c.getName()));
                        }
                    }
                } else if (r.equals("click_chats")) {
                    for (String p : settings.get(CLICK_GROUPS_KEY).orElse("").split(",")) {
                        if (p.trim().isEmpty()) continue;
                        try { long id = Long.parseLong(p.trim()); out.putIfAbsent(id, new Target(id, null, "chat " + id)); }
                        catch (NumberFormatException ignored) { }
                    }
                } else if (r.equals("mehmonlar")) {
                    for (Guest g : guestRepo.findAll())
                        if (g.getTelegramId() != null)
                            out.putIfAbsent(g.getTelegramId(), new Target(g.getTelegramId(), null, "mehmon " + g.getName()));
                }
            } catch (Exception e) {
                log.debug("Qabul qiluvchi «{}» o'qilmadi: {}", r, e.getMessage());
            }
        }
        return new ArrayList<>(out.values());
    }

    private static void addUser(Map<Long, Target> out, AppUser u) {
        if (u.getTelegramId() == null) return;
        out.putIfAbsent(u.getTelegramId(), new Target(u.getTelegramId(), u.getKassaId(), u.getFullName()));
    }

    /* ==================================================================
     * SHEETS MATNINI O'QISH (odam yozgan ko'rinishlar ham qabul qilinadi)
     * ================================================================== */

    /** «every:2;from:9;to:21;off:0» / «times:09:00,13:00» / «har 2 soat 09-21 +15» / «09:00, 13:00». */
    public static String parseScheduleText(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) return "times:09:00";
        if (v.startsWith("every:") || v.startsWith("times:") || v.startsWith("once:")) return v;
        java.time.LocalDateTime once = parseOnceText(v, null);
        if (once != null) return "once:" + once;
        Matcher m = Pattern.compile("(?i)har\\s*(\\d{1,2})\\s*soat(?:da)?\\s*(\\d{1,2})\\s*[-–]\\s*(\\d{1,2})(?:\\s*([+-]\\d{1,2}))?").matcher(v);
        if (m.find()) {
            int every = Math.max(1, Math.min(24, Integer.parseInt(m.group(1))));
            int from = Math.min(23, Integer.parseInt(m.group(2))), to = Math.min(23, Integer.parseInt(m.group(3)));
            int off = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
            return "every:" + every + ";from:" + from + ";to:" + to + ";off:" + off;
        }
        TreeSet<String> times = new TreeSet<>();
        for (String p : v.split("[,;\\s]+")) { String t = normTime(p); if (t != null) times.add(t); }
        return times.isEmpty() ? "times:09:00" : "times:" + String.join(",", times);
    }

    /**
     * Bir martalik vaqt: «05.09.2026 14:30», «05.09 14:30», «2026-09-05 14:30», «bugun 18:00»,
     * «ertaga 09:00». Yaroqsiz — null. zone null bo'lsa faqat to'liq sanali ko'rinishlar o'qiladi.
     */
    public static java.time.LocalDateTime parseOnceText(String raw, java.time.ZoneId zone) {
        // «2026-09-05T14:30» → «2026-09-05 14:30» (faqat sana-vaqt orasidagi T; «ertaga» buzilmasin)
        String v = raw == null ? "" : raw.trim().toLowerCase().replaceAll("(\\d)t(\\d)", "$1 $2");
        Matcher m = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})\\s+(\\d{1,2})[:.](\\d{2})$").matcher(v);
        if (m.matches())
            return safeDt(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
        m = Pattern.compile("^(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{4}))?\\s+(\\d{1,2})[:.](\\d{2})$").matcher(v);
        if (m.matches()) {
            int year = m.group(3) != null ? Integer.parseInt(m.group(3))
                    : java.time.LocalDate.now(zone == null ? java.time.ZoneId.of("Asia/Tashkent") : zone).getYear();
            return safeDt(year, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
        }
        if (zone == null) return null;
        m = Pattern.compile("^(bugun|ertaga|indin)\\s+(\\d{1,2})[:.](\\d{2})$").matcher(v);
        if (m.matches()) {
            java.time.LocalDate d = java.time.LocalDate.now(zone);
            if (m.group(1).equals("ertaga")) d = d.plusDays(1);
            else if (m.group(1).equals("indin")) d = d.plusDays(2);
            return safeDt(d.getYear(), d.getMonthValue(), d.getDayOfMonth(), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        }
        return null;
    }

    private static java.time.LocalDateTime safeDt(int y, int mo, int d, int h, int mi) {
        try { return java.time.LocalDateTime.of(y, mo, d, h, mi); } catch (Exception e) { return null; }
    }

    /** «1,2,3» / «Du,Se,Ch» / «Du-Ju» → «1,2,3»; bo'sh/hammasi → «». */
    public static String parseWeekdaysText(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase();
        if (v.isEmpty()) return "";
        List<String> names = List.of("du", "se", "ch", "pa", "ju", "sh", "ya");
        Set<Integer> out = new TreeSet<>();
        Matcher range = Pattern.compile("^([a-z]{2})\\s*[-–]\\s*([a-z]{2})$").matcher(v);
        if (range.matches()) {
            int a = names.indexOf(range.group(1)), b = names.indexOf(range.group(2));
            if (a >= 0 && b >= a) for (int i = a; i <= b; i++) out.add(i + 1);
        } else for (String p : v.split("[,;\\s]+")) {
            if (p.matches("[1-7]")) out.add(Integer.parseInt(p));
            else { int i = names.indexOf(p.length() > 2 ? p.substring(0, 2) : p); if (i >= 0) out.add(i + 1); }
        }
        if (out.size() == 7) return "";
        List<String> l = new ArrayList<>();
        for (int d : out) l.add(String.valueOf(d));
        return String.join(",", l);
    }

    /** Kimga ustuni: bo'sh joy/yangi qatorlar tozalanadi, faqat ma'lum tokenlar qoladi. */
    public static String parseRecipientsText(String raw) {
        if (raw == null) return "";
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String p : raw.split("[,;\\n]+")) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (t.matches("-?\\d{6,}")) t = "group:" + t;
            if (t.matches("group:-?\\d+|rol:[A-Za-z]+|user:\\d+|kassa:\\d+|karta_masul|click_chats|mehmonlar"))
                out.add(t.startsWith("rol:") ? "rol:" + t.substring(4).toUpperCase() : t);
        }
        return String.join(",", out);
    }

    /* ==================================================================
     * 🔘 MENYU TUGMALARI — shablon asosiy menyuda tugma sifatida (rollar bo'yicha).
     * Mavjud bo'limlarga tegilmaydi: tugmalar menyu OXIRIGA qo'shiladi.
     * ================================================================== */

    /** Shu rol uchun asosiy menyuga qo'shiladigan tugma matnlari (faol shablonlar, ID tartibida). */
    public List<String> buttonLabelsFor(Role role) {
        List<String> out = new ArrayList<>();
        for (Notify n : repo.findByActiveTrueOrderByIdAsc())
            if (n.isButtonFor(role) && !out.contains(n.getButtonLabel().trim())) out.add(n.getButtonLabel().trim());
        return out;
    }

    /** Bosilgan matn shu rolning shablon tugmasimi. */
    public Optional<Notify> buttonByLabel(Role role, String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String t = text.trim();
        for (Notify n : repo.findByActiveTrueOrderByIdAsc())
            if (n.isButtonFor(role) && n.getButtonLabel().trim().equals(t)) return Optional.of(n);
        return Optional.empty();
    }

    /** Tugma bosilganda: foydalanuvchi kontekstida (o'z chati, o'z otdeli — {kassa:mening…}) render. */
    public TemplateService.Result renderForUser(Notify n, AppUser u) {
        return templates.render(n.getTemplate(), new TemplateService.Ctx(u.getTelegramId(), u.getKassaId()));
    }

    /** Tugma matni muammosi: null — yaroqli; aks holda sabab (bo'sh, uzun, buyruq, mavjud tugma). */
    public static String buttonLabelProblem(String label) {
        if (label == null || label.isBlank()) return "bo'sh";
        String t = label.trim();
        if (t.length() > 40) return "40 belgidan uzun";
        if (t.startsWith("/")) return "buyruq (/) ko'rinishida bo'lmasin";
        if (t.equals("-")) return "«-» band";
        if (uz.kassa.bot.Keyboards.isMenuLabel(t) || uz.kassa.bot.LabelService.RENAMABLE.contains(t)
                || t.equals("⬅️ Orqaga")) return "mavjud menyu tugmasi bilan bir xil";
        return null;
    }

    /** Sheets «Tugma rollar» ustuni: «kassir, bux» → «KASSIR,BUXGALTER». */
    public static String parseButtonRolesText(String raw) {
        if (raw == null) return "";
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String p : raw.split("[,;\s]+")) {
            String v = p.trim().toUpperCase();
            if (v.isEmpty()) continue;
            if (v.startsWith("KASSIR") || v.startsWith("КАССИР")) out.add("KASSIR");
            else if (v.startsWith("BUX") || v.startsWith("БУХ")) out.add("BUXGALTER");
            else if (v.contains("ADMIN") || v.contains("АДМИН") || v.startsWith("SUPER")) out.add("SUPERADMIN");
        }
        return String.join(",", out);
    }

    public static String rolesText(Notify n) {
        List<String> l = new ArrayList<>();
        for (Role r : n.buttonRoleSet()) l.add(roleLabel(r.name()));
        return l.isEmpty() ? "hech kimga" : String.join(", ", l);
    }

    /* ==================================================================
     * MATN TAVSIFLARI (admin panel va Sheets uchun)
     * ================================================================== */

    public String describeRecipients(Notify n) {
        Set<String> set = n.recipientSet();
        if (set.isEmpty()) return "—";
        List<String> out = new ArrayList<>();
        for (String r : set) {
            if (r.startsWith("group:")) {
                long id = Long.parseLong(r.substring(6));
                var chat = sender.getChat(id);
                String name = chat == null ? null : (chat.getTitle() != null ? chat.getTitle() : chat.getUserName());
                out.add((chat != null && chat.isChannelChat() ? "📢 " : "👥 ") + TextUtil.esc(name != null ? name : String.valueOf(id)));
            } else if (r.startsWith("rol:")) out.add("🎭 " + roleLabel(r.substring(4)));
            else if (r.startsWith("user:")) out.add("👤 " + userRepo.findById(Long.parseLong(r.substring(5)))
                    .map(u -> TextUtil.esc(u.getFullName())).orElse("#" + r.substring(5)));
            else if (r.startsWith("kassa:")) out.add("🏪 " + kassaRepo.findById(Long.parseLong(r.substring(6)))
                    .map(k -> TextUtil.esc(k.getName()) + " kassirlari").orElse("#" + r.substring(6)));
            else if (r.equals("karta_masul")) out.add("💳 Karta mas'ullari");
            else if (r.equals("click_chats")) out.add("📣 Click hisobot chatlari");
            else if (r.equals("mehmonlar")) out.add("🙋 Mehmonlar");
            else out.add(TextUtil.esc(r));
        }
        return String.join(", ", out);
    }

    public static String roleLabel(String role) {
        return switch (role.toUpperCase()) {
            case "KASSIR" -> "Kassirlar";
            case "BUXGALTER" -> "Buxgalterlar";
            case "SUPERADMIN" -> "SuperAdminlar";
            default -> role;
        };
    }

    public String describeSchedule(Notify n) {
        StringBuilder sb = new StringBuilder();
        if (n.isOnceMode()) {
            java.time.LocalDateTime at = n.onceAt();
            return "bir marta: " + (at == null ? "?" : at.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")))
                    + (n.isActive() ? "" : " (yuborilgan/o'chirilgan)");
        }
        if (n.isIntervalMode()) {
            int every = n.schedInt("every", 1, 1, 24), from = n.schedInt("from", 0, 0, 23),
                    to = n.schedInt("to", 23, 0, 23), off = n.schedInt("off", 0, -59, 59);
            sb.append("har ").append(every).append(" soatda, ")
              .append(String.format("%02d:00–%02d:00", from, to));
            if (off != 0) sb.append(String.format(", siljish %+d min (mas. %s)", off,
                    LocalTime.of(from, 0).plusMinutes(off).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))));
        } else {
            List<String> t = times(n);
            sb.append(t.isEmpty() ? "vaqt belgilanmagan" : String.join(", ", t));
        }
        Set<Integer> days = n.weekdaySet();
        if (!days.isEmpty() && days.size() < 7) {
            String[] nm = {"Du", "Se", "Ch", "Pa", "Ju", "Sh", "Ya"};
            List<String> d = new ArrayList<>();
            for (int i = 1; i <= 7; i++) if (days.contains(i)) d.add(nm[i - 1]);
            sb.append(" · ").append(String.join(",", d));
        }
        return sb.toString();
    }
}
