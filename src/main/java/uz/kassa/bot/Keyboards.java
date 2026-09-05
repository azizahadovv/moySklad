package uz.kassa.bot;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Keyboards {
    private Keyboards() {}

    /** Kanonik nom -> foydalanuvchi bergan nom (LabelService yangilaydi). */
    private static volatile java.util.Map<String, String> DISPLAY = java.util.Map.of();

    /** SuperAdmin o'chirgan (yashirgan) bo'limlar — menyularda chiqmaydi. */
    private static volatile java.util.Set<String> HIDDEN = java.util.Set.of();

    public static void setDisplayMap(java.util.Map<String, String> m) { DISPLAY = m; }

    public static void setHiddenSet(java.util.Set<String> h) { HIDDEN = h; }

    /* ---------- 🧩 Menyu sxemasi (MenuSchemaService): tartib + ustunlar ---------- */

    /** Tartiblangan ro'yxat; custom=false — sozlanmagan, kod ko'rinishi qoladi. */
    public record Arranged(List<String> items, int cols, boolean custom) {}

    private static volatile java.util.function.BiFunction<String, List<String>, Arranged> ARRANGER =
            (k, l) -> new Arranged(l, 2, false);

    public static void setArranger(java.util.function.BiFunction<String, List<String>, Arranged> f) {
        ARRANGER = f;
    }

    /** key=null — menyu ro'yxat mazmunidan aniqlanadi. */
    public static Arranged arrange(String key, List<String> labels) {
        return ARRANGER.apply(key, labels);
    }

    /** Tayyor (display) nomlarni N ustunli qatorlarga yig'adi. */
    public static void addGrid(List<KeyboardRow> rows, List<String> shown, int cols) {
        int c = Math.max(1, Math.min(3, cols));
        for (int i = 0; i < shown.size(); i += c) {
            KeyboardRow r = new KeyboardRow();
            for (int j = i; j < Math.min(i + c, shown.size()); j++) r.add(new KeyboardButton(shown.get(j)));
            rows.add(r);
        }
    }

    /** Sozlangan tartib bo'lsa — filtr+display bilan grid; bo'lmasa false (kod tartibi). */
    private static boolean addCustom(List<KeyboardRow> rows, String key, List<String> canonicals,
                                     java.util.function.Predicate<String> visible) {
        Arranged a = ARRANGER.apply(key, canonicals);
        if (!a.custom()) return false;
        addGrid(rows, a.items().stream().filter(visible).map(Keyboards::disp).toList(), a.cols());
        return true;
    }

    private static final List<String> KASSIR_MAIN = List.of(
            "📊 КАССАМ", "💰 БУГУНГИ ТУШУМ", "🔁 O'tkazma", "📤 Hisobot topshirish",
            "🤝 КОНТРАГЕНТ", "💰 Баланс");
    /** Buxgalter/SuperAdmin bosh menyusi — 3 daraja qoidasi: bosh menyu → bo'lim → amal. */
    private static final List<String> BUX_MAIN = List.of(
            "🏪 Кассалар", "📥 Кутилаётганлар", "📊 Ҳисоботлар", "🤝 КОНТРАГЕНТ", "💰 Баланс", "⚙️ Настройка");
    public static final String SETTINGS_LABEL = "⚙️ Настройка";
    public static final String WEBAPP_LABEL = "🌐 Админ панел";

    private static String disp(String canonical) { return DISPLAY.getOrDefault(canonical, canonical); }

    /** Yashirilganlarni tashlab, qolganini display nomi bilan qatorga yig'adi. */
    private static void addVisibleRow(List<KeyboardRow> rows, String... canonicals) {
        addRowIf(rows, c -> !HIDDEN.contains(c), canonicals);
    }

    private static void addRowIf(List<KeyboardRow> rows,
                                 java.util.function.Predicate<String> visible, String... canonicals) {
        KeyboardRow r = new KeyboardRow();
        for (String c : canonicals)
            if (visible.test(c)) r.add(new KeyboardButton(disp(c)));
        if (!r.isEmpty()) rows.add(r);
    }

    /** Bosh menyu — foydalanuvchiga xos huquq filtri bilan (PermService prediati). */
    public static ReplyKeyboardMarkup kassirMenu(java.util.function.Predicate<String> visible) {
        return kassirMenu(visible, List.of());
    }

    /** Bosh menyu + 🔘 shablon tugmalari (admin sozlagan; mavjud tugmalardan KEYIN, 2 tadan qator). */
    public static ReplyKeyboardMarkup kassirMenu(java.util.function.Predicate<String> visible,
                                                 List<String> extra) {
        List<KeyboardRow> rows = new ArrayList<>();
        if (!addCustom(rows, "main.kassir", KASSIR_MAIN, visible)) {
            addRowIf(rows, visible, "📊 КАССАМ", "💰 БУГУНГИ ТУШУМ");
            addRowIf(rows, visible, "🔁 O'tkazma", "📤 Hisobot topshirish");
            addRowIf(rows, visible, "🤝 КОНТРАГЕНТ");
            addRowIf(rows, visible, "💰 Баланс");
        }
        addExtraRows(rows, extra);
        ReplyKeyboardMarkup m = new ReplyKeyboardMarkup();
        m.setKeyboard(rows);
        m.setResizeKeyboard(true);
        return m;
    }

    public static ReplyKeyboardMarkup buxMenu(java.util.function.Predicate<String> visible) {
        return buxMenu(visible, List.of(), true, null);
    }

    public static ReplyKeyboardMarkup buxMenu(java.util.function.Predicate<String> visible,
                                              List<String> extra) {
        return buxMenu(visible, extra, true, null);
    }

    /**
     * Buxgalter/SuperAdmin bosh menyusi.
     * superadmin=false — «⚙️ Настройка» ko'rinmaydi; webappUrl berilsa oxirida
     * «🌐 Админ панел» Mini App tugmasi (faqat shaxsiy chatda ishlaydi).
     */
    public static ReplyKeyboardMarkup buxMenu(java.util.function.Predicate<String> visible,
                                              List<String> extra, boolean superadmin, String webappUrl) {
        java.util.function.Predicate<String> vis =
                c -> visible.test(c) && (superadmin || !MenuSchemaService.SA_ONLY.contains(c));
        List<KeyboardRow> rows = new ArrayList<>();
        if (!addCustom(rows, "main.bux", BUX_MAIN, vis)) {
            addRowIf(rows, vis, "🏪 Кассалар", "📥 Кутилаётганлар");
            addRowIf(rows, vis, "📊 Ҳисоботлар", "🤝 КОНТРАГЕНТ");
            addRowIf(rows, vis, "💰 Баланс", SETTINGS_LABEL);
        }
        addExtraRows(rows, extra);
        // webappUrl: reply-klaviatura WebApp tugmasi ISHLATILMAYDI — Telegram u orqali initData
        // bermaydi; Mini App chat menyu tugmasi (≡) va /start inline tugmasi orqali ochiladi.
        ReplyKeyboardMarkup m = new ReplyKeyboardMarkup();
        m.setKeyboard(rows);
        m.setResizeKeyboard(true);
        return m;
    }

    /** 🔘 Shablon tugmalari: matn o'zgarishsiz (display/hidden filtri yo'q), 2 tadan qator. */
    private static void addExtraRows(List<KeyboardRow> rows, List<String> extra) {
        if (extra == null) return;
        for (int i = 0; i < extra.size(); i += 2) {
            KeyboardRow r = new KeyboardRow();
            r.add(new KeyboardButton(extra.get(i)));
            if (i + 1 < extra.size()) r.add(new KeyboardButton(extra.get(i + 1)));
            rows.add(r);
        }
    }

    /** Barcha asosiy menyu tugmalari — bosilganda tugallanmagan dialog bekor qilinadi. */
    private static final java.util.Set<String> MENU_LABELS = java.util.Set.of(
            "📊 Bugungi holat", "💰 Balansim",
            "📤 Hisobot topshirish", "🧾 Qarzlarim", "📜 Tarix",
            "🏪 Kassalar holati", "🧾 Qarzlar registri", "📊 Excel hisobot",
            "👥 Foydalanuvchi qo'shish", "🏪 Kassa qo'shish",
            "💼 Boshlang'ich qoldiq", "👤 Foydalanuvchilar",
            "👑 АДМИН ПАНЕЛ", "💰 БУГУНГИ ТУШУМ", "📊 ПАНЕЛ", "📊 КАССАМ", "🏪 KASSA",
            "🤝 КОНТРАГЕНТ", "💰 Баланс",
            "🏪 Кассалар", "📥 Кутилаётганлар", "📊 Ҳисоботлар", "⚙️ Настройка", "🌐 Админ панел");

    public static boolean isMenuLabel(String text) { return MENU_LABELS.contains(text); }

    /* ---------------- Reply (asosiy menyu) ---------------- */

    public static ReplyKeyboardMarkup kassirMenu() {
        List<KeyboardRow> rows = new ArrayList<>();
        addVisibleRow(rows, "📊 КАССАМ", "💰 БУГУНГИ ТУШУМ");
        addVisibleRow(rows, "🔁 O'tkazma", "📤 Hisobot topshirish");
        addVisibleRow(rows, "🤝 КОНТРАГЕНТ");
        addVisibleRow(rows, "💰 Баланс");
        ReplyKeyboardMarkup m = new ReplyKeyboardMarkup();
        m.setKeyboard(rows);
        m.setResizeKeyboard(true);
        return m;
    }

    /** Panel darajasi uchun menu: 2 tadan qator + «⬅️ Orqaga». */
    public static ReplyKeyboardMarkup levelMenu(List<String> labels) {
        return levelMenu(labels, c -> true);
    }

    /** Panel darajasi uchun menu — PermService prediati bilan filtrlanadi. */
    public static ReplyKeyboardMarkup levelMenu(List<String> labels,
                                                 java.util.function.Predicate<String> visible) {
        Arranged a = ARRANGER.apply(null, labels);   // 🧩 sozlanmagan bo'lsa — o'zgarishsiz, 2 ustun
        List<String> shown = a.items().stream()
                .filter(l -> !RENAMABLE_CHECK.test(l) || visible.test(l))
                .map(Keyboards::disp).toList();
        List<KeyboardRow> rows = new ArrayList<>();
        addGrid(rows, shown, a.cols());
        rows.add(row("⬅️ Orqaga"));
        ReplyKeyboardMarkup m = new ReplyKeyboardMarkup();
        m.setKeyboard(rows);
        m.setResizeKeyboard(true);
        return m;
    }

    private static final java.util.function.Predicate<String> RENAMABLE_CHECK =
            uz.kassa.bot.LabelService.RENAMABLE::contains;

    public static ReplyKeyboardMarkup buxMenu(boolean superadmin) {
        return buxMenu(c -> !HIDDEN.contains(c), List.of(), superadmin, null);
    }

    private static ReplyKeyboardMarkup replyMenu(KeyboardRow... rows) {
        ReplyKeyboardMarkup m = new ReplyKeyboardMarkup();
        m.setKeyboard(Arrays.asList(rows));
        m.setResizeKeyboard(true);
        return m;
    }

    private static KeyboardRow row(String... texts) {
        KeyboardRow r = new KeyboardRow();
        for (String t : texts) r.add(new KeyboardButton(t));
        return r;
    }

    /* ---------------- Inline ---------------- */

    public static InlineKeyboardButton btn(String text, String data) {
        return InlineKeyboardButton.builder().text(text).callbackData(data).build();
    }

    public static List<InlineKeyboardButton> irow(InlineKeyboardButton... btns) {
        return new ArrayList<>(Arrays.asList(btns));
    }

    public static InlineKeyboardMarkup inline(List<List<InlineKeyboardButton>> rows) {
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /** Naqd/Click tanlash (Terminal chiqimda qatnashmaydi). */
    public static InlineKeyboardMarkup mtChoice(String prefix) {
        return inline(List.of(
                irow(btn("💵 Naqd", prefix + ":NAQD"), btn("📲 Click", prefix + ":KLIK")),
                irow(btn("❌ Bekor", "cx"))));
    }

    public static InlineKeyboardMarkup cancelOnly() {
        return inline(List.of(irow(btn("❌ Bekor", "cx"))));
    }
}
