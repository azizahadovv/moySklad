package uz.kassa.service.jarima;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.bot.Sender;
import uz.kassa.bot.TextUtil;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.ClickAccount;
import uz.kassa.domain.Jarima;
import uz.kassa.repo.ClickAccountRepo;
import uz.kassa.service.AuditService;
import uz.kassa.service.NotifySwitches;
import uz.kassa.service.control.ControlNotifier;
import uz.kassa.service.moysklad.MoySkladClient;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static uz.kassa.bot.TextUtil.esc;

/**
 * ⚠️ Karta farqi nazorati (docs/JARIMA.md §KARTA-FARQ, 2026-09-22).
 * Karta (haqiqiy) qoldig'i va MoySklad qoldig'i farqi — kim tomonda xato ekani yo'nalishdan aniqlanadi:
 *  • MoySklad > karta (farq > 0): pul kartadan chiqib ketgan, hujjat yo'q — kartadan xarajat qilinib xabar berilmagan.
 *    Karta mas'uliga xabar; {@code jarima.farq_min} daqiqada tuzatilmasa — ⚖️ KARTA jarimasi (bir epizodda bir marta).
 *  • Karta > MoySklad (farq < 0): pul kartada bor, hujjat yo'q — MoySklad'ga otgruzka/to'lov kiritilmagan.
 *    Otdel rahbari va admin'ga xabar; karta mas'ulining aybi emas, jarima yozilmaydi.
 * Epizod FAQAT yangi karta qoldig'i kelganda ochiladi (eski qoldiqni yangi MoySklad qiymati bilan solishtirib yolg'on
 * farq chiqmasin — u «⏰ янгиланмаган» qoidasi ishi), farq 0 bo'lgan zahoti (istalgan tekshiruvda) yopiladi.
 * Tekshiruv Jobs.clickFarqTick dan har 5 daqiqada, faqat Click hisobot oynasi ichida; epizod boshi oynadan oldin
 * bo'lsa bugungi oyna boshidan qayta sanaladi (tun qo'shilmaydi).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClickFarqService {

    private final ClickAccountRepo clickRepo;
    private final MoySkladClient msClient;
    private final JarimaService jarima;
    private final JarimaConfig cfg;
    private final NotifySwitches sw;
    private final ControlNotifier notifier;
    private final Sender sender;
    private final AuditService audit;

    /** @param windowStart bugungi Click hisobot oynasining boshi (Jobs.clickWindowStart). */
    public void tick(Instant windowStart) {
        List<ClickAccount> accounts = clickRepo.findByActiveTrueOrderByIdAsc();
        if (accounts.isEmpty()) return;
        Map<String, Long> ms;
        try { ms = msClient.fetchAccountBalancesTiyin(); }
        catch (Exception e) { log.warn("Karta farqi: MoySklad qoldiqlari o'qilmadi — {}", e.getMessage()); return; }
        Instant now = Instant.now();
        for (ClickAccount c : accounts) {
            try { evaluate(c, ms, now, windowStart); }
            catch (Exception e) { log.warn("Karta farqi ({}): {}", c.getName(), e.getMessage()); }
        }
    }

    private void evaluate(ClickAccount c, Map<String, Long> ms, Instant now, Instant ws) {
        String aid = c.getMoyskladAccountId();
        Long msv = aid == null || aid.isBlank() ? null : ms.get(aid);
        if (msv == null || c.getCardBalance() == null || c.getCardBalanceAt() == null) return;
        long farqNow = msv - c.getCardBalance();
        boolean newReceipt = c.getFarqEvalAt() == null || c.getCardBalanceAt().isAfter(c.getFarqEvalAt());
        long cur = c.getFarqTiyin();
        boolean changed = false;

        if (farqNow == 0) {
            if (cur != 0) {
                audit.log(null, "KARTA_FARQ_YOPILDI", "click", c.getId(), c.getName() + " farq " + cur + " → 0");
                c.setFarqTiyin(0); c.setFarqSince(null); c.setFarqNotifiedAt(null); c.setFarqJarimaAt(null);
                changed = true;
            }
        } else if (newReceipt) {
            if (cur == 0 || Long.signum(cur) != Long.signum(farqNow)) {
                c.setFarqTiyin(farqNow); c.setFarqSince(now); c.setFarqNotifiedAt(null); c.setFarqJarimaAt(null);
                audit.log(null, "KARTA_FARQ_TOPILDI", "click", c.getId(), c.getName() + " farq " + farqNow + " (ms " + msv + ", karta " + c.getCardBalance() + ")");
                changed = true;
            } else if (cur != farqNow) { c.setFarqTiyin(farqNow); changed = true; }
        }
        if (newReceipt) { c.setFarqEvalAt(c.getCardBalanceAt()); changed = true; }

        // Epizod kechagi kundan qolgan bo'lsa — bugungi oyna boshidan qayta sanaladi (yangi kun — yangi muddat)
        if (c.getFarqSince() != null && c.getFarqSince().isBefore(ws)) { c.setFarqSince(ws); c.setFarqJarimaAt(null); changed = true; }

        long f = c.getFarqTiyin();
        if (f != 0 && c.getFarqNotifiedAt() == null) {
            notifyOpen(c, msv, now);
            c.setFarqNotifiedAt(now); changed = true;
        }
        int min = cfg.farqMin();
        if (f > 0 && min > 0 && c.getFarqSince() != null && c.getFarqJarimaAt() == null
                && Duration.between(c.getFarqSince(), now).toMinutes() >= min) {
            c.setFarqJarimaAt(now); changed = true;
            try {
                Optional<Jarima> j = jarima.kartaFarq(c, f, min);
                j.ifPresent(x -> notifyJarima(c, x, min));
            } catch (Exception e) { log.warn("Karta farqi jarimasi ({}): {}", c.getName(), e.getMessage()); }
        }
        if (changed) clickRepo.save(c);
    }

    /** Epizod boshida bir marta: guruhga (mention bilan) va tegishli odamlarga shaxsiy. */
    private void notifyOpen(ClickAccount c, long msv, Instant now) {
        long f = c.getFarqTiyin();
        int min = cfg.farqMin();
        StringBuilder sb = new StringBuilder("⚠️ <b>КАРТА ФАРҚИ</b> — 💳 ").append(esc(c.getName()));
        if (c.getKassaId() != null) sb.append(" · ").append(esc(notifier.kassaName(c.getKassaId())));
        sb.append("\n📦 MoySklad: <b>").append(TextUtil.fmtTiyin(msv)).append("</b> · 💳 Карта: <b>")
          .append(TextUtil.fmtTiyin(c.getCardBalance())).append("</b> · Фарқ: <b>").append(f > 0 ? "+" : "").append(TextUtil.fmtTiyin(f)).append("</b>\n\n");
        AppUser resp = jarima.responsibleUser(c);
        Set<AppUser> to = new LinkedHashSet<>();
        if (f > 0) {
            sb.append("MoySklad қолдиғи картадан КЎП — картадан харажат қилиниб хабар берилмаган кўринади.\n")
              .append("➡️ ").append(mention(c.getCardResponsible())).append(" харажатни дарҳол хабар қилинг (расход киритилсин) ва карта қолдиғини қайта юборинг.\n");
            if (min > 0) sb.append("⏳ <b>").append(min).append(" дақиқада</b> тузатилмаса — ⚖️ жарима.");
            else sb.append("⚖️ Фарқ жаримаси ўчирилган (jarima.farq_min = 0).");
            if (resp != null) to.add(resp);
        } else {
            sb.append("Карта қолдиғи MoySklad'дан КЎП — MoySklad'га отгрузка ёки тўлов киритилмаган кўринади.\n")
              .append("➡️ Отдел текширсин: сотув/тўлов MoySklad'га киритилсин. Карта масъулининг айби эмас — жарима ёзилмайди.");
            to.addAll(notifier.heads(c.getKassaId()));
            to.addAll(notifier.superadmins());
        }
        String text = sb.toString();
        if (sw.on(NotifySwitches.JR_FARQ))
            for (long chatId : cfg.groupChatIds()) {
                try { sender.send(chatId, text); }
                catch (Exception e) { log.warn("Karta farqi guruhga ketmadi ({}): {}", chatId, e.getMessage()); }
            }
        notifier.send(NotifySwitches.JR_FARQ, to, text, null);
    }

    /** Jarima yozilganda guruhga qisqa qator (xodimga/adminga JarimaService o'zi yuboradi). */
    private void notifyJarima(ClickAccount c, Jarima j, int min) {
        if (!sw.on(NotifySwitches.JR_FARQ)) return;
        String line = j.getHolat() == Jarima.Holat.OGOH
                ? "⚖️ <b>Огоҳлантириш</b> (" + j.getTartib() + "-ҳолат) — кейингисидан жарима " + JarimaConfig.foizText(j.getFoiz()) + "%"
                : "⚖️ <b>Жарима: " + TextUtil.fmt(j.getSumma()) + " сўм</b> (" + JarimaConfig.foizText(j.getFoiz()) + "% × " + TextUtil.fmt(j.getAsos()) + ", " + j.getTartib() + "-ҳолат)";
        String text = "⚖️ <b>КАРТА ФАРҚИ ТУЗАТИЛМАДИ</b> — 💳 " + esc(c.getName()) + " · " + mention(c.getCardResponsible()) + "\n"
                + "Фарқ <b>+" + TextUtil.fmtTiyin(c.getFarqTiyin()) + "</b> сўм " + min + " дақиқадан бери очиқ.\n" + line;
        for (long chatId : cfg.groupChatIds()) {
            try { sender.send(chatId, text); }
            catch (Exception e) { log.warn("Karta farqi jarimasi guruhga ketmadi ({}): {}", chatId, e.getMessage()); }
        }
    }

    /** Mas'ul matni → mention: @username o'zi ishlaydi, {id=..;Ism} — havola (Jobs.mention bilan bir xil). */
    private static String mention(String r) {
        if (r == null || r.isBlank()) return "масъул";
        return esc(r.trim()).replaceAll("\\{id=(\\d+);([^}]+)\\}", "<a href=\"tg://user?id=$1\">$2</a>");
    }
}
