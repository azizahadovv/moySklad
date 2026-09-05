package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uz.kassa.bot.NameService;
import uz.kassa.bot.TextUtil;
import uz.kassa.domain.*;
import uz.kassa.repo.KassaRepo;
import uz.kassa.service.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static uz.kassa.webapp.AdminApiService.mapOf;

/**
 * Kassa kartasidagi AMALLAR (Mini App): 💰 pul qabul qilish, 🛠 korrektirovka.
 * Biznes-qoidalar botdagi bilan bir xil servislarda (SubmissionService.directCollect,
 * LedgerService.postAdjustment); bu yerda faqat tekshiruv, audit va bildirishnomalar —
 * OtdelHandler.qbCommit / BalanceAdminHandler.krCommit bilan bir xil matnlar.
 */
@Service
@RequiredArgsConstructor
public class AdminActionService {

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final SubmissionService submissionService;
    private final LedgerService ledger;
    private final KassaRepo kassaRepo;
    private final NameService names;
    private final NotificationService notify;
    private final AuditService audit;

    public record CollectReq(String mt, Long amount, String topshirgan, String date) {}
    public record AdjustReq(String mt, Long amount, Long target, String reason, String date) {}

    /** 💰 Pul qabul qilish (Buxgalter ham, SuperAdmin ham). NAQD yoki TERMINAL. */
    public Map<String, Object> collect(AppUser u, long kassaId, CollectReq r) {
        Kassa k = kassaRepo.findById(kassaId).orElseThrow(() -> new BusinessException("Касса топилмади"));
        MoneyType mt = parseMt(r.mt(), MoneyType.NAQD, MoneyType.TERMINAL);
        long sum = r.amount() == null ? 0 : r.amount();
        if (sum <= 0) throw new BusinessException("Сумма нолдан катта бўлсин");
        String topshirgan = r.topshirgan() == null ? "" : r.topshirgan().trim();
        if (topshirgan.isEmpty()) throw new BusinessException("Ким топширганини кўрсатинг");
        LocalDate date = parseDate(r.date());
        if (mt == MoneyType.NAQD) {
            long avail = ledger.view(OwnerType.KASSA, kassaId, MoneyType.NAQD).available();
            if (sum > avail) throw new BusinessException("Мавжуд нақд " + TextUtil.fmt(avail)
                    + " сўм — ундан кўп қабул қилиб бўлмайди");
        }
        Operation op = submissionService.directCollect(kassaId, mt, sum, u, topshirgan, date);
        notify.toKassa(kassaId, "💰 Buxgalteriya kassangizdan pul qabul qildi: <b>" + TextUtil.fmt(sum)
                + "</b> so'm (" + mtLabel(mt) + ")\n📅 Sana: " + date.format(DF)
                + "\nTopshirdi: " + TextUtil.esc(topshirgan), null);
        return mapOf("opId", op.getId(), "kassa", k.getName(), "mt", mt.name(), "amount", sum,
                "date", date.toString(),
                "naqd", ledger.view(OwnerType.KASSA, kassaId, MoneyType.NAQD).getAmount());
    }

    /** 🛠 Korrektirovka (faqat SuperAdmin). amount — ishorali; target berilsa «=maqsad». */
    public Map<String, Object> adjust(AppUser u, long kassaId, AdjustReq r) {
        if (u.getRole() != Role.SUPERADMIN) throw new BusinessException("Корректировка фақат SuperAdmin учун");
        kassaRepo.findById(kassaId).orElseThrow(() -> new BusinessException("Касса топилмади"));
        MoneyType mt = parseMt(r.mt(), MoneyType.NAQD, MoneyType.KLIK);
        String reasonBase = r.reason() == null ? "" : r.reason().trim();
        if (reasonBase.length() < 3) throw new BusinessException("Сабабни ёзинг (камида 3 белги)");
        LocalDate date = parseDate(r.date());
        boolean past = date.isBefore(ledger.today());
        long cur = ledger.view(OwnerType.KASSA, kassaId, mt).getAmount();
        long asOf = past ? ledger.balanceAsOf(OwnerType.KASSA, kassaId, mt, date) : cur;
        long sum = r.target() != null ? r.target() - asOf : (r.amount() == null ? 0 : r.amount());
        if (sum == 0) throw new BusinessException("Баланс аллақачон керакли қийматда — тузатиш ёзилмади");
        String reason = reasonBase + " [" + date.format(DF) + "]";
        Operation op = ledger.postAdjustment(OpType.KORREKTIROVKA, OwnerType.KASSA, kassaId, mt, sum, reason,
                u.getId(), date);
        long after = ledger.view(OwnerType.KASSA, kassaId, mt).getAmount();
        String owner = names.owner(OwnerType.KASSA, kassaId);
        // audit: LedgerService.postAdjustment o'zi KORREKTIROVKA yozuvini yozadi
        String info = "🛠 Korrektirovka — <b>" + TextUtil.esc(owner) + "</b>: <b>" + (sum > 0 ? "+" : "")
                + TextUtil.fmt(sum) + "</b> so'm (" + mtLabel(mt) + ")\n📅 Sana: <b>" + date.format(DF) + "</b>\n"
                + (past ? "📆 " + date.format(DF) + " kun oxiri endi: <b>" + TextUtil.fmt(asOf + sum) + "</b> so'm\n" : "")
                + "Hozirgi balans: <b>" + TextUtil.fmt(after) + "</b> so'm\nSabab: " + TextUtil.esc(reason)
                + "\nKim: " + TextUtil.esc(u.getFullName());
        notify.toBuxgalteriya(info, null);
        notify.toKassa(kassaId, info, null);
        return mapOf("opId", op.getId(), "sum", sum, "after", after, "asOf", past ? asOf + sum : null,
                "date", date.toString());
    }

    private MoneyType parseMt(String s, MoneyType... allowed) {
        MoneyType mt;
        try { mt = MoneyType.valueOf(s == null ? "" : s.trim().toUpperCase()); }
        catch (Exception e) { throw new BusinessException("Пул тури нотўғри"); }
        for (MoneyType a : allowed) if (a == mt) return mt;
        throw new BusinessException("Бу амал учун " + mt + " рухсат этилмаган");
    }

    private LocalDate parseDate(String s) {
        LocalDate d;
        try { d = s == null || s.isBlank() ? ledger.today() : LocalDate.parse(s); }
        catch (Exception e) { throw new BusinessException("Сана нотўғри"); }
        if (d.isAfter(ledger.today())) throw new BusinessException("Келажак сана бўлмайди");
        if (d.isBefore(ledger.today().minusDays(370))) throw new BusinessException("Сана 1 йилдан эски");
        return d;
    }

    private static String mtLabel(MoneyType mt) {
        return switch (mt) { case NAQD -> "💵 Naqd"; case KLIK -> "📲 Click"; case TERMINAL -> "💳 Terminal"; };
    }
}
