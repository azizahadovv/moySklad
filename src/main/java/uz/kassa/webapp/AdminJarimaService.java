package uz.kassa.webapp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uz.kassa.bot.Sender;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Jarima;
import uz.kassa.domain.Jarima.Holat;
import uz.kassa.domain.Jarima.Tur;
import uz.kassa.domain.Kassa;
import uz.kassa.domain.Role;
import uz.kassa.repo.JarimaRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.service.BusinessException;
import uz.kassa.service.control.ControlNotifier;
import uz.kassa.service.jarima.JarimaConfig;
import uz.kassa.service.jarima.JarimaService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static uz.kassa.webapp.AdminApiService.mapOf;

/** 🌐 ⚖️ Жарималар: filtrli ro'yxat (holat · tur · xodim · otdel · sana oralig'i), karta, yopish/bekor, Excel chatga. */
@Service
@RequiredArgsConstructor
public class AdminJarimaService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final int PAGE = 50;

    private final JarimaService svc;
    private final JarimaRepo repo;
    private final JarimaConfig cfg;
    private final KassaRepo kassaRepo;
    private final ControlNotifier notifier;
    private final ExcelReportService excel;
    private final Sender sender;

    private JarimaService.Filter filter(String holat, String tur, long user, long kassa, String from, String to) {
        Holat h = null; Tur t = null;
        try { if (holat != null && !holat.isBlank()) h = Holat.valueOf(holat); } catch (IllegalArgumentException ignored) { }
        try { if (tur != null && !tur.isBlank()) t = Tur.valueOf(tur); } catch (IllegalArgumentException ignored) { }
        LocalDate f = null, tt = null;
        try { if (from != null && !from.isBlank()) f = LocalDate.parse(from); } catch (Exception ignored) { }
        try { if (to != null && !to.isBlank()) tt = LocalDate.parse(to); } catch (Exception ignored) { }
        if (f != null && tt != null && f.isAfter(tt)) { LocalDate x = f; f = tt; tt = x; }
        return new JarimaService.Filter(h, t, user, kassa, f, tt);
    }

    public Map<String, Object> list(AppUser u, String holat, String tur, long user, long kassa, String from, String to, int page) {
        JarimaService.Filter f = filter(holat, tur, user, kassa, from, to);
        List<Jarima> all = svc.list(f);
        // xodim va otdel tanlovlari — filtrsiz (davr bo'yicha) bazadan
        List<Jarima> base = svc.list(new JarimaService.Filter(null, null, null, null, f.from(), f.to()));
        Map<Long, String> users = new LinkedHashMap<>();
        Map<Long, Integer> ucount = new HashMap<>();
        for (Jarima j : base) if (j.getUserId() != null) { users.putIfAbsent(j.getUserId(), j.getXodim()); ucount.merge(j.getUserId(), 1, Integer::sum); }
        List<Map<String, Object>> userRows = new ArrayList<>();
        users.entrySet().stream().sorted((a, b) -> ucount.get(b.getKey()) - ucount.get(a.getKey()))
                .forEach(e -> userRows.add(mapOf("id", e.getKey(), "name", e.getValue(), "count", ucount.get(e.getKey()))));
        List<Map<String, Object>> kassalar = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) kassalar.add(mapOf("id", k.getId(), "name", k.getName()));
        List<Map<String, Object>> holatlar = new ArrayList<>();
        for (Holat h : Holat.values()) holatlar.add(mapOf("code", h.name(), "title", JarimaService.holatTitle(h), "count", all.stream().filter(j -> j.getHolat() == h).count()));
        List<Map<String, Object>> turlar = new ArrayList<>();
        for (Tur t : Tur.values()) turlar.add(mapOf("code", t.name(), "title", JarimaService.turTitle(t), "count", all.stream().filter(j -> j.getTur() == t).count()));

        int pages = Math.max(1, (all.size() + PAGE - 1) / PAGE);
        int p = Math.max(0, Math.min(page, pages - 1));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Jarima j : all.subList(p * PAGE, Math.min(all.size(), (p + 1) * PAGE))) rows.add(row(j));
        return mapOf(
                "rows", rows, "total", all.size(), "page", p, "pages", pages,
                "xulosa", mapOf("ochiq", JarimaService.sumOchiq(all), "yopiq", JarimaService.sumHolat(all, Holat.YOPIQ),
                        "bekor", JarimaService.sumHolat(all, Holat.BEKOR), "ogoh", all.stream().filter(j -> j.getHolat() == Holat.OGOH).count(),
                        "jamiOchiq", repo.sumByHolat(Holat.OCHIQ), "jamiOchiqSoni", repo.countByHolat(Holat.OCHIQ)),
                "users", userRows, "kassalar", kassalar, "holatlar", holatlar, "turlar", turlar,
                "enabled", cfg.enabled(), "superadmin", u.getRole() == Role.SUPERADMIN,
                "sozlama", mapOf("bazaviy", cfg.bazaviy(), "foizKarta", cfg.foiz(Tur.KARTA), "foizKg", cfg.foiz(Tur.KONTRAGENT),
                        "foizOt", cfg.foiz(Tur.OTGRUZKA), "ogohSoni", cfg.ogohSoni(), "payt", cfg.paytTuzatilmadi() ? "TUZATILMADI" : "TOPILDI",
                        "kunVaqt", cfg.kunVaqt().toString()));
    }

    private Map<String, Object> row(Jarima j) {
        return mapOf("id", j.getId(), "tur", j.getTur().name(), "turTitle", JarimaService.turTitle(j.getTur()),
                "holat", j.getHolat().name(), "holatTitle", JarimaService.holatTitle(j.getHolat()),
                "xodim", j.getXodim(), "userId", j.getUserId(), "kassa", j.getKassaId() == null ? "" : notifier.kassaName(j.getKassaId()),
                "manba", j.getManbaNomi(), "asos", j.getAsos(), "foiz", j.getFoiz(), "summa", j.getSumma(), "tartib", j.getTartib(),
                "sabab", j.getSabab(), "vaqt", LocalDateTime.ofInstant(j.getCreatedAt(), cfg.zone()).format(DTF), "sana", j.getSana().toString(),
                "yopilgan", j.getYopilganAt() == null ? "" : LocalDateTime.ofInstant(j.getYopilganAt(), cfg.zone()).format(DTF),
                "yopgan", j.getYopganUserId() == null ? "" : notifier.userName(j.getYopganUserId()), "izoh", j.getIzoh() == null ? "" : j.getIzoh(),
                "xabar", j.getXabarAt() != null, "kunlik", j.getKunlikAt() != null);
    }

    public Map<String, Object> one(AppUser u, long id) {
        Jarima j = repo.findById(id).orElseThrow(() -> new BusinessException("Ёзув топилмади"));
        Map<String, Object> m = new LinkedHashMap<>(row(j));
        m.put("superadmin", u.getRole() == Role.SUPERADMIN);
        return m;
    }

    public Map<String, Object> close(AppUser u, long id, String reason, boolean bekor) {
        if (u.getRole() != Role.SUPERADMIN) throw new BusinessException("Ёпиш/бекор қилиш фақат SuperAdmin учун");
        Jarima j = svc.close(id, u, reason, bekor);
        return mapOf("result", "OK", "holat", j.getHolat().name());
    }

    public Map<String, Object> excel(AppUser u, String holat, String tur, long user, long kassa, String from, String to) {
        if (u.getTelegramId() == null) throw new BusinessException("Telegram уланмаган — файл чатга юборилмайди");
        List<Jarima> list = svc.list(filter(holat, tur, user, kassa, from, to));
        if (list.isEmpty()) throw new BusinessException("Фильтр бўйича ёзув йўқ");
        byte[] data = excel.buildTable("Жарималар", JarimaService.XLS_COLS, svc.xlsRows(list));
        sender.sendDocument(u.getTelegramId(), data, "jarima-" + LocalDate.now(cfg.zone()) + ".xlsx",
                "⚖️ Жарималар: " + list.size() + " та · очиқ <b>" + uz.kassa.bot.TextUtil.fmt(JarimaService.sumOchiq(list)) + "</b> сўм");
        return mapOf("count", list.size());
    }
}
