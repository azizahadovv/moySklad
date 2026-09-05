package uz.kassa.gsheets;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.bot.NameService;
import uz.kassa.domain.*;
import uz.kassa.repo.*;
import uz.kassa.service.LedgerService;
import uz.kassa.service.moysklad.MoySkladClient;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BOT → SHEETS: Operatsiyalar, Balanslar, Kunlar, Kassalar, Foydalanuvchilar, Shablon, Sozlamalar varaqlarini yozish.
 * (SheetsSyncService dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SheetsPushService {

    private final GoogleSheetsClient gs;
    private final KassaRepo kassaRepo;
    private final AppUserRepo userRepo;
    private final OperationRepo opRepo;
    private final DayRepo dayRepo;
    private final LedgerService ledger;
    private final NameService names;
    private final MoySkladClient msClient;
    private final ClickAccountRepo clickRepo;
    private final NotifyRepo notifyRepo;
    private final SheetsState st;
    private final SheetsPullService pull;


    void pushOperatsiyalar() throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("ID", "Sana", "Turi", "Pul turi", "Summa",
                "Kimdan", "Kimga", "Status", "Izoh", "MoySklad"));
        List<Operation> ops = opRepo.byPeriod(ledger.today().minusDays(60), ledger.today());
        int n = 0;
        for (Operation o : ops) {
            if (n++ >= 3000) break;
            rows.add(List.of(o.getId(), o.getOpDate().toString(), o.getType().name(),
                    o.getMoneyType().name(), o.getAmount(),
                    st.owner(o.getFromOwnerType(), o.getFromOwnerId()),
                    st.owner(o.getToOwnerType(), o.getToOwnerId()),
                    o.getStatus().name(),
                    o.getComment() == null ? "" : o.getComment(),
                    o.getMoyskladId() == null ? "" : o.getMoyskladId()));
        }
        gs.overwrite("Operatsiyalar", rows);
    }


    void pushBalanslar() throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Kassa", "Naqd", "Band naqd", "Click", "Band click",
                "Terminal (bugun)", "JAMI"));
        long tn = 0, tk = 0, tt = 0;
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;
            var n = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.NAQD);
            var kl = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.KLIK);
            long term = dayRepo.findByKassaIdAndDate(k.getId(), ledger.today())
                    .map(DayRecord::getPrixodTerminal).orElse(0L);
            tn += n.getAmount(); tk += kl.getAmount(); tt += term;
            rows.add(List.of(k.getName(), n.getAmount(), n.getReserved(),
                    kl.getAmount(), kl.getReserved(), term,
                    n.getAmount() + kl.getAmount() + term));
        }
        var bn = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD);
        var bk = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.KLIK);
        rows.add(List.of("Отдел Основной", bn.getAmount(), bn.getReserved(),
                bk.getAmount(), bk.getReserved(), 0, bn.getAmount() + bk.getAmount()));
        long ck = 0;
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
            var cb = ledger.view(OwnerType.CLICK, c.getId(), MoneyType.KLIK);
            ck += cb.getAmount();
            rows.add(List.of("📲 " + c.getName(), "", "",
                    cb.getAmount(), cb.getReserved(), 0, cb.getAmount()));
        }
        rows.add(List.of("JAMI", tn + bn.getAmount(), "", tk + bk.getAmount() + ck, "", tt,
                tn + bn.getAmount() + tk + bk.getAmount() + ck + tt));
        gs.overwrite("Balanslar", rows);
    }


    void pushKunlar() throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Sana", "Kassa", "Kirim naqd", "Kirim click", "Terminal",
                "Rasxod naqd", "Rasxod click", "Qoplangan naqd", "Qoplangan click", "Status"));
        LocalDate from = ledger.today().minusDays(31);
        for (DayRecord d : dayRepo.findAll()) {
            if (d.getDate().isBefore(from)) continue;
            rows.add(List.of(d.getDate().toString(),
                    names.owner(OwnerType.KASSA, d.getKassaId()),
                    d.getPrixodNaqd(), d.getPrixodKlik(), d.getPrixodTerminal(),
                    d.getRasxodNaqd(), d.getRasxodKlik(),
                    d.getCoveredNaqd(), d.getCoveredKlik(), d.getStatus().name()));
        }
        gs.overwrite("Kunlar", rows);
    }


    void pushKassalar() throws Exception {
        if (!pull.kassaPullOk) return;   // o'qish muvaffaqiyatsiz — foydalanuvchi tahririni yo'qotmaymiz
        Map<String, String> groups;
        try { groups = msClient.fetchGroups(); } catch (Exception e) { groups = Map.of(); }
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("ID", "Nomi", "Otdel ID", "Otdel nomi", "Faol", "Holat"));
        java.util.Map<Long, String> snap = new java.util.HashMap<>();
        for (Kassa k : kassaRepo.findAll()) {
            String g = k.getMoyskladGroupId() == null ? "" : k.getMoyskladGroupId();
            String faolS = k.isActive() ? "TRUE" : "FALSE";
            snap.put(k.getId(), st.norm(k.getName()) + "|" + g + "|" + faolS);
            rows.add(List.of(k.getId(), k.getName(), g,
                    groups.getOrDefault(g, ""), faolS, ""));
        }
        rows.addAll(pull.pendingKassas);   // chala satrlar sabab bilan saqlanadi
        gs.overwrite("Kassalar", rows);
        st.kassaSnap.clear();
        st.kassaSnap.putAll(snap);
        st.saveSnap("sheets.snap.kassa", st.kassaSnap);
    }


    void pushUsers() throws Exception {
        if (!pull.usersPullOk) return;   // o'qish muvaffaqiyatsiz — foydalanuvchi tahririni yo'qotmaymiz
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("ID", "TelegramID", "Telefon", "Ism", "Rol",
                "KassaID", "KassaNomi", "Faol", "Holat"));
        java.util.Map<Long, String> snap = new java.util.HashMap<>();
        for (AppUser x : userRepo.findAll()) {
            String tgS = x.getTelegramId() == null ? "" : String.valueOf(x.getTelegramId());
            String telS = st.digits(x.getPhone());
            String kasS = x.getKassaId() == null ? "" : String.valueOf(x.getKassaId());
            String faolS = x.isActive() ? "TRUE" : "FALSE";
            // Varaqqa yozilayotgan holat snapshot'ga: keyingi pull'da faqat shundan
            // FARQ QILGAN kataklar operator tahriri deb qabul qilinadi.
            snap.put(x.getId(), tgS + "|" + telS + "|" + st.norm(x.getFullName()) + "|"
                    + x.getRole().name() + "|" + kasS + "|" + faolS);
            rows.add(List.of(x.getId(), tgS,
                    x.getPhone() == null ? "" : x.getPhone(),
                    x.getFullName(), x.getRole().name(),
                    x.getKassaId() == null ? "" : x.getKassaId(),
                    x.getKassaId() == null ? "" : names.owner(OwnerType.KASSA, x.getKassaId()),
                    faolS,
                    x.getTelegramId() == null
                            ? "⏳ Telegram ulanmagan — Telefon ustunini to'ldiring va odam botga kirib «📱 Telefon raqamni yuborish»ni bossin"
                            : ""));
        }
        rows.addAll(pull.pendingUsers);   // chala satrlar sabab bilan saqlanadi
        gs.overwrite("Foydalanuvchilar", rows);
        st.userSnap.clear();
        st.userSnap.putAll(snap);
        st.saveSnap("sheets.snap.users", st.userSnap);
    }


    void pushShablon() throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("ID", "Nomi", "Kimga", "Jadval", "Hafta kunlari", "Avto-o'chirish (min)", "Faol",
                "Shablon matni", "Oxirgi yuborilgan", "Xato", "Tugma matni", "Tugma rollar"));
        java.util.Map<Long, String> snap = new java.util.HashMap<>();
        for (Notify n : notifyRepo.findAllByOrderByIdAsc()) {
            String faol = n.isActive() ? "TRUE" : "FALSE";
            String avto = String.valueOf(n.getAutoDeleteMin());
            rows.add(List.of(n.getId(), n.getName(), n.getRecipients(), n.getSchedule(), n.getWeekdays(),
                    avto, faol, n.getTemplate(),
                    n.getLastSent() == null ? "" : n.getLastSent().replace('T', ' '),
                    n.getLastError() == null ? "" : n.getLastError(),
                    n.getButtonLabel(), n.getButtonRoles()));
            snap.put(n.getId(), SheetsState.notifyHash(n.getName(), n.getRecipients(), n.getSchedule(), n.getWeekdays(),
                    avto, faol, n.getTemplate(), n.getButtonLabel(), n.getButtonRoles()));
        }
        gs.overwrite("Shablon", rows);
        st.notifySnap.clear();
        st.notifySnap.putAll(snap);
        st.saveSnap("sheets.snap.notify", st.notifySnap);
    }


    void pushSozlamalar() throws Exception {
        gs.overwrite("Sozlamalar", List.of(
                List.of("Ko'rsatma", "Qiymat"),
                List.of("Oxirgi sinxron", LocalDateTime.now().withNano(0).toString()),
                List.of("Tahrir qilinadigan varaqlar", "Kassalar, Foydalanuvchilar, Shablon"),
                List.of("Shablon", "Bildirishnomalar: Nomi / Kimga (group:-100123, rol:KASSIR, user:5, kassa:2, karta_masul, click_chats, mehmonlar) / Jadval (every:2;from:9;to:21;off:0 YOKI 09:00,13:00) / Hafta kunlari (1-7, bo'sh=har kuni) / Avto-o'chirish (min) / Faol / Shablon matni; yangi satr (ID bo'sh) = yangi bildirishnoma. O'rinbosarlar ro'yxati: bot -> Настройка -> Билдиришномалар -> 📖"),
                List.of("Kassalar", "Nomi/Otdel (ID yoki nomi)/Faol; yangi satr (ID bo'sh) = yangi kassa"),
                List.of("Foydalanuvchilar", "Ism + Rol (kassir/buxgalter/admin) + TelegramID YOKI Telefon + Kassa (ID yoki nomi); yangi satr (ID bo'sh) = yangi foydalanuvchi"),
                List.of("Holat ustuni", "Satr qabul qilinmasa sabab shu ustunda chiqadi — satr O'CHMAYDI, to'ldirsangiz keyingi siklda qabul qilinadi"),
                List.of("Ustuvorlik", "BOT BAZASI — asosiy manba. Jadvaldagi katak faqat SIZ o'zgartirganingizda botga qo'llanadi; bot tomonida qilingan o'zgarishlarni jadval eski nusxasi qaytarib yubormaydi"),
                List.of("Qolgan varaqlar", "Bot tomonidan avtomatik yoziladi — tahrir 5 daqiqada ustidan yozib yuboriladi"),
                List.of("Davr", "Operatsiyalar: oxirgi 60 kun · Kunlar: oxirgi 31 kun")));
    }

}
