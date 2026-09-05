package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.*;
import uz.kassa.domain.*;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.service.LedgerService;
import uz.kassa.service.NotificationService;
import uz.kassa.service.moysklad.MoySkladClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.*;
import static uz.kassa.bot.handlers.AdminSupport.*;

/**
 * 🔄 Номлар (MoySklad): otdel/hisob nomlarini MoySklad bilan solishtirish, qo'lda o'zgartirish va qulflash.
 * (MoySkladAdminHandler dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
public class MoySkladNamesHandler {

    private final Sender sender;
    private final NameService names;
    private final KassaRepo kassaRepo;
    private final MoySkladClient msClient;
    private final uz.kassa.repo.ClickAccountRepo clickRepo;
    private final uz.kassa.service.AuditService audit;
    private final AdminSupport sup;



    /* ---------- 🔄 NOMLARNI MOYSKLAD'DAN YANGILASH ---------- */

    /** MoySklad'dagi joriy nomlar bilan farqlar: [tur, eski, yangi].
     *  Qo'lda qo'yilgan (name_locked) nomlarga TEGILMAYDI. */
    List<String[]> msNameDiffs(Map<String, String> groups, Map<String, String> accounts) {
        List<String[]> out = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isNameLocked()) continue;
            String g = k.getMoyskladGroupId();
            if (g == null || g.isBlank()) continue;
            String nn = groups.get(g);
            if (nn != null && !nn.isBlank() && !nn.trim().equals(k.getName()))
                out.add(new String[]{"🏪", k.getName(), nn.trim()});
        }
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
            if (c.isNameLocked()) continue;
            String a = c.getMoyskladAccountId();
            if (a == null || a.isBlank()) continue;
            String nn = accounts.get(a);
            if (nn != null && !nn.isBlank() && !nn.trim().equals(c.getName()))
                out.add(new String[]{"📲", c.getName(), nn.trim()});
        }
        return out;
    }



    /** 🔄 Номлар bo'limi bosh menyusi. */
    void msNamesMenu(long chatId, int msgId) {
        String text = "🔄 <b>Номлар (MoySklad)</b>\n\n"
                + "• <b>MoySklad'dan yangilash</b> — kassa nomlari otdel nomidan, klik "
                + "hisoblari MoySklad hisob nomidan tortiladi (avval farqlar ko'rsatiladi).\n"
                + "• <b>Qo'lda o'zgartirish</b> — istalgan kassa/klik hisobiga o'z nomingizni "
                + "qo'yasiz; bog'lanishlar saqlanadi, MoySklad yangilashi bu nomga TEGMAYDI (🔒).";
        List<List<InlineKeyboardButton>> rows = List.of(
                irow(btn("🔄 MoySklad'dan yangilash", "a:msrp")),
                irow(btn("✏️ Qo'lda nom o'zgartirish", "a:msn")),
                irow(sup.bk("a:p:set")));
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sender.send(chatId, text, inline(rows));
    }



    /** Qo'lda nom o'zgartirish — obyekt tanlash ro'yxati. */
    void msNameList(long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn("🏪 " + k.getName() + (k.isNameLocked() ? " 🔒" : ""),
                    "a:msni:k" + k.getId())));
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn("📲 " + c.getName() + (c.isNameLocked() ? " 🔒" : ""),
                    "a:msni:c" + c.getId())));
        rows.add(irow(sup.bk("a:msr")));
        sup.show(chatId, msgId, "✏️ <b>Qo'lda nom o'zgartirish</b>\n\n"
                + "🔒 — nomi qo'lda qo'yilgan (MoySklad yangilashi tegmaydi)\n"
                + "Nomini o'zgartirmoqchi bo'lgan kassa yoki klik hisobini tanlang:", rows);
    }



    /** Tanlangan obyekt kartasi: hozirgi nom, bog'lanish, amallar. */
    void msNameItem(String arg, long chatId, int msgId) {
        boolean isKassa = arg.startsWith("k");
        long id = Long.parseLong(arg.substring(1));
        String name, extra;
        boolean locked;
        if (isKassa) {
            Kassa k = kassaRepo.findById(id).orElse(null);
            if (k == null) { msNameList(chatId, msgId); return; }
            name = k.getName(); locked = k.isNameLocked();
            String g = k.getMoyskladGroupId();
            extra = "Otdel: " + (g == null || g.isBlank() ? "—"
                    : esc(msClient.fetchGroups().getOrDefault(g, g)));
        } else {
            ClickAccount c = clickRepo.findById(id).orElse(null);
            if (c == null) { msNameList(chatId, msgId); return; }
            name = c.getName(); locked = c.isNameLocked();
            extra = "Otdel: " + (c.getKassaId() == null ? "—"
                    : esc(names.owner(OwnerType.KASSA, c.getKassaId())));
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("✏️ Yangi nom kiritish", "a:msne:" + arg)));
        if (locked) rows.add(irow(btn("🔓 MoySklad nomiga qaytarish", "a:msnu:" + arg)));
        rows.add(irow(sup.bk("a:msn")));
        sup.show(chatId, msgId, (isKassa ? "🏪 " : "📲 ") + "<b>" + esc(name) + "</b>"
                + (locked ? " 🔒" : "") + "\n" + extra + "\n\n"
                + (locked ? "Nomi qo'lda qo'yilgan — MoySklad yangilashi unga tegmaydi."
                          : "Nomi MoySklad yangilashida almashishi mumkin. Qo'lda nom "
                            + "qo'ysangiz 🔒 bo'lib himoyalanadi.")
                + "\nBog'lanishlar (otdel/hisob) nom o'zgarganda SAQLANADI.", rows);
    }



    /** Yangi nom kiritishni boshlash. */
    void msNameEditStart(Session s, String arg, long chatId, int msgId) {
        s.reset();
        s.state = Session.State.ADM_NM_NAME;
        s.data.put("nmTarget", arg);
        sender.edit(chatId, msgId, "✏️ <b>Yangi nomni yozib yuboring</b> (2–40 belgi)\n\n"
                + "Masalan: <code>Карта Тимур</code>\n"
                + "Bekor qilish uchun «-» yuboring.");
    }



    /** Kiritilgan yangi nomni saqlash (name_locked=true bilan). */
    void nmNameSave(AppUser u, Session s, String text, long chatId) {
        String arg = s.getStr("nmTarget");
        if (text.trim().equals("-") || arg == null) {
            s.reset();
            sender.send(chatId, "❌ Bekor qilindi");
            return;
        }
        String nn = text.trim();
        if (nn.length() < 2 || nn.length() > 40) {
            sender.send(chatId, "⚠️ Nom 2–40 belgi bo'lsin. Qaytadan yozing yoki «-» yuboring.");
            return;
        }
        boolean isKassa = arg.startsWith("k");
        long id = Long.parseLong(arg.substring(1));
        String old;
        if (isKassa) {
            Kassa k = kassaRepo.findById(id).orElse(null);
            if (k == null) { s.reset(); return; }
            old = k.getName();
            k.setName(nn); k.setNameLocked(true);
            kassaRepo.save(k);
        } else {
            ClickAccount c = clickRepo.findById(id).orElse(null);
            if (c == null) { s.reset(); return; }
            old = c.getName();
            c.setName(nn); c.setNameLocked(true);
            clickRepo.save(c);
        }
        s.reset();
        audit.log(u.getId(), "NOM_QOLDA", isKassa ? "kassa" : "click", id,
                u.getFullName() + " nomni o'zgartirdi: «" + old + "» → «" + nn + "»");
        sender.send(chatId, "✅ Nom o'zgartirildi: <b>" + esc(old) + "</b> → <b>" + esc(nn)
                + "</b> 🔒\n\nBog'lanishlar saqlandi. MoySklad nom-yangilashi bu nomga "
                + "tegmaydi. Qaytarish: 🔄 Номлар → ✏️ Qo'lda → 🔓.", null);
    }



    /** Qulfni ochish: MoySklad nomi qaytariladi (topilsa), himoya o'chadi. */
    void msNameUnlock(AppUser u, String arg, long chatId, int msgId) {
        boolean isKassa = arg.startsWith("k");
        long id = Long.parseLong(arg.substring(1));
        String msg;
        if (isKassa) {
            Kassa k = kassaRepo.findById(id).orElse(null);
            if (k == null) { msNameList(chatId, msgId); return; }
            k.setNameLocked(false);
            String g = k.getMoyskladGroupId();
            String nn = g == null ? null : msClient.fetchGroups().get(g);
            String old = k.getName();
            if (nn != null && !nn.isBlank()) k.setName(nn.trim());
            kassaRepo.save(k);
            msg = nn == null || nn.isBlank()
                    ? "🔓 Himoya olindi (MoySklad'da mos nom topilmadi, nom o'zgarmadi)."
                    : "🔓 MoySklad nomi qaytarildi: <b>" + esc(old) + "</b> → <b>" + esc(nn.trim()) + "</b>";
        } else {
            ClickAccount c = clickRepo.findById(id).orElse(null);
            if (c == null) { msNameList(chatId, msgId); return; }
            c.setNameLocked(false);
            String a = c.getMoyskladAccountId();
            String nn = a == null ? null : msClient.fetchAccounts().get(a);
            String old = c.getName();
            if (nn != null && !nn.isBlank()) c.setName(nn.trim());
            clickRepo.save(c);
            msg = nn == null || nn.isBlank()
                    ? "🔓 Himoya olindi (MoySklad'da mos nom topilmadi, nom o'zgarmadi)."
                    : "🔓 MoySklad nomi qaytarildi: <b>" + esc(old) + "</b> → <b>" + esc(nn.trim()) + "</b>";
        }
        audit.log(u.getId(), "NOM_QULF_OCHILDI", isKassa ? "kassa" : "click", id,
                u.getFullName() + " nom himoyasini oldi");
        sup.show(chatId, msgId, msg, List.of(irow(sup.bk("a:msn"))));
    }



    void msNamesPreview(long chatId, int msgId) {
        Map<String, String> groups = msClient.fetchGroups();
        Map<String, String> accounts = msClient.fetchAccounts();
        String text;
        List<List<InlineKeyboardButton>> rows;
        if (groups.isEmpty() && accounts.isEmpty()) {
            text = "⚠️ MoySklad'dan ma'lumot olinmadi — API kaliti va ulanishni tekshiring "
                    + "(⚙️ Настройка → 🔑 MoySklad API).";
            rows = List.of(irow(sup.bk("a:msr")));
        } else {
            List<String[]> diffs = msNameDiffs(groups, accounts);
            if (diffs.isEmpty()) {
                text = "✅ Hamma nomlar MoySklad bilan allaqachon mos "
                        + "(🔒 qo'lda qo'yilganlarga tegilmaydi):\n"
                        + "🏪 kassalar — otdel nomlari bilan, 📲 klik hisoblari — "
                        + "MoySklad hisob nomlari bilan.";
                rows = List.of(irow(sup.bk("a:msr")));
            } else {
                StringBuilder sb = new StringBuilder("🔄 <b>Номларни MoySklad'дан янгилаш</b>\n\n"
                        + "Quyidagi nomlar MoySklad'dagidan farq qilyapti:\n\n");
                for (String[] d : diffs)
                    sb.append(d[0]).append(" ").append(esc(d[1]))
                      .append(" → <b>").append(esc(d[2])).append("</b>\n");
                sb.append("\nQo'llansinmi? (🏪 kassa nomi — MoySklad otdel nomidan, "
                        + "📲 klik hisobi nomi — MoySklad hisob nomidan olinadi)");
                text = sb.toString();
                rows = List.of(irow(btn("✅ Ha, yangilansin", "a:msry")),
                        irow(btn("❌ Yo'q", "a:msr")));
            }
        }
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sender.send(chatId, text, inline(rows));
    }



    void msNamesApply(AppUser u, long chatId, int msgId) {
        Map<String, String> groups = msClient.fetchGroups();
        Map<String, String> accounts = msClient.fetchAccounts();
        StringBuilder rep = new StringBuilder();
        int n = 0;
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isNameLocked()) continue;
            String g = k.getMoyskladGroupId();
            if (g == null || g.isBlank()) continue;
            String nn = groups.get(g);
            if (nn == null || nn.isBlank() || nn.trim().equals(k.getName())) continue;
            rep.append("🏪 ").append(esc(k.getName())).append(" → <b>").append(esc(nn.trim())).append("</b>\n");
            k.setName(nn.trim());
            kassaRepo.save(k);
            n++;
        }
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
            if (c.isNameLocked()) continue;
            String a = c.getMoyskladAccountId();
            if (a == null || a.isBlank()) continue;
            String nn = accounts.get(a);
            if (nn == null || nn.isBlank() || nn.trim().equals(c.getName())) continue;
            rep.append("📲 ").append(esc(c.getName())).append(" → <b>").append(esc(nn.trim())).append("</b>\n");
            c.setName(nn.trim());
            clickRepo.save(c);
            n++;
        }
        if (n > 0)
            audit.log(u.getId(), "NOMLAR_YANGILANDI", "settings", null,
                    u.getFullName() + " nomlarni MoySklad'dan yangiladi (" + n + " ta)");
        sup.show(chatId, msgId, n == 0
                ? "✅ O'zgarish yo'q — nomlar allaqachon mos."
                : "✅ <b>" + n + " ta nom yangilandi:</b>\n\n" + rep,
                List.of(irow(sup.bk("a:msr"))));
    }

}
