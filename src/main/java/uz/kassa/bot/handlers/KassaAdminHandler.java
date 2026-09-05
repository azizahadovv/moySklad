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
 * 🏪 Kassa boshqaruvi: kassa qo'shish/o'chirish, otdel bog'lash, Click hisoblari (boshlang'ich qoldiq), ♻️ nol boshlash.
 * (AdminHandler dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
public class KassaAdminHandler {

    private final Sender sender;
    private final NameService names;
    private final LedgerService ledger;
    private final KassaRepo kassaRepo;
    private final MoySkladClient msClient;
    private final uz.kassa.repo.DayRepo dayRepo;
    private final NotificationService notify;
    private final uz.kassa.repo.ClickAccountRepo clickRepo;
    private final uz.kassa.service.AuditService audit;
    private final AdminSupport sup;


    void setKassa(long chatId, int msgId) {
        sup.show(chatId, msgId, "🏪 <b>Касса</b>", List.of(
                irow(btn("➕ Касса қўшиш", "a:p:sknew")),
                irow(btn("🗂 Отдел боғлаш", "a:p:sko")),
                irow(btn("🚫 Касса ўчириш", "a:p:skd")),
                irow(sup.bk("a:p:set"))));
    }


    /* ---------- 🗂 KASSA–OTDEL BOG'LANISHI ---------- */

    /** Otdel band bo'lgan boshqa FAOL kassalar (o'zidan tashqari). */
    List<Kassa> otdelHolders(String groupId, long exceptKassaId) {
        List<Kassa> out = new ArrayList<>();
        for (Kassa o : kassaRepo.findByActiveTrueOrderByIdAsc())
            if (o.getId() != exceptKassaId && groupId.equals(o.getMoyskladGroupId())) out.add(o);
        return out;
    }


    void kassaOtdelList(long chatId, int msgId) {
        Map<String, String> groups = msClient.fetchGroups();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            String g = k.getMoyskladGroupId();
            String cur = (g == null || g.isBlank()) ? "—" : groups.getOrDefault(g, "?");
            rows.add(irow(btn("🏪 " + k.getName() + " · " + cur, "a:p:skg:" + k.getId())));
        }
        rows.add(irow(sup.bk("a:p:sk")));
        String text = "🗂 <b>Отдел боғлаш</b>\n\n"
                + "Har bir kassa yonida hozirgi MoySklad otdeli ko'rsatilgan "
                + "(— bo'lsa bog'lanmagan).\nKassani tanlang:";
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sender.send(chatId, text, inline(rows));
    }


    void kassaOtdelMenu(long kassaId, long chatId, int msgId) {
        Kassa k = kassaRepo.findById(kassaId).orElse(null);
        if (k == null) { kassaOtdelList(chatId, msgId); return; }
        Map<String, String> groups = msClient.fetchGroups();
        if (groups.isEmpty()) {
            sup.show(chatId, msgId, "⚠️ MoySklad otdellari olinmadi — API kaliti va ulanishni "
                    + "tekshiring (⚙️ Настройка → 🔑 MoySklad API).",
                    List.of(irow(sup.bk("a:p:sko"))));
            return;
        }
        String cur = k.getMoyskladGroupId() == null ? "" : k.getMoyskladGroupId();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Map.Entry<String, String> g : groups.entrySet()) {
            String mark = g.getKey().equals(cur) ? "✅ "
                    : otdelHolders(g.getKey(), kassaId).isEmpty() ? "🗂 " : "🔒 ";
            rows.add(irow(btn(mark + g.getValue(), "a:p:skgs:" + kassaId + ":" + g.getKey())));
        }
        if (!cur.isBlank())
            rows.add(irow(btn("➖ Otdelni olib tashlash", "a:p:skgx:" + kassaId)));
        rows.add(irow(sup.bk("a:p:sko")));
        sup.show(chatId, msgId, "🗂 <b>" + esc(k.getName()) + "</b>\n\n"
                + "Hozirgi otdel: <b>" + (cur.isBlank() ? "—"
                        : esc(groups.getOrDefault(cur, cur))) + "</b>\n\n"
                + "✅ — hozirgisi · 🔒 — boshqa kassada band (bosilsa ko'chirish so'raladi)\n"
                + "Yangi otdelni tanlang:", rows);
    }


    void kassaOtdelSet(AppUser u, long kassaId, String groupId, boolean move,
                               long chatId, int msgId) {
        Kassa k = kassaRepo.findById(kassaId).orElse(null);
        if (k == null) { kassaOtdelList(chatId, msgId); return; }
        Map<String, String> groups = msClient.fetchGroups();
        String gName = groups.getOrDefault(groupId, groupId);
        List<Kassa> holders = otdelHolders(groupId, kassaId);

        // Har qanday biriktirish oldidan DOIM tasdiq so'raladi (bexosdan bosishdan himoya)
        if (!move) {
            String warn;
            if (holders.isEmpty()) {
                warn = "🗂 <b>" + esc(gName) + "</b> otdeli <b>" + esc(k.getName())
                        + "</b> kassasiga biriktirilsinmi?\n\nMoySklad'ning shu otdeldagi "
                        + "kirim/chiqim hujjatlari endi shu kassaga yoziladi.";
            } else {
                String who = holders.stream().map(Kassa::getName)
                        .reduce((x, y) -> x + ", " + y).orElse("?");
                warn = "⚠️ <b>" + esc(gName) + "</b> otdeli allaqachon <b>" + esc(who)
                        + "</b> kassasiga biriktirilgan.\n\nBitta otdel faqat bitta kassada "
                        + "bo'la oladi. <b>" + esc(k.getName()) + "</b> kassasiga ko'chirilsinmi? "
                        + "(avvalgi kassadan olib tashlanadi)";
            }
            sup.show(chatId, msgId, warn, List.of(
                    irow(btn(holders.isEmpty() ? "✅ Ha, biriktirilsin" : "✅ Ha, ko'chirilsin",
                            "a:p:skgm:" + kassaId + ":" + groupId)),
                    irow(btn("❌ Yo'q", "a:p:skg:" + kassaId))));
            return;
        }
        for (Kassa o : holders) {
            o.setMoyskladGroupId(null);
            kassaRepo.save(o);
            audit.log(u.getId(), "OTDEL_OLIB_TASHLANDI", "kassa", o.getId(),
                    u.getFullName() + " «" + gName + "» otdelini «" + o.getName()
                            + "» kassasidan oldi (ko'chirish)");
        }
        k.setMoyskladGroupId(groupId);
        kassaRepo.save(k);
        audit.log(u.getId(), "OTDEL_BIRIKTIRILDI", "kassa", k.getId(),
                u.getFullName() + " «" + gName + "» otdelini «" + k.getName() + "» kassasiga biriktirdi");
        sup.show(chatId, msgId, "✅ <b>" + esc(gName) + "</b> otdeli <b>" + esc(k.getName())
                + "</b> kassasiga biriktirildi."
                + (holders.isEmpty() ? "" : "\n(Avvalgi kassadan olib tashlandi.)")
                + "\n\nMoySklad hujjatlari endi shu kassaga yoziladi.",
                List.of(irow(sup.bk("a:p:sko"))));
    }


    /** Olib tashlash oldidan tasdiq. */
    void kassaOtdelClearConfirm(long kassaId, long chatId, int msgId) {
        Kassa k = kassaRepo.findById(kassaId).orElse(null);
        if (k == null) { kassaOtdelList(chatId, msgId); return; }
        String cur = k.getMoyskladGroupId();
        String gName = cur == null ? "—" : msClient.fetchGroups().getOrDefault(cur, cur);
        sup.show(chatId, msgId, "⚠️ <b>" + esc(k.getName()) + "</b> kassasidan <b>" + esc(gName)
                + "</b> otdeli olib tashlansinmi?\n\nKeyin bu kassaga MoySklad'dan avtomatik "
                + "hech narsa tushmaydi — otdel hujjatlari Buxgalteriyaga yoziladi.", List.of(
                irow(btn("✅ Ha, olib tashlansin", "a:p:skgy:" + kassaId)),
                irow(btn("❌ Yo'q", "a:p:skg:" + kassaId))));
    }


    void kassaOtdelClear(AppUser u, long kassaId, long chatId, int msgId) {
        Kassa k = kassaRepo.findById(kassaId).orElse(null);
        if (k == null) { kassaOtdelList(chatId, msgId); return; }
        String old = k.getMoyskladGroupId();
        k.setMoyskladGroupId(null);
        kassaRepo.save(k);
        audit.log(u.getId(), "OTDEL_OLIB_TASHLANDI", "kassa", k.getId(),
                u.getFullName() + " «" + k.getName() + "» kassasidan otdelni oldi (edi: " + old + ")");
        sup.show(chatId, msgId, "➖ <b>" + esc(k.getName()) + "</b> kassasidan otdel olib tashlandi.\n\n"
                + "⚠️ Endi bu kassaga MoySklad'dan avtomatik hech narsa tushmaydi — "
                + "otdel hujjatlari Buxgalteriyaga yoziladi.",
                List.of(irow(sup.bk("a:p:sko"))));
    }


    void kassaDeleteList(long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn("🚫 " + k.getName(), "a:p:skx:" + k.getId())));
        rows.add(irow(sup.bk("a:p:sk")));
        sup.show(chatId, msgId, "🚫 <b>Касса ўчириш</b>\n\nQaysi kassani o'chirasiz?", rows);
    }


    void kassaDeleteConfirm(long id, long chatId, int msgId) {
        sup.show(chatId, msgId, "⚠️ <b>" + esc(names.owner(OwnerType.KASSA, id))
                + "</b> kassasi o'chirilsinmi?\n\n"
                + "Kassa faolsizlanadi — tarix saqlanadi, yangi operatsiyalar to'xtaydi.", List.of(
                irow(btn("✅ Ha, o'chirilsin", "a:p:sky:" + id), btn("❌ Yo'q", "a:p:skd"))));
    }


    void kassaDeactivate(long id, long chatId, int msgId) {
        String block = kassaDeactivateBlock(id);
        if (block != null) {
            sup.show(chatId, msgId, block, List.of(irow(sup.bk("a:p:sk"))));
            return;
        }
        kassaRepo.findById(id).ifPresent(k -> { k.setActive(false); kassaRepo.save(k); });
        sup.show(chatId, msgId, "🚫 Kassa faolsizlantirildi", List.of(irow(sup.bk("a:p:sk"))));
    }


    /**
     * Kassani o'chirishdan oldin himoya: balansi yoki topshirilmagan kun qoldig'i
     * bo'lgan kassa o'chirilsa, puli barcha jamilardan «yo'qolib» qolardi.
     * null — o'chirish mumkin; aks holda sabab matni.
     */
    String kassaDeactivateBlock(long id) {
        long n = ledger.view(OwnerType.KASSA, id, MoneyType.NAQD).getAmount();
        long kl = ledger.view(OwnerType.KASSA, id, MoneyType.KLIK).getAmount();
        long rem = dayRepo.findByKassaIdAndStatusInOrderByDateAsc(id,
                        List.of(DayStatus.OCHIQ, DayStatus.YOPILGAN)).stream()
                .mapToLong(d -> d.remainNaqd() + d.remainKlik()).sum();
        if (n == 0 && kl == 0 && rem == 0) return null;
        return "⚠️ <b>Kassada pul bor — o'chirib bo'lmaydi</b> (o'chirilsa bu pul "
                + "hisobotlardan yo'qolib qoladi):\n"
                + "💵 Naqd: <b>" + fmt(n) + "</b> · 📲 Click: <b>" + fmt(kl) + "</b>"
                + (rem == 0 ? "" : "\n⏳ Topshirilmagan kunlar qoldig'i: <b>" + fmt(rem) + "</b>")
                + "\n\nQoldiqni 0 qilish yo'llari (IKKALASI ham avtomatik hisobga olinadi):\n"
                + "• hisobot topshirish/qabul yoki «Пулларни қабул қилиш»;\n"
                + "• MoySklad rasxod hujjati — rasxod ham qoldiqni kamaytiradi\n"
                + "  (masalan 100 000 dan 50 000 topshirilib, 50 000 rasxod qilinsa — qoldiq 0).\n"
                + "Kerak bo'lsa korrektirovka ham ishlaydi. Shundan keyin o'chirish ochiladi.";
    }


    /* ---------- 📲 КЛИКЛАР (alohida Click hisoblari) ---------- */

    void clickMenu(AppUser u, Session s, long chatId, int msgId) {
        StringBuilder sb = new StringBuilder("📲 <b>Кликлар</b>\n\n");
        long total = 0;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
            var b = ledger.view(OwnerType.CLICK, c.getId(), MoneyType.KLIK);
            total += b.getAmount();
            sb.append("• <b>").append(esc(c.getName())).append("</b>: ")
              .append(fmt(b.getAmount())).append(" so'm\n");
            if (u.getRole() == Role.SUPERADMIN)
                rows.add(irow(btn("💼 " + c.getName(), "a:ckq:" + c.getId())));
        }
        sb.append("\n➕ <b>Жами: ").append(fmt(total)).append("</b> so'm");
        if (u.getRole() == Role.SUPERADMIN)
            sb.append("\n\nQoldiq kiritish uchun hisobni tanlang:");
        if (msgId > 0) sup.show(chatId, msgId, sb.toString(), rows);
        else sup.sendContent(s, chatId, sb.toString(), rows.isEmpty() ? null : inline(rows));
    }


    void ckStart(Session s, String arg, long chatId, int msgId) {
        long id = Long.parseLong(arg);
        s.data.put("ckId", id);
        s.state = Session.State.ADM_CK_SUM;
        sender.edit(chatId, msgId, "📲 <b>" + esc(names.owner(OwnerType.CLICK, id))
                + "</b>\n\n<b>Boshlang'ich qoldiqni kiriting</b> (so'm):");
    }


    void ckSum(Session s, String text, long chatId) {
        long sum = parseAmount(text);
        if (sum <= 0) { sender.send(chatId, "⚠️ Musbat summa kiriting:"); return; }
        s.data.put("ckSum", sum);
        s.state = Session.State.ADM_CK_SANA;
        java.time.LocalDate now = java.time.LocalDate.now();
        sender.send(chatId, "📅 <b>Qaysi sanaga kiritilsin?</b>\n\n"
                        + "Tugmani bosing yoki eskiroq sanani o'zingiz yozing (masalan <code>"
                        + now.minusDays(10).format(DF) + "</code>):",
                inline(List.of(
                        irow(btn("📅 Bugun", "a:ckd:0"), btn("Kecha", "a:ckd:1")),
                        irow(btn(now.minusDays(2).format(DF), "a:ckd:2"),
                             btn(now.minusDays(3).format(DF), "a:ckd:3"),
                             btn(now.minusDays(4).format(DF), "a:ckd:4")),
                        irow(btn("🗓 Kalendar", "a:cal:o:ck")),
                        irow(btn("❌ Bekor", "cx")))));
    }


    void ckSanaBtn(AppUser u, Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_CK_SANA) return;
        java.time.LocalDate d = java.time.LocalDate.now().minusDays(Long.parseLong(arg));
        sender.edit(chatId, msgId, "📅 Sana: <b>" + d.format(DF) + "</b>");
        ckCommit(u, s, d, chatId);
    }


    void ckSana(AppUser u, Session s, String text, long chatId) {
        java.time.LocalDate d;
        try { d = java.time.LocalDate.parse(text.trim(), DF); }
        catch (Exception e) {
            try { d = java.time.LocalDate.parse(text.trim()); }
            catch (Exception e2) {
                sender.send(chatId, "⚠️ Sana formati: <code>kun.oy.yil</code> — masalan <code>"
                        + java.time.LocalDate.now().format(DF) + "</code>");
                return;
            }
        }
        if (d.isAfter(java.time.LocalDate.now())) {
            sender.send(chatId, "⚠️ Kelajak sanasi bo'lmaydi. Qaytadan kiriting:");
            return;
        }
        ckCommit(u, s, d, chatId);
    }


    void ckCommit(AppUser u, Session s, java.time.LocalDate date, long chatId) {
        long id = s.getLong("ckId");
        long sum = s.getLong("ckSum");
        String nav = s.getStr("nav");
        Object pm = s.data.get("panelMsg");
        s.reset();
        if (nav != null) s.data.put("nav", nav);
        if (pm != null) s.data.put("panelMsg", pm);

        ledger.postAdjustment(OpType.BOSHLANGICH, OwnerType.CLICK, id, MoneyType.KLIK,
                sum, "Boshlang'ich qoldiq", u.getId(), date);
        var b = ledger.view(OwnerType.CLICK, id, MoneyType.KLIK);
        sender.send(chatId, "✅ <b>" + esc(names.owner(OwnerType.CLICK, id))
                + "</b> — qoldiq kiritildi\n"
                + "📅 Sana: <b>" + date.format(DF) + "</b>\n"
                + "📲 Yangi balans: <b>" + fmt(b.getAmount()) + "</b> so'm");
    }


    /* ==================== 🏪 KASSA QO'SHISH ==================== */

    void akName(Session s, String text, long chatId) {
        // Soddalashtirilgan: nom -> darhol otdel tanlash (savdo nuqtasi bosqichi olib tashlandi)
        s.data.put("kassaName", text);
        akFinish(s, "-", chatId);
    }


    void akFinish(Session s, String text, long chatId) {
        s.data.put("kassaMsId", text.equals("-") ? "" : text.trim());

        // MoySklad otdellari (Владелец-отдел) — kirim/chiqim shu bog'lanish orqali kassaga tushadi
        Map<String, String> groups = msClient.fetchGroups();
        if (groups.isEmpty()) { createKassa(s, null, chatId, null); return; }

        s.data.put("groups", groups);
        s.state = Session.State.ADM_AK_GROUP;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Map.Entry<String, String> g : groups.entrySet())
            rows.add(irow(btn("🗂 " + g.getValue(), "a:gr:" + g.getKey())));
        rows.add(irow(btn("➖ Otdel biriktirmaslik", "a:gr:-")));
        rows.add(irow(btn("❌ Bekor", "cx")));
        sender.send(chatId, "MoySklad <b>otdelini</b> tanlang —\n"
                + "Приходный/Расходный ордерlar shu otdel bo'yicha kassaga yoziladi:", inline(rows));
    }


    void akGroup(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_AK_GROUP) return;
        createKassa(s, arg.equals("-") ? null : arg, chatId, msgId);
    }


    void createKassa(Session s, String groupId, long chatId, Integer msgId) {
        String name = s.getStr("kassaName");
        String msIdRaw = s.getStr("kassaMsId");
        String storeId = (msIdRaw == null || msIdRaw.isBlank()) ? null : msIdRaw;
        @SuppressWarnings("unchecked")
        Map<String, String> groups = s.data.get("groups") instanceof Map<?, ?> m
                ? (Map<String, String>) m : new LinkedHashMap<>();
        s.reset();

        Kassa k = kassaRepo.save(Kassa.builder()
                .name(name).moyskladStoreId(storeId).moyskladGroupId(groupId).active(true).build());

        String text = "✅ Kassa qo'shildi: <b>" + esc(k.getName()) + "</b> (#" + k.getId() + ")"
                + (storeId == null ? "" : "\nSavdo nuqtasi ID: <code>" + esc(storeId) + "</code>")
                + (groupId == null
                    ? "\n\n⚠️ Otdel biriktirilmagan — bu kassaning MoySklad kirim-chiqimi Buxgalteriyaga yoziladi."
                    : "\nOtdel: <b>" + esc(groups.getOrDefault(groupId, groupId)) + "</b>");
        if (msgId == null) sender.send(chatId, text, null);
        else sender.edit(chatId, msgId, text);
    }


    void rzStart(Session s, long chatId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("🏢 Barcha kassalar", "a:rz:all")));
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            if (k.isCashless()) continue;   // B5: cashless nol boshlashda qatnashmaydi
            rows.add(irow(btn("🏪 " + k.getName(), "a:rz:K" + k.getId())));
        }
        rows.add(irow(btn("❌ Bekor", "cx")));
        sup.sendContent(s, chatId, "♻️ <b>Нол бошлаш</b>\n\n"
                + "Bugungi kundan OLDINGI barcha topshirilmagan kunlar «qabul qilingan» deb "
                + "yopiladi va ularning qoldig'i kassa balansidan (Naqd va Klik) chiqariladi — "
                + "kassa hisobni <b>0 dan</b> boshlaydi.\n"
                + "Bugungi tushum saqlanadi, butun tarix jurnalda qoladi.\n\n"
                + "Kimni nol qilamiz?", inline(rows));
    }


    List<Kassa> rzTargets(String arg) {
        if (arg.equals("all")) return kassaRepo.findByActiveTrueOrderByIdAsc();
        if (arg.startsWith("K"))
            return kassaRepo.findById(Long.parseLong(arg.substring(1)))
                    .map(List::of).orElse(List.of());
        return List.of();
    }


    /** Tanlov qilindi — nima yopilishini ko'rsatib tasdiqlash so'raladi. */
    void rzPick(Session s, String arg, long chatId, int msgId) {
        List<Kassa> targets = rzTargets(arg);
        if (targets.isEmpty()) return;
        java.time.LocalDate today = ledger.today();
        StringBuilder sb = new StringBuilder("♻️ <b>Нол бошлаш — tasdiqlash</b>\n"
                + "📅 <b>" + today.format(DF) + "</b> dan oldingi kunlar yopiladi:\n");
        long tn = 0, tk = 0;
        int td = 0;
        for (Kassa k : targets) {
            long n = 0, kl = 0;
            int c = 0;
            for (DayRecord d : dayRepo.findByKassaIdAndStatusInOrderByDateAsc(k.getId(),
                    List.of(DayStatus.OCHIQ, DayStatus.YOPILGAN)))
                if (d.getDate().isBefore(today)) { n += d.remainNaqd(); kl += d.remainKlik(); c++; }
            tn += n; tk += kl; td += c;
            sb.append("\n🏪 <b>").append(esc(k.getName())).append("</b>: ").append(c)
              .append(" kun — Naqd <b>").append(fmt(n)).append("</b> · Click <b>")
              .append(fmt(kl)).append("</b>");
        }
        sb.append("\n\nJami <b>").append(td).append("</b> kun — Naqd <b>").append(fmt(tn))
          .append("</b> · Click <b>").append(fmt(tk))
          .append("</b> so'm balanslardan chiqariladi.\n\n"
                  + "⚠️ Bu amal ortga qaytarilmaydi (faqat qo'lda korrektirovka bilan). "
                  + "Davom etamizmi?");
        sender.edit(chatId, msgId, sb.toString(), inline(List.of(
                irow(btn("✅ Ha, nol qilinsin", "a:rzc:" + arg)),
                irow(btn("❌ Yo'q", "cx")))));
    }


    void rzCommit(AppUser u, Session s, String arg, long chatId, int msgId) {
        List<Kassa> targets = rzTargets(arg);
        if (targets.isEmpty()) return;
        java.time.LocalDate today = ledger.today();
        StringBuilder sb = new StringBuilder("♻️ <b>Нол бошлаш bajarildi</b> — "
                + esc(u.getFullName()) + "\n📅 " + today.format(DF) + " dan oldingi kunlar yopildi:\n");
        for (Kassa k : targets) {
            long[] r = ledger.resetKassaBefore(k.getId(), today, u.getId());
            long newN = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.NAQD).getAmount();
            long newK = ledger.view(OwnerType.KASSA, k.getId(), MoneyType.KLIK).getAmount();
            sb.append("\n🏪 <b>").append(esc(k.getName())).append("</b>: ").append(r[2])
              .append(" kun yopildi (Naqd ").append(fmt(r[0])).append(" · Click ").append(fmt(r[1]))
              .append(")\n   Yangi balans: Naqd <b>").append(fmt(newN))
              .append("</b> · Click <b>").append(fmt(newK)).append("</b>");
            notify.toKassa(k.getId(), "♻️ <b>Kassangiz yangi hisobni boshladi</b>\n"
                    + today.format(DF) + " dan oldingi kunlar yopildi.\n"
                    + "Joriy balans: Naqd <b>" + fmt(newN) + "</b> · Click <b>" + fmt(newK)
                    + "</b> so'm", null);
        }
        sender.edit(chatId, msgId, sb.toString());
        notify.toBuxgalteriya("♻️ Нол бошлаш — " + esc(u.getFullName())
                + " kassalarni yangi hisobga o'tkazdi (" + today.format(DF)
                + " dan oldingi kunlar yopildi).", null);
    }

}
