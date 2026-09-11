package uz.kassa.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Role;
import uz.kassa.repo.KassaHeadRepo;

import java.util.*;

/**
 * 🔕 Хабарномалар — bot O'ZI yuboradigan avtomatik xabarlarni tur bo'yicha va AUDITORIYA bo'yicha
 * yoqish/o'chirish (Настройка → 🔕 Хабарномалар). Standart: hammasi yoqiq; o'chirilganlar settings
 * {@code notify.off} kalitida vergul bilan saqlanadi: {@code KOD} — butun tur, {@code KOD:AUD} — faqat
 * shu auditoriya (ADMIN / BUX / RAHBAR / XODIM).
 *
 * Auditoriya foydalanuvchidan aniqlanadi ({@link #audOf}): SuperAdmin → ADMIN, Buxgalter → BUX,
 * otdel rahbari (kassa_heads) → RAHBAR, qolganlar → XODIM. Har kalitda faqat unga aslida boradigan
 * auditoriyalar ko'rsatiladi (masalan 🧮 Buxgalter — moliyaviy xabarlarda).
 *
 * 🔔 Билдиришномалар (shablonlar) bunga kirmaydi. Yuboruvchi joy yuborishdan oldin {@link #on(String)}
 * (tur) va {@link #allow(String, AppUser)} (tur + auditoriya) ni tekshiradi; o'chiq bo'lsa hisob-kitob
 * (audit, status, eskalatsiya vaqti) baribir yuriladi — faqat Telegram xabari ketmaydi.
 */
@Service
@RequiredArgsConstructor
public class NotifySwitches {

    public static final String KEY = "notify.off";

    /** Auditoriya. */
    public enum Aud {
        ADMIN("👑", "Admin"), BUX("🧮", "Buxgalter"), RAHBAR("👔", "Rahbar"), XODIM("👤", "Xodim");
        public final String emoji, title;
        Aud(String e, String t) { emoji = e; title = t; }
    }
    private static final Set<Aud> A = EnumSet.of(Aud.ADMIN);
    private static final Set<Aud> AB = EnumSet.of(Aud.ADMIN, Aud.BUX);
    private static final Set<Aud> ABR = EnumSet.of(Aud.ADMIN, Aud.BUX, Aud.RAHBAR);
    private static final Set<Aud> ABRX = EnumSet.allOf(Aud.class);
    private static final Set<Aud> ARX = EnumSet.of(Aud.ADMIN, Aud.RAHBAR, Aud.XODIM);
    private static final Set<Aud> RX = EnumSet.of(Aud.RAHBAR, Aud.XODIM);
    private static final Set<Aud> NONE = EnumSet.noneOf(Aud.class);

    /** Bitta kalit: kod, guruh, tugma nomi, izoh (kimga/qachon), auditoriyalar. */
    public record Sw(String code, String group, String title, String desc, Set<Aud> auds) {}

    public static final String G_BALANS   = "💰 Баланс ва касса";
    public static final String G_MOYSKLAD = "🔄 MoySklad ҳужжатлари";
    public static final String G_HISOBOT  = "📊 Ҳисоботлар";
    public static final String G_KG       = "🕵️ Назорат — контрагент";
    public static final String G_OT       = "📦 Назорат — отгрузка / қарз";
    public static final String G_OMBOR    = "🏬 Омбор";
    public static final String G_XODIM    = "👥 Ходимлар";
    public static final String G_TEXNIK   = "⚙️ Техник";

    /* 💰 balans / kassa */
    public static final String BAL_MANFIY     = "BAL_MANFIY";
    public static final String BAL_NOMUVOFIQ  = "BAL_NOMUVOFIQ";
    public static final String NAQD_TEKSHIRUV = "NAQD_TEKSHIRUV";
    public static final String CLICK_AVTO     = "CLICK_AVTO";
    public static final String KASSIR_ESLATMA = "KASSIR_ESLATMA";
    /* 🔄 MoySklad hujjatlari */
    public static final String MS_KIRIM        = "MS_KIRIM";
    public static final String MS_RASXOD       = "MS_RASXOD";
    public static final String MS_KLIK         = "MS_KLIK";
    public static final String MS_KASSIR_NUSXA = "MS_KASSIR_NUSXA";
    public static final String MS_TUZATISH     = "MS_TUZATISH";
    /* 📊 hisobotlar */
    public static final String CLICK_SOATLIK  = "CLICK_SOATLIK";
    public static final String KUNLIK_HISOBOT = "KUNLIK_HISOBOT";
    public static final String QARZ_ESLATMA   = "QARZ_ESLATMA";
    /* 🕵️ kontragent */
    public static final String KG_XATO        = "KG_XATO";
    public static final String KG_TUZATILDI   = "KG_TUZATILDI";
    public static final String KG_ESKALATSIYA = "KG_ESKALATSIYA";
    public static final String KG_KUNLIK      = "KG_KUNLIK";
    public static final String KG_XUSH        = "KG_XUSH";
    /* 📦 otgruzka / qarz */
    public static final String OT_QARZ          = "OT_QARZ";
    public static final String OT_QARZ_YOPILDI  = "OT_QARZ_YOPILDI";
    public static final String OT_KAMCHILIK     = "OT_KAMCHILIK";
    public static final String OT_ESKALATSIYA   = "OT_ESKALATSIYA";
    public static final String OT_KUNLIK_XODIM  = "OT_KUNLIK_XODIM";
    public static final String OT_KUNLIK_RAHBAR = "OT_KUNLIK_RAHBAR";
    public static final String OT_KUNLIK_ADMIN  = "OT_KUNLIK_ADMIN";
    public static final String OT_QARZ_ESLATMA  = "OT_QARZ_ESLATMA";
    /* 🏬 ombor */
    public static final String OM_KAMCHILIK   = "OM_KAMCHILIK";
    public static final String OM_ESKALATSIYA = "OM_ESKALATSIYA";
    /* 👥 xodimlar */
    public static final String XODIM_ULANDI = "XODIM_ULANDI";
    public static final String XODIM_OGOH   = "XODIM_OGOH";
    /* ⚙️ texnik */
    public static final String TEXNIK_OGOH = "TEXNIK_OGOH";

    public static final List<Sw> ALL = List.of(
            new Sw(BAL_MANFIY, G_BALANS, "⚠️ Manfiy balans",
                    "sinxron natijasida kassa/click balansi manfiyga tushganda", AB),
            new Sw(BAL_NOMUVOFIQ, G_BALANS, "⚠️ Balans nomuvofiqligi",
                    "kunlar kesimi ≠ balans, balans yaxlitligi buzilgan (har 30 daqiqada tekshiruv)", A),
            new Sw(NAQD_TEKSHIRUV, G_BALANS, "💵 Naqd tekshiruvi",
                    "MoySklad CASH va bot jami naqd farqi topildi/yopildi (soatlik)", AB),
            new Sw(CLICK_AVTO, G_BALANS, "🔄 Click avto-tenglashtirish",
                    "karta qoldig'i MoySklad bilan tenglashtirilganda", AB),
            new Sw(KASSIR_ESLATMA, G_BALANS, "🔔 Kunlik kassir eslatmasi",
                    "kechqurun: bugungi kirim, topshirilmagan kunlar, qo'ldagi qoldiq → kassirlar", RX),

            new Sw(MS_KIRIM, G_MOYSKLAD, "💰 Kirim xabarlari",
                    "MoySklad'dan bugungi kirim (kassa / Click / Основной)", AB),
            new Sw(MS_RASXOD, G_MOYSKLAD, "💸 Rasxod xabarlari",
                    "MoySklad'dan bugungi naqd rasxod (Выплата, Расходный ордер)", AB),
            new Sw(MS_KLIK, G_MOYSKLAD, "📲 Klik chiqim xabarlari",
                    "Klik kassadan chiqim", AB),
            new Sw(MS_KASSIR_NUSXA, G_MOYSKLAD, "👷 Kassirga hujjat nusxasi",
                    "o'z kassasidagi kirim/rasxod/klik xabarining nusxasi → o'sha kassa kassirlari", RX),
            new Sw(MS_TUZATISH, G_MOYSKLAD, "✏️ Avto-tuzatishlar",
                    "summa o'zgargan, otdel ko'chirildi, STORNO, «o'tgan kunlardagi N ta tuzatildi»", AB),

            new Sw(CLICK_SOATLIK, G_HISOBOT, "📲 Click soatlik hisobot",
                    "«CLICK ҚОЛДИҚЛАРИ» jadval bo'yicha → Click guruh/kanallari", NONE),
            new Sw(KUNLIK_HISOBOT, G_HISOBOT, "📋 Kunlik kassa solishtirish",
                    "rasm + Excel + tasdiq tugmasi → Click guruhlari (doim) + shaxsiy", AB),
            new Sw(QARZ_ESLATMA, G_HISOBOT, "🧾 Qarz daftari eslatmalari",
                    "qo'lda kiritilgan eslatmalar (muddat/takror), avto-yopilish → eslatma oluvchilari", ABRX),

            new Sw(KG_XATO, G_KG, "⚠️ Kontragent xatosi",
                    "yangi kontragentda K1–K8 xato → yaratgan xodim (ulanmagan bo'lsa admin)", ARX),
            new Sw(KG_TUZATILDI, G_KG, "✅ Tuzatildi xabari",
                    "«Kontragent tuzatildi, rahmat» → xodim", RX),
            new Sw(KG_ESKALATSIYA, G_KG, "⏰ Eskalatsiya",
                    "tuzatilmasa rahbarga, keyin «TUZATILMADI» admin'ga", ABR),
            new Sw(KG_KUNLIK, G_KG, "📋 Kunlik jamlama",
                    "har kuni tuzatilmagan kontragentlar ro'yxati → xodim", RX),
            new Sw(KG_XUSH, G_KG, "👋 Xush kelibsiz (nazorat)",
                    "xodim botga ulanganda: kutib turgan xato/qarz xabarlari → xodim", RX),

            new Sw(OT_QARZ, G_OT, "🧾 Qarzga tushdi",
                    "otgruzka to'lovi to'liq emas → xodim, Масъул, rahbar, admin", ABRX),
            new Sw(OT_QARZ_YOPILDI, G_OT, "✅ Qarz to'landi / bekor",
                    "qarz to'langanda yoki qarzdagi otgruzka bekor bo'lganda", ABRX),
            new Sw(OT_KAMCHILIK, G_OT, "📦 Otgruzka kamchiliklari",
                    "O1–O5 (muddat/masul/telefon…) guruhlangan → xodim (ulanmagan bo'lsa admin)", ARX),
            new Sw(OT_ESKALATSIYA, G_OT, "⏰ Kamchilik eskalatsiyasi",
                    "tuzatilmasa rahbarga, keyin «TUZATILMADI» admin'ga", ABR),
            new Sw(OT_KUNLIK_XODIM, G_OT, "🔔 Kunlik: xodimga",
                    "«Qarzdorlaringiz» → xodim / Масъул", RX),
            new Sw(OT_KUNLIK_RAHBAR, G_OT, "🔔 Kunlik: rahbarga",
                    "«Otdel qarzdorlari» → otdel rahbarlari, otdelli belgilanganlar", RX),
            new Sw(OT_KUNLIK_ADMIN, G_OT, "🔔 Kunlik: admin'ga",
                    "«Qarzdorlar — umumiy» → SuperAdmin + belgilanganlar", AB),
            new Sw(OT_QARZ_ESLATMA, G_OT, "⏳ Muddat eslatmasi",
                    "muddatidan oldin/keyin takror eslatma → xodim / Масъул", RX),

            new Sw(OM_KAMCHILIK, G_OMBOR, "🏬 Ombor kamchiliklari",
                    "23 qoida bo'yicha yangi kamchilik → qoidada belgilangan rol", ABRX),
            new Sw(OM_ESKALATSIYA, G_OMBOR, "⏰ Ombor eskalatsiyasi",
                    "tuzatilmasa rahbarga, keyin admin'ga", ABR),

            new Sw(XODIM_ULANDI, G_XODIM, "🔗 Xodim botga ulandi",
                    "taklif havolasi / telefon / MoySklad xodimi orqali ulanish", A),
            new Sw(XODIM_OGOH, G_XODIM, "📱 Kontakt ogohlantirishlari",
                    "yangi notanish kontakt, telefon mos kelmadi, faolsiz foydalanuvchi", A),

            new Sw(TEXNIK_OGOH, G_TEXNIK, "🚨 Texnik ogohlantirishlar",
                    "job 10 marta yiqildi, MoySklad token huquqi, valyuta kursi yo'q, dublikat otdel, noma'lum Klik statusi", AB)
    );

    public static List<String> groups() {
        List<String> out = new ArrayList<>();
        for (Sw s : ALL) if (!out.contains(s.group())) out.add(s.group());
        return out;
    }

    public static List<Sw> inGroup(String group) {
        List<Sw> out = new ArrayList<>();
        for (Sw s : ALL) if (s.group().equals(group)) out.add(s);
        return out;
    }

    public static Sw find(String code) {
        for (Sw s : ALL) if (s.code().equals(code)) return s;
        return null;
    }


    private final SettingsService settings;
    private final KassaHeadRepo headRepo;

    private volatile Set<String> off;
    private volatile long loadedAt;

    private Set<String> off() {
        Set<String> o = off;
        if (o == null || System.currentTimeMillis() - loadedAt > 5_000) o = reload();
        return o;
    }

    private synchronized Set<String> reload() {
        Set<String> o = new LinkedHashSet<>();
        for (String p : settings.get(KEY).orElse("").split(","))
            if (!p.isBlank()) o.add(p.trim().toUpperCase());
        off = o;
        loadedAt = System.currentTimeMillis();
        return o;
    }

    /* ---------- o'qish ---------- */

    /** Shu turdagi xabar umuman yuborilsinmi (standart: ha). */
    public boolean on(String code) { return !off().contains(code); }

    /** Shu tur shu auditoriyaga yuborilsinmi (tur yoqiq VA auditoriya o'chirilmagan). */
    public boolean on(String code, Aud aud) {
        return on(code) && (aud == null || !off().contains(code + ":" + aud.name()));
    }

    /** Foydalanuvchining auditoriyasi: SuperAdmin → ADMIN, Buxgalter → BUX, otdel rahbari → RAHBAR, qolgan → XODIM. */
    public Aud audOf(AppUser u) {
        if (u == null) return Aud.XODIM;
        if (u.getRole() == Role.SUPERADMIN) return Aud.ADMIN;
        if (u.getRole() == Role.BUXGALTER) return Aud.BUX;
        try { if (u.getId() != null && !headRepo.findByUserId(u.getId()).isEmpty()) return Aud.RAHBAR; }
        catch (Exception ignored) { }
        return Aud.XODIM;
    }

    /** Shu foydalanuvchiga shu turdagi xabar ketsinmi. */
    public boolean allow(String code, AppUser u) { return on(code, audOf(u)); }

    /** Auditoriyasi o'chirilganlar chiqarib tashlangan ro'yxat (tur o'chiq bo'lsa — bo'sh). */
    public List<AppUser> filter(String code, Collection<AppUser> users) {
        List<AppUser> out = new ArrayList<>();
        if (!on(code)) return out;
        for (AppUser u : users) if (on(code, audOf(u))) out.add(u);
        return out;
    }

    /* ---------- yozish ---------- */

    public void toggle(String code) {
        Set<String> o = new LinkedHashSet<>(off());
        if (!o.remove(code)) o.add(code);
        save(o);
    }

    public void toggleAud(String code, Aud aud) {
        Set<String> o = new LinkedHashSet<>(off());
        String k = code + ":" + aud.name();
        if (!o.remove(k)) o.add(k);
        save(o);
    }

    /** Guruhni butunlay yoqish/o'chirish (yoqishda auditoriya cheklovlari ham tozalanadi). */
    public void setGroup(String group, boolean on) {
        Set<String> o = new LinkedHashSet<>(off());
        for (Sw s : inGroup(group)) {
            if (on) o.removeIf(k -> k.equals(s.code()) || k.startsWith(s.code() + ":"));
            else o.add(s.code());
        }
        save(o);
    }

    private void save(Set<String> o) {
        settings.set(KEY, o.isEmpty() ? " " : String.join(",", o));
        reload();
    }

    /* ---------- UI uchun ---------- */

    public int onCount(String group) {
        int n = 0;
        for (Sw s : inGroup(group)) if (on(s.code())) n++;
        return n;
    }

    /** Kalit yoqiq, lekin ba'zi auditoriyalari o'chiq. */
    public boolean partial(Sw s) {
        if (!on(s.code())) return false;
        for (Aud a : s.auds()) if (!on(s.code(), a)) return true;
        return false;
    }

    /** Yoqiq auditoriyalar emoji qatori (👑🧮👔👤). */
    public String audLine(Sw s) {
        StringBuilder sb = new StringBuilder();
        for (Aud a : s.auds()) if (on(s.code(), a)) sb.append(a.emoji);
        return sb.toString();
    }

    public int offTotal() {
        int n = 0;
        for (String k : off()) if (!k.contains(":")) n++;
        return n;
    }
}
