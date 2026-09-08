package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.Sender;
import uz.kassa.bot.Session;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Kassa;
import uz.kassa.domain.KassaHead;
import uz.kassa.domain.Role;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.KassaHeadRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.service.AuditService;
import uz.kassa.service.control.*;
import uz.kassa.service.moysklad.MoySkladClient;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.esc;
import static uz.kassa.bot.TextUtil.fmt;

/**
 * ⚙️ Настройка → 🕵️ Назорат (SuperAdmin): yoqish/o'chirish, boshlanish sanasi, kutish oynasi,
 * tekshiruv oralig'i, kunlik vaqt, eskalatsiya, oluvchilar, qoidalar (K1–K8),
 * xodim ↔ MoySklad bog'lash, otdel rahbarlari, test, hozir tekshirish. Callback: a:ct*.
 */
@Component
@RequiredArgsConstructor
public class ControlAdminHandler {

    private final Sender sender;
    private final ControlConfig cfg;
    private final AgentCheckService agents;
    private final ShipmentControlService ships;
    private final EmployeeLinkService link;
    private final AppUserRepo userRepo;
    private final KassaRepo kassaRepo;
    private final KassaHeadRepo headRepo;
    private final AuditService audit;

    private static final String BACK = "a:ct";


    public boolean onCallback(AppUser u, Session s, String cmd, String arg, long chatId, int msgId) {
        if (u.getRole() != Role.SUPERADMIN) { sender.send(chatId, "⚠️ Faqat SuperAdmin uchun."); return true; }
        switch (cmd) {
            case "ct" -> menu(s, chatId, msgId);
            case "ctg" -> { cfg.setEnabled(!cfg.enabled()); audit.log(u.getId(), "NAZORAT_" + (cfg.enabled() ? "YOQILDI" : "OCHIRILDI"), "settings", null, ""); menu(s, chatId, msgId); }
            case "ctv" -> askValue(s, arg, chatId, msgId);
            case "ctr" -> recipients(chatId, msgId);
            case "ctrt" -> { cfg.toggleRecipient(Long.parseLong(arg)); recipients(chatId, msgId); }
            case "ctk" -> rules(chatId, msgId);
            case "ctq" -> quietStates(s, chatId, msgId);
            case "ctqt" -> {
                Object o = s.data.get("ctQ");
                if (o instanceof List<?> l) {
                    int i = Integer.parseInt(arg);
                    if (i >= 0 && i < l.size()) {
                        cfg.toggleQuietState(String.valueOf(l.get(i)));
                        audit.log(u.getId(), "NAZORAT_JIM_STATUS", "settings", null, String.valueOf(l.get(i)) + "=" + (cfg.isQuietState(String.valueOf(l.get(i))) ? "jim" : "xabarli"));
                    }
                }
                quietStates(s, chatId, msgId);
            }
            case "ctkt" -> {
                cfg.toggleRule(arg);
                if (arg.startsWith("O")) new Thread(ships::reevaluateAllIssues, "issues-reeval").start();
                audit.log(u.getId(), "NAZORAT_QOIDA", "settings", null, arg + "=" + (cfg.ruleOn(arg) ? "on" : "off"));
                rules(chatId, msgId);
            }
            case "cte" -> employees(s, chatId, msgId);
            case "ctey" -> {
                var r = link.syncEmployees(true, u.getId());
                StringBuilder sb = new StringBuilder("🔄 <b>MoySklad bilan sinxron</b>\nBog'landi: " + r.linked()
                        + " · yaratildi: " + r.created() + " · otdel qo'yildi: " + r.kassaSet() + " · rahbar: " + r.headsSet() + "\n");
                int n = 0;
                for (String x : r.notes()) { if (++n > 25) { sb.append("…\n"); break; } sb.append(esc(x)).append("\n"); }
                if (r.notes().isEmpty()) sb.append("O'zgarish yo'q — hammasi mos.\n");
                sb.append("\nYangi yaratilgan xodimlar botga kirib kontakt (telefon) yuborsa Telegram ulanadi.");
                sender.send(chatId, sb.toString());
                employees(s, chatId, 0);
            }
            case "ctel" -> employeePick(s, Integer.parseInt(arg), chatId, msgId);
            case "ctes" -> employeeSet(u, s, arg, chatId, msgId);
            case "ctex" -> employeeUnlink(u, s, Integer.parseInt(arg), chatId, msgId);
            case "ctu" -> userCard(s, Long.parseLong(arg), chatId, msgId);
            case "ctuk" -> userKassaPick(s, Long.parseLong(arg), chatId, msgId);
            case "ctuks" -> userKassaSet(u, s, arg, chatId, msgId);
            case "ctue" -> userEmpPick(s, Long.parseLong(arg), chatId, msgId);
            case "ctues" -> userEmpSet(u, s, arg, chatId, msgId);
            case "ctuex" -> userEmpUnlink(u, s, Long.parseLong(arg), chatId, msgId);
            case "ctuh" -> userHeadPick(s, Long.parseLong(arg), chatId, msgId);
            case "ctuht" -> userHeadToggle(u, s, arg, chatId, msgId);
            case "cth" -> heads(chatId, msgId);
            case "cthk" -> headKassa(Long.parseLong(arg), chatId, msgId);
            case "ctht" -> headToggle(u, arg, chatId, msgId);
            case "ctt" -> test(u, chatId);
            case "ctn" -> runNow(chatId, msgId);
            default -> { return false; }
        }
        return true;
    }


    public void menu(Session s, long chatId, int msgId) {
        StringBuilder sb = new StringBuilder("🕵️ <b>Контрагент назорати</b>\n\n");
        sb.append(cfg.enabled() ? "🟢 Yoqilgan" : "⚪ O'chirilgan").append("\n");
        sb.append("📅 Eski qarzlar: <b>").append(cfg.since()).append("</b> dan\n");
        sb.append("⏱ Kutish oynasi: <b>").append(cfg.graceMin()).append(" min</b> · 🔁 Balans tekshiruvi: <b>")
          .append(cfg.checkMin()).append(" min</b>\n");
        sb.append("🕘 Kunlik jamlama: <b>").append(cfg.dailyTime()).append("</b> · ⏰ Eskalatsiya: <b>")
          .append(cfg.escalateHours()).append(" soat</b>\n");
        sb.append("👥 Qo'shimcha oluvchilar: <b>").append(cfg.recipientIds().size()).append("</b> ta · 📏 Qoidalar: <b>")
          .append(ControlConfig.ALL_RULES.size() - cfg.rulesOff().size()).append("/8</b>\n\n");
        sb.append("🧾 Qarzda: <b>").append(ships.debtCount()).append("</b> ta otgruzka · <b>").append(fmt(ships.debtSum()))
          .append("</b> so'm\n⚠️ Tuzatilmagan kontragent: <b>").append(agents.openCount()).append("</b> ta\n");
        cfg.lastDemandSync().ifPresent(t -> sb.append("🔄 Oxirgi sinxron: ").append(t.format(ControlConfig.FMT)).append("\n"));
        long unlinked = userRepo.findByActiveTrueOrderByRoleAscIdAsc().stream()
                .filter(x -> x.getMsUid() == null && x.getMsEmployeeId() == null).count();
        if (unlinked > 0) sb.append("👔 MoySklad'ga bog'lanmagan xodimlar: <b>").append(unlinked).append("</b>\n");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn(cfg.enabled() ? "⏸ O'chirish" : "▶️ Yoqish", "a:ctg"), btn("📅 Boshlanish sanasi", "a:ctv:since")));
        rows.add(irow(btn("⏱ Kutish (min)", "a:ctv:grace"), btn("🔁 Tekshiruv (min)", "a:ctv:check")));
        rows.add(irow(btn("🕘 Kunlik vaqt", "a:ctv:daily"), btn("⏰ Eskalatsiya (soat)", "a:ctv:esc")));
        rows.add(irow(btn("👥 Oluvchilar", "a:ctr"), btn("📏 Qoidalar", "a:ctk")));
        rows.add(irow(btn("🏦 Jim statuslar (faqat ro'yxatda)", "a:ctq")));
        rows.add(irow(btn("👔 Xodimlar (MoySklad)", "a:cte"), btn("🏪 Otdel rahbarlari", "a:cth")));
        rows.add(irow(btn("🔄 Hammasini yangilash (MoySklad)", "a:ctn")));
        rows.add(irow(btn("🧪 Test (o'zimga)", "a:ctt")));
        rows.add(irow(btn("⬅️ Orqaga", "a:p:set")));
        show(chatId, msgId, sb.toString(), inline(rows));
    }


    /* ---------- qiymat kiritish ---------- */

    private void askValue(Session s, String key, long chatId, int msgId) {
        String prompt = switch (key) {
            case "since" -> "📅 Eski qarzlar qaysi sanadan yuklansin? (yyyy-MM-dd yoki dd.MM.yyyy)\nHozir: " + cfg.since()
                    + "\n⚠️ O'zgartirilsa shu sanadan barcha to'lanmagan otgruzkalar qayta yuklanadi (xabarsiz).";
            case "grace" -> "⏱ Otgruzkadan keyin necha daqiqa kutilsin? (5–43200)\nHozir: " + cfg.graceMin();
            case "check" -> "🔁 Qarzdorlar balansi necha daqiqada bir tekshirilsin? (2–1440)\nHozir: " + cfg.checkMin();
            case "daily" -> "🕘 Kunlik jamlama vaqti (HH:mm)\nHozir: " + cfg.dailyTime();
            case "esc" -> "⏰ Kontragent xatosi necha soatdan keyin rahbar/SuperAdmin'ga chiqsin? (1–720)\nHozir: " + cfg.escalateHours();
            default -> null;
        };
        if (prompt == null) { menu(s, chatId, msgId); return; }
        s.state = Session.State.ADM_CT_VAL;
        s.data.put("ctKey", key);
        sender.edit(chatId, msgId, prompt, inline(List.of(irow(btn("❌ Bekor", BACK)))));
    }


    public void onText(AppUser u, Session s, String text, long chatId) {
        String key = s.getStr("ctKey");
        s.reset();
        String t = text.trim();
        try {
            switch (key == null ? "" : key) {
                case "since" -> {
                    LocalDate d = t.matches("\\d{2}\\.\\d{2}\\.\\d{4}")
                            ? LocalDate.parse(t, java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")) : LocalDate.parse(t);
                    if (d.isAfter(LocalDate.now(cfg.zone()))) throw new IllegalArgumentException("kelajak sana");
                    cfg.set(ControlConfig.SINCE, d.toString());
                }
                case "grace" -> cfg.set(ControlConfig.GRACE_MIN, String.valueOf(range(t, 5, 43200)));
                case "check" -> cfg.set(ControlConfig.CHECK_MIN, String.valueOf(range(t, 2, 1440)));
                case "daily" -> cfg.set(ControlConfig.DAILY_TIME, LocalTime.parse(t.length() == 4 ? "0" + t : t).toString());
                case "esc" -> cfg.set(ControlConfig.ESCALATE_H, String.valueOf(range(t, 1, 720)));
                default -> { sender.send(chatId, "⚠️ Noma'lum sozlama"); return; }
            }
            audit.log(u.getId(), "NAZORAT_SOZLAMA", "settings", null, key + "=" + t);
            sender.send(chatId, "✅ Saqlandi: " + esc(key) + " = " + esc(t));
        } catch (Exception e) {
            sender.send(chatId, "⚠️ Qiymat noto'g'ri: " + esc(t) + ". Qaytadan: ⚙️ Настройка → 🕵️ Назорат");
        }
        menu(s, chatId, 0);
    }

    private static int range(String t, int min, int max) {
        int v = Integer.parseInt(t.replaceAll("\\D", ""));
        if (v < min || v > max) throw new IllegalArgumentException("oraliq");
        return v;
    }


    /* ---------- 🏦 jim statuslar ---------- */

    /** Qaysi otgruzka statuslari qarz ro'yxatiga XABARSIZ tushadi (Перечисление — bank orqali, kunlab). */
    private void quietStates(Session s, long chatId, int msgId) {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(ships.allDebtStates());
        for (String q : cfg.get(ControlConfig.QUIET_STATES).orElse(ControlConfig.QUIET_DEFAULT).split(","))
            if (!q.isBlank()) all.add(q.trim());
        all.remove("—");
        List<String> list = new ArrayList<>(all);
        s.data.put("ctQ", list);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < list.size(); i++)
            rows.add(irow(btn((cfg.isQuietState(list.get(i)) ? "🏦 jim · " : "🔔 xabarli · ") + list.get(i), "a:ctqt:" + i)));
        rows.add(irow(btn("⬅️ Orqaga", BACK)));
        show(chatId, msgId, "🏦 <b>Jim statuslar</b>\n\nBu statusdagi otgruzkalar qarzdorlar ro'yxatiga tushadi, lekin xodim, rahbar va "
                + "SuperAdmin'ga xabar KETMAYDI (qo'shildi/to'landi/kamchilik/kunlik qatorlar). Kunlik jamlamada faqat soni ko'rinadi. "
                + "Ro'yxatda status filtri bilan ko'riladi.\n\nBosib almashtiring:", inline(rows));
    }


    /* ---------- oluvchilar ---------- */

    private void recipients(long chatId, int msgId) {
        Set<Long> ids = cfg.recipientIds();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc()) {
            if (x.getRole() == Role.SUPERADMIN) {   // SuperAdmin doim oladi — ro'yxat to'liq ko'rinsin
                rows.add(irow(btn(cut("👑 " + x.getFullName() + " · doim oladi" + (x.getTelegramId() == null ? " · 🟡 Telegram yo'q" : ""), 55), "a:ctr")));
                continue;
            }
            String label = (ids.contains(x.getId()) ? "✅ " : "▫️ ") + roleMark(x) + x.getFullName()
                    + (x.getKassaId() == null ? " · hammasi" : " · " + kassaName(x.getKassaId()).replace("Отдел ", ""))
                    + (x.getTelegramId() == null ? " · 🟡" : "");
            rows.add(irow(btn(cut(label, 55), "a:ctrt:" + x.getId())));
        }
        rows.add(irow(btn("⬅️ Orqaga", BACK)));
        show(chatId, msgId, "👥 <b>Qo'shimcha oluvchilar</b>\n\nQarz qo'shilganda/to'langanda va kunlik jamlamani "
                + "SuperAdmin'lardan tashqari kimlar olsin? Otdelga biriktirilgan xodim faqat O'Z otdeli xabarini oladi, "
                + "otdelsiz (masalan buxgalter) — hammasini.\n\n✅ — oladi, ▫️ — olmaydi:", inline(rows));
    }


    /* ---------- qoidalar ---------- */

    private void rules(long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String c : ControlConfig.ALL_RULES)
            rows.add(irow(btn((cfg.ruleOn(c) ? "✅ " : "▫️ ") + c + " · " + ControlConfig.ruleTitle(c), "a:ctkt:" + c)));
        for (String c : ControlConfig.SHIP_RULES)
            rows.add(irow(btn((cfg.ruleOn(c) ? "✅ " : "▫️ ") + c + " · " + ControlConfig.ruleTitle(c), "a:ctkt:" + c)));
        rows.add(irow(btn("⬅️ Orqaga", BACK)));
        show(chatId, msgId, "📏 <b>Qoidalar</b>\n\n<b>K1–K8 — kontragent:</b> yangi kontragent yaratilganda tekshiriladi, "
                + "xato bo'lsa yaratgan xodimga xabar.\n<b>O1–O5 — otgruzka:</b> qarzga tushgan otgruzkada tekshiriladi, "
                + "xodimga guruhlangan xabar (⚠️ Хатолар → 📦 Otgruzkalar). O2/O4 standart o'chiq — deyarli hamma otgruzkada bo'sh.\n\n"
                + "Bosib yoqing/o'chiring:", inline(rows));
    }


    /* ---------- 👔 xodimlar: BARCHA bot foydalanuvchilari + botda yo'q MoySklad xodimlari ---------- */

    private void employees(Session s, long chatId, int msgId) {
        List<MoySkladClient.MsEmployeeFull> emps = link.employees();
        s.data.put("ctEmps", emps);
        List<AppUser> users = new ArrayList<>(userRepo.findByActiveTrueOrderByRoleAscIdAsc());
        users.sort(java.util.Comparator.comparing((AppUser x) -> x.getKassaId() == null ? Long.MAX_VALUE : x.getKassaId())
                .thenComparing(AppUser::getId));
        long tgNo = users.stream().filter(x -> x.getTelegramId() == null).count();
        long msNo = users.stream().filter(x -> x.getMsUid() == null && x.getMsEmployeeId() == null).count();
        StringBuilder sb = new StringBuilder("👔 <b>Xodimlar</b> — bot foydalanuvchilari: <b>" + users.size() + "</b> ta");
        if (tgNo > 0) sb.append(" · Telegram yo'q: <b>").append(tgNo).append("</b>");
        if (msNo > 0) sb.append(" · MoySklad'siz: <b>").append(msNo).append("</b>");
        sb.append("\n✅ Telegram ulangan · 🟡 ulanmagan (botga kirib kontakt yuborsin) · 👔 MoySklad bog'langan · ✖ bog'lanmagan"
                + "\n👑 SuperAdmin · 🧮 buxgalter · 🎖 otdel rahbari. Xodimni bosing — otdel, MoySklad, rahbarlik.\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (AppUser x : users) {
            String label = (x.getTelegramId() == null ? "🟡 " : "✅ ") + roleMark(x) + x.getFullName()
                    + " · " + (x.getKassaId() == null ? "otdelsiz" : kassaName(x.getKassaId()).replace("Отдел ", ""))
                    + (x.getMsUid() != null || x.getMsEmployeeId() != null ? " 👔" : " ✖")
                    + (headRepo.findByUserId(x.getId()).isEmpty() ? "" : " 🎖");
            rows.add(irow(btn(cut(label, 60), "a:ctu:" + x.getId())));
        }
        int missing = 0;
        for (int i = 0; i < emps.size(); i++) {
            MoySkladClient.MsEmployeeFull e = emps.get(i);
            if (e.archived()) continue;
            boolean linked = users.stream().anyMatch(x -> e.id().equals(x.getMsEmployeeId())
                    || (e.uid() != null && !e.uid().isBlank() && e.uid().equalsIgnoreCase(x.getMsUid())));
            if (linked) continue;
            missing++;
            rows.add(irow(btn(cut("❌ " + e.name() + (e.groupName().isBlank() ? "" : " · " + e.groupName().replace("Отдел ", "")), 60),
                    "a:ctel:" + i)));
        }
        if (emps.isEmpty()) sb.append("⚠️ MoySklad xodimlari o'qilmadi (API kaliti?).\n");
        else if (missing > 0) sb.append("❌ MoySklad'da bor, botda yo'q: <b>").append(missing).append("</b> ta — bosib bog'lang yoki 🔄 sinxron yaratadi.\n");
        rows.add(irow(btn("🔄 MoySklad bilan sinxron (otdel · yangi xodim · rahbar)", "a:ctey")));
        rows.add(irow(btn("⬅️ Orqaga", BACK)));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    private static String roleMark(AppUser x) {
        return x.getRole() == Role.SUPERADMIN ? "👑 " : x.getRole() == Role.BUXGALTER ? "🧮 " : "";
    }

    private static String roleName(Role r) {
        return r == Role.SUPERADMIN ? "👑 SuperAdmin" : r == Role.BUXGALTER ? "🧮 Buxgalter" : "👤 Kassir";
    }

    private static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    @SuppressWarnings("unchecked")
    private List<MoySkladClient.MsEmployeeFull> emps(Session s) {
        Object o = s.data.get("ctEmps");
        return o instanceof List<?> l ? (List<MoySkladClient.MsEmployeeFull>) l : link.employees();
    }

    private MoySkladClient.MsEmployeeFull empOf(Session s, AppUser x) {
        return emps(s).stream().filter(m -> m.id().equals(x.getMsEmployeeId())
                || (m.uid() != null && !m.uid().isBlank() && m.uid().equalsIgnoreCase(x.getMsUid()))).findFirst().orElse(null);
    }


    /** 👤 Foydalanuvchi kartasi: rol, telefon, Telegram, otdel, MoySklad, rahbarlik. */
    private void userCard(Session s, long userId, long chatId, int msgId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null) { employees(s, chatId, msgId); return; }
        MoySkladClient.MsEmployeeFull e = empOf(s, x);
        List<String> headOf = new ArrayList<>();
        for (KassaHead h : headRepo.findByUserId(userId)) headOf.add(kassaName(h.getKassaId()));
        StringBuilder sb = new StringBuilder("👤 <b>" + esc(x.getFullName()) + "</b> · " + roleName(x.getRole()) + "\n");
        sb.append("📞 ").append(x.getPhone() == null || x.getPhone().isBlank() ? "—" : esc(x.getPhone())).append("\n");
        sb.append("📱 Telegram: ").append(x.getTelegramId() == null ? "🟡 ulanmagan — botga kirib kontakt (telefon) yuborsin" : "✅ ulangan").append("\n");
        sb.append("🏪 Otdel: <b>").append(x.getKassaId() == null ? "—" : esc(kassaName(x.getKassaId()))).append("</b>\n");
        sb.append("👔 MoySklad: ").append(e == null
                ? (x.getMsUid() == null ? "✖ bog'lanmagan" : esc(x.getMsUid()))
                : esc(e.name()) + (e.groupName().isBlank() ? "" : " · " + esc(e.groupName()))
                    + (e.position() == null || e.position().isBlank() ? "" : " · " + esc(e.position()))).append("\n");
        sb.append("🎖 Rahbar: ").append(headOf.isEmpty() ? "—" : esc(String.join(", ", headOf))).append("\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("🏪 Otdel", "a:ctuk:" + userId), btn("👔 MoySklad xodimi", "a:ctue:" + userId)));
        rows.add(irow(btn("🎖 Rahbarlik (otdellar)", "a:ctuh:" + userId)));
        rows.add(irow(btn("⬅️ Xodimlar", "a:cte")));
        show(chatId, msgId, sb.toString(), inline(rows));
    }

    /** 🏪 Otdel tanlash. */
    private void userKassaPick(Session s, long userId, long chatId, int msgId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null) { employees(s, chatId, msgId); return; }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn((k.getId().equals(x.getKassaId()) ? "✅ " : "🏪 ") + k.getName(), "a:ctuks:" + userId + "." + k.getId())));
        rows.add(irow(btn((x.getKassaId() == null ? "✅ " : "") + "➖ Otdelsiz", "a:ctuks:" + userId + ".0")));
        rows.add(irow(btn("⬅️ Orqaga", "a:ctu:" + userId)));
        show(chatId, msgId, "🏪 <b>" + esc(x.getFullName()) + "</b> — otdelni tanlang.\n"
                + "ℹ️ MoySklad xodimi bog'langan bo'lsa soatlik sinxron otdelni MoySklad'dagi bo'limga qaytaradi.", inline(rows));
    }

    private void userKassaSet(AppUser admin, Session s, String arg, long chatId, int msgId) {
        String[] p = arg.split("\\.");
        long userId = Long.parseLong(p[0]);
        long kassaId = Long.parseLong(p[1]);
        userRepo.findById(userId).ifPresent(x -> {
            String old = x.getKassaId() == null ? "—" : kassaName(x.getKassaId());
            x.setKassaId(kassaId == 0 ? null : kassaId);
            userRepo.save(x);
            audit.log(admin.getId(), "OTDEL_QOLDA", "user", userId, old + " -> " + (kassaId == 0 ? "—" : kassaName(kassaId)));
        });
        userCard(s, userId, chatId, msgId);
    }

    /** 👔 Bot foydalanuvchisiga MoySklad xodimini tanlash. */
    private void userEmpPick(Session s, long userId, long chatId, int msgId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null) { employees(s, chatId, msgId); return; }
        List<MoySkladClient.MsEmployeeFull> emps = emps(s);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < emps.size(); i++) {
            MoySkladClient.MsEmployeeFull e = emps.get(i);
            if (e.archived()) continue;
            boolean cur = e.id().equals(x.getMsEmployeeId()) || (e.uid() != null && !e.uid().isBlank() && e.uid().equalsIgnoreCase(x.getMsUid()));
            rows.add(irow(btn(cut((cur ? "✅ " : "👔 ") + e.name() + (e.groupName().isBlank() ? "" : " · " + e.groupName().replace("Отдел ", "")), 60),
                    "a:ctues:" + userId + "." + i)));
        }
        if (x.getMsUid() != null || x.getMsEmployeeId() != null) rows.add(irow(btn("➖ Bog'lanishni olib tashlash", "a:ctuex:" + userId)));
        rows.add(irow(btn("⬅️ Orqaga", "a:ctu:" + userId)));
        show(chatId, msgId, "👔 <b>" + esc(x.getFullName()) + "</b> — MoySklad'da qaysi xodim?", inline(rows));
    }

    private void userEmpSet(AppUser admin, Session s, String arg, long chatId, int msgId) {
        String[] p = arg.split("\\.");
        long userId = Long.parseLong(p[0]);
        int idx = Integer.parseInt(p[1]);
        List<MoySkladClient.MsEmployeeFull> emps = emps(s);
        if (idx >= 0 && idx < emps.size()) {
            MoySkladClient.MsEmployeeFull e = emps.get(idx);
            for (AppUser o : userRepo.findByActiveTrueOrderByRoleAscIdAsc())
                if (!o.getId().equals(userId) && (e.id().equals(o.getMsEmployeeId()) || (e.uid() != null && e.uid().equalsIgnoreCase(o.getMsUid()))))
                    link.unlink(o);
            userRepo.findById(userId).ifPresent(x -> {
                link.link(x, e.id(), e.uid());
                audit.log(admin.getId(), "MS_XODIM_BOGLANDI", "user", x.getId(), e.name() + " → " + x.getFullName());
            });
        }
        userCard(s, userId, chatId, msgId);
    }

    private void userEmpUnlink(AppUser admin, Session s, long userId, long chatId, int msgId) {
        userRepo.findById(userId).ifPresent(x -> {
            link.unlink(x);
            audit.log(admin.getId(), "MS_XODIM_UZILDI", "user", x.getId(), x.getFullName());
        });
        userCard(s, userId, chatId, msgId);
    }

    /** 🎖 Rahbarlik: qaysi otdellarda rahbar (bir nechta bo'lishi mumkin). */
    private void userHeadPick(Session s, long userId, long chatId, int msgId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null) { employees(s, chatId, msgId); return; }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            boolean cur = headRepo.findByKassaIdAndUserId(k.getId(), userId).isPresent();
            rows.add(irow(btn((cur ? "✅ " : "▫️ ") + k.getName(), "a:ctuht:" + userId + "." + k.getId())));
        }
        rows.add(irow(btn("⬅️ Orqaga", "a:ctu:" + userId)));
        show(chatId, msgId, "🎖 <b>" + esc(x.getFullName()) + "</b> — qaysi otdel(lar) rahbari? Rahbar o'z otdeli qarzdorlari va "
                + "kontragent xatolarini oladi. Bosib yoqing/o'chiring:", inline(rows));
    }

    private void userHeadToggle(AppUser admin, Session s, String arg, long chatId, int msgId) {
        String[] p = arg.split("\\.");
        long userId = Long.parseLong(p[0]);
        long kassaId = Long.parseLong(p[1]);
        headToggle(admin, kassaId + "." + userId, chatId, 0);   // xabar yubormaydi (msgId=0 → headKassa ko'rinishi)
        userHeadPick(s, userId, chatId, msgId);
    }


    /* ---------- MoySklad xodimi → bot foydalanuvchisi (botda yo'qlar uchun) ---------- */

    private void employeePick(Session s, int idx, long chatId, int msgId) {
        List<MoySkladClient.MsEmployeeFull> emps = emps(s);
        if (idx < 0 || idx >= emps.size()) { employees(s, chatId, msgId); return; }
        MoySkladClient.MsEmployeeFull e = emps.get(idx);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc()) {
            boolean cur = e.id().equals(x.getMsEmployeeId()) || (e.uid() != null && e.uid().equalsIgnoreCase(x.getMsUid()));
            String label = (cur ? "✅ " : "👤 ") + x.getFullName() + (x.getPhone() == null ? "" : " · " + x.getPhone());
            rows.add(irow(btn(cut(label, 55), "a:ctes:" + idx + "." + x.getId())));
        }
        rows.add(irow(btn("➕ Yangi foydalanuvchi sifatida yaratish (🔄 sinxron)", "a:ctey")));
        rows.add(irow(btn("⬅️ Orqaga", "a:cte")));
        show(chatId, msgId, "👔 <b>" + esc(e.name()) + "</b>\n🔑 " + esc(e.uid()) + (e.phone().isBlank() ? "" : " · 📞 " + esc(e.phone()))
                + (e.groupName().isBlank() ? "" : "\n🏪 " + esc(e.groupName()))
                + "\n\nBotdagi qaysi foydalanuvchi shu odam?", inline(rows));
    }

    private void employeeSet(AppUser admin, Session s, String arg, long chatId, int msgId) {
        String[] p = arg.split("\\.");
        int idx = Integer.parseInt(p[0]);
        long userId = Long.parseLong(p[1]);
        List<MoySkladClient.MsEmployeeFull> emps = emps(s);
        if (idx < 0 || idx >= emps.size()) { employees(s, chatId, msgId); return; }
        MoySkladClient.MsEmployeeFull e = emps.get(idx);
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc())
            if (!x.getId().equals(userId) && (e.id().equals(x.getMsEmployeeId()) || (e.uid() != null && e.uid().equalsIgnoreCase(x.getMsUid()))))
                link.unlink(x);
        userRepo.findById(userId).ifPresent(x -> {
            link.link(x, e.id(), e.uid());
            audit.log(admin.getId(), "MS_XODIM_BOGLANDI", "user", x.getId(), e.name() + " → " + x.getFullName());
        });
        employees(s, chatId, msgId);
    }

    private void employeeUnlink(AppUser admin, Session s, int idx, long chatId, int msgId) {
        List<MoySkladClient.MsEmployeeFull> emps = emps(s);
        if (idx >= 0 && idx < emps.size()) {
            MoySkladClient.MsEmployeeFull e = emps.get(idx);
            for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc())
                if (e.id().equals(x.getMsEmployeeId()) || (e.uid() != null && e.uid().equalsIgnoreCase(x.getMsUid()))) {
                    link.unlink(x);
                    audit.log(admin.getId(), "MS_XODIM_UZILDI", "user", x.getId(), e.name());
                }
        }
        employees(s, chatId, msgId);
    }


    /* ---------- otdel rahbarlari ---------- */

    private void heads(long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            List<String> names = new ArrayList<>();
            for (KassaHead h : headRepo.findByKassaId(k.getId()))
                userRepo.findById(h.getUserId()).ifPresent(x -> names.add(x.getFullName()));
            String label = "🏪 " + k.getName() + " · " + (names.isEmpty() ? "—" : String.join(", ", names));
            rows.add(irow(btn(label.length() > 60 ? label.substring(0, 60) : label, "a:cthk:" + k.getId())));
        }
        rows.add(irow(btn("⬅️ Orqaga", BACK)));
        show(chatId, msgId, "🏪 <b>Otdel rahbarlari</b>\n\nRahbar o'z otdeli qarzdorlari va kontragent xatolarini "
                + "oladi hamda bo'limda ko'radi. Kassani tanlang:", inline(rows));
    }

    private void headKassa(long kassaId, long chatId, int msgId) {
        Kassa k = kassaRepo.findById(kassaId).orElse(null);
        if (k == null) { heads(chatId, msgId); return; }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc()) {
            boolean cur = headRepo.findByKassaIdAndUserId(kassaId, x.getId()).isPresent();
            String label = (cur ? "✅ " : "▫️ ") + x.getFullName() + (x.getKassaId() == null ? "" : " · " + kassaName(x.getKassaId()));
            rows.add(irow(btn(label.length() > 55 ? label.substring(0, 55) : label, "a:ctht:" + kassaId + "." + x.getId())));
        }
        rows.add(irow(btn("⬅️ Orqaga", "a:cth")));
        show(chatId, msgId, "🏪 <b>" + esc(k.getName()) + "</b> — rahbar(lar)ni belgilang (✅ — rahbar):", inline(rows));
    }

    private void headToggle(AppUser admin, String arg, long chatId, int msgId) {
        String[] p = arg.split("\\.");
        long kassaId = Long.parseLong(p[0]);
        long userId = Long.parseLong(p[1]);
        var cur = headRepo.findByKassaIdAndUserId(kassaId, userId);
        if (cur.isPresent()) {
            headRepo.delete(cur.get());
            audit.log(admin.getId(), "OTDEL_RAHBAR_OLINDI", "kassa", kassaId, kassaName(kassaId) + " ← " + userName(userId));
        } else {
            headRepo.save(KassaHead.builder().kassaId(kassaId).userId(userId).build());
            audit.log(admin.getId(), "OTDEL_RAHBAR_QOSHILDI", "kassa", kassaId, kassaName(kassaId) + " → " + userName(userId));
        }
        if (msgId > 0) headKassa(kassaId, chatId, msgId);
    }


    /* ---------- test / hozir ---------- */

    private void test(AppUser u, long chatId) {
        String kg = "⚠️ <b>Контрагент хато киритилди</b> <i>(TEST)</i>\n👤 Xodim: " + esc(u.getFullName())
                + "\n🏢 Kontragent: <b>Test Kontragent</b>\n🕒 Yaratildi: hozir (MoySklad)\n\n<b>Tuzatish kerak:</b>\n"
                + "• 📞 Telefon kiritilmagan\n• 🏷 Guruh (Группы) tanlanmagan\n\nℹ️ MoySklad'da tuzating — bot o'zi tekshiradi.";
        String ot = "🧾 <b>Отгрузка тўлови тўлиқ эмас — қарздорлар рўйхатига қўшилди</b> <i>(TEST)</i>\n"
                + "👤 Xodim: <b>" + esc(u.getFullName()) + "</b>\n🏢 Klient: <b>Test Klient</b>\n📞 Telefon: +998 90 123 45 67\n"
                + "📦 Otgruzka: <b>№00000</b> · " + LocalDate.now(cfg.zone()) + " · Карз\n💰 Summa: <b>420 000</b> · To'langan: 0 · Qoldiq: <b>420 000</b> so'm\n"
                + "📅 To'lov muddati: ❗ <b>kiritilmagan</b> — MoySklad'da «Тўлов муддати»ni to'ldiring\n👔 Масъул: —\n💬 Komentariya: test";
        sender.send(chatId, kg);
        sender.send(chatId, ot);
    }

    private void runNow(long chatId, int msgId) {
        sender.edit(chatId, msgId, "⏳ Yangilanmoqda: xodimlar, kontragentlar, otgruzkalar, balanslar… (10–90 soniya)");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder("✅ <b>Yangilash yakunlandi</b>\n");
            try {
                var r = link.syncEmployees(true, null);
                sb.append("• xodimlar ✔️ (yaratildi ").append(r.created()).append(", otdel ").append(r.kassaSet())
                  .append(", rahbar ").append(r.headsSet()).append(")\n");
            } catch (Exception e) { sb.append("• xodimlar: ⚠️ ").append(esc(String.valueOf(e.getMessage()))).append("\n"); }
            try { agents.tick(); sb.append("• kontragentlar ✔️\n"); } catch (Exception e) { sb.append("• kontragentlar: ⚠️ ").append(esc(String.valueOf(e.getMessage()))).append("\n"); }
            try { ships.tick(); sb.append("• otgruzkalar ✔️\n"); } catch (Exception e) { sb.append("• otgruzkalar: ⚠️ ").append(esc(String.valueOf(e.getMessage()))).append("\n"); }
            try { ships.balanceTick(); sb.append("• balanslar ✔️\n"); } catch (Exception e) { sb.append("• balanslar: ⚠️ ").append(esc(String.valueOf(e.getMessage()))).append("\n"); }
            sb.append("\n🧾 Qarzda: <b>").append(ships.debtCount()).append("</b> ta · ⚠️ xato: <b>").append(agents.openCount()).append("</b> ta");
            sender.send(chatId, sb.toString(), inline(List.of(irow(btn("⬅️ Назорат", BACK)))));
        }, "control-run-now").start();
    }


    /* ---------- yordamchi ---------- */

    private void show(long chatId, int msgId, String text, InlineKeyboardMarkup kb) {
        if (msgId > 0) sender.edit(chatId, msgId, text, kb);
        else sender.send(chatId, text, kb);
    }

    private String kassaName(Long id) { return kassaRepo.findById(id).map(Kassa::getName).orElse("#" + id); }

    private String userName(Long id) { return userRepo.findById(id).map(AppUser::getFullName).orElse("#" + id); }
}
