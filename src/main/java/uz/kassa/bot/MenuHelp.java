package uz.kassa.bot;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static uz.kassa.bot.TextUtil.esc;

/**
 * ℹ️ Menyu ochilganda «Bo'limni tanlang:» ustida har tugmaning bir qatorli izohi (2026-11-01, user: «menyular
 * ko'pligidan nima ish qilishini yodimdan chiqarib qo'yyapman»). Kalit — menyu konteksti, chunki bir xil kanonik
 * nom turli menyuda turli ish qiladi (mas. «💰 Бугунги тушум»: 📊 da barcha kassalar, kassa kartasida bitta kassa).
 * Izohsiz tugma ro'yxatda chiqmaydi. To'liq xarita — docs emas, artifact sahifa (Bot menyu xaritasi).
 */
public final class MenuHelp {

    private MenuHelp() { }

    private static final Map<String, Map<String, String>> HELP = Map.of(
            "stat", Map.ofEntries(
                    Map.entry("💰 Бугунги тушум", "barcha kassalar bugungi kirimi"),
                    Map.entry("🧾 Расходлар", "tanlangan kun rasxodlari"),
                    Map.entry("🏪 Кассалар холати", "har kassa balansi va topshirilmagan kunlar"),
                    Map.entry("🧾 Карзлар реестр", "kassalar orasidagi ichki qarzlar"),
                    Map.entry("📜 История", "davr bo'yicha kirim/chiqim va amallar"),
                    Map.entry("👥 Фойдаланувчилар умумий", "faol foydalanuvchilar ro'yxati"),
                    Map.entry("🏦 Бухгалтерия", "shu oy buxgalteriya hisoboti"),
                    Map.entry("💼 Салдо", "faqat Отдел основной balansi"),
                    Map.entry("📲 Кликлар", "Click hisoblari balansi (bot)"),
                    Map.entry("📊 Свод", "Excel: oy, davr yoki bitta kassa"),
                    Map.entry("🏦 Топширилган пуллар", "kassalardan qabul qilingan pul")),
            "sozlash", Map.of(
                    "🏢 Ташкилот", "kassa, xodimlar, huquqlar, guruhlar",
                    "💼 Молия", "boshlang'ich qoldiq, korrektirovka",
                    "🔗 MoySklad", "API, nazorat, ombor, jarima, Adesk",
                    "🎛 Интерфейс", "tugma nomlari, menyu, xabarlar, audit"),
            "soz.tashkilot", Map.of(
                    "🏪 Касса", "kassa qo'shish, otdelga bog'lash, o'chirish",
                    "👥 Фойдаланувчилар", "xodim qo'shish, rol, birlashtirish",
                    "👁 Ҳуқуқлар", "kim qaysi bo'limni ko'radi",
                    "💳 Карта масъуллари", "karta mas'uli, hisobotdan chiqarish",
                    "📣 Гуруҳлар/Каналлар", "Click hisoboti guruhlari va vaqti"),
            "soz.moliya", Map.of(
                    "💼 Бошланғич қолдиқ", "Отдел основной boshlang'ich qoldig'i",
                    "🛠 Корректировка", "balansni qo'lda tuzatish",
                    "📅 Ledger санаси", "bot hisobi boshlanish sanasi",
                    "♻️ Нол бошлаш", "eski kunlarni yopib nol qilish (ehtiyot!)"),
            "soz.moysklad", Map.of(
                    "🔑 MoySklad API", "kalit holati va almashtirish",
                    "🔄 Номлар (MoySklad)", "kassa/hisob nomlarini yangilash",
                    "📥 Қайта юклаш", "hammasini o'chirib qayta yuklash (ehtiyot!)",
                    "🩺 Диагностика", "manfiy balanslar va tuzatish",
                    "🕵️ Назорат", "kontragent va otgruzka nazorati",
                    "🏬 Омбор назорати", "ombor moduli sozlamalari",
                    "📨 Бот хабарлари", "akkauntlar, karta qoldiqlari",
                    "⚖️ Жарималар", "jarima foizlari va xulosa",
                    "📒 Adesk", "Adesk ↔ MoySklad sinxron"),
            "soz.interfeys", Map.of(
                    "🏷 Тугма номлари", "tugmani qayta nomlash/yashirish",
                    "🧩 Меню тартиби", "tugmalar tartibi va ustunlar",
                    "🔔 Билдиришномалар", "o'zingiz yozadigan shablon xabarlar",
                    "🔕 Хабарномалар", "botning avto xabarlarini yoqish/o'chirish",
                    "📋 Аудит", "kim nima qilgani"),
            "kassa", Map.of(
                    "💰 Бугунги тушум", "shu kassaning bugungi kirimi",
                    "📆 Давр танлаш", "davr statistikasi, kunma-kun",
                    "💵 Топширилмаган пул", "kassadagi pul, pul qabul qilish",
                    "💸 Расход", "oxirgi 7 kun rasxodlari"),
            "osn", Map.of(
                    "💵 Пул қолдиғи", "buxgalteriya + kassalardagi pul",
                    "🏦 Ҳисобот", "📊 → 🏦 Бухгалтерия bilan bir xil"),
            "kassam", Map.of(
                    "💰 Бугунги тушум", "bosh menyudagi bilan bir xil",
                    "💸 Расход", "faqat bugungi rasxodlar",
                    "📆 Давр танлаш", "davr bo'yicha amallar tarixi",
                    "💼 Салдо", "kassa balansi: band va bo'sh",
                    "🧾 Қарзларим", "kassalar orasidagi ichki qarz",
                    "🏦 Топширганларим", "hisobotlaringiz holati",
                    "📊 Excel", "davr bo'yicha Excel"));

    /** «• <ko'rinadigan nom> — <izoh>» qatorlari (HTML, oxirida bo'sh qator); izoh yo'q bo'lsa "". */
    public static String block(String ctx, List<String> canonicals, Function<String, String> display) {
        Map<String, String> m = HELP.get(ctx);
        if (m == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String c : canonicals) {
            String h = m.get(c);
            if (h != null) sb.append("• ").append(esc(display.apply(c))).append(" — <i>").append(esc(h)).append("</i>\n");
        }
        return sb.length() == 0 ? "" : sb.append("\n").toString();
    }
}
