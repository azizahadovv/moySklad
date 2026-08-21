package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import uz.kassa.bot.NameService;
import uz.kassa.bot.TextUtil;
import uz.kassa.domain.*;
import uz.kassa.repo.*;
import uz.kassa.service.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Telegram Mini App REST API. Har so'rovda X-Telegram-Init-Data headeri
 * Telegram imzosi bilan tekshiriladi; rol bo'yicha ko'rish/qaror cheklovlari
 * bot bilan bir xil (TZ 13).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WebAppController {

    private final TelegramWebAppAuth auth;
    private final LedgerService ledger;
    private final KassaRepo kassaRepo;
    private final DayRepo dayRepo;
    private final OperationRepo opRepo;
    private final SubmissionRepo subRepo;
    private final AppUserRepo userRepo;
    private final CategoryRepo categoryRepo;
    private final NameService names;
    private final RasxodService rasxodService;
    private final TransferService transferService;
    private final SubmissionService submissionService;
    private final NotificationService notify;
    private final ExcelReportService excel;
    private final uz.kassa.bot.Sender sender;
    private final DebtRepo debtRepo;
    private final GuestRepo guestRepo;
    private final uz.kassa.service.moysklad.MoySkladClient msClient;
    private final uz.kassa.service.moysklad.MoySkladSyncService syncService;

    private AppUser user(String initData) {
        AppUser u = auth.authenticate(initData);
        if (u == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Ruxsat yo'q");
        return u;
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> business(BusinessException e) {
        return Map.of("error", e.getMessage());
    }

    /* ============================ ME ============================ */

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader("X-Telegram-Init-Data") String init) {
        AppUser u = user(init);
        return Map.of(
                "name", u.getFullName(),
                "role", u.getRole().name(),
                "kassaId", u.getKassaId() == null ? 0 : u.getKassaId(),
                "kassaName", u.getKassaId() == null ? "" : names.owner(OwnerType.KASSA, u.getKassaId()));
    }

    /* ============================ BALANS ============================ */

    @GetMapping("/balances")
    public List<Map<String, Object>> balances(@RequestHeader("X-Telegram-Init-Data") String init) {
        AppUser u = user(init);
        syncService.syncIfStale(45);   // so'ralganda oxirgi ma'lumot kelsin
        List<Map<String, Object>> out = new ArrayList<>();
        List<Kassa> kassas = u.getRole() == Role.KASSIR
                ? kassaRepo.findById(u.getKassaId() == null ? -1L : u.getKassaId()).map(List::of).orElse(List.of())
                : kassaRepo.findByActiveTrueOrderByIdAsc();
        for (Kassa k : kassas) {
            Balance n = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.NAQD);
            Balance kl = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.KLIK);
            long term = dayRepo.findByKassaIdAndDate(k.getId(), ledger.today())
                    .map(DayRecord::getPrixodTerminal).orElse(0L);
            out.add(Map.of("id", k.getId(), "name", k.getName(),
                    "naqd", n.getAmount(), "naqdBand", n.getReserved(),
                    "klik", kl.getAmount(), "klikBand", kl.getReserved(),
                    "terminal", term,
                    "jami", n.getAmount() + kl.getAmount() + term));
        }
        if (u.getRole() != Role.KASSIR) {
            Balance bn = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD);
            Balance bk = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.KLIK);
            out.add(Map.of("id", 0, "name", "🏦 Buxgalteriya",
                    "naqd", bn.getAmount(), "naqdBand", bn.getReserved(),
                    "klik", bk.getAmount(), "klikBand", bk.getReserved(),
                    "terminal", 0,
                    "jami", bn.getAmount() + bk.getAmount()));
        }
        return out;
    }

    @GetMapping("/kassas")
    public List<Map<String, Object>> kassas(@RequestHeader("X-Telegram-Init-Data") String init) {
        user(init);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            out.add(Map.of("id", k.getId(), "name", k.getName()));
        return out;
    }

    /* ============================ TARIX ============================ */

    @GetMapping("/operations")
    public List<Map<String, Object>> operations(
            @RequestHeader("X-Telegram-Init-Data") String init,
            @RequestParam String from, @RequestParam String to,
            @RequestParam(required = false, defaultValue = "0") long kassaId,
            @RequestParam(required = false, defaultValue = "") String type) {
        AppUser u = user(init);
        LocalDate f = LocalDate.parse(from), t = LocalDate.parse(to);
        if (f.isAfter(t)) { LocalDate x = f; f = t; t = x; }
        if (f.isBefore(t.minusDays(370)))
            throw new BusinessException("Davr 1 yildan oshmasin");

        long kf = u.getRole() == Role.KASSIR
                ? (u.getKassaId() == null ? -1 : u.getKassaId())
                : kassaId;

        List<Map<String, Object>> out = new ArrayList<>();
        for (Operation o : opRepo.byPeriod(f, t)) {
            if (kf > 0 && !isForKassa(o, kf)) continue;
            if (u.getRole() == Role.KASSIR && kf <= 0) continue;
            if (!type.isBlank() && !o.getType().name().equals(type)) continue;
            String cat = o.getCategoryId() == null ? "" :
                    categoryRepo.findById(o.getCategoryId()).map(Category::getName).orElse("");
            out.add(Map.ofEntries(
                    Map.entry("id", o.getId()),
                    Map.entry("date", o.getOpDate().toString()),
                    Map.entry("type", o.getType().name()),
                    Map.entry("mt", o.getMoneyType().name()),
                    Map.entry("amount", o.getAmount()),
                    Map.entry("from", o.getFromOwnerType() == null ? "" :
                            names.owner(o.getFromOwnerType(), o.getFromOwnerId())),
                    Map.entry("to", o.getToOwnerType() == null ? "" :
                            names.owner(o.getToOwnerType(), o.getToOwnerId())),
                    Map.entry("status", o.getStatus().name()),
                    Map.entry("category", cat),
                    Map.entry("comment", o.getComment() == null ? "" : o.getComment())));
            if (out.size() >= 500) break;   // himoya: juda katta javob bo'lmasin
        }
        return out;
    }

    private boolean isForKassa(Operation o, long kassaId) {
        return (o.getFromOwnerType() == OwnerType.KASSA && kassaId == o.getFromOwnerId())
                || (o.getToOwnerType() == OwnerType.KASSA && kassaId == o.getToOwnerId());
    }

    /* ============================ KASSA PROFILI ============================ */

    @GetMapping("/kassa/{id}/profile")
    public Map<String, Object> kassaProfile(@RequestHeader("X-Telegram-Init-Data") String init,
                                            @PathVariable long id,
                                            @RequestParam String from, @RequestParam String to) {
        AppUser u = user(init);
        if (u.getRole() == Role.KASSIR && (u.getKassaId() == null || u.getKassaId() != id))
            throw new BusinessException("Faqat o'z kassangizni ko'ra olasiz");
        syncService.syncIfStale(45);
        Kassa k = kassaRepo.findById(id).orElseThrow(() -> new BusinessException("Kassa topilmadi"));

        Balance n = ledger.view(OwnerType.KASSA, id, MoneyType.NAQD);
        Balance kl = ledger.view(OwnerType.KASSA, id, MoneyType.KLIK);
        DayRecord today = dayRepo.findByKassaIdAndDate(id, ledger.today()).orElse(null);

        List<Map<String, Object>> kassirs = new ArrayList<>();
        for (AppUser x : userRepo.findByKassaIdAndActiveTrue(id))
            kassirs.add(Map.of("name", x.getFullName(),
                    "tgId", x.getTelegramId() == null ? 0L : x.getTelegramId()));

        LocalDate f = LocalDate.parse(from), t = LocalDate.parse(to);
        List<Map<String, Object>> ops = new ArrayList<>();
        long kirim = 0, chiqim = 0;
        for (Operation o : opRepo.byPeriod(f, t)) {
            if (!isForKassa(o, id)) continue;
            boolean in = (o.getToOwnerType() == OwnerType.KASSA && id == o.getToOwnerId());
            if (o.getType() == OpType.PRIXOD || o.getType() == OpType.BOSHLANGICH
                    || (o.getType() == OpType.OTKAZMA && in)) kirim += o.getAmount();
            else chiqim += o.getAmount();
            if (ops.size() < 100)
                ops.add(Map.of("id", o.getId(), "date", o.getOpDate().toString(),
                        "type", o.getType().name(), "mt", o.getMoneyType().name(),
                        "amount", o.getAmount(), "in", in || o.getType() == OpType.PRIXOD,
                        "status", o.getStatus().name(),
                        "comment", o.getComment() == null ? "" : o.getComment()));
        }

        Map<String, Object> day = today == null ? Map.of() : Map.of(
                "prixodNaqd", today.getPrixodNaqd(), "prixodKlik", today.getPrixodKlik(),
                "prixodTerminal", today.getPrixodTerminal(),
                "rasxodNaqd", today.getRasxodNaqd(), "rasxodKlik", today.getRasxodKlik());

        return Map.ofEntries(
                Map.entry("id", id), Map.entry("name", k.getName()),
                Map.entry("naqd", n.getAmount()), Map.entry("naqdBand", n.getReserved()),
                Map.entry("klik", kl.getAmount()), Map.entry("klikBand", kl.getReserved()),
                Map.entry("jami", n.getAmount() + kl.getAmount()),
                Map.entry("today", day),
                Map.entry("openDays", submissionService.submittableDays(id).size()),
                Map.entry("kassirs", kassirs),
                Map.entry("kirim", kirim), Map.entry("chiqim", chiqim),
                Map.entry("ops", ops));
    }

    /* ============================ 📊 EXCEL HISOBOT ============================ */

    public record ExportReq(String from, String to) {}

    @PostMapping("/export")
    public Map<String, String> export(@RequestHeader("X-Telegram-Init-Data") String init,
                                      @RequestBody ExportReq r) {
        AppUser u = user(init);
        if (u.getRole() == Role.KASSIR)
            throw new BusinessException("Excel hisobot faqat rahbariyat uchun");
        LocalDate f = LocalDate.parse(r.from()), t = LocalDate.parse(r.to());
        if (f.isAfter(t)) { LocalDate x = f; f = t; t = x; }
        if (f.isBefore(t.minusDays(370))) throw new BusinessException("Davr 1 yildan oshmasin");

        byte[] xlsx = excel.build(f, t);
        sender.sendDocument(u.getTelegramId(), xlsx,
                "kassa-hisobot_" + f + "_" + t + ".xlsx",
                "📊 Kassa hisoboti: <b>" + f + " — " + t + "</b>\n"
                        + "Varaqlar: Umumiy · Tranzaksiyalar · MoySklad hujjatlari");
        return Map.of("ok", "true");
    }

    /* ============================ KUTILAYOTGANLAR ============================ */

    @GetMapping("/pending")
    public Map<String, Object> pending(@RequestHeader("X-Telegram-Init-Data") String init) {
        AppUser u = user(init);
        List<Map<String, Object>> rasxod = new ArrayList<>();
        List<Map<String, Object>> transfer = new ArrayList<>();
        List<Map<String, Object>> submission = new ArrayList<>();

        if (u.getRole() != Role.KASSIR) {
            for (Operation op : opRepo.findByStatusAndType(OpStatus.KUTILMOQDA, OpType.RASXOD)) {
                String kassir = op.getCreatedBy() == null ? "" :
                        userRepo.findById(op.getCreatedBy()).map(AppUser::getFullName).orElse("");
                rasxod.add(Map.of("id", op.getId(), "amount", op.getAmount(),
                        "mt", op.getMoneyType().name(),
                        "from", names.owner(op.getFromOwnerType(), op.getFromOwnerId()),
                        "comment", op.getComment() == null ? "" : op.getComment(),
                        "kassir", kassir, "date", op.getOpDate().toString()));
            }
            for (Submission sub : subRepo.findByStatusOrderByIdAsc(SubmissionStatus.KUTILMOQDA))
                submission.add(Map.of("id", sub.getId(),
                        "kassa", names.owner(OwnerType.KASSA, sub.getKassaId()),
                        "naqd", sub.getNaqd(), "klik", sub.getKlik()));
        }

        OwnerType ot = u.getRole() == Role.KASSIR ? OwnerType.KASSA : OwnerType.BUXGALTERIYA;
        Long oid = u.getRole() == Role.KASSIR ? u.getKassaId() : LedgerService.BUX_ID;
        if (oid != null)
            for (Operation op : opRepo.incomingTransfers(ot, oid))
                transfer.add(Map.of("id", op.getId(), "amount", op.getAmount(),
                        "mt", op.getMoneyType().name(),
                        "from", names.owner(op.getFromOwnerType(), op.getFromOwnerId()),
                        "kind", op.getTransferKind() == null ? "" : op.getTransferKind().name(),
                        "comment", op.getComment() == null ? "" : op.getComment(),
                        "date", op.getOpDate().toString()));

        return Map.of("rasxod", rasxod, "transfer", transfer, "submission", submission);
    }

    /* ============================ LUG'ATLAR ============================ */

    @GetMapping("/categories")
    public List<Map<String, Object>> categories(@RequestHeader("X-Telegram-Init-Data") String init) {
        user(init);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Category c : categoryRepo.findByActiveTrueOrderByIdAsc())
            out.add(Map.of("id", c.getId(), "name", c.getName()));
        return out;
    }

    /* ============================ 💸 RASXOD ============================ */

    public record RasxodReq(String mt, long amount, Long categoryId, String comment) {}

    /** Kassir — so'rov (buxgalter tasdig'i bilan); Buxgalter/Admin — to'g'ridan-to'g'ri. */
    @PostMapping("/rasxod-create")
    public Map<String, Object> rasxodCreate(@RequestHeader("X-Telegram-Init-Data") String init,
                                            @RequestBody RasxodReq r) {
        AppUser u = user(init);
        MoneyType mt = MoneyType.valueOf(r.mt());
        String comment = r.comment() == null ? "" : r.comment().trim();
        String catName = r.categoryId() == null ? "" :
                categoryRepo.findById(r.categoryId()).map(Category::getName).orElse("");

        if (u.getRole() == Role.KASSIR) {
            Operation op = rasxodService.createRequest(u, mt, r.amount(), r.categoryId(), comment);
            var kb = uz.kassa.bot.Keyboards.inline(List.of(uz.kassa.bot.Keyboards.irow(
                    uz.kassa.bot.Keyboards.btn("✅ Tasdiqlash", "rx:a:" + op.getId()),
                    uz.kassa.bot.Keyboards.btn("❌ Rad etish", "rx:r:" + op.getId()))));
            notify.toBuxgalteriya("💸 <b>Rasxod so'rovi</b> #" + op.getId() + "\n\n"
                    + "Kassa: <b>" + TextUtil.esc(names.owner(OwnerType.KASSA, u.getKassaId())) + "</b>\n"
                    + "Summa: <b>" + TextUtil.fmt(r.amount()) + "</b> so'm\n"
                    + (catName.isEmpty() ? "" : "Kategoriya: " + TextUtil.esc(catName) + "\n")
                    + (comment.isEmpty() ? "" : "Izoh: " + TextUtil.esc(comment) + "\n")
                    + "Kassir: " + TextUtil.esc(u.getFullName()), kb);
            return Map.of("ok", true, "id", op.getId(), "pending", true);
        }
        Operation op = rasxodService.direct(u, mt, r.amount(), r.categoryId(), comment);
        return Map.of("ok", true, "id", op.getId(), "pending", false);
    }

    /* ============================ 🔁 O'TKAZMA ============================ */

    public record TransferReq(String toType, Long toId, String mt, long amount,
                              String kind, Long debtId, String comment) {}

    @PostMapping("/transfer-create")
    public Map<String, Object> transferCreate(@RequestHeader("X-Telegram-Init-Data") String init,
                                              @RequestBody TransferReq r) {
        AppUser u = user(init);
        OwnerType fromT = u.getRole() == Role.KASSIR ? OwnerType.KASSA : OwnerType.BUXGALTERIYA;
        Long fromId = u.getRole() == Role.KASSIR ? u.getKassaId() : LedgerService.BUX_ID;
        if (fromId == null) throw new BusinessException("Sizga kassa biriktirilmagan");
        OwnerType toT = "B".equals(r.toType()) ? OwnerType.BUXGALTERIYA : OwnerType.KASSA;
        Long toId = toT == OwnerType.BUXGALTERIYA ? LedgerService.BUX_ID : r.toId();
        TransferKind kind = r.kind() == null || r.kind().isBlank()
                ? TransferKind.ODDIY : TransferKind.valueOf(r.kind());
        String comment = r.comment() == null ? "" : r.comment().trim();

        Operation op = transferService.create(u, fromT, fromId, toT, toId,
                MoneyType.valueOf(r.mt()), r.amount(), kind, r.debtId(), comment);

        var kb = uz.kassa.bot.Keyboards.inline(List.of(uz.kassa.bot.Keyboards.irow(
                uz.kassa.bot.Keyboards.btn("✅ Oldim", "tr:a:" + op.getId()),
                uz.kassa.bot.Keyboards.btn("❌ Olmadim", "tr:r:" + op.getId()))));
        String msg = "🔁 <b>Sizga o'tkazma</b> #" + op.getId() + "\n\n"
                + "Kimdan: <b>" + TextUtil.esc(names.owner(fromT, fromId)) + "</b>\n"
                + "Summa: <b>" + TextUtil.fmt(r.amount()) + "</b> so'm\n"
                + (comment.isEmpty() ? "" : "Izoh: " + TextUtil.esc(comment) + "\n")
                + "\nPulni qabul qilganingizni tasdiqlang:";
        if (toT == OwnerType.KASSA) notify.toKassa(toId, msg, kb);
        else notify.toBuxgalteriya(msg, kb);
        return Map.of("ok", true, "id", op.getId());
    }

    /* ============================ 🧾 QARZLAR ============================ */

    @GetMapping("/debts")
    public List<Map<String, Object>> debts(@RequestHeader("X-Telegram-Init-Data") String init) {
        AppUser u = user(init);
        List<uz.kassa.domain.Debt> list;
        if (u.getRole() == Role.KASSIR) {
            if (u.getKassaId() == null) return List.of();
            list = new ArrayList<>(debtRepo.findByDebtorTypeAndDebtorIdAndStatus(
                    OwnerType.KASSA, u.getKassaId(), DebtStatus.OCHIQ));
            list.addAll(debtRepo.findByCreditorTypeAndCreditorIdAndStatus(
                    OwnerType.KASSA, u.getKassaId(), DebtStatus.OCHIQ));
        } else list = debtRepo.findByStatusOrderByIdAsc(DebtStatus.OCHIQ);
        List<Map<String, Object>> out = new ArrayList<>();
        for (uz.kassa.domain.Debt d : list)
            out.add(Map.of("id", d.getId(),
                    "debtor", names.owner(d.getDebtorType(), d.getDebtorId()),
                    "creditor", names.owner(d.getCreditorType(), d.getCreditorId()),
                    "mt", d.getMoneyType().name(),
                    "amount", d.getAmount(), "repaid", d.getRepaid(), "remain", d.remain(),
                    "reason", d.getReason() == null ? "" : d.getReason()));
        return out;
    }

    /* ============================ 📤 HISOBOT (kassir) ============================ */

    @GetMapping("/submit-days")
    public List<Map<String, Object>> submitDays(@RequestHeader("X-Telegram-Init-Data") String init) {
        AppUser u = user(init);
        if (u.getRole() != Role.KASSIR || u.getKassaId() == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (DayRecord d : submissionService.submittableDays(u.getKassaId()))
            out.add(Map.of("date", d.getDate().toString(),
                    "naqd", d.remainNaqd(), "klik", d.remainKlik()));
        return out;
    }

    public record SubmitReq(int days) {}

    @PostMapping("/submit")
    public Map<String, Object> submit(@RequestHeader("X-Telegram-Init-Data") String init,
                                      @RequestBody SubmitReq r) {
        AppUser u = user(init);
        if (u.getRole() != Role.KASSIR) throw new BusinessException("Faqat kassir topshiradi");
        Submission sub = submissionService.create(u, r.days());
        var kb = uz.kassa.bot.Keyboards.inline(List.of(
                uz.kassa.bot.Keyboards.irow(uz.kassa.bot.Keyboards.btn("✅ To'liq qabul", "sb:f:" + sub.getId())),
                uz.kassa.bot.Keyboards.irow(
                        uz.kassa.bot.Keyboards.btn("🟡 Qisman qabul", "sb:p:" + sub.getId()),
                        uz.kassa.bot.Keyboards.btn("❌ Rad etish", "sb:r:" + sub.getId()))));
        notify.toBuxgalteriya("📤 <b>Hisobot</b> #" + sub.getId() + " — <b>"
                + TextUtil.esc(names.owner(OwnerType.KASSA, sub.getKassaId())) + "</b>\n"
                + "Kassir: " + TextUtil.esc(u.getFullName()) + "\n\n"
                + "Jami: Naqd <b>" + TextUtil.fmt(sub.getNaqd()) + "</b> · Click <b>"
                + TextUtil.fmt(sub.getKlik()) + "</b> so'm", kb);
        return Map.of("ok", true, "id", sub.getId(),
                "naqd", sub.getNaqd(), "klik", sub.getKlik());
    }

    /* ============================ 👑 ADMIN ============================ */

    private void adminOnly(AppUser u) {
        if (u.getRole() != Role.SUPERADMIN) throw new BusinessException("Faqat SuperAdmin uchun");
    }

    @GetMapping("/guests")
    public List<Map<String, Object>> guests(@RequestHeader("X-Telegram-Init-Data") String init) {
        adminOnly(user(init));
        List<Map<String, Object>> out = new ArrayList<>();
        for (uz.kassa.domain.Guest g : guestRepo.findAllByOrderByLastSeenDesc()) {
            if (userRepo.findByTelegramId(g.getTelegramId()).isPresent()) continue;
            out.add(Map.of("tgId", g.getTelegramId(),
                    "name", g.getName() == null ? "" : g.getName(),
                    "username", g.getUsername() == null ? "" : g.getUsername()));
            if (out.size() >= 15) break;
        }
        return out;
    }

    public record NewUserReq(long tgId, String name, String role, Long kassaId) {}

    @PostMapping("/admin/user")
    public Map<String, Object> addUser(@RequestHeader("X-Telegram-Init-Data") String init,
                                       @RequestBody NewUserReq r) {
        adminOnly(user(init));
        if (userRepo.findByTelegramId(r.tgId()).isPresent())
            throw new BusinessException("Bu ID bilan foydalanuvchi allaqachon mavjud");
        Role role = Role.valueOf(r.role());
        if (role == Role.KASSIR && r.kassaId() == null)
            throw new BusinessException("Kassir uchun kassa tanlang");
        userRepo.save(AppUser.builder().telegramId(r.tgId()).fullName(r.name())
                .role(role).kassaId(role == Role.KASSIR ? r.kassaId() : null).active(true).build());
        guestRepo.deleteById(r.tgId());
        notify.toUser(r.tgId(), "✅ Siz tizimga qo'shildingiz! Botga /start yozing.");
        return Map.of("ok", true);
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users(@RequestHeader("X-Telegram-Init-Data") String init) {
        adminOnly(user(init));
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc())
            out.add(Map.of("id", x.getId(),
                    "tgId", x.getTelegramId() == null ? 0L : x.getTelegramId(),
                    "name", x.getFullName(), "role", x.getRole().name(),
                    "kassa", x.getKassaId() == null ? "" : names.owner(OwnerType.KASSA, x.getKassaId())));
        return out;
    }

    public record IdReq(long id) {}

    @PostMapping("/admin/user-deactivate")
    public Map<String, Object> deactivate(@RequestHeader("X-Telegram-Init-Data") String init,
                                          @RequestBody IdReq r) {
        AppUser me = user(init); adminOnly(me);
        AppUser x = userRepo.findById(r.id()).orElseThrow(() -> new BusinessException("Topilmadi"));
        if (x.getId().equals(me.getId())) throw new BusinessException("O'zingizni o'chira olmaysiz");
        x.setActive(false); userRepo.save(x);
        return Map.of("ok", true);
    }

    @GetMapping("/groups")
    public List<Map<String, Object>> groups(@RequestHeader("X-Telegram-Init-Data") String init) {
        adminOnly(user(init));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : msClient.fetchGroups().entrySet())
            out.add(Map.of("id", e.getKey(), "name", e.getValue()));
        return out;
    }

    public record NewKassaReq(String name, String storeId, String groupId) {}

    @PostMapping("/admin/kassa")
    public Map<String, Object> addKassa(@RequestHeader("X-Telegram-Init-Data") String init,
                                        @RequestBody NewKassaReq r) {
        adminOnly(user(init));
        if (r.name() == null || r.name().isBlank()) throw new BusinessException("Nom kiriting");
        Kassa k = kassaRepo.save(Kassa.builder().name(r.name().trim())
                .moyskladStoreId(r.storeId() == null || r.storeId().isBlank() ? null : r.storeId().trim())
                .moyskladGroupId(r.groupId() == null || r.groupId().isBlank() ? null : r.groupId())
                .active(true).build());
        return Map.of("ok", true, "id", k.getId());
    }

    public record InitBalanceReq(String ownerType, Long ownerId, long naqd, long klik) {}

    @PostMapping("/admin/init-balance")
    public Map<String, Object> initBalance(@RequestHeader("X-Telegram-Init-Data") String init,
                                           @RequestBody InitBalanceReq r) {
        AppUser u = user(init); adminOnly(u);
        OwnerType ot = "B".equals(r.ownerType()) ? OwnerType.BUXGALTERIYA : OwnerType.KASSA;
        Long oid = ot == OwnerType.BUXGALTERIYA ? LedgerService.BUX_ID : r.ownerId();
        if (r.naqd() == 0 && r.klik() == 0) throw new BusinessException("Ikkala summa ham 0");
        if (r.naqd() > 0) ledger.postAdjustment(OpType.BOSHLANGICH, ot, oid, MoneyType.NAQD,
                r.naqd(), "Boshlang'ich qoldiq", u.getId());
        if (r.klik() > 0) ledger.postAdjustment(OpType.BOSHLANGICH, ot, oid, MoneyType.KLIK,
                r.klik(), "Boshlang'ich qoldiq", u.getId());
        return Map.of("ok", true);
    }

    /* ============================ QARORLAR ============================ */

    public record DecideReq(String kind, long id, String action,
                            String reason, Long naqd, Long klik) {}

    @PostMapping("/decide")
    public Map<String, String> decide(@RequestHeader("X-Telegram-Init-Data") String init,
                                      @RequestBody DecideReq r) {
        AppUser u = user(init);
        switch (r.kind()) {
            case "rasxod" -> {
                if (u.getRole() == Role.KASSIR)
                    throw new BusinessException("Bu amal faqat buxgalter uchun");
                if (r.action().equals("approve")) {
                    Operation op = rasxodService.approve(r.id(), u);
                    notify.toKassa(op.getFromOwnerId(), "✅ Rasxod so'rovingiz tasdiqlandi: <b>"
                            + TextUtil.fmt(op.getAmount()) + "</b> so'm", null);
                } else {
                    Operation op = rasxodService.reject(r.id(), u,
                            r.reason() == null || r.reason().isBlank() ? "Sabab ko'rsatilmagan" : r.reason());
                    notify.toKassa(op.getFromOwnerId(), "❌ Rasxod so'rovingiz rad etildi: <b>"
                            + TextUtil.fmt(op.getAmount()) + "</b> so'm\nSabab: "
                            + TextUtil.esc(op.getRejectReason()), null);
                }
            }
            case "transfer" -> {
                Operation check = opRepo.findById(r.id())
                        .orElseThrow(() -> new BusinessException("O'tkazma topilmadi"));
                boolean receiverOk = check.getToOwnerType() == OwnerType.KASSA
                        ? u.getRole() == Role.KASSIR && check.getToOwnerId().equals(u.getKassaId())
                        : u.getRole() != Role.KASSIR;
                if (!receiverOk)
                    throw new BusinessException("Bu o'tkazmani faqat qabul qiluvchi tomon tasdiqlaydi");
                Operation op = r.action().equals("approve")
                        ? transferService.accept(r.id(), u)
                        : transferService.reject(r.id(), u);
                String text = (r.action().equals("approve") ? "✅ O'tkazmangiz qabul qilindi: "
                        : "❌ O'tkazmangiz rad etildi: ")
                        + "<b>" + TextUtil.fmt(op.getAmount()) + "</b> so'm — "
                        + TextUtil.esc(names.owner(op.getToOwnerType(), op.getToOwnerId()));
                if (op.getFromOwnerType() == OwnerType.KASSA) notify.toKassa(op.getFromOwnerId(), text, null);
                else notify.toBuxgalteriya(text, null);
            }
            case "submission" -> {
                if (u.getRole() == Role.KASSIR)
                    throw new BusinessException("Bu amal faqat buxgalter uchun");
                Submission sub = switch (r.action()) {
                    case "approve" -> submissionService.acceptFull(r.id(), u);
                    case "partial" -> submissionService.acceptPartial(r.id(), u,
                            r.naqd() == null ? 0 : r.naqd(), r.klik() == null ? 0 : r.klik());
                    default -> submissionService.reject(r.id(), u,
                            r.reason() == null || r.reason().isBlank() ? "Sabab ko'rsatilmagan" : r.reason());
                };
                notify.toKassa(sub.getKassaId(), "📤 Hisobot #" + sub.getId() + " — "
                        + switch (r.action()) {
                            case "approve" -> "✅ to'liq qabul qilindi";
                            case "partial" -> "🟡 qisman qabul qilindi";
                            default -> "❌ rad etildi";
                        }, null);
            }
            default -> throw new BusinessException("Noma'lum amal");
        }
        return Map.of("ok", "true");
    }
}
