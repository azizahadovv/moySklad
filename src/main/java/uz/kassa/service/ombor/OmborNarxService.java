package uz.kassa.service.ombor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.domain.*;
import uz.kassa.gsheets.GoogleSheetsClient;
import uz.kassa.repo.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 🏬 Yetkazuvchi narxlari (docs/OMBOR-TZ.md B3): faqat priyomkalardan haqiqiy narx (PRIYOMKA) — tovar kelgach narx tahlil qilinadi,
 * qo'lda/Sheets narx kiritish yo'q (2026-09-12 qarori). Google Sheets «Етказувчилар», «Даврлар» — yetkazuvchi profili va davrlar.
 * Yetkazuvchi profili priyomkadan avtomatik yaratiladi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OmborNarxService {

    public static final List<String> TABS = List.of("Етказувчилар", "Даврлар");

    public static final String PRIYOMKA = "PRIYOMKA";

    private final OmborNarxRepo repo;
    private final OmborYetkazuvchiRepo supRepo;
    private final OmborHujjatRepo hujjatRepo;
    private final OmborPozitsiyaRepo posRepo;
    private final OmborTovarRepo tovarRepo;
    private final OmborDavrRepo davrRepo;
    private final GoogleSheetsClient gs;
    private final OmborConfig cfg;

    /* ==================== PRIYOMKA → narx ==================== */

    /** Oxirgi docs_days kundagi priyomkalar: har pozitsiya narxi (agent, tovar, sana). Qaytadi: yangi yozuvlar. */
    public int fromSupplies() {
        int n = 0;
        Map<String, OmborYetkazuvchi> sups = new HashMap<>();
        for (OmborHujjat h : hujjatRepo.supplies("supply", LocalDateTime.now(cfg.zone()).minusDays(cfg.docsDays()))) {
            if (Boolean.FALSE.equals(h.getApplicable()) || h.getMoment() == null) continue;
            OmborYetkazuvchi s = sups.computeIfAbsent(h.getAgentMsId(), id -> supRepo.findById(id)
                    .orElse(OmborYetkazuvchi.builder().agentMsId(id).name(h.getAgentName()).build()));
            if (s.getName().isBlank()) s.setName(h.getAgentName());
            LocalDate d = h.getMoment().toLocalDate();
            if (s.getLastSupply() == null || s.getLastSupply().isBefore(d)) s.setLastSupply(d);
            for (OmborPozitsiya p : posRepo.findByHujjatId(h.getId())) {
                if (p.getPrice() <= 0) continue;
                if (repo.findFirstByAgentMsIdAndProductMsIdAndAtDateAndSource(h.getAgentMsId(), p.getProductMsId(), d, PRIYOMKA).isPresent()) continue;
                repo.save(OmborNarx.builder().agentMsId(h.getAgentMsId()).productMsId(p.getProductMsId()).price(p.getPrice()).atDate(d)
                        .source(PRIYOMKA).note("№" + h.getDocNo()).build());
                n++;
            }
        }
        for (OmborYetkazuvchi s : sups.values()) {
            s.setSuppliesN((int) hujjatRepo.supplies("supply", LocalDateTime.now(cfg.zone()).minusDays(400)).stream()
                    .filter(h -> s.getAgentMsId().equals(h.getAgentMsId())).count());
            supRepo.save(s);
        }
        return n;
    }

    /* ==================== yetkazuvchi / tovar ==================== */

    /** Yetkazuvchini nomi bo'yicha (bor bo'lsa) yoki MoySklad kontragent nomi bo'yicha yaratish (Sheets «Етказувчилар»). */
    public OmborYetkazuvchi supplier(String name) {
        Optional<OmborYetkazuvchi> s = supRepo.findFirstByNameIgnoreCase(name);
        if (s.isPresent()) return s.get();
        // priyomkalarda uchragan kontragent bo'lsa — id shu yerdan
        for (OmborHujjat h : hujjatRepo.supplies("supply", LocalDateTime.now(cfg.zone()).minusDays(400)))
            if (h.getAgentName().equalsIgnoreCase(name))
                return supRepo.save(OmborYetkazuvchi.builder().agentMsId(h.getAgentMsId()).name(h.getAgentName()).build());
        // MoySklad'da yo'q — mahalliy id (qo'lda kiritilgan yetkazuvchi)
        return supRepo.save(OmborYetkazuvchi.builder().agentMsId("local:" + OmborTovar.norm(name)).name(name).build());
    }

    public OmborTovar product(String ref) {
        List<OmborTovar> found = tovarRepo.search(OmborTovar.norm(ref));
        OmborTovar exact = found.stream().filter(t -> t.getArticle().equalsIgnoreCase(ref) || t.getCode().equalsIgnoreCase(ref) || t.getName().equalsIgnoreCase(ref)).findFirst().orElse(null);
        if (exact == null && found.size() == 1) exact = found.get(0);
        if (exact == null) throw new IllegalArgumentException(found.isEmpty() ? "tovar topilmadi: " + ref : found.size() + " ta tovar mos keldi, artikulni yozing");
        return exact;
    }

    /* ==================== so'rovlar ==================== */

    /** Tovar bo'yicha har yetkazuvchining oxirgi priyomka narxi, 365 kun ichida. Eski QOLDA/SHEETS yozuvlari hisobga olinmaydi. */
    public Map<String, OmborNarx> lastPrices(String productMsId) {
        Map<String, OmborNarx> out = new LinkedHashMap<>();
        LocalDate lim = LocalDate.now(cfg.zone()).minusDays(365);
        for (OmborNarx n : repo.findByProductMsIdOrderByAtDateDesc(productMsId)) {
            if (n.getAtDate().isBefore(lim) || !PRIYOMKA.equals(n.getSource())) continue;
            out.putIfAbsent(n.getAgentMsId(), n);
        }
        return out;
    }

    /** (agent, tovar) bo'yicha oxirgi ikki priyomka narxi: [oxirgi, oldingi] (oldingi bo'lmasa null). */
    public OmborNarx[] lastTwo(String agent, String product) {
        List<OmborNarx> l = repo.findByAgentMsIdAndProductMsIdOrderByAtDateDescIdDesc(agent, product).stream()
                .filter(n -> PRIYOMKA.equals(n.getSource())).toList();
        OmborNarx last = l.isEmpty() ? null : l.get(0), prev = null;
        for (int i = 1; i < l.size(); i++) if (l.get(i).getPrice() != last.getPrice()) { prev = l.get(i); break; }
        return new OmborNarx[]{last, prev};
    }

    public List<Object[]> pairs() { return repo.pairs(); }

    /* ==================== Google Sheets ==================== */

    /** Ikki varaqni o'qish (yetkazuvchi profili, davrlar). Qaytadi: natija matni (yoki null — Sheets sozlanmagan). */
    public String pullSheets(Long actorId) {
        if (!gs.configured()) return null;
        StringBuilder sb = new StringBuilder();
        try { gs.ensureTabs(TABS); } catch (Exception e) { log.warn("Sheets ensureTabs: {}", e.getMessage()); }
        // Етказувчилар: Nomi | Davlat | Muddat ertalab | Muddat kechqurun | Cutoff soat | Landed koef | Min tarix | Faol
        int n = 0, err = 0;
        try {
            for (List<String> r : gs.get("Етказувчилар!A2:H300")) {
                if (r.isEmpty() || cell(r, 0).isBlank()) continue;
                try {
                    OmborYetkazuvchi s = supplier(cell(r, 0));
                    s.setCountry(cell(r, 1));
                    if (!cell(r, 2).isBlank()) s.setLeadDaysAm(Integer.parseInt(cell(r, 2).replaceAll("\\D", "")));
                    if (!cell(r, 3).isBlank()) s.setLeadDaysPm(Integer.parseInt(cell(r, 3).replaceAll("\\D", "")));
                    if (!cell(r, 4).isBlank()) s.setCutoffHour(Integer.parseInt(cell(r, 4).replaceAll("\\D", "")));
                    if (!cell(r, 5).isBlank()) s.setLandedCoef(new BigDecimal(cell(r, 5).replace(",", ".")));
                    if (!cell(r, 6).isBlank()) s.setMinHistory(Integer.parseInt(cell(r, 6).replaceAll("\\D", "")));
                    if (!cell(r, 7).isBlank()) s.setActive(!cell(r, 7).trim().equalsIgnoreCase("FALSE") && !cell(r, 7).trim().equals("0"));
                    supRepo.save(s); n++;
                } catch (Exception e) { err++; }
            }
            sb.append("Етказувчилар: ").append(n).append(err > 0 ? " (xato " + err + ")" : "").append("\n");
        } catch (Exception e) { sb.append("Етказувчилар: ⚠️ ").append(e.getMessage()).append("\n"); }
        // Даврлар: Tur | Tovar yoki #Guruh | Kod | Dan | Gacha | Izoh
        n = 0; err = 0;
        try {
            for (List<String> r : gs.get("Даврлар!A2:F500")) {
                if (r.isEmpty() || cell(r, 0).isBlank() || cell(r, 1).isBlank()) continue;
                try {
                    String kind = cell(r, 0).trim().toUpperCase();
                    if (!List.of("AKSIYA", "MAVSUM", "YANGI", "SOVISH").contains(kind)) throw new IllegalArgumentException("tur");
                    LocalDate from = parseDate(cell(r, 3)), to = parseDate(cell(r, 4));
                    String what = cell(r, 1).trim();
                    boolean folder = what.startsWith("#");
                    String pid = folder ? null : product(what).getMsId();
                    String fname = folder ? what.substring(1).trim() : null;
                    boolean exists = folder ? davrRepo.existsByKindAndFolderNameAndFromDate(kind, fname, from) : davrRepo.existsByKindAndProductMsIdAndFromDate(kind, pid, from);
                    if (exists) continue;
                    davrRepo.save(OmborDavr.builder().kind(kind).productMsId(pid).folderName(fname).code(cell(r, 2)).fromDate(from).toDate(to).note(cell(r, 5)).createdBy(actorId).build());
                    n++;
                } catch (Exception e) { err++; }
            }
            sb.append("Даврлар: ").append(n).append(" yangi").append(err > 0 ? " (xato " + err + ")" : "").append("\n");
        } catch (Exception e) { sb.append("Даврлар: ⚠️ ").append(e.getMessage()).append("\n"); }
        return sb.toString();
    }

    private static String cell(List<String> r, int i) { return i < r.size() && r.get(i) != null ? r.get(i).trim() : ""; }

    private static LocalDate parseDate(String s) {
        s = s.trim();
        if (s.matches("\\d{2}\\.\\d{2}\\.\\d{4}")) return LocalDate.parse(s, java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        return LocalDate.parse(s);
    }
}
