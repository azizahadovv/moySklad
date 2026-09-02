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
    private final LabelService labelSvc;
    private final PermService permSvc;
    private final uz.kassa.config.AppProps props;
    private final uz.kassa.repo.SubmissionRepo subRepo;
    private final uz.kassa.repo.CategoryRepo categoryRepo;
    private final uz.kassa.service.BalansService balansSvc;
    private final uz.kassa.service.SettingsService settings;
    private final uz.kassa.scheduler.Jobs jobs;

    private static final java.time.format.DateTimeFormatter DF =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /* ============================ MATN ============================ */

    public boolean onText(AppUser u, Session s, String text, long chatId) {
        if (u.getRole() == Role.KASSIR) return false;

        // 💰 Pul qabul qilish: summa kiritish (Buxgalter ham, Admin ham)
        if (s.state == Session.State.ADM_QB_SUM) { qbSum(u, s, text, chatId); return true; }

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
            case ADM_LB_NAME -> { labelName(s, text, chatId); return true; }
            case ADM_MS_TOKEN -> { msTokenSave(u, s, text, chatId); return true; }
            case ADM_NM_NAME -> { nmNameSave(u, s, text, chatId); return true; }
            case ADM_CG_ID -> { cgIdSave(u, s, text, chatId); return true; }
            case ADM_CG_FOOTER -> { cgFooterSave(u, s, text, chatId); return true; }
            case ADM_LS_DATE -> { lsSave(u, s, text, chatId); return true; }
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
                // Foydalanuvchi qarori: Баланс bosilganda DOIM avval НАҚД oynasi ochiladi
                // (КЛИК/ЖАМИ — pastdagi tugmalar orqali)
                sendContent(s, chatId, balansSvc.buildAll(uz.kassa.service.BalansService.NAQD),
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
                && !java.util.Set.of("p", "qbu", "qbd", "cal", "bl", "rxm").contains(cmd))
            return false;

        // Ҳуқуқлар — barcha SuperAdmin'larga ochiq; lekin SUPERADMIN'ga tegadigan
        // amallar (rol berish/olish, faolsizlantirish) faqat asosiy (yaratuvchi)
        // SuperAdmin uchun — target-tekshiruvlar tegishli metodlarda.
        if (java.util.Set.of("prm", "prc", "prs", "prt", "prko", "prk", "prq",
                        "prr", "prz", "prx", "prxy").contains(cmd)
                && u.getRole() != Role.SUPERADMIN) {
            sender.send(chatId, "⚠️ Ҳуқуқлар bo'limi faqat SuperAdmin uchun.");
            return true;
        }

        switch (cmd) {
            case "p" -> panel(u, s, arg, chatId, msgId);
            case "qbu" -> qbUser(u, s, arg, chatId, msgId);
            case "qbd" -> qbDate(u, s, arg, chatId, msgId);
            case "cal" -> calCb(u, s, arg, chatId, msgId);
            case "rxm" -> rasxodMenu(s, chatId, msgId);
            case "audm" -> auditMenu(s, chatId, msgId);
            case "aud" -> auditView(s, Long.parseLong(arg), chatId, msgId);
            case "aux" -> auditExcel(Long.parseLong(arg), chatId);
            case "lbm" -> labelList(s, chatId, msgId);
            case "lb" -> labelPick(s, Integer.parseInt(arg), chatId, msgId);
            case "lbr" -> labelRenameStart(s, Integer.parseInt(arg), chatId, msgId);
            case "lbh" -> labelHideToggle(s, Integer.parseInt(arg), chatId, msgId);
            case "msk" -> msToken(s, chatId, msgId);
            case "msr" -> msNamesMenu(chatId, msgId);
            case "msrp" -> msNamesPreview(chatId, msgId);
            case "msry" -> msNamesApply(u, chatId, msgId);
            case "msn" -> msNameList(chatId, msgId);
            case "msni" -> msNameItem(arg, chatId, msgId);
            case "msne" -> msNameEditStart(s, arg, chatId, msgId);
            case "msnu" -> msNameUnlock(u, arg, chatId, msgId);
            case "prm" -> permMenu(s, chatId, msgId);
            case "prc" -> permCard(u, Long.parseLong(arg), chatId, msgId);
            case "prs" -> permGrid("user", Long.parseLong(arg), chatId, msgId);
            case "prt" -> permToggle(u, "user", arg, chatId, msgId);
            case "prko" -> permKassaList(chatId, msgId);
            case "prk" -> permGrid("kassa", Long.parseLong(arg), chatId, msgId);
            case "prq" -> permToggle(u, "kassa", arg, chatId, msgId);
            case "prr" -> permRolePick(u, Long.parseLong(arg), chatId, msgId);
            case "prz" -> permRoleApply(u, arg, chatId, msgId);
            case "prx" -> permDeactConfirm(u, Long.parseLong(arg), chatId, msgId);
            case "prxy" -> {
                deactivate(u, Long.parseLong(arg), chatId, msgId);
                permMenu(s, chatId, 0);
            }
            case "mske" -> {
                s.state = Session.State.ADM_MS_TOKEN;
                sender.edit(chatId, msgId, "🔑 <b>Yangi MoySklad API kalitini yuboring</b>\n\n"
                        + "MoySklad → Sozlamalar → Tokenlar bo'limidan olinadi.\n"
                        + "Bekor qilish uchun «-» yuboring.");
            }
            case "cg" -> clickGroupMenu(s, chatId, msgId);
            case "kml" -> kartaMasList(chatId, msgId);
            case "kmc" -> kartaMasCard(Long.parseLong(arg), chatId, msgId);
            case "kmu" -> kartaMasUsers(Long.parseLong(arg), chatId, msgId);
            case "kms" -> kartaMasSet(u, arg, chatId, msgId);
            case "kmx" -> kartaMasClear(u, Long.parseLong(arg), chatId, msgId);
            case "cge" -> {
                s.state = Session.State.ADM_CG_ID;
                sender.edit(chatId, msgId, "📣 <b>Гуруҳлар/Каналлар — yangi chat qo'shish</b>\n\n"
                        + "1) Botni (@" + esc(props.getBot().getUsername()) + ") kerakli guruhga "
                        + "(a'zo yetarli) yoki kanalga (ADMIN shart) qo'shing.\n"
                        + "2) Shu chatning ID sini yuboring (odatda manfiy son, mas. -1001234567890).\n\n"
                        + "Yoki oddiyroq yo'l: o'sha guruhning o'zida /setclickgroup buyrug'ini "
                        + "yozing — chat avtomatik ro'yxatga qo'shiladi.\n\n"
                        + "<i>Chat ID sini bilmasangiz — botni guruhga qo'shib, guruhda istalgan "
                        + "xabar yozing, keyin @userinfobot yoki shunga o'xshash vosita bilan ID'ni "
                        + "toping.</i>\n\nBekor qilish uchun «-» yuboring.");
            }
            case "cgt" -> {
                sender.edit(chatId, msgId, "⏳ Test yuborilmoqda...");
                jobs.clickReportNow();
                clickGroupMenu(s, chatId, msgId);
            }
            case "cgx" -> {
                // Eski xabarlardagi argumentsiz tugma bosilsa — shunchaki menyu yangilanadi
                if (!arg.isBlank()) {
                    long gid = Long.parseLong(arg);
                    jobs.removeClickChat(gid);
                    audit.log(u.getId(), "CLICK_GROUP_OCHIRILDI", "settings", null,
                            u.getFullName() + " guruh/kanalni hisobot ro'yxatidan o'chirdi: " + gid);
                }
                clickGroupMenu(s, chatId, msgId);
            }
            case "cgs" -> clickScheduleMenu(s, chatId, msgId);
            case "cgf" -> {
                s.state = Session.State.ADM_CG_FOOTER;
                String cur = jobs.clickFooter();
                sender.edit(chatId, msgId, "✍️ <b>Hisobot ostiga qo'shiladigan matn</b>\n\n"
                        + (cur.isEmpty() ? "Hozir: <i>yo'q</i>" : "Hozir: <code>" + esc(cur) + "</code>")
                        + "\n\nYangi matnni yozib yuboring — u har bir Click hisoboti "
                        + "OSTIDA chiqadi. Ichida:\n"
                        + "• <code>{hamma}</code> — guruhning BARCHA ma'lum a'zolarini belgilaydi "
                        + "(bot guruhda yozgan/qo'shilgan har kimni eslab boradi — ro'yxat vaqt "
                        + "o'tishi bilan to'ladi)\n"
                        + "• <code>{adminlar}</code> — guruh/kanal adminlarini bot O'ZI topib belgilaydi\n"
                        + "• <code>{xodimlar}</code> — botda ro'yxatdagi va shu guruhga a'zo xodimlarni "
                        + "avtomatik belgilaydi\n"
                        + "• <code>@username</code> — aniq bir odamga eslatma (mention)\n"
                        + "• <code>{id=123456789;Ism}</code> — username'siz odamni Telegram ID "
                        + "orqali belgilash (ID'ni @userinfobot beradi)\n\n"
                        + "Masalan: <code>Hisobotni tekshiring: {adminlar}</code>\n\n"
                        + "Olib tashlash uchun «-» yuboring.");
            }
            case "cgi" -> {
                settings.set(uz.kassa.scheduler.Jobs.CLICK_EVERY_KEY, arg);
                audit.log(u.getId(), "CLICK_JADVAL", "settings", null,
                        u.getFullName() + " hisobot intervalini o'zgartirdi: har " + arg + " soatda");
                clickScheduleMenu(s, chatId, msgId);
            }
            case "cgo" -> {   // minut siljishi: -20…+20
                settings.set(uz.kassa.scheduler.Jobs.CLICK_OFFSET_KEY, arg);
                audit.log(u.getId(), "CLICK_JADVAL", "settings", null,
                        u.getFullName() + " hisobot minut siljishini o'zgartirdi: " + arg + " min");
                clickScheduleMenu(s, chatId, msgId);
            }
            case "cgw" -> {
                String[] w = arg.split(":");
                settings.set(uz.kassa.scheduler.Jobs.CLICK_FROM_KEY, w[0]);
                settings.set(uz.kassa.scheduler.Jobs.CLICK_TO_KEY, w[1]);
                audit.log(u.getId(), "CLICK_JADVAL", "settings", null,
                        u.getFullName() + " hisobot oynasini o'zgartirdi: " + w[0] + ":00–" + w[1] + ":00");
                clickScheduleMenu(s, chatId, msgId);
            }
            case "lsm" -> ledgerMenu(s, chatId, msgId);
            case "lse" -> {
                s.state = Session.State.ADM_LS_DATE;
                sender.edit(chatId, msgId, "📅 <b>Ledger boshlanish sanasi</b>\n\n"
                        + "Yangi sanani yuboring (masalan <code>2026-08-26</code> yoki "
                        + "<code>26.08.2026</code>).\n\n"
                        + "⚠️ <b>DIQQAT:</b> bu sanadan OLDINGI, bazada yo'q MoySklad hujjatlari "
                        + "sinxronga olinmaydi. Sanani ORQAGA surish eski hujjatlarni qayta "
                        + "«kashf qilib» balanslarni ikki marta hisoblashi mumkin — faqat nima "
                        + "qilayotganingizni aniq bilsangiz o'zgartiring.\n\n"
                        + "Bekor qilish uchun «-» yuboring.");
            }
            case "lsx" -> {
                settings.set(uz.kassa.service.moysklad.MoySkladSyncService.LEDGER_START_KEY, "");
                audit.log(u.getId(), "LEDGER_SANA", "settings", null,
                        u.getFullName() + " ledger sanasini .env qiymatiga qaytardi");
                ledgerMenu(s, chatId, msgId);
            }
            case "diag" -> diagMenu(s, chatId, msgId);
            case "rld" -> reloadConfirm(s, chatId, msgId);
            case "rldc" -> reloadCommit(u, chatId, msgId);
            case "fixo" -> {
                s.reset();
                s.state = Session.State.ADM_KR_OWNER;
                krOwner(s, arg, chatId, msgId);
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
            case "krok" -> krCommit(u, s, chatId, msgId);
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
                    "🛠 Корректировка", "📋 Аудит",
                    "🏷 Тугма номлари", "🔑 MoySklad API", "🔄 Номлар (MoySklad)", "👁 Ҳуқуқлар",
                    "📣 Гуруҳлар/Каналлар", "💳 Карта масъуллари", "📅 Ledger санаси",
                    "🩺 Диагностика", "📥 Қайта юклаш", "♻️ Нол бошлаш");
    private static final List<String> STAT_MENU =
            List.of("🏪 Кассалар холати", "🧾 Карзлар реестр", "📜 История",
                    "👥 Фойдаланувчилар умумий",
                    "🏦 Бухгалтерия", "💼 Салдо", "📲 Кликлар", "📊 Свод");
    private static final List<String> SOZUSER_MENU =
            List.of("➕ Фойдаланувчи қўшиш", "🔄 Рол ўзгартириш", "🚫 Фойдаланувчини ўчириш");

    private static final List<String> SOZKASSA_MENU =
            List.of("➕ Касса қўшиш", "🗂 Отдел боғлаш", "🚫 Касса ўчириш");

    /** Panel nomi va bo'limlari — rol kesimida. */
    private String panelTitle(AppUser u) {
        return "🏪 <b>KASSA</b>\n\nBo'limni tanlang:";
    }

    private List<String> panelLabels(AppUser u) {
        return u.getRole() == Role.SUPERADMIN
                ? List.of("🏬 Отдел", "⚙️ Настройка", "📈 Статистика",
                          "💰 Бугунги тушум", "🧾 Расходлар", "🏪 Кассалар холати")
                : List.of("🏬 Отдел", "📈 Статистика", "💰 Бугунги тушум",
                          "🧾 Расходлар", "🏪 Кассалар холати");
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
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            if (!k.isCashless()) out.add("🏪 " + k.getName());
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
                    case "🧾 Расходлар" -> rasxodMenu(s, chatId, 0);
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
                            SOZKASSA_MENU);
                    case "👥 Фойдаланувчилар" -> navTo(u, s, "sozuser", chatId,
                            "👥 <b>Фойдаланувчилар</b>", SOZUSER_MENU);
                    case "💼 Бошланғич қолдиқ" -> { ibStart(s, chatId); s.data.put("nav", "sozlash"); }
                    case "🛠 Корректировка" -> { krStart(s, chatId); s.data.put("nav", "sozlash"); }
                    case "📋 Аудит" -> auditMenu(s, chatId, 0);
                    case "🏷 Тугма номлари" -> labelList(s, chatId, 0);
                    case "🔑 MoySklad API" -> msToken(s, chatId, 0);
                    case "🔄 Номлар (MoySklad)" -> msNamesMenu(chatId, 0);
                    case "📣 Гуруҳлар/Каналлар" -> clickGroupMenu(s, chatId, 0);
                    case "💳 Карта масъуллари" -> kartaMasList(chatId, 0);
                    case "📅 Ledger санаси" -> ledgerMenu(s, chatId, 0);
                    case "🩺 Диагностика" -> diagMenu(s, chatId, 0);
                    case "📥 Қайта юклаш" -> reloadConfirm(s, chatId, 0);
                    // Ҳуқуқлар — barcha SuperAdmin'larga ochiq; SUPERADMIN'larga tegadigan
                    // amallar ichkarida faqat yaratuvchiga ko'rsatiladi/ruxsat etiladi.
                    case "👁 Ҳуқуқлар" -> permMenu(s, chatId, 0);
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
                    case "🗂 Отдел боғлаш" -> kassaOtdelList(chatId, 0);
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
                    String block = kassaDeactivateBlock(id);
                    if (block != null) {
                        navTo(u, s, "sozkassa", chatId, block, SOZKASSA_MENU);
                        return true;
                    }
                    kassaRepo.findById(id).ifPresent(k -> { k.setActive(false); kassaRepo.save(k); });
                    navTo(u, s, "sozkassa", chatId, "🚫 Kassa faolsizlantirildi",
                            SOZKASSA_MENU);
                } else if (text.startsWith("❌")) {
                    navTo(u, s, "sozkassa", chatId, "🏪 <b>Касса</b>",
                            SOZKASSA_MENU);
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
                if (!applyRole(u, idOf(nav), text, chatId)) return false;
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

    private boolean applyRole(AppUser actor, long userId, String text, long chatId) {
        Role newRole; Long kassaId = null;
        if (text.startsWith("👤 Kassir — ")) {
            Kassa k = kassaByLabel(text.substring("👤 Kassir — ".length()));
            if (k == null) return false;
            newRole = Role.KASSIR; kassaId = k.getId();
        } else if (text.equals("🧮 Buxgalter")) newRole = Role.BUXGALTER;
        else if (text.equals("👑 SuperAdmin")) newRole = Role.SUPERADMIN;
        else return false;
        return applyRoleDirect(actor, userId, newRole, kassaId, chatId);
    }

    /** Bu user .env dagi asosiy (yaratuvchi) SuperAdmin ID egasimi. */
    private boolean isCreatorId(AppUser x) {
        Long t = props.getSuperadmin().getTelegramId();
        return t != null && t > 0 && t.equals(x.getTelegramId());
    }

    private boolean applyRoleDirect(AppUser actor, long userId, Role newRole, Long kassaId,
                                    long chatId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null) return false;

        // Asosiy (yaratuvchi) SuperAdmin rolini pasaytirib bo'lmaydi — hatto o'zi ham:
        // aks holda SuperAdmin berish huquqi egasiz qolib, tizim qulflanadi
        if (isCreatorId(x) && newRole != Role.SUPERADMIN) {
            sender.send(chatId, "⚠️ Asosiy (yaratuvchi) SuperAdmin rolini pasaytirib "
                    + "bo'lmaydi — tizim boshqaruvsiz qolmasligi uchun. Bu himoya "
                    + "hammaga, jumladan o'zingizga ham amal qiladi.");
            return true;
        }
        // Admin (SuperAdmin) maqomini berish/olish — faqat asosiy (yaratuvchi) SuperAdmin
        if ((x.getRole() == Role.SUPERADMIN || newRole == Role.SUPERADMIN) && !isCreator(actor)) {
            sender.send(chatId, "⚠️ SuperAdmin maqomini berish yoki olishni faqat asosiy "
                    + "(yaratuvchi) SuperAdmin qila oladi.");
            return true;
        }
        // Oxirgi SuperAdmin'ni pasaytirib bo'lmaydi — tizim egasiz qolmasin
        if (x.getRole() == Role.SUPERADMIN && newRole != Role.SUPERADMIN
                && userRepo.findByRoleAndActiveTrue(Role.SUPERADMIN).size() <= 1) {
            sender.send(chatId, "⚠️ Bu oxirgi SuperAdmin — rolini o'zgartirib bo'lmaydi. "
                    + "Avval boshqa SuperAdmin tayinlang.");
            return true;
        }

        Role oldRole = x.getRole();
        x.setRole(newRole);
        x.setKassaId(kassaId);
        userRepo.save(x);
        audit.log(actor.getId(), "ROL_OZGARTIRILDI", "user", x.getId(),
                actor.getFullName() + ": " + x.getFullName() + " " + oldRole + " → " + newRole
                        + (kassaId == null ? "" : " (" + names.owner(OwnerType.KASSA, kassaId) + ")"));
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
                    SOZKASSA_MENU);
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
            case "sko"  -> kassaOtdelList(chatId, msgId);
            case "skg"  -> kassaOtdelMenu(Long.parseLong(a[1]), chatId, msgId);
            case "skgs" -> kassaOtdelSet(u, Long.parseLong(a[1]), a[2], false, chatId, msgId);
            case "skgm" -> kassaOtdelSet(u, Long.parseLong(a[1]), a[2], true, chatId, msgId);
            case "skgx" -> kassaOtdelClearConfirm(Long.parseLong(a[1]), chatId, msgId);
            case "skgy" -> kassaOtdelClear(u, Long.parseLong(a[1]), chatId, msgId);
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
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;   // B5: pul yuritilmaydigan kassa panelda ko'rinmaydi
            rows.add(irow(btn("🏪 " + k.getName(), "a:p:k:" + k.getId())));
        }
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
        sendContent(s, chatId, sb.toString(), null);
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
        String extra = "";
        if (MoneyType.valueOf(mt) == MoneyType.NAQD) {
            long avail = ledger.view(OwnerType.KASSA, kassaId, MoneyType.NAQD).available();
            extra = "\n💼 Kassada mavjud: <b>" + fmt(avail) + "</b> so'm"
                    + (avail <= 0 ? "\n⚠️ Mavjud pul yo'q — qabul o'tmaydi." : "");
        }
        sender.edit(chatId, msgId, "💰 " + esc(names.owner(OwnerType.KASSA, kassaId))
                + " — " + mtLabel(MoneyType.valueOf(mt)) + extra
                + "\n\n<b>Olingan summani kiriting</b> (so'm):");
    }

    private void qbSum(AppUser u, Session s, String text, long chatId) {
        long sum = parseAmount(text);
        if (sum <= 0) { sender.send(chatId, "⚠️ Musbat summa kiriting:"); return; }
        // NAQD: mavjud qoldiqdan ko'p qabul qilib bo'lmaydi (balans 0 — pul yo'q)
        if (MoneyType.valueOf(s.getStr("qbMt")) == MoneyType.NAQD) {
            long avail = ledger.view(OwnerType.KASSA, s.getLong("qbKassa"), MoneyType.NAQD).available();
            if (sum > avail) {
                sender.send(chatId, "⚠️ Kassada mavjud: <b>" + fmt(avail) + "</b> so'm — "
                        + "undan ko'p qabul qilib bo'lmaydi.\n"
                        + "Agar pul haqiqatan kassada bo'lsa — avval MoySklad kirim "
                        + "hujjatlarini tekshiring.\nBoshqa summa kiriting:");
                return;
            }
        }
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
        // Avval amal — xato bo'lsa (mavjud yetarli emas / kutilayotgan hisobot bor)
        // sessiya saqlanib qoladi va foydalanuvchi xabarni ko'radi
        var op = submissionService.directCollect(kassaId, mt, sum, u, topshirgan, date);
        String nav = s.getStr("nav");
        Object pm = s.data.get("panelMsg");
        s.reset();
        if (nav != null) s.data.put("nav", nav);   // panel navigatsiyasi saqlanadi
        if (pm != null) s.data.put("panelMsg", pm);
        s.data.put("contentMsg", msgId);           // tasdiq xabari — joriy kontent
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
                irow(btn("📋 Аудит", "a:audm")),
                irow(btn("🏷 Тугма номлари", "a:lbm"), btn("🔑 MoySklad API", "a:msk")),
                irow(btn("🔄 Номлар (MoySklad)", "a:msr")),
                irow(btn("👁 Ҳуқуқлар", "a:prm"), btn("📣 Гуруҳлар/Каналлар", "a:cg")),
                irow(btn("💳 Карта масъуллари", "a:kml")),
                irow(bk("a:p:main"))));
    }

    /* ---------- 💳 KARTA MAS'ULLARI (kim qaysi otdel kartasiga biriktirilgan) ---------- */

    private void kartaMasList(long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
            String otdel = c.getKassaId() == null ? "—"
                    : kassaRepo.findById(c.getKassaId()).map(Kassa::getName).orElse("?");
            String mas = kartaMasName(c);
            rows.add(irow(btn("💳 " + c.getName() + " · " + otdel + " · " + mas,
                    "a:kmc:" + c.getId())));
        }
        rows.add(irow(bk("a:p:set")));
        String text = "💳 <b>Карта масъуллари</b>\n\n"
                + "Karta → otdel → mas'ul. O'zgartirish uchun kartani bosing:";
        // Reply-menyu (Настройка) yo'lidan ham TUGMALAR bilan ochilsin (show() 0 da
        // inline'siz yuborardi — bu bo'limda tugmasiz ma'no yo'q)
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sender.send(chatId, text, inline(rows));
    }

    /** Mas'ul yorlig'i: {id=..;Ism} / @username / — . */
    private String kartaMasName(ClickAccount c) {
        String r = c.getCardResponsible();
        if (r == null || r.isBlank()) return "mas'ul yo'q";
        var m = java.util.regex.Pattern.compile("\\{id=(\\d+);([^}]+)\\}").matcher(r);
        return m.find() ? m.group(2) : r;
    }

    private void kartaMasCard(long id, long chatId, int msgId) {
        ClickAccount c = clickRepo.findById(id).orElse(null);
        if (c == null) { kartaMasList(chatId, msgId); return; }
        String otdel = c.getKassaId() == null ? "biriktirilmagan"
                : kassaRepo.findById(c.getKassaId()).map(Kassa::getName).orElse("?");
        String r = c.getCardResponsible();
        var m = java.util.regex.Pattern.compile("\\{id=(\\d+);([^}]+)\\}")
                .matcher(r == null ? "" : r);
        String masLine = (r == null || r.isBlank()) ? "<i>biriktirilmagan</i>"
                : m.find() ? "<b>" + esc(m.group(2)) + "</b> · ID: <code>" + m.group(1) + "</code>"
                : "<b>" + esc(r) + "</b>";
        show(chatId, msgId, "💳 <b>" + esc(c.getName()) + "</b>\n"
                + "🏪 Otdel: <b>" + esc(otdel) + "</b>\n"
                + "👤 Mas'ul: " + masLine + "\n"
                + (c.getCardBalance() == null ? ""
                    : "💰 Karta qoldig'i: <b>" + uz.kassa.bot.TextUtil.fmtTiyin(c.getCardBalance()) + "</b> so'm ("
                      + esc(c.getCardBalanceBy() == null ? "?" : c.getCardBalanceBy()) + ")\n"),
                List.of(
                        irow(btn("👤 Mas'ulni tanlash", "a:kmu:" + id)),
                        irow(btn("🗑 Mas'ulni olib tashlash", "a:kmx:" + id)),
                        irow(bk("a:kml"))));
    }

    private void kartaMasUsers(long cardId, long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int shown = 0;
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc()) {
            if (x.getTelegramId() == null) continue;   // Telegram'siz odam tanlolmaydi
            if (shown++ >= 20) break;
            rows.add(irow(btn("👤 " + x.getFullName(), "a:kms:" + cardId + ":" + x.getId())));
        }
        rows.add(irow(bk("a:kmc:" + cardId)));
        show(chatId, msgId, "👤 <b>Mas'ulni tanlang</b> (user ID bilan biriktiriladi):", rows);
    }

    private void kartaMasSet(AppUser admin, String arg, long chatId, int msgId) {
        String[] p = arg.split(":");
        long cardId = Long.parseLong(p[0]);
        long userId = Long.parseLong(p[1]);
        ClickAccount c = clickRepo.findById(cardId).orElse(null);
        AppUser x = userRepo.findById(userId).orElse(null);
        if (c == null || x == null || x.getTelegramId() == null) {
            kartaMasList(chatId, msgId);
            return;
        }
        c.setCardResponsible("{id=" + x.getTelegramId() + ";" + x.getFullName() + "}");
        clickRepo.save(c);
        audit.log(admin.getId(), "KARTA_MASUL", "click", cardId,
                admin.getFullName() + ": " + c.getName() + " -> " + x.getFullName()
                        + " (tg=" + x.getTelegramId() + ")");
        kartaMasCard(cardId, chatId, msgId);
    }

    private void kartaMasClear(AppUser admin, long cardId, long chatId, int msgId) {
        clickRepo.findById(cardId).ifPresent(c -> {
            c.setCardResponsible(null);
            clickRepo.save(c);
            audit.log(admin.getId(), "KARTA_MASUL", "click", cardId,
                    admin.getFullName() + ": " + c.getName() + " -> mas'ul olib tashlandi");
        });
        kartaMasCard(cardId, chatId, msgId);
    }

    private void setKassa(long chatId, int msgId) {
        show(chatId, msgId, "🏪 <b>Касса</b>", List.of(
                irow(btn("➕ Касса қўшиш", "a:p:sknew")),
                irow(btn("🗂 Отдел боғлаш", "a:p:sko")),
                irow(btn("🚫 Касса ўчириш", "a:p:skd")),
                irow(bk("a:p:set"))));
    }

    /* ---------- 🗂 KASSA–OTDEL BOG'LANISHI ---------- */

    /** Otdel band bo'lgan boshqa FAOL kassalar (o'zidan tashqari). */
    private List<Kassa> otdelHolders(String groupId, long exceptKassaId) {
        List<Kassa> out = new ArrayList<>();
        for (Kassa o : kassaRepo.findByActiveTrueOrderByIdAsc())
            if (o.getId() != exceptKassaId && groupId.equals(o.getMoyskladGroupId())) out.add(o);
        return out;
    }

    private void kassaOtdelList(long chatId, int msgId) {
        Map<String, String> groups = msClient.fetchGroups();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            String g = k.getMoyskladGroupId();
            String cur = (g == null || g.isBlank()) ? "—" : groups.getOrDefault(g, "?");
            rows.add(irow(btn("🏪 " + k.getName() + " · " + cur, "a:p:skg:" + k.getId())));
        }
        rows.add(irow(bk("a:p:sk")));
        String text = "🗂 <b>Отдел боғлаш</b>\n\n"
                + "Har bir kassa yonida hozirgi MoySklad otdeli ko'rsatilgan "
                + "(— bo'lsa bog'lanmagan).\nKassani tanlang:";
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sender.send(chatId, text, inline(rows));
    }

    private void kassaOtdelMenu(long kassaId, long chatId, int msgId) {
        Kassa k = kassaRepo.findById(kassaId).orElse(null);
        if (k == null) { kassaOtdelList(chatId, msgId); return; }
        Map<String, String> groups = msClient.fetchGroups();
        if (groups.isEmpty()) {
            show(chatId, msgId, "⚠️ MoySklad otdellari olinmadi — API kaliti va ulanishni "
                    + "tekshiring (⚙️ Настройка → 🔑 MoySklad API).",
                    List.of(irow(bk("a:p:sko"))));
            return;
        }
        String cur = k.getMoyskladGroupId() == null ? "" : k.getMoyskladGroupId();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Map.Entry<String, String> g : groups.entrySet()) {
            String mark = g.getKey().equals(cur) ? "✅ "
                    : otdelHolders(g.getKey(), kassaId).isEmpty() ? "🗂 " : "🔒 ";
            rows.add(irow(btn(mark + g.getValue(), "a:p:skgs:" + kassaId + ":" + g.getKey())));
        }
        if (!cur.isBlank())
            rows.add(irow(btn("➖ Otdelni olib tashlash", "a:p:skgx:" + kassaId)));
        rows.add(irow(bk("a:p:sko")));
        show(chatId, msgId, "🗂 <b>" + esc(k.getName()) + "</b>\n\n"
                + "Hozirgi otdel: <b>" + (cur.isBlank() ? "—"
                        : esc(groups.getOrDefault(cur, cur))) + "</b>\n\n"
                + "✅ — hozirgisi · 🔒 — boshqa kassada band (bosilsa ko'chirish so'raladi)\n"
                + "Yangi otdelni tanlang:", rows);
    }

    private void kassaOtdelSet(AppUser u, long kassaId, String groupId, boolean move,
                               long chatId, int msgId) {
        Kassa k = kassaRepo.findById(kassaId).orElse(null);
        if (k == null) { kassaOtdelList(chatId, msgId); return; }
        Map<String, String> groups = msClient.fetchGroups();
        String gName = groups.getOrDefault(groupId, groupId);
        List<Kassa> holders = otdelHolders(groupId, kassaId);

        // Har qanday biriktirish oldidan DOIM tasdiq so'raladi (bexosdan bosishdan himoya)
        if (!move) {
            String warn;
            if (holders.isEmpty()) {
                warn = "🗂 <b>" + esc(gName) + "</b> otdeli <b>" + esc(k.getName())
                        + "</b> kassasiga biriktirilsinmi?\n\nMoySklad'ning shu otdeldagi "
                        + "kirim/chiqim hujjatlari endi shu kassaga yoziladi.";
            } else {
                String who = holders.stream().map(Kassa::getName)
                        .reduce((x, y) -> x + ", " + y).orElse("?");
                warn = "⚠️ <b>" + esc(gName) + "</b> otdeli allaqachon <b>" + esc(who)
                        + "</b> kassasiga biriktirilgan.\n\nBitta otdel faqat bitta kassada "
                        + "bo'la oladi. <b>" + esc(k.getName()) + "</b> kassasiga ko'chirilsinmi? "
                        + "(avvalgi kassadan olib tashlanadi)";
            }
            show(chatId, msgId, warn, List.of(
                    irow(btn(holders.isEmpty() ? "✅ Ha, biriktirilsin" : "✅ Ha, ko'chirilsin",
                            "a:p:skgm:" + kassaId + ":" + groupId)),
                    irow(btn("❌ Yo'q", "a:p:skg:" + kassaId))));
            return;
        }
        for (Kassa o : holders) {
            o.setMoyskladGroupId(null);
            kassaRepo.save(o);
            audit.log(u.getId(), "OTDEL_OLIB_TASHLANDI", "kassa", o.getId(),
                    u.getFullName() + " «" + gName + "» otdelini «" + o.getName()
                            + "» kassasidan oldi (ko'chirish)");
        }
        k.setMoyskladGroupId(groupId);
        kassaRepo.save(k);
        audit.log(u.getId(), "OTDEL_BIRIKTIRILDI", "kassa", k.getId(),
                u.getFullName() + " «" + gName + "» otdelini «" + k.getName() + "» kassasiga biriktirdi");
        show(chatId, msgId, "✅ <b>" + esc(gName) + "</b> otdeli <b>" + esc(k.getName())
                + "</b> kassasiga biriktirildi."
                + (holders.isEmpty() ? "" : "\n(Avvalgi kassadan olib tashlandi.)")
                + "\n\nMoySklad hujjatlari endi shu kassaga yoziladi.",
                List.of(irow(bk("a:p:sko"))));
    }

    /** Olib tashlash oldidan tasdiq. */
    private void kassaOtdelClearConfirm(long kassaId, long chatId, int msgId) {
        Kassa k = kassaRepo.findById(kassaId).orElse(null);
        if (k == null) { kassaOtdelList(chatId, msgId); return; }
        String cur = k.getMoyskladGroupId();
        String gName = cur == null ? "—" : msClient.fetchGroups().getOrDefault(cur, cur);
        show(chatId, msgId, "⚠️ <b>" + esc(k.getName()) + "</b> kassasidan <b>" + esc(gName)
                + "</b> otdeli olib tashlansinmi?\n\nKeyin bu kassaga MoySklad'dan avtomatik "
                + "hech narsa tushmaydi — otdel hujjatlari Buxgalteriyaga yoziladi.", List.of(
                irow(btn("✅ Ha, olib tashlansin", "a:p:skgy:" + kassaId)),
                irow(btn("❌ Yo'q", "a:p:skg:" + kassaId))));
    }

    private void kassaOtdelClear(AppUser u, long kassaId, long chatId, int msgId) {
        Kassa k = kassaRepo.findById(kassaId).orElse(null);
        if (k == null) { kassaOtdelList(chatId, msgId); return; }
        String old = k.getMoyskladGroupId();
        k.setMoyskladGroupId(null);
        kassaRepo.save(k);
        audit.log(u.getId(), "OTDEL_OLIB_TASHLANDI", "kassa", k.getId(),
                u.getFullName() + " «" + k.getName() + "» kassasidan otdelni oldi (edi: " + old + ")");
        show(chatId, msgId, "➖ <b>" + esc(k.getName()) + "</b> kassasidan otdel olib tashlandi.\n\n"
                + "⚠️ Endi bu kassaga MoySklad'dan avtomatik hech narsa tushmaydi — "
                + "otdel hujjatlari Buxgalteriyaga yoziladi.",
                List.of(irow(bk("a:p:sko"))));
    }

    /* ---------- 🔄 NOMLARNI MOYSKLAD'DAN YANGILASH ---------- */

    /** MoySklad'dagi joriy nomlar bilan farqlar: [tur, eski, yangi].
     *  Qo'lda qo'yilgan (name_locked) nomlarga TEGILMAYDI. */
    private List<String[]> msNameDiffs(Map<String, String> groups, Map<String, String> accounts) {
        List<String[]> out = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isNameLocked()) continue;
            String g = k.getMoyskladGroupId();
            if (g == null || g.isBlank()) continue;
            String nn = groups.get(g);
            if (nn != null && !nn.isBlank() && !nn.trim().equals(k.getName()))
                out.add(new String[]{"🏪", k.getName(), nn.trim()});
        }
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
            if (c.isNameLocked()) continue;
            String a = c.getMoyskladAccountId();
            if (a == null || a.isBlank()) continue;
            String nn = accounts.get(a);
            if (nn != null && !nn.isBlank() && !nn.trim().equals(c.getName()))
                out.add(new String[]{"📲", c.getName(), nn.trim()});
        }
        return out;
    }

    /** 🔄 Номлар bo'limi bosh menyusi. */
    private void msNamesMenu(long chatId, int msgId) {
        String text = "🔄 <b>Номлар (MoySklad)</b>\n\n"
                + "• <b>MoySklad'dan yangilash</b> — kassa nomlari otdel nomidan, klik "
                + "hisoblari MoySklad hisob nomidan tortiladi (avval farqlar ko'rsatiladi).\n"
                + "• <b>Qo'lda o'zgartirish</b> — istalgan kassa/klik hisobiga o'z nomingizni "
                + "qo'yasiz; bog'lanishlar saqlanadi, MoySklad yangilashi bu nomga TEGMAYDI (🔒).";
        List<List<InlineKeyboardButton>> rows = List.of(
                irow(btn("🔄 MoySklad'dan yangilash", "a:msrp")),
                irow(btn("✏️ Qo'lda nom o'zgartirish", "a:msn")),
                irow(bk("a:p:set")));
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sender.send(chatId, text, inline(rows));
    }

    /** Qo'lda nom o'zgartirish — obyekt tanlash ro'yxati. */
    private void msNameList(long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn("🏪 " + k.getName() + (k.isNameLocked() ? " 🔒" : ""),
                    "a:msni:k" + k.getId())));
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn("📲 " + c.getName() + (c.isNameLocked() ? " 🔒" : ""),
                    "a:msni:c" + c.getId())));
        rows.add(irow(bk("a:msr")));
        show(chatId, msgId, "✏️ <b>Qo'lda nom o'zgartirish</b>\n\n"
                + "🔒 — nomi qo'lda qo'yilgan (MoySklad yangilashi tegmaydi)\n"
                + "Nomini o'zgartirmoqchi bo'lgan kassa yoki klik hisobini tanlang:", rows);
    }

    /** Tanlangan obyekt kartasi: hozirgi nom, bog'lanish, amallar. */
    private void msNameItem(String arg, long chatId, int msgId) {
        boolean isKassa = arg.startsWith("k");
        long id = Long.parseLong(arg.substring(1));
        String name, extra;
        boolean locked;
        if (isKassa) {
            Kassa k = kassaRepo.findById(id).orElse(null);
            if (k == null) { msNameList(chatId, msgId); return; }
            name = k.getName(); locked = k.isNameLocked();
            String g = k.getMoyskladGroupId();
            extra = "Otdel: " + (g == null || g.isBlank() ? "—"
                    : esc(msClient.fetchGroups().getOrDefault(g, g)));
        } else {
            ClickAccount c = clickRepo.findById(id).orElse(null);
            if (c == null) { msNameList(chatId, msgId); return; }
            name = c.getName(); locked = c.isNameLocked();
            extra = "Otdel: " + (c.getKassaId() == null ? "—"
                    : esc(names.owner(OwnerType.KASSA, c.getKassaId())));
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("✏️ Yangi nom kiritish", "a:msne:" + arg)));
        if (locked) rows.add(irow(btn("🔓 MoySklad nomiga qaytarish", "a:msnu:" + arg)));
        rows.add(irow(bk("a:msn")));
        show(chatId, msgId, (isKassa ? "🏪 " : "📲 ") + "<b>" + esc(name) + "</b>"
                + (locked ? " 🔒" : "") + "\n" + extra + "\n\n"
                + (locked ? "Nomi qo'lda qo'yilgan — MoySklad yangilashi unga tegmaydi."
                          : "Nomi MoySklad yangilashida almashishi mumkin. Qo'lda nom "
                            + "qo'ysangiz 🔒 bo'lib himoyalanadi.")
                + "\nBog'lanishlar (otdel/hisob) nom o'zgarganda SAQLANADI.", rows);
    }

    /** Yangi nom kiritishni boshlash. */
    private void msNameEditStart(Session s, String arg, long chatId, int msgId) {
        s.reset();
        s.state = Session.State.ADM_NM_NAME;
        s.data.put("nmTarget", arg);
        sender.edit(chatId, msgId, "✏️ <b>Yangi nomni yozib yuboring</b> (2–40 belgi)\n\n"
                + "Masalan: <code>Карта Тимур</code>\n"
                + "Bekor qilish uchun «-» yuboring.");
    }

    /** Kiritilgan yangi nomni saqlash (name_locked=true bilan). */
    private void nmNameSave(AppUser u, Session s, String text, long chatId) {
        String arg = s.getStr("nmTarget");
        if (text.trim().equals("-") || arg == null) {
            s.reset();
            sender.send(chatId, "❌ Bekor qilindi");
            return;
        }
        String nn = text.trim();
        if (nn.length() < 2 || nn.length() > 40) {
            sender.send(chatId, "⚠️ Nom 2–40 belgi bo'lsin. Qaytadan yozing yoki «-» yuboring.");
            return;
        }
        boolean isKassa = arg.startsWith("k");
        long id = Long.parseLong(arg.substring(1));
        String old;
        if (isKassa) {
            Kassa k = kassaRepo.findById(id).orElse(null);
            if (k == null) { s.reset(); return; }
            old = k.getName();
            k.setName(nn); k.setNameLocked(true);
            kassaRepo.save(k);
        } else {
            ClickAccount c = clickRepo.findById(id).orElse(null);
            if (c == null) { s.reset(); return; }
            old = c.getName();
            c.setName(nn); c.setNameLocked(true);
            clickRepo.save(c);
        }
        s.reset();
        audit.log(u.getId(), "NOM_QOLDA", isKassa ? "kassa" : "click", id,
                u.getFullName() + " nomni o'zgartirdi: «" + old + "» → «" + nn + "»");
        sender.send(chatId, "✅ Nom o'zgartirildi: <b>" + esc(old) + "</b> → <b>" + esc(nn)
                + "</b> 🔒\n\nBog'lanishlar saqlandi. MoySklad nom-yangilashi bu nomga "
                + "tegmaydi. Qaytarish: 🔄 Номлар → ✏️ Qo'lda → 🔓.", null);
    }

    /** Qulfni ochish: MoySklad nomi qaytariladi (topilsa), himoya o'chadi. */
    private void msNameUnlock(AppUser u, String arg, long chatId, int msgId) {
        boolean isKassa = arg.startsWith("k");
        long id = Long.parseLong(arg.substring(1));
        String msg;
        if (isKassa) {
            Kassa k = kassaRepo.findById(id).orElse(null);
            if (k == null) { msNameList(chatId, msgId); return; }
            k.setNameLocked(false);
            String g = k.getMoyskladGroupId();
            String nn = g == null ? null : msClient.fetchGroups().get(g);
            String old = k.getName();
            if (nn != null && !nn.isBlank()) k.setName(nn.trim());
            kassaRepo.save(k);
            msg = nn == null || nn.isBlank()
                    ? "🔓 Himoya olindi (MoySklad'da mos nom topilmadi, nom o'zgarmadi)."
                    : "🔓 MoySklad nomi qaytarildi: <b>" + esc(old) + "</b> → <b>" + esc(nn.trim()) + "</b>";
        } else {
            ClickAccount c = clickRepo.findById(id).orElse(null);
            if (c == null) { msNameList(chatId, msgId); return; }
            c.setNameLocked(false);
            String a = c.getMoyskladAccountId();
            String nn = a == null ? null : msClient.fetchAccounts().get(a);
            String old = c.getName();
            if (nn != null && !nn.isBlank()) c.setName(nn.trim());
            clickRepo.save(c);
            msg = nn == null || nn.isBlank()
                    ? "🔓 Himoya olindi (MoySklad'da mos nom topilmadi, nom o'zgarmadi)."
                    : "🔓 MoySklad nomi qaytarildi: <b>" + esc(old) + "</b> → <b>" + esc(nn.trim()) + "</b>";
        }
        audit.log(u.getId(), "NOM_QULF_OCHILDI", isKassa ? "kassa" : "click", id,
                u.getFullName() + " nom himoyasini oldi");
        show(chatId, msgId, msg, List.of(irow(bk("a:msn"))));
    }

    private void msNamesPreview(long chatId, int msgId) {
        Map<String, String> groups = msClient.fetchGroups();
        Map<String, String> accounts = msClient.fetchAccounts();
        String text;
        List<List<InlineKeyboardButton>> rows;
        if (groups.isEmpty() && accounts.isEmpty()) {
            text = "⚠️ MoySklad'dan ma'lumot olinmadi — API kaliti va ulanishni tekshiring "
                    + "(⚙️ Настройка → 🔑 MoySklad API).";
            rows = List.of(irow(bk("a:msr")));
        } else {
            List<String[]> diffs = msNameDiffs(groups, accounts);
            if (diffs.isEmpty()) {
                text = "✅ Hamma nomlar MoySklad bilan allaqachon mos "
                        + "(🔒 qo'lda qo'yilganlarga tegilmaydi):\n"
                        + "🏪 kassalar — otdel nomlari bilan, 📲 klik hisoblari — "
                        + "MoySklad hisob nomlari bilan.";
                rows = List.of(irow(bk("a:msr")));
            } else {
                StringBuilder sb = new StringBuilder("🔄 <b>Номларни MoySklad'дан янгилаш</b>\n\n"
                        + "Quyidagi nomlar MoySklad'dagidan farq qilyapti:\n\n");
                for (String[] d : diffs)
                    sb.append(d[0]).append(" ").append(esc(d[1]))
                      .append(" → <b>").append(esc(d[2])).append("</b>\n");
                sb.append("\nQo'llansinmi? (🏪 kassa nomi — MoySklad otdel nomidan, "
                        + "📲 klik hisobi nomi — MoySklad hisob nomidan olinadi)");
                text = sb.toString();
                rows = List.of(irow(btn("✅ Ha, yangilansin", "a:msry")),
                        irow(btn("❌ Yo'q", "a:msr")));
            }
        }
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sender.send(chatId, text, inline(rows));
    }

    private void msNamesApply(AppUser u, long chatId, int msgId) {
        Map<String, String> groups = msClient.fetchGroups();
        Map<String, String> accounts = msClient.fetchAccounts();
        StringBuilder rep = new StringBuilder();
        int n = 0;
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isNameLocked()) continue;
            String g = k.getMoyskladGroupId();
            if (g == null || g.isBlank()) continue;
            String nn = groups.get(g);
            if (nn == null || nn.isBlank() || nn.trim().equals(k.getName())) continue;
            rep.append("🏪 ").append(esc(k.getName())).append(" → <b>").append(esc(nn.trim())).append("</b>\n");
            k.setName(nn.trim());
            kassaRepo.save(k);
            n++;
        }
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
            if (c.isNameLocked()) continue;
            String a = c.getMoyskladAccountId();
            if (a == null || a.isBlank()) continue;
            String nn = accounts.get(a);
            if (nn == null || nn.isBlank() || nn.trim().equals(c.getName())) continue;
            rep.append("📲 ").append(esc(c.getName())).append(" → <b>").append(esc(nn.trim())).append("</b>\n");
            c.setName(nn.trim());
            clickRepo.save(c);
            n++;
        }
        if (n > 0)
            audit.log(u.getId(), "NOMLAR_YANGILANDI", "settings", null,
                    u.getFullName() + " nomlarni MoySklad'dan yangiladi (" + n + " ta)");
        show(chatId, msgId, n == 0
                ? "✅ O'zgarish yo'q — nomlar allaqachon mos."
                : "✅ <b>" + n + " ta nom yangilandi:</b>\n\n" + rep,
                List.of(irow(bk("a:msr"))));
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
        String block = kassaDeactivateBlock(id);
        if (block != null) {
            show(chatId, msgId, block, List.of(irow(bk("a:p:sk"))));
            return;
        }
        kassaRepo.findById(id).ifPresent(k -> { k.setActive(false); kassaRepo.save(k); });
        show(chatId, msgId, "🚫 Kassa faolsizlantirildi", List.of(irow(bk("a:p:sk"))));
    }

    /**
     * Kassani o'chirishdan oldin himoya: balansi yoki topshirilmagan kun qoldig'i
     * bo'lgan kassa o'chirilsa, puli barcha jamilardan «yo'qolib» qolardi.
     * null — o'chirish mumkin; aks holda sabab matni.
     */
    private String kassaDeactivateBlock(long id) {
        long n = ledger.view(OwnerType.KASSA, id, MoneyType.NAQD).getAmount();
        long kl = ledger.view(OwnerType.KASSA, id, MoneyType.KLIK).getAmount();
        long rem = dayRepo.findByKassaIdAndStatusInOrderByDateAsc(id,
                        List.of(DayStatus.OCHIQ, DayStatus.YOPILGAN)).stream()
                .mapToLong(d -> d.remainNaqd() + d.remainKlik()).sum();
        if (n == 0 && kl == 0 && rem == 0) return null;
        return "⚠️ <b>Kassada pul bor — o'chirib bo'lmaydi</b> (o'chirilsa bu pul "
                + "hisobotlardan yo'qolib qoladi):\n"
                + "💵 Naqd: <b>" + fmt(n) + "</b> · 📲 Click: <b>" + fmt(kl) + "</b>"
                + (rem == 0 ? "" : "\n⏳ Topshirilmagan kunlar qoldig'i: <b>" + fmt(rem) + "</b>")
                + "\n\nQoldiqni 0 qilish yo'llari (IKKALASI ham avtomatik hisobga olinadi):\n"
                + "• hisobot topshirish/qabul yoki «Пулларни қабул қилиш»;\n"
                + "• MoySklad rasxod hujjati — rasxod ham qoldiqni kamaytiradi\n"
                + "  (masalan 100 000 dan 50 000 topshirilib, 50 000 rasxod qilinsa — qoldiq 0).\n"
                + "Kerak bo'lsa korrektirovka ham ishlaydi. Shundan keyin o'chirish ochiladi.";
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
            if (k.isCashless()) continue;
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

    /** 🧾 Бугунги расход — kassalar bo'yicha (💰 Бугунги тушумнинг rasxod ko'zgusi). */
    /** 🧾 Расходлар — avval otdel, keyin sana tanlanadi. */
    private void rasxodMenu(Session s, long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("➕ Барчаси", "a:cal:o:rxa")));
        rows.add(irow(btn("🏦 Отдел основной", "a:cal:o:rxo")));
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;
            rows.add(irow(btn("🏪 " + k.getName(), "a:cal:o:rxk" + k.getId())));
        }
        String text = "🧾 <b>Расходлар</b>\n\nOtdelni tanlang:";
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sendContent(s, chatId, text, inline(rows));
    }

    /** rxa — barchasi, rxo — Отдел основной, rxk<id> — bitta kassa. */
    private void rasxodByCtx(Session s, long chatId, int msgId, String ctx, java.time.LocalDate date) {
        if (ctx.equals("rxa")) { rasxodAll(s, chatId, msgId, date); return; }
        if (ctx.equals("rxo")) {
            rasxodOwner(s, chatId, msgId, OwnerType.BUXGALTERIYA, LedgerService.BUX_ID,
                    "🏦 Отдел основной", date, ctx);
            return;
        }
        if (ctx.startsWith("rxk")) {
            long kid = Long.parseLong(ctx.substring(3));
            String name = kassaRepo.findById(kid).map(Kassa::getName).orElse("Kassa #" + kid);
            rasxodOwner(s, chatId, msgId, OwnerType.KASSA, kid, "🏪 " + name, date, ctx);
        }
    }

    /** Bitta otdel/kassaning tanlangan kundagi rasxodi — har bir yozuv kimga/necha ekani bilan. */
    private void rasxodOwner(Session s, long chatId, int msgId, OwnerType ot, Long oid, String label,
                             java.time.LocalDate date, String calCtx) {
        syncService.syncIfStale(45);
        long naqd = 0, klik = 0;
        StringBuilder lines = new StringBuilder();
        for (Operation o : opRepo.byPeriod(date, date)) {
            if (o.getStatus() != OpStatus.TASDIQLANGAN || o.getType() != OpType.RASXOD) continue;
            if (o.getFromOwnerType() != ot || !oid.equals(o.getFromOwnerId())) continue;
            if (o.getMoneyType() == MoneyType.KLIK) klik += o.getAmount(); else naqd += o.getAmount();
            lines.append("• ").append(fmt(o.getAmount())).append(" so'm (")
                 .append(o.getMoneyType() == MoneyType.KLIK ? "📲" : "💵").append(")")
                 .append(o.getComment() == null || o.getComment().isBlank() ? "" : " — " + esc(o.getComment()))
                 .append("\n");
        }
        StringBuilder sb = new StringBuilder("🧾 <b>" + label + "</b>\n📅 " + date.format(DF) + "\n\n");
        sb.append(lines.length() == 0 ? "Rasxod yo'q.\n" : lines);
        sb.append("\n➕ <b>Жами: ").append(fmt(naqd + klik)).append("</b> so'm")
          .append(" (💵 ").append(fmt(naqd)).append(" · 📲 ").append(fmt(klik)).append(")");

        InlineKeyboardMarkup kb = inline(List.of(
                irow(btn("📆 Кун танлаш", "a:cal:o:" + calCtx)),
                irow(bk("a:rxm"))));
        if (msgId > 0) sender.edit(chatId, msgId, sb.toString(), kb);
        else sendContent(s, chatId, sb.toString(), kb);
    }

    /** 🧾 Расход — tanlangan kun, otdellar kesimida, har bir chiqim kimga/necha ekani bilan. */
    private void rasxodAll(Session s, long chatId, int msgId, java.time.LocalDate date) {
        syncService.syncIfStale(45);
        List<Operation> ops = opRepo.byPeriod(date, date).stream()
                .filter(o -> o.getStatus() == OpStatus.TASDIQLANGAN && o.getType() == OpType.RASXOD)
                .toList();

        StringBuilder sb = new StringBuilder("🧾 <b>РАСХОД</b>\n📅 " + date.format(DF) + "\n");
        long totNaqd = 0, totKlik = 0;

        long osnNaqd = 0, osnKlik = 0;   // pul turi bo'yicha AJRATILADI — hammasi «naqd» emas
        StringBuilder osnLines = new StringBuilder();
        for (Operation o : ops) {
            if (o.getFromOwnerType() != OwnerType.BUXGALTERIYA) continue;
            if (o.getMoneyType() == MoneyType.KLIK) osnKlik += o.getAmount(); else osnNaqd += o.getAmount();
            osnLines.append("  • ").append(fmt(o.getAmount())).append(" so'm (")
                    .append(o.getMoneyType() == MoneyType.KLIK ? "📲" : "💵").append(")")
                    .append(o.getComment() == null || o.getComment().isBlank() ? "" : " — " + esc(o.getComment()))
                    .append("\n");
        }
        totNaqd += osnNaqd; totKlik += osnKlik;
        sb.append("\n🏦 <b>Отдел основной</b> — <b>").append(fmt(osnNaqd + osnKlik)).append("</b> so'm\n").append(osnLines);

        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;
            long n = 0, kl = 0;
            StringBuilder lines = new StringBuilder();
            for (Operation o : ops) {
                if (o.getFromOwnerType() != OwnerType.KASSA || !k.getId().equals(o.getFromOwnerId())) continue;
                if (o.getMoneyType() == MoneyType.KLIK) kl += o.getAmount(); else n += o.getAmount();
                lines.append("  • ").append(fmt(o.getAmount())).append(" so'm (")
                     .append(o.getMoneyType() == MoneyType.KLIK ? "📲" : "💵").append(")")
                     .append(o.getComment() == null || o.getComment().isBlank() ? "" : " — " + esc(o.getComment()))
                     .append("\n");
            }
            totNaqd += n; totKlik += kl;
            sb.append("\n<b>").append(esc(k.getName())).append("</b> — <b>").append(fmt(n + kl)).append("</b> so'm\n")
              .append(lines);
        }

        sb.append("\n➕ <b>ЖАМИ: ").append(fmt(totNaqd + totKlik)).append("</b> so'm")
          .append("\n  💵 Нақд: ").append(fmt(totNaqd))
          .append(" · 📲 Click: ").append(fmt(totKlik));

        InlineKeyboardMarkup kb = inline(List.of(
                irow(btn("📆 Кун танлаш", "a:cal:o:rxa")),
                irow(bk("a:rxm"))));
        if (msgId > 0) sender.edit(chatId, msgId, sb.toString(), kb);
        else sendContent(s, chatId, sb.toString(), kb);
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
     *  kr — korrektirovka sanasi,
     *  rxa/rxo/rxk<id> — Расходлар bo'limida kun tanlash (barchasi/osnovnoy/kassa). */
    private boolean calSingle(String ctx) {
        return ctx.equals("q") || ctx.equals("ib") || ctx.equals("ck")
                || ctx.equals("kr") || ctx.startsWith("rx");
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
                    if (ctx.startsWith("rx")) { rasxodByCtx(s, chatId, msgId, ctx, d); return; }
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
     * 📣 ГУРУҲЛАР/КАНАЛЛАР — har soatda Click qoldiqlari yuboriladigan
     * guruh/kanallar RO'YXATI. ID kiritilganda bot shu chatda ADMIN/A'ZO
     * ekanligi darhol tekshiriladi — noto'g'ri ID yoki bot qo'shilmagan chat
     * sababli keyinchalik jim ishlamay qolmasligi uchun.
     * ================================================================== */

    private void clickGroupMenu(Session s, long chatId, int msgId) {
        List<Long> ids = jobs.clickChatIds();
        StringBuilder status = new StringBuilder();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (ids.isEmpty()) {
            status.append("🔴 <b>Hech qanday guruh/kanal ulanmagan</b>");
        } else {
            for (long gid : ids) {
                var chat = sender.getChat(gid);
                String botStat = sender.botStatusInChat(gid);
                boolean canPost = "administrator".equals(botStat) || "member".equals(botStat)
                        || "creator".equals(botStat);
                String name = chat != null
                        ? (chat.getTitle() != null ? chat.getTitle() : chat.getUserName()) : null;
                String shown = name != null ? name : ("ID " + gid);
                boolean channel = chat != null && chat.isChannelChat();
                status.append(canPost ? "🟢 " : "🟠 ").append(channel ? "📢 " : "👥 ")
                      .append("<b>").append(esc(shown)).append("</b> (<code>").append(gid).append("</code>)");
                if (!canPost) status.append(" — bot bu chatda topilmadi yoki chiqarib yuborilgan");
                status.append("\n");
                rows.add(irow(btn("🗑 " + shown, "a:cgx:" + gid)));
            }
        }
        String text = "📣 <b>Гуруҳлар / Каналлар</b>\n\n"
                + "Quyidagi barcha chatlarga Click hisoblarining MoySklad bilan "
                + "tenglashtirilgan qoldig'i yuboriladi.\n"
                + "Bu chatlarda bot hech qanday menyu ko'rsatmaydi va faqat SuperAdmin "
                + "buyruqlariga javob beradi.\n\n"
                + "⏰ Jadval: <b>har " + jobs.clickEvery() + " soatda</b>, "
                + String.format("<b>%02d:00–%02d:00</b> oralig'ida", jobs.clickFrom(), jobs.clickTo())
                + (jobs.clickOffsetMin() == 0 ? "" : String.format(", siljish <b>%+d min</b> (masalan %s)",
                        jobs.clickOffsetMin(), jobs.clickTimeExample(jobs.clickFrom())))
                + "\n"
                + (jobs.clickFooter().isEmpty() ? ""
                    : "✍️ Ost matn: <code>" + esc(jobs.clickFooter()) + "</code>\n")
                + "\n" + status;
        rows.add(irow(btn("➕ Guruh/Kanal qo'shish", "a:cge")));
        rows.add(irow(btn("⏰ Yuborish vaqtlari", "a:cgs"), btn("✍️ Ост матн", "a:cgf")));
        if (!ids.isEmpty()) rows.add(irow(btn("🧪 Hozir test yuborish", "a:cgt")));
        rows.add(irow(bk("a:p:set")));
        InlineKeyboardMarkup kb = inline(rows);
        if (msgId > 0) sender.edit(chatId, msgId, text, kb);
        else sendContent(s, chatId, text, kb);
    }

    /** Click hisoboti ostiga qo'shiladigan matnni saqlash («-» — olib tashlash). */
    private void cgFooterSave(AppUser u, Session s, String text, long chatId) {
        s.state = Session.State.IDLE;
        String v = text.trim();
        if (v.equals("-")) {
            settings.set(uz.kassa.scheduler.Jobs.CLICK_FOOTER_KEY, "");
            audit.log(u.getId(), "CLICK_OST_MATN", "settings", null,
                    u.getFullName() + " hisobot ost matnini olib tashladi");
            sender.send(chatId, "🗑 Ost matn olib tashlandi.");
        } else {
            if (v.length() > 300) {
                s.state = Session.State.ADM_CG_FOOTER;
                sender.send(chatId, "⚠️ Juda uzun (300 belgigacha). Qisqartirib qayta yuboring "
                        + "yoki «-» bilan bekor qiling.");
                return;
            }
            settings.set(uz.kassa.scheduler.Jobs.CLICK_FOOTER_KEY, v);
            audit.log(u.getId(), "CLICK_OST_MATN", "settings", null,
                    u.getFullName() + " hisobot ost matnini o'zgartirdi: " + v);
            sender.send(chatId, "✅ Saqlandi. Endi har Click hisoboti ostida chiqadi:\n\n"
                    + esc(v) + "\n\n🧪 Tekshirish: 📣 Гуруҳлар/Каналлар → «Hozir test yuborish».");
        }
        clickGroupMenu(s, chatId, 0);
    }

    private void cgIdSave(AppUser u, Session s, String text, long chatId) {
        s.state = Session.State.IDLE;
        if (text.equals("-")) {
            sender.send(chatId, "❌ Bekor qilindi.");
            clickGroupMenu(s, chatId, 0);
            return;
        }
        long gid;
        try {
            gid = Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            s.state = Session.State.ADM_CG_ID;
            sender.send(chatId, "⚠️ Bu raqamga o'xshamaydi. Guruh ID sini qaytadan yuboring "
                    + "(masalan -1001234567890) yoki «-» bilan bekor qiling:");
            return;
        }
        var chat = sender.getChat(gid);
        String botStat = sender.botStatusInChat(gid);
        boolean ok = chat != null && ("administrator".equals(botStat) || "member".equals(botStat)
                || "creator".equals(botStat));
        if (!ok) {
            s.state = Session.State.ADM_CG_ID;
            sender.send(chatId, "⚠️ Bu ID (<code>" + gid + "</code>) bilan chat topilmadi yoki "
                    + "bot u yerga hali qo'shilmagan.\n\nAvval botni (@" + esc(props.getBot().getUsername())
                    + ") shu guruhga qo'shing, so'ng ID ni qaytadan yuboring yoki «-» bilan bekor qiling:");
            return;
        }
        jobs.addClickChat(gid);
        audit.log(u.getId(), "CLICK_GROUP_SET", "settings", null,
                u.getFullName() + " hisobot ro'yxatiga guruh/kanal qo'shdi: " + gid);
        String name = chat.getTitle() != null ? chat.getTitle() : chat.getUserName();
        sender.send(chatId, "✅ <b>" + esc(name != null ? name : String.valueOf(gid))
                + "</b> hisobot yuboriladigan chatlar ro'yxatiga qo'shildi.\nJadval bo'yicha "
                + "shu yerga Click qoldiqlari tushadi.");
        clickGroupMenu(s, chatId, 0);
    }

    /** ⏰ Hisobot yuborish jadvali: interval (necha soatda bir) va soat oynasi. */
    private void clickScheduleMenu(Session s, long chatId, int msgId) {
        int every = jobs.clickEvery(), from = jobs.clickFrom(), to = jobs.clickTo();
        int off = jobs.clickOffsetMin();
        String text = "⏰ <b>Yuborish vaqtlari</b>\n\n"
                + "Joriy jadval: <b>har " + every + " soatda</b>, "
                + String.format("<b>%02d:00–%02d:00</b> oralig'ida", from, to)
                + (off == 0 ? "" : String.format(", siljish <b>%+d min</b>", off)) + ".\n"
                + "Masalan: <b>" + jobs.clickTimeExample(from) + "</b>, <b>"
                + jobs.clickTimeExample(Math.min(23, from + every)) + "</b>, …\n\n"
                + "Hisobot nominal soat + siljish vaqtida yuboriladi: soat tanlangan "
                + "oraliqda bo'lsa va intervalga to'g'ri kelsa.\n\n"
                + "<b>Interval</b> (necha soatda bir) · <b>Oraliq</b> · <b>Minut siljishi</b>:";
        java.util.function.BiFunction<Integer, String, InlineKeyboardButton> ib = (h, cb) ->
                btn((h == every ? "✅ " : "") + h + " soat", cb);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(ib.apply(1, "a:cgi:1"), ib.apply(2, "a:cgi:2"), ib.apply(3, "a:cgi:3")));
        rows.add(irow(ib.apply(4, "a:cgi:4"), ib.apply(6, "a:cgi:6"), ib.apply(12, "a:cgi:12")));
        rows.add(irow(ib.apply(24, "a:cgi:24")));
        java.util.function.BiFunction<int[], String, InlineKeyboardButton> wb = (w, cb) -> {
            boolean cur = w[0] == from && w[1] == to;
            return btn((cur ? "✅ " : "") + String.format("%02d:00–%02d:00", w[0], w[1]), cb);
        };
        rows.add(irow(wb.apply(new int[]{0, 23}, "a:cgw:0:23"),
                      wb.apply(new int[]{8, 20}, "a:cgw:8:20")));
        rows.add(irow(wb.apply(new int[]{9, 18}, "a:cgw:9:18"),
                      wb.apply(new int[]{9, 22}, "a:cgw:9:22")));
        // Minut siljishi: -20 … +20 (5 daqiqalik qadam). ✅ — joriy tanlov.
        java.util.function.Function<Integer, InlineKeyboardButton> ob = m ->
                btn((m == off ? "✅ " : "") + (m == 0 ? ":00" : String.format("%+d min", m)), "a:cgo:" + m);
        rows.add(irow(ob.apply(-20), ob.apply(-15), ob.apply(-10), ob.apply(-5)));
        rows.add(irow(ob.apply(0)));
        rows.add(irow(ob.apply(5), ob.apply(10), ob.apply(15), ob.apply(20)));
        rows.add(irow(btn("⬅️ Orqaga", "a:cg")));
        InlineKeyboardMarkup kb = inline(rows);
        if (msgId > 0) sender.edit(chatId, msgId, text, kb);
        else sendContent(s, chatId, text, kb);
    }

    /* ==================================================================
     * 📅 LEDGER САНАСИ — MoySklad sinxron shu sanadan OLDINGI, bazada yo'q
     * hujjatlarni qayta o'qimaydi (qo'lda kalibratsiya buzilmasligi uchun).
     * Bot ichidan o'zgartirilsa settings ustuvor, .env (MOYSKLAD_LEDGER_START)
     * zaxira bo'lib qoladi.
     * ================================================================== */

    private void ledgerMenu(Session s, long chatId, int msgId) {
        String override = settings.get(
                uz.kassa.service.moysklad.MoySkladSyncService.LEDGER_START_KEY).orElse("").trim();
        String env = props.getMoysklad().getLedgerStartDate();
        java.time.LocalDate eff = syncService.effectiveEpoch();
        String effStr = eff.equals(java.time.LocalDate.MIN)
                ? "❌ Belgilanmagan (cheklov yo'q)" : eff.format(DF);
        String source = !override.isBlank() ? "bot sozlamasi"
                : (env != null && !env.isBlank() ? ".env (MOYSKLAD_LEDGER_START)" : "—");
        String text = "📅 <b>Ledger boshlanish sanasi</b>\n\n"
                + "Amaldagi sana: <b>" + effStr + "</b> (manba: " + source + ")\n\n"
                + "Bu sanadan OLDINGI, bazada YO'Q MoySklad hujjatlari sinxron/reconcile "
                + "orqali qayta o'qilmaydi — boshlang'ich qoldiqlar shu sanaga kalibrlangan, "
                + "eski hujjatlar ikki marta hisoblanmasligi uchun. Bazada YOZUVI BOR "
                + "hujjatning o'zgarishi esa sanasidan qat'i nazar doim qo'llanadi.\n\n"
                + "⚠️ Odatda bu sana boshlang'ich qoldiq QAYTA kiritilgandagina o'zgartiriladi.";
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("✏️ Sanani o'zgartirish", "a:lse")));
        if (!override.isBlank() && env != null && !env.isBlank())
            rows.add(irow(btn("♻️ .env qiymatiga qaytarish (" + env + ")", "a:lsx")));
        rows.add(irow(bk("a:p:set")));
        InlineKeyboardMarkup kb = inline(rows);
        if (msgId > 0) sender.edit(chatId, msgId, text, kb);
        else sendContent(s, chatId, text, kb);
    }

    private void lsSave(AppUser u, Session s, String text, long chatId) {
        s.state = Session.State.IDLE;
        if (text.equals("-")) {
            sender.send(chatId, "❌ Bekor qilindi.");
            ledgerMenu(s, chatId, 0);
            return;
        }
        java.time.LocalDate d;
        try {
            String t = text.trim();
            d = t.contains(".") ? java.time.LocalDate.parse(t, DF) : java.time.LocalDate.parse(t);
        } catch (Exception e) {
            s.state = Session.State.ADM_LS_DATE;
            sender.send(chatId, "⚠️ Sana formati noto'g'ri. <code>2026-08-26</code> yoki "
                    + "<code>26.08.2026</code> ko'rinishida yuboring, yoki «-» bilan bekor qiling:");
            return;
        }
        if (d.isAfter(ledger.today())) {
            s.state = Session.State.ADM_LS_DATE;
            sender.send(chatId, "⚠️ Kelajak sanasi bo'lmaydi — sinxron butunlay to'xtab qolardi. "
                    + "Boshqa sana yuboring yoki «-» bilan bekor qiling:");
            return;
        }
        settings.set(uz.kassa.service.moysklad.MoySkladSyncService.LEDGER_START_KEY, d.toString());
        audit.log(u.getId(), "LEDGER_SANA", "settings", null,
                u.getFullName() + " ledger boshlanish sanasini o'zgartirdi: " + d);
        sender.send(chatId, "✅ Ledger boshlanish sanasi <b>" + d.format(DF) + "</b> qilib "
                + "saqlandi. Keyingi sinxron sikllaridan boshlab shu sanadan oldingi yangi "
                + "hujjatlar o'qilmaydi.");
        ledgerMenu(s, chatId, 0);
    }

    /* ==================================================================
     * 🩺 ДИАГНОСТИКА — minus balanslar va minus kunlarni topib, sababi bilan
     * ko'rsatadi; har bir muammoga bir bosishda Корректировка oqimiga o'tiladi.
     * Kassa/sklad minusda bo'lishining tipik sabablari:
     *  - rasxod hujjatlari kirimdan oldin/ko'p kelgan (MoySklad'da kirim boshqa
     *    otdelga yozilgan yoki umuman kiritilmagan);
     *  - boshlang'ich qoldiq kiritilmagan yoki noto'g'ri sana bilan kiritilgan;
     *  - korrektirovka summasi/sanasi xato;
     *  - kun ichida qoplash (qabul) haqiqiy tushumdan ortiq qilingan.
     * ================================================================== */

    private void diagMenu(Session s, long chatId, int msgId) {
        StringBuilder sb = new StringBuilder("🩺 <b>Диагностика — minus tekshiruvi</b>\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int issues = 0;

        // 1) Balanslar: Основной, har kassa (naqd/klik), Click hisoblari
        StringBuilder bal = new StringBuilder();
        long bn = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD).getAmount();
        long bkl = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.KLIK).getAmount();
        if (bn < 0) bal.append("🔻 🏦 Основной — 💵 Naqd: <b>").append(fmt(bn)).append("</b> so'm\n");
        if (bkl < 0) bal.append("🔻 🏦 Основной — 📲 Click: <b>").append(fmt(bkl)).append("</b> so'm\n");
        if (bn < 0 || bkl < 0) rows.add(irow(btn("🛠 Основной tuzatish", "a:fixo:B")));

        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            long n = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.NAQD).getAmount();
            long kl = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.KLIK).getAmount();
            if (n < 0) bal.append("🔻 🏪 ").append(esc(k.getName()))
                    .append(" — 💵 Naqd: <b>").append(fmt(n)).append("</b> so'm\n");
            if (kl < 0) bal.append("🔻 🏪 ").append(esc(k.getName()))
                    .append(" — 📲 Click: <b>").append(fmt(kl)).append("</b> so'm\n");
            if (n < 0 || kl < 0)
                rows.add(irow(btn("🛠 " + k.getName() + " tuzatish", "a:fixo:K" + k.getId())));
        }
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
            long v = ledger.view(OwnerType.CLICK, c.getId(), MoneyType.KLIK).getAmount();
            if (v < 0) {
                bal.append("🔻 📲 ").append(esc(c.getName()))
                        .append(": <b>").append(fmt(v)).append("</b> so'm\n");
                rows.add(irow(btn("🛠 " + c.getName() + " tuzatish", "a:fixo:C" + c.getId())));
            }
        }
        if (bal.length() > 0) {
            issues++;
            sb.append("\n<b>Minus balanslar:</b>\n").append(bal);
        }

        // 2) Topshirilmagan (OCHIQ/YOPILGAN) kunlarda minus qoldiq
        StringBuilder days = new StringBuilder();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            for (DayRecord d : dayRepo.findByKassaIdAndStatusInOrderByDateAsc(
                    k.getId(), List.of(DayStatus.OCHIQ, DayStatus.YOPILGAN))) {
                if (d.remainNaqd() < 0)
                    days.append("🔻 🏪 ").append(esc(k.getName())).append(" • ")
                        .append(d.getDate().format(DF)).append(" — 💵 <b>")
                        .append(fmt(d.remainNaqd())).append("</b> so'm\n");
                if (d.remainKlik() < 0)
                    days.append("🔻 🏪 ").append(esc(k.getName())).append(" • ")
                        .append(d.getDate().format(DF)).append(" — 📲 <b>")
                        .append(fmt(d.remainKlik())).append("</b> so'm\n");
            }
        }
        if (days.length() > 0) {
            issues++;
            sb.append("\n<b>Minus kunlar</b> (Кассалар холати'dagi minuslar shundan):\n")
              .append(days);
        }

        // 3) Bir xil telefon raqamli foydalanuvchilar (takror akkaunt — xato manbai)
        StringBuilder dups = new StringBuilder();
        java.util.Map<String, java.util.List<AppUser>> byPhone = new java.util.HashMap<>();
        for (AppUser x : userRepo.findAll()) {
            String np = uz.kassa.bot.TextUtil.normPhone(x.getPhone());
            if (np.isEmpty()) continue;
            byPhone.computeIfAbsent(np, z -> new ArrayList<>()).add(x);
        }
        for (var e2 : byPhone.entrySet()) {
            if (e2.getValue().size() < 2) continue;
            dups.append("📱 <code>").append(e2.getKey()).append("</code>: ")
                .append(esc(e2.getValue().stream()
                        .map(x -> x.getFullName() + (x.isActive() ? "" : " (nofaol)"))
                        .collect(java.util.stream.Collectors.joining(", "))))
                .append("\n");
        }
        if (dups.length() > 0) {
            issues++;
            sb.append("\n<b>Bir xil raqamli foydalanuvchilar</b> — takrorini o'chiring "
                    + "yoki raqamini to'g'rilang (aralashib xato beradi):\n").append(dups);
        }

        if (issues == 0) {
            sb.append("\n✅ Minus balans ham, minus kun ham topilmadi — hammasi joyida.");
        } else {
            sb.append("\n<b>Minus qayerdan chiqadi?</b>\n")
              .append("• MoySklad'da rasxod bor, lekin o'sha kunning kirimi boshqa otdelga "
                      + "yozilgan yoki umuman kiritilmagan;\n")
              .append("• boshlang'ich qoldiq kiritilmagan / sanasi noto'g'ri;\n")
              .append("• korrektirovka summasi yoki sanasi xato ketgan;\n")
              .append("• kunlik qoplash (pul qabul qilish) haqiqiy tushumdan ortiq qilingan.\n\n")
              .append("Avval MoySklad'dagi hujjatlarni tekshiring; haqiqatan xato bo'lsa — "
                      + "pastdagi 🛠 tugma orqali Корректировка bilan tuzating "
                      + "(<code>=summa</code> yozsangiz balans aynan shu qiymatga tenglashadi).");
        }

        rows.add(irow(btn("🔄 Qayta tekshirish", "a:diag")));
        rows.add(irow(bk("a:p:set")));
        InlineKeyboardMarkup kb = inline(rows);
        if (msgId > 0) sender.edit(chatId, msgId, sb.toString(), kb);
        else sendContent(s, chatId, sb.toString(), kb);
    }

    /* ==================================================================
     * 📥 ҚАЙТА ЮКЛАШ — BARCHA moliyaviy ma'lumotlar (operatsiyalar, kunlar,
     * hisobotlar, balanslar) O'CHIRILIB, MoySklad'dan ledger boshlanish
     * sanasidan bugungacha qaytadan tortiladi. Foydalanuvchi/kassa/Click
     * hisoblari/qarz daftariga tegilmaydi. Boshlang'ich qoldiqlar ham
     * o'chadi — keyin qo'lda qayta kiritish kerak.
     * ================================================================== */

    private void reloadConfirm(Session s, long chatId, int msgId) {
        java.time.LocalDate ep = syncService.effectiveEpoch();
        if (ep.equals(java.time.LocalDate.MIN)) {
            String warn = "⚠️ Avval <b>📅 Ledger санаси</b>ni belgilang — usiz qayta yuklash "
                    + "MoySklad'ning BUTUN tarixini tortib yuborardi.";
            if (msgId > 0) sender.edit(chatId, msgId, warn);
            else sendContent(s, chatId, warn, null);
            return;
        }
        long ops = opRepo.count();
        String text = "📥 <b>Қайта юклаш</b>\n\n"
                + "Bu amal:\n"
                + "• barcha operatsiyalarni (" + ops + " ta), kun yozuvlarini va "
                + "hisobotlarni <b>O'CHIRADI</b>;\n"
                + "• barcha balanslarni <b>0</b> ga tushiradi (boshlang'ich qoldiqlar va "
                + "korrektirovkalar ham o'chadi!);\n"
                + "• MoySklad'dan <b>" + ep.format(DF) + "</b> dan bugungacha hujjatlarni "
                + "qaytadan tortadi.\n\n"
                + "Foydalanuvchilar, kassalar, Click hisoblari, qarz daftari va sozlamalarga "
                + "tegilmaydi.\n\n⚠️ Bu amalni ORQAGA QAYTARIB BO'LMAYDI. Davom etasizmi?";
        InlineKeyboardMarkup kb = inline(List.of(
                irow(btn("✅ Ha, o'chirib qayta yukla", "a:rldc")),
                irow(btn("❌ Bekor", "cx"))));
        if (msgId > 0) sender.edit(chatId, msgId, text, kb);
        else sendContent(s, chatId, text, kb);
    }

    private void reloadCommit(AppUser u, long chatId, int msgId) {
        audit.log(u.getId(), "QAYTA_YUKLASH", "settings", null,
                u.getFullName() + " to'liq tozalash + MoySklad'dan qayta yuklashni boshladi");
        sender.edit(chatId, msgId, "⏳ Tozalanmoqda va MoySklad'dan qayta yuklanmoqda...\n"
                + "Bu bir necha daqiqa olishi mumkin — tugagach xabar keladi.");
        new Thread(() -> {
            try {
                int n = syncService.fullReload();
                if (n == -1) {
                    sender.send(chatId, "⚠️ Ledger sanasi belgilanmagan — hech narsa o'chirilmadi.");
                } else if (n == -2) {
                    sender.send(chatId, "⚠️ MoySklad tokeni ishlamayapti — hech narsa o'chirilmadi. "
                            + "Avval 🔑 MoySklad API bo'limini tekshiring.");
                } else {
                    sender.send(chatId, "✅ <b>Қайта юклаш tugadi.</b>\n\n"
                            + "MoySklad'dan <b>" + n + "</b> ta hujjat yuklandi ("
                            + syncService.effectiveEpoch().format(DF) + " dan bugungacha). "
                            + "Click hisoblari MoySklad'ning joriy qoldiqlariga tenglashtirildi.\n\n"
                            + balanceSummary()
                            + "\n❗️ Kassa/buxgalteriya NAQD qoldiqlari MoySklad'dan olinmaydi — "
                            + "haqiqiy naqd pulni <b>🛠 Корректировка</b> yoki "
                            + "<b>💼 Бошланғич қолдиқ</b> orqali kiriting.");
                }
            } catch (Exception ex) {
                sender.send(chatId, "❌ Qayta yuklashda xato: " + esc(String.valueOf(ex.getMessage())));
            }
        }, "full-reload").start();
    }

    /** Barcha egalar bo'yicha joriy balanslar — bir xabarlik qisqa xulosa. */
    private String balanceSummary() {
        StringBuilder sb = new StringBuilder("💰 <b>Joriy balanslar:</b>\n");
        long bn = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD).getAmount();
        long bk = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.KLIK).getAmount();
        sb.append("🏦 Основной: 💵 ").append(fmt(bn));
        if (bk != 0) sb.append(" · 📲 ").append(fmt(bk));
        sb.append(" so'm\n");
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            long n = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.NAQD).getAmount();
            long kl = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.KLIK).getAmount();
            if (n == 0 && kl == 0) continue;
            sb.append("🏪 ").append(esc(k.getName())).append(": 💵 ").append(fmt(n));
            if (kl != 0) sb.append(" · 📲 ").append(fmt(kl));
            sb.append(" so'm\n");
        }
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
            long v = ledger.view(OwnerType.CLICK, c.getId(), MoneyType.KLIK).getAmount();
            if (v != 0) sb.append("📲 ").append(esc(c.getName())).append(": ")
                    .append(fmt(v)).append(" so'm\n");
        }
        return sb.toString();
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

    /** Aktor shu userni boshqara oladimi: SUPERADMIN'ga faqat yaratuvchi tegadi. */
    private boolean canManage(AppUser actor, AppUser target) {
        return target.getRole() != Role.SUPERADMIN || isCreator(actor);
    }

    private void permCard(AppUser actor, long userId, long chatId, int msgId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null) return;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (x.getRole() != Role.SUPERADMIN)
            rows.add(irow(btn("⚙️ Бўлимларини бошқариш", "a:prs:" + userId)));
        if (canManage(actor, x)) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(btn("🔄 Rol o'zgartirish", "a:prr:" + userId));
            if (!x.getId().equals(actor.getId()))
                r.add(btn("🚫 Faolsizlantirish", "a:prx:" + userId));
            rows.add(r);
        }
        rows.add(irow(bk("a:prm")));
        String note = canManage(actor, x) ? ""
                : "\n\n<i>🔒 SuperAdmin maqomini faqat asosiy (yaratuvchi) SuperAdmin boshqaradi.</i>";
        sender.edit(chatId, msgId, permText(x) + note, inline(rows));
    }

    /** Ҳуқуқлар kartasidan rol tanlash. SuperAdmin qilish faqat yaratuvchiga ko'rinadi. */
    private void permRolePick(AppUser actor, long userId, long chatId, int msgId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null) return;
        if (!canManage(actor, x)) { permCard(actor, userId, chatId, msgId); return; }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn("👤 Kassir — " + k.getName(), "a:prz:" + userId + ".K" + k.getId())));
        List<InlineKeyboardButton> r = new ArrayList<>();
        r.add(btn("🧮 Buxgalter", "a:prz:" + userId + ".B"));
        if (isCreator(actor)) r.add(btn("👑 SuperAdmin", "a:prz:" + userId + ".S"));
        rows.add(r);
        rows.add(irow(bk("a:prc:" + userId)));
        sender.edit(chatId, msgId, "🔄 <b>" + esc(x.getFullName()) + "</b> (hozir: "
                + roleEmoji(x.getRole()) + " " + x.getRole()
                + (x.getKassaId() == null ? "" : " · " + esc(names.owner(OwnerType.KASSA, x.getKassaId())))
                + ")\n\nYangi rolni tanlang:", inline(rows));
    }

    /** arg: "<uid>.K<kassaId>" | "<uid>.B" | "<uid>.S" */
    private void permRoleApply(AppUser actor, String arg, long chatId, int msgId) {
        int dot = arg.indexOf('.');
        if (dot <= 0) return;
        long userId = Long.parseLong(arg.substring(0, dot));
        String code = arg.substring(dot + 1);
        Role role;
        Long kassaId = null;
        if (code.startsWith("K")) { role = Role.KASSIR; kassaId = Long.parseLong(code.substring(1)); }
        else if (code.equals("B")) role = Role.BUXGALTER;
        else if (code.equals("S")) role = Role.SUPERADMIN;
        else return;
        applyRoleDirect(actor, userId, role, kassaId, chatId);
        permCard(actor, userId, chatId, msgId);
    }

    /** Ҳуқуқлар kartasidan faolsizlantirish — tasdiq bilan. */
    private void permDeactConfirm(AppUser actor, long userId, long chatId, int msgId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null) return;
        if (!canManage(actor, x)) { permCard(actor, userId, chatId, msgId); return; }
        if (x.getId().equals(actor.getId())) {
            sender.edit(chatId, msgId, "⚠️ O'zingizni faolsizlantira olmaysiz.",
                    inline(List.of(irow(bk("a:prc:" + userId)))));
            return;
        }
        sender.edit(chatId, msgId, "⚠️ <b>" + esc(x.getFullName()) + "</b> ("
                + x.getRole() + ") faolsizlantirilsinmi?\n\nU botdan foydalana olmay qoladi. "
                + "Keyin kerak bo'lsa Sheets «Foydalanuvchilar» varag'ida Faol=TRUE qilib "
                + "qaytarish mumkin.", inline(List.of(
                irow(btn("✅ Ha, faolsizlantirilsin", "a:prxy:" + userId)),
                irow(btn("❌ Yo'q", "a:prc:" + userId)))));
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
                sb.append("• O'tkazma, hisobot topshirish\n");
                sb.append("• Kontragent qarz daftari: qidiruv, balans, eslatma qo'shish (o'ziniki)\n");
                if (x.getKassaId() != null)
                    sb.append("• 🤝 Настройка: otdeliga odam qo'shish (erkin); o'chirish/tahrir — "
                            + "SuperAdmin tasdig'i bilan\n");
            }
            case BUXGALTER -> {
                sb.append("• Barcha kassalar: holat, statistika, saldo, svod/Excel, tarix\n");
                sb.append("• Hisobot qabul qilish\n");
                sb.append("• Kassadan pul qabul qilish (sana tanlash bilan)\n");
                sb.append("• Kontragent qarz daftari (o'ziniki)\n");
            }
            case SUPERADMIN -> {
                sb.append("• Buxgalter qila oladigan HAMMASI\n");
                sb.append("• Foydalanuvchi/kassa qo'shish-o'chirish, rol o'zgartirish\n");
                sb.append("• Boshlang'ich qoldiq\n");
                sb.append("• Аудит (Excel bilan), tugma nomlari, MoySklad API kaliti\n");
                sb.append("• Kontragent: HAMMANING eslatmalarini ko'radi\n");
            }
        }

        sb.append("\n<b>Ko'rmaydi / qila olmaydi:</b>\n");
        switch (x.getRole()) {
            case KASSIR -> sb.append("• Boshqa kassalar, umumiy statistika, svod, "
                    + "buxgalteriya hisoboti\n• Sozlamalar, Аудит");
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
            case KASSIR -> sb.append("o'z kassasining kirim/chiqimi, "
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
            if (uz.kassa.bot.TextUtil.phoneEq(gp, digits)) {   // faqat TO'LIQ moslik — suffiks emas
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
        // Bir xil telefon raqamli IKKINCHI foydalanuvchi yaratilmasin
        if (!phone.isEmpty()) {
            var dupPhone = userRepo.findAll().stream()
                    .filter(x -> x.getPhone() != null
                            && uz.kassa.bot.TextUtil.phoneEq(x.getPhone(), phone))
                    .findFirst();
            if (dupPhone.isPresent()) {
                s.reset();
                sender.edit(chatId, msgId, "⚠️ Bu telefon raqam allaqachon <b>"
                        + esc(dupPhone.get().getFullName()) + "</b>"
                        + (dupPhone.get().isActive() ? "" : " (nofaol)")
                        + "da yozilgan — takror foydalanuvchi yaratilmadi.\n\n"
                        + "Raqam haqiqatan boshqa odamniki bo'lsa, avval eskisining "
                        + "raqamini to'g'rilang yoki o'chiring.");
                return;
            }
        }
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

    /** Korrektirovka: otdel (Buxgalteriya, kassa yoki alohida Click hisobi) tanlanadi. Faqat SuperAdmin. */
    private void krStart(Session s, long chatId) {
        s.reset(); s.state = Session.State.ADM_KR_OWNER;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("🏦 Буxгалтерия (Основной)", "a:kro:B")));
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;   // B5: cashless'da korrektirovka ham yo'q
            rows.add(irow(btn("🏪 " + k.getName(), "a:kro:K" + k.getId())));
        }
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn("📲 " + c.getName(), "a:kro:C" + c.getId())));
        rows.add(irow(btn("❌ Bekor", "cx")));
        sender.send(chatId, "🛠 <b>Корректировка</b>\n\n"
                + "Balans qo'lda tuzatiladigan otdelni tanlang:", inline(rows));
    }

    private void krOwner(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_KR_OWNER) return;
        if (arg.equals("B")) {
            s.data.put("krT", OwnerType.BUXGALTERIYA);
            s.data.put("krId", LedgerService.BUX_ID);
        } else if (arg.startsWith("C")) {
            s.data.put("krT", OwnerType.CLICK);
            s.data.put("krId", Long.parseLong(arg.substring(1)));
        } else {
            s.data.put("krT", OwnerType.KASSA);
            s.data.put("krId", Long.parseLong(arg.substring(1)));
        }
        OwnerType ot = (OwnerType) s.data.get("krT");
        long oid = s.getLong("krId");

        // Click hisobida faqat KLIK bo'ladi — pul turi so'ralmaydi, to'g'ridan-to'g'ri sanaga o'tadi
        if (ot == OwnerType.CLICK) { krProceedToSana(s, MoneyType.KLIK, chatId, msgId); return; }

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
        krProceedToSana(s, MoneyType.valueOf(arg), chatId, msgId);
    }

    private void krProceedToSana(Session s, MoneyType mt, long chatId, int msgId) {
        s.data.put("krMt", mt);
        long cur = ledger.view((OwnerType) s.data.get("krT"), s.getLong("krId"), mt).getAmount();
        s.state = Session.State.ADM_KR_SANA;
        String txt = "🛠 <b>" + esc(names.owner((OwnerType) s.data.get("krT"),
                        s.getLong("krId"))) + "</b> — " + mtLabel(mt)
                + "\nJoriy balans: <b>" + fmt(cur) + "</b> so'm\n\n"
                + "📅 <b>Qaysi sana bilan korrektirovka qilinsin?</b>";
        InlineKeyboardMarkup kb = inline(List.of(
                irow(btn("📅 Bugun", "a:krd:0"), btn("Kecha", "a:krd:1")),
                irow(btn("🗓 Kalendar", "a:cal:o:kr")),
                irow(btn("❌ Bekor", "cx"))));
        if (msgId > 0) sender.edit(chatId, msgId, txt, kb);
        else sender.send(chatId, txt, kb);
    }

    private void krSanaBtn(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_KR_SANA) return;
        krSanaChosen(s, ledger.today().minusDays(Long.parseLong(arg)), chatId, msgId);
    }

    /** Sana tanlandi (tugma yoki kalendar) — TO'G'RIDAN-TO'G'RI summa so'raladi.
     *  Soat (HH:mm) bosqichi OLIB TASHLANDI: u hech narsaga ta'sir qilmasdi
     *  (faqat izohga yozilardi) va «vaqt ishlamayapti» degan chalkashlik berardi. */
    private void krSanaChosen(Session s, java.time.LocalDate d, long chatId, int msgId) {
        s.data.put("krDate", d.toString());
        krAskSum(s, chatId, msgId);
    }

    /** Eski xabarlardagi «⏱ Hozirgi vaqt» tugmasi — soat bosqichi olib tashlangan, no-op. */
    private void krVaqtNow(AppUser u, Session s, long chatId, int msgId) { }

    /** Eski holatdan qolgan matn — soat bosqichi olib tashlangan, no-op. */
    private void krVaqt(AppUser u, Session s, String text, long chatId) { }

    /** Summa so'raladi (o'tgan sanada — o'sha kungi balansga nisbatan). */
    private void krAskSum(Session s, long chatId, int msgId) {
        MoneyType mt = (MoneyType) s.data.get("krMt");
        OwnerType ot = (OwnerType) s.data.get("krT");
        long oid = s.getLong("krId");
        java.time.LocalDate date = java.time.LocalDate.parse(s.getStr("krDate"));
        long cur = ledger.view(ot, oid, mt).getAmount();
        // O'tgan sana: o'sha kun oxiridagi balans — keyingi kunlarning harakatlarisiz.
        // Tuzatish shu qiymatga nisbatan kiritiladi, bugungi prixod-rasxodlar saqlanadi.
        boolean past = date.isBefore(ledger.today());
        long asOf = past ? ledger.balanceAsOf(ot, oid, mt, date) : cur;
        s.data.put("krAsOf", asOf);
        s.state = Session.State.ADM_KR_SUM;
        StringBuilder txt = new StringBuilder("📅 <b>" + date.format(DF) + "</b> — "
                + mtLabel(mt) + "\n");
        if (past) {
            txt.append("📆 ").append(date.format(DF)).append(" kun oxiridagi balans: <b>")
               .append(fmt(asOf)).append("</b> so'm\n")
               .append("📊 Hozirgi balans: <b>").append(fmt(cur))
               .append("</b> so'm (keyingi kunlar harakatlari bilan)\n");
        } else {
            txt.append("Joriy balans: <b>").append(fmt(cur)).append("</b> so'm\n");
        }
        txt.append("\nTuzatish summasini kiriting:\n")
           .append("• musbat — qo'shiladi (masalan <code>500000</code>)\n")
           .append("• manfiy — ayriladi (masalan <code>-500000</code>)\n")
           .append("• yoki <code>=</code> bilan O'SHA KUNGI bo'lishi kerak bo'lgan balans ")
           .append("(masalan <code>=423461000</code>) — farqni tizim o'zi hisoblaydi");
        if (past) txt.append("\n\nℹ️ Keyingi kunlardagi prixod-rasxodlar saqlanadi — "
                + "ular tuzatish ustiga qo'shilib boradi.");
        InlineKeyboardMarkup kb = inline(List.of(irow(btn("❌ Bekor", "cx"))));
        if (msgId > 0) sender.edit(chatId, msgId, txt.toString(), kb);
        else sender.send(chatId, txt.toString(), kb);
    }

    private void krSum(Session s, String text, long chatId) {
        String t = text.trim().replace(" ", "");
        long asOf = s.getLong("krAsOf");

        // «=maqsad» — o'sha kungi balans shu bo'lishi kerak; farqni tizim hisoblaydi
        if (t.startsWith("=")) {
            String body = t.substring(1);
            boolean negTarget = body.startsWith("-");
            long target = parseAmount(body);
            if (target < 0) {
                sender.send(chatId, "⚠️ Maqsad balansni raqamda kiriting, masalan "
                        + "<code>=423461000</code>");
                return;
            }
            if (negTarget) target = -target;
            long delta = target - asOf;
            if (delta == 0) {
                sender.send(chatId, "ℹ️ Balans allaqachon <b>" + fmt(target)
                        + "</b> so'm — tuzatish shart emas. Boshqa summa kiriting yoki bekor qiling:",
                        cancelOnly());
                return;
            }
            // Maqsad rejimi: TARGET saqlanadi — tasdiqlash va commit paytida farq
            // BALANS QAYTA O'QILIB yangidan hisoblanadi (oradagi sinxron adashtirmasin)
            s.data.put("krTarget", target);
            s.data.put("krSum", delta);
            s.state = Session.State.ADM_KR_IZOH;
            sender.send(chatId, "📆 O'sha kungi balans: <b>" + fmt(asOf) + "</b> → maqsad: <b>"
                    + fmt(target) + "</b> so'm\nFarq (tuzatish): <b>"
                    + (delta > 0 ? "+" : "") + fmt(delta) + "</b> so'm\n\n"
                    + "✍️ Sababini yozing (auditda saqlanadi):", cancelOnly());
            return;
        }

        boolean neg = t.startsWith("-");
        long v = parseAmount(t);
        if (v <= 0) {
            sender.send(chatId, "⚠️ Nolga teng bo'lmagan summa kiriting, masalan "
                    + "<code>500000</code>, <code>-500000</code> yoki <code>=423461000</code>");
            return;
        }
        s.data.put("krSum", neg ? -v : v);
        s.state = Session.State.ADM_KR_IZOH;
        sender.send(chatId, "Summa: <b>" + fmt(neg ? -v : v) + "</b> so'm\n\n"
                + "✍️ Sababini yozing (auditda saqlanadi):", cancelOnly());
    }

    /** Sabab olindi — TASDIQLASH ekrani ko'rsatiladi (darhol qo'llanMAYdi). */
    private void krIzoh(AppUser u, Session s, String text, long chatId) {
        s.data.put("krReason", text.trim().equals("-") ? "Korrektirovka" : text.trim());
        s.state = Session.State.IDLE;   // endi faqat tasdiq tugmasi kutiladi
        krConfirmScreen(s, chatId);
    }

    /**
     * TASDIQLASH ekrani (K1/K2): balans shu yerda QAYTA o'qiladi, preview
     * ko'rsatiladi va faqat «✅ Tasdiqlayman» bosilgandagina qo'llanadi.
     */
    private void krConfirmScreen(Session s, long chatId) {
        OwnerType ot = (OwnerType) s.data.get("krT");
        long oid = s.getLong("krId");
        MoneyType mt = (MoneyType) s.data.get("krMt");
        java.time.LocalDate date = java.time.LocalDate.parse(s.getStr("krDate"));
        boolean past = date.isBefore(ledger.today());
        long cur = ledger.view(ot, oid, mt).getAmount();
        long asOf = past ? ledger.balanceAsOf(ot, oid, mt, date) : cur;   // YANGIDAN o'qildi
        Long target = s.data.get("krTarget") == null ? null : s.getLong("krTarget");
        long sum = target != null ? target - asOf : s.getLong("krSum");
        if (sum == 0) {
            s.reset();
            sender.send(chatId, "ℹ️ Balans allaqachon kerakli qiymatda — tuzatish shart emas.");
            return;
        }
        s.data.put("krAsOf", asOf);
        s.data.put("krSum", sum);
        String txt = "🛠 <b>TASDIQLASH — Korrektirovka</b>\n\n"
                + "🏪 " + esc(names.owner(ot, oid)) + " · " + mtLabel(mt) + "\n"
                + "📅 Sana: <b>" + date.format(DF) + "</b>\n"
                + (past ? "📆 O'sha kun oxiri: <b>" + fmt(asOf) + "</b> → <b>"
                        + fmt(asOf + sum) + "</b> so'm\n" : "")
                + "📊 Hozirgi balans: <b>" + fmt(cur) + "</b> → <b>" + fmt(cur + sum) + "</b> so'm\n"
                + "Tuzatish: <b>" + (sum > 0 ? "+" : "") + fmt(sum) + "</b> so'm\n"
                + "Sabab: " + esc(s.getStr("krReason")) + "\n\n"
                + "Hammasi to'g'rimi?";
        sender.send(chatId, txt, inline(List.of(
                irow(btn("✅ Tasdiqlayman", "a:krok"), btn("❌ Bekor", "cx")))));
    }

    /**
     * ✅ Tasdiqlandi (callback) — commit OLDIDAN balans YANA qayta o'qiladi:
     * tasdiqlashdan buyon 30-soniyalik sinxron o'tgan bo'lsa ham «=maqsad»
     * aynan maqsad qiymatga tushadi (K1 tuzatildi).
     */
    private void krCommit(AppUser u, Session s, long chatId, int msgId) {
        if (s.data.get("krT") == null || s.data.get("krReason") == null
                || s.data.get("krMt") == null || s.getStr("krDate") == null) {
            sender.edit(chatId, msgId, "⚠️ Bu tasdiqlash eskirgan — «🛠 Корректировка»ni "
                    + "qaytadan boshlang.");
            return;
        }
        OwnerType ot = (OwnerType) s.data.get("krT");
        long oid = s.getLong("krId");
        MoneyType mt = (MoneyType) s.data.get("krMt");
        java.time.LocalDate date = java.time.LocalDate.parse(s.getStr("krDate"));
        boolean past = date.isBefore(ledger.today());
        long cur = ledger.view(ot, oid, mt).getAmount();
        long asOf = past ? ledger.balanceAsOf(ot, oid, mt, date) : cur;   // commit oldidan yana
        Long target = s.data.get("krTarget") == null ? null : s.getLong("krTarget");
        long sum = target != null ? target - asOf : s.getLong("krSum");
        String reasonBase = s.getStr("krReason");
        s.reset();
        if (sum == 0) {
            sender.edit(chatId, msgId, "ℹ️ Balans allaqachon kerakli qiymatda — tuzatish yozilmadi.");
            return;
        }
        String reason = reasonBase + " [" + date.format(DF) + "]";

        ledger.postAdjustment(OpType.KORREKTIROVKA, ot, oid, mt, sum, reason, u.getId(), date);
        long after = ledger.view(ot, oid, mt).getAmount();
        String owner = names.owner(ot, oid);
        // O'tgan sana: o'sha kun oxiri endi qancha bo'ldi — bugungi harakatlar saqlangan
        String asOfLine = past
                ? "📆 " + date.format(DF) + " kun oxiri endi: <b>" + fmt(asOf + sum) + "</b> so'm\n"
                : "";

        sender.edit(chatId, msgId, "✅ <b>Korrektirovka bajarildi</b> — " + esc(owner) + "\n"
                + mtLabel(mt) + ": <b>" + (sum > 0 ? "+" : "") + fmt(sum) + "</b> so'm\n"
                + "📅 Sana: <b>" + date.format(DF) + "</b>\n"
                + asOfLine
                + "Hozirgi balans: <b>" + fmt(after) + "</b> so'm"
                + (past ? " (keyingi kunlar harakatlari bilan)" : "") + "\n"
                + "Sabab: " + esc(reasonBase));

        String info = "🛠 Korrektirovka — <b>" + esc(owner) + "</b>: <b>"
                + (sum > 0 ? "+" : "") + fmt(sum) + "</b> so'm (" + mtLabel(mt) + ")\n"
                + "📅 Sana: <b>" + date.format(DF) + "</b>\n"
                + asOfLine
                + "Hozirgi balans: <b>" + fmt(after) + "</b> so'm\n"
                + "Sabab: " + esc(reason) + "\nKim: " + esc(u.getFullName());
        notify.toBuxgalteriya(info, null);
        if (ot == OwnerType.KASSA) notify.toKassa(oid, info, null);
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
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;   // B5: cashless nol boshlashda qatnashmaydi
            rows.add(irow(btn("🏪 " + k.getName(), "a:rz:K" + k.getId())));
        }
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
        // Asosiy (yaratuvchi) SuperAdmin'ni hech kim faolsizlantira olmaydi
        if (isCreatorId(x)) {
            sender.edit(chatId, msgId, "⚠️ Asosiy (yaratuvchi) SuperAdmin'ni "
                    + "faolsizlantirib bo'lmaydi.");
            return;
        }
        // SuperAdmin'ni faqat asosiy (yaratuvchi) SuperAdmin o'chira oladi
        if (x.getRole() == Role.SUPERADMIN && !isCreator(me)) {
            sender.edit(chatId, msgId, "⚠️ SuperAdmin'ni faqat asosiy (yaratuvchi) "
                    + "SuperAdmin faolsizlantira oladi.");
            return;
        }
        x.setActive(false);
        userRepo.save(x);
        audit.log(me.getId(), "USER_FAOLSIZLANTIRILDI", "user", x.getId(),
                me.getFullName() + " faolsizlantirdi: " + x.getFullName() + " (" + x.getRole() + ")");
        sender.edit(chatId, msgId, "🚫 <b>" + esc(x.getFullName())
                + "</b> faolsizlantirildi. U endi botdan foydalana olmaydi.");
    }
}
