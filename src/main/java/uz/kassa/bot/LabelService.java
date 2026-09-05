package uz.kassa.bot;

import org.springframework.stereotype.Component;
import uz.kassa.service.SettingsService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tugma (sahifa) nomlarini SuperAdmin o'zgartira olishi uchun xizmat.
 * KOD ichida hamma joyda ASL (kanonik) nom ishlatiladi; foydalanuvchiga
 * ko'rsatishda display(), kelgan matnni tanishda canonical() qo'llanadi —
 * shu tufayli nom o'zgarsa ham navigatsiya buzilmaydi.
 * Saqlash: settings jadvalida "label.<kanonik>" kaliti.
 */
@Component
public class LabelService {

    /** Nomi o'zgartirilishi mumkin bo'lgan tugmalar (kanonik ro'yxat). */
    public static final List<String> RENAMABLE = List.of(
            // Bosh menyu (Buxgalter/SuperAdmin: 🏪 Кассалар · 📥 · 📊 Ҳисоботлар · 🤝 · 💰 · ⚙️)
            "🏪 Кассалар", "📊 Ҳисоботлар", "📊 КАССАМ", "💰 БУГУНГИ ТУШУМ",
            "🔁 O'tkazma", "📤 Hisobot topshirish",
            "🤝 КОНТРАГЕНТ", "💰 Баланс",
            // Panel darajasi
            "🏬 Отдел", "⚙️ Настройка", "📈 Статистика", "💰 Бугунги тушум",
            "🧾 Расходлар", "📥 Кутилаётганлар", "📆 Давр танлаш",
            // ⚙️ Настройка guruhlari
            "🏢 Ташкилот", "💼 Молия", "🔗 MoySklad", "🎛 Интерфейс",
            // Статистика bo'limi
            "🏪 Кассалар холати", "🧾 Карзлар реестр", "📜 История",
            "👥 Фойдаланувчилар умумий", "🏦 Бухгалтерия",
            "💼 Салдо", "📲 Кликлар", "📊 Свод",
            // Kassir: 📊 КАССАМ paneli ichidagi bo'limlar
            "💸 Расход", "🧾 Қарзларим", "📊 Excel",
            // Kassa kartasi (buxgalter/superadmin — 🏬 Отдел ichida)
            "💵 Топширилмаган пул",
            // Отдел основной kartasi (buxgalteriya)
            "💵 Пул қолдиғи", "🏦 Ҳисобот");

    /** O'chirib bo'lmaydigan bo'lim — sozlamalarga kirish yo'li yopilib qolmasin. */
    public static final String PROTECTED_LABEL = "⚙️ Настройка";

    private final SettingsService settings;
    private volatile Map<String, String> toDisplay = Map.of();
    private volatile Map<String, String> toCanonical = Map.of();
    private volatile java.util.Set<String> hidden = java.util.Set.of();

    public LabelService(SettingsService settings) {
        this.settings = settings;
    }

    @jakarta.annotation.PostConstruct
    public void reload() {
        Map<String, String> d = new HashMap<>();
        Map<String, String> c = new HashMap<>();
        java.util.Set<String> h = new java.util.HashSet<>();
        for (String canonical : RENAMABLE) {
            String v = settings.get("label." + canonical).orElse("").trim();
            if (!v.isEmpty() && !v.equals(canonical)) {
                d.put(canonical, v);
                c.put(v, canonical);
            }
            if (!canonical.equals(PROTECTED_LABEL)
                    && settings.get("label.off." + canonical).orElse("").equals("1"))
                h.add(canonical);
        }
        toDisplay = d;
        toCanonical = c;
        hidden = h;
        Keyboards.setDisplayMap(d);   // statik menyu quruvchilar ham yangi nomni ko'rsatsin
        Keyboards.setHiddenSet(h);
    }

    /** Bo'lim SuperAdmin tomonidan o'chirilganmi (menyularda ko'rinmaydi). */
    public boolean isHidden(String canonical) { return hidden.contains(canonical); }

    /** Bo'limni o'chirish/yoqish. PROTECTED_LABEL hech qachon o'chirilmaydi. */
    public void setHidden(String canonical, boolean off) {
        if (canonical.equals(PROTECTED_LABEL)) return;
        settings.set("label.off." + canonical, off ? "1" : "");
        reload();
    }

    /** Foydalanuvchiga ko'rsatiladigan nom (o'zgartirilgan bo'lsa — yangisi). */
    public String display(String canonical) {
        return toDisplay.getOrDefault(canonical, canonical);
    }

    public List<String> displayAll(List<String> canonicals) {
        return canonicals.stream().map(this::display).toList();
    }

    /** Kelgan tugma matnini kanonik nomga qaytarish (mos kelmasa — o'zi). */
    public String canonical(String text) {
        return toCanonical.getOrDefault(text, text);
    }

    /** Yangi nom saqlash; bo'sh/null — asl nomga qaytarish. */
    public void rename(String canonical, String newName) {
        settings.set("label." + canonical, newName == null ? "" : newName.trim());
        reload();
    }

    public boolean isRenamed(String canonical) {
        return toDisplay.containsKey(canonical);
    }

    /** Yangi nom boshqa tugma bilan to'qnashmasligini tekshirish. */
    public boolean clashes(String canonical, String newName) {
        String n = newName.trim();
        for (String c : RENAMABLE) {
            if (c.equals(canonical)) continue;
            if (c.equals(n) || display(c).equals(n)) return true;
        }
        return false;
    }
}
