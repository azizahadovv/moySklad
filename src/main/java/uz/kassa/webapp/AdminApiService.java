package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.domain.*;
import uz.kassa.repo.*;
import uz.kassa.service.BusinessException;
import uz.kassa.service.DailyReportService;
import uz.kassa.service.LedgerService;
import uz.kassa.service.SubmissionService;
import uz.kassa.service.moysklad.MoySkladClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 🌐 Админ панел (Mini App) uchun ma'lumot yig'uvchi: dashboard, kassa kartasi,
 * kutilayotgan hisobot, Click kartalari. Hisob-kitob mavjud servislardan olinadi
 * (LedgerService, DailyReportService, SubmissionService) — bot bilan bir xil raqamlar.
 * MoySklad'ga boradigan ikkita og'ir so'rov (karta qoldiqlari, kunlik savdo) qisqa
 * muddat keshlanadi, chunki dashboard har ochilganda so'raladi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminApiService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Tashkent");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final KassaRepo kassaRepo;
    private final DayRepo dayRepo;
    private final SubmissionRepo subRepo;
    private final AppUserRepo userRepo;
    private final ClickAccountRepo clickRepo;
    private final OperationRepo opRepo;
    private final LedgerService ledger;
    private final SubmissionService submissionService;
    private final DailyReportService dailyReport;
    private final MoySkladClient msClient;

    /* ---------------- keshlar (og'ir MoySklad so'rovlari) ---------------- */

    private record Cached<T>(T value, long at, boolean ok) {}
    private volatile Cached<Map<String, Long>> msBalances;
    private volatile Cached<List<DailyReportService.Row>> dailyRows;
    private volatile LocalDate dailyRowsDate;

    /** MoySklad karta qoldiqlari (tiyin), 60 s kesh. ok=false — o'qilmadi, bo'sh map. */
    private Cached<Map<String, Long>> msBalances() {
        Cached<Map<String, Long>> c = msBalances;
        if (c != null && System.currentTimeMillis() - c.at() < 60_000) return c;
        try { c = new Cached<>(msClient.fetchAccountBalancesTiyin(), System.currentTimeMillis(), true); }
        catch (Exception e) {
            log.warn("Admin panel: MoySklad karta qoldiqlari o'qilmadi: {}", e.getMessage());
            c = new Cached<>(Map.of(), System.currentTimeMillis(), false);
        }
        msBalances = c;
        return c;
    }

    /** Kunlik solishtirish qatorlari (bugun), 120 s kesh. */
    private Cached<List<DailyReportService.Row>> dailyRows(LocalDate d) {
        Cached<List<DailyReportService.Row>> c = dailyRows;
        if (c != null && d.equals(dailyRowsDate) && System.currentTimeMillis() - c.at() < 120_000) return c;
        try {
            List<DailyReportService.Row> rows = dailyReport.rows(d);
            boolean ok = rows.stream().allMatch(DailyReportService.Row::msKnown);
            c = new Cached<>(rows, System.currentTimeMillis(), ok);
        } catch (Exception e) {
            log.warn("Admin panel: kunlik qatorlar o'qilmadi: {}", e.getMessage());
            c = new Cached<>(List.of(), System.currentTimeMillis(), false);
        }
        dailyRows = c;
        dailyRowsDate = d;
        return c;
    }

    /* ---------------- 🏠 Бугун ---------------- */

    public Map<String, Object> dashboard() {
        LocalDate today = ledger.today();
        Cached<List<DailyReportService.Row>> daily = dailyRows(today);
        Cached<Map<String, Long>> ms = msBalances();
        List<Submission> pending = subRepo.findByStatusOrderByIdAsc(SubmissionStatus.KUTILMOQDA);

        long naqd = 0, klik = 0;
        List<Map<String, Object>> kassalar = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            Balance n = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.NAQD);
            Balance kl = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.KLIK);
            naqd += n.getAmount();
            klik += kl.getAmount();
            DailyReportService.Row r = rowOf(daily.value(), k.getId());
            long pend = pending.stream().filter(s -> k.getId().equals(s.getKassaId())).count();
            kassalar.add(mapOf(
                    "id", k.getId(), "name", k.getName(), "label", nz(k.getShopLabel()),
                    "cashless", k.isCashless(),
                    "naqd", n.getAmount(), "naqdBand", n.getReserved(),
                    "klik", kl.getAmount(), "klikBand", kl.getReserved(),
                    "openDays", submissionService.submittableDays(k.getId()).size(),
                    "pending", pend,
                    "savdoMs", r == null ? 0 : r.msSavdo(), "savdoBot", r == null ? 0 : r.botSavdo(),
                    "farq", r == null ? 0 : r.farq(), "msKnown", r != null && r.msKnown(),
                    "karta", cardSummary(k.getId(), ms)));
        }
        Balance bn = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD);
        Balance bk = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.KLIK);

        return mapOf(
                "asOf", DT.format(Instant.now().atZone(ZONE)),
                "today", today.toString(),
                "naqdJami", naqd, "klikJami", klik,
                "buxNaqd", bn.getAmount(), "buxKlik", bk.getAmount(),
                "msKnown", ms.ok(), "dailyKnown", daily.ok(),
                "pending", pending.stream().map(this::pendingBrief).toList(),
                "karta", cardSummary(null, ms),
                "kassalar", kassalar);
    }

    /** Kartalar xulosasi: teng / farq / kiritilmagan (kassaId=null — hammasi). */
    private Map<String, Object> cardSummary(Long kassaId, Cached<Map<String, Long>> ms) {
        int teng = 0, farq = 0, yoq = 0;
        long msJami = 0, kartaJami = 0;
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
            if (kassaId != null && !kassaId.equals(c.getKassaId())) continue;
            long m = msTiyin(c, ms);
            msJami += m;
            if (c.getCardBalance() == null) { yoq++; continue; }
            kartaJami += c.getCardBalance();
            if (m - c.getCardBalance() == 0) teng++; else farq++;
        }
        return mapOf("teng", teng, "farq", farq, "kiritilmagan", yoq,
                "msTiyin", msJami, "kartaTiyin", kartaJami, "soni", teng + farq + yoq);
    }

    /* ---------------- 🏪 Kassa kartasi ---------------- */

    public Map<String, Object> kassa(long id) {
        Kassa k = kassaRepo.findById(id).orElseThrow(() -> new BusinessException("Kassa topilmadi"));
        LocalDate today = ledger.today();
        Balance n = ledger.view(OwnerType.KASSA, id, MoneyType.NAQD);
        Balance kl = ledger.view(OwnerType.KASSA, id, MoneyType.KLIK);
        DayRecord day = dayRepo.findByKassaIdAndDate(id, today).orElse(null);
        Cached<List<DailyReportService.Row>> daily = dailyRows(today);
        DailyReportService.Row r = rowOf(daily.value(), id);
        Cached<Map<String, Long>> ms = msBalances();

        List<Map<String, Object>> openDays = new ArrayList<>();
        for (DayRecord d : submissionService.submittableDays(id))
            openDays.add(mapOf("date", d.getDate().toString(), "naqd", d.remainNaqd(), "klik", d.remainKlik(),
                    "status", d.getStatus().name()));

        List<Map<String, Object>> kassirs = new ArrayList<>();
        for (AppUser x : userRepo.findByKassaIdAndActiveTrue(id))
            kassirs.add(mapOf("name", x.getFullName(), "role", x.getRole().name(),
                    "tgId", x.getTelegramId() == null ? 0L : x.getTelegramId()));

        List<Map<String, Object>> ops = new ArrayList<>();
        for (Operation o : opRepo.history(OwnerType.KASSA, id, org.springframework.data.domain.PageRequest.of(0, 20)))
            ops.add(mapOf("id", o.getId(), "date", o.getOpDate().toString(), "type", o.getType().name(),
                    "mt", o.getMoneyType().name(), "amount", o.getAmount(),
                    "in", o.getToOwnerType() == OwnerType.KASSA && id == o.getToOwnerId(),
                    "status", o.getStatus().name(), "comment", nz(o.getComment())));

        return mapOf(
                "id", id, "name", k.getName(), "label", nz(k.getShopLabel()), "cashless", k.isCashless(),
                "naqd", n.getAmount(), "naqdBand", n.getReserved(), "naqdMavjud", n.available(),
                "klik", kl.getAmount(), "klikBand", kl.getReserved(), "klikMavjud", kl.available(),
                "today", day == null ? Map.of() : mapOf(
                        "prixodNaqd", day.getPrixodNaqd(), "prixodKlik", day.getPrixodKlik(),
                        "prixodTerminal", day.getPrixodTerminal(),
                        "vozvratNaqd", day.getVozvratNaqd(), "vozvratKlik", day.getVozvratKlik(),
                        "rasxodNaqd", day.getRasxodNaqd(), "rasxodKlik", day.getRasxodKlik()),
                "savdoMs", r == null ? 0 : r.msSavdo(), "savdoBot", r == null ? 0 : r.botSavdo(),
                "farq", r == null ? 0 : r.farq(), "msKnown", r != null && r.msKnown(),
                "openDays", openDays,
                "pending", subRepo.findByKassaIdAndStatusOrderByIdAsc(id, SubmissionStatus.KUTILMOQDA)
                        .stream().map(this::pendingBrief).toList(),
                "kartalar", cards(id, ms),
                "kassirs", kassirs,
                "ops", ops);
    }

    /* ---------------- 📥 Kutilayotgan hisobot ---------------- */

    public Map<String, Object> pending(long id) {
        Submission s = subRepo.findById(id).orElseThrow(() -> new BusinessException("Hisobot topilmadi"));
        Map<String, Object> out = new LinkedHashMap<>(pendingBrief(s));
        List<Map<String, Object>> days = new ArrayList<>();
        Cached<List<DailyReportService.Row>> daily = dailyRows(ledger.today());
        for (DayRecord d : dayRepo.findAllById(s.getDayIds()).stream()
                .sorted(Comparator.comparing(DayRecord::getDate)).toList()) {
            Map<String, Object> m = mapOf("date", d.getDate().toString(),
                    "prixodNaqd", d.getPrixodNaqd(), "prixodKlik", d.getPrixodKlik(),
                    "prixodTerminal", d.getPrixodTerminal(),
                    "vozvratNaqd", d.getVozvratNaqd(), "vozvratKlik", d.getVozvratKlik(),
                    "rasxodNaqd", d.getRasxodNaqd(), "rasxodKlik", d.getRasxodKlik(),
                    "remainNaqd", d.remainNaqd(), "remainKlik", d.remainKlik(),
                    "status", d.getStatus().name());
            days.add(m);
        }
        out.put("days", days);
        out.put("status", s.getStatus().name());
        out.put("acceptedNaqd", s.getAcceptedNaqd() == null ? 0 : s.getAcceptedNaqd());
        out.put("acceptedKlik", s.getAcceptedKlik() == null ? 0 : s.getAcceptedKlik());
        out.put("comment", nz(s.getComment()));
        DailyReportService.Row r = s.getKassaId() == null ? null : rowOf(daily.value(), s.getKassaId());
        out.put("bugunFarq", r == null ? 0 : r.farq());
        out.put("msKnown", r != null && r.msKnown());
        return out;
    }

    private Map<String, Object> pendingBrief(Submission s) {
        String kassir = s.getSubmittedBy() == null ? "" :
                userRepo.findById(s.getSubmittedBy()).map(AppUser::getFullName).orElse("");
        return mapOf("id", s.getId(), "kassaId", s.getKassaId() == null ? 0 : s.getKassaId(),
                "kassa", s.getKassaId() == null ? "" : kassaRepo.findById(s.getKassaId()).map(Kassa::getName).orElse("?"),
                "naqd", s.getNaqd(), "klik", s.getKlik(), "days", s.getDayIds().size(),
                "kassir", kassir,
                "createdAt", s.getCreatedAt() == null ? "" : DT.format(s.getCreatedAt().atZone(ZONE)));
    }

    /* ---------------- 💳 Click kartalari ---------------- */

    public Map<String, Object> cardsPage() {
        Cached<Map<String, Long>> ms = msBalances();
        return mapOf("asOf", DT.format(Instant.now().atZone(ZONE)), "msKnown", ms.ok(),
                "xulosa", cardSummary(null, ms), "kartalar", cards(null, ms));
    }

    private List<Map<String, Object>> cards(Long kassaId, Cached<Map<String, Long>> ms) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
            if (kassaId != null && !kassaId.equals(c.getKassaId())) continue;
            long m = msTiyin(c, ms);
            Long karta = c.getCardBalance();
            String holat = karta == null ? "kiritilmagan" : (m - karta == 0 ? "teng" : "farq");
            out.add(mapOf("id", c.getId(), "name", c.getName(),
                    "kassaId", c.getKassaId() == null ? 0 : c.getKassaId(),
                    "kassa", c.getKassaId() == null ? "" : kassaRepo.findById(c.getKassaId()).map(Kassa::getName).orElse(""),
                    "masul", nz(c.getCardResponsible()).replaceAll("\\{id=\\d+;([^}]+)\\}", "$1"),
                    "msTiyin", m, "kartaTiyin", karta == null ? 0 : karta,
                    "farqTiyin", karta == null ? 0 : m - karta, "holat", holat,
                    "at", c.getCardBalanceAt() == null ? "" : DT.format(c.getCardBalanceAt().atZone(ZONE)),
                    "by", nz(c.getCardBalanceBy()).replace("(tasdiqlangan)", "").trim()));
        }
        return out;
    }

    /** Karta uchun MoySklad qoldig'i (tiyin); MS o'qilmasa — bot qoldig'i. */
    private long msTiyin(ClickAccount c, Cached<Map<String, Long>> ms) {
        Long v = c.getMoyskladAccountId() == null ? null : ms.value().get(c.getMoyskladAccountId());
        return v != null ? v : ledger.view(OwnerType.CLICK, c.getId(), MoneyType.KLIK).getAmount() * 100;
    }

    /* ---------------- yordamchi ---------------- */

    private static DailyReportService.Row rowOf(List<DailyReportService.Row> rows, long kassaId) {
        for (DailyReportService.Row r : rows) if (r.kassaId() != null && r.kassaId() == kassaId) return r;
        return null;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Map.of null qiymatni qabul qilmaydi — tartibli, null-ga chidamli map. */
    static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
