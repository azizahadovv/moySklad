package uz.kassa.service.moysklad;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.bot.TextUtil;
import uz.kassa.config.AppProps;
import uz.kassa.domain.*;
import uz.kassa.repo.CategoryRepo;
import uz.kassa.repo.ClickAccountRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.repo.OperationRepo;
import uz.kassa.service.LedgerService;
import uz.kassa.service.NotificationService;
import uz.kassa.service.SettingsService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static uz.kassa.service.moysklad.MoySkladSyncService.*;

/**
 * Sinxron uchun umumiy holat va yordamchilar: sikl konteksti (Ctx), ledger boshlanish sanasi, valyuta tekshiruvi, STORNO, xabar toshqiniga qarshi hisoblagich, nomlar.
 * (MoySkladSyncService dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SyncSupport {

    private final MoySkladClient client;
    private final LedgerService ledger;
    private final KassaRepo kassaRepo;
    private final CategoryRepo categoryRepo;
    private final ClickAccountRepo clickRepo;
    private final SettingsService settings;
    private final NotificationService notify;
    private final AppProps props;

    /**
     * Xabar toshqiniga qarshi: BUGUNGI hujjat tuzatishlari alohida xabar bilan,
     * ESKI (o'tgan kunlardagi) hujjat tuzatishlari esa faqat sanab boriladi va
     * sikl oxirida bitta umumlashma xabar yuboriladi. sync()/reconcile()
     * synchronized bo'lgani uchun oddiy int yetarli.
     */
    private int quietFixes = 0;

    /** 📥 Qayta yuklash davomida hujjatma-hujjat xabarlar butunlay o'chiriladi. */
    private volatile boolean quietReload = false;

    /** So'm (UZS) valyuta UUID lari — boshqa valyutadagi hujjatlar kurs bilan so'mga o'giriladi. */
    private volatile java.util.Set<String> somCurrencyIds = java.util.Set.of();

    /** Valyuta UUID -> ISO kod (USD, RUB...) — xabar/izohlarda ko'rsatish uchun. */
    private volatile java.util.Map<String, String> currencyIso = java.util.Map.of();

    /** «Kurs kiritilmagan» ogohlantirishi har hujjat uchun bir marta yuboriladi. */
    private final java.util.Set<String> noRateWarned =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Dublikat-otdel ogohlantirishi oxirgi yuborilgan vaqt (6 soatda 1 marta). */
    private volatile long lastDupWarnAt = 0;


    /** Sikl boshida jim tuzatishlar hisoblagichini nolga qaytarish. */
    void resetQuietFixes() { quietFixes = 0; }


    /** 📥 Qayta yuklash rejimi: hujjatma-hujjat xabarlar butunlay o'chiriladi. */
    void setQuietReload(boolean on) { quietReload = on; }


    /** Tuzatish xabarini yuborish kerakmi: bugungi hujjat — ha; eski — sanaladi. */
    boolean loudFix(LocalDate docDate) {
        if (!quietReload && docDate.equals(ledger.today())) return true;
        quietFixes++;
        return false;
    }


    /** Sikl oxirida jim tuzatishlar bo'yicha bitta umumlashma xabar. */
    void flushQuietFixes(String source) {
        int n = quietFixes;
        quietFixes = 0;
        if (n > 0) notify.toBuxgalteriya("🔧 MoySklad " + source + ": o'tgan kunlardagi <b>" + n
                + "</b> ta hujjat o'zgarishi (otdel/summa/storno) avtomatik tuzatildi. "
                + "Tafsilotlar: Настройка → 📋 Аудит.", null);
    }


    /** Bir siklda qayta ishlatiladigan xaritalar. */
    record Ctx(Map<String, Long> storeToKassa, Map<String, Long> groupToKassa,
                       Map<String, String> groupNames, Map<String, Long> catByName,
                       java.util.Set<String> dupGroups, Map<String, Long> accountToClick) {}


    Ctx buildCtx() {
        Map<String, Long> storeToKassa = new HashMap<>();
        Map<String, Long> groupToKassa = new HashMap<>();
        // Bitta otdel bir nechta faol kassaga biriktirilgan bo'lsa — sozlash xatosi:
        // hujjatlar kassalar orasida sakramasligi uchun ENG BIRINCHI (kichik id)
        // kassa qoladi, otdel-o'zgarish (reroute) esa bu guruh uchun to'xtatiladi.
        java.util.Set<String> dupGroups = new java.util.HashSet<>();
        Map<String, java.util.List<String>> dupNames = new HashMap<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.getMoyskladStoreId() != null && !k.getMoyskladStoreId().isBlank())
                storeToKassa.put(k.getMoyskladStoreId(), k.getId());
            if (k.getMoyskladGroupId() != null && !k.getMoyskladGroupId().isBlank()) {
                String g = k.getMoyskladGroupId();
                if (groupToKassa.containsKey(g)) {
                    dupGroups.add(g);
                    dupNames.computeIfAbsent(g, x -> new java.util.ArrayList<>(java.util.List.of(
                            kassaRepo.findById(groupToKassa.get(g)).map(Kassa::getName).orElse("?"))))
                            .add(k.getName());
                } else groupToKassa.put(g, k.getId());
            }
        }
        Map<String, String> groupNames = client.fetchGroups();

        if (!dupGroups.isEmpty() && System.currentTimeMillis() - lastDupWarnAt > 6 * 3600_000L) {
            lastDupWarnAt = System.currentTimeMillis();
            StringBuilder sb = new StringBuilder("⚠️ <b>SOZLASH XATOSI</b> — bitta MoySklad otdeli "
                    + "bir nechta kassaga biriktirilgan:\n");
            for (String g : dupGroups)
                sb.append("\n• <b>").append(TextUtil.esc(groupNames.getOrDefault(g, g)))
                  .append("</b>: ").append(TextUtil.esc(String.join(", ", dupNames.get(g))));
            sb.append("\n\nHujjatlar eng birinchi kassaga yoziladi, otdel-ko'chirishlar esa "
                    + "TO'XTATILDI (xabar yog'ilib ketmasligi uchun). Sheets «Kassalar» varag'ida "
                    + "yoki bazada otdelni faqat bitta kassada qoldiring.");
            notify.toRole(uz.kassa.domain.Role.SUPERADMIN, sb.toString(), null);
        }

        java.util.Map<String, String> isoMap = client.fetchCurrencies();
        if (!isoMap.isEmpty()) {   // xatoda eski ro'yxat saqlanadi — filtr o'chib qolmasin
            java.util.Set<String> som = new java.util.HashSet<>();
            isoMap.forEach((id, iso) -> {
                if (iso.equalsIgnoreCase("UZS")) som.add(id);
            });
            somCurrencyIds = som;
            currencyIso = isoMap;
        }

        // «Статья расходов» nomi tizim kategoriyasiga mos kelsa — avtomatik biriktiriladi
        Map<String, Long> catByName = new HashMap<>();
        for (Category c : categoryRepo.findByActiveTrueOrderByIdAsc())
            catByName.put(c.getName().trim().toLowerCase(), c.getId());

        // MoySklad "organizationAccount" -> Click hisobi: shu hisobga tushgan Клик
        // to'lovlari otdel/kassa o'rniga aynan shu hisobga alohida yoziladi.
        Map<String, Long> accountToClick = new HashMap<>();
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc())
            if (c.getMoyskladAccountId() != null && !c.getMoyskladAccountId().isBlank())
                accountToClick.put(c.getMoyskladAccountId(), c.getId());

        return new Ctx(storeToKassa, groupToKassa, groupNames, catByName, dupGroups, accountToClick);
    }


    LocalDate epoch() {
        String s = settings.get(LEDGER_START_KEY).orElse("").trim();
        if (s.isBlank()) s = props.getMoysklad().getLedgerStartDate();
        if (s == null || s.isBlank()) return LocalDate.MIN;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            log.warn("ledger-start-date noto'g'ri format: {}", s);
            return LocalDate.MIN;
        }
    }


    /** Kalibratsiyadan oldingi, ledger'da yo'q hujjat — yangidan yozilmaydi. */
    boolean skipNewPreEpoch(LocalDate docDate, Optional<Operation> existing) {
        return existing.isEmpty() && docDate.isBefore(epoch());
    }


    /** So'mda emasligini aniqlash (hujjat valyutasi UZS emas). */
    boolean notSom(String currencyId) {
        return !somCurrencyIds.isEmpty() && !currencyId.isEmpty()
                && !somCurrencyIds.contains(currencyId);
    }


    /**
     * Hujjat summasi SO'MDA: so'm hujjat — o'z summasi; valyuta hujjat —
     * MoySklad'da kiritilgan kurs (rate.value) bilan so'mga o'giriladi
     * (masalan $1 000 × 12 000 = 12 000 000). Kurs KIRITILMAGAN valyuta
     * hujjatida -1 qaytadi — tizim o'zidan kurs taxmin qilmaydi.
     */
    long somSum(MoySkladClient.MsExpense e) {
        if (!notSom(e.currencyId())) return e.sumTiyin() / 100;
        if (e.rateValue() <= 0) return -1;
        return Math.round(e.sumTiyin() / 100.0 * e.rateValue());
    }


    /** Valyuta hujjati izohi uchun belgi: «💱 1 000 USD × 12 000». */
    String fxNote(MoySkladClient.MsExpense e) {
        if (!notSom(e.currencyId())) return "";
        String iso = currencyIso.getOrDefault(e.currencyId(), "valyuta");
        return "💱 " + TextUtil.fmt(e.sumTiyin() / 100) + " " + iso + " × " + trimRate(e.rateValue());
    }


    String trimRate(double v) {
        return v == Math.rint(v) ? TextUtil.fmt((long) v) : String.valueOf(v);
    }


    /** Kurs kiritilmagan valyuta hujjati — bir marta ogohlantirish. */
    void warnNoRate(MoySkladClient.MsExpense e) {
        if (!noRateWarned.add(e.id())) return;
        String iso = currencyIso.getOrDefault(e.currencyId(), "valyuta");
        notify.toBuxgalteriya("💱⚠️ MoySklad hujjatida <b>valyuta kursi kiritilmagan</b> — "
                + "tizimga o'tkazilmadi:\n" + docInfo(e)
                + "\nSumma: <b>" + TextUtil.fmt(e.sumTiyin() / 100) + " " + TextUtil.esc(iso)
                + "</b>\nMoySklad'da kursni kiriting — keyingi sinxronda avtomatik kiradi.", null);
    }


    /* ==================== yordamchi ==================== */

    /** Sinxron opni STORNO qilib buxgalteriyaga xabar berish. */
    boolean storno(Operation op, String reason, String info) {
        String owner = ownerName(op);
        long amount = op.getAmount();
        MoneyType mt = op.getMoneyType();
        String kind = op.getType() == OpType.RASXOD ? "chiqim"
                : op.getType() == OpType.VOZVRAT ? "vozvrat" : "kirim";
        String msId = op.getMoyskladId();
        LocalDate opDate = op.getOpDate();
        if (!ledger.reverseSyncOp(op, reason)) return false;
        if (loudFix(opDate))
            notify.toBuxgalteriya("♻️ MoySklad STORNO — " + TextUtil.esc(reason) + ":\n"
                    + "<b>" + TextUtil.esc(owner) + "</b> — " + TextUtil.fmt(amount)
                    + " so'm (" + mtLabel(mt) + ", " + kind + ")"
                    + (info.isEmpty() ? "" : "\n" + info), null);
        log.info("STORNO {}: {} — {} so'm {}", msId, reason, amount, mt);
        return true;
    }


    /** Operatsiya egasining nomi (kirimda to-, chiqim/vozvratda from-tomon). */
    String ownerName(Operation op) {
        OwnerType ot = op.getType() == OpType.PRIXOD ? op.getToOwnerType() : op.getFromOwnerType();
        Long oid = op.getType() == OpType.PRIXOD ? op.getToOwnerId() : op.getFromOwnerId();
        return ownerDisplayName(ot, oid);
    }


    /** Egasi nomi: Отдел Основной / kassa nomi / Click hisobi nomi. */
    String ownerDisplayName(OwnerType ot, Long oid) {
        if (ot == OwnerType.BUXGALTERIYA) return "Отдел Основной";
        if (ot == OwnerType.CLICK)
            return clickRepo.findById(oid).map(ClickAccount::getName).orElse("Klik #" + oid);
        return kassaRepo.findById(oid).map(Kassa::getName).orElse("Kassa #" + oid);
    }


    String mtLabel(MoneyType mt) {
        return switch (mt) {
            case KLIK -> "📲 Klik"; case TERMINAL -> "💳 Terminal"; default -> "💵 Naqd";
        };
    }


    /** Xabar matni uchun hujjat rekvizitlari: №raqam · sana · status · kontragent. */
    String docInfo(MoySkladClient.MsExpense e) {
        StringBuilder sb = new StringBuilder("📄 №" + TextUtil.esc(e.docNo())
                + " · " + e.date().format(D_UZ));
        if (!e.state().isBlank()) sb.append(" · ").append(TextUtil.esc(e.state()));
        if (!e.agent().isBlank()) sb.append("\n👤 ").append(TextUtil.esc(e.agent()));
        return sb.toString();
    }


    /**
     * Faqat bugungi hujjatlar haqida xabar yuboriladi — tarixiy backfill paytida
     * yuzlab eski hujjat spam bo'lib ketmasligi uchun (ular baribir bazaga yoziladi).
     */
    boolean shouldNotify(MoySkladClient.MsExpense e) {
        return !quietReload && e.date().equals(ledger.today());
    }


    void checkNegative(OwnerType ot, Long oid, String sabab) {
        checkNegative(ot, oid, MoneyType.NAQD, sabab);
    }


    void checkNegative(OwnerType ot, Long oid, MoneyType mt, String sabab) {
        if (quietReload) return;
        long bal = ledger.view(ot, oid, mt).getAmount();
        if (bal < 0) {
            notify.toBuxgalteriya("⚠️ " + sabab + " natijasida <b>" + TextUtil.esc(ownerDisplayName(ot, oid))
                    + "</b> " + mtLabel(mt) + " balansi manfiy: " + TextUtil.fmt(bal)
                    + " so'm. Korrektirovka talab qilinadi.", null);
        }
    }


    Long matchCat(Map<String, Long> cats, String expenseItem) {
        if (expenseItem == null || expenseItem.isBlank()) return null;
        return cats.get(expenseItem.trim().toLowerCase());
    }


    /** «Статья расходов» + izohni birlashtirish, 450 belgigacha qisqartirish. */
    String joinNote(String expenseItem, String description) {
        String a = expenseItem == null ? "" : expenseItem.trim();
        String b = description == null ? "" : description.trim();
        String s = a.isEmpty() ? b : (b.isEmpty() ? a : a + " — " + b);
        return s.length() > 450 ? s.substring(0, 450) + "…" : s;
    }

}
