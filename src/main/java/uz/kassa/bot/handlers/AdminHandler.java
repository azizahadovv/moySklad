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
import static uz.kassa.bot.handlers.AdminSupport.*;

/** SuperAdmin oqimlari (TZ 8.3): foydalanuvchi/kassa boshqaruvi, boshlang'ich qoldiqlar. */
@Component
@RequiredArgsConstructor
public class AdminHandler {

    private final Sender sender;
    private final NameService names;
    private final KassaRepo kassaRepo;
    private final BuxgalterHandler bux;
    private final uz.kassa.service.moysklad.MoySkladSyncService syncService;
    private final uz.kassa.service.AuditService audit;
    private final PermService permSvc;
    private final uz.kassa.config.AppProps props;
    private final uz.kassa.service.BalansService balansSvc;
    private final uz.kassa.service.SettingsService settings;
    private final uz.kassa.scheduler.Jobs jobs;
    private final NotifyAdminHandler notifyAdmin;
    private final MenuSchemaHandler menuSchemaH;
    private final MenuSupport menus;
    private final MenuSchemaService schema;
    private final AdminSupport sup;
    private final StatsHandler statsH;
    private final OtdelHandler otdelH;
    private final BalanceAdminHandler balanceH;
    private final KassaAdminHandler kassaH;
    private final UsersAdminHandler usersH;
    private final PermAdminHandler permH;
    private final SettingsAdminHandler settingsH;
    private final MoySkladAdminHandler msH;
    private final MoySkladNamesHandler namesH;
    private final CalendarHandler calH;


    /* ============================ MATN ============================ */

    public boolean onText(AppUser u, Session s, String text, long chatId) {
        if (u.getRole() == Role.KASSIR) return false;

        // 💰 Pul qabul qilish: summa kiritish (Buxgalter ham, Admin ham)
        if (s.state == Session.State.ADM_QB_SUM) { otdelH.qbSum(u, s, text, chatId); return true; }

        if (u.getRole() == Role.SUPERADMIN) switch (s.state) {
            case ADM_AU_TGID -> { usersH.auTgId(s, text, chatId); return true; }
            case ADM_AU_NAME -> { usersH.auName(s, text, chatId); return true; }
            case ADM_AK_NAME -> { kassaH.akName(s, text, chatId); return true; }
            case ADM_AK_MSID -> { kassaH.akFinish(s, text, chatId); return true; }
            case ADM_IB_NAQD -> { balanceH.ibNaqd(s, text, chatId); return true; }
            case ADM_IB_KLIK -> { balanceH.ibFinish(u, s, text, chatId); return true; }
            case ADM_IB_SANA -> { balanceH.ibSana(u, s, text, chatId); return true; }
            case ADM_KR_SUM -> { balanceH.krSum(s, text, chatId); return true; }
            case ADM_KR_IZOH -> { balanceH.krIzoh(u, s, text, chatId); return true; }
            case ADM_KR_VAQT -> { balanceH.krVaqt(u, s, text, chatId); return true; }
            case ADM_CK_SUM -> { kassaH.ckSum(s, text, chatId); return true; }
            case ADM_CK_SANA -> { kassaH.ckSana(u, s, text, chatId); return true; }
            case ADM_LB_NAME -> { settingsH.labelName(s, text, chatId); return true; }
            case ADM_MS_TOKEN -> { msH.msTokenSave(u, s, text, chatId); return true; }
            case ADM_NM_NAME -> { namesH.nmNameSave(u, s, text, chatId); return true; }
            case ADM_CG_ID -> { settingsH.cgIdSave(u, s, text, chatId); return true; }
            case ADM_CG_FOOTER -> { settingsH.cgFooterSave(u, s, text, chatId); return true; }
            case ADM_LS_DATE -> { msH.lsSave(u, s, text, chatId); return true; }
            case ADM_NF_NAME, ADM_NF_TPL, ADM_NF_TIMES, ADM_NF_CHAT, ADM_NF_DEL, ADM_NF_ONCE, ADM_NF_BTN -> {
                if (notifyAdmin.onText(u, s, text, chatId)) return true;
            }
            default -> { }
        }

        // Panel ichida bo'lsa — pastki menu tugmalari bo'yicha navigatsiya
        String nav = s.getStr("nav");
        if (nav != null && handleNav(u, s, nav, text, chatId)) return true;

        // Bosh menyu: eski nomlar → bosh menyu; qolgani — sxemali dispatch (ostmenyu yoki amal)
        switch (text) {
            case "🏪 KASSA", "👑 АДМИН ПАНЕЛ", "📊 ПАНЕЛ" -> { toMain(u, s, chatId); return true; }
            default -> { }
        }
        if (dispatch(u, s, text, chatId)) return true;
        switch (text) {
            case "💰 БУГУНГИ ТУШУМ" -> { statsH.tushumAll(s, chatId); return true; }
            case "💰 Баланс" -> {
                syncService.syncIfStale(45);
                // Foydalanuvchi qarori: Баланс bosilganda DOIM avval НАҚД oynasi ochiladi
                // (КЛИК/ЖАМИ — pastdagi tugmalar orqali)
                sup.sendContent(s, chatId, balansSvc.buildAll(uz.kassa.service.BalansService.NAQD),
                        sup.balansKb());
                return true;
            }
            default -> { }
        }

        if (u.getRole() != Role.SUPERADMIN) return false;
        return switch (text) {
            case "👥 Foydalanuvchi qo'shish" -> { usersH.auStart(s, chatId); yield true; }
            case "🏪 Kassa qo'shish" -> {
                s.reset(); s.state = Session.State.ADM_AK_NAME;
                sender.send(chatId, "🏪 <b>Yangi kassa</b>\n\nKassa nomini kiriting:", cancelOnly());
                yield true;
            }
            case "💼 Boshlang'ich qoldiq" -> { balanceH.ibStart(s, chatId); yield true; }
            case "👤 Foydalanuvchilar" -> { usersH.listUsers(u, chatId); yield true; }
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
            case "qbu" -> otdelH.qbUser(u, s, arg, chatId, msgId);
            case "qbd" -> otdelH.qbDate(u, s, arg, chatId, msgId);
            case "cal" -> calH.calCb(u, s, arg, chatId, msgId);
            case "rxm" -> statsH.rasxodMenu(s, chatId, msgId);
            case "audm" -> statsH.auditMenu(s, chatId, msgId);
            case "aud" -> statsH.auditView(s, Long.parseLong(arg), chatId, msgId);
            case "aux" -> statsH.auditExcel(Long.parseLong(arg), chatId);
            case "lbm" -> settingsH.labelList(s, chatId, msgId);
            case "lb" -> settingsH.labelPick(s, Integer.parseInt(arg), chatId, msgId);
            case "lbr" -> settingsH.labelRenameStart(s, Integer.parseInt(arg), chatId, msgId);
            case "lbh" -> settingsH.labelHideToggle(s, Integer.parseInt(arg), chatId, msgId);
            case "msk" -> msH.msToken(s, chatId, msgId);
            case "msr" -> namesH.msNamesMenu(chatId, msgId);
            case "msrp" -> namesH.msNamesPreview(chatId, msgId);
            case "msry" -> namesH.msNamesApply(u, chatId, msgId);
            case "msn" -> namesH.msNameList(chatId, msgId);
            case "msni" -> namesH.msNameItem(arg, chatId, msgId);
            case "msne" -> namesH.msNameEditStart(s, arg, chatId, msgId);
            case "msnu" -> namesH.msNameUnlock(u, arg, chatId, msgId);
            case "prm" -> permH.permMenu(s, chatId, msgId);
            case "prc" -> permH.permCard(u, Long.parseLong(arg), chatId, msgId);
            case "prs" -> permH.permGrid("user", Long.parseLong(arg), chatId, msgId);
            case "prt" -> permH.permToggle(u, "user", arg, chatId, msgId);
            case "prko" -> permH.permKassaList(chatId, msgId);
            case "prk" -> permH.permGrid("kassa", Long.parseLong(arg), chatId, msgId);
            case "prq" -> permH.permToggle(u, "kassa", arg, chatId, msgId);
            case "prr" -> permH.permRolePick(u, Long.parseLong(arg), chatId, msgId);
            case "prz" -> permH.permRoleApply(u, arg, chatId, msgId);
            case "prx" -> permH.permDeactConfirm(u, Long.parseLong(arg), chatId, msgId);
            case "prxy" -> {
                usersH.deactivate(u, Long.parseLong(arg), chatId, msgId);
                permH.permMenu(s, chatId, 0);
            }
            case "mske" -> {
                s.state = Session.State.ADM_MS_TOKEN;
                sender.edit(chatId, msgId, "🔑 <b>Yangi MoySklad API kalitini yuboring</b>\n\n"
                        + "MoySklad → Sozlamalar → Tokenlar bo'limidan olinadi.\n"
                        + "Bekor qilish uchun «-» yuboring.");
            }
            case "cg" -> settingsH.clickGroupMenu(s, chatId, msgId);
            case "kml" -> settingsH.kartaMasList(chatId, msgId);
            case "kmc" -> settingsH.kartaMasCard(Long.parseLong(arg), chatId, msgId);
            case "kmu" -> settingsH.kartaMasUsers(Long.parseLong(arg), chatId, msgId);
            case "kms" -> settingsH.kartaMasSet(u, arg, chatId, msgId);
            case "kmx" -> settingsH.kartaMasClear(u, Long.parseLong(arg), chatId, msgId);
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
                settingsH.clickGroupMenu(s, chatId, msgId);
            }
            case "cgx" -> {
                // Eski xabarlardagi argumentsiz tugma bosilsa — shunchaki menyu yangilanadi
                if (!arg.isBlank()) {
                    long gid = Long.parseLong(arg);
                    jobs.removeClickChat(gid);
                    audit.log(u.getId(), "CLICK_GROUP_OCHIRILDI", "settings", null,
                            u.getFullName() + " guruh/kanalni hisobot ro'yxatidan o'chirdi: " + gid);
                }
                settingsH.clickGroupMenu(s, chatId, msgId);
            }
            case "cgs" -> settingsH.clickScheduleMenu(s, chatId, msgId);
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
                settingsH.clickScheduleMenu(s, chatId, msgId);
            }
            case "cgo" -> {   // minut siljishi: -20…+20
                settings.set(uz.kassa.scheduler.Jobs.CLICK_OFFSET_KEY, arg);
                audit.log(u.getId(), "CLICK_JADVAL", "settings", null,
                        u.getFullName() + " hisobot minut siljishini o'zgartirdi: " + arg + " min");
                settingsH.clickScheduleMenu(s, chatId, msgId);
            }
            case "cgw" -> {
                String[] w = arg.split(":");
                settings.set(uz.kassa.scheduler.Jobs.CLICK_FROM_KEY, w[0]);
                settings.set(uz.kassa.scheduler.Jobs.CLICK_TO_KEY, w[1]);
                audit.log(u.getId(), "CLICK_JADVAL", "settings", null,
                        u.getFullName() + " hisobot oynasini o'zgartirdi: " + w[0] + ":00–" + w[1] + ":00");
                settingsH.clickScheduleMenu(s, chatId, msgId);
            }
            case "lsm" -> msH.ledgerMenu(s, chatId, msgId);
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
                msH.ledgerMenu(s, chatId, msgId);
            }
            case "diag" -> msH.diagMenu(s, chatId, msgId);
            case "rld" -> msH.reloadConfirm(s, chatId, msgId);
            case "rldc" -> msH.reloadCommit(u, chatId, msgId);
            case "fixo" -> {
                s.reset();
                s.state = Session.State.ADM_KR_OWNER;
                balanceH.krOwner(s, arg, chatId, msgId);
            }
            case "gu" -> usersH.auPick(s, arg, chatId, msgId);
            case "me" -> usersH.auEmp(s, arg, chatId, msgId);
            case "rl" -> usersH.auRole(s, arg, chatId, msgId);
            case "ks" -> usersH.auKassa(s, arg, chatId, msgId);
            case "gr" -> kassaH.akGroup(s, arg, chatId, msgId);
            case "ib" -> balanceH.ibOwner(s, arg, chatId, msgId);
            case "ibd" -> balanceH.ibSanaBtn(u, s, arg, chatId, msgId);
            case "kro" -> balanceH.krOwner(s, arg, chatId, msgId);
            case "krm" -> balanceH.krMt(s, arg, chatId, msgId);
            case "krd" -> balanceH.krSanaBtn(s, arg, chatId, msgId);
            case "krt" -> balanceH.krVaqtNow(u, s, chatId, msgId);
            case "krok" -> balanceH.krCommit(u, s, chatId, msgId);
            case "bl" -> sender.edit(chatId, msgId,
                    balansSvc.buildAll(arg.isEmpty() ? 'j' : arg.charAt(0)), sup.balansKb());
            case "rz" -> kassaH.rzPick(s, arg, chatId, msgId);
            case "rzc" -> kassaH.rzCommit(u, s, arg, chatId, msgId);
            case "ckq" -> kassaH.ckStart(s, arg, chatId, msgId);
            case "ckd" -> kassaH.ckSanaBtn(u, s, arg, chatId, msgId);
            case "ux" -> usersH.deactivate(u, Long.parseLong(arg), chatId, msgId);
            default -> {
                // 🔔 Bildirishnomalar — «a:nf…» (alohida handler)
                if (cmd.startsWith("nf")) return notifyAdmin.onCallback(u, s, cmd, arg, chatId, msgId);
                // 🧩 Menyu tartibi — «a:mo…»
                if (cmd.startsWith("mo")) return menuSchemaH.onCallback(u, s, cmd, arg, chatId, msgId);
                return false;
            }
        }
        return true;
    }


    private boolean handleNav(AppUser u, Session s, String nav, String text, long chatId) {
        if (text.equals("⬅️ Orqaga")) { navBack(u, s, nav, chatId); return true; }
        String lvl = nav.split(":")[0];
        switch (lvl) {
            case "panel" -> {
                switch (text) {
                    case "🏬 Отдел" -> sup.navTo(u, s, "otdel", chatId,
                            "🏬 <b>Отдел</b>\n\nKassani tanlang:", sup.otdelLabels());
                    case "⚙️ Настройка" -> {
                        if (u.getRole() != Role.SUPERADMIN) return false;
                        sup.navTo(u, s, "sozlash", chatId, "⚙️ <b>Настройка</b>", SOZ_GROUP_LABELS);
                    }
                    case "📈 Статистика" -> sup.navTo(u, s, "stat", chatId,
                            "📈 <b>Статистика</b>", sup.statLabels(u));
                    case "💰 Бугунги тушум" -> statsH.tushumAll(s, chatId);
                    case "🧾 Расходлар" -> statsH.rasxodMenu(s, chatId, 0);
                    case "🏪 Кассалар холати" -> bux.overview(chatId);
                    case "📥 Кутилаётганлар" -> bux.pendingList(chatId);
                    default -> { return false; }
                }
            }
            case "otdel" -> {
                if (text.equals(OSN_LABEL)) {
                    sup.navTo(u, s, "kassab", chatId,
                            "🏦 <b>Отдел основной</b> (Буxгалтерия)\n\nBo'limni tanlang:", OSN_MENU);
                    return true;
                }
                Kassa k = sup.kassaByLabel(text);
                if (k == null) return false;
                sup.navTo(u, s, "kassa:" + k.getId(), chatId,
                        "🏪 <b>" + esc(k.getName()) + "</b>\n\nBo'limni tanlang:", KASSA_MENU);
            }
            case "kassa" -> {
                long id = sup.idOf(nav);
                switch (text) {
                    case "💰 Бугунги тушум" -> { syncService.syncIfStale(45); otdelH.kassaTushum(s, id, chatId, 0); }
                    case "📆 Давр танлаш" -> sup.navTo(u, s, "davr:" + id, chatId,
                            "📆 <b>Давр танлаш</b>\n\nDavrni tanlang:", PERIODS);
                    case "💵 Топширилмаган пул" -> {
                        syncService.syncIfStale(45);
                        otdelH.kassaTopshirilmagan(s, id, chatId, 0);
                    }
                    case "💸 Расход" -> {
                        syncService.syncIfStale(45);
                        otdelH.kassaRasxodPanel(s, id, chatId);
                    }
                    default -> { return false; }
                }
            }
            case "kassab" -> {
                switch (text) {
                    case "💵 Пул қолдиғи" -> { syncService.syncIfStale(45); otdelH.osnovnoyQoldiq(s, chatId); }
                    case "🏦 Ҳисобот" -> statsH.buxReport(s, chatId);
                    default -> { return false; }
                }
            }
            case "davr" -> {
                if (text.equals("🗓 Kalendar")) { calH.calOpen(s, chatId, 0, "k" + sup.idOf(nav)); return true; }
                String code = sup.codeOf(text);
                if (code == null) return false;
                syncService.syncIfStale(45);
                otdelH.kassaPeriodStats(s, sup.idOf(nav), code, chatId, 0);
            }
            case "m", "stat", "sozlash", "sozg" -> {
                // Erkin (sxemali) menyu: ostmenyu yorlig'i → ochish, amal → registr
                if (!dispatch(u, s, text, chatId)) return false;
            }
            case "__legacy_sozlash__" -> {
                switch (text) {
                    case "🏪 Касса" -> sup.navTo(u, s, "sozkassa", chatId, "🏪 <b>Касса</b>",
                            SOZKASSA_MENU);
                    case "👥 Фойдаланувчилар" -> sup.navTo(u, s, "sozuser", chatId,
                            "👥 <b>Фойдаланувчилар</b>", SOZUSER_MENU);
                    case "💼 Бошланғич қолдиқ" -> { balanceH.ibStart(s, chatId); s.data.put("nav", "sozlash"); }
                    case "🛠 Корректировка" -> { balanceH.krStart(s, chatId); s.data.put("nav", "sozlash"); }
                    case "📋 Аудит" -> statsH.auditMenu(s, chatId, 0);
                    case "🏷 Тугма номлари" -> settingsH.labelList(s, chatId, 0);
                    case "🧩 Меню тартиби" -> menuSchemaH.menu(s, chatId, 0);
                    case "🔑 MoySklad API" -> msH.msToken(s, chatId, 0);
                    case "🔄 Номлар (MoySklad)" -> namesH.msNamesMenu(chatId, 0);
                    case "📣 Гуруҳлар/Каналлар" -> settingsH.clickGroupMenu(s, chatId, 0);
                    case "🔔 Билдиришномалар" -> notifyAdmin.menu(s, chatId, 0);
                    case "💳 Карта масъуллари" -> settingsH.kartaMasList(chatId, 0);
                    case "📅 Ledger санаси" -> msH.ledgerMenu(s, chatId, 0);
                    case "🩺 Диагностика" -> msH.diagMenu(s, chatId, 0);
                    case "📥 Қайта юклаш" -> msH.reloadConfirm(s, chatId, 0);
                    // Ҳуқуқлар — barcha SuperAdmin'larga ochiq; SUPERADMIN'larga tegadigan
                    // amallar ichkarida faqat yaratuvchiga ko'rsatiladi/ruxsat etiladi.
                    case "👁 Ҳуқуқлар" -> permH.permMenu(s, chatId, 0);
                    case "♻️ Нол бошлаш" -> kassaH.rzStart(s, chatId);
                    default -> { return false; }
                }
            }
            case "sozkassa" -> {
                switch (text) {
                    case "➕ Касса қўшиш" -> {
                        s.state = Session.State.ADM_AK_NAME;
                        sender.send(chatId, "🏪 <b>Yangi kassa</b>\n\nKassa nomini kiriting:");
                    }
                    case "🗂 Отдел боғлаш" -> kassaH.kassaOtdelList(chatId, 0);
                    case "🚫 Касса ўчириш" -> sup.navTo(u, s, "kassadel", chatId,
                            "🚫 <b>Касса ўчириш</b>\n\nQaysi kassani o'chirasiz?", sup.kassaLabels());
                    default -> { return false; }
                }
            }
            case "kassadel" -> {
                Kassa k = sup.kassaByLabel(text);
                if (k == null) return false;
                sup.navTo(u, s, "kassadelc:" + k.getId(), chatId,
                        "⚠️ <b>" + esc(k.getName()) + "</b> kassasi o'chirilsinmi?\n\n"
                                + "Kassa faolsizlanadi — tarix saqlanadi.",
                        List.of("✅ Ha, o'chirilsin", "❌ Yo'q"));
            }
            case "kassadelc" -> {
                if (text.startsWith("✅")) {
                    long id = sup.idOf(nav);
                    String block = kassaH.kassaDeactivateBlock(id);
                    if (block != null) {
                        sup.navTo(u, s, "sozkassa", chatId, block, SOZKASSA_MENU);
                        return true;
                    }
                    kassaRepo.findById(id).ifPresent(k -> { k.setActive(false); kassaRepo.save(k); });
                    sup.navTo(u, s, "sozkassa", chatId, "🚫 Kassa faolsizlantirildi",
                            SOZKASSA_MENU);
                } else if (text.startsWith("❌")) {
                    sup.navTo(u, s, "sozkassa", chatId, "🏪 <b>Касса</b>",
                            SOZKASSA_MENU);
                } else return false;
            }
            case "sozuser" -> {
                switch (text) {
                    case "➕ Фойдаланувчи қўшиш" -> { usersH.auStart(s, chatId); s.data.put("nav", "sozuser"); }
                    case "🔄 Рол ўзгартириш" -> sup.navTo(u, s, "roluser", chatId,
                            "🔄 <b>Рол ўзгартириш</b>\n\nFoydalanuvchini tanlang:", usersH.userLabels());
                    case "🚫 Фойдаланувчини ўчириш" -> usersH.listUsers(u, chatId);
                    default -> { return false; }
                }
            }
            case "roluser" -> {
                AppUser x = usersH.userByLabel(text);
                if (x == null) return false;
                sup.navTo(u, s, "rolpick:" + x.getId(), chatId,
                        "🔄 <b>" + esc(x.getFullName()) + "</b> (hozir: " + x.getRole()
                                + (x.getKassaId() == null ? "" :
                                   " · " + esc(names.owner(OwnerType.KASSA, x.getKassaId()))) + ")\n\n"
                                + "Yangi rolni tanlang:", usersH.roleLabels());
            }
            case "rolpick" -> {
                if (!usersH.applyRole(u, sup.idOf(nav), text, chatId)) return false;
                sup.navTo(u, s, "sozuser", chatId, "👥 <b>Фойдаланувчилар</b>", SOZUSER_MENU);
            }
            case "__legacy_stat__" -> {
                switch (text) {
                    case "💰 Бугунги тушум" -> statsH.tushumAll(s, chatId);
                    case "🧾 Расходлар" -> statsH.rasxodMenu(s, chatId, 0);
                    case "🏪 Кассалар холати" -> bux.overview(chatId);
                    case "🧾 Карзлар реестр" -> bux.debtsRegistry(chatId);
                    case "📜 История" -> bux.historyMenu(chatId);
                    case "👥 Фойдаланувчилар умумий" -> {
                        if (u.getRole() != Role.SUPERADMIN) return false;
                        usersH.listUsers(u, chatId);
                    }
                    case "🏦 Бухгалтерия" -> statsH.buxReport(s, chatId);
                    case "💼 Салдо" -> { syncService.syncIfStale(45); statsH.saldoKassa(s, "B", chatId, 0); }
                    case "📲 Кликлар" -> kassaH.clickMenu(u, s, chatId, 0);
                    case "📊 Свод" -> sup.navTo(u, s, "svod", chatId, "📊 <b>Свод</b>\n\nExcel turini tanlang:",
                            List.of("📗 Умумий Excel", "📘 Даврий Excel", "📙 Отдел Excel"));
                    default -> { return false; }
                }
            }
            case "svod" -> {
                switch (text) {
                    case "📗 Умумий Excel" -> statsH.genExcel(chatId, 0, "m", null);
                    case "📘 Даврий Excel" -> sup.navTo(u, s, "svoddavr", chatId,
                            "📘 <b>Даврий Excel</b>\n\nDavrni tanlang:", PERIODS);
                    case "📙 Отдел Excel" -> sup.navTo(u, s, "svodotd", chatId,
                            "📙 <b>Отдел Excel</b>\n\nKassani tanlang:", sup.kassaLabels());
                    default -> { return false; }
                }
            }
            case "svoddavr" -> {
                if (text.equals("🗓 Kalendar")) { calH.calOpen(s, chatId, 0, "x"); return true; }
                String code = sup.codeOf(text);
                if (code == null) return false;
                statsH.genExcel(chatId, 0, code, null);
            }
            case "svodotd" -> {
                Kassa k = sup.kassaByLabel(text);
                if (k == null) return false;
                sup.navTo(u, s, "svodotdd:" + k.getId(), chatId,
                        "📙 <b>Отдел Excel</b> — " + esc(k.getName()) + "\n\nDavrni tanlang:", PERIODS);
            }
            case "svodotdd" -> {
                if (text.equals("🗓 Kalendar")) { calH.calOpen(s, chatId, 0, "xo" + sup.idOf(nav)); return true; }
                String code = sup.codeOf(text);
                if (code == null) return false;
                statsH.genExcel(chatId, 0, code, sup.idOf(nav));
            }
            default -> { return false; }
        }
        return true;
    }


    /** «⬅️ Orqaga» — bir pog'ona yuqoriga. */
    private void navBack(AppUser u, Session s, String nav, long chatId) {
        String lvl = nav.split(":")[0];
        switch (lvl) {
            case "panel", "otdel", "sozlash", "stat" -> toMain(u, s, chatId);
            case "sozg" -> sup.navMenu(u, s, "sozlash", chatId);
            case "m" -> {
                String key = nav.substring(2);
                String parent = schema.parentOf(key);
                if (parent == null) toMain(u, s, chatId); else sup.navMenu(u, s, parent, chatId);
            }
            case "kassa", "kassab" -> sup.navTo(u, s, "otdel", chatId,
                    "🏪 <b>Кассалар</b>\n\nKassani tanlang:", sup.otdelLabels());
            case "davr" -> {
                long id = sup.idOf(nav);
                sup.navTo(u, s, "kassa:" + id, chatId,
                        "🏪 <b>" + esc(names.owner(OwnerType.KASSA, id)) + "</b>\n\nBo'limni tanlang:",
                        KASSA_MENU);
            }
            case "sozkassa", "sozuser" -> {
                String owner = schema.menuOf(lvl.equals("sozkassa") ? "🏪 Касса" : "👥 Фойдаланувчилар");
                if (owner == null || owner.startsWith("main.")) toMain(u, s, chatId); else sup.navMenu(u, s, owner, chatId);
            }
            case "roluser", "rolpick" -> sup.navTo(u, s, "sozuser", chatId,
                    "👥 <b>Фойдаланувчилар</b>", SOZUSER_MENU);
            case "kassadel", "kassadelc" -> sup.navTo(u, s, "sozkassa", chatId, "🏪 <b>Касса</b>",
                    SOZKASSA_MENU);
            case "saldo", "svod" -> {
                String owner = schema.menuOf("📊 Свод");
                if (owner == null || owner.startsWith("main.")) toMain(u, s, chatId); else sup.navMenu(u, s, owner, chatId);
            }
            case "svoddavr", "svodotd" -> sup.navTo(u, s, "svod", chatId,
                    "📊 <b>Свод</b>\n\nExcel turini tanlang:",
                    List.of("📗 Умумий Excel", "📘 Даврий Excel", "📙 Отдел Excel"));
            case "svodotdd" -> sup.navTo(u, s, "svodotd", chatId,
                    "📙 <b>Отдел Excel</b>\n\nKassani tanlang:", sup.kassaLabels());
            default -> toMain(u, s, chatId);
        }
    }


    /** Bosh menyuga qaytish: panel xabarlari o'chiriladi, rolga mos klaviatura (🌐 bilan). */
    private void toMain(AppUser u, Session s, long chatId) {
        s.data.remove("nav");
        sup.deletePrevPanel(s, chatId);
        sender.send(chatId, "🏠 Bosh menyu", menus.menuFor(u));
    }


    /* ==================================================================
     * SXEMALI DISPATCH — tugma qaysi menyuda turishidan qat'i nazar:
     *   ostmenyu yorlig'i (MenuSchemaService.SUBMENUS) → o'sha menyu ochiladi,
     *   amal (ACTIONS) → bajariladi. Shuning uchun admin web'da tugmalarni
     *   menyular orasida bemalol ko'chira oladi.
     * ================================================================== */

    @FunctionalInterface
    private interface Action { void run(AppUser u, Session s, long chatId); }

    private final Map<String, Action> ACTIONS = new LinkedHashMap<>();

    @jakarta.annotation.PostConstruct
    void registerActions() {
        // bosh menyu
        ACTIONS.put("🏪 Кассалар", (u, s, c) -> sup.navTo(u, s, "otdel", c, "🏪 <b>Кассалар</b>\n\nKassani tanlang:", sup.otdelLabels()));
        ACTIONS.put("📥 Кутилаётганлар", (u, s, c) -> bux.pendingList(c));
        // 📊 Ҳисоботлар
        ACTIONS.put("💰 Бугунги тушум", (u, s, c) -> statsH.tushumAll(s, c));
        ACTIONS.put("🧾 Расходлар", (u, s, c) -> statsH.rasxodMenu(s, c, 0));
        ACTIONS.put("🏪 Кассалар холати", (u, s, c) -> bux.overview(c));
        ACTIONS.put("🧾 Карзлар реестр", (u, s, c) -> bux.debtsRegistry(c));
        ACTIONS.put("📜 История", (u, s, c) -> bux.historyMenu(c));
        ACTIONS.put("👥 Фойдаланувчилар умумий", (u, s, c) -> usersH.listUsers(u, c));
        ACTIONS.put("🏦 Бухгалтерия", (u, s, c) -> statsH.buxReport(s, c));
        ACTIONS.put("💼 Салдо", (u, s, c) -> { syncService.syncIfStale(45); statsH.saldoKassa(s, "B", c, 0); });
        ACTIONS.put("📲 Кликлар", (u, s, c) -> kassaH.clickMenu(u, s, c, 0));
        ACTIONS.put("📊 Свод", (u, s, c) -> sup.navTo(u, s, "svod", c, "📊 <b>Свод</b>\n\nExcel turini tanlang:",
                List.of("📗 Умумий Excel", "📘 Даврий Excel", "📙 Отдел Excel")));
        // ⚙️ Настройка guruhlari ichidagi amallar
        ACTIONS.put("🏪 Касса", (u, s, c) -> sup.navTo(u, s, "sozkassa", c, "🏪 <b>Касса</b>", SOZKASSA_MENU));
        ACTIONS.put("👥 Фойдаланувчилар", (u, s, c) -> sup.navTo(u, s, "sozuser", c, "👥 <b>Фойдаланувчилар</b>", SOZUSER_MENU));
        ACTIONS.put("💼 Бошланғич қолдиқ", (u, s, c) -> { String nav = s.getStr("nav"); balanceH.ibStart(s, c); if (nav != null) s.data.put("nav", nav); });
        ACTIONS.put("🛠 Корректировка", (u, s, c) -> { String nav = s.getStr("nav"); balanceH.krStart(s, c); if (nav != null) s.data.put("nav", nav); });
        ACTIONS.put("📋 Аудит", (u, s, c) -> statsH.auditMenu(s, c, 0));
        ACTIONS.put("🏷 Тугма номлари", (u, s, c) -> settingsH.labelList(s, c, 0));
        ACTIONS.put("🧩 Меню тартиби", (u, s, c) -> menuSchemaH.menu(s, c, 0));
        ACTIONS.put("🔑 MoySklad API", (u, s, c) -> msH.msToken(s, c, 0));
        ACTIONS.put("🔄 Номлар (MoySklad)", (u, s, c) -> namesH.msNamesMenu(c, 0));
        ACTIONS.put("📣 Гуруҳлар/Каналлар", (u, s, c) -> settingsH.clickGroupMenu(s, c, 0));
        ACTIONS.put("🔔 Билдиришномалар", (u, s, c) -> notifyAdmin.menu(s, c, 0));
        ACTIONS.put("💳 Карта масъуллари", (u, s, c) -> settingsH.kartaMasList(c, 0));
        ACTIONS.put("📅 Ledger санаси", (u, s, c) -> msH.ledgerMenu(s, c, 0));
        ACTIONS.put("🩺 Диагностика", (u, s, c) -> msH.diagMenu(s, c, 0));
        ACTIONS.put("📥 Қайта юклаш", (u, s, c) -> msH.reloadConfirm(s, c, 0));
        ACTIONS.put("👁 Ҳуқуқлар", (u, s, c) -> permH.permMenu(s, c, 0));
        ACTIONS.put("♻️ Нол бошлаш", (u, s, c) -> kassaH.rzStart(s, c));
    }

    /** Sxemali dispatch. false — bu tugma erkin menyularga tegishli emas. */
    private boolean dispatch(AppUser u, Session s, String text, long chatId) {
        boolean sa = u.getRole() == Role.SUPERADMIN;
        String sub = MenuSchemaService.SUBMENUS.get(text);
        if (sub != null) {
            if (!sa && MenuSchemaService.SA_ONLY.contains(text)) return false;
            sup.navMenu(u, s, sub, chatId);
            return true;
        }
        Action a = ACTIONS.get(text);
        if (a == null) return false;
        if (!sa && MenuSchemaService.SA_ONLY.contains(text)) return false;
        a.run(u, s, chatId);
        return true;
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
                && !java.util.Set.of("qb", "qm", "kt", "kr", "kpp", "sdk", "kq", "bq", "pnd").contains(a[0])) return;
        // Pul ko'rsatadigan sahifalar ochilganda avval MoySklad'dan yangilanadi
        switch (a[0]) {
            case "kt", "kr", "kpp", "sdk", "kq", "bq" -> syncService.syncIfStale(45);
            default -> { }
        }
        switch (a[0]) {
            case "main" -> panelMain(chatId, msgId);
            case "otd"  -> otdelH.otdel(chatId, msgId);
            case "k"    -> otdelH.kassaMenu(Long.parseLong(a[1]), chatId, msgId);
            case "kt"   -> otdelH.kassaTushum(s, Long.parseLong(a[1]), chatId, msgId);
            case "kr"   -> otdelH.kassaRasxodPanel(s, Long.parseLong(a[1]), chatId);
            case "kq"   -> otdelH.kassaTopshirilmagan(s, Long.parseLong(a[1]), chatId, msgId);
            case "pnd"  -> bux.pendingList(chatId, a.length > 1 ? Long.parseLong(a[1]) : null);
            case "bq"   -> otdelH.osnovnoyQoldiq(s, chatId);
            case "kd"   -> otdelH.kassaDavr(Long.parseLong(a[1]), chatId, msgId);
            case "kpp"  -> otdelH.kassaPeriodStats(s, Long.parseLong(a[1]), a[2], chatId, msgId);
            case "set"  -> settingsH.settingsMenu(chatId, msgId);
            case "sk"   -> kassaH.setKassa(chatId, msgId);
            case "skd"  -> kassaH.kassaDeleteList(chatId, msgId);
            case "skx"  -> kassaH.kassaDeleteConfirm(Long.parseLong(a[1]), chatId, msgId);
            case "sky"  -> kassaH.kassaDeactivate(Long.parseLong(a[1]), chatId, msgId);
            case "sko"  -> kassaH.kassaOtdelList(chatId, msgId);
            case "skg"  -> kassaH.kassaOtdelMenu(Long.parseLong(a[1]), chatId, msgId);
            case "skgs" -> kassaH.kassaOtdelSet(u, Long.parseLong(a[1]), a[2], false, chatId, msgId);
            case "skgm" -> kassaH.kassaOtdelSet(u, Long.parseLong(a[1]), a[2], true, chatId, msgId);
            case "skgx" -> kassaH.kassaOtdelClearConfirm(Long.parseLong(a[1]), chatId, msgId);
            case "skgy" -> kassaH.kassaOtdelClear(u, Long.parseLong(a[1]), chatId, msgId);
            case "sunew" -> { s.reset(); usersH.auStart(s, chatId); }
            case "sknew" -> {
                s.reset(); s.state = Session.State.ADM_AK_NAME;
                sender.edit(chatId, msgId, "🏪 <b>Yangi kassa</b>\n\nKassa nomini kiriting:");
            }
            case "su"   -> usersH.setUsers(chatId, msgId);
            case "st"   -> statsH.statMenu(chatId, msgId);
            case "dbt"  -> bux.debtsRegistry(chatId);
            case "his"  -> bux.historyMenu(chatId);
            case "usr"  -> usersH.listUsers(u, chatId);
            case "sd"   -> { syncService.syncIfStale(45); statsH.saldoKassa(s, "B", chatId, msgId); }
            case "ck"   -> kassaH.clickMenu(u, s, chatId, msgId);
            case "sdk"  -> statsH.saldoKassa(s, a[1], chatId, msgId);
            case "sv"   -> statsH.svodMenu(chatId, msgId);
            case "xe"   -> statsH.excelFlow(a, chatId, msgId);
            case "qb"   -> otdelH.qbStart(s, Long.parseLong(a[1]), chatId);
            case "qm"   -> otdelH.qbMoney(s, Long.parseLong(a[1]), a[2], chatId, msgId);
        }
    }


    /* ---------- daraja 1 ---------- */

    private void panelMain(long chatId, int msgId) {
        sup.show(chatId, msgId, "👑 <b>АДМИН ПАНЕЛ</b>\n\nBo'limni tanlang:", List.of(
                irow(btn("🏬 Отдел", "a:p:otd")),
                irow(btn("⚙️ Настройка", "a:p:set")),
                irow(btn("📈 Статистика", "a:p:st"))));
    }

}
