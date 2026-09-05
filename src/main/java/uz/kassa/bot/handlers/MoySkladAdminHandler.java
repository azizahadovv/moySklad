package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.*;
import uz.kassa.domain.*;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.service.LedgerService;
import uz.kassa.service.NotificationService;
import uz.kassa.service.moysklad.MoySkladClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.*;
import static uz.kassa.bot.handlers.AdminSupport.*;

/**
 * 🔑 MoySklad: API kalit, 🔄 nomlar, 📅 ledger sanasi, 🩺 diagnostika, 📥 qayta yuklash.
 * (AdminHandler dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
public class MoySkladAdminHandler {

    private final Sender sender;
    private final LedgerService ledger;
    private final AppUserRepo userRepo;
    private final KassaRepo kassaRepo;
    private final MoySkladClient msClient;
    private final uz.kassa.repo.DayRepo dayRepo;
    private final uz.kassa.repo.OperationRepo opRepo;
    private final uz.kassa.repo.ClickAccountRepo clickRepo;
    private final uz.kassa.service.moysklad.MoySkladSyncService syncService;
    private final uz.kassa.service.AuditService audit;
    private final uz.kassa.config.AppProps props;
    private final uz.kassa.service.SettingsService settings;
    private final AdminSupport sup;



    /* ==================================================================
     * 🔑 MOYSKLAD API KALITI — botning o'zidan ko'rish/almashtirish.
     * Kalit settings jadvalida saqlanadi (.env dagi zaxira bo'lib qoladi),
     * shuning uchun API ulanmagan/yaroqsiz paytda ham shu yerdan tuzatiladi.
     * ================================================================== */

    void msToken(Session s, long chatId, int msgId) {
        String t = msClient.currentToken();
        String masked = t.isBlank() ? "<i>kiritilmagan</i>"
                : t.length() > 12 ? "<code>" + esc(t.substring(0, 6)) + "…"
                    + esc(t.substring(t.length() - 4)) + "</code>"
                : "<code>•••</code>";
        boolean ok = !t.isBlank() && msClient.testToken(t);
        String text = "🔑 <b>MoySklad API kaliti</b>\n\n"
                + "Joriy kalit: " + masked + "\n"
                + "Holat: " + (ok ? "🟢 <b>ulangan</b> — API javob beryapti"
                    : "🔴 <b>ulanmagan</b> — kalit yo'q yoki yaroqsiz")
                + "\n\nYangi kalit kiritsangiz, sinxron darhol yangi kalit bilan ishlaydi.";
        InlineKeyboardMarkup kb = inline(List.of(
                irow(btn("✏️ Yangi kalit kiritish", "a:mske")),
                irow(sup.bk("a:p:set"))));
        if (msgId > 0) sender.edit(chatId, msgId, text, kb);
        else sup.sendContent(s, chatId, text, kb);
    }



    void msTokenSave(AppUser u, Session s, String text, long chatId) {
        s.state = Session.State.IDLE;
        if (text.equals("-")) {
            sender.send(chatId, "❌ Bekor qilindi — kalit o'zgartirilmadi.");
            return;
        }
        String token = text.trim();
        if (token.length() < 20 || token.contains(" ")) {
            s.state = Session.State.ADM_MS_TOKEN;
            sender.send(chatId, "⚠️ Bu MoySklad kalitiga o'xshamaydi (juda qisqa yoki "
                    + "bo'sh joy bor). Qaytadan yuboring yoki «-» bilan bekor qiling:");
            return;
        }
        boolean ok = msClient.testToken(token);
        msClient.updateToken(token);
        audit.log(u.getId(), "MS_TOKEN_YANGILANDI", "settings", null,
                "yangi kalit: " + token.substring(0, 6) + "… (test: " + (ok ? "OK" : "XATO") + ")");
        if (ok) {
            sender.send(chatId, "✅ <b>Yangi kalit saqlandi va tekshirildi</b> — API javob berdi.\n"
                    + "Sinxron keyingi siklda (30 soniyagacha) yangi kalit bilan ishlaydi.");
            new Thread(syncService::sync).start();
        } else {
            sender.send(chatId, "⚠️ Kalit <b>saqlandi</b>, lekin API hozircha javob bermadi "
                    + "(yaroqsiz kalit yoki tarmoq muammosi bo'lishi mumkin).\n"
                    + "Kalit to'g'ri bo'lsa, sinxron o'zi tiklanadi. Holatni "
                    + "🔑 MoySklad API bo'limidan qayta tekshiring.");
        }
    }



    /* ==================================================================
     * 📅 LEDGER САНАСИ — MoySklad sinxron shu sanadan OLDINGI, bazada yo'q
     * hujjatlarni qayta o'qimaydi (qo'lda kalibratsiya buzilmasligi uchun).
     * Bot ichidan o'zgartirilsa settings ustuvor, .env (MOYSKLAD_LEDGER_START)
     * zaxira bo'lib qoladi.
     * ================================================================== */

    void ledgerMenu(Session s, long chatId, int msgId) {
        String override = settings.get(
                uz.kassa.service.moysklad.MoySkladSyncService.LEDGER_START_KEY).orElse("").trim();
        String env = props.getMoysklad().getLedgerStartDate();
        java.time.LocalDate eff = syncService.effectiveEpoch();
        String effStr = eff.equals(java.time.LocalDate.MIN)
                ? "❌ Belgilanmagan (cheklov yo'q)" : eff.format(DF);
        String source = !override.isBlank() ? "bot sozlamasi"
                : (env != null && !env.isBlank() ? ".env (MOYSKLAD_LEDGER_START)" : "—");
        String text = "📅 <b>Ledger boshlanish sanasi</b>\n\n"
                + "Amaldagi sana: <b>" + effStr + "</b> (manba: " + source + ")\n\n"
                + "Bu sanadan OLDINGI, bazada YO'Q MoySklad hujjatlari sinxron/reconcile "
                + "orqali qayta o'qilmaydi — boshlang'ich qoldiqlar shu sanaga kalibrlangan, "
                + "eski hujjatlar ikki marta hisoblanmasligi uchun. Bazada YOZUVI BOR "
                + "hujjatning o'zgarishi esa sanasidan qat'i nazar doim qo'llanadi.\n\n"
                + "⚠️ Odatda bu sana boshlang'ich qoldiq QAYTA kiritilgandagina o'zgartiriladi.";
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("✏️ Sanani o'zgartirish", "a:lse")));
        if (!override.isBlank() && env != null && !env.isBlank())
            rows.add(irow(btn("♻️ .env qiymatiga qaytarish (" + env + ")", "a:lsx")));
        rows.add(irow(sup.bk("a:p:set")));
        InlineKeyboardMarkup kb = inline(rows);
        if (msgId > 0) sender.edit(chatId, msgId, text, kb);
        else sup.sendContent(s, chatId, text, kb);
    }



    void lsSave(AppUser u, Session s, String text, long chatId) {
        s.state = Session.State.IDLE;
        if (text.equals("-")) {
            sender.send(chatId, "❌ Bekor qilindi.");
            ledgerMenu(s, chatId, 0);
            return;
        }
        java.time.LocalDate d;
        try {
            String t = text.trim();
            d = t.contains(".") ? java.time.LocalDate.parse(t, DF) : java.time.LocalDate.parse(t);
        } catch (Exception e) {
            s.state = Session.State.ADM_LS_DATE;
            sender.send(chatId, "⚠️ Sana formati noto'g'ri. <code>2026-08-26</code> yoki "
                    + "<code>26.08.2026</code> ko'rinishida yuboring, yoki «-» bilan bekor qiling:");
            return;
        }
        if (d.isAfter(ledger.today())) {
            s.state = Session.State.ADM_LS_DATE;
            sender.send(chatId, "⚠️ Kelajak sanasi bo'lmaydi — sinxron butunlay to'xtab qolardi. "
                    + "Boshqa sana yuboring yoki «-» bilan bekor qiling:");
            return;
        }
        settings.set(uz.kassa.service.moysklad.MoySkladSyncService.LEDGER_START_KEY, d.toString());
        audit.log(u.getId(), "LEDGER_SANA", "settings", null,
                u.getFullName() + " ledger boshlanish sanasini o'zgartirdi: " + d);
        sender.send(chatId, "✅ Ledger boshlanish sanasi <b>" + d.format(DF) + "</b> qilib "
                + "saqlandi. Keyingi sinxron sikllaridan boshlab shu sanadan oldingi yangi "
                + "hujjatlar o'qilmaydi.");
        ledgerMenu(s, chatId, 0);
    }



    /* ==================================================================
     * 🩺 ДИАГНОСТИКА — minus balanslar va minus kunlarni topib, sababi bilan
     * ko'rsatadi; har bir muammoga bir bosishda Корректировка oqimiga o'tiladi.
     * Kassa/sklad minusda bo'lishining tipik sabablari:
     *  - rasxod hujjatlari kirimdan oldin/ko'p kelgan (MoySklad'da kirim boshqa
     *    otdelga yozilgan yoki umuman kiritilmagan);
     *  - boshlang'ich qoldiq kiritilmagan yoki noto'g'ri sana bilan kiritilgan;
     *  - korrektirovka summasi/sanasi xato;
     *  - kun ichida qoplash (qabul) haqiqiy tushumdan ortiq qilingan.
     * ================================================================== */

    void diagMenu(Session s, long chatId, int msgId) {
        StringBuilder sb = new StringBuilder("🩺 <b>Диагностика — minus tekshiruvi</b>\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int issues = 0;

        // 1) Balanslar: Основной, har kassa (naqd/klik), Click hisoblari
        StringBuilder bal = new StringBuilder();
        long bn = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD).getAmount();
        long bkl = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.KLIK).getAmount();
        if (bn < 0) bal.append("🔻 🏦 Основной — 💵 Naqd: <b>").append(fmt(bn)).append("</b> so'm\n");
        if (bkl < 0) bal.append("🔻 🏦 Основной — 📲 Click: <b>").append(fmt(bkl)).append("</b> so'm\n");
        if (bn < 0 || bkl < 0) rows.add(irow(btn("🛠 Основной tuzatish", "a:fixo:B")));

        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            long n = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.NAQD).getAmount();
            long kl = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.KLIK).getAmount();
            if (n < 0) bal.append("🔻 🏪 ").append(esc(k.getName()))
                    .append(" — 💵 Naqd: <b>").append(fmt(n)).append("</b> so'm\n");
            if (kl < 0) bal.append("🔻 🏪 ").append(esc(k.getName()))
                    .append(" — 📲 Click: <b>").append(fmt(kl)).append("</b> so'm\n");
            if (n < 0 || kl < 0)
                rows.add(irow(btn("🛠 " + k.getName() + " tuzatish", "a:fixo:K" + k.getId())));
        }
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
            long v = ledger.view(OwnerType.CLICK, c.getId(), MoneyType.KLIK).getAmount();
            if (v < 0) {
                bal.append("🔻 📲 ").append(esc(c.getName()))
                        .append(": <b>").append(fmt(v)).append("</b> so'm\n");
                rows.add(irow(btn("🛠 " + c.getName() + " tuzatish", "a:fixo:C" + c.getId())));
            }
        }
        if (bal.length() > 0) {
            issues++;
            sb.append("\n<b>Minus balanslar:</b>\n").append(bal);
        }

        // 2) Topshirilmagan (OCHIQ/YOPILGAN) kunlarda minus qoldiq
        StringBuilder days = new StringBuilder();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            for (DayRecord d : dayRepo.findByKassaIdAndStatusInOrderByDateAsc(
                    k.getId(), List.of(DayStatus.OCHIQ, DayStatus.YOPILGAN))) {
                if (d.remainNaqd() < 0)
                    days.append("🔻 🏪 ").append(esc(k.getName())).append(" • ")
                        .append(d.getDate().format(DF)).append(" — 💵 <b>")
                        .append(fmt(d.remainNaqd())).append("</b> so'm\n");
                if (d.remainKlik() < 0)
                    days.append("🔻 🏪 ").append(esc(k.getName())).append(" • ")
                        .append(d.getDate().format(DF)).append(" — 📲 <b>")
                        .append(fmt(d.remainKlik())).append("</b> so'm\n");
            }
        }
        if (days.length() > 0) {
            issues++;
            sb.append("\n<b>Minus kunlar</b> (Кассалар холати'dagi minuslar shundan):\n")
              .append(days);
        }

        // 3) Bir xil telefon raqamli foydalanuvchilar (takror akkaunt — xato manbai)
        StringBuilder dups = new StringBuilder();
        java.util.Map<String, java.util.List<AppUser>> byPhone = new java.util.HashMap<>();
        for (AppUser x : userRepo.findAll()) {
            String np = uz.kassa.bot.TextUtil.normPhone(x.getPhone());
            if (np.isEmpty()) continue;
            byPhone.computeIfAbsent(np, z -> new ArrayList<>()).add(x);
        }
        for (var e2 : byPhone.entrySet()) {
            if (e2.getValue().size() < 2) continue;
            dups.append("📱 <code>").append(e2.getKey()).append("</code>: ")
                .append(esc(e2.getValue().stream()
                        .map(x -> x.getFullName() + (x.isActive() ? "" : " (nofaol)"))
                        .collect(java.util.stream.Collectors.joining(", "))))
                .append("\n");
        }
        if (dups.length() > 0) {
            issues++;
            sb.append("\n<b>Bir xil raqamli foydalanuvchilar</b> — takrorini o'chiring "
                    + "yoki raqamini to'g'rilang (aralashib xato beradi):\n").append(dups);
        }

        if (issues == 0) {
            sb.append("\n✅ Minus balans ham, minus kun ham topilmadi — hammasi joyida.");
        } else {
            sb.append("\n<b>Minus qayerdan chiqadi?</b>\n")
              .append("• MoySklad'da rasxod bor, lekin o'sha kunning kirimi boshqa otdelga "
                      + "yozilgan yoki umuman kiritilmagan;\n")
              .append("• boshlang'ich qoldiq kiritilmagan / sanasi noto'g'ri;\n")
              .append("• korrektirovka summasi yoki sanasi xato ketgan;\n")
              .append("• kunlik qoplash (pul qabul qilish) haqiqiy tushumdan ortiq qilingan.\n\n")
              .append("Avval MoySklad'dagi hujjatlarni tekshiring; haqiqatan xato bo'lsa — "
                      + "pastdagi 🛠 tugma orqali Корректировка bilan tuzating "
                      + "(<code>=summa</code> yozsangiz balans aynan shu qiymatga tenglashadi).");
        }

        rows.add(irow(btn("🔄 Qayta tekshirish", "a:diag")));
        rows.add(irow(sup.bk("a:p:set")));
        InlineKeyboardMarkup kb = inline(rows);
        if (msgId > 0) sender.edit(chatId, msgId, sb.toString(), kb);
        else sup.sendContent(s, chatId, sb.toString(), kb);
    }



    /* ==================================================================
     * 📥 ҚАЙТА ЮКЛАШ — BARCHA moliyaviy ma'lumotlar (operatsiyalar, kunlar,
     * hisobotlar, balanslar) O'CHIRILIB, MoySklad'dan ledger boshlanish
     * sanasidan bugungacha qaytadan tortiladi. Foydalanuvchi/kassa/Click
     * hisoblari/qarz daftariga tegilmaydi. Boshlang'ich qoldiqlar ham
     * o'chadi — keyin qo'lda qayta kiritish kerak.
     * ================================================================== */

    void reloadConfirm(Session s, long chatId, int msgId) {
        java.time.LocalDate ep = syncService.effectiveEpoch();
        if (ep.equals(java.time.LocalDate.MIN)) {
            String warn = "⚠️ Avval <b>📅 Ledger санаси</b>ni belgilang — usiz qayta yuklash "
                    + "MoySklad'ning BUTUN tarixini tortib yuborardi.";
            if (msgId > 0) sender.edit(chatId, msgId, warn);
            else sup.sendContent(s, chatId, warn, null);
            return;
        }
        long ops = opRepo.count();
        String text = "📥 <b>Қайта юклаш</b>\n\n"
                + "Bu amal:\n"
                + "• barcha operatsiyalarni (" + ops + " ta), kun yozuvlarini va "
                + "hisobotlarni <b>O'CHIRADI</b>;\n"
                + "• barcha balanslarni <b>0</b> ga tushiradi (boshlang'ich qoldiqlar va "
                + "korrektirovkalar ham o'chadi!);\n"
                + "• MoySklad'dan <b>" + ep.format(DF) + "</b> dan bugungacha hujjatlarni "
                + "qaytadan tortadi.\n\n"
                + "Foydalanuvchilar, kassalar, Click hisoblari, qarz daftari va sozlamalarga "
                + "tegilmaydi.\n\n⚠️ Bu amalni ORQAGA QAYTARIB BO'LMAYDI. Davom etasizmi?";
        InlineKeyboardMarkup kb = inline(List.of(
                irow(btn("✅ Ha, o'chirib qayta yukla", "a:rldc")),
                irow(btn("❌ Bekor", "cx"))));
        if (msgId > 0) sender.edit(chatId, msgId, text, kb);
        else sup.sendContent(s, chatId, text, kb);
    }



    void reloadCommit(AppUser u, long chatId, int msgId) {
        audit.log(u.getId(), "QAYTA_YUKLASH", "settings", null,
                u.getFullName() + " to'liq tozalash + MoySklad'dan qayta yuklashni boshladi");
        sender.edit(chatId, msgId, "⏳ Tozalanmoqda va MoySklad'dan qayta yuklanmoqda...\n"
                + "Bu bir necha daqiqa olishi mumkin — tugagach xabar keladi.");
        new Thread(() -> {
            try {
                int n = syncService.fullReload();
                if (n == -1) {
                    sender.send(chatId, "⚠️ Ledger sanasi belgilanmagan — hech narsa o'chirilmadi.");
                } else if (n == -2) {
                    sender.send(chatId, "⚠️ MoySklad tokeni ishlamayapti — hech narsa o'chirilmadi. "
                            + "Avval 🔑 MoySklad API bo'limini tekshiring.");
                } else {
                    sender.send(chatId, "✅ <b>Қайта юклаш tugadi.</b>\n\n"
                            + "MoySklad'dan <b>" + n + "</b> ta hujjat yuklandi ("
                            + syncService.effectiveEpoch().format(DF) + " dan bugungacha). "
                            + "Click hisoblari MoySklad'ning joriy qoldiqlariga tenglashtirildi.\n\n"
                            + balanceSummary()
                            + "\n❗️ Kassa/buxgalteriya NAQD qoldiqlari MoySklad'dan olinmaydi — "
                            + "haqiqiy naqd pulni <b>🛠 Корректировка</b> yoki "
                            + "<b>💼 Бошланғич қолдиқ</b> orqali kiriting.");
                }
            } catch (Exception ex) {
                sender.send(chatId, "❌ Qayta yuklashda xato: " + esc(String.valueOf(ex.getMessage())));
            }
        }, "full-reload").start();
    }



    /** Barcha egalar bo'yicha joriy balanslar — bir xabarlik qisqa xulosa. */
    String balanceSummary() {
        StringBuilder sb = new StringBuilder("💰 <b>Joriy balanslar:</b>\n");
        long bn = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD).getAmount();
        long bk = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.KLIK).getAmount();
        sb.append("🏦 Основной: 💵 ").append(fmt(bn));
        if (bk != 0) sb.append(" · 📲 ").append(fmt(bk));
        sb.append(" so'm\n");
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            long n = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.NAQD).getAmount();
            long kl = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.KLIK).getAmount();
            if (n == 0 && kl == 0) continue;
            sb.append("🏪 ").append(esc(k.getName())).append(": 💵 ").append(fmt(n));
            if (kl != 0) sb.append(" · 📲 ").append(fmt(kl));
            sb.append(" so'm\n");
        }
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
            long v = ledger.view(OwnerType.CLICK, c.getId(), MoneyType.KLIK).getAmount();
            if (v != 0) sb.append("📲 ").append(esc(c.getName())).append(": ")
                    .append(fmt(v)).append(" so'm\n");
        }
        return sb.toString();
    }

}
