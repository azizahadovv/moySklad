package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.*;
import uz.kassa.domain.*;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.service.LedgerService;
import uz.kassa.service.NotificationService;
import uz.kassa.service.moysklad.MoySkladClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.*;

/** SuperAdmin oqimlari (TZ 8.3): foydalanuvchi/kassa boshqaruvi, boshlang'ich qoldiqlar. */
@Component
@RequiredArgsConstructor
public class AdminHandler {

    private final Sender sender;
    private final NameService names;
    private final LedgerService ledger;
    private final AppUserRepo userRepo;
    private final KassaRepo kassaRepo;
    private final uz.kassa.repo.GuestRepo guestRepo;
    private final MoySkladClient msClient;
    private final uz.kassa.repo.DayRepo dayRepo;
    private final uz.kassa.repo.OperationRepo opRepo;
    private final uz.kassa.repo.DebtRepo debtRepo;
    private final NotificationService notify;
    private final uz.kassa.service.SubmissionService submissionService;
    private final uz.kassa.webapp.ExcelReportService excelReport;
    private final uz.kassa.repo.ClickAccountRepo clickRepo;
    private final BuxgalterHandler bux;
    private final uz.kassa.service.moysklad.MoySkladSyncService syncService;
    private final uz.kassa.repo.AuditRepo auditRepo;
    private final uz.kassa.service.AuditService audit;
    private final uz.kassa.service.RasxodService rasxodService;
    private final LabelService labelSvc;
    private final PermService permSvc;
    private final uz.kassa.config.AppProps props;
    private final uz.kassa.repo.SubmissionRepo subRepo;
    private final uz.kassa.repo.CategoryRepo categoryRepo;
    private final uz.kassa.service.BalansService balansSvc;

    private static final java.time.format.DateTimeFormatter DF =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /* ============================ MATN ============================ */

    public boolean onText(AppUser u, Session s, String text, long chatId) {
        if (u.getRole() == Role.KASSIR) return false;

        // 💰 Pul qabul qilish: summa kiritish (Buxgalter ham, Admin ham)
        if (s.state == Session.State.ADM_QB_SUM) { qbSum(u, s, text, chatId); return true; }

        // 💸 Kassa nomidan rasxod: summa/izoh (Buxgalter ham, Admin ham)
        if (s.state == Session.State.ADM_KRX_SUM) { krxSum(s, text, chatId); return true; }
        if (s.state == Session.State.ADM_KRX_CMT) { krxFinish(u, s, text, chatId); return true; }

        if (u.getRole() == Role.SUPERADMIN) switch (s.state) {
            case ADM_AU_TGID -> { auTgId(s, text, chatId); return true; }
            case ADM_AU_NAME -> { auName(s, text, chatId); return true; }
            case ADM_AK_NAME -> { akName(s, text, chatId); return true; }
            case ADM_AK_MSID -> { akFinish(s, text, chatId); return true; }
            case ADM_IB_NAQD -> { ibNaqd(s, text, chatId); return true; }
            case ADM_IB_KLIK -> { ibFinish(u, s, text, chatId); return true; }
            case ADM_IB_SANA -> { ibSana(u, s, text, chatId); return true; }
            case ADM_KR_SUM -> { krSum(s, text, chatId); return true; }
            case ADM_KR_IZOH -> { krIzoh(u, s, text, chatId); return true; }
            case ADM_KR_VAQT -> { krVaqt(u, s, text, chatId); return true; }
            case ADM_CK_SUM -> { ckSum(s, text, chatId); return true; }
            case ADM_CK_SANA -> { ckSana(u, s, text, chatId); return true; }
            case ADM_RXE_SUM -> { rxEditSum(u, s, text, chatId); return true; }
            case ADM_LB_NAME -> { labelName(s, text, chatId); return true; }
            case ADM_MS_TOKEN -> { msTokenSave(u, s, text, chatId); return true; }
            default -> { }
        }

        // Panel ichida bo'lsa — pastki menu tugmalari bo'yicha navigatsiya
        String nav = s.getStr("nav");
        if (nav != null && handleNav(u, s, nav, text, chatId)) return true;

        // Panelga kirish (🏪 KASSA; eski nomlar ham ishlaydi)
        switch (text) {
            case "🏪 KASSA", "👑 АДМИН ПАНЕЛ", "📊 ПАНЕЛ" -> {
                navTo(u, s, "panel", chatId, panelTitle(u), panelLabels(u));
                return true;
            }
            case "💰 БУГУНГИ ТУШУМ" -> { tushumAll(s, chatId); return true; }
            case "💰 Баланс" -> {
                syncService.syncIfStale(45);
                sendContent(s, chatId, balansSvc.buildAll(uz.kassa.service.BalansService.JAMI),
                        balansKb());
                return true;
            }
            default -> { }
        }

        if (u.getRole() != Role.SUPERADMIN) return false;
        return switch (text) {
            case "👥 Foydalanuvchi qo'shish" -> { auStart(s, chatId); yield true; }
            case "🏪 Kassa qo'shish" -> {
                s.reset(); s.state = Session.State.ADM_AK_NAME;
                sender.send(chatId, "🏪 <b>Yangi kassa</b>\n\nKassa nomini kiriting:", cancelOnly());
                yield true;
            }
            case "💼 Boshlang'ich qoldiq" -> { ibStart(s, chatId); yield true; }
            case "👤 Foydalanuvchilar" -> { listUsers(u, chatId); yield true; }
            default -> false;
        };
    }

    /* ============================ CALLBACK ============================ */

    public boolean onCallback(AppUser u, Session s, String data, long chatId, int msgId) {
        if (!data.startsWith("a:")) return false;
        if (u.getRole() == Role.KASSIR) return false;
        String[] p = data.split(":", 3);
        String cmd = p[1];
        String arg = p.length > 2 ? p[2] : "";

        // Buxgalter: panel ko'rinishlari, pul qabul qilish, kassa nomidan rasxod,
        // kalendar va Баланс
        if (u.getRole() == Role.BUXGALTER
                && !java.util.Set.of("p", "qbu", "qbd", "cal", "krx", "bl").contains(cmd))
            return false;

        // Ҳуқуқлар — faqat asosiy (yaratuvchi) SuperAdmin o'zgartira oladi
        if (java.util.Set.of("prm", "prc", "prs", "prt", "prko", "prk", "prq").contains(cmd)
                && !isCreator(u)) {
            sender.send(chatId, "⚠️ Ҳуқуқлар bo'limini faqat asosiy (yaratuvchi) "
                    + "SuperAdmin boshqaradi.");
            return true;
        }

        switch (cmd) {
            case "p" -> panel(u, s, arg, chatId, msgId);
            case "qbu" -> qbUser(u, s, arg, chatId, msgId);
            case "qbd" -> qbDate(u, s, arg, chatId, msgId);
            case "cal" -> calCb(u, s, arg, chatId, msgId);
            case "rxl" -> rxList(s, chatId, msgId);
            case "rxc" -> rxCard(Long.parseLong(arg), chatId, msgId);
            case "rxx" -> rxConfirm(Long.parseLong(arg), chatId, msgId);
            case "rxy" -> rxCancel(u, Long.parseLong(arg), chatId, msgId);
            case "rxe" -> rxEditStart(s, Long.parseLong(arg), chatId, msgId);
            case "audm" -> auditMenu(s, chatId, msgId);
            case "aud" -> auditView(s, Long.parseLong(arg), chatId, msgId);
            case "aux" -> auditExcel(Long.parseLong(arg), chatId);
            case "lbm" -> labelList(s, chatId, msgId);
            case "lb" -> labelPick(s, Integer.parseInt(arg), chatId, msgId);
            case "lbr" -> labelRenameStart(s, Integer.parseInt(arg), chatId, msgId);
            case "lbh" -> labelHideToggle(s, Integer.parseInt(arg), chatId, msgId);
            case "msk" -> msToken(s, chatId, msgId);
            case "prm" -> permMenu(s, chatId, msgId);
            case "prc" -> permCard(Long.parseLong(arg), chatId, msgId);
            case "prs" -> permGrid("user", Long.parseLong(arg), chatId, msgId);
            case "prt" -> permToggle(u, "user", arg, chatId, msgId);
            case "prko" -> permKassaList(chatId, msgId);
            case "prk" -> permGrid("kassa", Long.parseLong(arg), chatId, msgId);
            case "prq" -> permToggle(u, "kassa", arg, chatId, msgId);
            case "mske" -> {
                s.state = Session.State.ADM_MS_TOKEN;
                sender.edit(chatId, msgId, "🔑 <b>Yangi MoySklad API kalitini yuboring</b>\n\n"
                        + "MoySklad → Sozlamalar → Tokenlar bo'limidan olinadi.\n"
                        + "Bekor qilish uchun «-» yuboring.");
            }
            case "gu" -> auPick(s, arg, chatId, msgId);
            case "me" -> auEmp(s, arg, chatId, msgId);
            case "rl" -> auRole(s, arg, chatId, msgId);
            case "ks" -> auKassa(s, arg, chatId, msgId);
            case "gr" -> akGroup(s, arg, chatId, msgId);
            case "ib" -> ibOwner(s, arg, chatId, msgId);
            case "ibd" -> ibSanaBtn(u, s, arg, chatId, msgId);
            case "kro" -> krOwner(s, arg, chatId, msgId);
            case "krm" -> krMt(s, arg, chatId, msgId);
            case "krd" -> krSanaBtn(s, arg, chatId, msgId);
            case "krt" -> krVaqtNow(u, s, chatId, msgId);
            case "krx" -> krxCb(u, s, arg, chatId, msgId);
            case "bl" -> sender.edit(chatId, msgId,
                    balansSvc.buildAll(arg.isEmpty() ? 'j' : arg.charAt(0)), balansKb());
            case "rz" -> rzPick(s, arg, chatId, msgId);
            case "rzc" -> rzCommit(u, s, arg, chatId, msgId);
            case "ckq" -> ckStart(s, arg, chatId, msgId);
            case "ckd" -> ckSanaBtn(u, s, arg, chatId, msgId);
            case "ux" -> deactivate(u, Long.parseLong(arg), chatId, msgId);
            default -> { return false; }
        }
        return true;
    }

    /* ==================================================================
     * 👑 АДМИН ПАНЕЛ — PASTKI MENU TUGMALARI bilan ichma-ich navigatsiya.
     * Har daraja reply-keyboard'ni almashtiradi, «⬅️ Orqaga» bir pog'ona
     * yuqoriga qaytaradi (bosh sahifaga emas).
     * ================================================================== */

    private static final List<String> PERIODS =
            List.of("📆 Bugun", "Kecha", "7 kun", "30 kun", "Shu oy", "🗓 Kalendar");
    /** Kassa kartasi bo'limlari (reply-menyu). */
    private static final List<String> KASSA_MENU =
            List.of("💰 Бугунги тушум", "📆 Давр танлаш", "💵 Топширилмаган пул", "💸 Расход");
    /** Основной отдел (Buxgalteriya) kartasi bo'limlari. */
    private static final List<String> OSN_MENU =
            List.of("💵 Пул қолдиғи", "🏦 Ҳисобот");
    private static final String OSN_LABEL = "🏦 Отдел основной";
    private static final List<String> SOZLASH_MENU =
            List.of("🏪 Касса", "👥 Фойдаланувчилар", "💼 Бошланғич қолдиқ",
                    "🛠 Корректировка", "🧾 Расходлар", "📋 Аудит",
                    "🏷 Тугма номлари", "🔑 MoySklad API", "👁 Ҳуқуқлар",
                    "♻️ Нол бошлаш");
    private static final List<String> STAT_MENU =
            List.of("🏪 Кассалар холати", "🧾 Карзлар реестр", "📜 История",
                    "👥 Фойдаланувчилар умумий",
                    "🏦 Бухгалтерия", "💼 Салдо", "📲 Кликлар", "📊 Свод");
    private static final List<String> SOZUSER_MENU =
            List.of("➕ Фойдаланувчи қўшиш", "🔄 Рол ўзгартириш", "🚫 Фойдаланувчини ўчириш");

    /** Panel nomi va bo'limlari — rol kesimida. */
    private String panelTitle(AppUser u) {
        return "🏪 <b>KASSA</b>\n\nBo'limni tanlang:";
    }

    private List<String> panelLabels(AppUser u) {
        return u.getRole() == Role.SUPERADMIN
                ? List.of("🏬 Отдел", "⚙️ Настройка", "📈 Статистика",
                          "💰 Бугунги тушум", "🏪 Кассалар холати")
                : List.of("🏬 Отдел", "📈 Статистика", "💰 Бугунги тушум", "🏪 Кассалар холати");
    }

    private List<String> statLabels(AppUser u) {
        return u.getRole() == Role.SUPERADMIN
                ? STAT_MENU
                : List.of("🏪 Кассалар холати", "🧾 Карзлар реестр", "📜 История",
                          "🏦 Бухгалтерия", "💼 Салдо", "📲 Кликлар", "📊 Свод");
    }

    private void navTo(AppUser u, Session s, String nav, long chatId, String title, List<String> labels) {
        s.data.put("nav", nav);
        deletePrevPanel(s, chatId);
        Integer id = sender.sendId(chatId, title, menuKb(u, labels));
        if (id != null) s.data.put("panelMsg", id);
    }

    /** Oldingi panel va kontent xabarlarini o'chirish — chatda faqat 2 ta xabar qoladi. */
    private void deletePrevPanel(Session s, long chatId) {
        Object prev = s.data.remove("panelMsg");
        if (prev instanceof Integer i) sender.deleteMessage(chatId, i);
        Object c = s.data.remove("contentMsg");
        if (c instanceof Integer i2) sender.deleteMessage(chatId, i2);
    }

    /** Ma'lumot xabari: oldingisini o'chirib yuboradi — bittasi qoladi. */
    private void sendContent(Session s, long chatId, String text, InlineKeyboardMarkup kb) {
        Object prev = s.data.remove("contentMsg");
        if (prev instanceof Integer i) sender.deleteMessage(chatId, i);
        Integer id = sender.sendId(chatId, text, kb);
        if (id != null) s.data.put("contentMsg", id);
    }

    private org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup
            menuKb(AppUser u, List<String> labels) {
        List<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow> rows =
                new ArrayList<>();
        // Shu foydalanuvchi uchun yopiq bo'limlar yashiriladi, nomlar display bilan
        List<String> shown = labels.stream()
                .filter(l -> !LabelService.RENAMABLE.contains(l) || permSvc.visible(u, l))
                .map(labelSvc::display).toList();
        for (int i = 0; i < shown.size(); i += 2) {
            var r = new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow();
            r.add(shown.get(i));
            if (i + 1 < shown.size()) r.add(shown.get(i + 1));
            rows.add(r);
        }
        var back = new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow();
        back.add("⬅️ Orqaga");
        rows.add(back);
        var m = new org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup();
        m.setKeyboard(rows);
        m.setResizeKeyboard(true);
        return m;
    }

    private List<String> kassaLabels() {
        List<String> out = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) out.add("🏪 " + k.getName());
        return out;
    }

    /** 🏬 Отдел ro'yxati: Основной отдел (Buxgalteriya) + faol kassalar. */
    private List<String> otdelLabels() {
        List<String> out = new ArrayList<>();
        out.add(OSN_LABEL);
        out.addAll(kassaLabels());
        return out;
    }

    private Kassa kassaByLabel(String text) {
        String name = text.startsWith("🏪 ") ? text.substring(3).trim() : text.trim();
        return kassaRepo.findByActiveTrueOrderByIdAsc().stream()
                .filter(k -> k.getName().equals(name)).findFirst().orElse(null);
    }

    private String codeOf(String text) {
        return switch (text) {
            case "📆 Bugun" -> "t"; case "Kecha" -> "y";
            case "7 kun" -> "7"; case "30 kun" -> "30"; case "Shu oy" -> "m";
            default -> null;
        };
    }

    private long idOf(String nav) { return Long.parseLong(nav.substring(nav.indexOf(':') + 1)); }

    private boolean handleNav(AppUser u, Session s, String nav, String text, long chatId) {
        if (text.equals("⬅️ Orqaga")) { navBack(u, s, nav, chatId); return true; }
        String lvl = nav.split(":")[0];
        switch (lvl) {
            case "panel" -> {
                switch (text) {
                    case "🏬 Отдел" -> navTo(u, s, "otdel", chatId,
                            "🏬 <b>Отдел</b>\n\nKassani tanlang:", otdelLabels());
                    case "⚙️ Настройка" -> {
                        if (u.getRole() != Role.SUPERADMIN) return false;
                        navTo(u, s, "sozlash", chatId, "⚙️ <b>Настройка</b>", SOZLASH_MENU);
                    }
                    case "📈 Статистика" -> navTo(u, s, "stat", chatId,
                            "📈 <b>Статистика</b>", statLabels(u));
                    case "💰 Бугунги тушум" -> tushumAll(s, chatId);
                    case "🏪 Кассалар холати" -> bux.overview(chatId);
                    default -> { return false; }
                }
            }
            case "otdel" -> {
                if (text.equals(OSN_LABEL)) {
                    navTo(u, s, "kassab", chatId,
                            "🏦 <b>Отдел основной</b> (Буxгалтерия)\n\nBo'limni tanlang:", OSN_MENU);
                    return true;
                }
                Kassa k = kassaByLabel(text);
                if (k == null) return false;
                navTo(u, s, "kassa:" + k.getId(), chatId,
                        "🏪 <b>" + esc(k.getName()) + "</b>\n\nBo'limni tanlang:", KASSA_MENU);
            }
            case "kassa" -> {
                long id = idOf(nav);
                switch (text) {
                    case "💰 Бугунги тушум" -> { syncService.syncIfStale(45); kassaTushum(s, id, chatId, 0); }
                    case "📆 Давр танлаш" -> navTo(u, s, "davr:" + id, chatId,
                            "📆 <b>Давр танлаш</b>\n\nDavrni tanlang:", PERIODS);
                    case "💵 Топширилмаган пул" -> {
                        syncService.syncIfStale(45);
                        kassaTopshirilmagan(s, id, chatId, 0);
                    }
                    case "💸 Расход" -> {
                        syncService.syncIfStale(45);
                        kassaRasxodPanel(s, id, chatId);
                    }
                    default -> { return false; }
                }
            }
            case "kassab" -> {
                switch (text) {
                    case "💵 Пул қолдиғи" -> { syncService.syncIfStale(45); osnovnoyQoldiq(s, chatId); }
                    case "🏦 Ҳисобот" -> buxReport(s, chatId);
                    default -> { return false; }
                }
            }
            case "davr" -> {
                if (text.equals("🗓 Kalendar")) { calOpen(s, chatId, 0, "k" + idOf(nav)); return true; }
                String code = codeOf(text);
                if (code == null) return false;
                syncService.syncIfStale(45);
                kassaPeriodStats(s, idOf(nav), code, chatId, 0);
            }
            case "sozlash" -> {
                switch (text) {
                    case "🏪 Касса" -> navTo(u, s, "sozkassa", chatId, "🏪 <b>Касса</b>",
                            List.of("➕ Касса қўшиш", "🚫 Касса ўчириш"));
                    case "👥 Фойдаланувчилар" -> navTo(u, s, "sozuser", chatId,
                            "👥 <b>Фойдаланувчилар</b>", SOZUSER_MENU);
                    case "💼 Бошланғич қолдиқ" -> { ibStart(s, chatId); s.data.put("nav", "sozlash"); }
                    case "🛠 Корректировка" -> { krStart(s, chatId); s.data.put("nav", "sozlash"); }
                    case "🧾 Расходлар" -> rxList(s, chatId, 0);
                    case "📋 Аудит" -> auditMenu(s, chatId, 0);
                    case "🏷 Тугма номлари" -> labelList(s, chatId, 0);
                    case "🔑 MoySklad API" -> msToken(s, chatId, 0);
                    case "👁 Ҳуқуқлар" -> {
                        if (!isCreator(u)) {
                            sendContent(s, chatId, "⚠️ Ҳуқуқлар bo'limini faqat asosiy "
                                    + "(yaratuvchi) SuperAdmin boshqaradi.", null);
                            return true;
                        }
                        permMenu(s, chatId, 0);
                    }
                    case "♻️ Нол бошлаш" -> rzStart(s, chatId);
                    default -> { return false; }
                }
            }
            case "sozkassa" -> {
                switch (text) {
                    case "➕ Касса қўшиш" -> {
                        s.state = Session.State.ADM_AK_NAME;
                        sender.send(chatId, "🏪 <b>Yangi kassa</b>\n\nKassa nomini kiriting:");
                    }
                    case "🚫 Касса ўчириш" -> navTo(u, s, "kassadel", chatId,
                            "🚫 <b>Касса ўчириш</b>\n\nQaysi kassani o'chirasiz?", kassaLabels());
                    default -> { return false; }
                }
            }
            case "kassadel" -> {
                Kassa k = kassaByLabel(text);
                if (k == null) return false;
                navTo(u, s, "kassadelc:" + k.getId(), chatId,
                        "⚠️ <b>" + esc(k.getName()) + "</b> kassasi o'chirilsinmi?\n\n"
                                + "Kassa faolsizlanadi — tarix saqlanadi.",
                        List.of("✅ Ha, o'chirilsin", "❌ Yo'q"));
            }
            case "kassadelc" -> {
                if (text.startsWith("✅")) {
                    long id = idOf(nav);
                    kassaRepo.findById(id).ifPresent(k -> { k.setActive(false); kassaRepo.save(k); });
                    navTo(u, s, "sozkassa", chatId, "🚫 Kassa faolsizlantirildi",
                            List.of("➕ Касса қўшиш", "🚫 Касса ўчириш"));
                } else if (text.startsWith("❌")) {
                    navTo(u, s, "sozkassa", chatId, "🏪 <b>Касса</b>",
                            List.of("➕ Касса қўшиш", "🚫 Касса ўчириш"));
                } else return false;
            }
            case "sozuser" -> {
                switch (text) {
                    case "➕ Фойдаланувчи қўшиш" -> { auStart(s, chatId); s.data.put("nav", "sozuser"); }
                    case "🔄 Рол ўзгартириш" -> navTo(u, s, "roluser", chatId,
                            "🔄 <b>Рол ўзгартириш</b>\n\nFoydalanuvchini tanlang:", userLabels());
                    case "🚫 Фойдаланувчини ўчириш" -> listUsers(u, chatId);
                    default -> { return false; }
                }
            }
            case "roluser" -> {
                AppUser x = userByLabel(text);
                if (x == null) return false;
                navTo(u, s, "rolpick:" + x.getId(), chatId,
                        "🔄 <b>" + esc(x.getFullName()) + "</b> (hozir: " + x.getRole()
                                + (x.getKassaId() == null ? "" :
                                   " · " + esc(names.owner(OwnerType.KASSA, x.getKassaId()))) + ")\n\n"
                                + "Yangi rolni tanlang:", roleLabels());
            }
            case "rolpick" -> {
                if (!applyRole(idOf(nav), text, chatId)) return false;
                navTo(u, s, "sozuser", chatId, "👥 <b>Фойдаланувчилар</b>", SOZUSER_MENU);
            }
            case "stat" -> {
                switch (text) {
                    case "🏪 Кассалар холати" -> bux.overview(chatId);
                    case "🧾 Карзлар реестр" -> bux.debtsRegistry(chatId);
                    case "📜 История" -> bux.historyMenu(chatId);
                    case "👥 Фойдаланувчилар умумий" -> {
                        if (u.getRole() != Role.SUPERADMIN) return false;
                        listUsers(u, chatId);
                    }
                    case "🏦 Бухгалтерия" -> buxReport(s, chatId);
                    case "💼 Салдо" -> { syncService.syncIfStale(45); saldoKassa(s, "B", chatId, 0); }
                    case "📲 Кликлар" -> clickMenu(u, s, chatId, 0);
                    case "📊 Свод" -> navTo(u, s, "svod", chatId, "📊 <b>Свод</b>\n\nExcel turini tanlang:",
                            List.of("📗 Умумий Excel", "📘 Даврий Excel", "📙 Отдел Excel"));
                    default -> { return false; }
                }
            }
            case "svod" -> {
                switch (text) {
                    case "📗 Умумий Excel" -> genExcel(chatId, 0, "m", null);
                    case "📘 Даврий Excel" -> navTo(u, s, "svoddavr", chatId,
                            "📘 <b>Даврий Excel</b>\n\nDavrni tanlang:", PERIODS);
                    case "📙 Отдел Excel" -> navTo(u, s, "svodotd", chatId,
                            "📙 <b>Отдел Excel</b>\n\nKassani tanlang:", kassaLabels());
                    default -> { return false; }
                }
            }
            case "svoddavr" -> {
                if (text.equals("🗓 Kalendar")) { calOpen(s, chatId, 0, "x"); return true; }
                String code = codeOf(text);
                if (code == null) return false;
                genExcel(chatId, 0, code, null);
            }
            case "svodotd" -> {
                Kassa k = kassaByLabel(text);
                if (k == null) return false;
                navTo(u, s, "svodotdd:" + k.getId(), chatId,
                        "📙 <b>Отдел Excel</b> — " + esc(k.getName()) + "\n\nDavrni tanlang:", PERIODS);
            }
            case "svodotdd" -> {
                if (text.equals("🗓 Kalendar")) { calOpen(s, chatId, 0, "xo" + idOf(nav)); return true; }
                String code = codeOf(text);
                if (code == null) return false;
                genExcel(chatId, 0, code, idOf(nav));
            }
            default -> { return false; }
        }
        return true;
    }

    /* ---------- 🔄 ROL O'ZGARTIRISH ---------- */

    private List<String> userLabels() {
        List<String> out = new ArrayList<>();
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc())
            out.add("#" + x.getId() + " " + x.getFullName());
        return out;
    }

    private AppUser userByLabel(String text) {
        if (!text.startsWith("#")) return null;
        int sp = text.indexOf(' ');
        if (sp < 0) return null;
        try {
            return userRepo.findById(Long.parseLong(text.substring(1, sp))).orElse(null);
        } catch (NumberFormatException e) { return null; }
    }

    private List<String> roleLabels() {
        List<String> out = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            out.add("👤 Kassir — " + k.getName());
        out.add("🧮 Buxgalter");
        out.add("👑 SuperAdmin");
        return out;
    }

    private boolean applyRole(long userId, String text, long chatId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null) return false;

        Role newRole; Long kassaId = null;
        if (text.startsWith("👤 Kassir — ")) {
            Kassa k = kassaByLabel(text.substring("👤 Kassir — ".length()));
            if (k == null) return false;
            newRole = Role.KASSIR; kassaId = k.getId();
        } else if (text.equals("🧮 Buxgalter")) newRole = Role.BUXGALTER;
        else if (text.equals("👑 SuperAdmin")) newRole = Role.SUPERADMIN;
        else return false;

        // Oxirgi SuperAdmin'ni pasaytirib bo'lmaydi — tizim egasiz qolmasin
        if (x.getRole() == Role.SUPERADMIN && newRole != Role.SUPERADMIN
                && userRepo.findByRoleAndActiveTrue(Role.SUPERADMIN).size() <= 1) {
            sender.send(chatId, "⚠️ Bu oxirgi SuperAdmin — rolini o'zgartirib bo'lmaydi. "
                    + "Avval boshqa SuperAdmin tayinlang.");
            return true;
        }

        x.setRole(newRole);
        x.setKassaId(kassaId);
        userRepo.save(x);
        sender.send(chatId, "✅ <b>" + esc(x.getFullName()) + "</b> roli o'zgartirildi: <b>"
                + newRole + (kassaId == null ? "" : " · " + esc(names.owner(OwnerType.KASSA, kassaId)))
                + "</b>");
        notify.toUser(x.getTelegramId(),
                "🔄 Rolingiz o'zgartirildi. Yangi menyu uchun /start yozing.");
        return true;
    }

    /* ---------- 🏦 БУХГАЛТЕРИЯ HISOBOTI ---------- */

    private void buxReport(Session s, long chatId) {
        syncService.syncIfStale(45);
        var n = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD);
        var k = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.KLIK);
        java.time.LocalDate from = ledger.today().withDayOfMonth(1);

        long kirim = 0, chiqim = 0, boshl = 0;
        List<String> rasxodLines = new ArrayList<>();
        for (Operation o : opRepo.byPeriod(from, ledger.today())) {
            boolean in = o.getToOwnerType() == OwnerType.BUXGALTERIYA;
            boolean out = o.getFromOwnerType() == OwnerType.BUXGALTERIYA;
            if (!in && !out) continue;
            if (o.getType() == OpType.BOSHLANGICH && in) boshl += o.getAmount();
            else if (in) kirim += o.getAmount();
            if (out) {
                chiqim += o.getAmount();
                if (rasxodLines.size() < 15)
                    rasxodLines.add("• " + o.getOpDate().format(DF) + " — <b>"
                            + fmt(o.getAmount()) + "</b> so'm"
                            + (o.getComment() == null || o.getComment().isEmpty()
                                ? "" : " — " + esc(o.getComment())));
            }
        }

        StringBuilder sb = new StringBuilder("🏦 <b>БУХГАЛТЕРИЯ ҲИСОБОТИ</b>\n📅 "
                + from.format(DF) + " — " + ledger.today().format(DF) + "\n\n"
                + "💵 Naqd balans: <b>" + fmt(n.getAmount()) + "</b> so'm\n"
                + "📲 Click balans: <b>" + fmt(k.getAmount()) + "</b> so'm\n\n"
                + "⚙️ Boshlang'ich qoldiq: <b>" + fmt(boshl) + "</b>\n"
                + "🟢 Kirimlar (shu oy): <b>" + fmt(kirim) + "</b>\n"
                + "🔴 Chiqimlar (shu oy): <b>" + fmt(chiqim) + "</b>\n");

        if (n.getAmount() < 0 || k.getAmount() < 0) {
            sb.append("\n⚠️ <b>Balans manfiy — bu QARZ EMAS.</b>\n");
            if (boshl == 0)
                sb.append("Sabab: boshlang'ich qoldiq kiritilmagan — tizim 0 dan boshlab "
                        + "hisoblayapti, MoySklad chiqimlari esa ayirilyapti.\n"
                        + "Yechim: ⚙️ Настройка → 💼 Бошланғич қолдиқ → Buxgalteriya.\n");
        }

        if (!rasxodLines.isEmpty())
            sb.append("\n💸 <b>Nimalarga chiqim bo'ldi</b> (oxirgi ")
              .append(rasxodLines.size()).append(" ta):\n")
              .append(String.join("\n", rasxodLines)).append("\n");

        List<Debt> oweTo = debtRepo.findByDebtorTypeAndDebtorIdAndStatus(
                OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, DebtStatus.OCHIQ);
        List<Debt> oweFrom = debtRepo.findByCreditorTypeAndCreditorIdAndStatus(
                OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, DebtStatus.OCHIQ);
        sb.append("\n🧾 <b>Qarzlar registri bo'yicha:</b>\n");
        if (oweTo.isEmpty() && oweFrom.isEmpty())
            sb.append("Buxgalteriyaning hech kimga qarzi yo'q va hech kimdan haqi yo'q ✅");
        for (Debt d : oweTo)
            sb.append("🔴 KIMGA qarzdor: <b>")
              .append(esc(names.owner(d.getCreditorType(), d.getCreditorId())))
              .append("</b> — ").append(fmt(d.remain())).append(" so'm")
              .append(d.getReason() == null || d.getReason().isEmpty()
                      ? "" : " (" + esc(d.getReason()) + ")").append("\n");
        for (Debt d : oweFrom)
            sb.append("🟢 KIMDAN haqdor: <b>")
              .append(esc(names.owner(d.getDebtorType(), d.getDebtorId())))
              .append("</b> — ").append(fmt(d.remain())).append(" so'm")
              .append(d.getReason() == null || d.getReason().isEmpty()
                      ? "" : " (" + esc(d.getReason()) + ")").append("\n");

        sendContent(s, chatId, sb.toString(), null);
    }

    /** «⬅️ Orqaga» — bir pog'ona yuqoriga. */
    private void navBack(AppUser u, Session s, String nav, long chatId) {
        String lvl = nav.split(":")[0];
        switch (lvl) {
            case "panel" -> {
                s.data.remove("nav");
                deletePrevPanel(s, chatId);
                sender.send(chatId, "🏠 Bosh menyu",
                        Keyboards.buxMenu(c -> permSvc.visible(u, c)));
            }
            case "otdel", "sozlash", "stat" -> navTo(u, s, "panel", chatId,
                    panelTitle(u), panelLabels(u));
            case "kassa", "kassab" -> navTo(u, s, "otdel", chatId,
                    "🏬 <b>Отдел</b>\n\nKassani tanlang:", otdelLabels());
            case "davr" -> {
                long id = idOf(nav);
                navTo(u, s, "kassa:" + id, chatId,
                        "🏪 <b>" + esc(names.owner(OwnerType.KASSA, id)) + "</b>\n\nBo'limni tanlang:",
                        KASSA_MENU);
            }
            case "sozkassa", "sozuser" -> navTo(u, s, "sozlash", chatId, "⚙️ <b>Настройка</b>",
                    SOZLASH_MENU);
            case "roluser", "rolpick" -> navTo(u, s, "sozuser", chatId,
                    "👥 <b>Фойдаланувчилар</b>", SOZUSER_MENU);
            case "kassadel", "kassadelc" -> navTo(u, s, "sozkassa", chatId, "🏪 <b>Касса</b>",
                    List.of("➕ Касса қўшиш", "🚫 Касса ўчириш"));
            case "saldo", "svod" -> navTo(u, s, "stat", chatId, "📈 <b>Статистика</b>", statLabels(u));
            case "svoddavr", "svodotd" -> navTo(u, s, "svod", chatId,
                    "📊 <b>Свод</b>\n\nExcel turini tanlang:",
                    List.of("📗 Умумий Excel", "📘 Даврий Excel", "📙 Отдел Excel"));
            case "svodotdd" -> navTo(u, s, "svodotd", chatId,
                    "📙 <b>Отдел Excel</b>\n\nKassani tanlang:", kassaLabels());
            default -> {
                s.data.remove("nav");
                deletePrevPanel(s, chatId);
                sender.send(chatId, "🏠 Bosh menyu",
                        Keyboards.buxMenu(c -> permSvc.visible(u, c)));
            }
        }
    }

    /* ==================================================================
     * 👑 АДМИН ПАНЕЛ (sxema bo'yicha):
     *   АДМИН ПАНЕЛ -> Отдел | Настройка | Статистика
     *   Отдел -> Касса N -> Бугунги тушум (Касса/Click) | Расход | Давр танлаш
     *   Настройка -> Касса (қўшиш/ўчириш) | Фойдаланувчилар (қўшиш/ўчириш)
     *   Статистика -> Карзлар реестр | История | Фойдаланувчилар | Салдо | Свод
     *   Салдо -> Касса N (Click/Касса) ; Свод -> Умумий/Даврий/Отдел Excel
     * ================================================================== */

    private void panel(AppUser u, Session s, String arg, long chatId, int msgId) {
        String[] a = arg.split(":");
        // Buxgalter panel callbacklaridan faqat ko'rish + pul qabul qilishga ruxsatli
        if (u.getRole() == Role.BUXGALTER
                && !java.util.Set.of("qb", "qm", "kt", "kr", "kpp", "sdk", "kq", "bq").contains(a[0])) return;
        // Pul ko'rsatadigan sahifalar ochilganda avval MoySklad'dan yangilanadi
        switch (a[0]) {
            case "kt", "kr", "kpp", "sdk", "kq", "bq" -> syncService.syncIfStale(45);
            default -> { }
        }
        switch (a[0]) {
            case "main" -> panelMain(chatId, msgId);
            case "otd"  -> otdel(chatId, msgId);
            case "k"    -> kassaMenu(Long.parseLong(a[1]), chatId, msgId);
            case "kt"   -> kassaTushum(s, Long.parseLong(a[1]), chatId, msgId);
            case "kr"   -> kassaRasxodPanel(s, Long.parseLong(a[1]), chatId);
            case "kq"   -> kassaTopshirilmagan(s, Long.parseLong(a[1]), chatId, msgId);
            case "bq"   -> osnovnoyQoldiq(s, chatId);
            case "kd"   -> kassaDavr(Long.parseLong(a[1]), chatId, msgId);
            case "kpp"  -> kassaPeriodStats(s, Long.parseLong(a[1]), a[2], chatId, msgId);
            case "set"  -> settingsMenu(chatId, msgId);
            case "sk"   -> setKassa(chatId, msgId);
            case "skd"  -> kassaDeleteList(chatId, msgId);
            case "skx"  -> kassaDeleteConfirm(Long.parseLong(a[1]), chatId, msgId);
            case "sky"  -> kassaDeactivate(Long.parseLong(a[1]), chatId, msgId);
            case "sunew" -> { s.reset(); auStart(s, chatId); }
            case "sknew" -> {
                s.reset(); s.state = Session.State.ADM_AK_NAME;
                sender.edit(chatId, msgId, "🏪 <b>Yangi kassa</b>\n\nKassa nomini kiriting:");
            }
            case "su"   -> setUsers(chatId, msgId);
            case "st"   -> statMenu(chatId, msgId);
            case "dbt"  -> bux.debtsRegistry(chatId);
            case "his"  -> bux.historyMenu(chatId);
            case "usr"  -> listUsers(u, chatId);
            case "sd"   -> { syncService.syncIfStale(45); saldoKassa(s, "B", chatId, msgId); }
            case "ck"   -> clickMenu(u, s, chatId, msgId);
            case "sdk"  -> saldoKassa(s, a[1], chatId, msgId);
            case "sv"   -> svodMenu(chatId, msgId);
            case "xe"   -> excelFlow(a, chatId, msgId);
            case "qb"   -> qbStart(s, Long.parseLong(a[1]), chatId);
            case "qm"   -> qbMoney(s, Long.parseLong(a[1]), a[2], chatId, msgId);
        }
    }

    private InlineKeyboardButton bk(String data) { return btn("⬅️ Orqaga", data); }

    /** 💰 Баланс ko'rinishlari orasida almashish tugmalari. */
    private InlineKeyboardMarkup balansKb() {
        return inline(List.of(irow(
                btn("💵 Нақд", "a:bl:n"),
                btn("📲 Клик", "a:bl:k"),
                btn("💰 Жами", "a:bl:j"))));
    }

    private void show(long chatId, int msgId, String text,
                      List<List<InlineKeyboardButton>> rows) {
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sender.send(chatId, text, null);   // menu-rejimda inline tugmalarsiz
    }

    /* ---------- daraja 1 ---------- */

    private void panelMain(long chatId, int msgId) {
        show(chatId, msgId, "👑 <b>АДМИН ПАНЕЛ</b>\n\nBo'limni tanlang:", List.of(
                irow(btn("🏬 Отдел", "a:p:otd")),
                irow(btn("⚙️ Настройка", "a:p:set")),
                irow(btn("📈 Статистика", "a:p:st"))));
    }

    /* ---------- 🏬 ОТДЕЛ ---------- */

    private void otdel(long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn(OSN_LABEL, "a:p:bq")));
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn("🏪 " + k.getName(), "a:p:k:" + k.getId())));
        rows.add(irow(bk("a:p:main")));
        show(chatId, msgId, "🏬 <b>Отдел</b>\n\nKassani tanlang:", rows);
    }

    private void kassaMenu(long id, long chatId, int msgId) {
        String name = names.owner(OwnerType.KASSA, id);
        show(chatId, msgId, "🏪 <b>" + esc(name) + "</b>\n\nBo'limni tanlang:", List.of(
                irow(btn("💰 Бугунги тушум", "a:p:kt:" + id)),
                irow(btn("💸 Расход", "a:p:kr:" + id)),
                irow(btn("💵 Топширилмаган пул", "a:p:kq:" + id)),
                irow(btn("📆 Давр танлаш", "a:p:kd:" + id)),
                irow(bk("a:p:otd"))));
    }

    /** Бугунги тушум: Касса (naqd) va Click bo'lib ko'rsatiladi. */
    private void kassaTushum(Session s, long id, long chatId, int msgId) {
        DayRecord d = dayRepo.findByKassaIdAndDate(id, ledger.today()).orElse(null);
        long n = d == null ? 0 : d.getPrixodNaqd();
        long k = d == null ? 0 : d.getPrixodKlik();
        long t = d == null ? 0 : d.getPrixodTerminal();
        String text = "💰 <b>Бугунги тушум</b> — "
                + esc(names.owner(OwnerType.KASSA, id)) + "\n📅 " + ledger.today().format(DF) + "\n\n"
                + "💵 Касса (нақд): <b>" + fmt(n) + "</b> so'm\n"
                + "📲 Click: <b>" + fmt(k) + "</b> so'm\n"
                + "💳 Terminal: <b>" + fmt(t) + "</b> so'm\n"
                + "➕ <b>Жами: " + fmt(n + k + t) + "</b> so'm";
        InlineKeyboardMarkup qb = inline(List.of(
                irow(btn("💰 Пулларни қабул қилиш", "a:p:qb:" + id))));
        if (msgId > 0) sender.edit(chatId, msgId, text, qb);
        else sendContent(s, chatId, text, qb);
    }

    /**
     * 💵 Топширилмаган пул — kassaning buxgalteriyaga hali topshirilmagan puli:
     * joriy qo'ldagi qoldiq (naqd/click) + topshirilmagan yopilgan kunlar ro'yxati.
     */
    private void kassaTopshirilmagan(Session s, long id, long chatId, int msgId) {
        Balance n = ledger.view(OwnerType.KASSA, id, MoneyType.NAQD);
        Balance k = ledger.view(OwnerType.KASSA, id, MoneyType.KLIK);
        List<DayRecord> days = submissionService.submittableDays(id);

        StringBuilder sb = new StringBuilder("💵 <b>Топширилмаган пул</b> — "
                + esc(names.owner(OwnerType.KASSA, id)) + "\n\n"
                + "Қўлдаги қолдиқ (буxгалтерияга топширилмаган):\n"
                + "💵 Нақд: <b>" + fmt(n.getAmount()) + "</b> so'm"
                + (n.getReserved() > 0 ? " (банд: " + fmt(n.getReserved()) + ")" : "") + "\n"
                + "📲 Click: <b>" + fmt(k.getAmount()) + "</b> so'm"
                + (k.getReserved() > 0 ? " (банд: " + fmt(k.getReserved()) + ")" : "") + "\n"
                + "➕ <b>Жами: " + fmt(n.getAmount() + k.getAmount()) + "</b> so'm\n");

        if (days.isEmpty()) {
            sb.append("\n✅ Топширилмаган ёпилган кун йўқ");
        } else {
            long dn = 0, dk = 0;
            sb.append("\n⏳ Топширилмаган ёпилган кунлар: <b>").append(days.size()).append("</b> та\n");
            for (DayRecord d : days) {
                dn += d.remainNaqd(); dk += d.remainKlik();
                sb.append("• ").append(d.getDate().format(DF))
                  .append(" — Нақд ").append(fmt(d.remainNaqd()))
                  .append(" · Click ").append(fmt(d.remainKlik())).append("\n");
            }
            sb.append("Кунлар жами: Нақд <b>").append(fmt(dn))
              .append("</b> · Click <b>").append(fmt(dk)).append("</b> so'm");
        }
        InlineKeyboardMarkup qb = inline(List.of(
                irow(btn("💰 Пулларни қабул қилиш", "a:p:qb:" + id))));
        if (msgId > 0) sender.edit(chatId, msgId, sb.toString(), qb);
        else sendContent(s, chatId, sb.toString(), qb);
    }

    /** 🏦 Основной отдел (Buxgalteriya) pul qoldig'i + kassalarda turgan topshirilmagan pul. */
    private void osnovnoyQoldiq(Session s, long chatId) {
        Balance n = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD);
        Balance k = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.KLIK);
        long kn = 0, kk = 0;
        for (Kassa kas : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            kn += ledger.view(OwnerType.KASSA, kas.getId(), MoneyType.NAQD).getAmount();
            kk += ledger.view(OwnerType.KASSA, kas.getId(), MoneyType.KLIK).getAmount();
        }
        String text = "🏦 <b>Отдел основной</b> (Буxгалтерия) — пул қолдиғи\n\n"
                + "💵 Нақд: <b>" + fmt(n.getAmount()) + "</b> so'm"
                + (n.getReserved() > 0 ? " (банд: " + fmt(n.getReserved()) + ")" : "") + "\n"
                + "📲 Click: <b>" + fmt(k.getAmount()) + "</b> so'm"
                + (k.getReserved() > 0 ? " (банд: " + fmt(k.getReserved()) + ")" : "") + "\n"
                + "➕ <b>Жами: " + fmt(n.getAmount() + k.getAmount()) + "</b> so'm\n\n"
                + "🏪 Кассаларда (ҳали топширилмаган):\n"
                + "💵 Нақд: <b>" + fmt(kn) + "</b> · 📲 Click: <b>" + fmt(kk) + "</b>\n"
                + "➕ <b>Жами: " + fmt(kn + kk) + "</b> so'm";
        sendContent(s, chatId, text, null);
    }

    /** 💸 Расход — oxirgi 7 kunlik chiqimlar + kassa nomidan rasxod kiritish tugmasi. */
    private void kassaRasxodPanel(Session s, long id, long chatId) {
        java.time.LocalDate to = ledger.today();
        java.time.LocalDate from = to.minusDays(6);
        StringBuilder sb = new StringBuilder("💸 <b>Расход</b> — "
                + esc(names.owner(OwnerType.KASSA, id)) + "\n📅 "
                + from.format(DF) + " — " + to.format(DF) + "\n");
        long rn = 0, rk = 0;
        int shown = 0;
        for (Operation o : opRepo.byPeriod(from, to)) {
            if (o.getType() != OpType.RASXOD || o.getStatus() != OpStatus.TASDIQLANGAN) continue;
            if (o.getFromOwnerType() != OwnerType.KASSA || !Long.valueOf(id).equals(o.getFromOwnerId())) continue;
            if (o.getMoneyType() == MoneyType.KLIK) rk += o.getAmount(); else rn += o.getAmount();
            if (shown++ >= 20) continue;
            sb.append("\n• ").append(o.getOpDate().format(DF)).append(" — <b>")
              .append(fmt(o.getAmount())).append("</b> so'm (")
              .append(mtLabel(o.getMoneyType())).append(")")
              .append(o.getComment() == null || o.getComment().isEmpty()
                      ? "" : " — " + esc(o.getComment()));
        }
        if (shown == 0) sb.append("\nBu davrda rasxod yo'q");
        sb.append("\n\n💵 Нақд: <b>").append(fmt(rn)).append("</b> · 📲 Клик: <b>")
          .append(fmt(rk)).append("</b>\n➕ <b>Жами: ").append(fmt(rn + rk)).append("</b> so'm");
        sendContent(s, chatId, sb.toString(), inline(List.of(
                irow(btn("➕ Rasxod kiritish (kassa nomidan)", "a:krx:s:" + id)))));
    }

    private void kassaDavr(long id, long chatId, int msgId) {
        show(chatId, msgId, "📆 <b>Давр танлаш</b> — "
                + esc(names.owner(OwnerType.KASSA, id)), List.of(
                irow(btn("Bugun", "a:p:kpp:" + id + ":t"), btn("Kecha", "a:p:kpp:" + id + ":y")),
                irow(btn("7 kun", "a:p:kpp:" + id + ":7"), btn("30 kun", "a:p:kpp:" + id + ":30"),
                     btn("Shu oy", "a:p:kpp:" + id + ":m")),
                irow(btn("🗓 Kalendar", "a:cal:o:k" + id)),
                irow(bk("a:p:k:" + id))));
    }

    private void kassaPeriodStats(Session s, long id, String code, long chatId, int msgId) {
        java.time.LocalDate[] p = periodOf(code);
        kassaPeriodRange(s, id, p[0], p[1], chatId, msgId);
    }

    private void kassaPeriodRange(Session s, long id, java.time.LocalDate from,
                                  java.time.LocalDate to, long chatId, int msgId) {
        long kn = 0, kk = 0, rn = 0, rk = 0;
        for (Operation o : opRepo.byPeriod(from, to)) {
            boolean in = o.getToOwnerType() == OwnerType.KASSA && Long.valueOf(id).equals(o.getToOwnerId());
            boolean out = o.getFromOwnerType() == OwnerType.KASSA && Long.valueOf(id).equals(o.getFromOwnerId());
            if (o.getType() == OpType.PRIXOD && in) {
                if (o.getMoneyType() == MoneyType.KLIK) kk += o.getAmount(); else kn += o.getAmount();
            }
            if (o.getType() == OpType.RASXOD && out) {
                if (o.getMoneyType() == MoneyType.KLIK) rk += o.getAmount(); else rn += o.getAmount();
            }
        }
        String text = "📆 <b>" + rangeLabel(from, to) + "</b> — "
                + esc(names.owner(OwnerType.KASSA, id)) + "\n\n"
                + "🟢 Тушум: 💵 <b>" + fmt(kn) + "</b> · 📲 <b>" + fmt(kk) + "</b>\n"
                + "🔴 Расход: 💵 <b>" + fmt(rn) + "</b> · 📲 <b>" + fmt(rk) + "</b>\n"
                + "➕ <b>Фарқ: " + fmt(kn + kk - rn - rk) + "</b> so'm";
        InlineKeyboardMarkup qb = inline(List.of(
                irow(btn("💰 Пулларни қабул қилиш", "a:p:qb:" + id))));
        if (msgId > 0) sender.edit(chatId, msgId, text, qb);
        else sendContent(s, chatId, text, qb);
    }

    /* ---------- 💰 ПУЛЛАРНИ ҚАБУЛ ҚИЛИШ ---------- */

    private void qbStart(Session s, long kassaId, long chatId) {
        sendContent(s, chatId, "💰 <b>Пулларни қабул қилиш</b> — "
                        + esc(names.owner(OwnerType.KASSA, kassaId)) + "\n\nPul turini tanlang:\n"
                        + "<i>📲 Klik qabul qilinmaydi — u kassaning o'z hisobida yig'iladi, "
                        + "hisoboti «📤 Hisobot topshirish» orqali yopiladi.</i>",
                inline(List.of(
                        irow(btn("💵 Нақд", "a:p:qm:" + kassaId + ":NAQD"),
                             btn("💳 Терминал", "a:p:qm:" + kassaId + ":TERMINAL")),
                        irow(btn("❌ Bekor", "cx")))));
    }

    private void qbMoney(Session s, long kassaId, String mt, long chatId, int msgId) {
        s.data.put("qbKassa", kassaId);
        s.data.put("qbMt", mt);
        s.state = Session.State.ADM_QB_SUM;
        sender.edit(chatId, msgId, "💰 " + esc(names.owner(OwnerType.KASSA, kassaId))
                + " — " + mtLabel(MoneyType.valueOf(mt))
                + "\n\n<b>Olingan summani kiriting</b> (so'm):");
    }

    private void qbSum(AppUser u, Session s, String text, long chatId) {
        long sum = parseAmount(text);
        if (sum <= 0) { sender.send(chatId, "⚠️ Musbat summa kiriting:"); return; }
        s.data.put("qbSum", sum);
        s.state = Session.State.IDLE;

        long kassaId = s.getLong("qbKassa");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        // Avval shu kassaning kassirlari, keyin qolgan faol foydalanuvchilar
        List<AppUser> own = userRepo.findByKassaIdAndActiveTrue(kassaId);
        for (AppUser x : own)
            rows.add(irow(btn("👤 " + x.getFullName(), "a:qbu:" + x.getId())));
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc()) {
            if (own.stream().anyMatch(o -> o.getId().equals(x.getId()))) continue;
            if (x.getId().equals(u.getId())) continue;
            if (rows.size() >= 12) break;
            rows.add(irow(btn(x.getFullName(), "a:qbu:" + x.getId())));
        }
        rows.add(irow(btn("❌ Bekor", "cx")));
        sendContent(s, chatId, "💰 Summa: <b>" + fmt(sum) + "</b> so'm ("
                + mtLabel(MoneyType.valueOf(s.getStr("qbMt"))) + ")\n\n"
                + "<b>Kim topshirdi?</b>", inline(rows));
    }

    /** Kim topshirgani tanlandi — endi QAYSI SANA uchun qabul qilinishi so'raladi. */
    private void qbUser(AppUser u, Session s, String arg, long chatId, int msgId) {
        if (s.data.get("qbSum") == null) return;
        String topshirgan = userRepo.findById(Long.parseLong(arg))
                .map(AppUser::getFullName).orElse("?");
        s.data.put("qbWho", topshirgan);
        sender.edit(chatId, msgId, "💰 Summa: <b>" + fmt(s.getLong("qbSum")) + "</b> so'm ("
                + mtLabel(MoneyType.valueOf(s.getStr("qbMt"))) + ")\n"
                + "👤 Topshirdi: <b>" + esc(topshirgan) + "</b>\n\n"
                + "📅 <b>Qaysi sana uchun qabul qilinsin?</b>", inline(List.of(
                irow(btn("📅 Bugun", "a:qbd:0"), btn("Kecha", "a:qbd:1")),
                irow(btn("🗓 Kalendar", "a:cal:o:q")),
                irow(btn("❌ Bekor", "cx")))));
    }

    private void qbDate(AppUser u, Session s, String arg, long chatId, int msgId) {
        if (s.data.get("qbWho") == null) return;
        qbCommit(u, s, ledger.today().minusDays(Long.parseLong(arg)), chatId, msgId);
    }

    private void qbCommit(AppUser u, Session s, java.time.LocalDate date, long chatId, int msgId) {
        if (s.data.get("qbSum") == null || s.data.get("qbWho") == null) return;
        long kassaId = s.getLong("qbKassa");
        MoneyType mt = MoneyType.valueOf(s.getStr("qbMt"));
        long sum = s.getLong("qbSum");
        String topshirgan = s.getStr("qbWho");
        String nav = s.getStr("nav");
        Object pm = s.data.get("panelMsg");
        s.reset();
        if (nav != null) s.data.put("nav", nav);   // panel navigatsiyasi saqlanadi
        if (pm != null) s.data.put("panelMsg", pm);
        s.data.put("contentMsg", msgId);           // tasdiq xabari — joriy kontent

        var op = submissionService.directCollect(kassaId, mt, sum, u, topshirgan, date);
        String kassaName = names.owner(OwnerType.KASSA, kassaId);
        sender.edit(chatId, msgId, "✅ <b>Pul qabul qilindi</b> #" + op.getId() + "\n\n"
                + "🏪 Kassa: <b>" + esc(kassaName) + "</b> → 🏦 Buxgalteriya\n"
                + "💰 Summa: <b>" + fmt(sum) + "</b> so'm (" + mtLabel(mt) + ")\n"
                + "📅 Sana: <b>" + date.format(DF) + "</b>\n"
                + "👤 Topshirdi: <b>" + esc(topshirgan) + "</b>\n"
                + "✍️ Qabul qildi: " + esc(u.getFullName())
                + (mt == MoneyType.TERMINAL
                    ? "\n\nℹ️ Terminal puli faqat jurnalga yozildi — u firma bank hisobida."
                    : "\n\nKassa balansidan yechildi, Buxgalteriyaga qo'shildi."));
        notify.toKassa(kassaId, "💰 Buxgalteriya kassangizdan pul qabul qildi: <b>"
                + fmt(sum) + "</b> so'm (" + mtLabel(mt) + ")\n📅 Sana: "
                + date.format(DF) + "\nTopshirdi: " + esc(topshirgan), null);
    }

    /* ---------- ⚙️ НАСТРОЙКА ---------- */

    private void settingsMenu(long chatId, int msgId) {
        show(chatId, msgId, "⚙️ <b>Настройка</b>", List.of(
                irow(btn("🏪 Касса", "a:p:sk")),
                irow(btn("👥 Фойдаланувчилар", "a:p:su")),
                irow(btn("🧾 Расходлар", "a:rxl"), btn("📋 Аудит", "a:audm")),
                irow(btn("🏷 Тугма номлари", "a:lbm"), btn("🔑 MoySklad API", "a:msk")),
                irow(btn("👁 Ҳуқуқлар", "a:prm")),
                irow(bk("a:p:main"))));
    }

    private void setKassa(long chatId, int msgId) {
        show(chatId, msgId, "🏪 <b>Касса</b>", List.of(
                irow(btn("➕ Касса қўшиш", "a:p:sknew")),
                irow(btn("🚫 Касса ўчириш", "a:p:skd")),
                irow(bk("a:p:set"))));
    }

    private void kassaDeleteList(long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn("🚫 " + k.getName(), "a:p:skx:" + k.getId())));
        rows.add(irow(bk("a:p:sk")));
        show(chatId, msgId, "🚫 <b>Касса ўчириш</b>\n\nQaysi kassani o'chirasiz?", rows);
    }

    private void kassaDeleteConfirm(long id, long chatId, int msgId) {
        show(chatId, msgId, "⚠️ <b>" + esc(names.owner(OwnerType.KASSA, id))
                + "</b> kassasi o'chirilsinmi?\n\n"
                + "Kassa faolsizlanadi — tarix saqlanadi, yangi operatsiyalar to'xtaydi.", List.of(
                irow(btn("✅ Ha, o'chirilsin", "a:p:sky:" + id), btn("❌ Yo'q", "a:p:skd"))));
    }

    private void kassaDeactivate(long id, long chatId, int msgId) {
        kassaRepo.findById(id).ifPresent(k -> { k.setActive(false); kassaRepo.save(k); });
        show(chatId, msgId, "🚫 Kassa faolsizlantirildi", List.of(irow(bk("a:p:sk"))));
    }

    private void setUsers(long chatId, int msgId) {
        show(chatId, msgId, "👥 <b>Фойдаланувчилар</b>", List.of(
                irow(btn("➕ Фойдаланувчи қўшиш", "a:p:sunew")),
                irow(btn("🚫 Фойдаланувчини ўчириш", "a:p:usr")),
                irow(bk("a:p:set"))));
    }

    /* ---------- 📈 СТАТИСТИКА ---------- */

    private void statMenu(long chatId, int msgId) {
        show(chatId, msgId, "📈 <b>Статистика</b>", List.of(
                irow(btn("🧾 Карзлар реестр", "a:p:dbt"), btn("📜 История", "a:p:his")),
                irow(btn("👥 Фойдаланувчилар умумий", "a:p:usr")),
                irow(btn("💼 Салдо", "a:p:sd"), btn("📊 Свод", "a:p:sv")),
                irow(btn("📲 Кликлар", "a:p:ck")),
                irow(bk("a:p:main"))));
    }

    /** Салдо — faqat Основной отдел (buxgalteriya) qoldig'i. */
    private void saldoKassa(Session s, String who, long chatId, int msgId) {
        OwnerType ot = who.equals("B") ? OwnerType.BUXGALTERIYA : OwnerType.KASSA;
        Long id = who.equals("B") ? LedgerService.BUX_ID : Long.parseLong(who);
        var n = ledger.view(ot, id, MoneyType.NAQD);
        var k = ledger.view(ot, id, MoneyType.KLIK);
        String name = ot == OwnerType.BUXGALTERIYA ? "Основной отдел" : names.owner(ot, id);
        String text = "💼 <b>Салдо</b> — " + esc(name) + "\n\n"
                + "💵 Касса (нақд): <b>" + fmt(n.getAmount()) + "</b> so'm"
                + (n.getReserved() > 0 ? " (band " + fmt(n.getReserved()) + ")" : "") + "\n"
                + "📲 Click: <b>" + fmt(k.getAmount()) + "</b> so'm"
                + (k.getReserved() > 0 ? " (band " + fmt(k.getReserved()) + ")" : "") + "\n"
                + "➕ <b>Жами: " + fmt(n.getAmount() + k.getAmount()) + "</b> so'm";
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(List.of(irow(bk("a:p:st")))));
        else sendContent(s, chatId, text, null);
    }

    /* ---------- 📲 КЛИКЛАР (alohida Click hisoblari) ---------- */

    private void clickMenu(AppUser u, Session s, long chatId, int msgId) {
        StringBuilder sb = new StringBuilder("📲 <b>Кликлар</b>\n\n");
        long total = 0;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
            var b = ledger.view(OwnerType.CLICK, c.getId(), MoneyType.KLIK);
            total += b.getAmount();
            sb.append("• <b>").append(esc(c.getName())).append("</b>: ")
              .append(fmt(b.getAmount())).append(" so'm\n");
            if (u.getRole() == Role.SUPERADMIN)
                rows.add(irow(btn("💼 " + c.getName(), "a:ckq:" + c.getId())));
        }
        sb.append("\n➕ <b>Жами: ").append(fmt(total)).append("</b> so'm");
        if (u.getRole() == Role.SUPERADMIN)
            sb.append("\n\nQoldiq kiritish uchun hisobni tanlang:");
        if (msgId > 0) show(chatId, msgId, sb.toString(), rows);
        else sendContent(s, chatId, sb.toString(), rows.isEmpty() ? null : inline(rows));
    }

    private void ckStart(Session s, String arg, long chatId, int msgId) {
        long id = Long.parseLong(arg);
        s.data.put("ckId", id);
        s.state = Session.State.ADM_CK_SUM;
        sender.edit(chatId, msgId, "📲 <b>" + esc(names.owner(OwnerType.CLICK, id))
                + "</b>\n\n<b>Boshlang'ich qoldiqni kiriting</b> (so'm):");
    }

    private void ckSum(Session s, String text, long chatId) {
        long sum = parseAmount(text);
        if (sum <= 0) { sender.send(chatId, "⚠️ Musbat summa kiriting:"); return; }
        s.data.put("ckSum", sum);
        s.state = Session.State.ADM_CK_SANA;
        java.time.LocalDate now = java.time.LocalDate.now();
        sender.send(chatId, "📅 <b>Qaysi sanaga kiritilsin?</b>\n\n"
                        + "Tugmani bosing yoki eskiroq sanani o'zingiz yozing (masalan <code>"
                        + now.minusDays(10).format(DF) + "</code>):",
                inline(List.of(
                        irow(btn("📅 Bugun", "a:ckd:0"), btn("Kecha", "a:ckd:1")),
                        irow(btn(now.minusDays(2).format(DF), "a:ckd:2"),
                             btn(now.minusDays(3).format(DF), "a:ckd:3"),
                             btn(now.minusDays(4).format(DF), "a:ckd:4")),
                        irow(btn("🗓 Kalendar", "a:cal:o:ck")),
                        irow(btn("❌ Bekor", "cx")))));
    }

    private void ckSanaBtn(AppUser u, Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_CK_SANA) return;
        java.time.LocalDate d = java.time.LocalDate.now().minusDays(Long.parseLong(arg));
        sender.edit(chatId, msgId, "📅 Sana: <b>" + d.format(DF) + "</b>");
        ckCommit(u, s, d, chatId);
    }

    private void ckSana(AppUser u, Session s, String text, long chatId) {
        java.time.LocalDate d;
        try { d = java.time.LocalDate.parse(text.trim(), DF); }
        catch (Exception e) {
            try { d = java.time.LocalDate.parse(text.trim()); }
            catch (Exception e2) {
                sender.send(chatId, "⚠️ Sana formati: <code>kun.oy.yil</code> — masalan <code>"
                        + java.time.LocalDate.now().format(DF) + "</code>");
                return;
            }
        }
        if (d.isAfter(java.time.LocalDate.now())) {
            sender.send(chatId, "⚠️ Kelajak sanasi bo'lmaydi. Qaytadan kiriting:");
            return;
        }
        ckCommit(u, s, d, chatId);
    }

    private void ckCommit(AppUser u, Session s, java.time.LocalDate date, long chatId) {
        long id = s.getLong("ckId");
        long sum = s.getLong("ckSum");
        String nav = s.getStr("nav");
        Object pm = s.data.get("panelMsg");
        s.reset();
        if (nav != null) s.data.put("nav", nav);
        if (pm != null) s.data.put("panelMsg", pm);

        ledger.postAdjustment(OpType.BOSHLANGICH, OwnerType.CLICK, id, MoneyType.KLIK,
                sum, "Boshlang'ich qoldiq", u.getId(), date);
        var b = ledger.view(OwnerType.CLICK, id, MoneyType.KLIK);
        sender.send(chatId, "✅ <b>" + esc(names.owner(OwnerType.CLICK, id))
                + "</b> — qoldiq kiritildi\n"
                + "📅 Sana: <b>" + date.format(DF) + "</b>\n"
                + "📲 Yangi balans: <b>" + fmt(b.getAmount()) + "</b> so'm");
    }

    /* ---------- 📊 СВОД (Excel) ---------- */

    private void svodMenu(long chatId, int msgId) {
        show(chatId, msgId, "📊 <b>Свод</b>\n\nExcel turini tanlang:", List.of(
                irow(btn("📗 Умумий Excel (шу ой)", "a:p:xe:all")),
                irow(btn("📘 Даврий Excel", "a:p:xe:per")),
                irow(btn("📙 Отдел Excel", "a:p:xe:otd")),
                irow(bk("a:p:st"))));
    }

    /** a = [xe, tur, ...]: all | per | perc:<code> | otd | otdk:<id> | otdp:<id>:<code> */
    private void excelFlow(String[] a, long chatId, int msgId) {
        switch (a[1]) {
            case "all" -> genExcel(chatId, msgId, "m", null);
            case "per" -> show(chatId, msgId, "📘 <b>Даврий Excel</b>\n\nDavrni tanlang:", List.of(
                    irow(btn("Bugun", "a:p:xe:perc:t"), btn("Kecha", "a:p:xe:perc:y")),
                    irow(btn("7 kun", "a:p:xe:perc:7"), btn("30 kun", "a:p:xe:perc:30"),
                         btn("Shu oy", "a:p:xe:perc:m")),
                    irow(btn("🗓 Kalendar", "a:cal:o:x")),
                    irow(bk("a:p:sv"))));
            case "perc" -> genExcel(chatId, msgId, a[2], null);
            case "otd" -> {
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
                    rows.add(irow(btn("🏪 " + k.getName(), "a:p:xe:otdk:" + k.getId())));
                rows.add(irow(bk("a:p:sv")));
                show(chatId, msgId, "📙 <b>Отдел Excel</b>\n\nKassani tanlang:", rows);
            }
            case "otdk" -> show(chatId, msgId, "📙 <b>Отдел Excel</b> — "
                    + esc(names.owner(OwnerType.KASSA, Long.parseLong(a[2])))
                    + "\n\nDavrni tanlang:", List.of(
                    irow(btn("Bugun", "a:p:xe:otdp:" + a[2] + ":t"),
                         btn("7 kun", "a:p:xe:otdp:" + a[2] + ":7")),
                    irow(btn("30 kun", "a:p:xe:otdp:" + a[2] + ":30"),
                         btn("Shu oy", "a:p:xe:otdp:" + a[2] + ":m")),
                    irow(btn("🗓 Kalendar", "a:cal:o:xo" + a[2])),
                    irow(bk("a:p:xe:otd"))));
            case "otdp" -> genExcel(chatId, msgId, a[3], Long.parseLong(a[2]));
        }
    }

    private void genExcel(long chatId, int msgId, String code, Long kassaId) {
        java.time.LocalDate[] p = periodOf(code);
        genExcelRange(chatId, msgId, p[0], p[1], kassaId);
    }

    private void genExcelRange(long chatId, int msgId, java.time.LocalDate from,
                               java.time.LocalDate to, Long kassaId) {
        Kassa only = kassaId == null ? null : kassaRepo.findById(kassaId).orElse(null);
        String label = (only == null ? "Умумий" : only.getName()) + " · " + rangeLabel(from, to);
        sender.edit(chatId, msgId, "⏳ Excel tayyorlanmoqda: <b>" + esc(label)
                + "</b>\nMoySklad so'ralmoqda, biroz kuting…");
        Kassa fOnly = only;
        new Thread(() -> {
            try {
                byte[] xlsx = excelReport.build(from, to, fOnly);
                sender.sendDocument(chatId, xlsx,
                        "hisobot_" + (fOnly == null ? "umumiy" : "kassa" + fOnly.getId())
                                + "_" + from + "_" + to + ".xlsx",
                        "📊 Excel: <b>" + esc(label) + "</b>");
            } catch (Exception e) {
                sender.send(chatId, "⚠️ Excel xatosi: " + esc(e.getMessage()));
            }
        }).start();
    }

    /* ---------- 💰 БУГУНГИ ТУШУМ (barcha kassalar) ---------- */

    private void tushumAll(Session s, long chatId) {
        syncService.syncIfStale(45);   // so'ralganda oxirgi ma'lumot kelsin
        StringBuilder sb = new StringBuilder("💰 <b>БУГУНГИ ТУШУМ</b>\n📅 "
                + ledger.today().format(DF) + "\n");
        long tn = 0, tk = 0, tt = 0;
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            DayRecord d = dayRepo.findByKassaIdAndDate(k.getId(), ledger.today()).orElse(null);
            long n = d == null ? 0 : d.getPrixodNaqd();
            long kl = d == null ? 0 : d.getPrixodKlik();
            long t = d == null ? 0 : d.getPrixodTerminal();
            tn += n; tk += kl; tt += t;
            sb.append("\n<b>").append(esc(k.getName())).append("</b> — ")
              .append(fmt(n + kl + t)).append(" so'm\n")
              .append("  💵 ").append(fmt(n)).append(" · 📲 ").append(fmt(kl))
              .append(" · 💳 ").append(fmt(t)).append("\n");
        }
        sb.append("\n➕ <b>ЖАМИ: ").append(fmt(tn + tk + tt)).append("</b> so'm")
          .append("\n  💵 Нақд: ").append(fmt(tn))
          .append(" · 📲 Click: ").append(fmt(tk))
          .append(" · 💳 Terminal: ").append(fmt(tt));
        sendContent(s, chatId, sb.toString(), null);
    }

    /* ==================================================================
     * 🗓 KALENDAR — davr (bir yoki bir necha kun) tanlash.
     * ctx: "k<id>" — kassa davr statistikasi, "x" — umumiy Excel,
     *      "xo<id>" — otdel Excel. Birinchi bosish — boshlanish,
     *      ikkinchi bosish — tugash sanasi (bitta kun uchun ikki marta o'sha kun).
     * ================================================================== */

    private static final String[] OYLAR = {"Yanvar", "Fevral", "Mart", "Aprel", "May", "Iyun",
            "Iyul", "Avgust", "Sentabr", "Oktabr", "Noyabr", "Dekabr"};

    private void calOpen(Session s, long chatId, int msgId, String ctx) {
        s.data.remove("calFrom");
        s.data.put("calCtx", ctx);
        calShow(s, chatId, msgId, ctx, java.time.YearMonth.from(ledger.today()));
    }

    /** Bir-sanali kontekstlar: q — pul qabul, ib — boshlang'ich qoldiq, ck — Click qoldiq,
     *  kr — korrektirovka sanasi, krx — kassa nomidan rasxod sanasi. */
    private boolean calSingle(String ctx) {
        return ctx.equals("q") || ctx.equals("ib") || ctx.equals("ck")
                || ctx.equals("kr") || ctx.equals("krx");
    }

    private void calShow(Session s, long chatId, int msgId, String ctx, java.time.YearMonth ym) {
        calShow(s, chatId, msgId, ctx, ym, null);
    }

    private void calShow(Session s, long chatId, int msgId, String ctx,
                         java.time.YearMonth ym, String warn) {
        String fromStr = s.getStr("calFrom");
        String body = calSingle(ctx)
                ? "📅 <b>Sanani tanlang:</b>"
                : (fromStr == null
                    ? "📍 <b>Boshlanish</b> sanasini tanlang:"
                    : "📍 Boshlanish: <b>" + java.time.LocalDate.parse(fromStr).format(DF)
                      + "</b>\n🏁 Endi <b>tugash</b> sanasini tanlang\n"
                      + "(bitta kun uchun o'sha kunni yana bosing)");
        String title = "🗓 <b>Kalendar</b>\n\n" + (warn == null ? "" : warn + "\n") + body;
        InlineKeyboardMarkup kb = calKb(ym, ctx,
                fromStr == null ? null : java.time.LocalDate.parse(fromStr));
        if (msgId > 0) sender.edit(chatId, msgId, title, kb);
        else sendContent(s, chatId, title, kb);
    }

    private InlineKeyboardMarkup calKb(java.time.YearMonth ym, String ctx, java.time.LocalDate sel) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(
                btn("‹", "a:cal:n:" + ctx + ":" + ym.minusMonths(1)),
                btn(OYLAR[ym.getMonthValue() - 1] + " " + ym.getYear(), "a:cal:z"),
                btn("›", "a:cal:n:" + ctx + ":" + ym.plusMonths(1))));
        rows.add(irow(btn("Du", "a:cal:z"), btn("Se", "a:cal:z"), btn("Ch", "a:cal:z"),
                btn("Pa", "a:cal:z"), btn("Ju", "a:cal:z"), btn("Sh", "a:cal:z"), btn("Ya", "a:cal:z")));
        java.time.LocalDate today = ledger.today();
        int shift = ym.atDay(1).getDayOfWeek().getValue() - 1;   // Dushanba = 0
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (int i = 0; i < shift; i++) row.add(btn("⠀", "a:cal:z"));
        for (int day = 1; day <= ym.lengthOfMonth(); day++) {
            java.time.LocalDate d = ym.atDay(day);
            String label = d.equals(sel) ? "✅" + day
                    : d.equals(today) ? "·" + day + "·" : String.valueOf(day);
            row.add(btn(label, "a:cal:d:" + ctx + ":" + d.toEpochDay()));
            if (row.size() == 7) { rows.add(row); row = new ArrayList<>(); }
        }
        if (!row.isEmpty()) {
            while (row.size() < 7) row.add(btn("⠀", "a:cal:z"));
            rows.add(row);
        }
        rows.add(irow(btn("❌ Yopish", "a:cal:c")));
        return inline(rows);
    }

    /** a:cal:<op>... — z: bo'sh joy, c: yopish, o: ochish, n: oy almashtirish, d: kun. */
    private void calCb(AppUser u, Session s, String arg, long chatId, int msgId) {
        if (arg.equals("z")) return;
        if (arg.equals("c")) {
            s.data.remove("calFrom");
            s.data.remove("calCtx");
            sender.deleteMessage(chatId, msgId);
            return;
        }
        String[] a = arg.split(":");
        String ctx = a[1];
        switch (a[0]) {
            case "o" -> {
                s.data.remove("calFrom");
                s.data.put("calCtx", ctx);
                calShow(s, chatId, msgId, ctx, java.time.YearMonth.from(ledger.today()));
            }
            case "n" -> calShow(s, chatId, msgId, ctx, java.time.YearMonth.parse(a[2]));
            case "d" -> {
                java.time.LocalDate d = java.time.LocalDate.ofEpochDay(Long.parseLong(a[2]));

                // BIR-SANALI rejim (pul qabul / qoldiq kiritish): bitta bosish yetadi
                if (calSingle(ctx)) {
                    if (d.isAfter(ledger.today())) {
                        calShow(s, chatId, msgId, ctx, java.time.YearMonth.from(d),
                                "⚠️ Kelajak sanasi bo'lmaydi.");
                        return;
                    }
                    s.data.remove("calFrom");
                    s.data.remove("calCtx");
                    switch (ctx) {
                        case "q" -> qbCommit(u, s, d, chatId, msgId);
                        case "ib" -> {
                            if (s.state != Session.State.ADM_IB_SANA) return;
                            sender.edit(chatId, msgId, "📅 Sana: <b>" + d.format(DF) + "</b>");
                            ibCommit(u, s, d, chatId);
                        }
                        case "ck" -> {
                            if (s.state != Session.State.ADM_CK_SANA) return;
                            sender.edit(chatId, msgId, "📅 Sana: <b>" + d.format(DF) + "</b>");
                            ckCommit(u, s, d, chatId);
                        }
                        case "kr" -> {
                            if (s.state != Session.State.ADM_KR_SANA) return;
                            krSanaChosen(s, d, chatId, msgId);
                        }
                        case "krx" -> {
                            if (s.data.get("krxKassa") == null) return;
                            krxDateChosen(s, d, chatId, msgId);
                        }
                    }
                    return;
                }

                // DIAPAZON rejimi (hisobot/Excel): ikki bosish — boshlanish va tugash
                String fromStr = s.getStr("calFrom");
                if (fromStr == null) {
                    s.data.put("calFrom", d.toString());
                    calShow(s, chatId, msgId, ctx, java.time.YearMonth.from(d));
                    return;
                }
                java.time.LocalDate f = java.time.LocalDate.parse(fromStr);
                s.data.remove("calFrom");
                s.data.remove("calCtx");
                java.time.LocalDate from = f.isBefore(d) ? f : d;
                java.time.LocalDate to = f.isBefore(d) ? d : f;
                if (ctx.equals("x")) genExcelRange(chatId, msgId, from, to, null);
                else if (ctx.startsWith("xo"))
                    genExcelRange(chatId, msgId, from, to, Long.parseLong(ctx.substring(2)));
                else if (ctx.startsWith("k")) {
                    syncService.syncIfStale(45);
                    kassaPeriodRange(s, Long.parseLong(ctx.substring(1)), from, to, chatId, msgId);
                }
            }
        }
    }

    /* ==================================================================
     * 🧾 РАСХОДЛАР — tasdiqlangan rasxodni BEKOR qilish (summa qaytadi)
     * yoki summasini TAHRIRLASH. Faqat bot orqali qilinganlar.
     * ================================================================== */

    private void rxList(Session s, long chatId, int msgId) {
        List<Operation> ops = opRepo.findTop10ByTypeAndStatusAndMoyskladIdIsNullOrderByIdDesc(
                OpType.RASXOD, OpStatus.TASDIQLANGAN);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Operation o : ops)
            rows.add(irow(btn("#" + o.getId() + " · " + fmt(o.getAmount()) + " so'm · "
                    + names.owner(o.getFromOwnerType(), o.getFromOwnerId())
                    + " · " + o.getOpDate().format(DF), "a:rxc:" + o.getId())));
        String text = "🧾 <b>Расходлар</b>\n\n" + (ops.isEmpty()
                ? "Tasdiqlangan (bot orqali) rasxodlar yo'q."
                : "Bekor qilish yoki tahrirlash uchun tanlang (oxirgi " + ops.size() + " ta).\n"
                  + "ℹ️ MoySklad rasxodlari bu ro'yxatga kirmaydi — ular MoySkladda o'zgartiriladi.");
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sendContent(s, chatId, text, rows.isEmpty() ? null : inline(rows));
    }

    private void rxCard(long opId, long chatId, int msgId) {
        Operation o = opRepo.findById(opId).orElse(null);
        if (o == null) { sender.edit(chatId, msgId, "⚠️ Rasxod topilmadi"); return; }
        String creator = o.getCreatedBy() == null ? "—"
                : userRepo.findById(o.getCreatedBy()).map(AppUser::getFullName).orElse("—");
        String approver = o.getDecidedBy() == null ? "—"
                : userRepo.findById(o.getDecidedBy()).map(AppUser::getFullName).orElse("—");
        String text = "🧾 <b>Rasxod #" + o.getId() + "</b>"
                + (o.getStatus() != OpStatus.TASDIQLANGAN ? " (holati: " + o.getStatus() + ")" : "") + "\n\n"
                + "🏪 Kimdan: <b>" + esc(names.owner(o.getFromOwnerType(), o.getFromOwnerId())) + "</b>\n"
                + "💰 Summa: <b>" + fmt(o.getAmount()) + "</b> so'm (" + mtLabel(o.getMoneyType()) + ")\n"
                + "📅 Sana: " + o.getOpDate().format(DF) + "\n"
                + "✍️ So'ragan: " + esc(creator) + " · Tasdiqlagan: " + esc(approver)
                + (o.getComment() == null || o.getComment().isEmpty()
                    ? "" : "\n💬 " + esc(o.getComment()));
        sender.edit(chatId, msgId, text, inline(List.of(
                irow(btn("❌ Бекор қилиш", "a:rxx:" + o.getId()),
                     btn("✏️ Суммани ўзгартириш", "a:rxe:" + o.getId())),
                irow(bk("a:rxl")))));
    }

    private void rxConfirm(long opId, long chatId, int msgId) {
        Operation o = opRepo.findById(opId).orElse(null);
        if (o == null) return;
        sender.edit(chatId, msgId, "⚠️ Rasxod #" + o.getId() + " — <b>" + fmt(o.getAmount())
                + "</b> so'm (" + esc(names.owner(o.getFromOwnerType(), o.getFromOwnerId()))
                + ") <b>bekor qilinsinmi?</b>\n\nSumma balansga QAYTARILADI, "
                + "so'ragan foydalanuvchiga xabar boradi.", inline(List.of(
                irow(btn("✅ Ҳа, бекор қилинсин", "a:rxy:" + opId)),
                irow(bk("a:rxc:" + opId)))));
    }

    private void rxCancel(AppUser u, long opId, long chatId, int msgId) {
        Operation op = rasxodService.cancelApproved(opId, u);
        sender.edit(chatId, msgId, "✅ Rasxod #" + op.getId() + " <b>bekor qilindi</b>\n\n"
                + "💰 <b>" + fmt(op.getAmount()) + "</b> so'm ("
                + mtLabel(op.getMoneyType()) + ") balansga qaytarildi — "
                + esc(names.owner(op.getFromOwnerType(), op.getFromOwnerId())));
        rxNotify(op, u, "❌ Rasxod #" + op.getId() + " (<b>" + fmt(op.getAmount())
                + "</b> so'm, " + mtLabel(op.getMoneyType())
                + ") SuperAdmin tomonidan <b>bekor qilindi</b> — summa balansga qaytarildi.");
    }

    private void rxEditStart(Session s, long opId, long chatId, int msgId) {
        Operation o = opRepo.findById(opId).orElse(null);
        if (o == null) return;
        s.state = Session.State.ADM_RXE_SUM;
        s.data.put("rxeId", opId);
        sender.edit(chatId, msgId, "✏️ Rasxod #" + o.getId() + " — hozirgi summa: <b>"
                + fmt(o.getAmount()) + "</b> so'm\n\n<b>Yangi summani kiriting</b> (so'm):");
    }

    private void rxEditSum(AppUser u, Session s, String text, long chatId) {
        long sum = parseAmount(text);
        if (sum <= 0) { sender.send(chatId, "⚠️ Musbat summa kiriting:"); return; }
        long opId = s.getLong("rxeId");
        s.state = Session.State.IDLE;
        s.data.remove("rxeId");
        Operation before = opRepo.findById(opId).orElse(null);
        long old = before == null ? 0 : before.getAmount();
        Operation op = rasxodService.editApprovedAmount(opId, sum, u);
        sender.send(chatId, "✅ Rasxod #" + op.getId() + " summasi o'zgartirildi: <b>"
                + fmt(old) + "</b> → <b>" + fmt(sum) + "</b> so'm\nFarq balansga qo'llandi.");
        rxNotify(op, u, "✏️ Rasxod #" + op.getId() + " summasi SuperAdmin tomonidan "
                + "o'zgartirildi: <b>" + fmt(old) + "</b> → <b>" + fmt(sum) + "</b> so'm.");
    }

    /** Rasxod o'zgarishi haqida: so'ragan user + kassa + buxgalteriya xabardor qilinadi. */
    private void rxNotify(Operation op, AppUser by, String text) {
        if (op.getCreatedBy() != null)
            userRepo.findById(op.getCreatedBy()).ifPresent(x -> {
                if (x.getTelegramId() != null && !x.getId().equals(by.getId()))
                    notify.toUser(x.getTelegramId(), text);
            });
        if (op.getFromOwnerType() == OwnerType.KASSA) notify.toKassa(op.getFromOwnerId(), text, null);
        notify.toBuxgalteriya(text + "\n✍️ " + esc(by.getFullName()), null);
    }

    /* ==================================================================
     * 📋 АУДИТ — har bir foydalanuvchi qilgan amallar jurnali + Excel.
     * ================================================================== */

    private static final java.time.format.DateTimeFormatter AUDIT_DF =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private void auditMenu(Session s, long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<AppUser> users = userRepo.findByActiveTrueOrderByRoleAscIdAsc();
        for (int i = 0; i < users.size(); i += 2) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(btn("👤 " + users.get(i).getFullName(), "a:aud:" + users.get(i).getId()));
            if (i + 1 < users.size())
                r.add(btn("👤 " + users.get(i + 1).getFullName(), "a:aud:" + users.get(i + 1).getId()));
            rows.add(r);
        }
        rows.add(irow(btn("📄 Ҳаммаси (oxirgi 15)", "a:aud:0")));
        rows.add(irow(btn("📥 Excel (to'liq jurnal)", "a:aux:0")));
        String text = "📋 <b>Аудит</b>\n\nKimning amallarini ko'rasiz?";
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sendContent(s, chatId, text, inline(rows));
    }

    private void auditView(Session s, long userId, long chatId, int msgId) {
        List<AuditLog> logs = userId == 0
                ? auditRepo.findTop15ByOrderByIdDesc()
                : auditRepo.findTop15ByUserIdOrderByIdDesc(userId);
        String who = userId == 0 ? "Ҳаммаси"
                : userRepo.findById(userId).map(AppUser::getFullName).orElse("#" + userId);
        StringBuilder sb = new StringBuilder("📋 <b>Аудит</b> — " + esc(who) + "\n");
        if (logs.isEmpty()) sb.append("\nYozuvlar yo'q.");
        java.util.Map<Long, String> nameCache = new java.util.HashMap<>();
        for (AuditLog a : logs) {
            String un = a.getUserId() == null ? "tizim"
                    : nameCache.computeIfAbsent(a.getUserId(), id ->
                        userRepo.findById(id).map(AppUser::getFullName).orElse("#" + id));
            String pl = a.getPayload() == null ? "" : a.getPayload();
            if (pl.length() > 60) pl = pl.substring(0, 60) + "…";
            sb.append("\n• ").append(AUDIT_DF.withZone(props.zoneId()).format(a.getCreatedAt()))
              .append(" — <b>").append(esc(a.getAction())).append("</b>");
            if (userId == 0) sb.append(" · ").append(esc(un));
            if (a.getEntity() != null)
                sb.append(" · ").append(esc(a.getEntity()))
                  .append(a.getEntityId() == null ? "" : "#" + a.getEntityId());
            if (!pl.isEmpty()) sb.append("\n   <i>").append(esc(pl)).append("</i>");
        }
        sender.edit(chatId, msgId, sb.toString(), inline(List.of(
                irow(btn("📥 Excel", "a:aux:" + userId)),
                irow(bk("a:audm")))));
    }

    private void auditExcel(long userId, long chatId) {
        String who = userId == 0 ? "hammasi"
                : userRepo.findById(userId).map(AppUser::getFullName).orElse("user" + userId);
        sender.send(chatId, "⏳ Audit Excel tayyorlanmoqda: <b>" + esc(who) + "</b>…");
        new Thread(() -> {
            try {
                List<AuditLog> logs = userId == 0
                        ? auditRepo.findTop5000ByOrderByIdDesc()
                        : auditRepo.findTop5000ByUserIdOrderByIdDesc(userId);
                byte[] xlsx = excelReport.buildAudit(logs,
                        id -> userRepo.findById(id).map(AppUser::getFullName).orElse("#" + id),
                        props.zoneId());
                sender.sendDocument(chatId, xlsx,
                        "audit_" + (userId == 0 ? "hammasi" : "user" + userId)
                                + "_" + ledger.today() + ".xlsx",
                        "📋 Audit jurnali: <b>" + esc(who) + "</b> (oxirgi " + logs.size() + " yozuv)");
            } catch (Exception e) {
                sender.send(chatId, "⚠️ Excel xatosi: " + esc(e.getMessage()));
            }
        }).start();
    }

    /* ==================================================================
     * 🏷 ТУГМА НОМЛАРИ — sahifa/tugma nomlarini o'zgartirish.
     * ================================================================== */

    private String labelMark(String canonical) {
        return (labelSvc.isHidden(canonical) ? "🙈 " : "") + labelSvc.display(canonical)
                + (labelSvc.isRenamed(canonical) ? " *" : "");
    }

    private void labelList(Session s, long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<String> all = LabelService.RENAMABLE;
        for (int i = 0; i < all.size(); i += 2) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(btn(labelMark(all.get(i)), "a:lb:" + i));
            if (i + 1 < all.size()) r.add(btn(labelMark(all.get(i + 1)), "a:lb:" + (i + 1)));
            rows.add(r);
        }
        String text = "🏷 <b>Тугма номлари ва бўлимлар</b>\n\n"
                + "Bo'limni tanlang — nomini o'zgartirish yoki o'chirish/yoqish mumkin.\n"
                + "<i>* — nomi o'zgartirilgan · 🙈 — o'chirilgan (menyularda ko'rinmaydi)</i>";
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sendContent(s, chatId, text, inline(rows));
    }

    /** Bo'lim kartochkasi: nom o'zgartirish / o'chirish-yoqish. */
    private void labelPick(Session s, int idx, long chatId, int msgId) {
        if (idx < 0 || idx >= LabelService.RENAMABLE.size()) return;
        String canonical = LabelService.RENAMABLE.get(idx);
        boolean hidden = labelSvc.isHidden(canonical);
        boolean protectd = canonical.equals(LabelService.PROTECTED_LABEL);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("✏️ Nomini o'zgartirish", "a:lbr:" + idx)));
        if (!protectd)
            rows.add(irow(btn(hidden ? "👁 Yoqish (menyuga qaytarish)"
                    : "🙈 O'chirish (menyudan yashirish)", "a:lbh:" + idx)));
        rows.add(irow(bk("a:lbm")));
        sender.edit(chatId, msgId, "🏷 <b>" + esc(labelSvc.display(canonical)) + "</b>"
                + (labelSvc.isRenamed(canonical) ? " (asl: " + esc(canonical) + ")" : "")
                + "\nHolat: " + (hidden ? "🙈 <b>o'chirilgan</b> — menyularda ko'rinmaydi"
                    : "👁 <b>ko'rinadi</b>")
                + (protectd ? "\n\nℹ️ Bu bo'limni o'chirib bo'lmaydi — sozlamalarga kirish yo'li."
                    : ""), inline(rows));
    }

    private void labelRenameStart(Session s, int idx, long chatId, int msgId) {
        if (idx < 0 || idx >= LabelService.RENAMABLE.size()) return;
        String canonical = LabelService.RENAMABLE.get(idx);
        s.state = Session.State.ADM_LB_NAME;
        s.data.put("lbIdx", idx);
        sender.edit(chatId, msgId, "🏷 Tanlandi: <b>" + esc(labelSvc.display(canonical))
                + "</b>" + (labelSvc.isRenamed(canonical)
                    ? " (asl: " + esc(canonical) + ")" : "") + "\n\n"
                + "<b>Yangi nomni yozing</b> (2–30 belgi).\n"
                + "Asl nomga qaytarish uchun «-» yuboring:");
    }

    private void labelHideToggle(Session s, int idx, long chatId, int msgId) {
        if (idx < 0 || idx >= LabelService.RENAMABLE.size()) return;
        String canonical = LabelService.RENAMABLE.get(idx);
        if (canonical.equals(LabelService.PROTECTED_LABEL)) { labelPick(s, idx, chatId, msgId); return; }
        boolean nowHidden = !labelSvc.isHidden(canonical);
        labelSvc.setHidden(canonical, nowHidden);
        audit.log(null, nowHidden ? "BOLIM_OCHIRILDI" : "BOLIM_YOQILDI", "label", null, canonical);
        sender.send(chatId, (nowHidden
                ? "🙈 <b>" + esc(labelSvc.display(canonical)) + "</b> o'chirildi — menyularda "
                  + "ko'rinmaydi, bosilsa ham ishlamaydi (SuperAdmin'dan tashqari)."
                : "👁 <b>" + esc(labelSvc.display(canonical)) + "</b> yoqildi — menyularga qaytdi.")
                + "\nFoydalanuvchilarda yangi menyu /start bosilganda ko'rinadi.");
        labelPick(s, idx, chatId, msgId);
    }

    private void labelName(Session s, String text, long chatId) {
        int idx = (int) s.getLong("lbIdx");
        String canonical = LabelService.RENAMABLE.get(idx);
        s.state = Session.State.IDLE;
        s.data.remove("lbIdx");

        if (text.equals("-")) {
            labelSvc.rename(canonical, "");
            sender.send(chatId, "✅ <b>" + esc(canonical) + "</b> asl nomiga qaytarildi.\n"
                    + "Yangilangan menyu uchun bo'limni qayta oching yoki /start bosing.");
            return;
        }
        String name = text.trim();
        if (name.length() < 2 || name.length() > 30) {
            sender.send(chatId, "⚠️ Nom 2–30 belgi bo'lsin. Qaytadan yozing:");
            s.state = Session.State.ADM_LB_NAME;
            s.data.put("lbIdx", idx);
            return;
        }
        if (name.chars().allMatch(Character::isDigit)) {
            sender.send(chatId, "⚠️ Faqat raqamdan iborat nom bo'lmaydi (summa kiritish bilan "
                    + "adashadi). Qaytadan yozing:");
            s.state = Session.State.ADM_LB_NAME;
            s.data.put("lbIdx", idx);
            return;
        }
        if (labelSvc.clashes(canonical, name)) {
            sender.send(chatId, "⚠️ Bu nom boshqa tugma bilan bir xil bo'lib qoladi. Boshqa nom yozing:");
            s.state = Session.State.ADM_LB_NAME;
            s.data.put("lbIdx", idx);
            return;
        }
        labelSvc.rename(canonical, name);
        sender.send(chatId, "✅ Tugma nomi o'zgartirildi:\n<b>" + esc(canonical) + "</b> → <b>"
                + esc(name) + "</b>\n\nYangi nom menyu qayta ochilganda ko'rinadi "
                + "(foydalanuvchilar /start bosishi kifoya).");
    }

    /* ==================================================================
     * 🔑 MOYSKLAD API KALITI — botning o'zidan ko'rish/almashtirish.
     * Kalit settings jadvalida saqlanadi (.env dagi zaxira bo'lib qoladi),
     * shuning uchun API ulanmagan/yaroqsiz paytda ham shu yerdan tuzatiladi.
     * ================================================================== */

    private void msToken(Session s, long chatId, int msgId) {
        String t = msClient.currentToken();
        String masked = t.isBlank() ? "<i>kiritilmagan</i>"
                : t.length() > 12 ? "<code>" + esc(t.substring(0, 6)) + "…"
                    + esc(t.substring(t.length() - 4)) + "</code>"
                : "<code>•••</code>";
        boolean ok = !t.isBlank() && msClient.testToken(t);
        String text = "🔑 <b>MoySklad API kaliti</b>\n\n"
                + "Joriy kalit: " + masked + "\n"
                + "Holat: " + (ok ? "🟢 <b>ulangan</b> — API javob beryapti"
                    : "🔴 <b>ulanmagan</b> — kalit yo'q yoki yaroqsiz")
                + "\n\nYangi kalit kiritsangiz, sinxron darhol yangi kalit bilan ishlaydi.";
        InlineKeyboardMarkup kb = inline(List.of(
                irow(btn("✏️ Yangi kalit kiritish", "a:mske")),
                irow(bk("a:p:set"))));
        if (msgId > 0) sender.edit(chatId, msgId, text, kb);
        else sendContent(s, chatId, text, kb);
    }

    private void msTokenSave(AppUser u, Session s, String text, long chatId) {
        s.state = Session.State.IDLE;
        if (text.equals("-")) {
            sender.send(chatId, "❌ Bekor qilindi — kalit o'zgartirilmadi.");
            return;
        }
        String token = text.trim();
        if (token.length() < 20 || token.contains(" ")) {
            s.state = Session.State.ADM_MS_TOKEN;
            sender.send(chatId, "⚠️ Bu MoySklad kalitiga o'xshamaydi (juda qisqa yoki "
                    + "bo'sh joy bor). Qaytadan yuboring yoki «-» bilan bekor qiling:");
            return;
        }
        boolean ok = msClient.testToken(token);
        msClient.updateToken(token);
        audit.log(u.getId(), "MS_TOKEN_YANGILANDI", "settings", null,
                "yangi kalit: " + token.substring(0, 6) + "… (test: " + (ok ? "OK" : "XATO") + ")");
        if (ok) {
            sender.send(chatId, "✅ <b>Yangi kalit saqlandi va tekshirildi</b> — API javob berdi.\n"
                    + "Sinxron keyingi siklda (30 soniyagacha) yangi kalit bilan ishlaydi.");
            new Thread(syncService::sync).start();
        } else {
            sender.send(chatId, "⚠️ Kalit <b>saqlandi</b>, lekin API hozircha javob bermadi "
                    + "(yaroqsiz kalit yoki tarmoq muammosi bo'lishi mumkin).\n"
                    + "Kalit to'g'ri bo'lsa, sinxron o'zi tiklanadi. Holatni "
                    + "🔑 MoySklad API bo'limidan qayta tekshiring.");
        }
    }

    /* ==================================================================
     * 👁 ҲУҚУҚЛАР — foydalanuvchini tanlab, u UI'da nimani ko'rishi va
     * nimalar qila olishini jonli kartochkada ko'rish.
     * ================================================================== */

    private void permMenu(Session s, long chatId, int msgId) {
        List<AppUser> users = userRepo.findByActiveTrueOrderByRoleAscIdAsc();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < users.size(); i += 2) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(btn(roleEmoji(users.get(i).getRole()) + " " + users.get(i).getFullName(),
                    "a:prc:" + users.get(i).getId()));
            if (i + 1 < users.size())
                r.add(btn(roleEmoji(users.get(i + 1).getRole()) + " " + users.get(i + 1).getFullName(),
                        "a:prc:" + users.get(i + 1).getId()));
            rows.add(r);
        }
        rows.add(irow(btn("🏬 Отдел кесимида (butun kassaga)", "a:prko")));
        rows.add(irow(bk("a:p:set")));
        String text = "👁 <b>Ҳуқуқлар</b>\n\nKimning imkoniyatlarini ko'rasiz/boshqarasiz?\n"
                + "👤 kassir · 🧮 buxgalter · 👑 superadmin";
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sendContent(s, chatId, text, inline(rows));
    }

    private String roleEmoji(Role r) {
        return switch (r) { case KASSIR -> "👤"; case BUXGALTER -> "🧮"; case SUPERADMIN -> "👑"; };
    }

    private void permCard(long userId, long chatId, int msgId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null) return;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (x.getRole() != Role.SUPERADMIN)
            rows.add(irow(btn("⚙️ Бўлимларини бошқариш", "a:prs:" + userId)));
        rows.add(irow(bk("a:prm")));
        sender.edit(chatId, msgId, permText(x), inline(rows));
    }

    /* ---------- huquq berish/olish: user yoki kassa kesimida ---------- */

    private void permKassaList(long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn("🏪 " + k.getName(), "a:prk:" + k.getId())));
        rows.add(irow(bk("a:prm")));
        sender.edit(chatId, msgId, "🏬 <b>Отдел кесимида ҳуқуқ</b>\n\n"
                + "Kassani tanlang — sozlama shu kassaning BARCHA kassirlariga amal qiladi\n"
                + "(user uchun alohida belgilangani bo'lsa, o'shanisi ustun):", inline(rows));
    }

    private void permGrid(String subj, long id, long chatId, int msgId) {
        String who = subj.equals("user")
                ? userRepo.findById(id).map(AppUser::getFullName).orElse("#" + id)
                : names.owner(OwnerType.KASSA, id);
        List<String> all = LabelService.RENAMABLE;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        String cb = subj.equals("user") ? "a:prt:" : "a:prq:";
        for (int i = 0; i < all.size(); i += 2) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(permBtn(subj, id, all.get(i), cb + id + "." + i));
            if (i + 1 < all.size())
                r.add(permBtn(subj, id, all.get(i + 1), cb + id + "." + (i + 1)));
            rows.add(r);
        }
        rows.add(irow(bk(subj.equals("user") ? "a:prc:" + id : "a:prko")));
        boolean configured = subj.equals("user") ? permSvc.userConfigured(id) : permSvc.kassaConfigured(id);
        sender.edit(chatId, msgId, "⚙️ <b>Бўлим ҳуқуқлари</b> — "
                + (subj.equals("user") ? "👤 " : "🏪 ") + esc(who) + "\n\n"
                + "Tugmani bosib holatni almashtiring:\n"
                + "🚫 — taqiqlangan · ✅ — ruxsat berilgan · belgisiz — "
                + (configured ? "TAQIQLANGAN (kamida bitta ✅/🚫 belgilangач, belgilanmaganlar ko'rinmaydi)"
                              : "umumiy holat (hali hech narsa belgilanmagan)")
                + "\n<i>O'zgarish foydalanuvchida /start yoki menyu qayta ochilganda ko'rinadi.</i>",
                inline(rows));
    }

    private InlineKeyboardButton permBtn(String subj, long id, String canonical, String cb) {
        Boolean o = subj.equals("user") ? permSvc.userOverride(id, canonical)
                : permSvc.kassaOverride(id, canonical);
        String mark = o == null ? "" : (o ? "✅ " : "🚫 ");
        String label = mark + labelSvc.display(canonical);
        if (label.length() > 32) label = label.substring(0, 32);
        return btn(label, cb);
    }

    /** arg: "<id>.<idx>" — holat sikli: meros → 🚫 taqiq → ✅ ruxsat → meros. */
    private void permToggle(AppUser admin, String subj, String arg, long chatId, int msgId) {
        int dot = arg.indexOf('.');
        if (dot <= 0) return;
        long id = Long.parseLong(arg.substring(0, dot));
        int idx = Integer.parseInt(arg.substring(dot + 1));
        if (idx < 0 || idx >= LabelService.RENAMABLE.size()) return;
        String canonical = LabelService.RENAMABLE.get(idx);
        Boolean cur = subj.equals("user") ? permSvc.userOverride(id, canonical)
                : permSvc.kassaOverride(id, canonical);
        Boolean next = cur == null ? Boolean.FALSE : (cur ? null : Boolean.TRUE);
        permSvc.set(subj, id, canonical, next);
        audit.log(admin.getId(), "HUQUQ_" + (next == null ? "MEROS" : next ? "RUXSAT" : "TAQIQ"),
                subj, id, canonical);
        permGrid(subj, id, chatId, msgId);
    }

    /** Foydalanuvchining roli+kassasiga qarab UI va huquqlar kartochkasi. */
    private String permText(AppUser x) {
        String kassaName = x.getKassaId() == null ? null
                : names.owner(OwnerType.KASSA, x.getKassaId());
        Kassa kassa = x.getKassaId() == null ? null
                : kassaRepo.findById(x.getKassaId()).orElse(null);
        boolean msBound = kassa != null && kassa.getMoyskladGroupId() != null
                && !kassa.getMoyskladGroupId().isBlank();

        StringBuilder sb = new StringBuilder();
        sb.append("👁 <b>").append(esc(x.getFullName())).append("</b> — ")
          .append(roleEmoji(x.getRole())).append(" ").append(x.getRole().name());
        if (kassaName != null) sb.append(" · 🏪 ").append(esc(kassaName));
        sb.append("\n📲 Telegram: ").append(x.getTelegramId() == null
                ? "❌ ulanmagan (menyularni ko'ra olmaydi, xabar olmaydi)" : "✅ ulangan");

        sb.append("\n\n<b>Bosh menyusida ko'radi:</b>\n");
        switch (x.getRole()) {
            case KASSIR -> sb.append("• ").append(esc(labelSvc.display("📊 КАССАМ")))
                    .append(" · ").append(esc(labelSvc.display("💰 БУГУНГИ ТУШУМ")))
                    .append("\n• ").append(esc(labelSvc.display("💸 Rasxod")))
                    .append(" · ").append(esc(labelSvc.display("🔁 O'tkazma")))
                    .append("\n• ").append(esc(labelSvc.display("📤 Hisobot topshirish")))
                    .append(" · ").append(esc(labelSvc.display("🤝 КОНТРАГЕНТ")));
            default -> sb.append("• ").append(esc(labelSvc.display("🏪 KASSA")))
                    .append(" · ").append(esc(labelSvc.display("🤝 КОНТРАГЕНТ")));
        }

        sb.append("\n\n<b>Qila oladi:</b>\n");
        switch (x.getRole()) {
            case KASSIR -> {
                sb.append("• Faqat O'Z kassasi")
                  .append(kassaName == null ? "" : " («" + esc(kassaName) + "»)")
                  .append(": balans, tushum, tarix, Excel\n");
                sb.append("• Rasxod SO'ROVI (buxgalter tasdig'i bilan), o'tkazma, hisobot topshirish\n");
                sb.append("• Kontragent qarz daftari: qidiruv, balans, eslatma qo'shish (o'ziniki)\n");
                if (x.getKassaId() != null)
                    sb.append("• 🤝 Настройка: otdeliga odam qo'shish (erkin); o'chirish/tahrir — "
                            + "SuperAdmin tasdig'i bilan\n");
            }
            case BUXGALTER -> {
                sb.append("• Barcha kassalar: holat, statistika, saldo, svod/Excel, tarix\n");
                sb.append("• Rasxod so'rovlarini tasdiqlash/rad etish, hisobot qabul qilish\n");
                sb.append("• Kassadan pul qabul qilish (sana tanlash bilan), o'z rasxodi\n");
                sb.append("• Kontragent qarz daftari (o'ziniki)\n");
            }
            case SUPERADMIN -> {
                sb.append("• Buxgalter qila oladigan HAMMASI\n");
                sb.append("• Foydalanuvchi/kassa qo'shish-o'chirish, rol o'zgartirish\n");
                sb.append("• Boshlang'ich qoldiq, tasdiqlangan rasxodni bekor/tahrirlash\n");
                sb.append("• Аудит (Excel bilan), tugma nomlari, MoySklad API kaliti\n");
                sb.append("• Kontragent: HAMMANING eslatmalarini ko'radi\n");
            }
        }

        sb.append("\n<b>Ko'rmaydi / qila olmaydi:</b>\n");
        switch (x.getRole()) {
            case KASSIR -> sb.append("• Boshqa kassalar, umumiy statistika, svod, "
                    + "buxgalteriya hisoboti\n• Rasxodni o'zi tasdiqlash, sozlamalar, Аудит");
            case BUXGALTER -> sb.append("• ⚙️ Настройка (foydalanuvchi/kassa boshqaruvi, "
                    + "Аудит, tugma nomlari, MoySklad kaliti)\n• Boshqalarning qarz eslatmalari");
            case SUPERADMIN -> sb.append("• Cheklov yo'q");
        }

        if (x.getRole() == Role.KASSIR && kassa != null)
            sb.append("\n\nℹ️ Kassasi MoySklad otdeliga ").append(msBound
                    ? "BOG'LANGAN — kirim/chiqim avtomatik tushadi"
                    : "bog'lanMAGAN — MoySklad'dan avtomatik hech narsa kelmaydi, "
                      + "faqat bot orqali yuritiladi");

        sb.append("\n\n<b>Avtomatik xabarlar:</b> ");
        switch (x.getRole()) {
            case KASSIR -> sb.append("o'z kassasining kirim/chiqimi, rasxod javobi, "
                    + "qarz eslatmalari, 21:00 kunlik eslatma");
            case BUXGALTER, SUPERADMIN -> sb.append("MoySklad kirim/chiqim, STORNO/tuzatishlar, "
                    + "so'rovlar, qarz eslatmalari");
        }
        return sb.toString();
    }

    /* ---------- davr yordamchilari ---------- */

    private java.time.LocalDate[] periodOf(String code) {
        java.time.LocalDate t = ledger.today();
        return switch (code) {
            case "t" -> new java.time.LocalDate[]{t, t};
            case "y" -> new java.time.LocalDate[]{t.minusDays(1), t.minusDays(1)};
            case "7" -> new java.time.LocalDate[]{t.minusDays(6), t};
            case "30" -> new java.time.LocalDate[]{t.minusDays(29), t};
            default -> new java.time.LocalDate[]{t.withDayOfMonth(1), t};
        };
    }

    private String rangeLabel(java.time.LocalDate f, java.time.LocalDate t) {
        return f.equals(t) ? f.format(DF) : f.format(DF) + " — " + t.format(DF);
    }

    /* ==================== 👥 FOYDALANUVCHI QO'SHISH ==================== */

    /** Botga yozgan (hali qo'shilmagan) odamlar ro'yxatini ko'rsatadi — tanlash oson. */
    private void auStart(Session s, long chatId) {
        s.reset();
        List<uz.kassa.domain.Guest> guests = guestRepo.findAllByOrderByLastSeenDesc().stream()
                .filter(g -> userRepo.findByTelegramId(g.getTelegramId()).isEmpty())
                .limit(8).toList();

        // MoySklad xodimlari (Владелец-сотрудник) — hali tizimda yo'qlari
        List<String[]> emps = new ArrayList<>();
        try {
            List<AppUser> all = userRepo.findAll();
            for (MoySkladClient.MsEmployee e : msClient.fetchEmployees()) {
                boolean exists = all.stream()
                        .anyMatch(x -> x.getFullName().equalsIgnoreCase(e.name()));
                if (!exists) emps.add(new String[]{e.name(), e.phone()});
                if (emps.size() >= 20) break;
            }
        } catch (Exception ignored) { }
        s.data.put("msEmps", emps);

        s.state = Session.State.ADM_AU_PICK;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (uz.kassa.domain.Guest g : guests) {
            String label = (g.getName() == null || g.getName().isBlank()
                        ? String.valueOf(g.getTelegramId()) : g.getName())
                    + (g.getPhone() != null ? " · " + g.getPhone()
                        : (g.getUsername() == null ? "" : " (@" + g.getUsername() + ")"));
            if (label.length() > 40) label = label.substring(0, 40) + "…";
            rows.add(irow(btn("👤 " + label, "a:gu:" + g.getTelegramId())));
        }
        for (int i = 0; i < emps.size(); i++) {
            String label = emps.get(i)[0];
            if (label.length() > 45) label = label.substring(0, 45) + "…";
            rows.add(irow(btn("👔 " + label, "a:me:" + i)));
        }
        rows.add(irow(btn("✍️ Telefon raqam bilan qidirish", "a:gu:m")));
        rows.add(irow(btn("❌ Bekor", "cx")));
        sender.send(chatId, "👥 <b>Yangi foydalanuvchi</b>\n\n"
                + (guests.isEmpty() ? "" : "👤 — botga yozgan odamlar\n")
                + (emps.isEmpty() ? "" : "👔 — MoySklad xodimlari (Владелец-сотрудник): "
                    + "tanlansangiz Telegram'siz yaratiladi, odam botga kirib telefonini "
                    + "yuborsa avtomatik ulanadi\n")
                + "\nBirini tanlang:", inline(rows));
    }

    /** MoySklad xodimi tanlandi — Telegram'siz foydalanuvchi sifatida yaratish yo'li. */
    private void auEmp(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_AU_PICK) return;
        Object o = s.data.get("msEmps");
        int i = Integer.parseInt(arg);
        if (!(o instanceof List<?> l) || i < 0 || i >= l.size()) return;
        String[] emp = (String[]) l.get(i);
        s.data.remove("tgid");
        s.data.put("name", emp[0]);
        s.data.put("empPhone", emp[1] == null ? "" : emp[1]);
        s.state = Session.State.ADM_AU_ROLE;
        sender.edit(chatId, msgId, "👔 Tanlandi: <b>" + esc(emp[0]) + "</b>"
                + (emp[1] == null || emp[1].isBlank() ? "" : " · " + esc(emp[1]))
                + "\nℹ️ Telegram hali ulanmagan — u botga kirib «📱 Telefon raqamni "
                + "yuborish»ni bossa avtomatik ulanadi"
                + (emp[1] == null || emp[1].isBlank()
                    ? " (MoySkladda telefoni yo'q — keyin jadvalda Telefon ustunini to'ldiring)" : "")
                + "\n\nRolini tanlang:", inline(List.of(
                irow(btn("👤 Kassir", "a:rl:K")),
                irow(btn("🧮 Buxgalter", "a:rl:B"), btn("👑 SuperAdmin", "a:rl:S")),
                irow(btn("❌ Bekor", "cx")))));
    }

    private void auPick(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_AU_PICK) return;
        if (arg.equals("m")) {
            s.state = Session.State.ADM_AU_TGID;
            sender.edit(chatId, msgId, "📱 Telefon raqamini kiriting (masalan +998901234567).\n"
                    + "<i>Foydalanuvchi botga kirib «Telefon raqamni yuborish» tugmasini "
                    + "bosgan bo'lishi kerak.</i>");
            return;
        }
        long tgId;
        try { tgId = Long.parseLong(arg); } catch (NumberFormatException e) { return; }
        if (userRepo.findByTelegramId(tgId).isPresent()) {
            s.reset();
            sender.edit(chatId, msgId, "⚠️ Bu foydalanuvchi allaqachon tizimda mavjud");
            return;
        }
        s.data.put("tgid", tgId);
        String suggested = guestRepo.findById(tgId)
                .map(uz.kassa.domain.Guest::getName).orElse(null);
        s.state = Session.State.ADM_AU_NAME;
        if (suggested != null && !suggested.isBlank()) {
            s.data.put("suggName", suggested);
            sender.edit(chatId, msgId, "Tanlandi: <code>" + tgId + "</code>\n\n"
                    + "Ism-familiyasini kiriting, yoki «<b>-</b>» yuboring — "
                    + "<b>" + esc(suggested) + "</b> deb yoziladi:");
        } else {
            sender.edit(chatId, msgId, "Tanlandi: <code>" + tgId + "</code>\n\n"
                    + "Ism-familiyasini kiriting:");
        }
    }

    /** Telefon raqam (asosiy yo'l) yoki Telegram ID bilan qidirish. */
    private void auTgId(Session s, String text, long chatId) {
        String digits = text.replaceAll("\\D", "");
        if (digits.length() < 7) {
            sender.send(chatId, "⚠️ Telefon raqam (masalan +998901234567) yoki Telegram ID kiriting:");
            return;
        }

        // 1) Telefon bo'yicha — kontakt yuborgan mehmonlar orasidan
        for (uz.kassa.domain.Guest g : guestRepo.findAllByOrderByLastSeenDesc()) {
            String gp = g.getPhone() == null ? "" : g.getPhone().replaceAll("\\D", "");
            if (gp.isEmpty()) continue;
            if (gp.endsWith(digits) || digits.endsWith(gp)) {
                if (userRepo.findByTelegramId(g.getTelegramId()).isPresent()) {
                    sender.send(chatId, "⚠️ Bu raqam egasi allaqachon tizimda");
                    s.reset();
                    return;
                }
                s.data.put("tgid", g.getTelegramId());
                String sugg = g.getName();
                s.state = Session.State.ADM_AU_NAME;
                if (sugg != null && !sugg.isBlank()) {
                    s.data.put("suggName", sugg);
                    sender.send(chatId, "✅ Topildi: <b>" + esc(sugg) + "</b> ("
                            + esc(g.getPhone()) + ")\n\nIsm-familiyasini kiriting, "
                            + "yoki «<b>-</b>» — shu nom qoladi:");
                } else sender.send(chatId, "✅ Topildi: " + esc(g.getPhone())
                        + "\n\nIsm-familiyasini kiriting:");
                return;
            }
        }

        // 2) Telefonga o'xshasa (998 bilan boshlanadi) lekin topilmasa — yo'riqnoma
        if (digits.startsWith("998") || text.trim().startsWith("+")) {
            sender.send(chatId, "⚠️ Bu raqam topilmadi.\n\n"
                    + "Foydalanuvchi botga kirib <b>«📱 Telefon raqamni yuborish»</b> "
                    + "tugmasini bossin — keyin raqami bilan topiladi.");
            return;
        }

        // 3) Telegram ID sifatida
        long tgId = Long.parseLong(digits);
        if (userRepo.findByTelegramId(tgId).isPresent()) {
            sender.send(chatId, "⚠️ Bu ID bilan foydalanuvchi allaqachon mavjud");
            s.reset();
            return;
        }
        s.data.put("tgid", tgId);
        s.state = Session.State.ADM_AU_NAME;
        sender.send(chatId, "Ism-familiyasini kiriting:");
    }

    private void auName(Session s, String text, long chatId) {
        String sugg = s.getStr("suggName");
        s.data.put("name", text.equals("-") && sugg != null ? sugg : text);
        s.state = Session.State.ADM_AU_ROLE;
        sender.send(chatId, "Rolini tanlang:", inline(List.of(
                irow(btn("👤 Kassir", "a:rl:K")),
                irow(btn("🧮 Buxgalter", "a:rl:B"), btn("👑 SuperAdmin", "a:rl:S")),
                irow(btn("❌ Bekor", "cx")))));
    }

    private void auRole(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_AU_ROLE) return;
        switch (arg) {
            case "K" -> {
                List<Kassa> list = kassaRepo.findByActiveTrueOrderByIdAsc();
                if (list.isEmpty()) {
                    s.reset();
                    sender.edit(chatId, msgId, "⚠️ Avval kassa qo'shing (🏪 Kassa qo'shish)");
                    return;
                }
                s.data.put("role", Role.KASSIR);
                s.state = Session.State.ADM_AU_KASSA;
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                for (Kassa k : list) rows.add(irow(btn("🏪 " + k.getName(), "a:ks:" + k.getId())));
                rows.add(irow(btn("❌ Bekor", "cx")));
                sender.edit(chatId, msgId, "Qaysi kassaga biriktiriladi?", inline(rows));
            }
            case "B" -> saveUser(s, Role.BUXGALTER, null, chatId, msgId);
            case "S" -> saveUser(s, Role.SUPERADMIN, null, chatId, msgId);
        }
    }

    private void auKassa(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_AU_KASSA) return;
        saveUser(s, Role.KASSIR, Long.parseLong(arg), chatId, msgId);
    }

    private void saveUser(Session s, Role role, Long kassaId, long chatId, int msgId) {
        Long tgId = s.data.get("tgid") == null ? null : s.getLong("tgid");
        String name = s.getStr("name");
        String phoneRaw = s.getStr("empPhone");
        String phone = phoneRaw == null ? "" : phoneRaw.replaceAll("\\D", "");
        s.reset();
        userRepo.save(AppUser.builder()
                .telegramId(tgId).fullName(name).role(role).kassaId(kassaId)
                .phone(phone.isEmpty() ? null : phone)
                .active(true).build());
        if (tgId != null) guestRepo.deleteById(tgId);   // ro'yxatga olindi — mehmonlardan chiqadi
        String where = kassaId == null ? "" : "\nKassa: " + esc(names.owner(OwnerType.KASSA, kassaId));
        sender.edit(chatId, msgId, "✅ Foydalanuvchi qo'shildi:\n<b>" + esc(name) + "</b> ("
                + role + ")" + where
                + (tgId != null
                    ? "\nTelegram ID: <code>" + tgId + "</code>\n\n"
                      + "Endi u botga <b>/start</b> yozsa — menyusi ochiladi."
                    : (phone.isEmpty() ? "" : "\nTelefon: <code>" + esc(phone) + "</code>")
                      + "\n\nℹ️ Telegram hali ulanmagan — u botga kirib «📱 Telefon raqamni "
                      + "yuborish»ni bossa avtomatik ulanadi."));
    }

    /* ==================== 🏪 KASSA QO'SHISH ==================== */

    private void akName(Session s, String text, long chatId) {
        // Soddalashtirilgan: nom -> darhol otdel tanlash (savdo nuqtasi bosqichi olib tashlandi)
        s.data.put("kassaName", text);
        akFinish(s, "-", chatId);
    }

    private void akFinish(Session s, String text, long chatId) {
        s.data.put("kassaMsId", text.equals("-") ? "" : text.trim());

        // MoySklad otdellari (Владелец-отдел) — kirim/chiqim shu bog'lanish orqali kassaga tushadi
        Map<String, String> groups = msClient.fetchGroups();
        if (groups.isEmpty()) { createKassa(s, null, chatId, null); return; }

        s.data.put("groups", groups);
        s.state = Session.State.ADM_AK_GROUP;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Map.Entry<String, String> g : groups.entrySet())
            rows.add(irow(btn("🗂 " + g.getValue(), "a:gr:" + g.getKey())));
        rows.add(irow(btn("➖ Otdel biriktirmaslik", "a:gr:-")));
        rows.add(irow(btn("❌ Bekor", "cx")));
        sender.send(chatId, "MoySklad <b>otdelini</b> tanlang —\n"
                + "Приходный/Расходный ордерlar shu otdel bo'yicha kassaga yoziladi:", inline(rows));
    }

    private void akGroup(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_AK_GROUP) return;
        createKassa(s, arg.equals("-") ? null : arg, chatId, msgId);
    }

    private void createKassa(Session s, String groupId, long chatId, Integer msgId) {
        String name = s.getStr("kassaName");
        String msIdRaw = s.getStr("kassaMsId");
        String storeId = (msIdRaw == null || msIdRaw.isBlank()) ? null : msIdRaw;
        @SuppressWarnings("unchecked")
        Map<String, String> groups = s.data.get("groups") instanceof Map<?, ?> m
                ? (Map<String, String>) m : new LinkedHashMap<>();
        s.reset();

        Kassa k = kassaRepo.save(Kassa.builder()
                .name(name).moyskladStoreId(storeId).moyskladGroupId(groupId).active(true).build());

        String text = "✅ Kassa qo'shildi: <b>" + esc(k.getName()) + "</b> (#" + k.getId() + ")"
                + (storeId == null ? "" : "\nSavdo nuqtasi ID: <code>" + esc(storeId) + "</code>")
                + (groupId == null
                    ? "\n\n⚠️ Otdel biriktirilmagan — bu kassaning MoySklad kirim-chiqimi Buxgalteriyaga yoziladi."
                    : "\nOtdel: <b>" + esc(groups.getOrDefault(groupId, groupId)) + "</b>");
        if (msgId == null) sender.send(chatId, text, null);
        else sender.edit(chatId, msgId, text);
    }

    /* ==================== 💼 BOSHLANG'ICH QOLDIQ ==================== */

    /** Boshlang'ich qoldiq faqat Основной отдел (buxgalteriya)ga kiritiladi. */
    private void ibStart(Session s, long chatId) {
        s.reset(); s.state = Session.State.ADM_IB_NAQD;
        s.data.put("obT", OwnerType.BUXGALTERIYA);
        s.data.put("obId", LedgerService.BUX_ID);
        sender.send(chatId, "💼 <b>Boshlang'ich qoldiq</b> — Основной отдел\n\n"
                + "💵 <b>NAQD</b> boshlang'ich qoldiqni kiriting (so'm, 0 mumkin):", cancelOnly());
    }

    private void ibOwner(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_IB_OWNER) return;
        if (arg.equals("B")) {
            s.data.put("obT", OwnerType.BUXGALTERIYA);
            s.data.put("obId", LedgerService.BUX_ID);
        } else {
            s.data.put("obT", OwnerType.KASSA);
            s.data.put("obId", Long.parseLong(arg.substring(1)));
        }
        s.state = Session.State.ADM_IB_NAQD;
        sender.edit(chatId, msgId, "Tanlandi: <b>"
                + esc(names.owner((OwnerType) s.data.get("obT"), s.getLong("obId")))
                + "</b>\n\n💵 <b>NAQD</b> boshlang'ich qoldiqni kiriting (so'm, 0 mumkin):");
    }

    private void ibNaqd(Session s, String text, long chatId) {
        long v = text.equals("0") ? 0 : parseAmount(text);
        if (v < 0) { sender.send(chatId, "⚠️ 0 yoki musbat summa kiriting"); return; }
        s.data.put("ibNaqd", v);
        s.state = Session.State.ADM_IB_KLIK;
        sender.send(chatId, "📲 <b>CLICK</b> boshlang'ich qoldiqni kiriting (so'm, 0 mumkin):");
    }

    private void ibFinish(AppUser u, Session s, String text, long chatId) {
        long klik = text.equals("0") ? 0 : parseAmount(text);
        if (klik < 0) { sender.send(chatId, "⚠️ 0 yoki musbat summa kiriting"); return; }
        if (s.getLong("ibNaqd") == 0 && klik == 0) {
            s.reset();
            sender.send(chatId, "Ikkala summa ham 0 — hech narsa yozilmadi.");
            return;
        }
        s.data.put("ibKlik", klik);
        s.state = Session.State.ADM_IB_SANA;
        java.time.LocalDate now = java.time.LocalDate.now();
        sender.send(chatId, "📅 <b>Qaysi sanaga kiritilsin?</b>\n\n"
                        + "Tugmani bosing yoki eskiroq sanani o'zingiz yozing (masalan <code>"
                        + now.minusDays(10).format(DF) + "</code>):",
                inline(List.of(
                        irow(btn("📅 Bugun", "a:ibd:0"), btn("Kecha", "a:ibd:1")),
                        irow(btn(now.minusDays(2).format(DF), "a:ibd:2"),
                             btn(now.minusDays(3).format(DF), "a:ibd:3"),
                             btn(now.minusDays(4).format(DF), "a:ibd:4")),
                        irow(btn("🗓 Kalendar", "a:cal:o:ib")),
                        irow(btn("❌ Bekor", "cx")))));
    }

    private void ibSanaBtn(AppUser u, Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_IB_SANA) return;
        java.time.LocalDate d = java.time.LocalDate.now().minusDays(Long.parseLong(arg));
        sender.edit(chatId, msgId, "📅 Sana: <b>" + d.format(DF) + "</b>");
        ibCommit(u, s, d, chatId);
    }

    private void ibSana(AppUser u, Session s, String text, long chatId) {
        java.time.LocalDate d;
        try { d = java.time.LocalDate.parse(text.trim(), DF); }
        catch (Exception e) {
            try { d = java.time.LocalDate.parse(text.trim()); }
            catch (Exception e2) {
                sender.send(chatId, "⚠️ Sana formati: <code>kun.oy.yil</code> — masalan <code>"
                        + java.time.LocalDate.now().format(DF) + "</code>");
                return;
            }
        }
        if (d.isAfter(java.time.LocalDate.now())) {
            sender.send(chatId, "⚠️ Kelajak sanasi bo'lmaydi. Qaytadan kiriting:");
            return;
        }
        ibCommit(u, s, d, chatId);
    }

    private void ibCommit(AppUser u, Session s, java.time.LocalDate date, long chatId) {
        OwnerType ot = (OwnerType) s.data.get("obT");
        long oid = s.getLong("obId");
        long naqd = s.getLong("ibNaqd");
        long klik = s.getLong("ibKlik");
        s.reset();

        if (naqd > 0) ledger.postAdjustment(OpType.BOSHLANGICH, ot, oid, MoneyType.NAQD,
                naqd, "Boshlang'ich qoldiq", u.getId(), date);
        if (klik > 0) ledger.postAdjustment(OpType.BOSHLANGICH, ot, oid, MoneyType.KLIK,
                klik, "Boshlang'ich qoldiq", u.getId(), date);

        sender.send(chatId, "✅ Boshlang'ich qoldiq kiritildi — <b>"
                + esc(names.owner(ot, oid)) + "</b>\n"
                + "📅 Sana: <b>" + date.format(DF) + "</b>\n"
                + "💵 Naqd: <b>" + fmt(naqd) + "</b> so'm\n"
                + "📲 Click: <b>" + fmt(klik) + "</b> so'm");
    }

    /* ==================== 🛠 KORREKTIROVKA (har bir otdel uchun) ==================== */

    /** Korrektirovka: otdel (Buxgalteriya yoki istalgan kassa) tanlanadi. Faqat SuperAdmin. */
    private void krStart(Session s, long chatId) {
        s.reset(); s.state = Session.State.ADM_KR_OWNER;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("🏦 Буxгалтерия (Основной)", "a:kro:B")));
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn("🏪 " + k.getName(), "a:kro:K" + k.getId())));
        rows.add(irow(btn("❌ Bekor", "cx")));
        sender.send(chatId, "🛠 <b>Корректировка</b>\n\n"
                + "Balans qo'lda tuzatiladigan otdelni tanlang:", inline(rows));
    }

    private void krOwner(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_KR_OWNER) return;
        if (arg.equals("B")) {
            s.data.put("krT", OwnerType.BUXGALTERIYA);
            s.data.put("krId", LedgerService.BUX_ID);
        } else {
            s.data.put("krT", OwnerType.KASSA);
            s.data.put("krId", Long.parseLong(arg.substring(1)));
        }
        OwnerType ot = (OwnerType) s.data.get("krT");
        long oid = s.getLong("krId");
        long n = ledger.view(ot, oid, MoneyType.NAQD).getAmount();
        long k = ledger.view(ot, oid, MoneyType.KLIK).getAmount();
        s.state = Session.State.ADM_KR_MT;
        sender.edit(chatId, msgId, "🛠 <b>" + esc(names.owner(ot, oid)) + "</b>\n\n"
                        + "💵 Naqd: <b>" + fmt(n) + "</b> so'm\n"
                        + "📲 Click: <b>" + fmt(k) + "</b> so'm\n\n"
                        + "Qaysi pul turi tuzatiladi?",
                inline(List.of(
                        irow(btn("💵 Naqd", "a:krm:NAQD"), btn("📲 Click", "a:krm:KLIK")),
                        irow(btn("❌ Bekor", "cx")))));
    }

    /** Pul turi tanlandi — AVVAL sana so'raladi (keyin soat, keyin summa). */
    private void krMt(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_KR_MT) return;
        MoneyType mt = MoneyType.valueOf(arg);
        s.data.put("krMt", mt);
        long cur = ledger.view((OwnerType) s.data.get("krT"), s.getLong("krId"), mt).getAmount();
        s.state = Session.State.ADM_KR_SANA;
        sender.edit(chatId, msgId, "🛠 <b>" + esc(names.owner((OwnerType) s.data.get("krT"),
                        s.getLong("krId"))) + "</b> — " + mtLabel(mt)
                + "\nJoriy balans: <b>" + fmt(cur) + "</b> so'm\n\n"
                + "📅 <b>Qaysi sana bilan korrektirovka qilinsin?</b>", inline(List.of(
                irow(btn("📅 Bugun", "a:krd:0"), btn("Kecha", "a:krd:1")),
                irow(btn("🗓 Kalendar", "a:cal:o:kr")),
                irow(btn("❌ Bekor", "cx")))));
    }

    private void krSanaBtn(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_KR_SANA) return;
        krSanaChosen(s, ledger.today().minusDays(Long.parseLong(arg)), chatId, msgId);
    }

    /** Sana tanlandi (tugma yoki kalendar) — endi soat so'raladi. */
    private void krSanaChosen(Session s, java.time.LocalDate d, long chatId, int msgId) {
        s.data.put("krDate", d.toString());
        s.state = Session.State.ADM_KR_VAQT;
        String txt = "📅 Sana: <b>" + d.format(DF) + "</b>\n\n"
                + "🕐 <b>Soatni kiriting</b> (SS:DD, masalan <code>14:30</code>) "
                + "yoki tugmani bosing:";
        InlineKeyboardMarkup kb = inline(List.of(
                irow(btn("⏱ Hozirgi vaqt", "a:krt:now")),
                irow(btn("❌ Bekor", "cx"))));
        if (msgId > 0) sender.edit(chatId, msgId, txt, kb);
        else sender.send(chatId, txt, kb);
    }

    private void krVaqtNow(AppUser u, Session s, long chatId, int msgId) {
        if (s.state != Session.State.ADM_KR_VAQT) return;
        krTimeChosen(s, java.time.LocalTime.now(props.zoneId()).withSecond(0).withNano(0),
                chatId, msgId);
    }

    private void krVaqt(AppUser u, Session s, String text, long chatId) {
        java.time.LocalTime t;
        try {
            t = java.time.LocalTime.parse(text.trim(),
                    java.time.format.DateTimeFormatter.ofPattern("H:mm"));
        } catch (Exception e) {
            sender.send(chatId, "⚠️ Soat formati: <code>SS:DD</code> — masalan <code>14:30</code>. "
                    + "Qaytadan kiriting:");
            return;
        }
        krTimeChosen(s, t, chatId, 0);
    }

    /** Soat ham tanlandi — ENDI summa so'raladi. */
    private void krTimeChosen(Session s, java.time.LocalTime time, long chatId, int msgId) {
        String hhmm = time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        s.data.put("krVaqt", hhmm);
        MoneyType mt = (MoneyType) s.data.get("krMt");
        long cur = ledger.view((OwnerType) s.data.get("krT"), s.getLong("krId"), mt).getAmount();
        s.state = Session.State.ADM_KR_SUM;
        String txt = "📅 <b>" + java.time.LocalDate.parse(s.getStr("krDate")).format(DF)
                + " " + hhmm + "</b> — " + mtLabel(mt)
                + "\nJoriy balans: <b>" + fmt(cur) + "</b> so'm\n\n"
                + "Tuzatish summasini kiriting:\n"
                + "• musbat — qo'shiladi (masalan <code>500000</code>)\n"
                + "• manfiy — ayriladi (masalan <code>-500000</code>)";
        if (msgId > 0) sender.edit(chatId, msgId, txt);
        else sender.send(chatId, txt);
    }

    private void krSum(Session s, String text, long chatId) {
        String t = text.trim();
        boolean neg = t.startsWith("-");
        long v = parseAmount(t);
        if (v <= 0) {
            sender.send(chatId, "⚠️ Nolga teng bo'lmagan summa kiriting, masalan "
                    + "<code>500000</code> yoki <code>-500000</code>");
            return;
        }
        s.data.put("krSum", neg ? -v : v);
        s.state = Session.State.ADM_KR_IZOH;
        sender.send(chatId, "Summa: <b>" + fmt(neg ? -v : v) + "</b> so'm\n\n"
                + "✍️ Sababini yozing (auditda saqlanadi):", cancelOnly());
    }

    /** Sabab olindi — hammasi tayyor, korrektirovka bajariladi. */
    private void krIzoh(AppUser u, Session s, String text, long chatId) {
        s.data.put("krReason", text.trim().equals("-") ? "Korrektirovka" : text.trim());
        krCommit(u, s, chatId);
    }

    private void krCommit(AppUser u, Session s, long chatId) {
        OwnerType ot = (OwnerType) s.data.get("krT");
        long oid = s.getLong("krId");
        MoneyType mt = (MoneyType) s.data.get("krMt");
        long sum = s.getLong("krSum");
        java.time.LocalDate date = java.time.LocalDate.parse(s.getStr("krDate"));
        String hhmm = s.getStr("krVaqt");
        String reasonBase = s.getStr("krReason");
        // Soat operations jadvalida alohida ustunsiz — izoh oxiriga qayd etiladi
        String reason = reasonBase + " [" + date.format(DF) + " " + hhmm + "]";
        s.reset();

        ledger.postAdjustment(OpType.KORREKTIROVKA, ot, oid, mt, sum, reason, u.getId(), date);
        long after = ledger.view(ot, oid, mt).getAmount();
        String owner = names.owner(ot, oid);

        sender.send(chatId, "✅ <b>Korrektirovka bajarildi</b> — " + esc(owner) + "\n"
                + mtLabel(mt) + ": <b>" + (sum > 0 ? "+" : "") + fmt(sum) + "</b> so'm\n"
                + "📅 Sana: <b>" + date.format(DF) + " " + hhmm + "</b>\n"
                + "Yangi balans: <b>" + fmt(after) + "</b> so'm\n"
                + "Sabab: " + esc(reasonBase));

        String info = "🛠 Korrektirovka — <b>" + esc(owner) + "</b>: <b>"
                + (sum > 0 ? "+" : "") + fmt(sum) + "</b> so'm (" + mtLabel(mt) + ")\n"
                + "📅 Sana: <b>" + date.format(DF) + " " + hhmm + "</b>\n"
                + "Yangi balans: <b>" + fmt(after) + "</b> so'm\n"
                + "Sabab: " + esc(reason) + "\nKim: " + esc(u.getFullName());
        notify.toBuxgalteriya(info, null);
        if (ot == OwnerType.KASSA) notify.toKassa(oid, info, null);
    }

    /* ==================================================================
     * 💸 KASSA NOMIDAN RASXOD (Buxgalter/Admin) — hisobot qabulida yoki
     * kassa kartasidan: pul turi -> sana -> summa -> kategoriya -> izoh.
     * Darhol TASDIQLANGAN yoziladi (kassir tasdig'i so'ralmaydi).
     * ================================================================== */

    /** a:krx:<op>[:arg] — s: boshlash, m: pul turi, d: sana, c: kategoriya. */
    private void krxCb(AppUser u, Session s, String arg, long chatId, int msgId) {
        String[] a = arg.split(":", 2);
        switch (a[0]) {
            case "s" -> krxStart(s, Long.parseLong(a[1]), null, chatId, 0);
            case "m" -> krxMt(s, a[1], chatId, msgId);
            case "d" -> {
                if (s.data.get("krxMt") == null) return;
                java.time.LocalDate d = a[1].startsWith("e")
                        ? java.time.LocalDate.ofEpochDay(Long.parseLong(a[1].substring(1)))
                        : ledger.today().minusDays(Long.parseLong(a[1]));
                krxDateChosen(s, d, chatId, msgId);
            }
            case "c" -> krxCat(s, Long.parseLong(a[1]), chatId, msgId);
        }
    }

    /** Hisobot xabaridagi «💸 Rasxod kiritish» tugmasi (Router sb:x dan chaqiriladi). */
    public void krxStartForSub(AppUser u, Session s, Submission sub, long chatId) {
        List<Long> epochs = new ArrayList<>();
        dayRepo.findAllById(sub.getDayIds()).stream()
                .sorted((x, y) -> x.getDate().compareTo(y.getDate()))
                .forEach(d -> epochs.add(d.getDate().toEpochDay()));
        krxStart(s, sub.getKassaId(), epochs, chatId, 0);
    }

    private void krxStart(Session s, long kassaId, List<Long> dayEpochs, long chatId, int msgId) {
        String nav = s.getStr("nav");
        Object pm = s.data.get("panelMsg");
        Object cm = s.data.get("contentMsg");
        s.reset();
        if (nav != null) s.data.put("nav", nav);       // panel navigatsiyasi saqlanadi
        if (pm != null) s.data.put("panelMsg", pm);
        if (cm != null) s.data.put("contentMsg", cm);
        s.data.put("krxKassa", kassaId);
        if (dayEpochs != null && !dayEpochs.isEmpty()) s.data.put("krxDates", dayEpochs);
        String txt = "💸 <b>Rasxod kiritish</b> — 🏪 "
                + esc(names.owner(OwnerType.KASSA, kassaId)) + " nomidan\n\n"
                + "Pul turini tanlang:\n"
                + "<i>📲 Klik tanlansa — kassaning o'z Klik hisobidan ayriladi.</i>";
        InlineKeyboardMarkup kb = inline(List.of(
                irow(btn("💵 Naqd", "a:krx:m:NAQD"), btn("📲 Klik", "a:krx:m:KLIK")),
                irow(btn("❌ Bekor", "cx"))));
        if (msgId > 0) sender.edit(chatId, msgId, txt, kb);
        else sender.send(chatId, txt, kb);
    }

    private void krxMt(Session s, String mt, long chatId, int msgId) {
        if (s.data.get("krxKassa") == null) return;
        s.data.put("krxMt", mt);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        Object dates = s.data.get("krxDates");
        if (dates instanceof List<?> ds && !ds.isEmpty()) {
            // Hisobotdagi kunlar — rasxod o'sha sanalar bo'yicha kiritiladi
            List<InlineKeyboardButton> row = new ArrayList<>();
            for (Object o : ds) {
                java.time.LocalDate d = java.time.LocalDate.ofEpochDay(((Number) o).longValue());
                row.add(btn(d.format(DF), "a:krx:d:e" + d.toEpochDay()));
                if (row.size() == 3) { rows.add(row); row = new ArrayList<>(); }
            }
            if (!row.isEmpty()) rows.add(row);
        } else {
            rows.add(irow(btn("📅 Bugun", "a:krx:d:0"), btn("Kecha", "a:krx:d:1")));
            rows.add(irow(btn("🗓 Kalendar", "a:cal:o:krx")));
        }
        rows.add(irow(btn("❌ Bekor", "cx")));
        sender.edit(chatId, msgId, "💸 " + mtLabel(MoneyType.valueOf(mt))
                + " — 🏪 " + esc(names.owner(OwnerType.KASSA, s.getLong("krxKassa")))
                + "\n\n📅 <b>Rasxod qaysi sana uchun?</b>", inline(rows));
    }

    private void krxDateChosen(Session s, java.time.LocalDate d, long chatId, int msgId) {
        if (d.isAfter(ledger.today())) {
            sender.edit(chatId, msgId, "⚠️ Kelajak sanasi bo'lmaydi. Qaytadan boshlang.");
            return;
        }
        s.data.put("krxDate", d.toString());
        s.state = Session.State.ADM_KRX_SUM;
        sender.edit(chatId, msgId, "💸 " + mtLabel(MoneyType.valueOf(s.getStr("krxMt")))
                + " · 📅 " + d.format(DF) + "\n\n<b>Rasxod summasini kiriting</b> (so'm):");
    }

    private void krxSum(Session s, String text, long chatId) {
        long sum = parseAmount(text);
        if (sum <= 0) { sender.send(chatId, "⚠️ Musbat summa kiriting:"); return; }
        s.data.put("krxSum", sum);
        s.state = Session.State.IDLE;   // kategoriya callback orqali keladi
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Category c : categoryRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn(c.getName(), "a:krx:c:" + c.getId())));
        rows.add(irow(btn("❌ Bekor", "cx")));
        sender.send(chatId, "Summa: <b>" + fmt(sum) + "</b> so'm\n\nKategoriyani tanlang:",
                inline(rows));
    }

    private void krxCat(Session s, long catId, long chatId, int msgId) {
        if (s.data.get("krxSum") == null) return;
        s.data.put("krxCat", catId);
        s.data.put("krxCatName", categoryRepo.findById(catId)
                .map(Category::getName).orElse("?"));
        s.state = Session.State.ADM_KRX_CMT;
        sender.edit(chatId, msgId, "Kategoriya: <b>" + esc(s.getStr("krxCatName"))
                + "</b>\n\nIzoh kiriting (shart emas — «-» yuboring):");
    }

    private void krxFinish(AppUser u, Session s, String text, long chatId) {
        long kassaId = s.getLong("krxKassa");
        MoneyType mt = MoneyType.valueOf(s.getStr("krxMt"));
        java.time.LocalDate date = java.time.LocalDate.parse(s.getStr("krxDate"));
        long sum = s.getLong("krxSum");
        Long catId = s.getLong("krxCat");
        String catName = s.getStr("krxCatName");
        String comment = text.trim().equals("-") ? "" : text.trim();
        s.reset();

        Operation op = rasxodService.directForKassa(u, kassaId, mt, sum, catId, comment, date);
        String kassaName = names.owner(OwnerType.KASSA, kassaId);

        StringBuilder ok = new StringBuilder("✅ <b>Rasxod #" + op.getId() + " yozildi</b> — 🏪 "
                + esc(kassaName) + " nomidan\n"
                + "💰 <b>" + fmt(sum) + "</b> so'm (" + mtLabel(mt) + ")\n"
                + "📅 Sana: <b>" + date.format(DF) + "</b> · Kategoriya: " + esc(catName)
                + (comment.isEmpty() ? "" : "\nIzoh: " + esc(comment))
                + "\n✍️ Kiritdi: " + esc(u.getFullName()));
        // Shu kun kutilayotgan hisobotda bo'lsa — yangilangan summani ko'rsatish
        for (Submission sub : subRepo.findByStatusOrderByIdAsc(SubmissionStatus.KUTILMOQDA)) {
            if (!sub.getKassaId().equals(kassaId)) continue;
            ok.append("\n\nℹ️ Hisobot #").append(sub.getId())
              .append(" summasi yangilandi: Naqd <b>").append(fmt(sub.getNaqd()))
              .append("</b> · Click <b>").append(fmt(sub.getKlik())).append("</b> so'm");
            break;
        }
        sender.send(chatId, ok.toString());

        notify.toKassa(kassaId, "💸 <b>Kassangiz nomidan rasxod kiritildi</b>\n"
                + "💰 <b>" + fmt(sum) + "</b> so'm (" + mtLabel(mt) + ")\n"
                + "📅 Sana: " + date.format(DF) + " · Kategoriya: " + esc(catName)
                + (comment.isEmpty() ? "" : "\nIzoh: " + esc(comment))
                + "\n✍️ Kiritdi: " + esc(u.getFullName()), null);
    }

    /* ==================================================================
     * ♻️ НОЛ БОШЛАШ (faqat SuperAdmin) — bugundan oldingi topshirilmagan
     * kunlarni yopib, kassa balanslarini (naqd+klik) 0 dan boshlatish.
     * ================================================================== */

    /** Asosiy (yaratuvchi) SuperAdmin — .env SUPERADMIN_TELEGRAM_ID egasi. */
    private boolean isCreator(AppUser u) {
        if (u.getRole() != Role.SUPERADMIN) return false;
        Long t = props.getSuperadmin().getTelegramId();
        // .env da belgilanmagan bo'lsa — barcha SuperAdminlarga ruxsat (qulf bo'lib qolmasin)
        if (t == null || t <= 0) return true;
        return t.equals(u.getTelegramId());
    }

    private void rzStart(Session s, long chatId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("🏢 Barcha kassalar", "a:rz:all")));
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn("🏪 " + k.getName(), "a:rz:K" + k.getId())));
        rows.add(irow(btn("❌ Bekor", "cx")));
        sendContent(s, chatId, "♻️ <b>Нол бошлаш</b>\n\n"
                + "Bugungi kundan OLDINGI barcha topshirilmagan kunlar «qabul qilingan» deb "
                + "yopiladi va ularning qoldig'i kassa balansidan (Naqd va Klik) chiqariladi — "
                + "kassa hisobni <b>0 dan</b> boshlaydi.\n"
                + "Bugungi tushum saqlanadi, butun tarix jurnalda qoladi.\n\n"
                + "Kimni nol qilamiz?", inline(rows));
    }

    private List<Kassa> rzTargets(String arg) {
        if (arg.equals("all")) return kassaRepo.findByActiveTrueOrderByIdAsc();
        if (arg.startsWith("K"))
            return kassaRepo.findById(Long.parseLong(arg.substring(1)))
                    .map(List::of).orElse(List.of());
        return List.of();
    }

    /** Tanlov qilindi — nima yopilishini ko'rsatib tasdiqlash so'raladi. */
    private void rzPick(Session s, String arg, long chatId, int msgId) {
        List<Kassa> targets = rzTargets(arg);
        if (targets.isEmpty()) return;
        java.time.LocalDate today = ledger.today();
        StringBuilder sb = new StringBuilder("♻️ <b>Нол бошлаш — tasdiqlash</b>\n"
                + "📅 <b>" + today.format(DF) + "</b> dan oldingi kunlar yopiladi:\n");
        long tn = 0, tk = 0;
        int td = 0;
        for (Kassa k : targets) {
            long n = 0, kl = 0;
            int c = 0;
            for (DayRecord d : dayRepo.findByKassaIdAndStatusInOrderByDateAsc(k.getId(),
                    List.of(DayStatus.OCHIQ, DayStatus.YOPILGAN)))
                if (d.getDate().isBefore(today)) { n += d.remainNaqd(); kl += d.remainKlik(); c++; }
            tn += n; tk += kl; td += c;
            sb.append("\n🏪 <b>").append(esc(k.getName())).append("</b>: ").append(c)
              .append(" kun — Naqd <b>").append(fmt(n)).append("</b> · Click <b>")
              .append(fmt(kl)).append("</b>");
        }
        sb.append("\n\nJami <b>").append(td).append("</b> kun — Naqd <b>").append(fmt(tn))
          .append("</b> · Click <b>").append(fmt(tk))
          .append("</b> so'm balanslardan chiqariladi.\n\n"
                  + "⚠️ Bu amal ortga qaytarilmaydi (faqat qo'lda korrektirovka bilan). "
                  + "Davom etamizmi?");
        sender.edit(chatId, msgId, sb.toString(), inline(List.of(
                irow(btn("✅ Ha, nol qilinsin", "a:rzc:" + arg)),
                irow(btn("❌ Yo'q", "cx")))));
    }

    private void rzCommit(AppUser u, Session s, String arg, long chatId, int msgId) {
        List<Kassa> targets = rzTargets(arg);
        if (targets.isEmpty()) return;
        java.time.LocalDate today = ledger.today();
        StringBuilder sb = new StringBuilder("♻️ <b>Нол бошлаш bajarildi</b> — "
                + esc(u.getFullName()) + "\n📅 " + today.format(DF) + " dan oldingi kunlar yopildi:\n");
        for (Kassa k : targets) {
            long[] r = ledger.resetKassaBefore(k.getId(), today, u.getId());
            long newN = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.NAQD).getAmount();
            long newK = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.KLIK).getAmount();
            sb.append("\n🏪 <b>").append(esc(k.getName())).append("</b>: ").append(r[2])
              .append(" kun yopildi (Naqd ").append(fmt(r[0])).append(" · Click ").append(fmt(r[1]))
              .append(")\n   Yangi balans: Naqd <b>").append(fmt(newN))
              .append("</b> · Click <b>").append(fmt(newK)).append("</b>");
            notify.toKassa(k.getId(), "♻️ <b>Kassangiz yangi hisobni boshladi</b>\n"
                    + today.format(DF) + " dan oldingi kunlar yopildi.\n"
                    + "Joriy balans: Naqd <b>" + fmt(newN) + "</b> · Click <b>" + fmt(newK)
                    + "</b> so'm", null);
        }
        sender.edit(chatId, msgId, sb.toString());
        notify.toBuxgalteriya("♻️ Нол бошлаш — " + esc(u.getFullName())
                + " kassalarni yangi hisobga o'tkazdi (" + today.format(DF)
                + " dan oldingi kunlar yopildi).", null);
    }

    /* ==================== 👤 FOYDALANUVCHILAR ==================== */

    private void listUsers(AppUser me, long chatId) {
        List<AppUser> users = userRepo.findByActiveTrueOrderByRoleAscIdAsc();
        StringBuilder sb = new StringBuilder("👤 <b>Faol foydalanuvchilar</b>\n\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (AppUser x : users) {
            String where = x.getKassaId() == null ? "" :
                    " — " + names.owner(OwnerType.KASSA, x.getKassaId());
            sb.append("• <b>").append(esc(x.getFullName())).append("</b> (")
              .append(x.getRole()).append(esc(where)).append(") — <code>")
              .append(x.getTelegramId() == null ? "tg ulanmagan" : x.getTelegramId())
              .append("</code>\n");
            if (!x.getId().equals(me.getId()) && rows.size() < 12)
                rows.add(irow(btn("🚫 " + x.getFullName(), "a:ux:" + x.getId())));
        }
        sb.append("\nFaolsizlantirish uchun tugmani bosing:");
        sender.send(chatId, sb.toString(), rows.isEmpty() ? null : inline(rows));
    }

    private void deactivate(AppUser me, long userId, long chatId, int msgId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null || x.getId().equals(me.getId())) return;
        x.setActive(false);
        userRepo.save(x);
        sender.edit(chatId, msgId, "🚫 <b>" + esc(x.getFullName())
                + "</b> faolsizlantirildi. U endi botdan foydalana olmaydi.");
    }
}
