package uz.kassa.service.ombor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.OmborKamchilik;
import uz.kassa.domain.OmborQoida;
import uz.kassa.repo.OmborKamchilikRepo;
import uz.kassa.repo.OmborQoidaRepo;
import uz.kassa.service.AuditService;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.esc;

/**
 * 🏬 Kamchilik dvigateli — BITTA hayot sikli hamma qoida uchun (docs/OMBOR-TZ.md §6):
 *   tick(): qoidalar → tekshiruvchi → yangi topilma = yangi kamchilik; topilmagan ochiq = avto yopildi;
 *   notify(): notified_at bo'sh → oluvchilarga guruhlangan xabar (INFO — jim);
 *   escalate(): esc1_min → rahbar, esc2_min → rahbar + admin.
 * Tekshiruvchilar registry = Spring bean'lar (OmborChecker.code()).
 */
@Service
@Slf4j
public class OmborRuleEngine {

    public static final String ANSWER_IGNORE = "ETIBORSIZ", ANSWER_FIXED = "TUZATDIM";

    private final OmborQoidaRepo ruleRepo;
    private final OmborKamchilikRepo repo;
    private final OmborRecipients recipients;
    private final OmborConfig cfg;
    private final AuditService audit;
    private final uz.kassa.service.NotifySwitches sw;
    private final Map<String, OmborChecker> checkers = new LinkedHashMap<>();
    private final ObjectMapper om = new ObjectMapper();
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    public OmborRuleEngine(OmborQoidaRepo ruleRepo, OmborKamchilikRepo repo, OmborRecipients recipients,
                           OmborConfig cfg, AuditService audit, uz.kassa.service.NotifySwitches sw, List<OmborChecker> list) {
        this.ruleRepo = ruleRepo; this.repo = repo; this.recipients = recipients; this.cfg = cfg; this.audit = audit; this.sw = sw;
        for (OmborChecker c : list) checkers.put(c.code(), c);
    }

    public Collection<OmborChecker> checkers() { return checkers.values(); }
    public OmborChecker checker(String code) { return checkers.get(code); }

    public JsonNode params(OmborQoida r) {
        try { return om.readTree(r.getParams() == null || r.getParams().isBlank() ? "{}" : r.getParams()); }
        catch (Exception e) { return om.createObjectNode(); }
    }

    /** JSON matnini tekshirish (sozlamada saqlashdan oldin). */
    public String validateParams(String json) {
        try { om.readTree(json); return null; } catch (Exception e) { return e.getMessage(); }
    }


    /* ==================== baholash ==================== */

    /** Har 5 daqiqa. Natija: yangi/yopilgan soni. */
    public int[] tick() {
        if (!cfg.enabled() || !lock.tryLock()) return new int[]{0, 0};
        try {
            int opened = 0, closed = 0;
            for (OmborQoida r : ruleRepo.findByEnabledTrueOrderBySortAscCodeAsc()) {
                int[] c = evaluate(r);
                opened += c[0]; closed += c[1];
            }
            sendNew();
            escalate();
            return new int[]{opened, closed};
        } finally { lock.unlock(); }
    }

    /** Bitta qoida (sozlamadan «hozir tekshirish» ham). */
    public int[] evaluate(OmborQoida r) {
        OmborChecker c = checkers.get(r.getChecker());
        if (c == null) { log.warn("Ombor qoida {}: tekshiruvchi {} yo'q", r.getCode(), r.getChecker()); return new int[]{0, 0}; }
        List<OmborChecker.Found> found;
        try { found = c.run(r, params(r)); }
        catch (Exception e) { log.warn("Ombor qoida {} xatosi: {}", r.getCode(), e.getMessage()); return new int[]{0, 0}; }
        Map<String, OmborKamchilik> open = new HashMap<>();
        for (OmborKamchilik k : repo.findByRuleCodeAndResolvedAtIsNull(r.getCode())) open.put(k.getSubjectType() + "|" + k.getSubjectKey(), k);
        Instant now = Instant.now();
        Instant ignoreLim = now.minus(Duration.ofDays(cfg.ignoreDays()));
        boolean once = params(r).path("once").asBoolean(false);
        int opened = 0, closed = 0;
        for (OmborChecker.Found f : found) {
            OmborKamchilik k = open.remove(f.subjectType() + "|" + f.subjectKey());
            if (k != null) {   // hali ochiq — mazmun yangilanadi
                k.setTitle(f.title()); k.setDetail(f.detail()); k.setKassaId(f.kassaId());
                if (f.ownerUserId() != null) k.setOwnerUserId(f.ownerUserId());
                repo.save(k);
                continue;
            }
            // «once»: odam bir marta javob bergan (tasdiq/tasnif) — boshqa ochilmaydi
            if (once && repo.existsByRuleCodeAndSubjectTypeAndSubjectKeyAndResolvedByIsNotNull(r.getCode(), f.subjectType(), f.subjectKey())) continue;
            // «e'tiborsiz» deyilgan bo'lsa ignore_days ichida qayta ochilmaydi
            if (repo.findFirstByRuleCodeAndSubjectTypeAndSubjectKeyAndAnswerAndResolvedAtAfterOrderByResolvedAtDesc(
                    r.getCode(), f.subjectType(), f.subjectKey(), ANSWER_IGNORE, ignoreLim).isPresent()) continue;
            repo.save(OmborKamchilik.builder().ruleCode(r.getCode()).subjectType(f.subjectType()).subjectKey(f.subjectKey())
                    .kassaId(f.kassaId()).ownerUserId(f.ownerUserId()).title(f.title()).detail(f.detail()).since(now)
                    .notifiedAt(r.silent() ? now : null).build());
            opened++;
        }
        for (OmborKamchilik k : open.values()) {   // endi topilmadi — avto yopildi
            k.setResolvedAt(now); k.setResolvedBy(null); k.setAnswer("AVTO");
            repo.save(k);
            closed++;
        }
        if (opened > 0 || closed > 0) audit.log(null, "OMBOR_QOIDA", "ombor_qoida", null, r.getCode() + " +" + opened + " -" + closed);
        return new int[]{opened, closed};
    }


    /* ==================== xabar ==================== */

    private void sendNew() {
        List<OmborKamchilik> list = repo.findByResolvedAtIsNullAndNotifiedAtIsNull();
        if (list.isEmpty()) return;
        Map<String, OmborQoida> rules = rulesMap();
        // oluvchi → qoida → kamchiliklar (bitta odamga bitta guruhlangan xabar qoida bo'yicha)
        Map<Long, Map<String, List<OmborKamchilik>>> byUser = new LinkedHashMap<>();
        Map<Long, AppUser> users = new HashMap<>();
        Instant now = Instant.now();
        for (OmborKamchilik k : list) {
            OmborQoida r = rules.get(k.getRuleCode());
            k.setNotifiedAt(now);
            repo.save(k);
            if (r == null || r.silent()) continue;
            for (AppUser u : recipients.primary(r, k)) {
                users.put(u.getId(), u);
                byUser.computeIfAbsent(u.getId(), x -> new LinkedHashMap<>()).computeIfAbsent(r.getCode(), x -> new ArrayList<>()).add(k);
            }
        }
        for (var e : byUser.entrySet()) {
            AppUser u = users.get(e.getKey());
            for (var re : e.getValue().entrySet()) {
                OmborQoida r = rules.get(re.getKey());
                recipients.notifier().sendOne(uz.kassa.service.NotifySwitches.OM_KAMCHILIK, u, message(r, re.getValue(), null), listKb(r, re.getValue()));
            }
        }
    }

    private void escalate() {
        Map<String, OmborQoida> rules = rulesMap();
        Instant now = Instant.now();
        Map<String, List<OmborKamchilik>> st1 = new LinkedHashMap<>(), st2 = new LinkedHashMap<>();
        for (OmborKamchilik k : repo.findByResolvedAtIsNullAndNotifiedAtIsNotNull()) {
            OmborQoida r = rules.get(k.getRuleCode());
            if (r == null || r.silent()) continue;
            String g = r.getCode() + "|" + (k.getKassaId() == null ? 0 : k.getKassaId());
            if (r.getEsc1Min() > 0 && k.getEsc1At() == null && !k.getNotifiedAt().isAfter(now.minus(Duration.ofMinutes(r.getEsc1Min()))))
                st1.computeIfAbsent(g, x -> new ArrayList<>()).add(k);
            if (r.getEsc2Min() > 0 && k.getEsc2At() == null && !k.getNotifiedAt().isAfter(now.minus(Duration.ofMinutes(r.getEsc2Min()))))
                st2.computeIfAbsent(g, x -> new ArrayList<>()).add(k);
        }
        for (var e : st1.entrySet()) {
            List<OmborKamchilik> l = e.getValue();
            OmborQoida r = rules.get(l.get(0).getRuleCode());
            for (OmborKamchilik k : l) { k.setEsc1At(now); repo.save(k); }
            recipients.notifier().send(uz.kassa.service.NotifySwitches.OM_ESKALATSIYA, recipients.esc1(l.get(0)),
                    message(r, l, "⏰ <b>" + r.getEsc1Min() + " daqiqadan beri tuzatilmadi</b> — nazorat qiling"), listKb(r, l));
            audit.log(null, "OMBOR_ESKALATSIYA", "ombor_qoida", null, r.getCode() + " " + l.size() + " ta -> rahbar");
        }
        for (var e : st2.entrySet()) {
            List<OmborKamchilik> l = e.getValue();
            OmborQoida r = rules.get(l.get(0).getRuleCode());
            for (OmborKamchilik k : l) { k.setEsc2At(now); repo.save(k); }
            recipients.notifier().send(uz.kassa.service.NotifySwitches.OM_ESKALATSIYA, recipients.esc2(l.get(0)),
                    message(r, l, "❌ <b>TUZATILMADI</b> — " + r.getEsc2Min() + " daqiqadan beri ochiq, xodim ham rahbar ham tuzatmadi"), listKb(r, l));
            audit.log(null, "OMBOR_TUZATILMADI", "ombor_qoida", null, r.getCode() + " " + l.size() + " ta -> admin");
        }
    }

    public Map<String, OmborQoida> rulesMap() {
        Map<String, OmborQoida> m = new HashMap<>();
        for (OmborQoida r : ruleRepo.findAll()) m.put(r.getCode(), r);
        return m;
    }

    /** Bitta xabar shakli: emoji + qoida nomi + N ta + qatorlar (10 tagacha) + ko'rsatma. */
    public String message(OmborQoida r, List<OmborKamchilik> l, String head) {
        StringBuilder sb = new StringBuilder(r.emoji()).append(" <b>").append(esc(r.getTitle())).append("</b> — ").append(l.size()).append(" ta\n");
        if (head != null) sb.append(head).append("\n");
        Set<Long> kassas = new LinkedHashSet<>();
        for (OmborKamchilik k : l) if (k.getKassaId() != null) kassas.add(k.getKassaId());
        if (!kassas.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Long id : kassas) names.add(recipients.notifier().kassaName(id));
            sb.append("🏪 ").append(esc(String.join(", ", names))).append("\n");
        }
        sb.append("\n");
        int n = 0;
        for (OmborKamchilik k : l) {
            if (++n > 10) { sb.append("… yana ").append(l.size() - 10).append(" ta (ro'yxatda)\n"); break; }
            sb.append(n).append(". ").append(l.size() == 1 ? k.getDetail() : esc(k.getTitle())).append("\n");
        }
        sb.append("\n🏬 Омбор → ⚠️ Камчиликлар — tuzatgach «✅ Tuzatdim» bosing; bot o'zi ham tekshiradi.");
        return sb.toString();
    }

    private InlineKeyboardMarkup listKb(OmborQoida r, List<OmborKamchilik> l) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (l.size() == 1) rows.add(irow(btn("✅ Tuzatdim", "om:ok:" + l.get(0).getId()), btn("🙈 E'tiborsiz", "om:ig:" + l.get(0).getId())));
        rows.add(irow(btn("📋 Ro'yxat", "om:l:0.0." + r.getCode() + ".-.-")));
        return inline(rows);
    }


    /* ==================== odam amallari ==================== */

    /** ✅ Tuzatdim / 🙈 E'tiborsiz. */
    public OmborKamchilik resolve(long id, AppUser by, boolean ignore) { return resolve(id, by, ignore ? ANSWER_IGNORE : ANSWER_FIXED); }

    /** Istalgan javob bilan yopish (qoida params.answers: ALMASHTIRISH/BRAK/TASDIQ …). */
    public OmborKamchilik resolve(long id, AppUser by, String answer) {
        OmborKamchilik k = repo.findById(id).orElse(null);
        if (k == null || !k.open()) return k;
        k.setResolvedAt(Instant.now()); k.setResolvedBy(by.getId()); k.setAnswer(answer);
        repo.save(k);
        audit.log(by.getId(), "OMBOR_JAVOB_" + answer, "ombor_kamchilik", id, k.getRuleCode() + " " + k.getTitle());
        return k;
    }

    /** Qoidaning maxsus javob variantlari (params.answers CSV), bo'lmasa bo'sh. */
    public List<String> answers(OmborQoida r) {
        List<String> out = new ArrayList<>();
        if (r == null) return out;
        for (String a : params(r).path("answers").asText("").split(",")) if (!a.isBlank()) out.add(a.trim());
        return out;
    }

    public static String answerTitle(String a) {
        return switch (a) {
            case "ALMASHTIRISH" -> "🔁 Almashtirish (qayta sotuvga)"; case "BRAK" -> "🗑 Brak"; case "MUDDATI_OTGAN" -> "⏳ Muddati o'tgan brak";
            case "TASDIQ" -> "✅ Tasdiqlayman"; case "TUZATDIM" -> "✅ Tuzatdim"; case "ETIBORSIZ" -> "🙈 E'tiborsiz"; case "AVTO" -> "avto";
            default -> a;
        };
    }

    public long openCount() { return repo.countByResolvedAtIsNull(); }
    public int openByRule(String code) { return repo.findByRuleCodeAndResolvedAtIsNull(code).size(); }
}
