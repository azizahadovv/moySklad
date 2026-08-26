package uz.kassa.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.kassa.domain.*;
import uz.kassa.repo.DayRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.repo.OperationRepo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static uz.kassa.bot.TextUtil.esc;
import static uz.kassa.bot.TextUtil.fmt;

/**
 * 💰 БАЛАНС bo'limi — 3 ko'rinish: НАҚД / КЛИК / ЖАМИ.
 *  - НАҚД: faqat hali TOPSHIRILMAGAN kunlar (OCHIQ/YOPILGAN qoldig'i bor kunlar)
 *    kesimida naqd + Основной отдел naqd balansi — hisobot topshirilgan yoki
 *    qabul qilingan kun ko'rinmaydi;
 *  - КЛИК: har bir kassaning O'Z hisobida yig'iladigan klik (buxgalteriyaga
 *    o'tkazilmaydi): topshirilmagan kunlar kesimi + jami yig'ilgan klik hisobi;
 *  - ЖАМИ: har kassa bo'yicha bir qatorlik svod + УМУМИЙ. Основной отдел
 *    balansiga MoySklad'dan otdelga bog'lanmagan (osnovnoy) prixodlar
 *    avtomatik tushadi.
 */
@Service
@RequiredArgsConstructor
public class BalansService {

    /** Ko'rinish turlari. */
    public static final char NAQD = 'n', KLIK = 'k', JAMI = 'j';

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final LedgerService ledger;
    private final KassaRepo kassaRepo;
    private final DayRepo dayRepo;
    private final OperationRepo opRepo;
    private final uz.kassa.config.AppProps props;

    /* ==================== Buxgalter/SuperAdmin: barcha kassalar ==================== */

    @Transactional(readOnly = true)
    public String buildAll(char section) {
        return switch (section) {
            case NAQD -> allNaqd();
            case KLIK -> allKlik();
            default -> allJami();
        };
    }

    private String allNaqd() {
        LocalDate today = ledger.today();
        Balance bn = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD);

        StringBuilder body = new StringBuilder();
        body.append("\n🏦 <b>Отдел основной</b>\nФакт: <b>").append(fmt(bn.getAmount())).append("</b> so'm\n");

        long sum = 0;
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;
            List<DayRecord> days = openDays(k.getId()).stream()
                    .filter(d -> d.remainNaqd() != 0).toList();
            body.append("\n🏪 <b>").append(esc(k.getName())).append("</b>\n");
            if (days.isEmpty()) {
                body.append("✅ Topshirilmagan naqd yo'q\n");
                continue;
            }
            long dn = 0;
            for (DayRecord d : days) {
                dn += d.remainNaqd();
                body.append("• ").append(d.getDate().format(DF))
                  .append(d.getDate().equals(today) ? " (bugun)" : "")
                  .append(" — <b>").append(fmt(d.remainNaqd())).append("</b> so'm\n");
            }
            sum += dn;
            body.append("⏳ Jami: <b>").append(fmt(dn)).append("</b> so'm\n");
        }

        StringBuilder sb = header("💵 <b>БАЛАНС — НАҚД</b>");
        sb.append("\n➕ <b>УМУМИЙ НАҚД</b>  <b>").append(fmt(bn.getAmount() + sum)).append("</b> so'm\n");
        sb.append(body);
        return sb.toString();
    }

    private String allKlik() {
        LocalDate today = ledger.today();
        Balance bk = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.KLIK);
        StringBuilder sb = header("📲 <b>БАЛАНС — КЛИК</b>");
        sb.append("<i>Klik har bir kassaning o'z hisobida yig'iladi — "
                + "buxgalteriyaga o'tkazilmaydi.</i>\n");
        if (bk.getAmount() != 0)
            sb.append("\n🏦 <b>Отдел основной</b>: <b>").append(fmt(bk.getAmount()))
              .append("</b> so'm\n");

        long sumBal = 0;
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;
            long bal = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.KLIK).getAmount();
            sumBal += bal;
            List<DayRecord> days = openDays(k.getId()).stream()
                    .filter(d -> d.remainKlik() != 0).toList();
            sb.append("\n🏪 <b>").append(esc(k.getName())).append("</b>\n");
            if (!days.isEmpty()) {
                long dk = 0;
                for (DayRecord d : days) {
                    dk += d.remainKlik();
                    sb.append("• ").append(d.getDate().format(DF))
                      .append(d.getDate().equals(today) ? " (bugun)" : "")
                      .append(" — <b>").append(fmt(d.remainKlik())).append("</b> so'm\n");
                }
                sb.append("⏳ Hisoboti topshirilmagan: <b>").append(fmt(dk)).append("</b> so'm\n");
            }
            sb.append("💼 Klik hisobi (jami yig'ilgan): <b>").append(fmt(bal)).append("</b> so'm\n");
        }
        sb.append("\n➕ <b>УМУМИЙ КЛИК</b> (kassalar hisoblari")
          .append(bk.getAmount() != 0 ? " + осн." : "").append("): <b>")
          .append(fmt(sumBal + bk.getAmount())).append("</b> so'm");
        return sb.toString();
    }

    private String allJami() {
        LocalDate today = ledger.today();
        Balance bn = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD);
        Balance bk = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.KLIK);
        StringBuilder sb = header("💰 <b>БАЛАНС — ЖАМИ</b>");
        sb.append("\n🏦 <b>Отдел основной</b>: 💵 <b>").append(fmt(bn.getAmount()))
          .append("</b> so'm\n");
        if (bk.getAmount() != 0)
            sb.append("<i>⚠️ Отдел основнойда ").append(fmt(bk.getAmount()))
              .append(" so'm klik ham bor — otdelga bog'lanmagan hujjat, tekshiring.</i>\n");
        long[] osn = osnovnoyToday(today);
        if (osn[0] > 0 || osn[1] > 0)
            sb.append("🟢 Bugungi kirim: <b>").append(fmt(osn[0]))
              .append("</b> · 🔴 Bugungi rasxod: <b>").append(fmt(osn[1]))
              .append("</b> so'm\n");

        long sumNaqd = 0, sumKlikBal = 0;
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;
            long dn = openDays(k.getId()).stream().mapToLong(DayRecord::remainNaqd).sum();
            long bal = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.KLIK).getAmount();
            sumNaqd += dn;
            sumKlikBal += bal;
            sb.append("🏪 <b>").append(esc(k.getName())).append("</b>: 💵 <b>").append(fmt(dn))
              .append("</b> · 📲 <b>").append(fmt(bal))
              .append("</b> = <b>").append(fmt(dn + bal)).append("</b> so'm\n");
        }

        long totalNaqd = bn.getAmount() + sumNaqd;
        long totalKlik = bk.getAmount() + sumKlikBal;
        sb.append("\n➕ <b>УМУМИЙ</b>\n")
          .append("💵 Нақд: <b>").append(fmt(totalNaqd)).append("</b> so'm\n")
          .append("📲 Клик: <b>").append(fmt(totalKlik)).append("</b> so'm\n")
          .append("💰 <b>Жами: ").append(fmt(totalNaqd + totalKlik)).append("</b> so'm")
          .append("\n\n<i>💵 — topshirilmagan kunlar naqdi · 📲 — kassa klik hisobi</i>");
        return sb.toString();
    }

    /* ==================== Kassir: faqat o'z kassasi ==================== */

    @Transactional(readOnly = true)
    public String buildKassa(Long kassaId, String kassaName, char section) {
        return switch (section) {
            case NAQD -> kassaNaqd(kassaId, kassaName);
            case KLIK -> kassaKlik(kassaId, kassaName);
            default -> kassaJami(kassaId, kassaName);
        };
    }

    private String kassaNaqd(Long kassaId, String name) {
        LocalDate today = ledger.today();
        List<DayRecord> days = openDays(kassaId).stream()
                .filter(d -> d.remainNaqd() != 0).toList();
        StringBuilder sb = header("💵 <b>БАЛАНС — НАҚД</b> — 🏪 " + esc(name));
        if (days.isEmpty()) {
            sb.append("\n✅ Topshirilmagan naqd yo'q");
            return sb.toString();
        }
        long dn = 0;
        sb.append("\n⏳ Topshirilmagan kunlar:\n");
        for (DayRecord d : days) {
            dn += d.remainNaqd();
            sb.append("• ").append(d.getDate().format(DF))
              .append(d.getDate().equals(today) ? " (bugun)" : "")
              .append(" — <b>").append(fmt(d.remainNaqd())).append("</b> so'm\n");
        }
        sb.append("➕ <b>Jami: ").append(fmt(dn)).append("</b> so'm");
        return sb.toString();
    }

    private String kassaKlik(Long kassaId, String name) {
        LocalDate today = ledger.today();
        long bal = ledger.view(OwnerType.KASSA, kassaId, MoneyType.KLIK).getAmount();
        List<DayRecord> days = openDays(kassaId).stream()
                .filter(d -> d.remainKlik() != 0).toList();
        StringBuilder sb = header("📲 <b>БАЛАНС — КЛИК</b> — 🏪 " + esc(name));
        sb.append("<i>Klik pulingiz o'z hisobingizda yig'iladi — "
                + "buxgalteriyaga o'tkazilmaydi.</i>\n");
        if (!days.isEmpty()) {
            long dk = 0;
            sb.append("\n⏳ Hisoboti topshirilmagan kunlar:\n");
            for (DayRecord d : days) {
                dk += d.remainKlik();
                sb.append("• ").append(d.getDate().format(DF))
                  .append(d.getDate().equals(today) ? " (bugun)" : "")
                  .append(" — <b>").append(fmt(d.remainKlik())).append("</b> so'm\n");
            }
            sb.append("Jami: <b>").append(fmt(dk)).append("</b> so'm\n");
        }
        sb.append("\n💼 <b>Klik hisobim (jami yig'ilgan): ").append(fmt(bal)).append("</b> so'm");
        return sb.toString();
    }

    private String kassaJami(Long kassaId, String name) {
        long dn = openDays(kassaId).stream().mapToLong(DayRecord::remainNaqd).sum();
        long bal = ledger.view(OwnerType.KASSA, kassaId, MoneyType.KLIK).getAmount();
        StringBuilder sb = header("💰 <b>БАЛАНС — ЖАМИ</b> — 🏪 " + esc(name));
        sb.append("\n💵 Нақд (topshirilmagan kunlar): <b>").append(fmt(dn)).append("</b> so'm\n")
          .append("📲 Клик hisobim (jami yig'ilgan): <b>").append(fmt(bal)).append("</b> so'm\n")
          .append("💰 <b>Жами: ").append(fmt(dn + bal)).append("</b> so'm");
        return sb.toString();
    }

    /* ==================== yordamchilar ==================== */

    private StringBuilder header(String title) {
        return new StringBuilder(title + "\n📅 "
                + LocalDateTime.now(props.zoneId()).format(DTF) + "\n");
    }

    /** Topshirilmagan (OCHIQ/YOPILGAN) va qoldig'i bor kunlar, eng eskisidan. */
    private List<DayRecord> openDays(Long kassaId) {
        return dayRepo.findByKassaIdAndStatusInOrderByDateAsc(
                        kassaId, List.of(DayStatus.OCHIQ, DayStatus.YOPILGAN)).stream()
                .filter(d -> d.remainNaqd() != 0 || d.remainKlik() != 0)
                .toList();
    }

    /**
     * Osnovnoy otdel (Buxgalteriya)ning BUGUNGI harakati: {kirim, rasxod} —
     * MoySklad va bot orqali kiritilganlar birga (balansga ta'sir qilganlari).
     * KORREKTIROVKA/BOSHLANGICH texnik tuzatishlar hisobga OLINMAYDI —
     * bu qator faqat haqiqiy pul aylanmasini ko'rsatadi (MoySklad bilan mos).
     */
    private long[] osnovnoyToday(LocalDate today) {
        long in = 0, out = 0;
        for (Operation o : opRepo.byPeriod(today, today)) {
            if (o.getStatus() != OpStatus.TASDIQLANGAN || o.getMoneyType() == MoneyType.TERMINAL)
                continue;
            if (o.getType() == OpType.KORREKTIROVKA || o.getType() == OpType.BOSHLANGICH)
                continue;
            if (o.getToOwnerType() == OwnerType.BUXGALTERIYA) in += o.getAmount();
            if (o.getFromOwnerType() == OwnerType.BUXGALTERIYA) out += o.getAmount();
        }
        return new long[]{in, out};
    }
}
