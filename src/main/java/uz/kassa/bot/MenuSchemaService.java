package uz.kassa.bot;

import org.springframework.stereotype.Component;
import uz.kassa.service.SettingsService;

import java.util.*;

/**
 * 🧩 Bot menyu SXEMASI — admin paneldan (web) boshqariladi:
 *   • tugmalar tartibi va ustunlar soni (har menyu uchun),
 *   • «erkin» menyular orasida tugmalarni KO'CHIRISH (masalan «📋 Аудит»ni bosh menyuga),
 *   • yashirish — LabelService (label.off.*), huquqlar — PermService.
 *
 * Ikki xil menyu bor:
 *   ERKIN (FREE) — kontekstsiz: Buxgalter/Admin bosh menyusi, 📊 Ҳисоботлар, ⚙️ Настройка va
 *     uning 4 guruhi. Bularning tugmalari AMALLAR (AdminHandler.ACTIONS) yoki OSTMENYU yorliqlari
 *     (SUBMENUS) — istalgan erkin menyuga ko'chirilishi mumkin; bitta tugma faqat bitta joyda.
 *   KONTEKSTLI — kassir menyusi, 📊 КАССАМ, kassa kartasi, Отдел основной: tartib/ustun/yashirish
 *     mumkin, ko'chirish yo'q (handler'lari kontekstga bog'liq).
 *
 * Saqlash: "menu.order.<kalit>" (qatorma-qator kanonik nomlar), "menu.cols.<kalit>" (1..3).
 * Sozlanmagan menyu KOD tartibida qoladi; kodda yangi tugma paydo bo'lsa o'z menyusi oxiriga tushadi.
 */
@Component
public class MenuSchemaService {

    public record MenuDef(String key, String title, List<String> defaults, boolean free) {}

    /** Ostmenyu yorlig'i → ochiladigan menyu kaliti. */
    public static final Map<String, String> SUBMENUS = new LinkedHashMap<>();
    /** Faqat SuperAdmin ko'radigan tugmalar (qaysi menyuda bo'lmasin). */
    public static final Set<String> SA_ONLY = new LinkedHashSet<>();
    /** Ko'chirib bo'lmaydigan tugmalar (sozlamalarga kirish yo'li). */
    public static final Set<String> PINNED = Set.of("⚙️ Настройка", "🏪 Кассалар", "🤝 КОНТРАГЕНТ");

    public static final Map<String, MenuDef> MENUS;

    static {
        SUBMENUS.put("📊 Ҳисоботлар", "stat");
        SUBMENUS.put("⚙️ Настройка", "sozlash");
        SUBMENUS.put("🏢 Ташкилот", "soz.tashkilot");
        SUBMENUS.put("💼 Молия", "soz.moliya");
        SUBMENUS.put("🔗 MoySklad", "soz.moysklad");
        SUBMENUS.put("🎛 Интерфейс", "soz.interfeys");

        Map<String, MenuDef> m = new LinkedHashMap<>();
        reg(m, "main.bux", "🏠 Bosh menyu — Buxgalter/Admin", true,
                "🏪 Кассалар", "📥 Кутилаётганлар", "📊 Ҳисоботлар", "🤝 КОНТРАГЕНТ", "💰 Баланс", "⚙️ Настройка");
        reg(m, "stat", "📊 Ҳисоботлар", true,
                "💰 Бугунги тушум", "🧾 Расходлар",
                "🏪 Кассалар холати", "🧾 Карзлар реестр", "📜 История",
                "👥 Фойдаланувчилар умумий", "🏦 Бухгалтерия", "💼 Салдо", "📲 Кликлар", "📊 Свод");
        reg(m, "sozlash", "⚙️ Настройка", true,
                "🏢 Ташкилот", "💼 Молия", "🔗 MoySklad", "🎛 Интерфейс");
        reg(m, "soz.tashkilot", "🏢 Ташкилот", true,
                "🏪 Касса", "👥 Фойдаланувчилар", "👁 Ҳуқуқлар", "💳 Карта масъуллари", "📣 Гуруҳлар/Каналлар");
        reg(m, "soz.moliya", "💼 Молия", true,
                "💼 Бошланғич қолдиқ", "🛠 Корректировка", "📅 Ledger санаси", "♻️ Нол бошлаш");
        reg(m, "soz.moysklad", "🔗 MoySklad", true,
                "🔑 MoySklad API", "🔄 Номлар (MoySklad)", "📥 Қайта юклаш", "🩺 Диагностика");
        reg(m, "soz.interfeys", "🎛 Интерфейс", true,
                "🏷 Тугма номлари", "🧩 Меню тартиби", "🔔 Билдиришномалар", "📋 Аудит");
        reg(m, "main.kassir", "🏠 Bosh menyu — Kassir", false,
                "📊 КАССАМ", "💰 БУГУНГИ ТУШУМ", "🔁 O'tkazma", "📤 Hisobot topshirish", "🤝 КОНТРАГЕНТ", "💰 Баланс");
        reg(m, "kassam", "📊 КАССАМ (kassir paneli)", false,
                "💰 Бугунги тушум", "💸 Расход", "📆 Давр танлаш", "💼 Салдо", "🧾 Қарзларим", "📊 Excel");
        reg(m, "kassa", "🏪 Kassa kartasi", false,
                "💰 Бугунги тушум", "📆 Давр танлаш", "💵 Топширилмаган пул", "💸 Расход");
        reg(m, "osn", "🏦 Отдел основной", false, "💵 Пул қолдиғи", "🏦 Ҳисобот");
        MENUS = Collections.unmodifiableMap(m);

        // SuperAdmin'gagina ochiq: ⚙️ va uning barcha ichki tugmalari (+ 👥 умумий)
        SA_ONLY.add("⚙️ Настройка"); SA_ONLY.add("👥 Фойдаланувчилар умумий");
        for (String k : List.of("sozlash", "soz.tashkilot", "soz.moliya", "soz.moysklad", "soz.interfeys"))
            SA_ONLY.addAll(MENUS.get(k).defaults());
    }

    private static void reg(Map<String, MenuDef> m, String key, String title, boolean free, String... d) {
        m.put(key, new MenuDef(key, title, List.of(d), free));
    }

    /** Erkin menyularda uchraydigan barcha ma'lum tugmalar. */
    public static Set<String> freeLabels() {
        Set<String> all = new LinkedHashSet<>();
        for (MenuDef d : MENUS.values()) if (d.free()) all.addAll(d.defaults());
        return all;
    }

    private final SettingsService settings;
    private volatile Map<String, List<String>> order = Map.of();     // saqlangan (xom)
    private volatile Map<String, List<String>> placed = Map.of();    // erkin menyular — hisoblangan joylashuv
    private volatile Map<String, Integer> cols = Map.of();

    public MenuSchemaService(SettingsService settings) { this.settings = settings; }

    @jakarta.annotation.PostConstruct
    public void reload() {
        Map<String, List<String>> o = new HashMap<>();
        Map<String, Integer> c = new HashMap<>();
        for (String key : MENUS.keySet()) {
            String v = settings.get("menu.order." + key).orElse("").trim();
            if (!v.isEmpty()) {
                List<String> lst = new ArrayList<>();
                for (String line : v.split("\n")) { String t = line.trim(); if (!t.isEmpty() && !lst.contains(t)) lst.add(t); }
                if (!lst.isEmpty()) o.put(key, lst);
            }
            String cv = settings.get("menu.cols." + key).orElse("").trim();
            if (cv.matches("[1-3]")) c.put(key, Integer.parseInt(cv));
        }
        order = o;
        cols = c;
        placed = computePlacement(o);
        Keyboards.setArranger(this::arrange);
    }

    /**
     * Erkin menyular joylashuvi: saqlangan ro'yxatlar (faqat ma'lum tugmalar, har tugma bir marta —
     * birinchi uchragani qoladi), so'ng hech qayerga qo'yilmagan standart tugmalar o'z menyusiga.
     * PINNED tugmalar doim o'z menyusida.
     */
    private static Map<String, List<String>> computePlacement(Map<String, List<String>> saved) {
        Set<String> known = freeLabels();
        Map<String, List<String>> out = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (MenuDef d : MENUS.values()) {
            if (!d.free()) continue;
            List<String> lst = new ArrayList<>();
            for (String s : saved.getOrDefault(d.key(), List.of())) {
                if (!known.contains(s) || used.contains(s)) continue;
                if (PINNED.contains(s) && !d.defaults().contains(s)) continue;
                lst.add(s); used.add(s);
            }
            out.put(d.key(), lst);
        }
        for (MenuDef d : MENUS.values()) {
            if (!d.free()) continue;
            List<String> lst = out.get(d.key());
            for (String s : d.defaults()) if (!used.contains(s)) { lst.add(s); used.add(s); }
            // PINNED tugma boshqa joyga saqlangan bo'lsa ham shu yerda
        }
        return out;
    }

    /* ---------------- o'qish ---------------- */

    public boolean customized(String key) { return order.containsKey(key) || cols.containsKey(key); }
    public int cols(String key) { return cols.getOrDefault(key, 2); }
    public static boolean isFree(String key) { MenuDef d = MENUS.get(key); return d != null && d.free(); }

    /** Menyu tugmalari joriy tartibda. */
    public List<String> current(String key) {
        MenuDef d = MENUS.get(key);
        if (d == null) return List.of();
        if (d.free()) return placed.getOrDefault(key, d.defaults());
        return merge(order.get(key), d.defaults());
    }

    /** Erkin tugma qaysi menyuda turibdi (yo'q — null). */
    public String menuOf(String label) {
        for (var e : placed.entrySet()) if (e.getValue().contains(label)) return e.getKey();
        return null;
    }

    /** Ostmenyu kalitining ota menyusi (yorlig'i turgan menyu). */
    public String parentOf(String menuKey) {
        for (var e : SUBMENUS.entrySet()) if (e.getValue().equals(menuKey)) return menuOf(e.getKey());
        return null;
    }

    private static List<String> merge(List<String> saved, List<String> labels) {
        List<String> out = new ArrayList<>();
        if (saved != null) for (String s : saved) if (labels.contains(s) && !out.contains(s)) out.add(s);
        for (String l : labels) if (!out.contains(l)) out.add(l);
        return out;
    }

    /** Keyboards uchun: key=null — mazmunidan aniqlash; erkin menyu — joylashuv bo'yicha. */
    public Keyboards.Arranged arrange(String key, List<String> labels) {
        if (key == null) key = keyFor(labels);
        if (key == null) return new Keyboards.Arranged(labels, 2, false);
        MenuDef d = MENUS.get(key);
        if (d.free()) {
            List<String> cur = current(key);
            boolean custom = customized(key) || !cur.equals(d.defaults());
            return new Keyboards.Arranged(cur, cols(key), custom);
        }
        if (!customized(key)) return new Keyboards.Arranged(labels, 2, false);
        return new Keyboards.Arranged(merge(order.get(key), labels), cols(key), true);
    }

    static String keyFor(List<String> labels) {
        if (labels == null || labels.size() < 2) return null;
        MenuDef best = null;
        for (MenuDef d : MENUS.values()) {
            if (!d.defaults().containsAll(labels)) continue;
            if (best == null || d.defaults().size() < best.defaults().size()) best = d;
        }
        return best == null ? null : best.key();
    }

    /* ---------------- yozish ---------------- */

    public void move(String key, int idx, int delta) {
        List<String> cur = new ArrayList<>(current(key));
        int j = idx + delta;
        if (idx < 0 || idx >= cur.size() || j < 0 || j >= cur.size()) return;
        Collections.swap(cur, idx, j);
        settings.set("menu.order." + key, String.join("\n", cur));
        reload();
    }

    public void setCols(String key, int n) {
        if (!MENUS.containsKey(key) || n < 1 || n > 3) return;
        settings.set("menu.cols." + key, n == 2 ? "" : String.valueOf(n));
        reload();
    }

    public void reset(String key) {
        settings.set("menu.order." + key, "");
        settings.set("menu.cols." + key, "");
        reload();
    }

    /** To'liq sxemani saqlash (web muharriri): kalit → tugmalar. Erkin menyular ko'chirish bilan. */
    public void saveAll(Map<String, List<String>> menus) {
        for (var e : menus.entrySet()) {
            MenuDef d = MENUS.get(e.getKey());
            if (d == null) continue;
            List<String> clean = new ArrayList<>();
            Set<String> allowed = d.free() ? freeLabels() : new HashSet<>(d.defaults());
            for (String s : e.getValue()) if (allowed.contains(s) && !clean.contains(s)) clean.add(s);
            boolean sameAsDefault = clean.equals(d.defaults());
            settings.set("menu.order." + e.getKey(), sameAsDefault && d.free() ? "" : String.join("\n", clean));
        }
        reload();
    }
}
