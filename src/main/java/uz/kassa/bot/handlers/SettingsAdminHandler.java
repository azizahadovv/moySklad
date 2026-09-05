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
 * ⚙️ Настройка: menyu, 🏷 tugma nomlari, 💳 karta mas'ullari, 📣 guruh/kanallar va Click hisobot jadvali.
 * (AdminHandler dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
public class SettingsAdminHandler {

    private final Sender sender;
    private final AppUserRepo userRepo;
    private final KassaRepo kassaRepo;
    private final uz.kassa.repo.ClickAccountRepo clickRepo;
    private final uz.kassa.service.AuditService audit;
    private final LabelService labelSvc;
    private final uz.kassa.config.AppProps props;
    private final uz.kassa.service.SettingsService settings;
    private final uz.kassa.scheduler.Jobs jobs;
    private final AdminSupport sup;


    /* ---------- ⚙️ НАСТРОЙКА ---------- */

    void settingsMenu(long chatId, int msgId) {
        sup.show(chatId, msgId, "⚙️ <b>Настройка</b>", List.of(
                irow(btn("🏪 Касса", "a:p:sk")),
                irow(btn("👥 Фойдаланувчилар", "a:p:su")),
                irow(btn("📋 Аудит", "a:audm")),
                irow(btn("🏷 Тугма номлари", "a:lbm"), btn("🔑 MoySklad API", "a:msk")),
                irow(btn("🔄 Номлар (MoySklad)", "a:msr")),
                irow(btn("👁 Ҳуқуқлар", "a:prm"), btn("📣 Гуруҳлар/Каналлар", "a:cg")),
                irow(btn("🔔 Билдиришномалар", "a:nfm")),
                irow(btn("💳 Карта масъуллари", "a:kml")),
                irow(sup.bk("a:p:main"))));
    }


    /* ---------- 💳 KARTA MAS'ULLARI (kim qaysi otdel kartasiga biriktirilgan) ---------- */

    void kartaMasList(long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (ClickAccount c : clickRepo.findByActiveTrueOrderByIdAsc()) {
            String otdel = c.getKassaId() == null ? "—"
                    : kassaRepo.findById(c.getKassaId()).map(Kassa::getName).orElse("?");
            String mas = kartaMasName(c);
            rows.add(irow(btn("💳 " + c.getName() + " · " + otdel + " · " + mas,
                    "a:kmc:" + c.getId())));
        }
        rows.add(irow(sup.bk("a:p:set")));
        String text = "💳 <b>Карта масъуллари</b>\n\n"
                + "Karta → otdel → mas'ul. O'zgartirish uchun kartani bosing:";
        // Reply-menyu (Настройка) yo'lidan ham TUGMALAR bilan ochilsin (sup.show() 0 da
        // inline'siz yuborardi — bu bo'limda tugmasiz ma'no yo'q)
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sender.send(chatId, text, inline(rows));
    }


    /** Mas'ul yorlig'i: {id=..;Ism} / @username / — . */
    String kartaMasName(ClickAccount c) {
        String r = c.getCardResponsible();
        if (r == null || r.isBlank()) return "mas'ul yo'q";
        var m = java.util.regex.Pattern.compile("\\{id=(\\d+);([^}]+)\\}").matcher(r);
        return m.find() ? m.group(2) : r;
    }


    void kartaMasCard(long id, long chatId, int msgId) {
        ClickAccount c = clickRepo.findById(id).orElse(null);
        if (c == null) { kartaMasList(chatId, msgId); return; }
        String otdel = c.getKassaId() == null ? "biriktirilmagan"
                : kassaRepo.findById(c.getKassaId()).map(Kassa::getName).orElse("?");
        String r = c.getCardResponsible();
        var m = java.util.regex.Pattern.compile("\\{id=(\\d+);([^}]+)\\}")
                .matcher(r == null ? "" : r);
        String masLine = (r == null || r.isBlank()) ? "<i>biriktirilmagan</i>"
                : m.find() ? "<b>" + esc(m.group(2)) + "</b> · ID: <code>" + m.group(1) + "</code>"
                : "<b>" + esc(r) + "</b>";
        sup.show(chatId, msgId, "💳 <b>" + esc(c.getName()) + "</b>\n"
                + "🏪 Otdel: <b>" + esc(otdel) + "</b>\n"
                + "👤 Mas'ul: " + masLine + "\n"
                + (c.getCardBalance() == null ? ""
                    : "💰 Karta qoldig'i: <b>" + uz.kassa.bot.TextUtil.fmtTiyin(c.getCardBalance()) + "</b> so'm ("
                      + esc(c.getCardBalanceBy() == null ? "?" : c.getCardBalanceBy()) + ")\n"),
                List.of(
                        irow(btn("👤 Mas'ulni tanlash", "a:kmu:" + id)),
                        irow(btn("🗑 Mas'ulni olib tashlash", "a:kmx:" + id)),
                        irow(sup.bk("a:kml"))));
    }


    void kartaMasUsers(long cardId, long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int shown = 0;
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc()) {
            if (x.getTelegramId() == null) continue;   // Telegram'siz odam tanlolmaydi
            if (shown++ >= 20) break;
            rows.add(irow(btn("👤 " + x.getFullName(), "a:kms:" + cardId + ":" + x.getId())));
        }
        rows.add(irow(sup.bk("a:kmc:" + cardId)));
        sup.show(chatId, msgId, "👤 <b>Mas'ulni tanlang</b> (user ID bilan biriktiriladi):", rows);
    }


    void kartaMasSet(AppUser admin, String arg, long chatId, int msgId) {
        String[] p = arg.split(":");
        long cardId = Long.parseLong(p[0]);
        long userId = Long.parseLong(p[1]);
        ClickAccount c = clickRepo.findById(cardId).orElse(null);
        AppUser x = userRepo.findById(userId).orElse(null);
        if (c == null || x == null || x.getTelegramId() == null) {
            kartaMasList(chatId, msgId);
            return;
        }
        c.setCardResponsible("{id=" + x.getTelegramId() + ";" + x.getFullName() + "}");
        clickRepo.save(c);
        audit.log(admin.getId(), "KARTA_MASUL", "click", cardId,
                admin.getFullName() + ": " + c.getName() + " -> " + x.getFullName()
                        + " (tg=" + x.getTelegramId() + ")");
        kartaMasCard(cardId, chatId, msgId);
    }


    void kartaMasClear(AppUser admin, long cardId, long chatId, int msgId) {
        clickRepo.findById(cardId).ifPresent(c -> {
            c.setCardResponsible(null);
            clickRepo.save(c);
            audit.log(admin.getId(), "KARTA_MASUL", "click", cardId,
                    admin.getFullName() + ": " + c.getName() + " -> mas'ul olib tashlandi");
        });
        kartaMasCard(cardId, chatId, msgId);
    }


    /* ==================================================================
     * 🏷 ТУГМА НОМЛАРИ — sahifa/tugma nomlarini o'zgartirish.
     * ================================================================== */

    String labelMark(String canonical) {
        return (labelSvc.isHidden(canonical) ? "🙈 " : "") + labelSvc.display(canonical)
                + (labelSvc.isRenamed(canonical) ? " *" : "");
    }


    void labelList(Session s, long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<String> all = LabelService.RENAMABLE;
        for (int i = 0; i < all.size(); i += 2) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(btn(labelMark(all.get(i)), "a:lb:" + i));
            if (i + 1 < all.size()) r.add(btn(labelMark(all.get(i + 1)), "a:lb:" + (i + 1)));
            rows.add(r);
        }
        String text = "🏷 <b>Тугма номлари ва бўлимлар</b>\n\n"
                + "Bo'limni tanlang — nomini o'zgartirish yoki o'chirish/yoqish mumkin.\n"
                + "<i>* — nomi o'zgartirilgan · 🙈 — o'chirilgan (menyularda ko'rinmaydi)</i>";
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sup.sendContent(s, chatId, text, inline(rows));
    }


    /** Bo'lim kartochkasi: nom o'zgartirish / o'chirish-yoqish. */
    void labelPick(Session s, int idx, long chatId, int msgId) {
        if (idx < 0 || idx >= LabelService.RENAMABLE.size()) return;
        String canonical = LabelService.RENAMABLE.get(idx);
        boolean hidden = labelSvc.isHidden(canonical);
        boolean protectd = canonical.equals(LabelService.PROTECTED_LABEL);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("✏️ Nomini o'zgartirish", "a:lbr:" + idx)));
        if (!protectd)
            rows.add(irow(btn(hidden ? "👁 Yoqish (menyuga qaytarish)"
                    : "🙈 O'chirish (menyudan yashirish)", "a:lbh:" + idx)));
        rows.add(irow(sup.bk("a:lbm")));
        sender.edit(chatId, msgId, "🏷 <b>" + esc(labelSvc.display(canonical)) + "</b>"
                + (labelSvc.isRenamed(canonical) ? " (asl: " + esc(canonical) + ")" : "")
                + "\nHolat: " + (hidden ? "🙈 <b>o'chirilgan</b> — menyularda ko'rinmaydi"
                    : "👁 <b>ko'rinadi</b>")
                + (protectd ? "\n\nℹ️ Bu bo'limni o'chirib bo'lmaydi — sozlamalarga kirish yo'li."
                    : ""), inline(rows));
    }


    void labelRenameStart(Session s, int idx, long chatId, int msgId) {
        if (idx < 0 || idx >= LabelService.RENAMABLE.size()) return;
        String canonical = LabelService.RENAMABLE.get(idx);
        s.state = Session.State.ADM_LB_NAME;
        s.data.put("lbIdx", idx);
        sender.edit(chatId, msgId, "🏷 Tanlandi: <b>" + esc(labelSvc.display(canonical))
                + "</b>" + (labelSvc.isRenamed(canonical)
                    ? " (asl: " + esc(canonical) + ")" : "") + "\n\n"
                + "<b>Yangi nomni yozing</b> (2–30 belgi).\n"
                + "Asl nomga qaytarish uchun «-» yuboring:");
    }


    void labelHideToggle(Session s, int idx, long chatId, int msgId) {
        if (idx < 0 || idx >= LabelService.RENAMABLE.size()) return;
        String canonical = LabelService.RENAMABLE.get(idx);
        if (canonical.equals(LabelService.PROTECTED_LABEL)) { labelPick(s, idx, chatId, msgId); return; }
        boolean nowHidden = !labelSvc.isHidden(canonical);
        labelSvc.setHidden(canonical, nowHidden);
        audit.log(null, nowHidden ? "BOLIM_OCHIRILDI" : "BOLIM_YOQILDI", "label", null, canonical);
        sender.send(chatId, (nowHidden
                ? "🙈 <b>" + esc(labelSvc.display(canonical)) + "</b> o'chirildi — menyularda "
                  + "ko'rinmaydi, bosilsa ham ishlamaydi (SuperAdmin'dan tashqari)."
                : "👁 <b>" + esc(labelSvc.display(canonical)) + "</b> yoqildi — menyularga qaytdi.")
                + "\nFoydalanuvchilarda yangi menyu /start bosilganda ko'rinadi.");
        labelPick(s, idx, chatId, msgId);
    }


    void labelName(Session s, String text, long chatId) {
        int idx = (int) s.getLong("lbIdx");
        String canonical = LabelService.RENAMABLE.get(idx);
        s.state = Session.State.IDLE;
        s.data.remove("lbIdx");

        if (text.equals("-")) {
            labelSvc.rename(canonical, "");
            sender.send(chatId, "✅ <b>" + esc(canonical) + "</b> asl nomiga qaytarildi.\n"
                    + "Yangilangan menyu uchun bo'limni qayta oching yoki /start bosing.");
            return;
        }
        String name = text.trim();
        if (name.length() < 2 || name.length() > 30) {
            sender.send(chatId, "⚠️ Nom 2–30 belgi bo'lsin. Qaytadan yozing:");
            s.state = Session.State.ADM_LB_NAME;
            s.data.put("lbIdx", idx);
            return;
        }
        if (name.chars().allMatch(Character::isDigit)) {
            sender.send(chatId, "⚠️ Faqat raqamdan iborat nom bo'lmaydi (summa kiritish bilan "
                    + "adashadi). Qaytadan yozing:");
            s.state = Session.State.ADM_LB_NAME;
            s.data.put("lbIdx", idx);
            return;
        }
        if (labelSvc.clashes(canonical, name)) {
            sender.send(chatId, "⚠️ Bu nom boshqa tugma bilan bir xil bo'lib qoladi. Boshqa nom yozing:");
            s.state = Session.State.ADM_LB_NAME;
            s.data.put("lbIdx", idx);
            return;
        }
        labelSvc.rename(canonical, name);
        sender.send(chatId, "✅ Tugma nomi o'zgartirildi:\n<b>" + esc(canonical) + "</b> → <b>"
                + esc(name) + "</b>\n\nYangi nom menyu qayta ochilganda ko'rinadi "
                + "(foydalanuvchilar /start bosishi kifoya).");
    }


    /* ==================================================================
     * 📣 ГУРУҲЛАР/КАНАЛЛАР — har soatda Click qoldiqlari yuboriladigan
     * guruh/kanallar RO'YXATI. ID kiritilganda bot shu chatda ADMIN/A'ZO
     * ekanligi darhol tekshiriladi — noto'g'ri ID yoki bot qo'shilmagan chat
     * sababli keyinchalik jim ishlamay qolmasligi uchun.
     * ================================================================== */

    void clickGroupMenu(Session s, long chatId, int msgId) {
        List<Long> ids = jobs.clickChatIds();
        StringBuilder status = new StringBuilder();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (ids.isEmpty()) {
            status.append("🔴 <b>Hech qanday guruh/kanal ulanmagan</b>");
        } else {
            for (long gid : ids) {
                var chat = sender.getChat(gid);
                String botStat = sender.botStatusInChat(gid);
                boolean canPost = "administrator".equals(botStat) || "member".equals(botStat)
                        || "creator".equals(botStat);
                String name = chat != null
                        ? (chat.getTitle() != null ? chat.getTitle() : chat.getUserName()) : null;
                String shown = name != null ? name : ("ID " + gid);
                boolean channel = chat != null && chat.isChannelChat();
                status.append(canPost ? "🟢 " : "🟠 ").append(channel ? "📢 " : "👥 ")
                      .append("<b>").append(esc(shown)).append("</b> (<code>").append(gid).append("</code>)");
                if (!canPost) status.append(" — bot bu chatda topilmadi yoki chiqarib yuborilgan");
                status.append("\n");
                rows.add(irow(btn("🗑 " + shown, "a:cgx:" + gid)));
            }
        }
        String text = "📣 <b>Гуруҳлар / Каналлар</b>\n\n"
                + "Quyidagi barcha chatlarga Click hisoblarining MoySklad bilan "
                + "tenglashtirilgan qoldig'i yuboriladi.\n"
                + "Bu chatlarda bot hech qanday menyu ko'rsatmaydi va faqat SuperAdmin "
                + "buyruqlariga javob beradi.\n\n"
                + "⏰ Jadval: <b>har " + jobs.clickEvery() + " soatda</b>, "
                + String.format("<b>%02d:00–%02d:00</b> oralig'ida", jobs.clickFrom(), jobs.clickTo())
                + (jobs.clickOffsetMin() == 0 ? "" : String.format(", siljish <b>%+d min</b> (masalan %s)",
                        jobs.clickOffsetMin(), jobs.clickTimeExample(jobs.clickFrom())))
                + "\n"
                + (jobs.clickFooter().isEmpty() ? ""
                    : "✍️ Ost matn: <code>" + esc(jobs.clickFooter()) + "</code>\n")
                + "\n" + status;
        rows.add(irow(btn("➕ Guruh/Kanal qo'shish", "a:cge")));
        rows.add(irow(btn("⏰ Yuborish vaqtlari", "a:cgs"), btn("✍️ Ост матн", "a:cgf")));
        if (!ids.isEmpty()) rows.add(irow(btn("🧪 Hozir test yuborish", "a:cgt")));
        rows.add(irow(sup.bk("a:p:set")));
        InlineKeyboardMarkup kb = inline(rows);
        if (msgId > 0) sender.edit(chatId, msgId, text, kb);
        else sup.sendContent(s, chatId, text, kb);
    }


    /** Click hisoboti ostiga qo'shiladigan matnni saqlash («-» — olib tashlash). */
    void cgFooterSave(AppUser u, Session s, String text, long chatId) {
        s.state = Session.State.IDLE;
        String v = text.trim();
        if (v.equals("-")) {
            settings.set(uz.kassa.scheduler.Jobs.CLICK_FOOTER_KEY, "");
            audit.log(u.getId(), "CLICK_OST_MATN", "settings", null,
                    u.getFullName() + " hisobot ost matnini olib tashladi");
            sender.send(chatId, "🗑 Ost matn olib tashlandi.");
        } else {
            if (v.length() > 300) {
                s.state = Session.State.ADM_CG_FOOTER;
                sender.send(chatId, "⚠️ Juda uzun (300 belgigacha). Qisqartirib qayta yuboring "
                        + "yoki «-» bilan bekor qiling.");
                return;
            }
            settings.set(uz.kassa.scheduler.Jobs.CLICK_FOOTER_KEY, v);
            audit.log(u.getId(), "CLICK_OST_MATN", "settings", null,
                    u.getFullName() + " hisobot ost matnini o'zgartirdi: " + v);
            sender.send(chatId, "✅ Saqlandi. Endi har Click hisoboti ostida chiqadi:\n\n"
                    + esc(v) + "\n\n🧪 Tekshirish: 📣 Гуруҳлар/Каналлар → «Hozir test yuborish».");
        }
        clickGroupMenu(s, chatId, 0);
    }


    void cgIdSave(AppUser u, Session s, String text, long chatId) {
        s.state = Session.State.IDLE;
        if (text.equals("-")) {
            sender.send(chatId, "❌ Bekor qilindi.");
            clickGroupMenu(s, chatId, 0);
            return;
        }
        long gid;
        try {
            gid = Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            s.state = Session.State.ADM_CG_ID;
            sender.send(chatId, "⚠️ Bu raqamga o'xshamaydi. Guruh ID sini qaytadan yuboring "
                    + "(masalan -1001234567890) yoki «-» bilan bekor qiling:");
            return;
        }
        var chat = sender.getChat(gid);
        String botStat = sender.botStatusInChat(gid);
        boolean ok = chat != null && ("administrator".equals(botStat) || "member".equals(botStat)
                || "creator".equals(botStat));
        if (!ok) {
            s.state = Session.State.ADM_CG_ID;
            sender.send(chatId, "⚠️ Bu ID (<code>" + gid + "</code>) bilan chat topilmadi yoki "
                    + "bot u yerga hali qo'shilmagan.\n\nAvval botni (@" + esc(props.getBot().getUsername())
                    + ") shu guruhga qo'shing, so'ng ID ni qaytadan yuboring yoki «-» bilan bekor qiling:");
            return;
        }
        jobs.addClickChat(gid);
        audit.log(u.getId(), "CLICK_GROUP_SET", "settings", null,
                u.getFullName() + " hisobot ro'yxatiga guruh/kanal qo'shdi: " + gid);
        String name = chat.getTitle() != null ? chat.getTitle() : chat.getUserName();
        sender.send(chatId, "✅ <b>" + esc(name != null ? name : String.valueOf(gid))
                + "</b> hisobot yuboriladigan chatlar ro'yxatiga qo'shildi.\nJadval bo'yicha "
                + "shu yerga Click qoldiqlari tushadi.");
        clickGroupMenu(s, chatId, 0);
    }


    /** ⏰ Hisobot yuborish jadvali: interval (necha soatda bir) va soat oynasi. */
    void clickScheduleMenu(Session s, long chatId, int msgId) {
        int every = jobs.clickEvery(), from = jobs.clickFrom(), to = jobs.clickTo();
        int off = jobs.clickOffsetMin();
        String text = "⏰ <b>Yuborish vaqtlari</b>\n\n"
                + "Joriy jadval: <b>har " + every + " soatda</b>, "
                + String.format("<b>%02d:00–%02d:00</b> oralig'ida", from, to)
                + (off == 0 ? "" : String.format(", siljish <b>%+d min</b>", off)) + ".\n"
                + "Masalan: <b>" + jobs.clickTimeExample(from) + "</b>, <b>"
                + jobs.clickTimeExample(Math.min(23, from + every)) + "</b>, …\n\n"
                + "Hisobot nominal soat + siljish vaqtida yuboriladi: soat tanlangan "
                + "oraliqda bo'lsa va intervalga to'g'ri kelsa.\n\n"
                + "<b>Interval</b> (necha soatda bir) · <b>Oraliq</b> · <b>Minut siljishi</b>:";
        java.util.function.BiFunction<Integer, String, InlineKeyboardButton> ib = (h, cb) ->
                btn((h == every ? "✅ " : "") + h + " soat", cb);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(ib.apply(1, "a:cgi:1"), ib.apply(2, "a:cgi:2"), ib.apply(3, "a:cgi:3")));
        rows.add(irow(ib.apply(4, "a:cgi:4"), ib.apply(6, "a:cgi:6"), ib.apply(12, "a:cgi:12")));
        rows.add(irow(ib.apply(24, "a:cgi:24")));
        java.util.function.BiFunction<int[], String, InlineKeyboardButton> wb = (w, cb) -> {
            boolean cur = w[0] == from && w[1] == to;
            return btn((cur ? "✅ " : "") + String.format("%02d:00–%02d:00", w[0], w[1]), cb);
        };
        rows.add(irow(wb.apply(new int[]{0, 23}, "a:cgw:0:23"),
                      wb.apply(new int[]{8, 20}, "a:cgw:8:20")));
        rows.add(irow(wb.apply(new int[]{9, 18}, "a:cgw:9:18"),
                      wb.apply(new int[]{9, 22}, "a:cgw:9:22")));
        // Minut siljishi: -20 … +20 (5 daqiqalik qadam). ✅ — joriy tanlov.
        java.util.function.Function<Integer, InlineKeyboardButton> ob = m ->
                btn((m == off ? "✅ " : "") + (m == 0 ? ":00" : String.format("%+d min", m)), "a:cgo:" + m);
        rows.add(irow(ob.apply(-20), ob.apply(-15), ob.apply(-10), ob.apply(-5)));
        rows.add(irow(ob.apply(0)));
        rows.add(irow(ob.apply(5), ob.apply(10), ob.apply(15), ob.apply(20)));
        rows.add(irow(btn("⬅️ Orqaga", "a:cg")));
        InlineKeyboardMarkup kb = inline(rows);
        if (msgId > 0) sender.edit(chatId, msgId, text, kb);
        else sup.sendContent(s, chatId, text, kb);
    }

}
