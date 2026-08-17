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

    /** Barcha asosiy menyu tugmalari — bosilganda tugallanmagan dialog bekor qilinadi. */
    private static final java.util.Set<String> MENU_LABELS = java.util.Set.of(
            "📊 Bugungi holat", "💰 Balansim", "💸 Rasxod", "🔁 O'tkazma",
            "📤 Hisobot topshirish", "🧾 Qarzlarim", "📜 Tarix",
            "🏪 Kassalar holati", "📥 Kutilayotganlar", "💸 Rasxod (o'zim)",
            "🧾 Qarzlar registri", "📊 Excel hisobot",
            "👥 Foydalanuvchi qo'shish", "🏪 Kassa qo'shish",
            "💼 Boshlang'ich qoldiq", "👤 Foydalanuvchilar",
            "👑 АДМИН ПАНЕЛ", "💰 БУГУНГИ ТУШУМ");

    public static boolean isMenuLabel(String text) { return MENU_LABELS.contains(text); }

    /* ---------------- Reply (asosiy menyu) ---------------- */

    public static ReplyKeyboardMarkup kassirMenu() {
        return replyMenu(
                row("📊 Bugungi holat", "💰 Balansim"),
                row("💸 Rasxod", "🔁 O'tkazma"),
                row("📤 Hisobot topshirish", "🧾 Qarzlarim"),
                row("📜 Tarix"));
    }

    public static ReplyKeyboardMarkup buxMenu(boolean superadmin) {
        List<KeyboardRow> rows;
        if (superadmin) {
            // Sxema bo'yicha: АДМИН ПАНЕЛ (Отдел/Настройка/Статистика) + БУГУНГИ ТУШУМ
            rows = new ArrayList<>(List.of(
                    row("👑 АДМИН ПАНЕЛ"),
                    row("💰 БУГУНГИ ТУШУМ"),
                    row("📥 Kutilayotganlar", "🔁 O'tkazma"),
                    row("💸 Rasxod (o'zim)")));
        } else {
            rows = new ArrayList<>(List.of(
                    row("🏪 Kassalar holati", "📥 Kutilayotganlar"),
                    row("💸 Rasxod (o'zim)", "🔁 O'tkazma"),
                    row("🧾 Qarzlar registri", "📜 Tarix"),
                    row("📊 Excel hisobot")));
        }
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
