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

/**
 * Admin panel umumiy yordamchilari: navigatsiya (reply-menyu), xabar yuborish/tahrirlash, menyu ro'yxatlari, davr va huquq tekshiruvlari. Barcha admin handlerlar shundan foydalanadi.
 * (AdminHandler dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
public class AdminSupport {

    private final Sender sender;
    private final LedgerService ledger;
    private final KassaRepo kassaRepo;
    private final LabelService labelSvc;
    private final MenuSchemaService schema;
    private final PermService permSvc;
    private final uz.kassa.config.AppProps props;


    static final java.time.format.DateTimeFormatter DF =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");


    /* ==================================================================
     * 👑 АДМИН ПАНЕЛ — PASTKI MENU TUGMALARI bilan ichma-ich navigatsiya.
     * Har daraja reply-keyboard'ni almashtiradi, «⬅️ Orqaga» bir pog'ona
     * yuqoriga qaytaradi (bosh sahifaga emas).
     * ================================================================== */

    static final List<String> PERIODS =
            List.of("📆 Bugun", "Kecha", "7 kun", "30 kun", "Shu oy", "🗓 Kalendar");

    /** Kassa kartasi bo'limlari (reply-menyu). */
    static final List<String> KASSA_MENU =
            List.of("💰 Бугунги тушум", "📆 Давр танлаш", "💵 Топширилмаган пул", "💸 Расход");

    /** Основной отдел (Buxgalteriya) kartasi bo'limlari. */
    static final List<String> OSN_MENU =
            List.of("💵 Пул қолдиғи", "🏦 Ҳисобот");

    static final String OSN_LABEL = "🏦 Отдел основной";

    static final List<String> SOZLASH_MENU =
            List.of("🏪 Касса", "👥 Фойдаланувчилар", "💼 Бошланғич қолдиқ",
                    "🛠 Корректировка", "📋 Аудит",
                    "🏷 Тугма номлари", "🧩 Меню тартиби", "🔑 MoySklad API", "🔄 Номлар (MoySklad)", "👁 Ҳуқуқлар",
                    "📣 Гуруҳлар/Каналлар", "🔔 Билдиришномалар", "💳 Карта масъуллари", "📅 Ledger санаси",
                    "🩺 Диагностика", "📥 Қайта юклаш", "♻️ Нол бошлаш");

    static final List<String> STAT_MENU =
            List.of("💰 Бугунги тушум", "🧾 Расходлар",
                    "🏪 Кассалар холати", "🧾 Карзлар реестр", "📜 История",
                    "👥 Фойдаланувчилар умумий",
                    "🏦 Бухгалтерия", "💼 Салдо", "📲 Кликлар", "📊 Свод");

    /**
     * ⚙️ Настройка → 4 guruh (3 daraja qoidasi: bosh menyu → guruh → amal).
     * Tugmalarning o'zi (kanonik nomlar, handler'lar) o'zgarmagan — faqat guruhlangan.
     */
    static final java.util.LinkedHashMap<String, List<String>> SOZ_GROUPS = new java.util.LinkedHashMap<>();
    static {
        SOZ_GROUPS.put("🏢 Ташкилот", List.of("🏪 Касса", "👥 Фойдаланувчилар", "👁 Ҳуқуқлар",
                "💳 Карта масъуллари", "📣 Гуруҳлар/Каналлар"));
        SOZ_GROUPS.put("💼 Молия", List.of("💼 Бошланғич қолдиқ", "🛠 Корректировка",
                "📅 Ledger санаси", "♻️ Нол бошлаш"));
        SOZ_GROUPS.put("🔗 MoySklad", List.of("🔑 MoySklad API", "🔄 Номлар (MoySklad)",
                "📥 Қайта юклаш", "🩺 Диагностика"));
        SOZ_GROUPS.put("🎛 Интерфейс", List.of("🏷 Тугма номлари", "🧩 Меню тартиби",
                "🔔 Билдиришномалар", "📋 Аудит"));
    }
    static final List<String> SOZ_GROUP_LABELS = List.copyOf(SOZ_GROUPS.keySet());

    static final List<String> SOZUSER_MENU =
            List.of("➕ Фойдаланувчи қўшиш", "🔄 Рол ўзгартириш", "🚫 Фойдаланувчини ўчириш");


    static final List<String> SOZKASSA_MENU =
            List.of("➕ Касса қўшиш", "🗂 Отдел боғлаш", "🚫 Касса ўчириш");


    /* ==================================================================
     * 🗓 KALENDAR — davr (bir yoki bir necha kun) tanlash.
     * ctx: "k<id>" — kassa davr statistikasi, "x" — umumiy Excel,
     *      "xo<id>" — otdel Excel. Birinchi bosish — boshlanish,
     *      ikkinchi bosish — tugash sanasi (bitta kun uchun ikki marta o'sha kun).
     * ================================================================== */

    static final String[] OYLAR = {"Yanvar", "Fevral", "Mart", "Aprel", "May", "Iyun",
            "Iyul", "Avgust", "Sentabr", "Oktabr", "Noyabr", "Dekabr"};


    /* ==================================================================
     * 📋 АУДИТ — har bir foydalanuvchi qilgan amallar jurnali + Excel.
     * ================================================================== */

    static final java.time.format.DateTimeFormatter AUDIT_DF =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm");


    /** Panel nomi va bo'limlari — rol kesimida. */
    String panelTitle(AppUser u) {
        return "🏪 <b>KASSA</b>\n\nBo'limni tanlang:";
    }


    List<String> panelLabels(AppUser u) {
        return u.getRole() == Role.SUPERADMIN
                ? List.of("🏬 Отдел", "⚙️ Настройка", "📈 Статистика",
                          "💰 Бугунги тушум", "🧾 Расходлар", "🏪 Кассалар холати",
                          "📥 Кутилаётганлар")
                : List.of("🏬 Отдел", "📈 Статистика", "💰 Бугунги тушум",
                          "🧾 Расходлар", "🏪 Кассалар холати", "📥 Кутилаётганлар");
    }


    List<String> statLabels(AppUser u) {
        return u.getRole() == Role.SUPERADMIN
                ? STAT_MENU
                : List.of("💰 Бугунги тушум", "🧾 Расходлар",
                          "🏪 Кассалар холати", "🧾 Карзлар реестр", "📜 История",
                          "🏦 Бухгалтерия", "💼 Салдо", "📲 Кликлар", "📊 Свод");
    }


    /** Erkin (sxemali) menyuni ochish: nav = "m:<kalit>", tugmalar — MenuSchemaService joylashuvi. */
    void navMenu(AppUser u, Session s, String key, long chatId) {
        MenuSchemaService.MenuDef d = MenuSchemaService.MENUS.get(key);
        if (d == null || !d.free()) { navMenu(u, s, "sozlash", chatId); return; }
        navTo(u, s, "m:" + key, chatId, "<b>" + esc(d.title()) + "</b>\n\nBo'limni tanlang:", schema.current(key));
    }

    /** Eski nom — guruh sahifasi (endi sxemali menyu). */
    void navGroup(AppUser u, Session s, String group, long chatId) {
        String key = MenuSchemaService.SUBMENUS.get(group);
        navMenu(u, s, key == null ? "sozlash" : key, chatId);
    }


    /** Tugma qaysi guruhga tegishli (guruhga «Orqaga» uchun). */
    static String groupOf(String item) {
        for (var e : SOZ_GROUPS.entrySet()) if (e.getValue().contains(item)) return e.getKey();
        return null;
    }


    void navTo(AppUser u, Session s, String nav, long chatId, String title, List<String> labels) {
        s.data.put("nav", nav);
        deletePrevPanel(s, chatId);
        Integer id = sender.sendId(chatId, title, menuKb(u, labels));
        if (id != null) s.data.put("panelMsg", id);
    }


    /** Oldingi panel va kontent xabarlarini o'chirish — chatda faqat 2 ta xabar qoladi. */
    void deletePrevPanel(Session s, long chatId) {
        Object prev = s.data.remove("panelMsg");
        if (prev instanceof Integer i) sender.deleteMessage(chatId, i);
        Object c = s.data.remove("contentMsg");
        if (c instanceof Integer i2) sender.deleteMessage(chatId, i2);
    }


    /** Ma'lumot xabari: oldingisini o'chirib yuboradi — bittasi qoladi. */
    void sendContent(Session s, long chatId, String text, InlineKeyboardMarkup kb) {
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
        // 🧩 Menyu sxemasi: sozlangan bo'lsa tartib/ustun qo'llanadi, bo'lmasa avvalgidek (2 ustun)
        Keyboards.Arranged a = Keyboards.arrange(null, labels);
        List<String> shown = a.items().stream()
                .filter(l -> !LabelService.RENAMABLE.contains(l) || permSvc.visible(u, l))
                .filter(l -> u.getRole() == Role.SUPERADMIN || !MenuSchemaService.SA_ONLY.contains(l))
                .map(labelSvc::display).toList();
        Keyboards.addGrid(rows, shown, a.cols());
        var back = new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow();
        back.add("⬅️ Orqaga");
        rows.add(back);
        var m = new org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup();
        m.setKeyboard(rows);
        m.setResizeKeyboard(true);
        return m;
    }


    List<String> kassaLabels() {
        List<String> out = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            if (!k.isCashless()) out.add("🏪 " + k.getName());
        return out;
    }


    /** 🏬 Отдел ro'yxati: Основной отдел (Buxgalteriya) + faol kassalar. */
    List<String> otdelLabels() {
        List<String> out = new ArrayList<>();
        out.add(OSN_LABEL);
        out.addAll(kassaLabels());
        return out;
    }


    Kassa kassaByLabel(String text) {
        String name = text.startsWith("🏪 ") ? text.substring(3).trim() : text.trim();
        return kassaRepo.findByActiveTrueOrderByIdAsc().stream()
                .filter(k -> k.getName().equals(name)).findFirst().orElse(null);
    }


    String codeOf(String text) {
        return switch (text) {
            case "📆 Bugun" -> "t"; case "Kecha" -> "y";
            case "7 kun" -> "7"; case "30 kun" -> "30"; case "Shu oy" -> "m";
            default -> null;
        };
    }


    long idOf(String nav) { return Long.parseLong(nav.substring(nav.indexOf(':') + 1)); }


    /** Bu user .env dagi asosiy (yaratuvchi) SuperAdmin ID egasimi. */
    boolean isCreatorId(AppUser x) {
        Long t = props.getSuperadmin().getTelegramId();
        return t != null && t > 0 && t.equals(x.getTelegramId());
    }


    InlineKeyboardButton bk(String data) { return btn("⬅️ Orqaga", data); }


    /** 💰 Баланс ko'rinishlari orasida almashish tugmalari. */
    InlineKeyboardMarkup balansKb() {
        return inline(List.of(irow(
                btn("💵 Нақд", "a:bl:n"),
                btn("📲 Клик", "a:bl:k"),
                btn("💰 Жами", "a:bl:j"))));
    }


    void show(long chatId, int msgId, String text,
                      List<List<InlineKeyboardButton>> rows) {
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sender.send(chatId, text, null);   // menu-rejimda inline tugmalarsiz
    }


    String roleEmoji(Role r) {
        return switch (r) { case KASSIR -> "👤"; case BUXGALTER -> "🧮"; case SUPERADMIN -> "👑"; };
    }


    /** Aktor shu userni boshqara oladimi: SUPERADMIN'ga faqat yaratuvchi tegadi. */
    boolean canManage(AppUser actor, AppUser target) {
        return target.getRole() != Role.SUPERADMIN || isCreator(actor);
    }


    /* ---------- davr yordamchilari ---------- */

    java.time.LocalDate[] periodOf(String code) {
        java.time.LocalDate t = ledger.today();
        return switch (code) {
            case "t" -> new java.time.LocalDate[]{t, t};
            case "y" -> new java.time.LocalDate[]{t.minusDays(1), t.minusDays(1)};
            case "7" -> new java.time.LocalDate[]{t.minusDays(6), t};
            case "30" -> new java.time.LocalDate[]{t.minusDays(29), t};
            default -> new java.time.LocalDate[]{t.withDayOfMonth(1), t};
        };
    }


    String rangeLabel(java.time.LocalDate f, java.time.LocalDate t) {
        return f.equals(t) ? f.format(DF) : f.format(DF) + " — " + t.format(DF);
    }


    /* ==================================================================
     * ♻️ НОЛ БОШЛАШ (faqat SuperAdmin) — bugundan oldingi topshirilmagan
     * kunlarni yopib, kassa balanslarini (naqd+klik) 0 dan boshlatish.
     * ================================================================== */

    /** Asosiy (yaratuvchi) SuperAdmin — .env SUPERADMIN_TELEGRAM_ID egasi. */
    boolean isCreator(AppUser u) {
        if (u.getRole() != Role.SUPERADMIN) return false;
        Long t = props.getSuperadmin().getTelegramId();
        // .env da belgilanmagan bo'lsa — barcha SuperAdminlarga ruxsat (qulf bo'lib qolmasin)
        if (t == null || t <= 0) return true;
        return t.equals(u.getTelegramId());
    }

}
