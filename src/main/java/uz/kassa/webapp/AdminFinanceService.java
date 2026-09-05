package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uz.kassa.bot.NameService;
import uz.kassa.bot.TextUtil;
import uz.kassa.config.AppProps;
import uz.kassa.domain.*;
import uz.kassa.repo.ClickAccountRepo;
import uz.kassa.repo.DayRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.service.*;
import uz.kassa.service.moysklad.MoySkladSyncService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static uz.kassa.webapp.AdminApiService.mapOf;

/**
 * 💼 Молия (Mini App, faqat SuperAdmin): boshlang'ich qoldiq, korrektirovka
 * (Buxgalteriya / kassa / Click hisobi), ledger sanasi, nol boshlash.
 * BalanceAdminHandler / KassaAdminHandler.rz* / MoySkladAdminHandler.ledger* bilan bir xil qoidalar.
 */
@Service
@RequiredArgsConstructor
public class AdminFinanceService {

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final LedgerService ledger;
    private final KassaRepo kassaRepo;
    private final ClickAccountRepo clickRepo;
    private final DayRepo dayRepo;
    private final NameService names;
    private final NotificationService notify;
    private final AuditService audit;
    private final SettingsService settings;
    private final AppProps props;
    private final MoySkladSyncService syncService;

    /** Egalar ro'yxati: B (Buxgalteriya), K<id> (kassa), C<id> (Click hisobi) + joriy balanslar. */
    public List<Map<String, Object>> owners() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(owner("B", "🏦 Отдел основной", OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, true));
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            out.add(owner("K" + k.getId(), "🏪 " + k.getName(), OwnerType.KASSA, k.getId(), true));
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc())
            out.add(owner("C" + c.getId(), "📲 " + c.getName(), OwnerType.CLICK, c.getId(), false));
        return out;
    }

    private Map<String, Object> owner(String code, String name, OwnerType ot, Long oid, boolean hasNaqd) {
        return mapOf("code", code, "name", name,
                "naqd", hasNaqd ? ledger.view(ot, oid, MoneyType.NAQD).getAmount() : null,
                "klik", ledger.view(ot, oid, MoneyType.KLIK).getAmount());
    }

    private record Owner(OwnerType ot, Long oid) {}

    private Owner parse(String code) {
        if (code == null || code.isBlank()) throw new BusinessException("Эга танланмаган");
        if (code.equals("B")) return new Owner(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID);
        try {
            long id = Long.parseLong(code.substring(1));
            if (code.startsWith("K")) { kassaRepo.findById(id).orElseThrow(); return new Owner(OwnerType.KASSA, id); }
            if (code.startsWith("C")) { clickRepo.findById(id).orElseThrow(); return new Owner(OwnerType.CLICK, id); }
        } catch (BusinessException e) { throw e; } catch (Exception ignored) { /* pastda */ }
        throw new BusinessException("Эга топилмади: " + code);
    }

    private LocalDate parseDate(String s) {
        LocalDate d;
        try { d = s == null || s.isBlank() ? ledger.today() : LocalDate.parse(s); }
        catch (Exception e) { throw new BusinessException("Сана нотўғри"); }
        if (d.isAfter(ledger.today())) throw new BusinessException("Келажак сана бўлмайди");
        return d;
    }

    /* ---------------- 💼 Boshlang'ich qoldiq ---------------- */

    public record InitReq(String owner, Long naqd, Long klik, String date) {}

    public Map<String, Object> initBalance(AppUser by, InitReq r) {
        Owner o = parse(r.owner());
        long naqd = r.naqd() == null ? 0 : r.naqd(), klik = r.klik() == null ? 0 : r.klik();
        if (naqd <= 0 && klik <= 0) throw new BusinessException("Камида битта сумма 0 дан катта бўлсин");
        if (o.ot() == OwnerType.CLICK && naqd > 0) throw new BusinessException("Click ҳисобида нақд юритилмайди");
        LocalDate date = parseDate(r.date());
        if (naqd > 0) ledger.postAdjustment(OpType.BOSHLANGICH, o.ot(), o.oid(), MoneyType.NAQD, naqd, "Boshlang'ich qoldiq", by.getId(), date);
        if (klik > 0) ledger.postAdjustment(OpType.BOSHLANGICH, o.ot(), o.oid(), MoneyType.KLIK, klik, "Boshlang'ich qoldiq", by.getId(), date);
        audit.log(by.getId(), "BOSHLANGICH", "balance", o.oid(), by.getFullName() + " (web) " + o.ot() + ":" + o.oid()
                + " naqd=" + naqd + " klik=" + klik + " " + date);
        return mapOf("owner", names.owner(o.ot(), o.oid()), "naqd", naqd, "klik", klik, "date", date.toString());
    }

    /* ---------------- 🛠 Korrektirovka (istalgan ega) ---------------- */

    public record AdjustReq(String owner, String mt, Long amount, Long target, String reason, String date) {}

    public Map<String, Object> adjust(AppUser by, AdjustReq r) {
        Owner o = parse(r.owner());
        MoneyType mt;
        try { mt = MoneyType.valueOf(r.mt()); } catch (Exception e) { throw new BusinessException("Пул тури нотўғри"); }
        if (mt == MoneyType.TERMINAL) throw new BusinessException("Терминал балансда юритилмайди");
        if (o.ot() == OwnerType.CLICK && mt != MoneyType.KLIK) throw new BusinessException("Click ҳисобида фақат click");
        String reasonBase = r.reason() == null ? "" : r.reason().trim();
        if (reasonBase.length() < 3) throw new BusinessException("Сабабни ёзинг (камида 3 белги)");
        LocalDate date = parseDate(r.date());
        boolean past = date.isBefore(ledger.today());
        long cur = ledger.view(o.ot(), o.oid(), mt).getAmount();
        long asOf = past ? ledger.balanceAsOf(o.ot(), o.oid(), mt, date) : cur;
        long sum = r.target() != null ? r.target() - asOf : (r.amount() == null ? 0 : r.amount());
        if (sum == 0) throw new BusinessException("Баланс аллақачон керакли қийматда — тузатиш ёзилмади");
        String reason = reasonBase + " [" + date.format(DF) + "]";
        Operation op = ledger.postAdjustment(OpType.KORREKTIROVKA, o.ot(), o.oid(), mt, sum, reason, by.getId(), date);
        long after = ledger.view(o.ot(), o.oid(), mt).getAmount();
        String owner = names.owner(o.ot(), o.oid());
        // audit: LedgerService.postAdjustment o'zi KORREKTIROVKA yozuvini yozadi (takror yozilmasin)
        String info = "🛠 Korrektirovka — <b>" + TextUtil.esc(owner) + "</b>: <b>" + (sum > 0 ? "+" : "") + TextUtil.fmt(sum)
                + "</b> so'm (" + (mt == MoneyType.NAQD ? "💵 Naqd" : "📲 Click") + ")\n📅 Sana: <b>" + date.format(DF) + "</b>\n"
                + (past ? "📆 " + date.format(DF) + " kun oxiri endi: <b>" + TextUtil.fmt(asOf + sum) + "</b> so'm\n" : "")
                + "Hozirgi balans: <b>" + TextUtil.fmt(after) + "</b> so'm\nSabab: " + TextUtil.esc(reason) + "\nKim: " + TextUtil.esc(by.getFullName());
        notify.toBuxgalteriya(info, null);
        if (o.ot() == OwnerType.KASSA) notify.toKassa(o.oid(), info, null);
        return mapOf("opId", op.getId(), "sum", sum, "after", after, "owner", owner);
    }

    /* ---------------- 📅 Ledger sanasi ---------------- */

    public Map<String, Object> ledgerDate() {
        String override = settings.get(MoySkladSyncService.LEDGER_START_KEY).orElse("").trim();
        String env = props.getMoysklad().getLedgerStartDate();
        LocalDate eff = syncService.effectiveEpoch();
        return mapOf("effective", eff.equals(LocalDate.MIN) ? "" : eff.toString(),
                "source", !override.isBlank() ? "bot" : (env != null && !env.isBlank() ? "env" : ""),
                "override", override, "env", nz(env));
    }

    /** date bo'sh — .env qiymatiga qaytarish. */
    public Map<String, Object> setLedgerDate(AppUser by, String date) {
        if (date == null || date.isBlank()) {
            settings.set(MoySkladSyncService.LEDGER_START_KEY, "");
            audit.log(by.getId(), "LEDGER_SANA", "settings", null, by.getFullName() + " ledger sanasini .env ga qaytardi (web)");
        } else {
            LocalDate d = parseDate(date);
            settings.set(MoySkladSyncService.LEDGER_START_KEY, d.toString());
            audit.log(by.getId(), "LEDGER_SANA", "settings", null, by.getFullName() + " ledger sanasi (web): " + d);
        }
        return ledgerDate();
    }

    /* ---------------- ♻️ Nol boshlash ---------------- */

    private List<Kassa> rzTargets(String arg) {
        if ("all".equals(arg)) return kassaRepo.findByActiveTrueOrderByIdAsc().stream().filter(k -> !k.isCashless()).toList();
        try { return kassaRepo.findById(Long.parseLong(arg)).map(List::of).orElse(List.of()); }
        catch (Exception e) { return List.of(); }
    }

    /** Oldindan ko'rish: nima yopiladi. arg: "all" yoki kassa id. */
    public Map<String, Object> zeroPreview(String arg) {
        LocalDate today = ledger.today();
        List<Map<String, Object>> rows = new ArrayList<>();
        long tn = 0, tk = 0; int td = 0;
        for (Kassa k : rzTargets(arg)) {
            long n = 0, kl = 0; int c = 0;
            for (DayRecord d : dayRepo.findByKassaIdAndStatusInOrderByDateAsc(k.getId(), List.of(DayStatus.OCHIQ, DayStatus.YOPILGAN)))
                if (d.getDate().isBefore(today)) { n += d.remainNaqd(); kl += d.remainKlik(); c++; }
            tn += n; tk += kl; td += c;
            rows.add(mapOf("id", k.getId(), "name", k.getName(), "days", c, "naqd", n, "klik", kl));
        }
        return mapOf("today", today.toString(), "rows", rows, "days", td, "naqd", tn, "klik", tk);
    }

    public Map<String, Object> zeroCommit(AppUser by, String arg) {
        List<Kassa> targets = rzTargets(arg);
        if (targets.isEmpty()) throw new BusinessException("Касса топилмади");
        LocalDate today = ledger.today();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Kassa k : targets) {
            long[] r = ledger.resetKassaBefore(k.getId(), today, by.getId());
            long newN = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.NAQD).getAmount();
            long newK = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.KLIK).getAmount();
            rows.add(mapOf("name", k.getName(), "days", r[2], "naqd", r[0], "klik", r[1], "newNaqd", newN, "newKlik", newK));
            notify.toKassa(k.getId(), "♻️ <b>Kassangiz yangi hisobni boshladi</b>\n" + today.format(DF)
                    + " dan oldingi kunlar yopildi.\nJoriy balans: Naqd <b>" + TextUtil.fmt(newN) + "</b> · Click <b>" + TextUtil.fmt(newK) + "</b> so'm", null);
        }
        audit.log(by.getId(), "NOL_BOSHLASH", "kassa", null, by.getFullName() + " (web): " + arg);
        notify.toBuxgalteriya("♻️ Нол бошлаш — " + TextUtil.esc(by.getFullName()) + " kassalarni yangi hisobga o'tkazdi ("
                + today.format(DF) + " dan oldingi kunlar yopildi).", null);
        return mapOf("rows", rows);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
