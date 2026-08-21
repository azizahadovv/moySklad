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

    public static void setDisplayMap(java.util.Map<String, String> m) { DISPLAY = m; }

    private static String disp(String canonical) { return DISPLAY.getOrDefault(canonical, canonical); }

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
        return replyMenu(
                row(disp("📊 КАССАМ"), disp("💰 БУГУНГИ ТУШУМ")),
                row(disp("💸 Rasxod"), disp("🔁 O'tkazma")),
                row(disp("📤 Hisobot topshirish"), disp("🤝 КОНТРАГЕНТ")));
    }

    /** Panel darajasi uchun menu: 2 tadan qator + «⬅️ Orqaga». */
    public static ReplyKeyboardMarkup levelMenu(List<String> labels) {
        List<KeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < labels.size(); i += 2) {
            KeyboardRow r = new KeyboardRow();
            r.add(new KeyboardButton(labels.get(i)));
            if (i + 1 < labels.size()) r.add(new KeyboardButton(labels.get(i + 1)));
            rows.add(r);
        }
        rows.add(row("⬅️ Orqaga"));
        ReplyKeyboardMarkup m = new ReplyKeyboardMarkup();
        m.setKeyboard(rows);
        m.setResizeKeyboard(true);
        return m;
    }

    public static ReplyKeyboardMarkup buxMenu(boolean superadmin) {
        List<KeyboardRow> rows;
        // Bosh menyu — faqat bitta panel; qolgan hammasi 🏪 KASSA ichida
        rows = new ArrayList<>(List.of(row(disp("🏪 KASSA"), disp("🤝 КОНТРАГЕНТ"))));
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
