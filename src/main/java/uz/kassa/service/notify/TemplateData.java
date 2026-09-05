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
import static uz.kassa.service.notify.TemplateService.*;
import uz.kassa.service.notify.TemplateService.Run;

/**
 * Shablon o'rinbosarlari uchun MA'LUMOT manbalari: kassa/karta/user/eslatma/qarz maydonlari, yig'indilar (jami), davrlar va MoySklad davr keshi.
 * (TemplateService dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TemplateData {

    private final KassaRepo kassaRepo;
    private final ClickAccountRepo clickRepo;
    private final AppUserRepo userRepo;
    private final ReminderRepo reminderRepo;
    private final DebtRepo debtRepo;
    private final DayRepo dayRepo;
    private final LedgerService ledger;
    private final SubmissionService submissionService;
    private final MoySkladClient msClient;
    private final AppProps props;
    private final uz.kassa.service.DailyReportService dailyReport;

    private final Map<String, PeriodData> periodCache = new java.util.concurrent.ConcurrentHashMap<>();


    Object kassaField(long id, String field, String[] mods, Run run) {
        Kassa k = kassaRepo.findById(id).orElse(null);
        if (k == null) return "❓kassa#" + id;
        if (field.isEmpty()) field = "nom";
        LocalDate[] per = period(mods);
        return switch (field) {
            case "nom" -> TextUtil.esc(k.getName());
            case "id" -> String.valueOf(k.getId());
            case "label" -> TextUtil.esc(k.getShopLabel() == null ? "" : k.getShopLabel());
            case "naqd" -> som(ledger.view(OwnerType.KASSA, id, MoneyType.NAQD).getAmount());
            case "klik" -> som(ledger.view(OwnerType.KASSA, id, MoneyType.KLIK).getAmount());
            case "terminal" -> som(ledger.view(OwnerType.KASSA, id, MoneyType.TERMINAL).getAmount());
            case "naqd_mavjud" -> som(ledger.view(OwnerType.KASSA, id, MoneyType.NAQD).available());
            case "klik_mavjud" -> som(ledger.view(OwnerType.KASSA, id, MoneyType.KLIK).available());
            case "prixod" -> msSum(run, per, id, "sale_cash") + msSum(run, per, id, "sale_nocash");
            case "prixod_naqd" -> msSum(run, per, id, "sale_cash");
            case "prixod_beznaqd" -> msSum(run, per, id, "sale_nocash");
            case "vozvrat" -> msSum(run, per, id, "ret_cash") + msSum(run, per, id, "ret_nocash");
            case "vozvrat_naqd" -> msSum(run, per, id, "ret_cash");
            case "vozvrat_beznaqd" -> msSum(run, per, id, "ret_nocash");
            case "rasxod" -> msSum(run, per, id, "cashout");
            case "sof" -> msSum(run, per, id, "sale_cash") + msSum(run, per, id, "sale_nocash")
                    - msSum(run, per, id, "ret_cash") - msSum(run, per, id, "ret_nocash");
            case "bot_prixod" -> daySum(id, per, d -> d.getPrixodNaqd() + d.getPrixodKlik() + d.getPrixodTerminal());
            case "bot_prixod_naqd" -> daySum(id, per, DayRecord::getPrixodNaqd);
            case "bot_prixod_klik" -> daySum(id, per, DayRecord::getPrixodKlik);
            case "bot_prixod_terminal" -> daySum(id, per, DayRecord::getPrixodTerminal);
            case "bot_rasxod" -> daySum(id, per, d -> d.getRasxodNaqd() + d.getRasxodKlik());
            case "bot_vozvrat" -> daySum(id, per, d -> d.getVozvratNaqd() + d.getVozvratKlik());
            case "kirim" -> daySum(id, per, d -> d.getKirimNaqd() + d.getKirimKlik());
            case "chiqim" -> daySum(id, per, d -> d.getChiqimNaqd() + d.getChiqimKlik());
            case "topshirilmagan" -> (long) submissionService.submittableDays(id).size() * 100;
            case "kassir" -> {
                StringBuilder m = new StringBuilder();
                for (AppUser x : userRepo.findByKassaIdAndActiveTrue(id))
                    if (x.getTelegramId() != null) m.append(TemplateService.link(x.getTelegramId(), x.getFullName()));
                    else m.append(TextUtil.esc(x.getFullName())).append(" ");
                yield m.toString().trim();
            }
            case "kartalar_soni" -> (long) clickRepo.findByActiveTrueOrderByIdAsc().stream()
                    .filter(c -> Long.valueOf(id).equals(c.getKassaId())).count() * 100;
            // 📋 Kunlik solishtirish (DailyReportService bilan bir xil hisob) — davr: bitta kun (per[0])
            case "nuqta", "kassirlar", "savdo_ms", "savdo_bot", "savdo_farq", "savdo_holat",
                 "naqd_topshirilgan", "p2p_qoldiq" -> dailyField(id, field, per[0], run);
            default -> null;
        };
    }


    /** Kunlik solishtirish qatori — bir render davomida sana bo'yicha bir marta o'qiladi. */
    @SuppressWarnings("unchecked")
    Object dailyField(long kassaId, String field, LocalDate d, Run run) {
        List<uz.kassa.service.DailyReportService.Row> rows =
                (List<uz.kassa.service.DailyReportService.Row>) run.cache.computeIfAbsent("daily.rows." + d, k -> {
                    try { return dailyReport.rows(d); }
                    catch (Exception e) { log.warn("Shablon: kunlik qatorlar o'qilmadi ({}): {}", d, e.getMessage()); return List.of(); }
                });
        uz.kassa.service.DailyReportService.Row r = null;
        for (var x : rows) if (x.kassaId() != null && x.kassaId() == kassaId) { r = x; break; }
        if (r == null) return field.equals("savdo_holat") ? "—" : (field.equals("nuqta") || field.equals("kassirlar")) ? "—" : 0L;
        if (!r.msKnown()) run.msFailed = true;
        return switch (field) {
            case "nuqta" -> TextUtil.esc(r.nuqta());
            case "kassirlar" -> TextUtil.esc(r.kassir());
            case "savdo_ms" -> som(r.msSavdo());
            case "savdo_bot" -> som(r.botSavdo());
            case "savdo_farq" -> som(r.farq());
            case "savdo_holat" -> r.farq() == 0 ? "✅" : "⚠️";
            case "naqd_topshirilgan" -> som(r.naqdTopshirilgan());
            case "p2p_qoldiq" -> r.p2pQoldiqTiyin() < 0 ? 0L : r.p2pQoldiqTiyin();
            default -> null;
        };
    }


    interface DayFn { long apply(DayRecord d); }


    Long daySum(long kassaId, LocalDate[] per, DayFn fn) {
        long s = 0;
        for (DayRecord d : dayRepo.findByKassaIdAndDateBetween(kassaId, per[0], per[1])) s += fn.apply(d);
        return s * 100;
    }


    static Long som(long som) { return som * 100; }


    Object kartaField(long id, String field, Run run) {
        ClickAccount c = clickRepo.findById(id).orElse(null);
        if (c == null) return "❓karta#" + id;
        if (field.isEmpty()) field = "nom";
        ZoneId z = props.zoneId();
        return switch (field) {
            case "nom" -> TextUtil.esc(c.getName());
            case "id" -> String.valueOf(c.getId());
            case "kassa" -> c.getKassaId() == null ? "—"
                    : kassaRepo.findById(c.getKassaId()).map(k -> TextUtil.esc(k.getName())).orElse("?");
            case "masul" -> c.getCardResponsible() == null ? "—" : TextUtil.esc(c.getCardResponsible());
            case "qoldiq" -> c.getCardBalance() == null ? 0L : c.getCardBalance();
            case "qoldiq_vaqt" -> c.getCardBalanceAt() == null ? "—"
                    : LocalDateTime.ofInstant(c.getCardBalanceAt(), z).format(DTF);
            case "qoldiq_kim" -> c.getCardBalanceBy() == null ? "—" : TextUtil.esc(c.getCardBalanceBy());
            case "bot" -> som(ledger.view(OwnerType.CLICK, id, MoneyType.KLIK).getAmount());
            case "ms" -> kartaMs(c, run);
            case "farq" -> c.getCardBalance() == null ? 0L : kartaMs(c, run) - c.getCardBalance();
            case "holat" -> {
                if (c.getCardBalance() == null) yield "❗️ киритилмаган";
                long f = kartaMs(c, run) - c.getCardBalance();
                yield f == 0 ? "✅ тенг" : "⚠️ фарқ " + (f > 0 ? "+" : "") + TextUtil.fmtTiyin(f);
            }
            default -> null;
        };
    }


    /** Karta MoySklad qoldig'i (tiyin) — bir render davomida bir marta o'qiladi. */
    @SuppressWarnings("unchecked")
    long kartaMs(ClickAccount c, Run run) {
        Map<String, Long> ms = (Map<String, Long>) run.cache.computeIfAbsent("ms.accounts", k -> {
            try { return msClient.fetchAccountBalancesTiyin(); }
            catch (Exception e) { run.msFailed = true; return Map.of(); }
        });
        String aid = c.getMoyskladAccountId();
        Long v = aid == null ? null : ms.get(aid);
        return v != null ? v : ledger.view(OwnerType.CLICK, c.getId(), MoneyType.KLIK).getAmount() * 100;
    }


    Object userField(long id, String field) {
        AppUser u = userRepo.findById(id).orElse(null);
        if (u == null) return "❓user#" + id;
        return switch (field) {
            case "", "mention" -> u.getTelegramId() == null ? TextUtil.esc(u.getFullName())
                    : TemplateService.link(u.getTelegramId(), u.getFullName()).trim();
            case "ism" -> TextUtil.esc(u.getFullName());
            case "rol" -> u.getRole().name();
            case "tel" -> u.getPhone() == null ? "—" : TextUtil.esc(u.getPhone());
            case "kassa" -> u.getKassaId() == null ? "—"
                    : kassaRepo.findById(u.getKassaId()).map(k -> TextUtil.esc(k.getName())).orElse("?");
            default -> null;
        };
    }


    Object eslatmaField(long id, String field) {
        Reminder r = reminderRepo.findById(id).orElse(null);
        if (r == null) return "❓eslatma#" + id;
        long left = ChronoUnit.DAYS.between(LocalDate.now(props.zoneId()), r.getDueDate());
        return switch (field) {
            case "", "agent" -> TextUtil.esc(r.getAgentName());
            case "info" -> TextUtil.esc(r.getAgentInfo() == null ? "" : r.getAgentInfo());
            case "summa" -> som(r.getAmount());
            case "tolandi" -> som(r.getRepaid());
            case "qoldiq" -> som(r.remain());
            case "muddat" -> r.getDueDate().format(DF);
            case "qoldi_kun" -> left * 100;
            case "otdi_kun" -> Math.max(0, -left) * 100;
            case "holat" -> left > 0 ? "⏳ " + left + " kun qoldi" : left == 0 ? "❗️ BUGUN" : "⚠️ " + (-left) + " kun o'tdi";
            case "yonalish" -> r.getDirection() == Reminder.Direction.BIZ_QARZDOR ? "🔴 Biz to'laymiz" : "🟢 U qaytaradi";
            case "izoh" -> TextUtil.esc(r.getComment() == null ? "" : r.getComment());
            default -> null;
        };
    }


    Object qarzField(long id, String field) {
        Debt d = debtRepo.findById(id).orElse(null);
        if (d == null) return "❓qarz#" + id;
        return switch (field) {
            case "", "qarzdor" -> TextUtil.esc(ownerName(d.getDebtorType(), d.getDebtorId()));
            case "kreditor" -> TextUtil.esc(ownerName(d.getCreditorType(), d.getCreditorId()));
            case "summa" -> som(d.getAmount());
            case "tolandi" -> som(d.getRepaid());
            case "qoldiq" -> som(d.getAmount() - d.getRepaid());
            case "sabab" -> TextUtil.esc(d.getReason() == null ? "" : d.getReason());
            case "holat" -> d.getStatus().name();
            default -> null;
        };
    }


    String ownerName(OwnerType t, Long id) {
        if (t == OwnerType.BUXGALTERIYA) return "Основной";
        if (t == OwnerType.KASSA) return kassaRepo.findById(id).map(Kassa::getName).orElse("kassa#" + id);
        if (t == OwnerType.CLICK) return clickRepo.findById(id).map(ClickAccount::getName).orElse("click#" + id);
        return t + "#" + id;
    }


    /* -------------------- bux / jami -------------------- */

    Object bux(String field, String[] mods, Run run) {
        long id = LedgerService.BUX_ID;
        return switch (field) {
            case "naqd" -> som(ledger.view(OwnerType.BUXGALTERIYA, id, MoneyType.NAQD).getAmount());
            case "klik" -> som(ledger.view(OwnerType.BUXGALTERIYA, id, MoneyType.KLIK).getAmount());
            case "terminal" -> som(ledger.view(OwnerType.BUXGALTERIYA, id, MoneyType.TERMINAL).getAmount());
            case "naqd_mavjud" -> som(ledger.view(OwnerType.BUXGALTERIYA, id, MoneyType.NAQD).available());
            default -> null;
        };
    }


    Object jami(String field, String[] mods, Run run) {
        LocalDate[] per = period(mods);
        Set<Long> filter = kassaFilter(mods);
        List<Kassa> kassas = kassaRepo.findByActiveTrueOrderByIdAsc().stream()
                .filter(k -> filter == null || filter.contains(k.getId())).toList();
        List<ClickAccount> cards = clickRepo.findByActiveTrueOrderByIdAsc().stream()
                .filter(c -> filter == null || (c.getKassaId() != null && filter.contains(c.getKassaId()))).toList();
        switch (field) {
            case "naqd": case "klik": case "terminal": {
                MoneyType mt = MoneyType.valueOf(field.toUpperCase());
                long s = 0;
                for (Kassa k : kassas) s += ledger.view(OwnerType.KASSA, k.getId(), mt).getAmount();
                return som(s);
            }
            case "hammasi": {
                long s = 0;
                for (Kassa k : kassas) for (MoneyType mt : MoneyType.values())
                    s += ledger.view(OwnerType.KASSA, k.getId(), mt).getAmount() * 100;
                for (ClickAccount c : cards) if (c.getCardBalance() != null) s += c.getCardBalance();
                return s;
            }
            case "prixod": case "prixod_naqd": case "prixod_beznaqd":
            case "vozvrat": case "vozvrat_naqd": case "vozvrat_beznaqd": case "rasxod": case "sof": {
                long s = 0;
                if (filter == null) {
                    // Filtr yo'q — MoySklad'dagi BARCHA hujjatlar (otdelga bog'lanmaganlari ham)
                    s = switch (field) {
                        case "prixod" -> msSum(run, per, null, "sale_cash") + msSum(run, per, null, "sale_nocash");
                        case "prixod_naqd" -> msSum(run, per, null, "sale_cash");
                        case "prixod_beznaqd" -> msSum(run, per, null, "sale_nocash");
                        case "vozvrat" -> msSum(run, per, null, "ret_cash") + msSum(run, per, null, "ret_nocash");
                        case "vozvrat_naqd" -> msSum(run, per, null, "ret_cash");
                        case "vozvrat_beznaqd" -> msSum(run, per, null, "ret_nocash");
                        case "rasxod" -> msSum(run, per, null, "cashout");
                        default -> msSum(run, per, null, "sale_cash") + msSum(run, per, null, "sale_nocash")
                                - msSum(run, per, null, "ret_cash") - msSum(run, per, null, "ret_nocash");
                    };
                } else for (Kassa k : kassas) s += (Long) kassaField(k.getId(), field, mods, run);
                return s;
            }
            case "bot_prixod": case "bot_rasxod": case "kirim": case "chiqim": {
                long s = 0;
                for (Kassa k : kassas) s += (Long) kassaField(k.getId(), field, mods, run);
                return s;
            }
            case "karta_qoldiq": {
                long s = 0;
                for (ClickAccount c : cards) if (c.getCardBalance() != null) s += c.getCardBalance();
                return s;
            }
            case "karta_ms": {
                long s = 0;
                for (ClickAccount c : cards) s += kartaMs(c, run);
                return s;
            }
            case "karta_farq": {
                long s = 0;
                for (ClickAccount c : cards) if (c.getCardBalance() != null) s += kartaMs(c, run) - c.getCardBalance();
                return s;
            }
            case "karta_farq_soni": {
                long n = 0;
                for (ClickAccount c : cards) if (c.getCardBalance() != null && kartaMs(c, run) != c.getCardBalance()) n++;
                return n * 100;
            }
            case "karta_kiritilmagan": {
                long n = cards.stream().filter(c -> c.getCardBalance() == null).count();
                return n * 100;
            }
            case "karta_soni": return (long) cards.size() * 100;
            case "kassa_soni": return (long) kassas.size() * 100;
            case "xodim_soni": return userRepo.findByActiveTrueOrderByRoleAscIdAsc().size() * 100L;
            case "eslatma_faol": return reminderRepo.findByStatusOrderByDueDateAscIdAsc(Reminder.Status.FAOL).size() * 100L;
            case "eslatma_otgan": {
                LocalDate today = LocalDate.now(props.zoneId());
                return reminderRepo.findByStatusOrderByDueDateAscIdAsc(Reminder.Status.FAOL).stream()
                        .filter(r -> r.getDueDate().isBefore(today)).count() * 100;
            }
            case "eslatma_qoldiq": {
                long s = 0;
                for (Reminder r : reminderRepo.findByStatusOrderByDueDateAscIdAsc(Reminder.Status.FAOL)) s += r.remain();
                return som(s);
            }
            case "qarz_ochiq": {
                long s = 0;
                for (Debt d : debtRepo.findByStatusOrderByIdAsc(DebtStatus.OCHIQ)) s += d.getAmount() - d.getRepaid();
                return som(s);
            }
            case "topshirilmagan": {
                long n = 0;
                for (Kassa k : kassas) n += submissionService.submittableDays(k.getId()).size();
                return n * 100;
            }
            default: return null;
        }
    }


    static Set<Long> kassaFilter(String[] mods) {
        for (String m : mods) {
            if (m.startsWith("kassa=")) {
                Set<Long> s = new HashSet<>();
                for (String p : m.substring(6).split(","))
                    try { s.add(Long.parseLong(p.trim())); } catch (NumberFormatException ignored) { }
                return s;
            }
        }
        return null;
    }


    /* -------------------- davr -------------------- */

    /** Modifikatorlardan davr [from, to]; topilmasa — bugun. */
    LocalDate[] period(String[] mods) {
        LocalDate today = LocalDate.now(props.zoneId());
        for (String m : mods) {
            String p = m.trim();
            if (p.isEmpty() || p.contains("=")) continue;
            switch (p) {
                case "kun", "bugun": return new LocalDate[]{today, today};
                case "kecha": return new LocalDate[]{today.minusDays(1), today.minusDays(1)};
                case "hafta": return new LocalDate[]{today.with(DayOfWeek.MONDAY), today};
                case "otgan_hafta": {
                    LocalDate mon = today.with(DayOfWeek.MONDAY).minusWeeks(1);
                    return new LocalDate[]{mon, mon.plusDays(6)};
                }
                case "oy": return new LocalDate[]{today.withDayOfMonth(1), today};
                case "otgan_oy": {
                    LocalDate f = today.withDayOfMonth(1).minusMonths(1);
                    return new LocalDate[]{f, f.plusMonths(1).minusDays(1)};
                }
                case "yil": return new LocalDate[]{today.withDayOfYear(1), today};
                default: {
                    Matcher n = Pattern.compile("(\\d+)kun").matcher(p);
                    if (n.matches()) return new LocalDate[]{today.minusDays(Integer.parseInt(n.group(1)) - 1), today};
                    Matcher r = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\.\\.(\\d{4}-\\d{2}-\\d{2})").matcher(p);
                    if (r.matches()) return new LocalDate[]{LocalDate.parse(r.group(1)), LocalDate.parse(r.group(2))};
                    Matcher one = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(p);
                    if (one.matches()) { LocalDate d = LocalDate.parse(p); return new LocalDate[]{d, d}; }
                }
            }
        }
        return new LocalDate[]{today, today};
    }


    /* -------------------- MoySklad davr keshi (5 daqiqa) -------------------- */

    /** kassaId → {sale_cash, sale_nocash, ret_cash, ret_nocash, cashout} (tiyin). -1 — bog'lanmagan. */
    record PeriodData(Map<Long, Map<String, Long>> byKassa, boolean ok, long at) {}


    /** kassaId null — barcha hujjatlar yig'indisi. */
    long msSum(Run run, LocalDate[] per, Long kassaId, String key) {
        PeriodData pd = periodData(per);
        if (!pd.ok()) {
            run.msFailed = true;
            // Zaxira: bot bazasi (faqat sotuv/vozvrat/rasxod uchun)
            if (kassaId == null) {
                long s = 0;
                for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) s += botFallback(k.getId(), per, key);
                return s;
            }
            return botFallback(kassaId, per, key);
        }
        if (kassaId == null) {
            long s = 0;
            for (Map<String, Long> m : pd.byKassa().values()) s += m.getOrDefault(key, 0L);
            return s;
        }
        return pd.byKassa().getOrDefault(kassaId, Map.of()).getOrDefault(key, 0L);
    }


    long botFallback(long kassaId, LocalDate[] per, String key) {
        return switch (key) {
            case "sale_cash" -> daySum(kassaId, per, DayRecord::getPrixodNaqd);
            case "sale_nocash" -> daySum(kassaId, per, d -> d.getPrixodKlik() + d.getPrixodTerminal());
            case "ret_cash" -> daySum(kassaId, per, DayRecord::getVozvratNaqd);
            case "ret_nocash" -> daySum(kassaId, per, DayRecord::getVozvratKlik);
            case "cashout" -> daySum(kassaId, per, d -> d.getRasxodNaqd() + d.getRasxodKlik());
            default -> 0;
        };
    }


    PeriodData periodData(LocalDate[] per) {
        String key = per[0] + ".." + per[1];
        PeriodData pd = periodCache.get(key);
        long now = System.currentTimeMillis();
        if (pd != null && now - pd.at() < 5 * 60_000L) return pd;
        Map<Long, Map<String, Long>> out = new HashMap<>();
        boolean ok = true;
        try {
            Map<String, Long> storeToKassa = new HashMap<>();
            Map<String, Long> groupToKassa = new HashMap<>();
            for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
                if (k.getMoyskladStoreId() != null && !k.getMoyskladStoreId().isBlank())
                    storeToKassa.put(k.getMoyskladStoreId(), k.getId());
                if (k.getMoyskladGroupId() != null && !k.getMoyskladGroupId().isBlank())
                    groupToKassa.putIfAbsent(k.getMoyskladGroupId(), k.getId());
            }
            for (MoySkladClient.MsDoc d : msClient.fetchSalesByMoment("retaildemand", per[0], per[1])) {
                if (!d.applicable()) continue;
                Long k = storeToKassa.getOrDefault(d.storeId(), -1L);
                add(out, k, "sale_cash", d.cashTiyin());
                add(out, k, "sale_nocash", d.noCashTiyin());
            }
            for (MoySkladClient.MsDoc d : msClient.fetchSalesByMoment("retailsalesreturn", per[0], per[1])) {
                if (!d.applicable()) continue;
                Long k = storeToKassa.getOrDefault(d.storeId(), -1L);
                add(out, k, "ret_cash", d.cashTiyin());
                add(out, k, "ret_nocash", d.noCashTiyin());
            }
            for (MoySkladClient.MsExpense e : msClient.fetchDrawerCashoutsByMoment(per[0], per[1])) {
                if (!e.applicable()) continue;
                add(out, storeToKassa.getOrDefault(e.storeId(), -1L), "cashout", e.sumTiyin());
            }
            for (MoySkladClient.MsExpense e : msClient.fetchDocsByMoment("cashout", per[0], per[1]))
                add(out, groupToKassa.getOrDefault(e.groupId(), -1L), "cashout", e.sumTiyin());
        } catch (Exception e) {
            log.warn("Shablon: MoySklad davr {} o'qilmadi: {}", key, e.getMessage());
            ok = false;
        }
        pd = new PeriodData(out, ok, ok ? now : now - 4 * 60_000L);   // xato bo'lsa 1 daqiqadan keyin qayta uriniladi
        periodCache.put(key, pd);
        return pd;
    }


    static void add(Map<Long, Map<String, Long>> out, Long k, String key, long v) {
        out.computeIfAbsent(k, x -> new HashMap<>()).merge(key, v, Long::sum);
    }

}
