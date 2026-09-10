package uz.kassa.service.control;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import uz.kassa.bot.TextUtil;
import uz.kassa.domain.AgentCheck;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.MsAgentIndex;
import uz.kassa.repo.AgentCheckRepo;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.MsAgentRepo;
import uz.kassa.service.AuditService;
import uz.kassa.service.moysklad.MoySkladClient;
import uz.kassa.service.moysklad.MoySkladClient.MsAgentFull;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.esc;

/**
 * A-modul: kontragent sifat nazorati (docs/KONTRAGENT-NAZORAT.md §2).
 * Har 2 daqiqada MoySklad'dan o'zgargan kontragentlar o'qiladi; yangi yaratilganlari qoidalar
 * (K1..K8) bilan tekshiriladi. Xato bo'lsa — FAQAT yaratgan xodimga xabar; tuzatilgach «✅»;
 * kuniga 1 eslatma; escalate_hours dan keyin otdel rahbari + SuperAdmin.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentCheckService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final AgentCheckRepo repo;
    private final MsAgentRepo indexRepo;
    private final AppUserRepo userRepo;
    private final MoySkladClient msClient;
    private final ControlConfig cfg;
    private final EmployeeLinkService link;
    private final ControlNotifier notifier;
    private final AuditService audit;

    /** Bitta xato: kod + ko'rinadigan matn (K5 uchun dublikat nomlari bilan). */
    public record Violation(String code, String text) {}

    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();


    /* ==================== POLLING ==================== */

    public void tick() {
        if (!cfg.enabled()) return;
        String token = msClient.currentToken();
        if (token == null || token.isBlank()) return;
        if (!lock.tryLock()) return;
        try { tickLocked(); } finally { lock.unlock(); }
    }


    private void tickLocked() {
        ensureIndexed();

        LocalDateTime now = LocalDateTime.now(cfg.zone());
        LocalDateTime from = cfg.lastAgentSync().orElse(now.minusHours(1)).minusMinutes(2);
        List<MsAgentFull> list;
        try {
            list = msClient.fetchAgentsUpdated(from);
        } catch (Exception e) {
            log.warn("Kontragent nazorati: MoySklad o'qilmadi: {}", e.getMessage());
            return;
        }
        for (MsAgentFull a : list) {
            try {
                index(a);
                process(a, now);
            } catch (Exception e) {
                log.warn("Kontragent {} tekshiruvida xato: {}", a.name(), e.getMessage());
            }
        }
        cfg.set(ControlConfig.LAST_AGENT_SYNC, now);
        verifyOpen(now);
        escalate(now);
    }


    private volatile long lastVerify = 0;

    /**
     * O'chirilgan kontragent «o'zgargan» ro'yxatiga tushmaydi — shuning uchun OCHIQ xatolar 10 daqiqada bir
     * MoySklad'dan alohida o'qiladi: 404 → OCHIRILDI (xato yopiladi, dublikat indeksidan chiqadi);
     * bor bo'lsa qayta baholanadi (tuzatilgan bo'lsa ✅). Ko'pi bilan 30 ta / marta (429 limiti).
     */
    private void verifyOpen(LocalDateTime now) {
        if (System.currentTimeMillis() - lastVerify < 600_000L) return;
        lastVerify = System.currentTimeMillis();
        int n = 0;
        for (AgentCheck ac : repo.findByStatusOrderByIdDesc(AgentCheck.Status.OCHIQ)) {
            if (++n > 30) break;
            try {
                MsAgentFull a = msClient.fetchAgent(ac.getAgentMsId());
                if (a == null) markDeleted(ac);
                else { index(a); process(a, now); }
            } catch (Exception e) {
                log.warn("Kontragent {} tekshirilmadi: {}", ac.getAgentName(), e.getMessage());
                break;   // limit/tarmoq — keyingi safar
            }
        }
    }

    /** Kontragent MoySklad'da o'chirilgan: xato yopiladi, indeksdan chiqadi (dublikat K5 uchun). */
    public void markDeleted(AgentCheck ac) {
        ac.setStatus(AgentCheck.Status.OCHIRILDI);
        ac.setFixedAt(Instant.now());
        repo.save(ac);
        try { indexRepo.deleteById(ac.getAgentMsId()); } catch (Exception ignored) { }
        audit.log(ac.getCreatorUserId(), "KG_OCHIRILDI", "agent_check", ac.getId(), ac.getAgentName());
        log.info("Kontragent o'chirilgan, xato yopildi: {}", ac.getAgentName());
    }


    /** Dublikat indeksi: birinchi marta barcha kontragentlar (bir marta, ~100 sahifa). */
    private void ensureIndexed() {
        if (cfg.get(ControlConfig.AGENTS_INDEXED).isPresent()) return;
        LocalDateTime now = LocalDateTime.now(cfg.zone());
        try {
            List<MsAgentFull> all = msClient.fetchAgentsAll();
            List<MsAgentIndex> rows = new ArrayList<>();
            for (MsAgentFull a : all) rows.add(indexRow(a));
            indexRepo.saveAll(rows);
            cfg.set(ControlConfig.AGENTS_INDEXED, "1");
            if (cfg.agentsSince().isEmpty()) cfg.set(ControlConfig.AGENTS_SINCE, now);
            log.info("Kontragent indeksi yuklandi: {} ta", rows.size());
        } catch (Exception e) {
            log.warn("Kontragent indeksi yuklanmadi: {}", e.getMessage());
        }
    }

    private MsAgentIndex indexRow(MsAgentFull a) {
        return MsAgentIndex.builder().msId(a.id()).name(ShipmentControlService.cut(a.name(), 400))
                .phoneNorm(ShipmentControlService.cut(TextUtil.normPhone(a.phone()), 20))
                .inn(ShipmentControlService.cut(a.inn(), 40))
                .archived(a.archived()).msUpdated(a.updated()).build();
    }

    private void index(MsAgentFull a) { indexRepo.save(indexRow(a)); }


    /** Bitta kontragent: yangi bo'lsa tekshiruv, OCHIQ bo'lsa qayta tekshiruv. */
    void process(MsAgentFull a, LocalDateTime now) {
        AgentCheck ac = repo.findByAgentMsId(a.id()).orElse(null);
        LocalDateTime since = cfg.agentsSince().orElse(now);
        if (ac == null && (a.created() == null || a.created().isBefore(since))) return; // eski kontragent
        List<Violation> v = evaluate(a);

        if (ac == null) {
            ac = AgentCheck.builder().agentMsId(a.id()).agentName(ShipmentControlService.cut(a.name(), 400)).msCreatedAt(a.created())
                    .msUpdatedAt(a.updated()).kassaId(notifier.kassaByGroup(a.groupId())).build();
            if (v.isEmpty()) { ac.setStatus(AgentCheck.Status.OK); repo.save(ac); return; }
            open(ac, a, v, now, true);
            return;
        }

        ac.setAgentName(ShipmentControlService.cut(a.name(), 400));
        ac.setMsUpdatedAt(a.updated());
        if (ac.getKassaId() == null) ac.setKassaId(notifier.kassaByGroup(a.groupId()));
        switch (ac.getStatus()) {
            case ETIBORSIZ -> repo.save(ac);
            case OCHIQ -> {
                if (v.isEmpty()) fixed(ac, now);
                else { ac.setViolations(codes(v)); repo.save(ac); }
            }
            case OK, TUZATILDI, OCHIRILDI -> {   // OCHIRILDI: savatdan qaytarilgan bo'lsa qayta baholanadi
                if (v.isEmpty()) { if (ac.getStatus() == AgentCheck.Status.OCHIRILDI) ac.setStatus(AgentCheck.Status.OK); repo.save(ac); }
                else open(ac, a, v, now, false);   // tahrirda buzilgan
            }
        }
    }


    private void open(AgentCheck ac, MsAgentFull a, List<Violation> v, LocalDateTime now, boolean fresh) {
        ac.setStatus(AgentCheck.Status.OCHIQ);
        ac.setViolations(codes(v));
        ac.setNotifiedAt(Instant.now());
        ac.setEscalatedAt(null);
        ac.setEscalated2At(null);
        ac.setFixedAt(null);
        if (ac.getCreatedUid() == null || ac.getCreatedUid().isBlank()) {
            String uid = msClient.fetchAgentCreatorUid(a.id());
            if (uid.isBlank()) uid = a.ownerUid();
            ac.setCreatedUid(uid);
        }
        if (ac.getCreatorUserId() == null) {
            Optional<AppUser> u = link.byUid(ac.getCreatedUid());
            if (u.isEmpty()) u = link.resolve(a.ownerId(), a.ownerUid(), a.ownerName(), "");
            u.ifPresent(x -> {
                ac.setCreatorUserId(x.getId());
                if (ac.getKassaId() == null) ac.setKassaId(x.getKassaId());
            });
        }
        repo.save(ac);
        audit.log(ac.getCreatorUserId(), "KG_XATO_TOPILDI", "agent_check", ac.getId(),
                a.name() + " " + ac.getViolations());

        String text = errorMessage(ac, a, v, fresh);
        AppUser creator = ac.getCreatorUserId() == null ? null : userRepo.findById(ac.getCreatorUserId()).orElse(null);
        if (creator != null && creator.getTelegramId() != null) {
            notifier.sendOne(creator, text, agentKb(a.id()));
        } else {
            notifier.send(notifier.superadmins(), "⚠️ <i>Xodim botga bog'lanmagan: " + esc(who(ac))
                    + " — xabar sizga keldi. Pastdagi tugma bilan ulang, keyingi xabarlar unga boradi.</i>"
                    + notifier.inviteLine(creator) + "\n\n" + text,
                    ControlNotifier.withLink(agentKb(a.id()), creator));
        }
    }


    private void fixed(AgentCheck ac, LocalDateTime now) {
        ac.setStatus(AgentCheck.Status.TUZATILDI);
        ac.setFixedAt(Instant.now());
        repo.save(ac);
        audit.log(ac.getCreatorUserId(), "KG_XATO_TUZATILDI", "agent_check", ac.getId(), ac.getAgentName());
        String text = "✅ <b>Kontragent tuzatildi</b>: " + esc(ac.getAgentName()) + "\nRahmat, endi hammasi to'g'ri.";
        if (ac.getCreatorUserId() != null)
            userRepo.findById(ac.getCreatorUserId()).ifPresent(u -> notifier.sendOne(u, text, null));
    }


    /**
     * Ikki bosqichli eskalatsiya (user 09.09.2026): xodimga xabardan esc1 daqiqa o'tsa — otdel RAHBARI;
     * esc2 daqiqa o'tsa ham tuzatilmasa — «tuzatilmadi» SuperAdmin + rahbar + belgilanganlar.
     */
    private void escalate(LocalDateTime now) {
        Instant lim1 = Instant.now().minusSeconds(cfg.esc1Min() * 60L);
        Instant lim2 = Instant.now().minusSeconds(cfg.esc2Min() * 60L);
        for (AgentCheck ac : repo.findByStatusOrderByIdDesc(AgentCheck.Status.OCHIQ)) {
            if (ac.getNotifiedAt() == null) continue;
            if (ac.getEscalatedAt() == null && !ac.getNotifiedAt().isAfter(lim1)) {
                ac.setEscalatedAt(Instant.now());
                repo.save(ac);
                audit.log(ac.getCreatorUserId(), "KG_XATO_ESKALATSIYA", "agent_check", ac.getId(), ac.getAgentName() + " -> rahbar");
                String text = "\u23F0 <b>Kontragent xatosi " + cfg.esc1Min() + " daqiqadan beri tuzatilmadi</b>\n"
                        + agentLines(ac)
                        + "\nXodim tuzatishini nazorat qiling. " + cfg.esc2Min() + " daqiqada tuzatilmasa admin'ga «tuzatilmadi» xabari boradi.";
                Set<AppUser> heads = notifier.heads(ac.getKassaId());
                if (!heads.isEmpty()) notifier.send(heads, text, agentKb(ac.getAgentMsId()));
            }
            if (ac.getEscalated2At() == null && !ac.getNotifiedAt().isAfter(lim2)) {
                ac.setEscalated2At(Instant.now());
                repo.save(ac);
                audit.log(ac.getCreatorUserId(), "KG_XATO_TUZATILMADI", "agent_check", ac.getId(), ac.getAgentName() + " -> admin");
                String text = "\u274C <b>TUZATILMADI — kontragent xatosi " + cfg.esc2Min() + " daqiqadan beri ochiq</b>\n"
                        + agentLines(ac)
                        + "\nXodim ham, otdel rahbari ham tuzatmadi.";
                notifier.send(notifier.escalation(ac.getKassaId()), text, agentKb(ac.getAgentMsId()));
            }
        }
    }

    private String agentLines(AgentCheck ac) {
        return "\uD83D\uDC64 Xodim: " + esc(who(ac)) + " · " + esc(notifier.kassaName(ac.getKassaId())) + "\n"
                + "\uD83C\uDFE2 Kontragent: " + esc(ac.getAgentName()) + "\n"
                + "\uD83D\uDD52 Yaratilgan: " + (ac.getMsCreatedAt() == null ? "—" : ac.getMsCreatedAt().format(DTF)) + "\n\n"
                + "Tuzatish kerak:\n" + bullets(ac.violationList());
    }


    /** Kunlik jamlama (daily_time dan keyin, kuniga bir): har xodimga tuzatilmagan kontragentlari. */
    public void dailyTick() {
        if (!cfg.enabled()) return;
        LocalDate today = LocalDate.now(cfg.zone());
        if (LocalTime.now(cfg.zone()).isBefore(cfg.dailyTime())) return;
        if (today.toString().equals(cfg.get(ControlConfig.AGENT_DAILY_SENT).orElse(""))) return;
        cfg.set(ControlConfig.AGENT_DAILY_SENT, today.toString());

        Map<Long, List<AgentCheck>> byUser = new LinkedHashMap<>();
        for (AgentCheck ac : repo.findByStatusOrderByIdDesc(AgentCheck.Status.OCHIQ)) {
            if (ac.getCreatorUserId() == null) continue;
            if (ac.getNotifiedAt() != null && ac.getNotifiedAt().isAfter(Instant.now().minusSeconds(3600))) continue;
            byUser.computeIfAbsent(ac.getCreatorUserId(), k -> new ArrayList<>()).add(ac);
        }
        for (var e : byUser.entrySet()) {
            AppUser u = userRepo.findById(e.getKey()).orElse(null);
            if (u == null || u.getTelegramId() == null) continue;
            for (AgentCheck ac : e.getValue()) { ac.setLastDaily(today); repo.save(ac); }
            notifier.sendOne(u, openListText(e.getValue()), null);
        }
    }

    /** Xodimning ochiq (OCHIQ) tekshiruvlari — eskidan yangiga (kelish tartibi). */
    public List<AgentCheck> openFor(AppUser u) {
        List<AgentCheck> l = new ArrayList<>(repo.findByStatusAndCreatorUserIdOrderByIdDesc(AgentCheck.Status.OCHIQ, u.getId()));
        Collections.reverse(l);
        return l;
    }

    /** Saqlangan ma'lumotdan standart xato xabari (MoySklad'ga murojaatsiz) — xodim keyin ulanganda ham o'sha ko'rinish. */
    public String storedErrorMessage(AgentCheck ac) {
        StringBuilder sb = new StringBuilder("⚠️ <b>Контрагент хато киритилди</b>\n");
        sb.append("👤 Xodim: ").append(esc(who(ac)));
        if (ac.getKassaId() != null) sb.append(" · ").append(esc(notifier.kassaName(ac.getKassaId())));
        sb.append("\n🏢 Kontragent: <b>").append(esc(ac.getAgentName())).append("</b>\n");
        sb.append("🕒 Yaratildi: ").append(ac.getMsCreatedAt() == null ? "—" : ac.getMsCreatedAt().format(DTF)).append(" (MoySklad)\n\n");
        sb.append("<b>Tuzatish kerak:</b>\n").append(bullets(ac.violationList()));
        sb.append("\nℹ️ MoySklad'da tuzating — bot o'zi tekshiradi va «✅» yuboradi. ")
          .append(cfg.esc1Min()).append(" daqiqada tuzatilmasa rahbarga, ").append(cfg.esc2Min()).append(" daqiqada admin'ga xabar boradi.");
        return sb.toString();
    }

    /** Xodim xabarni HAQIQATAN olgan paytdan tartib qayta boshlanadi: eskalatsiya soati shu ondan hisoblanadi. */
    public void restartTimeline(AgentCheck ac) {
        ac.setNotifiedAt(Instant.now());
        ac.setEscalatedAt(null);
        ac.setEscalated2At(null);
        repo.save(ac);
    }


    String openListText(List<AgentCheck> list) {
        StringBuilder sb = new StringBuilder("⚠️ <b>Tuzatilmagan kontragentlar</b> — " + list.size() + " ta\n");
        int n = 0;
        for (AgentCheck ac : list) {
            if (++n > 20) { sb.append("… yana ").append(list.size() - 20).append(" ta\n"); break; }
            sb.append(n).append(". <b>").append(esc(ac.getAgentName())).append("</b> · ")
              .append(ac.getMsCreatedAt() == null ? "" : ac.getMsCreatedAt().format(DTF)).append(" · ")
              .append(String.join(", ", ac.violationList().stream().map(AgentCheckService::shortTitle).toList()))
              .append("\n");
        }
        sb.append("\nMoySklad'da tuzating — bot o'zi tekshiradi. Ro'yxat: 🤝 КОНТРАГЕНТ → ⚠️ Хатолар");
        return sb.toString();
    }


    /* ==================== QOIDALAR ==================== */

    public List<Violation> evaluate(MsAgentFull a) {
        List<Violation> out = new ArrayList<>();
        String name = a.name() == null ? "" : a.name().trim();
        Set<String> tags = new HashSet<>();
        for (String t : a.tags()) tags.add(MoySkladClient.normAttr(t));
        boolean legal = a.isLegal(), ip = a.isEntrepreneur(), individual = a.isIndividual();
        boolean orgTag = tags.contains("ташкилот") || tags.contains("tashkilot");

        if (cfg.ruleOn("K1")) {
            String low = name.toLowerCase();
            if (name.length() < 3 || name.matches("[\\d\\s.,-]+")
                    || low.matches("(test|qwe|asd|aaa|bbb|www|xxx|123|тест|проба|sinov)[\\w\\s]*"))
                out.add(new Violation("K1", "📛 Наименование noto'g'ri yoki juda qisqa: «" + esc(name) + "»"));
        }
        if (cfg.ruleOn("K2") && a.tags().isEmpty())
            out.add(new Violation("K2", "🏷 Guruh (Группы) tanlanmagan"));
        String np = TextUtil.normPhone(a.phone());
        if (cfg.ruleOn("K3") && (np.length() != 12 || !np.startsWith("998")))
            out.add(new Violation("K3", (a.phone() == null || a.phone().isBlank())
                    ? "📞 Telefon kiritilmagan" : "📞 Telefon formati noto'g'ri: «" + esc(a.phone()) + "» (+998 XX XXX XX XX)"));
        if (cfg.ruleOn("K4") && (legal || ip || orgTag)) {
            String inn = a.inn() == null ? "" : a.inn().replaceAll("\\D", "");
            if (inn.length() < 9)
                out.add(new Violation("K4", "🧾 ИНН kiritilmagan yoki noto'g'ri (Юр. лицо / ИП / ташкилот uchun majburiy)"));
        }
        if (cfg.ruleOn("K5")) {
            List<String> dup = new ArrayList<>();
            if (!np.isEmpty())
                for (MsAgentIndex x : indexRepo.findByPhoneNormAndArchivedFalse(np))
                    if (!x.getMsId().equals(a.id())) dup.add(x.getName() + " (telefon)");
            if (!name.isEmpty())
                for (MsAgentIndex x : indexRepo.findByNameIgnoreCaseAndArchivedFalse(name))
                    if (!x.getMsId().equals(a.id())) dup.add(x.getName() + " (nom)");
            if (!dup.isEmpty())
                out.add(new Violation("K5", "👥 Dublikat — bunday kontragent bor: "
                        + esc(String.join("; ", dup.size() > 3 ? dup.subList(0, 3) : dup))));
        }
        if (cfg.ruleOn("K6") && legal
                && ((a.legalTitle() == null || a.legalTitle().isBlank()) || (a.legalAddress() == null || a.legalAddress().isBlank())))
            out.add(new Violation("K6", "🏢 Полное наименование / Юридический адрес to'ldirilmagan (Юр. лицо)"));
        if (cfg.ruleOn("K7") && tags.contains("клиент")) {
            boolean any = false;
            for (var e : a.attributes().entrySet()) {
                String n = MoySkladClient.normAttr(e.getKey());
                if ((n.contains("келди") || n.contains("реклам")) && e.getValue() != null && !e.getValue().isBlank()) any = true;
            }
            if (!any) out.add(new Violation("K7", "📣 «ким орқали келди» / «Рекламный канал» tanlanmagan (клиент)"));
        }
        if (cfg.ruleOn("K8")) {
            boolean legalTag = orgTag || tags.contains("поставщик") || tags.contains("филиал");
            if ((legalTag && individual) || (tags.contains("сотрудник") && legal))
                out.add(new Violation("K8", "⚠️ Guruh va Тип контрагента mos emas: " + esc(String.join(", ", a.tags()))
                        + " ↔ " + typeLabel(a.companyType())));
        }
        return out;
    }


    /** UI: qayta tekshirish (MoySklad'dan jonli). Kontragent o'chirilgan bo'lsa null. */
    public List<Violation> recheck(AgentCheck ac) {
        MsAgentFull a = msClient.fetchAgent(ac.getAgentMsId());
        if (a == null) return null;
        index(a);
        List<Violation> v = evaluate(a);
        ac.setAgentName(a.name());
        ac.setMsUpdatedAt(a.updated());
        if (ac.getStatus() == AgentCheck.Status.OCHIQ && v.isEmpty()) fixed(ac, LocalDateTime.now(cfg.zone()));
        else if (ac.getStatus() != AgentCheck.Status.ETIBORSIZ && !v.isEmpty()) {
            if (ac.getStatus() != AgentCheck.Status.OCHIQ) ac.setStatus(AgentCheck.Status.OCHIQ);
            ac.setViolations(codes(v));
            repo.save(ac);
        } else repo.save(ac);
        return v;
    }


    public void ignore(AgentCheck ac, AppUser by) {
        ac.setStatus(AgentCheck.Status.ETIBORSIZ);
        repo.save(ac);
        audit.log(by.getId(), "KG_XATO_ETIBORSIZ", "agent_check", ac.getId(), ac.getAgentName());
    }


    /* ==================== KO'RINISH ==================== */

    public List<AgentCheck> visibleFor(AppUser u) {
        return switch (u.getRole()) {
            case SUPERADMIN, BUXGALTER -> repo.findByStatusOrderByIdDesc(AgentCheck.Status.OCHIQ);
            case KASSIR -> {
                List<AgentCheck> out = new ArrayList<>(repo.findByStatusAndCreatorUserIdOrderByIdDesc(
                        AgentCheck.Status.OCHIQ, u.getId()));
                for (Long k : notifier.headOf(u))
                    for (AgentCheck ac : repo.findByStatusAndKassaIdOrderByIdDesc(AgentCheck.Status.OCHIQ, k))
                        if (out.stream().noneMatch(x -> x.getId().equals(ac.getId()))) out.add(ac);
                yield out;
            }
        };
    }

    public Optional<AgentCheck> find(long id) { return repo.findById(id); }

    public long openCount() { return repo.countByStatus(AgentCheck.Status.OCHIQ); }


    private String errorMessage(AgentCheck ac, MsAgentFull a, List<Violation> v, boolean fresh) {
        StringBuilder sb = new StringBuilder();
        sb.append(fresh ? "⚠️ <b>Контрагент хато киритилди</b>\n" : "⚠️ <b>Контрагентда хато пайдо бўлди</b>\n");
        sb.append("👤 Xodim: ").append(esc(who(ac)));
        if (ac.getKassaId() != null) sb.append(" · ").append(esc(notifier.kassaName(ac.getKassaId())));
        sb.append("\n🏢 Kontragent: <b>").append(esc(a.name())).append("</b>\n");
        if (a.phone() != null && !a.phone().isBlank()) sb.append("📞 ").append(esc(a.phone())).append("\n");
        sb.append("🕒 Yaratildi: ").append(a.created() == null ? "—" : a.created().format(DTF)).append(" (MoySklad)\n\n");
        sb.append("<b>Tuzatish kerak:</b>\n");
        for (Violation x : v) sb.append("• ").append(x.text()).append("\n");
        sb.append("\nℹ️ MoySklad'da tuzating — bot o'zi tekshiradi va «✅» yuboradi. ")
          .append(cfg.esc1Min()).append(" daqiqada tuzatilmasa rahbarga, ").append(cfg.esc2Min()).append(" daqiqada admin'ga xabar boradi.");
        return sb.toString();
    }

    public String who(AgentCheck ac) {
        if (ac.getCreatorUserId() != null) return notifier.userName(ac.getCreatorUserId());
        return ac.getCreatedUid() == null || ac.getCreatedUid().isBlank() ? "noma'lum" : ac.getCreatedUid();
    }

    public static InlineKeyboardMarkup agentKb(String agentId) {
        return inline(List.of(irow(ControlNotifier.urlBtn("🔗 MoySklad'da ochish",
                ControlNotifier.MS_AGENT_URL + agentId))));
    }

    static String codes(List<Violation> v) {
        return String.join(",", v.stream().map(Violation::code).toList());
    }

    public static String bullets(List<String> codes) {
        StringBuilder sb = new StringBuilder();
        for (String c : codes) sb.append("• ").append(shortTitle(c)).append("\n");
        return sb.toString();
    }

    public static String shortTitle(String code) {
        return switch (code) {
            case "K1" -> "📛 Наименование";
            case "K2" -> "🏷 Guruh";
            case "K3" -> "📞 Telefon";
            case "K4" -> "🧾 ИНН";
            case "K5" -> "👥 Dublikat";
            case "K6" -> "🏢 Полное наим./адрес";
            case "K7" -> "📣 Reklama kanali";
            case "K8" -> "⚠️ Guruh↔Tip";
            default -> code;
        };
    }

    static String typeLabel(String companyType) {
        if (companyType == null) return "—";
        if (companyType.startsWith("legal")) return "Юр. лицо";
        if (companyType.startsWith("entrepreneur")) return "ИП";
        return "Физ. лицо";
    }
}
