package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uz.kassa.bot.NameService;
import uz.kassa.bot.Sender;
import uz.kassa.bot.TextUtil;
import uz.kassa.config.AppProps;
import uz.kassa.domain.*;
import uz.kassa.repo.*;
import uz.kassa.service.AuditService;
import uz.kassa.service.BusinessException;
import uz.kassa.service.LedgerService;
import uz.kassa.service.moysklad.MoySkladClient;
import uz.kassa.service.moysklad.MoySkladSyncService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static uz.kassa.webapp.AdminApiService.mapOf;

/**
 * 🔗 MoySklad va 📋 Аудит (Mini App, faqat SuperAdmin): API kaliti, nomlar
 * (MoySklad'dan yangilash / qo'lda / qulf), diagnostika (minuslar, takror raqamlar),
 * to'liq qayta yuklash, audit jurnali. MoySkladAdminHandler / MoySkladNamesHandler /
 * StatsHandler.audit* bilan bir xil qoidalar.
 */
@Service
@RequiredArgsConstructor
public class AdminMoySkladService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final MoySkladClient msClient;
    private final MoySkladSyncService syncService;
    private final KassaRepo kassaRepo;
    private final ClickAccountRepo clickRepo;
    private final AppUserRepo userRepo;
    private final DayRepo dayRepo;
    private final OperationRepo opRepo;
    private final AuditRepo auditRepo;
    private final LedgerService ledger;
    private final NameService names;
    private final AuditService audit;
    private final Sender sender;
    private final ExcelReportService excel;
    private final AppProps props;

    /* ---------------- 🔑 Token ---------------- */

    public Map<String, Object> token() {
        String t = msClient.currentToken();
        boolean ok = !t.isBlank() && msClient.testToken(t);
        String masked = t.isBlank() ? "" : t.length() > 12 ? t.substring(0, 6) + "…" + t.substring(t.length() - 4) : "•••";
        long l403 = msClient.last403At();
        return mapOf("masked", masked, "ok", ok,
                "last403", l403 == 0 ? "" : DT.format(java.time.Instant.ofEpochMilli(l403).atZone(props.zoneId())),
                "last403Url", nz(msClient.last403Url()));
    }

    public Map<String, Object> setToken(AppUser by, String token) {
        String t = token == null ? "" : token.trim();
        if (t.length() < 20 || t.contains(" ")) throw new BusinessException("Бу MoySklad калитига ўхшамайди (қисқа ёки бўш жой бор)");
        boolean ok = msClient.testToken(t);
        msClient.updateToken(t);
        audit.log(by.getId(), "MS_TOKEN_YANGILANDI", "settings", null, "yangi kalit (web): " + t.substring(0, 6) + "… (test: " + (ok ? "OK" : "XATO") + ")");
        if (ok) new Thread(syncService::sync, "web-ms-sync").start();
        return mapOf("ok", ok);
    }

    /* ---------------- 🔄 Nomlar ---------------- */

    public Map<String, Object> names() {
        Map<String, String> groups = safe(msClient::fetchGroups), accounts = safe(msClient::fetchAccounts);
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> diffs = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            String g = k.getMoyskladGroupId();
            String ms = g == null ? null : groups.get(g);
            items.add(mapOf("key", "k" + k.getId(), "kind", "kassa", "name", k.getName(), "locked", k.isNameLocked(),
                    "link", g == null || g.isBlank() ? "" : groups.getOrDefault(g, g), "msName", nz(ms)));
            if (!k.isNameLocked() && ms != null && !ms.isBlank() && !ms.trim().equals(k.getName()))
                diffs.add(mapOf("key", "k" + k.getId(), "from", k.getName(), "to", ms.trim()));
        }
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
            String a = c.getMoyskladAccountId();
            String ms = a == null ? null : accounts.get(a);
            items.add(mapOf("key", "c" + c.getId(), "kind", "click", "name", c.getName(), "locked", c.isNameLocked(),
                    "link", c.getKassaId() == null ? "" : names.owner(OwnerType.KASSA, c.getKassaId()), "msName", nz(ms)));
            if (!c.isNameLocked() && ms != null && !ms.isBlank() && !ms.trim().equals(c.getName()))
                diffs.add(mapOf("key", "c" + c.getId(), "from", c.getName(), "to", ms.trim()));
        }
        return mapOf("items", items, "diffs", diffs, "msOk", !(groups.isEmpty() && accounts.isEmpty()));
    }

    private static <T> Map<String, T> safe(java.util.function.Supplier<? extends Map<String, T>> s) {
        try { return s.get(); } catch (Exception e) { return Map.of(); }
    }

    /** MoySklad'dan yangilash (qulflanmaganlar). */
    public int applyNames(AppUser by) {
        Map<String, String> groups = safe(msClient::fetchGroups), accounts = safe(msClient::fetchAccounts);
        int n = 0;
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isNameLocked() || k.getMoyskladGroupId() == null) continue;
            String nn = groups.get(k.getMoyskladGroupId());
            if (nn == null || nn.isBlank() || nn.trim().equals(k.getName())) continue;
            k.setName(nn.trim()); kassaRepo.save(k); n++;
        }
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
            if (c.isNameLocked() || c.getMoyskladAccountId() == null) continue;
            String nn = accounts.get(c.getMoyskladAccountId());
            if (nn == null || nn.isBlank() || nn.trim().equals(c.getName())) continue;
            c.setName(nn.trim()); clickRepo.save(c); n++;
        }
        if (n > 0) audit.log(by.getId(), "NOMLAR_YANGILANDI", "settings", null, by.getFullName() + " (web) nomlarni MoySklad'dan yangiladi (" + n + " ta)");
        return n;
    }

    /** Qo'lda nom (🔒). name bo'sh — qulfni ochib MoySklad nomini qaytarish. */
    public Map<String, Object> rename(AppUser by, String key, String name) {
        boolean isKassa = key.startsWith("k");
        long id;
        try { id = Long.parseLong(key.substring(1)); } catch (Exception e) { throw new BusinessException("Калит нотўғри"); }
        String nn = name == null ? "" : name.trim();
        if (!nn.isEmpty() && (nn.length() < 2 || nn.length() > 40)) throw new BusinessException("Ном 2–40 белги");
        String old, result;
        if (isKassa) {
            Kassa k = kassaRepo.findById(id).orElseThrow(() -> new BusinessException("Касса топилмади"));
            old = k.getName();
            if (nn.isEmpty()) {
                k.setNameLocked(false);
                String ms = k.getMoyskladGroupId() == null ? null : safe(msClient::fetchGroups).get(k.getMoyskladGroupId());
                if (ms != null && !ms.isBlank()) k.setName(ms.trim());
            } else { k.setName(nn); k.setNameLocked(true); }
            kassaRepo.save(k); result = k.getName();
        } else {
            ClickAccount c = clickRepo.findById(id).orElseThrow(() -> new BusinessException("Карта топилмади"));
            old = c.getName();
            if (nn.isEmpty()) {
                c.setNameLocked(false);
                String ms = c.getMoyskladAccountId() == null ? null : safe(msClient::fetchAccounts).get(c.getMoyskladAccountId());
                if (ms != null && !ms.isBlank()) c.setName(ms.trim());
            } else { c.setName(nn); c.setNameLocked(true); }
            clickRepo.save(c); result = c.getName();
        }
        audit.log(by.getId(), nn.isEmpty() ? "NOM_QULF_OCHILDI" : "NOM_QOLDA", isKassa ? "kassa" : "click", id,
                by.getFullName() + " (web): «" + old + "» → «" + result + "»");
        return mapOf("name", result);
    }

    /* ---------------- 🩺 Diagnostika ---------------- */

    public Map<String, Object> diag() {
        List<Map<String, Object>> minus = new ArrayList<>();
        long bn = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD).getAmount();
        long bk = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.KLIK).getAmount();
        if (bn < 0) minus.add(mapOf("owner", "B", "name", "🏦 Отдел основной", "mt", "NAQD", "amount", bn));
        if (bk < 0) minus.add(mapOf("owner", "B", "name", "🏦 Отдел основной", "mt", "KLIK", "amount", bk));
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            long n = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.NAQD).getAmount();
            long kl = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.KLIK).getAmount();
            if (n < 0) minus.add(mapOf("owner", "K" + k.getId(), "name", "🏪 " + k.getName(), "mt", "NAQD", "amount", n));
            if (kl < 0) minus.add(mapOf("owner", "K" + k.getId(), "name", "🏪 " + k.getName(), "mt", "KLIK", "amount", kl));
        }
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
            long v = ledger.view(OwnerType.CLICK, c.getId(), MoneyType.KLIK).getAmount();
            if (v < 0) minus.add(mapOf("owner", "C" + c.getId(), "name", "📲 " + c.getName(), "mt", "KLIK", "amount", v));
        }
        List<Map<String, Object>> days = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            for (DayRecord d : dayRepo.findByKassaIdAndStatusInOrderByDateAsc(k.getId(), List.of(DayStatus.OCHIQ, DayStatus.YOPILGAN))) {
                if (d.remainNaqd() < 0) days.add(mapOf("kassa", k.getName(), "date", d.getDate().toString(), "mt", "NAQD", "amount", d.remainNaqd()));
                if (d.remainKlik() < 0) days.add(mapOf("kassa", k.getName(), "date", d.getDate().toString(), "mt", "KLIK", "amount", d.remainKlik()));
            }
        Map<String, List<String>> byPhone = new LinkedHashMap<>();
        for (AppUser x : userRepo.findAll()) {
            String np = TextUtil.normPhone(x.getPhone());
            if (np.isEmpty()) continue;
            byPhone.computeIfAbsent(np, z -> new ArrayList<>()).add(x.getFullName() + (x.isActive() ? "" : " (нофаол)"));
        }
        List<Map<String, Object>> dups = new ArrayList<>();
        for (var e : byPhone.entrySet()) if (e.getValue().size() > 1) dups.add(mapOf("phone", e.getKey(), "users", e.getValue()));
        return mapOf("minus", minus, "days", days, "dups", dups, "ok", minus.isEmpty() && days.isEmpty() && dups.isEmpty());
    }

    /* ---------------- 📥 Qayta yuklash ---------------- */

    public Map<String, Object> reloadInfo() {
        LocalDate ep = syncService.effectiveEpoch();
        return mapOf("epoch", ep.equals(LocalDate.MIN) ? "" : ep.toString(), "ops", opRepo.count());
    }

    /** Fonda ishlaydi; natija foydalanuvchi chatiga boradi (bot bilan bir xil). */
    public void reload(AppUser by) {
        if (syncService.effectiveEpoch().equals(LocalDate.MIN)) throw new BusinessException("Аввал 📅 Ledger санасини белгиланг");
        if (by.getTelegramId() == null) throw new BusinessException("Telegram уланмаган");
        long chat = by.getTelegramId();
        audit.log(by.getId(), "QAYTA_YUKLASH", "settings", null, by.getFullName() + " (web) to'liq tozalash + qayta yuklash");
        sender.send(chat, "⏳ Tozalanmoqda va MoySklad'dan qayta yuklanmoqda... tugagach xabar keladi.");
        new Thread(() -> {
            try {
                int n = syncService.fullReload();
                sender.send(chat, n == -1 ? "⚠️ Ledger sanasi belgilanmagan — hech narsa o'chirilmadi."
                        : n == -2 ? "⚠️ MoySklad tokeni ishlamayapti — hech narsa o'chirilmadi."
                        : "✅ <b>Қайта юклаш tugadi.</b> MoySklad'dan <b>" + n + "</b> ta hujjat yuklandi.\n"
                          + "❗️ Naqd qoldiqlarni 💼 Бошланғич қолдиқ yoki 🛠 Корректировка orqali kiriting.");
            } catch (Exception e) { sender.send(chat, "⚠️ Qayta yuklash xatosi: " + TextUtil.esc(e.getMessage())); }
        }, "web-reload").start();
    }

    /* ---------------- 📋 Audit ---------------- */

    public Map<String, Object> auditList(long userId, int limit) {
        List<AuditLog> logs = userId == 0 ? auditRepo.findTop5000ByOrderByIdDesc() : auditRepo.findTop5000ByUserIdOrderByIdDesc(userId);
        Map<Long, String> cache = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        ZoneId z = props.zoneId();
        for (AuditLog a : logs) {
            if (out.size() >= Math.max(10, Math.min(500, limit))) break;
            String un = a.getUserId() == null ? "тизим" : cache.computeIfAbsent(a.getUserId(), id -> userRepo.findById(id).map(AppUser::getFullName).orElse("#" + id));
            out.add(mapOf("id", a.getId(), "at", DT.format(a.getCreatedAt().atZone(z)), "user", un, "action", a.getAction(),
                    "entity", nz(a.getEntity()) + (a.getEntityId() == null ? "" : "#" + a.getEntityId()), "payload", nz(a.getPayload())));
        }
        List<Map<String, Object>> users = new ArrayList<>();
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc()) users.add(mapOf("id", x.getId(), "name", x.getFullName()));
        return mapOf("rows", out, "total", logs.size(), "users", users);
    }

    public void auditExcel(AppUser by, long userId) {
        if (by.getTelegramId() == null) throw new BusinessException("Telegram уланмаган");
        long chat = by.getTelegramId();
        new Thread(() -> {
            try {
                List<AuditLog> logs = userId == 0 ? auditRepo.findTop5000ByOrderByIdDesc() : auditRepo.findTop5000ByUserIdOrderByIdDesc(userId);
                byte[] xlsx = excel.buildAudit(logs, id -> userRepo.findById(id).map(AppUser::getFullName).orElse("#" + id), props.zoneId());
                sender.sendDocument(chat, xlsx, "audit_" + (userId == 0 ? "hammasi" : "user" + userId) + "_" + ledger.today() + ".xlsx",
                        "📋 Audit jurnali (oxirgi " + logs.size() + " yozuv)");
            } catch (Exception e) { sender.send(chat, "⚠️ Excel xatosi: " + TextUtil.esc(e.getMessage())); }
        }, "web-audit-excel").start();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
