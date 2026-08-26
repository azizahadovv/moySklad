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
        List<KeyboardRow> rows = new ArrayList<>();
        addRowIf(rows, visible, "📊 КАССАМ", "💰 БУГУНГИ ТУШУМ");
        addRowIf(rows, visible, "💸 Rasxod", "🔁 O'tkazma");
        addRowIf(rows, visible, "📤 Hisobot topshirish", "🤝 КОНТРАГЕНТ");
        ReplyKeyboardMarkup m = new ReplyKeyboardMarkup();
        m.setKeyboard(rows);
        m.setResizeKeyboard(true);
        return m;
    }

    public static ReplyKeyboardMarkup buxMenu(java.util.function.Predicate<String> visible) {
        List<KeyboardRow> rows = new ArrayList<>();
        addRowIf(rows, visible, "🏪 KASSA", "🤝 КОНТРАГЕНТ");
        ReplyKeyboardMarkup m = new ReplyKeyboardMarkup();
        m.setKeyboard(rows);
        m.setResizeKeyboard(true);
        return m;
    }

    /** Barcha asosiy menyu tugmalari — bosilganda tugallanmagan dialog bekor qilinadi. */
    private static final java.util.Set<String> MENU_LABELS = java.util.Set.of(
            "📊 Bugungi holat", "💰 Balansim", "💸 Rasxod",
            "📤 Hisobot topshirish", "🧾 Qarzlarim", "📜 Tarix",
            "🏪 Kassalar holati", "🧾 Qarzlar registri", "📊 Excel hisobot",
            "👥 Foydalanuvchi qo'shish", "🏪 Kassa qo'shish",
            "💼 Boshlang'ich qoldiq", "👤 Foydalanuvchilar",
            "👑 АДМИН ПАНЕЛ", "💰 БУГУНГИ ТУШУМ", "📊 ПАНЕЛ", "📊 КАССАМ", "🏪 KASSA",
            "🤝 КОНТРАГЕНТ");

    public static boolean isMenuLabel(String text) { return MENU_LABELS.contains(text); }

    /* ---------------- Reply (asosiy menyu) ---------------- */

    public static ReplyKeyboardMarkup kassirMenu() {
        List<KeyboardRow> rows = new ArrayList<>();
        addVisibleRow(rows, "📊 КАССАМ", "💰 БУГУНГИ ТУШУМ");
        addVisibleRow(rows, "💸 Rasxod", "🔁 O'tkazma");
        addVisibleRow(rows, "📤 Hisobot topshirish", "🤝 КОНТРАГЕНТ");
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
        List<String> shown = labels.stream()
                .filter(l -> !RENAMABLE_CHECK.test(l) || visible.test(l))
                .map(Keyboards::disp).toList();
        List<KeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < shown.size(); i += 2) {
            KeyboardRow r = new KeyboardRow();
            r.add(new KeyboardButton(shown.get(i)));
            if (i + 1 < shown.size()) r.add(new KeyboardButton(shown.get(i + 1)));
            rows.add(r);
        }
        rows.add(row("⬅️ Orqaga"));
        ReplyKeyboardMarkup m = new ReplyKeyboardMarkup();
        m.setKeyboard(rows);
        m.setResizeKeyboard(true);
        return m;
    }

    private static final java.util.function.Predicate<String> RENAMABLE_CHECK =
            uz.kassa.bot.LabelService.RENAMABLE::contains;

    public static ReplyKeyboardMarkup buxMenu(boolean superadmin) {
        // Bosh menyu — faqat bitta panel; qolgan hammasi 🏪 KASSA ichida
        List<KeyboardRow> rows = new ArrayList<>();
        addVisibleRow(rows, "🏪 KASSA", "🤝 КОНТРАГЕНТ");
        ReplyKeyboardMarkup m = new ReplyKeyboardMarkup();
        m.setKeyboard(rows);
        m.setResizeKeyboard(true);
        return m;
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
