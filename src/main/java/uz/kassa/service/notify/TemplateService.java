package uz.kassa.service.notify;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.bot.Sender;
import uz.kassa.bot.TextUtil;
import uz.kassa.config.AppProps;
import uz.kassa.domain.*;
import uz.kassa.repo.*;
import uz.kassa.service.LedgerService;
import uz.kassa.service.SubmissionService;
import uz.kassa.service.moysklad.MoySkladClient;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 🔔 Shablon dvigateli — {…} o'rinbosarlarni bazadan / MoySklad'dan to'ldiradi.
 *
 * Sintaksis (docs/NOTIFY-SHABLON.md):
 *   {sana} {vaqt} {oy_nomi} …                — umumiy
 *   {kassa:1.naqd}  {kassa:"Nom".prixod:oy}  — otdel/kassa maydonlari (ID yoki nom)
 *   {karta:3.qoldiq}  {bux.naqd}  {user:5}  {rol:KASSIR}  {eslatma:7.qoldiq}  {qarz:2.qoldiq}
 *   {jami.naqd}  {jami.prixod:oy}  {jami.naqd:kassa=1,3}
 *   :davr  — kun (standart) / kecha / hafta / otgan_hafta / oy / otgan_oy / 7kun / 2026-09-01..2026-09-03
 *   |format — (yo'q)=so'm, |tiyin, |ming, |mln, |+
 *   {#kassalar}…{/kassalar}  {#kartalar:kassa=1}…{/kartalar}  {#eslatmalar:otgan}…{/eslatmalar}
 *   {#qarzlar}…{/qarzlar}  {#xodimlar:rol=KASSIR}…{/xodimlar}
 *   {?ifoda}…{/?}  — qiymat bo'sh/nol bo'lmasa ko'rinadi
 *
 * Barcha pul qiymatlari ICHKI TIYINDA yuradi; standart ko'rinish — so'm.
 * Noma'lum o'rinbosar o'zgarishsiz qoladi va Result.unknown ga yoziladi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TemplateService {

    private final KassaRepo kassaRepo;
    private final ClickAccountRepo clickRepo;
    private final AppUserRepo userRepo;
    private final ReminderRepo reminderRepo;
    private final DebtRepo debtRepo;
    private final GroupMemberRepo groupMemberRepo;
    private final Sender sender;
    private final AppProps props;

    static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("HH:mm");
    static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final String[] KUN = {"Dushanba", "Seshanba", "Chorshanba", "Payshanba",
            "Juma", "Shanba", "Yakshanba"};
    private static final String[] OY = {"Yanvar", "Fevral", "Mart", "Aprel", "May", "Iyun",
            "Iyul", "Avgust", "Sentyabr", "Oktyabr", "Noyabr", "Dekabr"};

    private static final Pattern LIST_BLOCK = Pattern.compile(
            "\\{#([a-zA-Z_]+)(?::([^}]*))?\\}(.*?)\\{/\\1\\}", Pattern.DOTALL);
    private static final Pattern COND_BLOCK = Pattern.compile(
            "\\{\\?([^}]+)\\}(.*?)\\{/(?:\\?|\\1)\\}", Pattern.DOTALL);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}#?/][^{}]*)\\}");
    private static final Pattern ID_MENTION = Pattern.compile("\\{id=(\\d+);([^}]+)\\}");

    /* -------------------- yordam matni (admin panel uchun) -------------------- */

    public static final String HELP_1 = """
            📖 <b>O'rinbosarlar (1/3) — umumiy va ob'ektlar</b>

            <b>Umumiy:</b> <code>{sana}</code> <code>{vaqt}</code> <code>{kun_nomi}</code> <code>{oy_nomi}</code> \
            <code>{hafta_boshi}</code> <code>{hafta_oxiri}</code> <code>{kecha}</code>
            <b>Mention:</b> <code>{adminlar}</code> <code>{xodimlar}</code> <code>{hamma}</code> \
            <code>{rol:KASSIR}</code> <code>{user:5}</code> <code>{id=123;Ism}</code> @username

            <b>Otdel/kassa</b> <code>{kassa:1.MAYDON}</code> yoki <code>{kassa:"Nom".MAYDON}</code>, \
            <code>{kassa:mening.MAYDON}</code> (kassirning o'z otdeli):
            nom · naqd · klik · terminal · naqd_mavjud · klik_mavjud · kassir · topshirilmagan
            <i>MoySklad (davr bilan):</i> prixod · prixod_naqd · prixod_beznaqd · vozvrat · rasxod · sof
            <i>Bot bazasi:</i> bot_prixod · bot_rasxod · kirim · chiqim
            <i>Kunlik solishtirish:</i> nuqta · kassirlar · savdo_ms · savdo_bot · savdo_farq · savdo_holat · \
            naqd_topshirilgan · p2p_qoldiq (davr: kun/kecha)

            <b>Karta</b> <code>{karta:3.MAYDON}</code>: nom · kassa · masul · qoldiq · qoldiq_vaqt · \
            qoldiq_kim · bot · ms · farq · holat
            <b>Основной:</b> <code>{bux.naqd}</code> <code>{bux.klik}</code> <code>{bux.terminal}</code>
            <b>Eslatma</b> <code>{eslatma:7.MAYDON}</code>: agent · summa · tolandi · qoldiq · muddat · \
            qoldi_kun · holat · izoh
            <b>Qarz</b> <code>{qarz:2.MAYDON}</code>: qarzdor · kreditor · summa · qoldiq · sabab
            """;

    public static final String HELP_2 = """
            📖 <b>O'rinbosarlar (2/3) — yig'indi, davr, format</b>

            <b>Yig'indi</b> <code>{jami.MAYDON}</code>: naqd · klik · terminal · hammasi · \
            prixod · prixod_naqd · prixod_beznaqd · vozvrat · rasxod · sof · karta_qoldiq · karta_ms · \
            karta_farq · karta_farq_soni · karta_kiritilmagan · karta_soni · kassa_soni · \
            eslatma_faol · eslatma_otgan · eslatma_qoldiq · qarz_ochiq · topshirilmagan
            Tanlangan otdellar: <code>{jami.naqd:kassa=1,3}</code>

            <b>Davr</b> (prixod/vozvrat/rasxod/kirim/chiqim uchun, ":" bilan):
            <code>:kun</code> (standart) · <code>:kecha</code> · <code>:hafta</code> · <code>:otgan_hafta</code> · \
            <code>:oy</code> · <code>:otgan_oy</code> · <code>:yil</code> · <code>:7kun</code> · \
            <code>:2026-09-01..2026-09-03</code>
            Masalan: <code>{kassa:1.prixod:oy}</code>  <code>{jami.prixod_naqd:kecha}</code>

            <b>Format</b> ("|" bilan): standart — so'm; <code>|tiyin</code> · <code>|ming</code> · \
            <code>|mln</code> · <code>|+</code> (ishora bilan)
            Masalan: <code>{karta:3.farq|+}</code>  <code>{jami.prixod:oy|mln}</code>
            """;

    public static final String HELP_3 = """
            📖 <b>O'rinbosarlar (3/3) — ro'yxat va shart bloklari</b>

            Ro'yxat: <code>{#kassalar}…{/kassalar}</code> (<code>{#kassalar:naqdli}</code> — naqdsizlarsiz) — har otdel uchun takrorlanadi, ichida \
            <code>{nom}</code> <code>{naqd}</code> <code>{prixod:oy}</code> kabi maydonlar to'g'ridan-to'g'ri.
            Boshqa ro'yxatlar: <code>{#kartalar}</code> <code>{#kartalar:kassa=1}</code> \
            <code>{#eslatmalar}</code> <code>{#eslatmalar:otgan}</code> <code>{#eslatmalar:bugun}</code> \
            <code>{#qarzlar}</code> <code>{#xodimlar}</code> <code>{#xodimlar:rol=KASSIR}</code>

            Shart: <code>{?jami.karta_kiritilmagan}❗️ kiritilmagan bor{/?}</code> — qiymat nol/bo'sh \
            bo'lmasa ko'rinadi.

            <b>Misol:</b>
            <code>📊 {sana} — savdo
            {#kassalar}
            🏪 {nom}: bugun {prixod} · oyda {prixod:oy} so'm
            {/kassalar}
            Jami: {jami.prixod:oy|mln}  {xodimlar}</code>

            HTML ruxsat: &lt;b&gt; &lt;i&gt; &lt;code&gt;. Noma'lum o'rinbosar o'zgarishsiz qoladi va \
            saqlashda ⚠️ bilan ko'rsatiladi.
            """;
    private final TemplateData data;


    /** Render konteksti: chatId — {adminlar}/{xodimlar} uchun; kassaId — {kassa:mening…} uchun. */
    public record Ctx(Long chatId, Long kassaId) {
        public static Ctx of(Long chatId) { return new Ctx(chatId, null); }
    }


    public record Result(String text, Set<String> unknown, boolean msFailed) {}


    /* ====================================================================
     * Bir render davomida yashaydigan holat (kesh + bayroqlar)
     * ==================================================================== */
    final class Run {
        final Ctx ctx;
        final Set<String> unknown = new LinkedHashSet<>();
        boolean msFailed = false;
        final Map<String, Object> cache = new HashMap<>();
        Run(Ctx ctx) { this.ctx = ctx; }
    }


    /** Ro'yxat bloki ichidagi joriy element: tur (kassa/karta/eslatma/qarz/user) + ID. */
    private record Scope(String type, long id) {}


    public Result render(String template, Ctx ctx) {
        Run run = new Run(ctx == null ? new Ctx(null, null) : ctx);
        String out;
        try {
            out = renderPart(template == null ? "" : template, run, null);
        } catch (Exception e) {
            log.warn("Shablon render xatosi: {}", e.getMessage(), e);
            out = (template == null ? "" : template) + "\n\n⚠️ Shablon xatosi: " + TextUtil.esc(String.valueOf(e.getMessage()));
        }
        out = ID_MENTION.matcher(out).replaceAll("<a href=\"tg://user?id=$1\">$2</a>");
        if (run.msFailed) out += "\n\n⚠️ <i>MoySklad o'qilmadi — davr raqamlari bot ma'lumotidan</i>";
        return new Result(out.trim(), run.unknown, run.msFailed);
    }


    /** Sozlamalar oynasi uchun: shablondagi barcha o'rinbosarlar to'g'rimi (yuborilmaydi). */
    public Set<String> unknownPlaceholders(String template) {
        return render(template, new Ctx(null, null)).unknown();
    }


    private String renderPart(String tpl, Run run, Scope scope) {
        // 1) ro'yxat bloklari
        StringBuilder sb = new StringBuilder();
        Matcher m = LIST_BLOCK.matcher(tpl);
        int last = 0;
        while (m.find()) {
            sb.append(tpl, last, m.start());
            String name = m.group(1), args = m.group(2) == null ? "" : m.group(2), body = m.group(3);
            List<Scope> items = listItems(name, args, run);
            if (items == null) { run.unknown.add("{#" + name + "}"); sb.append(m.group()); }
            else for (Scope it : items) sb.append(renderPart(stripEdges(body), run, it)).append("\n");
            last = m.end();
        }
        sb.append(tpl.substring(last));
        tpl = sb.toString();

        // 2) shart bloklari
        sb = new StringBuilder();
        m = COND_BLOCK.matcher(tpl);
        last = 0;
        while (m.find()) {
            sb.append(tpl, last, m.start());
            Object v = resolve(m.group(1).trim(), run, scope);
            if (truthy(v)) sb.append(renderPart(m.group(2), run, scope));
            last = m.end();
        }
        sb.append(tpl.substring(last));
        tpl = sb.toString();

        // 3) oddiy o'rinbosarlar
        sb = new StringBuilder();
        m = PLACEHOLDER.matcher(tpl);
        last = 0;
        while (m.find()) {
            sb.append(tpl, last, m.start());
            String token = m.group(1).trim();
            if (token.startsWith("id=")) { sb.append(m.group()); last = m.end(); continue; }
            String fmt = null;
            int bar = token.lastIndexOf('|');
            if (bar > 0) { fmt = token.substring(bar + 1).trim(); token = token.substring(0, bar).trim(); }
            Object v = resolve(token, run, scope);
            if (v == null) { run.unknown.add("{" + m.group(1).trim() + "}"); sb.append(m.group()); }
            else sb.append(format(v, fmt));
            last = m.end();
        }
        sb.append(tpl.substring(last));
        return sb.toString();
    }


    private static String stripEdges(String body) {
        // blok ochilgan/yopilgan satrdagi bitta yangi qator olib tashlanadi
        if (body.startsWith("\n")) body = body.substring(1);
        if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
        return body;
    }


    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Long l) return l != 0;
        if (v instanceof Integer i) return i != 0;
        return !v.toString().isBlank();
    }


    /* ==================================================================== FORMAT */

    private String format(Object v, String fmt) {
        if (!(v instanceof Long tiyin)) return String.valueOf(v);
        if (fmt == null || fmt.isEmpty()) return TextUtil.fmt(Math.round(tiyin / 100.0));
        return switch (fmt) {
            case "tiyin" -> TextUtil.fmtTiyin(tiyin);
            case "+" -> (tiyin > 0 ? "+" : "") + TextUtil.fmt(Math.round(tiyin / 100.0));
            case "ming" -> TextUtil.fmt(Math.round(tiyin / 100_000.0)) + " ming";
            case "mln" -> String.format(Locale.ROOT, "%.2f mln", tiyin / 100_000_000.0)
                    .replace(".00 mln", " mln");
            case "son" -> String.valueOf(tiyin);   // formatlanmagan xom son
            default -> TextUtil.fmt(Math.round(tiyin / 100.0));
        };
    }


    /* ==================================================================== RESOLVE */

    /** Token: obj[:id.field][:modifier…] yoki obj.field[:modifier…]. Null — noma'lum. */
    private Object resolve(String token, Run run, Scope scope) {
        try {
            // Ro'yxat ichida: {nom} / {prixod:oy} → joriy element maydoni
            if (scope != null && token.matches("[a-z_]+(:[^.{}]*)?")) {
                String[] p = token.split(":", 2);
                Object v = objectField(scope.type(), scope.id(), p[0],
                        p.length > 1 ? p[1].split(":") : new String[0], run);
                if (v != null) return v;
            }
            String[] parts = token.split(":");
            String head = parts[0];
            // {jami.naqd:oy}, {bux.naqd}
            if (head.contains(".")) {
                int dot = head.indexOf('.');
                String obj = head.substring(0, dot), field = head.substring(dot + 1);
                String[] mods = Arrays.copyOfRange(parts, 1, parts.length);
                if (obj.equals("jami")) return data.jami(field, mods, run);
                if (obj.equals("bux")) return data.bux(field, mods, run);
                return null;
            }
            // {kassa:1.naqd:oy}, {karta:"Nom".qoldiq}, {user:5}, {rol:KASSIR}
            if (Set.of("kassa", "karta", "user", "eslatma", "qarz", "rol").contains(head) && parts.length >= 2) {
                // parts[1] — `ID.field` yoki `"Nom".field`; nom ichida ':' bo'lsa — birlashtiriladi
                String rest = String.join(":", Arrays.copyOfRange(parts, 1, parts.length));
                String ref, field; String[] mods;
                if (rest.startsWith("\"")) {
                    int q = rest.indexOf('"', 1);
                    if (q < 0) return null;
                    ref = rest.substring(1, q);
                    String tail = rest.substring(q + 1);
                    if (tail.startsWith(".")) tail = tail.substring(1);
                    String[] tp = tail.isEmpty() ? new String[0] : tail.split(":");
                    field = tp.length > 0 ? tp[0] : "";
                    mods = tp.length > 1 ? Arrays.copyOfRange(tp, 1, tp.length) : new String[0];
                } else {
                    String[] tp = rest.split(":");
                    int dot = tp[0].indexOf('.');
                    ref = dot < 0 ? tp[0] : tp[0].substring(0, dot);
                    field = dot < 0 ? "" : tp[0].substring(dot + 1);
                    mods = tp.length > 1 ? Arrays.copyOfRange(tp, 1, tp.length) : new String[0];
                }
                if (head.equals("rol")) return roleMentions(ref);
                Long id = findId(head, ref, run);
                if (id == null) return "❓" + TextUtil.esc(ref);
                return objectField(head, id, field, mods, run);
            }
            return global(head, run);
        } catch (Exception e) {
            log.debug("O'rinbosar {} xatosi: {}", token, e.getMessage());
            return null;
        }
    }


    /* -------------------- umumiy -------------------- */

    private Object global(String name, Run run) {
        ZoneId z = props.zoneId();
        LocalDateTime now = LocalDateTime.now(z);
        LocalDate today = now.toLocalDate();
        return switch (name) {
            case "sana" -> today.format(DF);
            case "vaqt" -> now.format(TF);
            case "yil" -> String.valueOf(today.getYear());
            case "kun_nomi" -> KUN[today.getDayOfWeek().getValue() - 1];
            case "oy_nomi" -> OY[today.getMonthValue() - 1];
            case "hafta_boshi" -> today.with(DayOfWeek.MONDAY).format(DF);
            case "hafta_oxiri" -> today.with(DayOfWeek.SUNDAY).format(DF);
            case "kecha" -> today.minusDays(1).format(DF);
            case "adminlar" -> chatAdmins(run);
            case "xodimlar" -> chatEmployees(run);
            case "hamma" -> chatEveryone(run);
            default -> null;
        };
    }


    private String chatAdmins(Run run) {
        if (run.ctx.chatId() == null) return "";
        StringBuilder m = new StringBuilder();
        for (var u : sender.chatAdmins(run.ctx.chatId())) {
            if (u.getUserName() != null && !u.getUserName().isBlank()) m.append("@").append(u.getUserName()).append(" ");
            else m.append(link(u.getId(), u.getFirstName()));
        }
        return m.toString().trim();
    }


    private String chatEmployees(Run run) {
        StringBuilder m = new StringBuilder();
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc()) {
            if (x.getTelegramId() == null) continue;
            if (run.ctx.chatId() != null && run.ctx.chatId() < 0) {
                String st = sender.memberStatus(run.ctx.chatId(), x.getTelegramId());
                if (!"member".equals(st) && !"administrator".equals(st) && !"creator".equals(st)) continue;
            }
            m.append(link(x.getTelegramId(), x.getFullName()));
        }
        return m.toString().trim();
    }


    private String chatEveryone(Run run) {
        if (run.ctx.chatId() == null || run.ctx.chatId() >= 0) return chatEmployees(run);
        StringBuilder m = new StringBuilder();
        var members = groupMemberRepo.findByChatIdOrderByIdAsc(run.ctx.chatId());
        int shown = 0;
        for (GroupMember gm : members) {
            if (shown++ >= 30) break;
            if (gm.getUsername() != null && !gm.getUsername().isBlank()) m.append("@").append(gm.getUsername()).append(" ");
            else m.append(link(gm.getUserId(), gm.getFirstName() == null ? "user" : gm.getFirstName()));
        }
        if (members.size() > 30) m.append("+").append(members.size() - 30).append(" boshqa");
        return m.toString().trim();
    }


    static String link(Long tgId, String name) {
        return "<a href=\"tg://user?id=" + tgId + "\">" + TextUtil.esc(name == null ? "user" : name) + "</a> ";
    }


    private String roleMentions(String role) {
        Role r;
        try { r = Role.valueOf(role.trim().toUpperCase()); } catch (Exception e) { return null; }
        StringBuilder m = new StringBuilder();
        for (AppUser x : userRepo.findByRoleAndActiveTrue(r))
            if (x.getTelegramId() != null) m.append(link(x.getTelegramId(), x.getFullName()));
        return m.toString().trim();
    }


    /* -------------------- ID topish (raqam / nom / mening) -------------------- */

    private Long findId(String type, String refRaw, Run run) {
        final String ref = refRaw.trim();
        if (type.equals("kassa") && ref.equalsIgnoreCase("mening")) return run.ctx.kassaId();
        if (ref.matches("\\d+")) return Long.parseLong(ref);
        final String lc = ref.toLowerCase();
        return switch (type) {
            case "kassa" -> kassaRepo.findAll().stream()
                    .filter(k -> k.getName().equalsIgnoreCase(ref)
                            || k.getName().toLowerCase().contains(lc)).map(Kassa::getId).findFirst().orElse(null);
            case "karta" -> clickRepo.findAll().stream()
                    .filter(c -> c.getName().equalsIgnoreCase(ref)
                            || c.getName().toLowerCase().contains(lc)).map(ClickAccount::getId).findFirst().orElse(null);
            case "user" -> userRepo.findAll().stream()
                    .filter(u -> u.getFullName() != null && (u.getFullName().equalsIgnoreCase(ref)
                            || u.getFullName().toLowerCase().contains(lc))).map(AppUser::getId).findFirst().orElse(null);
            case "eslatma" -> reminderRepo.findByStatusOrderByDueDateAscIdAsc(Reminder.Status.FAOL).stream()
                    .filter(r -> r.getAgentName().toLowerCase().contains(lc)).map(Reminder::getId).findFirst().orElse(null);
            default -> null;
        };
    }


    /* -------------------- ob'ekt maydonlari -------------------- */

    private Object objectField(String type, long id, String field, String[] mods, Run run) {
        return switch (type) {
            case "kassa" -> data.kassaField(id, field, mods, run);
            case "karta" -> data.kartaField(id, field, run);
            case "user" -> data.userField(id, field);
            case "eslatma" -> data.eslatmaField(id, field);
            case "qarz" -> data.qarzField(id, field);
            default -> null;
        };
    }


    /* -------------------- ro'yxatlar -------------------- */

    private List<Scope> listItems(String name, String args, Run run) {
        Map<String, String> a = new HashMap<>();
        String flag = "";
        for (String p : args.split(":")) {
            p = p.trim();
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            if (eq > 0) a.put(p.substring(0, eq), p.substring(eq + 1)); else flag = p;
        }
        List<Scope> out = new ArrayList<>();
        switch (name) {
            case "kassalar" -> {
                for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
                    if (flag.equals("naqdli") && k.isCashless()) continue;   // faqat naqd otdellar
                    out.add(new Scope("kassa", k.getId()));
                }
            }
            case "kartalar" -> {
                Long kid = a.containsKey("kassa") ? findId("kassa", a.get("kassa"), run) : null;
                for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc())
                    if (kid == null || kid.equals(c.getKassaId())) out.add(new Scope("karta", c.getId()));
            }
            case "eslatmalar" -> {
                LocalDate today = LocalDate.now(props.zoneId());
                for (Reminder r : reminderRepo.findByStatusOrderByDueDateAscIdAsc(Reminder.Status.FAOL)) {
                    long left = ChronoUnit.DAYS.between(today, r.getDueDate());
                    boolean ok = switch (flag) {
                        case "otgan" -> left < 0;
                        case "bugun" -> left == 0;
                        case "hafta" -> left >= 0 && left <= 7;
                        default -> true;
                    };
                    if (ok) out.add(new Scope("eslatma", r.getId()));
                }
            }
            case "qarzlar" -> {
                for (Debt d : debtRepo.findByStatusOrderByIdAsc(DebtStatus.OCHIQ)) out.add(new Scope("qarz", d.getId()));
            }
            case "xodimlar" -> {
                Role role = null;
                if (a.containsKey("rol")) try { role = Role.valueOf(a.get("rol").toUpperCase()); } catch (Exception ignored) { }
                Long kid = a.containsKey("kassa") ? findId("kassa", a.get("kassa"), run) : null;
                for (AppUser u : userRepo.findByActiveTrueOrderByRoleAscIdAsc()) {
                    if (role != null && u.getRole() != role) continue;
                    if (kid != null && !kid.equals(u.getKassaId())) continue;
                    out.add(new Scope("user", u.getId()));
                }
            }
            default -> { return null; }
        }
        return out;
    }

}
