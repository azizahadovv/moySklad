package uz.kassa.service.jarima;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import uz.kassa.bot.Sender;
import uz.kassa.domain.AgentCheck;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.ClickAccount;
import uz.kassa.domain.Jarima;
import uz.kassa.domain.Jarima.Holat;
import uz.kassa.domain.Jarima.Tur;
import uz.kassa.domain.Shipment;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.ClickAccountRepo;
import uz.kassa.repo.GroupMemberRepo;
import uz.kassa.repo.JarimaRepo;
import uz.kassa.service.AuditService;
import uz.kassa.service.BusinessException;
import uz.kassa.service.NotifySwitches;
import uz.kassa.service.control.ControlConfig;
import uz.kassa.service.control.ControlNotifier;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.esc;
import static uz.kassa.bot.TextUtil.fmt;

/**
 * ⚖️ Жарималар (docs/JARIMA.md): xodim holatlarini yozadi, ogohlantirish/jarima hisoblaydi,
 * darhol xabar (xodim + admin/rahbar; KARTA — Click hisobotining o'zida qator) va kun oxirida jamlama
 * (xodim o'ziga, admin/rahbarlarga, KARTA turi — Click guruhiga) yuboradi.
 *
 * Qoidalar:
 *  • Xodimning shu turdagi birinchi {@code jarima.ogoh_soni} holati — OGOH (summa 0), keyingilari — asos × foiz.
 *  • Asos: KARTA (qoldiq yuborilmagan/eskirgan) — kartaning oxirgi ma'lum qoldig'i (so'm; yo'q bo'lsa bazaviy),
 *    KARTA (farq tuzatilmadi) — farq summasi, KONTRAGENT — bazaviy summa, OTGRUZKA — otgruzka summasi.
 *  • Bir manba bir epizodda bir marta (karta: oxirgi qoldiq yuborilgan vaqtdan keyin; xato: shu topilish epizodida).
 *  • 🔕 Хабарномалар kaliti o'chiq bo'lsa faqat xabar ketmaydi — yozuv baribir yoziladi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JarimaService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final Pattern RESP_ID = Pattern.compile("id=(\\d+)");
    public static final String RULE = "━━━━━━━━━━━━━━━━━━━━";

    private final JarimaRepo repo;
    private final JarimaConfig cfg;
    private final ControlNotifier notifier;
    private final AppUserRepo userRepo;
    private final GroupMemberRepo memberRepo;
    private final ClickAccountRepo clickRepo;
    private final Sender sender;
    private final AuditService audit;
    private final NotifySwitches sw;

    /* ==================== NOMLAR ==================== */

    /** Kirill (avtomatik hisobot, Mini App). */
    public static String turTitle(Tur t) {
        return switch (t) { case KARTA -> "💳 Карта қолдиғи"; case KONTRAGENT -> "🏢 Контрагент хатоси"; case OTGRUZKA -> "📦 Отгрузка камчилиги"; };
    }
    /** Lotin (bot dialoglari). */
    public static String turTitleLat(Tur t) {
        return switch (t) { case KARTA -> "💳 Karta qoldig'i"; case KONTRAGENT -> "🏢 Kontragent xatosi"; case OTGRUZKA -> "📦 Otgruzka kamchiligi"; };
    }
    public static String holatTitle(Holat h) {
        return switch (h) { case OGOH -> "⚠️ Огоҳлантириш"; case OCHIQ -> "🔴 Очиқ"; case YOPIQ -> "✅ Ёпилган"; case BEKOR -> "❌ Бекор"; };
    }
    public static String holatTitleLat(Holat h) {
        return switch (h) { case OGOH -> "⚠️ Ogohlantirish"; case OCHIQ -> "🔴 Ochiq"; case YOPIQ -> "✅ Yopilgan"; case BEKOR -> "❌ Bekor"; };
    }

    /* ==================== MANBALAR (hook'lar) ==================== */

    /**
     * 📲 Click hisobotida karta qoldig'i eskirgan/kiritilmagan (Jobs.cardBlock, faqat jadval bo'yicha yuborishda).
     * Mas'ul bo'lmasa — yozilmaydi (kimga yozish noma'lum). Bir epizodda bir marta: epizod {@code since} dan boshlanadi —
     * bugungi hisobot oynasi boshi yoki undan keyingi oxirgi yuborish (Jobs.cardStaleSince); har kun alohida sanaladi.
     * @param ageMin bugungi oyna ichida necha daqiqa yangilanmagan (Jobs.cardAgeMin)
     * @return yozilgan holat (hisobot qatoriga qo'shish uchun) yoki bo'sh.
     */
    public Optional<Jarima> kartaStale(ClickAccount c, long ageMin, Instant since) {
        if (!cfg.enabled()) return Optional.empty();
        String resp = c.getCardResponsible();
        if (resp == null || resp.isBlank()) return Optional.empty();
        String manba = "click:" + c.getId();
        LocalDate today = LocalDate.now(cfg.zone());
        // Shu karta uchun bugun FARQ jarimasi yozilgan bo'lsa — qoldiq eskirgani uchun ikkinchi jarima yozilmaydi
        // (bitta e'tiborsizlik, bitta jarima; farq jarimasi aniqroq — asosi aynan yo'qolgan summa).
        if (repo.existsByTurAndManbaAndSana(Tur.KARTA, "clickfarq:" + c.getId(), today)) return Optional.empty();
        boolean dup = c.getCardBalanceAt() == null || since == null
                ? repo.existsByTurAndManbaAndSana(Tur.KARTA, manba, today)
                : repo.existsByTurAndManbaAndCreatedAtAfter(Tur.KARTA, manba, since);
        if (dup) return Optional.empty();
        AppUser u = resolveResponsible(resp);
        String name = u != null ? u.getFullName() : plainResponsible(resp);
        long asos = c.getCardBalance() == null || c.getCardBalance() <= 0 ? cfg.bazaviy() : c.getCardBalance() / 100;
        String sabab;
        if (c.getCardBalanceAt() == null) {
            sabab = "Карта «" + c.getName() + "» қолдиғи умуман юборилмаган — Click ҳисоботида «❗️ киритилмаган». "
                    + "Асос: базавий сумма " + fmt(asos) + " сўм.";
        } else {
            sabab = "Карта «" + c.getName() + "» қолдиғи бугунги ҳисобот вақти оралиғида " + (ageMin / 60) + " соатдан бери янгиланмаган (охирги: "
                    + LocalDateTime.ofInstant(c.getCardBalanceAt(), cfg.zone()).format(SHORT)
                    + (c.getCardBalanceBy() == null ? "" : ", " + c.getCardBalanceBy().replace("(tasdiqlangan)", "").trim())
                    + ") — Click ҳисоботида «⏰ маълумот янгиланмаган». Асос: охирги қолдиқ " + fmt(asos) + " сўм.";
        }
        return Optional.of(register(Tur.KARTA, u, name, c.getKassaId() != null ? c.getKassaId() : (u == null ? null : u.getKassaId()),
                manba, c.getName(), asos, sabab));
    }

    /**
     * ⚠️ Karta farqi (ClickFarqService): MoySklad qoldig'i kartadan KO'P — kartadan xarajat qilinib xabar berilmagan —
     * va {@code min} daqiqada tuzatilmagan. Asos — FARQ summasining o'zi (karta qoldig'i emas: jarima yo'qolgan
     * pul hajmiga bog'liq, 2026-09-23 user qarori). Bir epizodda bir marta (ClickFarqService farq_jarima_at).
     * Mas'ul bo'lmasa yoki farq so'mga yaxlitlanganda 0 chiqsa — yozilmaydi.
     * @param farqTiyin MoySklad − karta, tiyin (musbat)
     */
    public Optional<Jarima> kartaFarq(ClickAccount c, long farqTiyin, int min) {
        if (!cfg.enabled()) return Optional.empty();
        String resp = c.getCardResponsible();
        if (resp == null || resp.isBlank()) return Optional.empty();
        long asos = farqTiyin / 100;   // SO'MDA — jarima asosi aynan farq
        if (asos <= 0) return Optional.empty();
        AppUser u = resolveResponsible(resp);
        String name = u != null ? u.getFullName() : plainResponsible(resp);
        String sabab = "Карта «" + c.getName() + "» қолдиғи MoySklad қолдиғидан " + uz.kassa.bot.TextUtil.fmtTiyin(farqTiyin)
                + " сўм КАМ — картадан харажат қилиниб хабар берилмаган (расход киритилмаган ёки қолдиқ қайта юборилмаган); "
                + min + " дақиқада тузатилмади. Асос: фарқ суммаси " + fmt(asos) + " сўм.";
        return Optional.of(register(Tur.KARTA, u, name, c.getKassaId() != null ? c.getKassaId() : (u == null ? null : u.getKassaId()),
                "clickfarq:" + c.getId(), c.getName() + " · фарқ +" + uz.kassa.bot.TextUtil.fmtTiyin(farqTiyin), asos, sabab));
    }

    /** Karta mas'uli → bot xodimi (ClickFarqService xabari uchun). */
    public AppUser responsibleUser(ClickAccount c) {
        String resp = c.getCardResponsible();
        return resp == null || resp.isBlank() ? null : resolveResponsible(resp);
    }

    /**
     * 🏢 Kontragent xatosi (AgentCheckService): {@code tuzatilmadi=false} — topilganda, {@code true} — admin eskalatsiyasida.
     * Qaysi payt hisoblanishi sozlamada (jarima.xato_payt).
     */
    public void kontragent(AgentCheck ac, boolean tuzatilmadi) {
        if (!cfg.enabled() || tuzatilmadi != cfg.paytTuzatilmadi()) return;
        String manba = "agent:" + ac.getAgentMsId();
        Instant since = ac.getNotifiedAt() == null ? Instant.now().minusSeconds(86400) : ac.getNotifiedAt().minusSeconds(1);
        if (repo.existsByTurAndManbaAndCreatedAtAfter(Tur.KONTRAGENT, manba, since)) return;
        AppUser u = ac.getCreatorUserId() == null ? null : userRepo.findById(ac.getCreatorUserId()).orElse(null);
        String name = u != null ? u.getFullName() : (ac.getCreatedUid() == null || ac.getCreatedUid().isBlank() ? "номаълум" : ac.getCreatedUid());
        StringBuilder sb = new StringBuilder("Контрагент «").append(ac.getAgentName()).append("» хато киритилган");
        if (tuzatilmadi) sb.append(" ва белгиланган муддатда тузатилмади (ходим ва раҳбар хабардор қилинган)");
        sb.append(". Хатолар:\n");
        sb.append(bullets(ac.violationList()));
        sb.append("\nАсос: базавий сумма ").append(fmt(cfg.bazaviy())).append(" сўм.");
        register(Tur.KONTRAGENT, u, name, ac.getKassaId() != null ? ac.getKassaId() : (u == null ? null : u.getKassaId()),
                manba, ac.getAgentName(), cfg.bazaviy(), sb.toString());
    }

    /**
     * 📦 Otgruzka kamchiligi (ShipmentControlService). Asos — otgruzka summasi.
     * Faqat otgruzka summasi ham, qarz qoldig'i (balans) ham 0 dan katta bo'lganda yoziladi —
     * 0 so'mlik yoki to'liq to'langan otgruzkada mas'ul/to'lov muddati yo'qligi jarima emas (2026-09-22).
     */
    public void otgruzka(Shipment s, boolean tuzatilmadi) {
        if (!cfg.enabled() || tuzatilmadi != cfg.paytTuzatilmadi()) return;
        if (s.getSum() <= 0 || s.remain() <= 0) return;
        String manba = "demand:" + s.getMsId();
        Instant since = s.getIssuesSince() == null ? Instant.now().minusSeconds(86400) : s.getIssuesSince().minusSeconds(1);
        if (repo.existsByTurAndManbaAndCreatedAtAfter(Tur.OTGRUZKA, manba, since)) return;
        AppUser u = s.getOwnerUserId() == null ? null : userRepo.findById(s.getOwnerUserId()).orElse(null);
        String name = u != null ? u.getFullName() : (s.getOwnerName() == null || s.getOwnerName().isBlank() ? "номаълум" : s.getOwnerName());
        StringBuilder sb = new StringBuilder("Отгрузка №").append(s.getDocNo()).append(" (").append(s.getAgentName()).append(")");
        if (s.getMoment() != null) sb.append(", ").append(s.getMoment().format(DTF));
        sb.append(" — камчиликлар:\n");
        sb.append(bullets(s.issueList()));
        if (tuzatilmadi) sb.append("\nБелгиланган муддатда тузатилмади (ходим ва раҳбар хабардор қилинган).");
        sb.append("\nАсос: отгрузка суммаси ").append(fmt(s.getSum())).append(" сўм.");
        register(Tur.OTGRUZKA, u, name, s.getKassaId() != null ? s.getKassaId() : (u == null ? null : u.getKassaId()),
                manba, "№" + s.getDocNo() + " · " + s.getAgentName(), s.getSum(), sb.toString());
    }

    /** Har bir kamchilik alohida qatorda: «• KOD — sarlavha». */
    private static String bullets(List<String> codes) {
        if (codes.isEmpty()) return "• —";
        return String.join("\n", codes.stream().map(x -> "• " + x + " — " + ControlConfig.ruleTitle(x)).toList());
    }

    /**
     * Sabab matni ko'rsatish uchun. Eski yozuvlarda kamchiliklar «; » bilan bir qatorga qo'shilgan —
     * ularni ham alohida qatorlarga ajratadi (yangi yozuvlar allaqachon qatorma-qator saqlanadi).
     */
    public static String sababLines(String sabab) {
        if (sabab == null) return "";
        if (sabab.contains("\n• ")) return sabab;
        java.util.regex.Matcher m = LEGACY_LIST.matcher(sabab);
        if (!m.find()) return sabab;
        String items = String.join("\n", java.util.Arrays.stream(m.group(2).split(";\\s*")).map(x -> "• " + x.trim()).toList());
        String tail = m.group(3) == null ? "" : m.group(3).substring(2).replace(". ", "\n");
        return sabab.substring(0, m.start()) + m.group(1) + ":\n" + items + (tail.isEmpty() ? "" : "\n" + tail);
    }
    private static final java.util.regex.Pattern LEGACY_LIST =
            java.util.regex.Pattern.compile("(камчиликлар|Хатолар): (.+?)(\\. (?:Белгиланган|Асос).*)?$", java.util.regex.Pattern.DOTALL);

    /* ==================== YOZISH ==================== */

    private Jarima register(Tur tur, AppUser u, String xodim, Long kassaId, String manba, String manbaNomi, long asos, String sabab) {
        String kalit = u != null ? "u:" + u.getId() : "n:" + xodim;
        int tartib = (int) repo.countByKalitAndTurAndHolatNot(kalit, tur, Holat.BEKOR) + 1;   // bekor qilinganlar sanalmaydi
        boolean ogoh = tartib <= cfg.ogohSoni();
        double foiz = cfg.foiz(tur);
        long summa = ogoh ? 0 : Math.round(asos * foiz / 100.0);
        Jarima j = Jarima.builder()
                .tur(tur).holat(ogoh ? Holat.OGOH : Holat.OCHIQ)
                .userId(u == null ? null : u.getId()).kalit(kalit).xodim(cut(xodim, 200)).kassaId(kassaId)
                .manba(cut(manba, 80)).manbaNomi(cut(manbaNomi, 400))
                .asos(asos).foiz(foiz).summa(summa).sabab(sabab).tartib(tartib)
                .sana(LocalDate.now(cfg.zone())).createdAt(Instant.now())
                .build();
        j = repo.save(j);
        audit.log(u == null ? null : u.getId(), ogoh ? "JARIMA_OGOH" : "JARIMA_YOZILDI", "jarima", j.getId(),
                tur + " " + manbaNomi + " " + summa);
        log.info("Jarima #{} {} {} {} → {} so'm ({}-holat)", j.getId(), tur, xodim, manbaNomi, summa, tartib);
        notifyNow(j, u);
        return j;
    }

    /** Darhol xabar: xodimga (JR_XODIM) va admin + otdel rahbariga (JR_ADMIN). */
    private void notifyNow(Jarima j, AppUser u) {
        String text = card(j, true);
        boolean sent = false;
        if (u != null && u.getTelegramId() != null && sw.allow(NotifySwitches.JR_XODIM, u)) {
            notifier.sendOne(NotifySwitches.JR_XODIM, u, text, myKb());
            sent = true;
        }
        List<AppUser> admins = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        if (u != null) seen.add(u.getId());
        for (AppUser a : notifier.heads(j.getKassaId())) if (seen.add(a.getId())) admins.add(a);
        for (AppUser a : notifier.superadmins()) if (seen.add(a.getId())) admins.add(a);
        String head = (u == null || u.getTelegramId() == null)
                ? "⚠️ <i>Ходим ботга боғланмаган — хабар унга бормади.</i>\n\n" : "";
        notifier.send(NotifySwitches.JR_ADMIN, admins, head + text, null);
        if (sent) { j.setXabarAt(Instant.now()); repo.save(j); }
    }

    /** Xabar/karta matni (kirill). {@code intro} — sarlavha bilan (darhol xabar), aks holda faqat tana. */
    public String card(Jarima j, boolean intro) {
        StringBuilder sb = new StringBuilder();
        if (intro) sb.append(j.getHolat() == Holat.OGOH ? "⚠️ <b>ОГОҲЛАНТИРИШ</b>\n\n" : "⚖️ <b>ЖАРИМА ЁЗИЛДИ</b>\n\n");
        sb.append("👤 ").append(xodimMention(j));
        if (j.getKassaId() != null) sb.append(" · ").append(esc(notifier.kassaName(j.getKassaId())));
        sb.append("\n📌 Тур: ").append(turTitle(j.getTur())).append("\n");
        sb.append("🕒 ").append(LocalDateTime.ofInstant(j.getCreatedAt(), cfg.zone()).format(DTF)).append("\n");
        sb.append("📝 Сабаб: ").append(esc(sababLines(j.getSabab()))).append("\n");
        if (j.getHolat() == Holat.OGOH) {
            sb.append("💰 Жарима: <b>йўқ</b> — бу ").append(j.getTartib()).append("-ҳолат, огоҳлантириш. ")
              .append("Кейинги ҳолатдан асоснинг <b>").append(JarimaConfig.foizText(j.getFoiz())).append("%</b>и жарима ҳисобланади.\n");
        } else {
            sb.append("💰 Жарима: <b>").append(fmt(j.getSumma())).append("</b> сўм (")
              .append(JarimaConfig.foizText(j.getFoiz())).append("% × ").append(fmt(j.getAsos())).append(")\n");
            sb.append("🔢 Бу ").append(j.getTartib()).append("-ҳолат");
            if (cfg.ogohSoni() > 0) sb.append(" (биринчи ").append(cfg.ogohSoni() == 1 ? "ҳолат" : cfg.ogohSoni() + " та ҳолат").append(" огоҳлантириш эди)");
            sb.append("\n");
            if (j.getHolat() != Holat.OCHIQ) {
                sb.append("📄 Ҳолат: ").append(holatTitle(j.getHolat()));
                if (j.getYopilganAt() != null) sb.append(" · ").append(LocalDateTime.ofInstant(j.getYopilganAt(), cfg.zone()).format(DTF));
                if (j.getYopganUserId() != null) sb.append(" · ").append(esc(notifier.userName(j.getYopganUserId())));
                if (j.getIzoh() != null && !j.getIzoh().isBlank()) sb.append(" — ").append(esc(j.getIzoh()));
                sb.append("\n");
            }
        }
        if (intro) sb.append(RULE).append("\n<i>Кун охирида кунлик жамлама юборилади. Савол бўлса бўлим раҳбарига мурожаат қилинг.</i>");
        return sb.toString();
    }

    public static InlineKeyboardMarkup myKb() { return inline(List.of(irow(btn("📋 Жарималарим", "jr:my:30")))); }

    /* ==================== KUNLIK JAMLAMA ==================== */

    /** Har 5 daqiqada chaqiriladi; kun_vaqt dan keyin bir marta (guard jarima.kun_sent). */
    public void dailyTick() {
        if (!cfg.enabled()) return;
        LocalDate today = LocalDate.now(cfg.zone());
        if (LocalTime.now(cfg.zone()).isBefore(cfg.kunVaqt())) return;
        if (today.toString().equals(cfg.get(JarimaConfig.KUN_SENT).orElse(""))) return;
        cfg.set(JarimaConfig.KUN_SENT, today.toString());
        List<Jarima> all = todayActive(today);
        if (all.isEmpty()) return;
        try { sendDaily(all, today); }
        catch (Exception e) { log.warn("Jarima kunlik jamlama: {}", e.getMessage()); }
        Instant now = Instant.now();
        for (Jarima j : all) { j.setKunlikAt(now); }
        repo.saveAll(all);
    }

    /** Test/qo'lda: bugungi jamlamani hozir yuborish (guard'siz). @return yozuvlar soni. */
    public int sendDailyNow() {
        LocalDate today = LocalDate.now(cfg.zone());
        List<Jarima> all = todayActive(today);
        if (!all.isEmpty()) sendDaily(all, today);
        return all.size();
    }

    /** Bugungi yozuvlar, BEKOR qilinganlarsiz (2026-09-18): bekor = xato yozilgan, jamlamada ko'rinmasin. */
    private List<Jarima> todayActive(LocalDate today) {
        List<Jarima> out = new ArrayList<>();
        for (Jarima j : repo.findBySanaOrderByIdAsc(today)) if (j.getHolat() != Holat.BEKOR) out.add(j);
        return out;
    }

    private void sendDaily(List<Jarima> all, LocalDate today) {
        // xodim kesimida
        Map<String, List<Jarima>> byKalit = new LinkedHashMap<>();
        for (Jarima j : all) byKalit.computeIfAbsent(j.getKalit(), k -> new ArrayList<>()).add(j);
        for (var e : byKalit.entrySet()) {
            Jarima first = e.getValue().get(0);
            if (first.getUserId() == null) continue;
            AppUser u = userRepo.findById(first.getUserId()).orElse(null);
            if (u == null || u.getTelegramId() == null) continue;
            long ochiq = repo.sumByHolatAndUser(Holat.OCHIQ, u.getId());
            notifier.sendOne(NotifySwitches.JR_KUNLIK_XODIM, u, dailyText(e.getValue(), today, u.getFullName(), ochiq, true), myKb());
        }
        // admin + rahbarlar: hammasi (rahbar — o'z otdeli)
        Set<AppUser> admins = notifier.superadmins();
        String adminText = dailyAdminText(all, today, null);
        notifier.send(NotifySwitches.JR_KUNLIK_ADMIN, admins, adminText, null);
        Set<Long> kassas = new LinkedHashSet<>();
        for (Jarima j : all) if (j.getKassaId() != null) kassas.add(j.getKassaId());
        Set<Long> adminIds = new HashSet<>();
        for (AppUser a : admins) adminIds.add(a.getId());
        for (Long k : kassas) {
            List<AppUser> heads = new ArrayList<>();
            for (AppUser a : notifier.heads(k)) if (!adminIds.contains(a.getId())) heads.add(a);
            if (heads.isEmpty()) continue;
            List<Jarima> mine = all.stream().filter(j -> k.equals(j.getKassaId())).toList();
            notifier.send(NotifySwitches.JR_KUNLIK_ADMIN, heads, dailyAdminText(mine, today, k), null);
        }
        // guruh: faqat KARTA
        List<Jarima> karta = all.stream().filter(j -> j.getTur() == Tur.KARTA).toList();
        if (!karta.isEmpty() && sw.on(NotifySwitches.JR_GURUH)) {
            String g = dailyGroupText(karta, today);
            for (Long chatId : cfg.groupChatIds()) {
                try { sender.send(chatId, g); }
                catch (Exception ex) { log.warn("Jarima guruh jamlamasi ({}): {}", chatId, ex.getMessage()); }
            }
        }
    }

    /** Xodimga: bugungi ro'yxat sabablari bilan + jami + to'lanmagan umumiy. */
    public String dailyText(List<Jarima> list, LocalDate d, String name, long ochiqJami, boolean forEmployee) {
        StringBuilder sb = new StringBuilder("⚖️ <b>ЖАРИМАЛАР — КУН ЯКУНИ</b>\n\n");
        sb.append("📅 ").append(d.format(DF)).append(" · 👤 ").append(esc(name)).append("\n\n");
        long jami = 0; int n = 0, ogoh = 0;
        for (Jarima j : list) {
            n++;
            sb.append(n).append(". ").append(turTitle(j.getTur())).append(" · ")
              .append(LocalDateTime.ofInstant(j.getCreatedAt(), cfg.zone()).format(TF)).append(" · ");
            if (j.getHolat() == Holat.OGOH) { ogoh++; sb.append("огоҳлантириш (").append(j.getTartib()).append("-ҳолат)"); }
            else {
                sb.append("<b>").append(fmt(j.getSumma())).append("</b> сўм (").append(JarimaConfig.foizText(j.getFoiz()))
                  .append("% × ").append(fmt(j.getAsos())).append(")");
                if (j.getHolat() != Holat.OCHIQ) sb.append(" · ").append(holatTitle(j.getHolat()));
                if (j.getHolat() == Holat.OCHIQ) jami += j.getSumma();
            }
            sb.append("\n   Сабаб: ").append(esc(sababLines(j.getSabab())).replace("\n", "\n   ")).append("\n");
        }
        sb.append(RULE).append("\n");
        sb.append("Бугун: <b>").append(fmt(jami)).append("</b> сўм (").append(n - ogoh).append(" та жарима")
          .append(ogoh > 0 ? ", " + ogoh + " та огоҳлантириш" : "").append(")\n");
        sb.append("Жами тўланмаган: <b>").append(fmt(ochiqJami)).append("</b> сўм\n");
        if (forEmployee) sb.append("\n<i>Ҳар бир жарима сабаби юқорида. Эътироз бўлса бўлим раҳбарига мурожаат қилинг.</i>");
        return sb.toString();
    }

    /** Admin/rahbarga: xodim kesimida guruhlangan (sabablar bilan), jami. */
    public String dailyAdminText(List<Jarima> list, LocalDate d, Long kassaId) {
        StringBuilder sb = new StringBuilder("⚖️ <b>ЖАРИМАЛАР — КУН ЯКУНИ</b>\n\n");
        sb.append("📅 ").append(d.format(DF));
        if (kassaId != null) sb.append(" · 🏪 ").append(esc(notifier.kassaName(kassaId)));
        sb.append("\n\n");
        Map<String, List<Jarima>> by = new LinkedHashMap<>();
        for (Jarima j : list) by.computeIfAbsent(j.getXodim(), k -> new ArrayList<>()).add(j);
        long jami = 0; int soni = 0, ogoh = 0;
        for (var e : by.entrySet()) {
            long xj = 0;
            StringBuilder lines = new StringBuilder();
            for (Jarima j : e.getValue()) {
                lines.append("   • ").append(turTitle(j.getTur())).append(" · ")
                     .append(LocalDateTime.ofInstant(j.getCreatedAt(), cfg.zone()).format(TF)).append(" · ");
                if (j.getHolat() == Holat.OGOH) { ogoh++; lines.append("огоҳлантириш"); }
                else { soni++; lines.append("<b>").append(fmt(j.getSumma())).append("</b> сўм"); if (j.getHolat() == Holat.OCHIQ) { xj += j.getSumma(); } else lines.append(" · ").append(holatTitle(j.getHolat())); }
                lines.append("\n      ").append(esc(cut(sababLines(j.getSabab()), 260)).replace("\n", "\n      ")).append("\n");
            }
            jami += xj;
            sb.append("👤 <b>").append(xodimMention(e.getValue().get(0))).append("</b> — ").append(fmt(xj)).append(" сўм\n").append(lines);
        }
        sb.append(RULE).append("\n");
        sb.append("Жами бугун: <b>").append(fmt(jami)).append("</b> сўм · ").append(soni).append(" та жарима")
          .append(ogoh > 0 ? " · " + ogoh + " та огоҳлантириш" : "").append("\n");
        sb.append("Жами тўланмаган (барча кунлар): <b>").append(fmt(repo.sumByHolat(Holat.OCHIQ))).append("</b> сўм · ")
          .append(repo.countByHolat(Holat.OCHIQ)).append(" та\n");
        sb.append("\n<i>Ёпиш/бекор: ⚙️ Настройка → 🔗 MoySklad → ⚖️ Жарималар ёки 🌐 Админ панел → Ҳисоботлар → ⚖️ Жарималар.</i>");
        return sb.toString();
    }

    /** Guruhga: faqat karta qoldig'i jarimalari, xodim mention bilan. */
    public String dailyGroupText(List<Jarima> karta, LocalDate d) {
        StringBuilder sb = new StringBuilder("⚖️ <b>КАРТА ҚОЛДИҒИ — ЖАРИМАЛАР</b>\n\n");
        sb.append("📅 ").append(d.format(DF)).append("\n\n");
        Map<String, List<Jarima>> by = new LinkedHashMap<>();
        for (Jarima j : karta) by.computeIfAbsent(j.getXodim(), k -> new ArrayList<>()).add(j);
        long jami = 0;
        for (var e : by.entrySet()) {
            sb.append("👤 ").append(xodimMention(e.getValue().get(0))).append("\n");
            for (Jarima j : e.getValue()) {
                sb.append("   • 💳 ").append(esc(j.getManbaNomi())).append(" · ")
                  .append(LocalDateTime.ofInstant(j.getCreatedAt(), cfg.zone()).format(TF)).append(" · ");
                if (j.getHolat() == Holat.OGOH) sb.append("огоҳлантириш (").append(j.getTartib()).append("-ҳолат)");
                else {
                    sb.append("<b>").append(fmt(j.getSumma())).append("</b> сўм");
                    if (j.getHolat() == Holat.OCHIQ) jami += j.getSumma();
                    else sb.append(" · ").append(holatTitle(j.getHolat()));
                }
                sb.append("\n      ").append(esc(cut(sababLines(j.getSabab()), 260)).replace("\n", "\n      ")).append("\n");
            }
        }
        sb.append(RULE).append("\n");
        sb.append("Жами: <b>").append(fmt(jami)).append("</b> сўм\n");
        sb.append("<i>Қоида: карта қолдиғи ҳисобот вақтида юборилмаган бўлса — биринчиси огоҳлантириш, кейингилари охирги қолдиқнинг ")
          .append(JarimaConfig.foizText(cfg.foiz(Tur.KARTA))).append("% и.</i>");
        return sb.toString();
    }

    /* ==================== ADMIN AMALLARI ==================== */

    public Jarima close(long id, AppUser by, String izoh, boolean bekor) {
        Jarima j = repo.findById(id).orElseThrow(() -> new BusinessException("Jarima topilmadi"));
        // OCHIQ — yopish/bekor; OGOH (ogohlantirish) — faqat bekor (noto'g'ri yozilgan bo'lsa tartibdan chiqadi, 2026-09-18)
        boolean ogohBekor = bekor && j.getHolat() == Holat.OGOH;
        if (j.getHolat() != Holat.OCHIQ && !ogohBekor) throw new BusinessException("Bu yozuv ochiq emas: " + holatTitleLat(j.getHolat()));
        j.setHolat(bekor ? Holat.BEKOR : Holat.YOPIQ);
        j.setYopilganAt(Instant.now());
        j.setYopganUserId(by.getId());
        j.setIzoh(izoh == null ? null : cut(izoh.trim(), 400));
        repo.save(j);
        audit.log(by.getId(), bekor ? "JARIMA_BEKOR" : "JARIMA_YOPILDI", "jarima", j.getId(), j.getXodim() + " " + j.getSumma() + " " + (izoh == null ? "" : izoh));
        if (j.getUserId() != null) userRepo.findById(j.getUserId()).ifPresent(u -> notifier.sendOne(NotifySwitches.JR_XODIM, u,
                (bekor ? "❌ <b>Жарима бекор қилинди</b>" : "✅ <b>Жарима ёпилди</b>") + "\n\n" + card(j, false), myKb()));
        return j;
    }

    /**
     * Xato yozilgan jarimalarni TO'PLAM bilan bekor qilish (2026-09-18): ro'yxatdagi OGOH/OCHIQ yozuvlar BEKOR bo'ladi
     * (tartibdan chiqadi), har xodimga bitta jamlama xabar. YOPIQ (to'langan) tegilmaydi. @return bekor qilinganlar soni.
     */
    public int cancelBulk(List<Jarima> list, AppUser by, String izoh) {
        Instant now = Instant.now();
        String note = izoh == null || izoh.isBlank() ? "xato yozilgan" : cut(izoh.trim(), 400);
        Map<Long, List<Jarima>> byUser = new LinkedHashMap<>();
        int n = 0;
        for (Jarima j : list) {
            if (j.getHolat() != Holat.OCHIQ && j.getHolat() != Holat.OGOH) continue;
            j.setHolat(Holat.BEKOR);
            j.setYopilganAt(now);
            j.setYopganUserId(by.getId());
            j.setIzoh(note);
            repo.save(j);
            n++;
            audit.log(by.getId(), "JARIMA_BEKOR", "jarima", j.getId(), "to'plam: " + j.getXodim() + " " + j.getSumma() + " " + note);
            if (j.getUserId() != null) byUser.computeIfAbsent(j.getUserId(), k -> new ArrayList<>()).add(j);
        }
        for (var e : byUser.entrySet()) userRepo.findById(e.getKey()).ifPresent(u -> {
            StringBuilder sb = new StringBuilder("❌ <b>Жарималар бекор қилинди</b> — " + e.getValue().size() + " та\n\n");
            for (Jarima j : e.getValue())
                sb.append("• ").append(LocalDateTime.ofInstant(j.getCreatedAt(), cfg.zone()).format(DTF)).append(" · ")
                  .append(turTitle(j.getTur())).append(" · ")
                  .append(j.getSumma() == 0 ? "огоҳлантириш" : fmt(j.getSumma()) + " сўм").append("\n");
            sb.append("\nСабаб: ").append(esc(note));
            notifier.sendOne(NotifySwitches.JR_XODIM, u, sb.toString(), myKb());
        });
        log.info("Jarima to'plam bekor: {} ta ({}), {}", n, note, by.getFullName());
        return n;
    }

    /* ==================== RO'YXAT / FILTR ==================== */

    /** Filtr: hammasi null/0 — cheklovsiz. days: 0 — barcha davr (2 yil). */
    public record Filter(Holat holat, Tur tur, Long userId, Long kassaId, LocalDate from, LocalDate to) {}

    public List<Jarima> list(Filter f) {
        LocalDate today = LocalDate.now(cfg.zone());
        LocalDate from = f.from() == null ? today.minusYears(2) : f.from();
        LocalDate to = f.to() == null ? today : f.to();
        List<Jarima> out = new ArrayList<>();
        for (Jarima j : repo.findBySanaBetweenOrderByIdDesc(from, to)) {
            if (f.holat() != null && j.getHolat() != f.holat()) continue;
            if (f.tur() != null && j.getTur() != f.tur()) continue;
            if (f.userId() != null && f.userId() > 0 && !f.userId().equals(j.getUserId())) continue;
            if (f.kassaId() != null && f.kassaId() > 0 && !f.kassaId().equals(j.getKassaId())) continue;
            out.add(j);
        }
        return out;
    }

    public List<Jarima> mine(AppUser u, int days) {
        LocalDate today = LocalDate.now(cfg.zone());
        return repo.findByUserIdAndSanaBetweenOrderByIdDesc(u.getId(), today.minusDays(Math.max(0, days - 1)), today);
    }

    public Optional<Jarima> get(long id) { return repo.findById(id); }

    public static long sumOchiq(List<Jarima> list) {
        long s = 0; for (Jarima j : list) if (j.getHolat() == Holat.OCHIQ) s += j.getSumma(); return s;
    }
    public static long sumHolat(List<Jarima> list, Holat h) {
        long s = 0; for (Jarima j : list) if (j.getHolat() == h) s += j.getSumma(); return s;
    }

    /** Excel qatorlari (webapp.ExcelReportService.buildTable uchun). */
    public static final String[] XLS_COLS = {"ID", "Сана", "Вақт", "Тур", "Ҳолат", "Ходим", "Отдел", "Манба", "Асос (сўм)", "Фоиз", "Сумма (сўм)", "Сабаб", "Тартиб", "Ёпилган", "Ёпган", "Изоҳ"};

    public List<Object[]> xlsRows(List<Jarima> list) {
        List<Object[]> rows = new ArrayList<>();
        for (Jarima j : list) {
            LocalDateTime c = LocalDateTime.ofInstant(j.getCreatedAt(), cfg.zone());
            rows.add(new Object[]{ j.getId(), c.format(DF), c.format(TF), turTitle(j.getTur()).substring(turTitle(j.getTur()).indexOf(' ') + 1),
                    holatTitle(j.getHolat()).substring(holatTitle(j.getHolat()).indexOf(' ') + 1), j.getXodim(), notifier.kassaName(j.getKassaId()),
                    j.getManbaNomi(), j.getAsos(), j.getFoiz(), j.getSumma(), j.getSabab(), j.getTartib(),
                    j.getYopilganAt() == null ? "" : LocalDateTime.ofInstant(j.getYopilganAt(), cfg.zone()).format(DTF),
                    j.getYopganUserId() == null ? "" : notifier.userName(j.getYopganUserId()), j.getIzoh() == null ? "" : j.getIzoh() });
        }
        return rows;
    }

    /* ==================== YORDAMCHI ==================== */

    /** click_accounts.card_responsible → bot xodimi: "{id=TG;Ism}" (telegram id) yoki "@username" (guruh a'zolari registri). */
    private AppUser resolveResponsible(String resp) {
        Matcher m = RESP_ID.matcher(resp);
        if (m.find()) {
            try { return userRepo.findByTelegramId(Long.parseLong(m.group(1))).filter(AppUser::isActive).orElse(null); }
            catch (NumberFormatException ignored) { }
        }
        String t = resp.trim();
        if (t.startsWith("@") && t.length() > 1) {
            String un = t.substring(1).trim();
            try {
                var gm = memberRepo.findFirstByUsernameIgnoreCase(un);
                if (gm.isPresent() && gm.get().getUserId() != null)
                    return userRepo.findByTelegramId(gm.get().getUserId()).filter(AppUser::isActive).orElse(null);
            } catch (Exception ignored) { }
        }
        return null;
    }

    /**
     * 👤 Xodim ismi MENTION ko'rinishida (guruh va admin xabarlarida odamni belgilash, 17.09.2026):
     * 1) bot xodimi (users.telegram_id) → tg://user havola; 2) KARTA — click_accounts.card_responsible:
     * {@code {id=N;Ism}} → havola, {@code @username} → group_members registri orqali id (topilmasa @username matni —
     * guruhda Telegram o'zi belgilaydi); 3) aks holda oddiy ism. Xato bo'lsa ism.
     */
    public String xodimMention(Jarima j) {
        String name = esc(j.getXodim());
        try {
            if (j.getUserId() != null) {
                Long tg = userRepo.findById(j.getUserId()).map(AppUser::getTelegramId).orElse(null);
                if (tg != null) return link(tg, name);
            }
            if (j.getTur() == Tur.KARTA && j.getManba() != null && (j.getManba().startsWith("click:") || j.getManba().startsWith("clickfarq:"))) {
                long cid = Long.parseLong(j.getManba().substring(j.getManba().indexOf(':') + 1));
                String resp = clickRepo.findById(cid).map(ClickAccount::getCardResponsible).orElse(null);
                if (resp != null && !resp.isBlank()) {
                    Matcher m = RESP_ID.matcher(resp);
                    if (m.find()) return link(Long.parseLong(m.group(1)), name);
                    String t = resp.trim();
                    if (t.startsWith("@") && t.length() > 1) {
                        String un = t.substring(1).trim();
                        var gm = memberRepo.findFirstByUsernameIgnoreCase(un);
                        if (gm.isPresent() && gm.get().getUserId() != null) return link(gm.get().getUserId(), name);
                        return name.contains("@" + un) ? name : name + " " + esc("@" + un);
                    }
                }
            }
        } catch (Exception e) { log.debug("xodimMention ({}): {}", j.getId(), e.getMessage()); }
        return name;
    }

    private static String link(long tgId, String escName) {
        return "<a href=\"tg://user?id=" + tgId + "\">" + escName + "</a>";
    }

    private static String plainResponsible(String resp) {
        Matcher m = Pattern.compile("\\{id=\\d+;([^}]+)\\}").matcher(resp);
        if (m.find()) return m.group(1).trim();
        return resp.trim();
    }

    private static String cut(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
