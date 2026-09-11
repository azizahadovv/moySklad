package uz.kassa.service.ombor.checks;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.kassa.domain.OmborQoida;
import uz.kassa.domain.OmborTovar;
import uz.kassa.repo.OmborTovarRepo;
import uz.kassa.service.ombor.OmborChecker;
import java.util.*;
import static uz.kassa.bot.TextUtil.esc;

/**
 * DUBLIKAT — faol tovarlar orasida nom (normallashgan) / shtrix-kod / artikul / kod to'qnashuvi.
 * Natija JUFTLIK ko'rinishida: «Название 1 (Код 1) ↔ Название 2 (Код 2) — sabab» (пробелы · регистр · слэш · дефис · …),
 * Excel hisoboti ham shu tartibda ({@link #pairs}).
 */
@Component
@RequiredArgsConstructor
public class DublikatChecker implements OmborChecker {

    private final OmborTovarRepo repo;

    /** Juftlik: asosiy (birinchi) tovar va dublikat, mos kelish sababi, qaysi kalit bo'yicha topilgan. */
    public record Pair(OmborTovar a, OmborTovar b, String reason, String key) {}

    @Override public String code() { return "DUBLIKAT"; }
    @Override public String help() { return "Dublikat tovarlar. params: {\"keys\":\"name,barcode,article,code\"}"; }

    @Override
    public List<Found> run(OmborQoida rule, JsonNode p) {
        List<Found> out = new ArrayList<>();
        for (var g : groups(p.path("keys").asText("name,barcode,article")).entrySet()) {
            List<OmborTovar> list = g.getValue();
            String key = g.getKey().substring(0, g.getKey().indexOf(':'));
            OmborTovar main = list.get(0);
            StringBuilder d = new StringBuilder(label(key) + " bir xil — " + list.size() + " ta tovar:\n");
            for (int i = 1; i < list.size(); i++) {
                OmborTovar b = list.get(i);
                d.append("• <b>").append(esc(main.getName())).append("</b> (").append(esc(code(main))).append(") ↔ <b>")
                 .append(esc(b.getName())).append("</b> (").append(esc(code(b))).append(") — ").append(esc(reason(main, b, key))).append("\n");
            }
            d.append("MoySklad'da bittasini qoldirib, qolganini arxivlang (qoldig'i bo'lsa avval ko'chiring).");
            out.add(Found.of("tovar", g.getKey().length() > 190 ? g.getKey().substring(0, 190) : g.getKey(), null,
                    "Dublikat (" + label(key) + "): " + cut(main.getName(), 60) + " ×" + list.size(), d.toString()));
        }
        return out;
    }

    /** Excel va tahlil uchun juftliklar (asosiy = ro'yxatda birinchi, nomi bo'yicha). */
    public List<Pair> pairs(String keysCsv) {
        List<Pair> out = new ArrayList<>();
        for (var g : groups(keysCsv).entrySet()) {
            String key = g.getKey().substring(0, g.getKey().indexOf(':'));
            List<OmborTovar> list = g.getValue();
            for (int i = 1; i < list.size(); i++) out.add(new Pair(list.get(0), list.get(i), reason(list.get(0), list.get(i), key), key));
        }
        return out;
    }

    /** kalit:qiymat → tartiblangan guruh (≥2). Bitta juftlik bir necha kalit bo'yicha topilsa faqat birinchisi qoladi. */
    private Map<String, List<OmborTovar>> groups(String keysCsv) {
        Set<String> keys = new LinkedHashSet<>();
        for (String k : keysCsv.split(",")) if (!k.isBlank()) keys.add(k.trim().toLowerCase());
        List<OmborTovar> all = repo.findByArchivedFalse();
        Map<String, List<OmborTovar>> out = new LinkedHashMap<>();
        Set<String> seenPairs = new HashSet<>();
        for (String k : keys) {
            Map<String, List<OmborTovar>> groups = new HashMap<>();
            for (OmborTovar t : all) {
                if (!t.getType().equals("product") && !t.getType().equals("bundle")) continue;   // variantlar bir nomda bo'ladi
                String v = switch (k) {
                    case "name" -> t.getNameNorm();
                    case "barcode" -> t.getBarcode();
                    case "article" -> t.getArticle().trim().toLowerCase();
                    case "code" -> t.getCode().trim().toLowerCase();
                    default -> "";
                };
                if (v.length() < 3) continue;
                groups.computeIfAbsent(v, x -> new ArrayList<>()).add(t);
            }
            for (var e : groups.entrySet()) {
                if (e.getValue().size() < 2) continue;
                List<OmborTovar> g = new ArrayList<>(e.getValue());
                g.sort(Comparator.comparing(OmborTovar::getCode).thenComparing(OmborTovar::getName));
                String pk = g.get(0).getMsId() + "|" + g.get(1).getMsId();
                if (!seenPairs.add(pk)) continue;
                out.put(k + ":" + e.getKey(), g);
            }
        }
        return out;
    }

    /** Mos kelish sababi (skrinshotdagi uslub): пробелы · регистр · слэш · дефис · точка · скобки · запятая; barcode/article — shu kalit. */
    public static String reason(OmborTovar a, OmborTovar b, String key) {
        if (key.equals("barcode")) return "штрих-код совпадает (" + a.getBarcode() + ")";
        if (key.equals("article")) return "артикул совпадает (" + a.getArticle() + ")";
        if (key.equals("code")) return "код совпадает (" + a.getCode() + ")";
        String x = a.getName(), y = b.getName();
        List<String> r = new ArrayList<>();
        if (count(x, ' ') != count(y, ' ')) r.add("пробелы");
        if (count(x, '/') != count(y, '/')) r.add("слэш");
        if (count(x, '-') != count(y, '-')) r.add("дефис");
        if (count(x, '.') != count(y, '.')) r.add("точка");
        if (count(x, ',') != count(y, ',')) r.add("запятая");
        if (count(x, '(') != count(y, '(') || count(x, '"') != count(y, '"')) r.add("скобки/кавычки");
        String sx = x.replaceAll("[^\\p{L}\\p{N}]+", ""), sy = y.replaceAll("[^\\p{L}\\p{N}]+", "");
        if (!sx.equals(sy) && sx.equalsIgnoreCase(sy)) r.add("регистр");
        else if (!sx.equalsIgnoreCase(sy) && r.isEmpty()) r.add("кириллица/латиница");
        if (r.isEmpty()) r.add(x.equals(y) ? "полностью одинаковое название" : "регистр");
        return String.join("/", r);
    }

    private static int count(String s, char c) { int n = 0; for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++; return n; }
    private static String code(OmborTovar t) { return t.getCode().isBlank() ? (t.getArticle().isBlank() ? "kod yo'q" : t.getArticle()) : t.getCode(); }

    private static String label(String k) {
        return switch (k) { case "name" -> "nom"; case "barcode" -> "shtrix-kod"; case "article" -> "artikul"; case "code" -> "kod"; default -> k; };
    }

    static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }
}
