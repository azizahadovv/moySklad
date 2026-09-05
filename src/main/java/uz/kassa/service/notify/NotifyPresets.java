package uz.kassa.service.notify;

import uz.kassa.domain.Notify;

import java.util.List;

/**
 * 📚 Tayyor shablonlar (namunalar) — mavjud hisobotlarning shablon ko'rinishidagi
 * NUSXALARI. Asl hisobotlar (Click soatlik, kunlik solishtirish, kassir eslatmasi)
 * KOD'da avvalgidek ishlayveradi; namuna yangi Notify sifatida (o'chirilgan holda)
 * yaratiladi, admin uni tahrirlab yoqadi. Sxema orqali boshqarishning 2-bosqichi.
 */
public final class NotifyPresets {
    private NotifyPresets() {}

    public record Preset(String key, String title, String about, String name,
                         String schedule, String weekdays, String recipients,
                         int autoDeleteMin, String template) {
        public Notify toNotify() {
            return Notify.builder().name(name).template(template).schedule(schedule)
                    .weekdays(weekdays).recipients(recipients).autoDeleteMin(autoDeleteMin)
                    .active(false).build();
        }
    }

    public static final List<Preset> ALL = List.of(
        new Preset("click", "📲 Click qoldiqlari (soatlik)",
            "Mavjud soatlik Click hisobotining shablon nusxasi: har karta bo'yicha MoySklad va karta "
            + "qoldig'i, farq, xulosa. Kimga: Click guruhlari.",
            "📲 Click qoldiqlari (namuna)", "every:1;from:9;to:21;off:0", "", "click_chats", 0,
            """
            📲 <b>CLICK ҚОЛДИҚЛАРИ</b>
            📅 {sana}  🕐 {vaqt}
            ━━━━━━━━━━━━━━━━━━━━
            {#kartalar}
            💳 <b>{nom}</b> ({kassa}) — {masul}
            📦 Мой склад қолдиғи: <b>{ms}</b>
            💳 Карта қолдиғи {qoldiq_vaqt}: <b>{qoldiq}</b> <i>({qoldiq_kim})</i>
            {holat}
            {/kartalar}
            ━━━━━━━━━━━━━━━━━━━━
            📊 <b>ХУЛОСА:</b>  ⚠️ фарқ — {jami.karta_farq_soni}   ❗️ киритилмаган — {jami.karta_kiritilmagan}
            {?jami.karta_kiritilmagan}📣 <b>Карта қолдиқларини юборинг!</b> {xodimlar}{/?}"""),

        new Preset("daily", "📋 Kunlik kassa solishtirish",
            "Kunlik hisobot matnining nusxasi: har otdel bo'yicha MoySklad savdosi, bot savdosi va farq "
            + "(rasm/Excel emas — matn). Kimga: Click guruhlari, SuperAdmin, Buxgalter.",
            "📋 Kunlik kassa solishtirish (namuna)", "times:22:00", "",
            "click_chats,rol:SUPERADMIN,rol:BUXGALTER", 0,
            """
            📋 <b>Kunlik kassa solishtirish</b> — {sana}
            {#kassalar:naqdli}
            {savdo_holat} <b>{nuqta}</b> ({kassirlar}): MoySklad <b>{savdo_ms}</b> · bot <b>{savdo_bot}</b> · farq <b>{savdo_farq|+}</b>
            {/kassalar}
            <i>Farq = MoySklad savdosi − bot savdosi (0 — ikkala tizim bir xil)</i>"""),

        new Preset("kassir", "🔔 Kunlik eslatma (kassirga)",
            "Kechki kassir eslatmasining nusxasi: bugungi kirim, topshirilmagan kunlar, qo'ldagi qoldiq — "
            + "har kassir O'Z otdeli bo'yicha oladi. Kimga: barcha kassirlar.",
            "🔔 Kunlik eslatma (namuna)", "times:21:00", "", "rol:KASSIR", 0,
            """
            🔔 <b>Kunlik eslatma</b>

            Bugungi kirim: Naqd {kassa:mening.bot_prixod_naqd} · Click {kassa:mening.bot_prixod_klik} · Terminal {kassa:mening.bot_prixod_terminal} so'm
            {?kassa:mening.topshirilmagan}Topshirilmagan kunlar: <b>{kassa:mening.topshirilmagan}</b> ta
            {/?}Qo'lingizdagi qoldiq: Naqd {kassa:mening.naqd_mavjud} · Click {kassa:mening.klik_mavjud} so'm"""),

        new Preset("hafta", "📊 Haftalik savdo (dushanba)",
            "O'tgan hafta savdosi otdellar kesimida (MoySklad): prixod, vozvrat, sof. Har dushanba 09:00. "
            + "Kimga: SuperAdmin, Buxgalter.",
            "📊 Haftalik savdo (namuna)", "times:09:00", "1", "rol:SUPERADMIN,rol:BUXGALTER", 0,
            """
            📊 <b>Haftalik savdo</b> — o'tgan hafta
            {#kassalar}
            🏪 {nom}: prixod <b>{prixod:otgan_hafta}</b> · vozvrat {vozvrat:otgan_hafta} · sof <b>{sof:otgan_hafta}</b>
            {/kassalar}
            Jami prixod: <b>{jami.prixod:otgan_hafta}</b> so'm · sof: <b>{jami.sof:otgan_hafta}</b> so'm"""),

        new Preset("oy", "📈 Oy boshidan savdo",
            "Joriy oy boshidan bugungacha savdo (MoySklad) otdellar kesimida va jami. Har kuni 09:00. "
            + "Kimga: SuperAdmin, Buxgalter.",
            "📈 Oy boshidan savdo (namuna)", "times:09:00", "", "rol:SUPERADMIN,rol:BUXGALTER", 0,
            """
            📈 <b>{oy_nomi} — oy boshidan savdo</b> ({sana} holatida)
            {#kassalar}
            🏪 {nom}: prixod <b>{prixod:oy|mln}</b> · sof <b>{sof:oy|mln}</b>
            {/kassalar}
            Jami: prixod <b>{jami.prixod:oy}</b> so'm · vozvrat {jami.vozvrat:oy} · sof <b>{jami.sof:oy}</b> so'm"""),

        new Preset("eslatma", "⏰ Kontragent eslatmalari",
            "Bugun muddati kelgan va muddati o'tgan kontragent eslatmalari. Har kuni 09:00. "
            + "Kimga: SuperAdmin, Buxgalter.",
            "⏰ Kontragent eslatmalari (namuna)", "times:09:00", "", "rol:SUPERADMIN,rol:BUXGALTER", 0,
            """
            ⏰ <b>Kontragent eslatmalari</b> — {sana}
            ❗️ <b>Bugun:</b>
            {#eslatmalar:bugun}
            • {agent} — <b>{qoldiq}</b> so'm ({yonalish})
            {/eslatmalar}
            ⚠️ <b>Muddati o'tgan:</b>
            {#eslatmalar:otgan}
            • {agent} — <b>{qoldiq}</b> so'm, {holat}
            {/eslatmalar}
            Faol eslatmalar: {jami.eslatma_faol} ta · qoldiq {jami.eslatma_qoldiq} so'm""")
    );

    public static Preset byKey(String key) {
        for (Preset p : ALL) if (p.key().equals(key)) return p;
        return null;
    }
}
