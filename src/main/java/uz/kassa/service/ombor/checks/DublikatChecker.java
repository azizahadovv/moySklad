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
 * DUBLIKAT — faol tovarlar orasida nom (normallashgan) / shtrix-kod / artikul / kod to'qnashuvi,
 * shuningdek NOMI O'XSHASH (lekin bir xil emas) tovarlar — masalan «Monoblok Ziffler 24" H610» va
 * «Monoblok Ziffler 24" H610 DDR5» (bir necha so'z farqi bilan). {@code keys}ga {@code name_fuzzy}
 * qo'shilsa nomlar SO'Z CHEGARASI bo'yicha qisqichlanadi (contained): qisqaroq nom uzunroq nomning
 * ichida BUTUN SO'ZLAR sifatida uchraydimi (masalan «mouse rapoo n 100» ⊂ «mouse rapoo n 100 white»).
 * <p>
 * Bu Jaccard/so'z-to'plami o'xshashligidan ATAYLAB qat'iyroq: real katalogda ko'p tovar nomi
 * («CPU i5 10400» / «CPU i5 10400F», rang/hajm bo'yicha kartrijlar va h.k.) faqat BITTA so'z bilan
 * farqlanadi va shu bitta so'z aynan MODEL/RANG kodi — ya'ni haqiqatda BOSHQA tovar. Oddiy so'z-to'plami
 * o'xshashligi bunday holatlarni ham dublikat deb ko'rsatib, foydasiz shovqin yaratadi (tekshirilgan:
 * 5400+ tovarli katalogda 2882 ta soxta juftlik). So'z chegarasi bo'yicha "ichida bor" tekshiruvi esa
 * faqat «bazaviy nom + oxiriga/oralig'iga QO'SHILGAN so'z(lar)» holatini topadi — xuddi shu real
 * katalogda sinovdan o'tkazilganda atigi ~130 ta oqilona nomzod berdi.
 * Natija JUFTLIK ko'rinishida: «Название 1 (Код 1) ↔ Название 2 (Код 2) — sabab»
 * (пробелы · регистр · слэш · дефис · … yoki qo'shilgan so'z), Excel hisoboti ham shu tartibda ({@link #pairs}).
 */
@Component
@RequiredArgsConstructor
public class DublikatChecker implements OmborChecker {

    private final OmborTovarRepo repo;

    private static final String FUZZY_KEY = "name_fuzzy";
    /** Qisqaroq (bazaviy) nom kamida shu uzunlikda (model kodi birlashtirilgandan keyin) va XOM so'z
     *  sonida bo'lishi kerak — juda qisqa/umumiy bazalar («Zaryadnik Acer», «KEYS MyPro») ko'plab turli
     *  brend/modelga mos kelib shovqin beradi; so'z soni XOM (birlashtirishdan oldingi) hisoblanadi, aks
     *  holda «epson L 8050» kabi nomlar kodi birlashgach 2 so'zga tushib, bekorga chiqarib tashlanadi. */
    private static final int MIN_BASE_LEN = 10, MIN_BASE_WORDS = 3;
    /** Uzunroq nomda ortiqcha qancha belgi (so'zlar bilan) ruxsat etiladi — undan ko'p farq bo'lsa boshqa tovar. */
    private static final int MAX_EXTRA_CHARS = 30;

    /** Juftlik: asosiy (birinchi) tovar va dublikat, mos kelish sababi, qaysi kalit bo'yicha topilgan. */
    public record Pair(OmborTovar a, OmborTovar b, String reason, String key) {}

    @Override public String code() { return "DUBLIKAT"; }
    @Override public String help() { return "Dublikat tovarlar. params: {\"keys\":\"name,barcode,article,code,name_fuzzy\"}"; }

    @Override
    public List<Found> run(OmborQoida rule, JsonNode p) {
        List<Found> out = new ArrayList<>();
        for (var g : groups(p.path("keys").asText("name,barcode,article")).entrySet()) {
            List<OmborTovar> list = g.getValue();
            String key = g.getKey().substring(0, g.getKey().indexOf(':'));
            boolean fuzzy = key.equals(FUZZY_KEY);
            OmborTovar main = list.get(0);
            StringBuilder d = new StringBuilder((fuzzy ? "Nomi o'xshash, lekin bir xil emas" : label(key) + " bir xil")
                    + " — " + list.size() + " ta tovar:\n");
            for (int i = 1; i < list.size(); i++) {
                OmborTovar b = list.get(i);
                d.append("• <b>").append(esc(main.getName())).append("</b> (").append(esc(code(main))).append(") ↔ <b>")
                 .append(esc(b.getName())).append("</b> (").append(esc(code(b))).append(") — ").append(esc(reason(main, b, key))).append("\n");
            }
            d.append(fuzzy ? "Tekshiring: haqiqatan bitta tovarmi (dublikat) yoki qasddan boshqa variant (rang/o'lcham)mi — "
                    + "bitta bo'lsa MoySklad'da birlashtiring (bittasini arxivlang), boshqa variant bo'lsa MoySklad «Модификация»"
                    + " orqali rasmiylashtiring."
                    : "MoySklad'da bittasini qoldirib, qolganini arxivlang (qoldig'i bo'lsa avval ko'chiring).");
            out.add(Found.of("tovar", g.getKey().length() > 190 ? g.getKey().substring(0, 190) : g.getKey(), null,
                    (fuzzy ? "Ehtimoliy dublikat: " : "Dublikat (" + label(key) + "): ") + cut(main.getName(), 60) + " ×" + list.size(), d.toString()));
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
        List<OmborTovar> products = repo.findByArchivedFalse().stream()
                .filter(t -> t.getType().equals("product") || t.getType().equals("bundle"))   // variantlar bir nomda bo'ladi
                .toList();
        Map<String, List<OmborTovar>> out = new LinkedHashMap<>();
        Set<String> seenPairs = new HashSet<>();
        for (String k : keys) {
            if (k.equals(FUZZY_KEY)) {
                for (OmborTovar[] pr : fuzzyPairs(products)) {
                    String pk = pr[0].getMsId() + "|" + pr[1].getMsId();
                    if (!seenPairs.add(pk)) continue;
                    out.put(FUZZY_KEY + ":" + pk, List.of(pr[0], pr[1]));
                }
                continue;
            }
            Map<String, List<OmborTovar>> byValue = new HashMap<>();
            for (OmborTovar t : products) {
                String v = switch (k) {
                    case "name" -> t.getNameNorm();
                    case "barcode" -> t.getBarcode();
                    case "article" -> t.getArticle().trim().toLowerCase();
                    case "code" -> t.getCode().trim().toLowerCase();
                    default -> "";
                };
                if (v.length() < 3) continue;
                byValue.computeIfAbsent(v, x -> new ArrayList<>()).add(t);
            }
            for (var e : byValue.entrySet()) {
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

    /**
     * Nomi o'xshash (lekin bir xil emas) juftliklar: qisqaroq nom uzunroq nomning ichida SO'Z
     * CHEGARASI bilan (butun so'z(lar) sifatida) uchraydimi. Model kodlari («L 8050» ↔ «L8050»,
     * «MF 3010» ↔ «MF3010») qo'shilgan/qo'shilmagan probel bilan yozilishi mumkin — solishtirishdan
     * oldin qisqa harf kodi (≤3 harf) + darhol undan keyingi 3+ xonali raqam BITTA so'zga birlashtiriladi.
     * Juda qisqa/umumiy baza («Zaryadnik Acer», «KEYS MyPro») ko'plab boshqa modelga mos kelib shovqin
     * bermasligi uchun XOM (birlashtirilmagan) so'z soni {@value #MIN_BASE_WORDS}dan kam bo'lmasin talab
     * qilinadi. Uzunlik bo'yicha saralab, o'sish {@value #MAX_EXTRA_CHARS} belgidan oshsa to'xtatiladi —
     * to'liq N² solishtirish shart emas.
     */
    private List<OmborTovar[]> fuzzyPairs(List<OmborTovar> products) {
        List<OmborTovar> cand = new ArrayList<>();
        Map<String, String> fusedOf = new HashMap<>();
        for (OmborTovar t : products) {
            String[] raw = words(t.getName());
            if (raw.length < MIN_BASE_WORDS) continue;
            String fused = String.join(" ", fuseCodes(raw));
            if (fused.length() < MIN_BASE_LEN) continue;
            fusedOf.put(t.getMsId(), fused);
            cand.add(t);
        }
        cand.sort(Comparator.comparingInt(t -> fusedOf.get(t.getMsId()).length()));
        List<OmborTovar[]> out = new ArrayList<>();
        for (int i = 0; i < cand.size(); i++) {
            String shortSp = fusedOf.get(cand.get(i).getMsId());
            String shortPad = " " + shortSp + " ";
            for (int j = i + 1; j < cand.size(); j++) {
                String longSp = fusedOf.get(cand.get(j).getMsId());
                if (longSp.length() - shortSp.length() > MAX_EXTRA_CHARS) break;   // saralangan — keyingilari ham oshadi
                if (longSp.equals(shortSp)) continue;   // aynan bir xil — "name" kaliti allaqachon ushlaydi
                if ((" " + longSp + " ").contains(shortPad)) out.add(new OmborTovar[]{cand.get(i), cand.get(j)});
            }
        }
        return out;
    }

    /** Nomni so'zlarga ajratish (chegara saqlanadi): kichik harf, ё/ў/қ/ғ/ҳ almashtirish. */
    private static String[] words(String name) {
        String t = name.toLowerCase().replace('ё', 'е').replace('ў', 'у').replace('қ', 'к').replace('ғ', 'г').replace('ҳ', 'х');
        List<String> out = new ArrayList<>();
        for (String w : t.split("[^\\p{L}\\p{N}]+")) if (!w.isBlank()) out.add(w);
        return out.toArray(new String[0]);
    }

    /** Qisqa harf kodi (≤3 harf) + darhol undan keyingi 3+ xonali raqam so'zlarini bittaga qo'shadi («l»+«8050» → «l8050»). */
    private static List<String> fuseCodes(String[] ws) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < ws.length; i++) {
            String w = ws[i], nx = i + 1 < ws.length ? ws[i + 1] : null;
            if (w.length() <= 3 && w.matches("[a-z]+") && nx != null && nx.matches("\\d{3,}")) { out.add(w + nx); i++; }
            else out.add(w);
        }
        return out;
    }

    /** Mos kelish sababi (skrinshotdagi uslub): пробелы · регистр · слэш · дефис · точка · скобки · запятая; barcode/article — shu kalit. */
    public static String reason(OmborTovar a, OmborTovar b, String key) {
        if (key.equals("barcode")) return "штрих-код совпадает (" + a.getBarcode() + ")";
        if (key.equals("article")) return "артикул совпадает (" + a.getArticle() + ")";
        if (key.equals("code")) return "код совпадает (" + a.getCode() + ")";
        if (key.equals(FUZZY_KEY)) {
            String sa = String.join(" ", fuseCodes(words(a.getName()))), sb = String.join(" ", fuseCodes(words(b.getName())));
            String shortSp = sa.length() <= sb.length() ? sa : sb, longSp = sa.length() <= sb.length() ? sb : sa;
            String extra = longSp.replaceFirst(java.util.regex.Pattern.quote(shortSp), "").trim();
            return "nomi bir xil, qo'shimcha so'z(lar): «" + (extra.isEmpty() ? "?" : extra) + "»";
        }
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
        return switch (k) {
            case "name" -> "nom"; case "barcode" -> "shtrix-kod"; case "article" -> "artikul"; case "code" -> "kod";
            case FUZZY_KEY -> "o'xshash nom";
            default -> k;
        };
    }

    static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }
}
