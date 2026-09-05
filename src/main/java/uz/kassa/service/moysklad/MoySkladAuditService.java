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
import uz.kassa.service.moysklad.SyncSupport.Ctx;

/**
 * Auditlar: Click hisoblari tenglashtiruvi, naqd tekshiruvi (faqat xabar), kunlik hisobot uchun MoySklad savdosi.
 * (MoySkladSyncService dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MoySkladAuditService {

    private final MoySkladClient client;
    private final LedgerService ledger;
    private final KassaRepo kassaRepo;
    private final ClickAccountRepo clickRepo;
    private final OperationRepo opRepo;
    private final SettingsService settings;
    private final NotificationService notify;
    private final AppProps props;
    private final SyncSupport sup;
    private final MoySkladDocApplier applier;
    private final MoySkladSyncService core;


    /* ==================== CLICK BALANS AUDITI (to'liq tarix) ==================== */

    /**
     * Click balanslari MoySklad'ning JORIY qoldiqlariga (/report/money/byaccount)
     * muntazam tenglashtiriladi — har bog'langan Click hisobi to'liq. Hujjatma-hujjat
     * solishtiruv «farq qaysi hujjatdan» ma'lumoti uchun saqlangan. NAQD bu yerda
     * tenglashtirilMAYDI (2026-09-02, pastdagi izohga qarang). Jobs: soatda 1 yoki /auditclick.
     */
    public void auditClickAccounts() {
        synchronized (core) {
        String token = client.currentToken();
        if (token == null || token.isBlank()) return;
        core.sync();   // avval oxirgi hujjatlar olinadi — «yo'ldagi» hujjat tenglashtirishni adashtirmasin

        List<ClickAccount> accounts = clickRepo.findByActiveTrueOrderByIdAsc();
        Map<String, Long> accountToClick = new HashMap<>();
        for (ClickAccount c : accounts)
            if (c.getMoyskladAccountId() != null && !c.getMoyskladAccountId().isBlank())
                accountToClick.put(c.getMoyskladAccountId(), c.getId());
        if (accountToClick.isEmpty()) return;

        Map<Long, Long> trueBalance = new HashMap<>();
        for (Long id : accountToClick.values()) trueBalance.put(id, 0L);

        // «Aybdorni topish» uchun: har hisob bo'yicha MoySklad hujjatlari
        // (bot jurnalidagi msId -> ishorali summa va inson o'qiy oladigan ta'rif)
        record MsDocRef(long signedSum, String label) {}
        Map<Long, Map<String, MsDocRef>> msDocs = new HashMap<>();
        for (Long id : accountToClick.values()) msDocs.put(id, new HashMap<>());

        // Ledger boshlanish sanasidan OLDINGI hujjatlar SANALMAYDI — u davr
        // kalibratsiya (qo'lda kiritilgan qoldiq) ichida, hujjatma-hujjat emas.
        LocalDate ep = sup.epoch();
        try {
            for (MoySkladClient.MsExpense e : client.fetchAllPaymentsIn()) {
                Long clickId = accountToClick.get(e.accountId());
                if (clickId == null || !e.applicable() || !e.state().equalsIgnoreCase(applier.klikState())) continue;
                if (e.date().isBefore(ep)) continue;
                long sum = sup.somSum(e);
                if (sum > 0) {
                    trueBalance.merge(clickId, sum, Long::sum);
                    msDocs.get(clickId).put("pi:" + e.id(),
                            new MsDocRef(sum, "№" + e.docNo() + " · " + e.date().format(D_UZ)));
                }
            }
            for (MoySkladClient.MsExpense e : client.fetchAllPaymentsOut()) {
                Long clickId = accountToClick.get(e.accountId());
                if (clickId == null || !e.applicable()) continue;
                if (e.date().isBefore(ep)) continue;
                long sum = sup.somSum(e);
                if (sum > 0) {
                    trueBalance.merge(clickId, -sum, Long::sum);
                    msDocs.get(clickId).put("po:" + e.id(),
                            new MsDocRef(-sum, "№" + e.docNo() + " · " + e.date().format(D_UZ)));
                }
            }
        } catch (Exception ex) {
            log.warn("Click balans auditi uzildi: {}", ex.getMessage());
            return;
        }

        // NISHON — MoySklad'ning o'zi ko'rsatayotgan JORIY qoldiq (to'liq tarix,
        // epoch'dan oldingi davr bilan birga). Farq TO'LIQ yopiladi.
        Map<String, Long> msBal;
        try { msBal = client.fetchAccountBalances(); }
        catch (Exception ex) {
            log.warn("MoySklad pul hisoboti o'qilmadi: {}", ex.getMessage());
            return;
        }
        if (msBal.isEmpty()) {
            log.warn("MoySklad pul hisoboti bo'sh/ruxsat yo'q — tenglashtirish o'tkazildi");
            return;
        }

        for (ClickAccount c : accounts) {
            String accId = c.getMoyskladAccountId();
            if (accId == null || accId.isBlank() || !msBal.containsKey(accId)) continue;
            long target = msBal.get(accId);
            long bot = ledger.view(OwnerType.CLICK, c.getId(), MoneyType.KLIK).getAmount();
            long diff = target - bot;
            if (diff == 0) continue;

            // AYBDOR HUJJATLAR: MoySklad ro'yxati bilan bot jurnalini hujjatma-hujjat
            // solishtirib, farq aynan qaysi hujjat(lar)dan chiqqanini ko'rsatamiz.
            StringBuilder det = new StringBuilder();
            int shownDocs = 0;
            Map<String, MsDocRef> expect = msDocs.getOrDefault(c.getId(), Map.of());
            Map<String, Long> botOps = new HashMap<>();
            for (Operation op : opRepo.syncOpsOf(OwnerType.CLICK, c.getId(), MoneyType.KLIK)) {
                long signed = op.getToOwnerType() == OwnerType.CLICK ? op.getAmount() : -op.getAmount();
                botOps.merge(op.getMoyskladId(), signed, Long::sum);
            }
            for (var en : expect.entrySet()) {
                Long got = botOps.get(en.getKey());
                if (got != null && got == en.getValue().signedSum()) continue;
                if (shownDocs++ >= 8) { det.append("…\n"); break; }
                if (got == null)
                    det.append("• MoySklad'da bor, botda YO'Q: ")
                       .append(TextUtil.esc(en.getValue().label())).append(" · ")
                       .append(TextUtil.fmt(en.getValue().signedSum())).append(" so'm\n");
                else
                    det.append("• Summa farq: ").append(TextUtil.esc(en.getValue().label()))
                       .append(" · bot ").append(TextUtil.fmt(got)).append(" / MS ")
                       .append(TextUtil.fmt(en.getValue().signedSum())).append(" so'm\n");
            }
            if (shownDocs <= 8)
                for (var en : botOps.entrySet()) {
                    if (expect.containsKey(en.getKey())) continue;
                    if (shownDocs++ >= 8) { det.append("…\n"); break; }
                    det.append("• Botda bor, MoySklad ro'yxatida YO'Q: <code>")
                       .append(TextUtil.esc(en.getKey())).append("</code> · ")
                       .append(TextUtil.fmt(en.getValue())).append(" so'm\n");
                }

            ledger.postAdjustment(OpType.KORREKTIROVKA, OwnerType.CLICK, c.getId(), MoneyType.KLIK,
                    diff, "MoySklad joriy qoldig'iga tenglashtirish (doim bir xil siyosati)",
                    null, ledger.today());
            notify.toBuxgalteriya("🔄 <b>Click avto-tenglashtirish</b>: <b>" + TextUtil.esc(c.getName())
                    + "</b> — MoySklad joriy qoldig'i bilan farq topildi, tenglashtirildi:\n"
                    + TextUtil.fmt(bot) + " → <b>" + TextUtil.fmt(bot + diff) + "</b> so'm ("
                    + (diff > 0 ? "+" : "") + TextUtil.fmt(diff) + ")"
                    + (det.length() == 0 ? "" : "\n\n<b>Farq bergan hujjatlar:</b>\n" + det), null);
            log.info("Click audit tuzatish: {} {} -> {} (farq {})", c.getName(), bot, bot + diff, diff);
        }

        // NAQD tenglashtirilMAYDI (foydalanuvchi qarori, 2026-09-02). Avval jami naqd
        // (Основной + kassalar) MoySklad CASH bilan solishtirilib, farq Основнойga
        // KORREKTIROVKA sifatida yozilar edi. Bu (a) MoySklad pul hisoboti bilan
        // inkremental sinxron o'rtasidagi poyga tufayli (hujjat hisobotda bor, botga
        // hali tushmagan) har soat +X, keyingi soat -X korrektirovka aylanasini
        // berar, (b) «Nol boshlash»dan keyin butun tarixni Основнойga to'kib
        // yuborar edi. Farq endi faqat hujjat darajasida (sync/reconcile) yopiladi.
        }
    }


    /**
     * 🔍 Jami NAQD (Основной + kassalar) MoySklad CASH bilan solishtiriladi.
     * HECH NARSA YOZILMAYDI (foydalanuvchi qarori, 2026-09-02): farq topilsa
     * buxgalteriyaga SABAB (hujjatma-hujjat), SUMMA va TUZATISH YO'LI bilan xabar
     * boradi. Bir xil farq 24 soatda bir marta takrorlanadi; farq o'zgarsa darhol;
     * farq yopilsa bir marta «✅ yopildi» xabari. Jobs: soatda 1 yoki /auditclick.
     */
    public void auditNaqd() {
        synchronized (core) {
        String token = client.currentToken();
        if (token == null || token.isBlank()) return;
        long[] m = measureNaqd();
        if (m == null) return;
        long msCash = m[0], osn = m[1], kassalar = m[2], diff = m[3];

        String prev = settings.get(NAQD_AUDIT_KEY).orElse("");
        if (diff == 0) {
            if (!prev.isBlank()) {
                settings.set(NAQD_AUDIT_KEY, "");
                notify.toBuxgalteriya("✅ <b>Naqd tekshiruvi</b>: farq yopildi — MoySklad CASH va bot "
                        + "jami naqd teng: <b>" + TextUtil.fmt(msCash) + "</b> so'm", null);
            }
            return;
        }
        long nowMs = System.currentTimeMillis();
        String[] pp = prev.split("\\|");
        if (pp.length == 2) {
            try {
                if (Long.parseLong(pp[0]) == diff && nowMs - Long.parseLong(pp[1]) < 24 * 3600_000L) {
                    log.info("Naqd tekshiruvi: farq {} o'zgarmagan — xabar takrorlanmadi", diff);
                    return;
                }
            } catch (NumberFormatException ignore) { }
        }

        // QAYTA TEKSHIRUV (foydalanuvchi qarori, 02.09.2026): hujjat MoySklad'da qayta
        // rasmiylashtirilayotgan paytdagi (12:03 hodisasi: eski chiqim bekor, yangisi
        // hali yozilmagan) VAQTINCHALIK farq xabar bermasin. 3 daqiqadan keyin alohida
        // oqimda qayta o'lchanadi (qulfsiz — sinxron to'xtab qolmaydi); farq aynan shu
        // qiymatda QOLSAGINA buxgalteriyaga xabar ketadi.
        log.info("Naqd tekshiruvi: MS {} bot {} farq {} — 3 daqiqadan keyin qayta tekshiriladi",
                msCash, total(osn, kassalar), diff);
        final long first = diff;
        Thread t = new Thread(() -> {
            try { Thread.sleep(180_000L); } catch (InterruptedException e) { return; }
            confirmNaqd(first);
        }, "naqd-confirm");
        t.setDaemon(true);
        t.start();
        }
    }


    static long total(long osn, long kassalar) { return osn + kassalar; }


    /** MoySklad CASH, Основной, kassalar jami, farq — yoki null (hisobot o'qilmadi). */
    long[] measureNaqd() {
        Map<String, Long> msBal;
        try { msBal = client.fetchAccountBalances(); }
        catch (Exception ex) {
            log.warn("Naqd tekshiruvi: MoySklad pul hisoboti o'qilmadi: {}", ex.getMessage());
            return null;
        }
        Long msCash = msBal.get("CASH");
        if (msCash == null) {
            log.warn("Naqd tekshiruvi: MoySklad hisobotida CASH qatori yo'q/ruxsat yo'q — o'tkazildi");
            return null;
        }
        long osn = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD).getAmount();
        long kassalar = 0;
        for (Kassa k : kassaRepo.findAll())
            kassalar += ledger.view(OwnerType.KASSA, k.getId(), MoneyType.NAQD).getAmount();
        return new long[]{msCash, osn, kassalar, msCash - osn - kassalar};
    }


    /** 3 daqiqadan keyingi qayta o'lchov: farq o'zgarmagan bo'lsa — xabar; o'zgargan/yopilgan — jim. */
    void confirmNaqd(long first) {
        try {
            long[] m = measureNaqd();
            if (m == null) return;
            long msCash = m[0], osn = m[1], kassalar = m[2], diff = m[3];
            if (diff == 0) {
                log.info("Naqd tekshiruvi: farq {} vaqtinchalik edi — 3 daqiqada yopildi, xabar yo'q", first);
                return;
            }
            if (diff != first) {
                log.info("Naqd tekshiruvi: farq {} → {} o'zgardi (hujjat harakati) — xabar keyingi tekshiruvda",
                        first, diff);
                return;
            }
            String prev = settings.get(NAQD_AUDIT_KEY).orElse("");
            String[] pp = prev.split("\\|");
            if (pp.length == 2) {
                try {
                    if (Long.parseLong(pp[0]) == diff
                            && System.currentTimeMillis() - Long.parseLong(pp[1]) < 24 * 3600_000L) return;
                } catch (NumberFormatException ignore) { }
            }
            long total = osn + kassalar;
            StringBuilder sb = new StringBuilder();
            sb.append("🔍 <b>Naqd tekshiruvi — FARQ TOPILDI</b> <i>(3 daqiqada qayta tasdiqlandi)</i>\n")
              .append("MoySklad CASH: <b>").append(TextUtil.fmt(msCash)).append("</b> so'm\n")
              .append("Bot jami naqd: <b>").append(TextUtil.fmt(total)).append("</b> so'm (Основной ")
              .append(TextUtil.fmt(osn)).append(" · kassalar ").append(TextUtil.fmt(kassalar)).append(")\n")
              .append("Farq: <b>").append(diff > 0 ? "+" : "").append(TextUtil.fmt(diff)).append("</b> so'm — ")
              .append(diff > 0 ? "MoySklad'da KO'P (botga hujjat tushmagan yoki qoldiq kam)"
                               : "botda KO'P (ortiqcha yozuv yoki qoldiq ko'p)")
              .append("\n\n")
              .append(naqdCauses(diff))
              .append("\nℹ️ Bot hech narsani O'ZI YOZMAYDI. Xabar farq o'zgarganda darhol, "
                    + "aks holda 24 soatda bir keladi. Qo'lda tekshirish: /auditclick");
            settings.set(NAQD_AUDIT_KEY, diff + "|" + System.currentTimeMillis());
            notify.toBuxgalteriya(sb.toString(), null);
            log.info("Naqd tekshiruvi: MS {} bot {} farq {} — xabar yuborildi", msCash, total, diff);
        } catch (Exception e) {
            log.warn("Naqd qayta tekshiruv xatosi: {}", e.getMessage());
        }
    }


    /**
     * Farqning SABABI va TUZATISH YO'LI: ledger boshlanish sanasidan beri MoySklad
     * naqd hujjatlari (cashin/cashout/sotuv/vozvrat/Выплата денег) bot jurnali bilan
     * hujjatma-hujjat solishtiriladi; qolgan (hujjatsiz) farq alohida ko'rsatiladi.
     */
    String naqdCauses(long diff) {
        record Ref(long signed, String label) {}
        LocalDate ep = sup.epoch();
        LocalDate to = LocalDate.now(props.zoneId());
        StringBuilder sb = new StringBuilder("<b>Sabablar</b>");
        if (ep.equals(LocalDate.MIN)) {
            sb.append(":\n• Ledger boshlanish sanasi belgilanmagan — hujjatma-hujjat solishtiruv "
                    + "imkonsiz.\n\n<b>Tuzatish yo'li</b>:\n• Настройка bo'limida ledger boshlanish "
                    + "sanasini belgilang, keyin /auditclick.\n");
            return sb.toString();
        }
        sb.append(" (hujjatlar ").append(ep.format(D_UZ)).append(" dan beri):\n");

        // MoySklad tomoni: msId -> ishorali summa
        Map<String, Ref> expect = new java.util.LinkedHashMap<>();
        long t0 = System.currentTimeMillis();
        String fetchErr = "";
        try {
            for (MoySkladClient.MsExpense e : client.fetchDocsByMoment("cashin", ep, to)) {
                long sum = sup.somSum(e);
                if (sum > 0) expect.put("ci:" + e.id(), new Ref(sum,
                        "Приходный №" + e.docNo() + " · " + e.date().format(D_UZ)
                        + (e.agent().isEmpty() ? "" : " · " + e.agent())));
            }
            for (MoySkladClient.MsExpense e : client.fetchDocsByMoment("cashout", ep, to)) {
                long sum = sup.somSum(e);
                if (sum > 0) expect.put("co:" + e.id(), new Ref(-sum,
                        "Расходный №" + e.docNo() + " · " + e.date().format(D_UZ)
                        + (e.agent().isEmpty() ? "" : " · " + e.agent())));
            }
            for (MoySkladClient.MsDoc d : client.fetchSalesByMoment("retaildemand", ep, to)) {
                long sum = d.cashTiyin() / 100;
                if (d.applicable() && sum > 0)
                    expect.put("rd:" + d.id() + ":n", new Ref(sum, "Sotuv (naqd) · " + d.date().format(D_UZ)));
            }
            for (MoySkladClient.MsDoc d : client.fetchSalesByMoment("retailsalesreturn", ep, to)) {
                long sum = d.cashTiyin() / 100;
                if (d.applicable() && sum > 0)
                    expect.put("rr:" + d.id() + ":n", new Ref(-sum, "Vozvrat (naqd) · " + d.date().format(D_UZ)));
            }
            for (MoySkladClient.MsExpense e : client.fetchDrawerCashoutsByMoment(ep, to)) {
                long sum = sup.somSum(e);
                if (e.applicable() && sum > 0)
                    expect.put("dc:" + e.id(), new Ref(-sum,
                            "Выплата денег " + e.docNo() + " · " + e.date().format(D_UZ)));
            }
        } catch (Exception ex) {
            fetchErr = ex.getMessage() == null ? "xato" : ex.getMessage();
            log.warn("Naqd tekshiruvi: hujjatlar o'qilmadi: {}", fetchErr);
        }
        boolean perm403 = client.last403At() >= t0;

        // Bot tomoni: sinxron (moysklad_id bor) NAQD yozuvlari — msId -> ishorali summa
        Map<String, Long> bot = new HashMap<>();
        for (String pref : List.of("ci:", "co:", "rd:", "rr:", "dc:"))
            for (Operation op : opRepo.findByOpDateBetweenAndMoyskladIdStartingWith(ep, to, pref)) {
                if (op.getMoneyType() != MoneyType.NAQD || op.getStatus() != OpStatus.TASDIQLANGAN) continue;
                long signed = op.getToOwnerType() != null ? op.getAmount() : -op.getAmount();
                bot.merge(op.getMoyskladId(), signed, Long::sum);
            }

        // Toifalar
        List<String> msOnly = new java.util.ArrayList<>(), botOnly = new java.util.ArrayList<>(),
                     sumDiff = new java.util.ArrayList<>();
        long sumMsOnly = 0, sumBotOnly = 0, sumSumDiff = 0;
        for (var en : expect.entrySet()) {
            Long got = bot.get(en.getKey());
            long ms = en.getValue().signed();
            if (got == null) {
                sumMsOnly += ms;
                msOnly.add("   – " + TextUtil.esc(en.getValue().label()) + " · " + signedFmt(ms));
            } else if (got != ms) {
                sumSumDiff += ms - got;
                sumDiff.add("   – " + TextUtil.esc(en.getValue().label()) + " · MS " + signedFmt(ms)
                        + " / bot " + signedFmt(got));
            }
        }
        if (!perm403 && fetchErr.isEmpty())
            for (var en : bot.entrySet()) {
                if (expect.containsKey(en.getKey())) continue;
                sumBotOnly += en.getValue();
                botOnly.add("   – <code>" + TextUtil.esc(en.getKey()) + "</code> · " + signedFmt(en.getValue()));
            }

        // Bot qo'lda yozuvlari (MoySklad'da yo'q): korrektirovka + boshlang'ich qoldiq
        long manual = 0; int manualN = 0;
        for (OpType t : List.of(OpType.KORREKTIROVKA, OpType.BOSHLANGICH))
            for (Operation op : opRepo.findByStatusAndType(OpStatus.TASDIQLANGAN, t)) {
                if (op.getMoneyType() != MoneyType.NAQD) continue;
                manual += op.getToOwnerType() != null ? op.getAmount() : -op.getAmount();
                manualN++;
            }

        long explained = sumMsOnly - sumBotOnly + sumSumDiff;
        long rest = diff - explained;

        int reconcileDays = props.getMoysklad().getReconcileDays();
        if (!fetchErr.isEmpty())
            sb.append("• ⚠️ MoySklad hujjatlari to'liq o'qilmadi: ").append(TextUtil.esc(fetchErr)).append("\n");
        if (perm403)
            sb.append("• ⚠️ Token HUQUQI yetmadi (403) — retail (sotuv/vozvrat/Выплата) hujjatlar "
                    + "solishtirilmadi, ular botga umuman tushmayotgan bo'lishi mumkin.\n");
        appendCat(sb, "📥 MoySklad'da bor, botda YO'Q", msOnly, sumMsOnly);
        appendCat(sb, "📤 Botda bor, MoySklad'da YO'Q (o'chirilgan/bekor qilingan?)", botOnly, sumBotOnly);
        appendCat(sb, "✏️ Summasi farq qiladigan hujjatlar", sumDiff, sumSumDiff);
        if (manualN > 0)
            sb.append("• 🧾 Bot qo'lda yozuvlari (korrektirovka/boshlang'ich, MoySklad'da yo'q): ")
              .append(manualN).append(" ta, jami ").append(signedFmt(manual)).append("\n");
        if (rest != 0)
            sb.append("• ❓ Hujjatlar bilan tushuntirilmagan qoldiq: <b>").append(signedFmt(rest))
              .append("</b> — bu ").append(ep.format(D_UZ))
              .append(" gacha bo'lgan davr (MoySklad tarixi) bilan botdagi boshlang'ich qoldiq/"
                    + "korrektirovkalar o'rtasidagi farq, yoki eski hujjat keyin tahrirlangan.\n");
        if (msOnly.isEmpty() && botOnly.isEmpty() && sumDiff.isEmpty() && rest == 0)
            sb.append("• Hujjatlar teng — farq faqat qoldiq darajasida.\n");

        sb.append("\n<b>Tuzatish yo'li</b>:\n");
        if (!msOnly.isEmpty() || !sumDiff.isEmpty())
            sb.append("• Tushmagan/farqli hujjatlar: 10 daqiqalik avto-tekshiruv (reconcile) oxirgi ")
              .append(reconcileDays).append(" kunni o'zi yopadi — kuting va xabarni qayta tekshiring. ")
              .append("Undan eski hujjat bo'lsa — Настройка → «Qayta yuklash» (to'liq).\n");
        if (!botOnly.isEmpty())
            sb.append("• Ortiqcha yozuvlar: hujjat MoySklad'da o'chirilgan/bekor qilingan bo'lsa reconcile ")
              .append("STORNO qiladi (oxirgi ").append(reconcileDays)
              .append(" kun); eskiroq bo'lsa — Настройка → «Qayta yuklash».\n");
        if (perm403)
            sb.append("• MoySklad kabinetida token egasiga «Розничная торговля» ko'rish huquqini bering, "
                    + "so'ng /auditclick.\n");
        if (rest != 0)
            sb.append("• Tushuntirilmagan ").append(signedFmt(rest))
              .append(": haqiqiy pulni sanab, Настройка → 🛠 Корректировка orqali kerakli otdelga "
                    + "(odatda Основной) shu summani ± yozing yoki boshlang'ich qoldiqni to'g'rilang.\n");
        return sb.toString();
    }


    void appendCat(StringBuilder sb, String title, List<String> lines, long sum) {
        if (lines.isEmpty()) return;
        sb.append("• ").append(title).append(": ").append(lines.size()).append(" ta, jami ")
          .append(signedFmt(sum)).append("\n");
        int shown = 0;
        for (String l : lines) {
            if (shown++ >= 5) { sb.append("   … yana ").append(lines.size() - 5).append(" ta\n"); break; }
            sb.append(l).append("\n");
        }
    }


    static String signedFmt(long v) {
        return (v > 0 ? "+" : "") + TextUtil.fmt(v) + " so'm";
    }


    /* ==================== KUNLIK HISOBOT: MoySklad savdosi ==================== */

    /**
     * Kunlik kassa solishtirish uchun MoySklad'dan BEVOSITA: kassa (otdel) kesimida
     * kunlik savdo, so'mda — cashin (naqd kirim) + paymentin (Klik/terminal statusli)
     * + retail sotuv − retail vozvrat. Bot kun yozuvi bilan solishtiriladi (Farq).
     * Kalit — kassa id; hech qaysi otdelga bog'lanmagan hujjatlar — kalit -1.
     */
    public Map<Long, Long> moyskladDaySales(LocalDate d) {
        Map<Long, Long> out = new HashMap<>();
        Ctx ctx = sup.buildCtx();
        for (MoySkladClient.MsExpense e : client.fetchDocsByMoment("cashin", d, d)) {
            long sum = sup.somSum(e);
            if (sum <= 0) continue;
            Long k = ctx.groupToKassa().get(e.groupId());
            out.merge(k == null ? -1L : k, sum, Long::sum);
        }
        for (MoySkladClient.MsExpense e : client.fetchDocsByMoment("paymentin", d, d)) {
            if (applier.paymentMt(e.state()) == null) continue;
            long sum = sup.somSum(e);
            if (sum <= 0) continue;
            Long k = ctx.groupToKassa().get(e.groupId());
            out.merge(k == null ? -1L : k, sum, Long::sum);
        }
        try {
            for (MoySkladClient.MsDoc sd : client.fetchSalesByMoment("retaildemand", d, d)) {
                if (!sd.applicable()) continue;
                Long k = ctx.storeToKassa().get(sd.storeId());
                out.merge(k == null ? -1L : k, (sd.cashTiyin() + sd.noCashTiyin()) / 100, Long::sum);
            }
            for (MoySkladClient.MsDoc sd : client.fetchSalesByMoment("retailsalesreturn", d, d)) {
                if (!sd.applicable()) continue;
                Long k = ctx.storeToKassa().get(sd.storeId());
                out.merge(k == null ? -1L : k, -(sd.cashTiyin() + sd.noCashTiyin()) / 100, Long::sum);
            }
        } catch (Exception ex) {
            log.debug("Kunlik retail o'qilmadi: {}", ex.getMessage());
        }
        return out;
    }

}
