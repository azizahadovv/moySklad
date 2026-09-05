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
 * 👁 Ҳуқуқлар: user/kassa kesimida bo'limlarga ruxsat/taqiq, rol berish, faolsizlantirish tasdig'i.
 * (AdminHandler dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
public class PermAdminHandler {

    private final Sender sender;
    private final NameService names;
    private final AppUserRepo userRepo;
    private final KassaRepo kassaRepo;
    private final uz.kassa.service.AuditService audit;
    private final LabelService labelSvc;
    private final PermService permSvc;
    private final AdminSupport sup;
    private final UsersAdminHandler usersH;


    /* ==================================================================
     * 👁 ҲУҚУҚЛАР — foydalanuvchini tanlab, u UI'da nimani ko'rishi va
     * nimalar qila olishini jonli kartochkada ko'rish.
     * ================================================================== */

    void permMenu(Session s, long chatId, int msgId) {
        List<AppUser> users = userRepo.findByActiveTrueOrderByRoleAscIdAsc();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < users.size(); i += 2) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(btn(sup.roleEmoji(users.get(i).getRole()) + " " + users.get(i).getFullName(),
                    "a:prc:" + users.get(i).getId()));
            if (i + 1 < users.size())
                r.add(btn(sup.roleEmoji(users.get(i + 1).getRole()) + " " + users.get(i + 1).getFullName(),
                        "a:prc:" + users.get(i + 1).getId()));
            rows.add(r);
        }
        rows.add(irow(btn("🏬 Отдел кесимида (butun kassaga)", "a:prko")));
        rows.add(irow(sup.bk("a:p:set")));
        String text = "👁 <b>Ҳуқуқлар</b>\n\nKimning imkoniyatlarini ko'rasiz/boshqarasiz?\n"
                + "👤 kassir · 🧮 buxgalter · 👑 superadmin";
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sup.sendContent(s, chatId, text, inline(rows));
    }


    void permCard(AppUser actor, long userId, long chatId, int msgId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null) return;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (x.getRole() != Role.SUPERADMIN)
            rows.add(irow(btn("⚙️ Бўлимларини бошқариш", "a:prs:" + userId)));
        if (sup.canManage(actor, x)) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(btn("🔄 Rol o'zgartirish", "a:prr:" + userId));
            if (!x.getId().equals(actor.getId()))
                r.add(btn("🚫 Faolsizlantirish", "a:prx:" + userId));
            rows.add(r);
        }
        rows.add(irow(sup.bk("a:prm")));
        String note = sup.canManage(actor, x) ? ""
                : "\n\n<i>🔒 SuperAdmin maqomini faqat asosiy (yaratuvchi) SuperAdmin boshqaradi.</i>";
        sender.edit(chatId, msgId, permText(x) + note, inline(rows));
    }


    /** Ҳуқуқлар kartasidan rol tanlash. SuperAdmin qilish faqat yaratuvchiga ko'rinadi. */
    void permRolePick(AppUser actor, long userId, long chatId, int msgId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null) return;
        if (!sup.canManage(actor, x)) { permCard(actor, userId, chatId, msgId); return; }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn("👤 Kassir — " + k.getName(), "a:prz:" + userId + ".K" + k.getId())));
        List<InlineKeyboardButton> r = new ArrayList<>();
        r.add(btn("🧮 Buxgalter", "a:prz:" + userId + ".B"));
        if (sup.isCreator(actor)) r.add(btn("👑 SuperAdmin", "a:prz:" + userId + ".S"));
        rows.add(r);
        rows.add(irow(sup.bk("a:prc:" + userId)));
        sender.edit(chatId, msgId, "🔄 <b>" + esc(x.getFullName()) + "</b> (hozir: "
                + sup.roleEmoji(x.getRole()) + " " + x.getRole()
                + (x.getKassaId() == null ? "" : " · " + esc(names.owner(OwnerType.KASSA, x.getKassaId())))
                + ")\n\nYangi rolni tanlang:", inline(rows));
    }


    /** arg: "<uid>.K<kassaId>" | "<uid>.B" | "<uid>.S" */
    void permRoleApply(AppUser actor, String arg, long chatId, int msgId) {
        int dot = arg.indexOf('.');
        if (dot <= 0) return;
        long userId = Long.parseLong(arg.substring(0, dot));
        String code = arg.substring(dot + 1);
        Role role;
        Long kassaId = null;
        if (code.startsWith("K")) { role = Role.KASSIR; kassaId = Long.parseLong(code.substring(1)); }
        else if (code.equals("B")) role = Role.BUXGALTER;
        else if (code.equals("S")) role = Role.SUPERADMIN;
        else return;
        usersH.applyRoleDirect(actor, userId, role, kassaId, chatId);
        permCard(actor, userId, chatId, msgId);
    }


    /** Ҳуқуқлар kartasidan faolsizlantirish — tasdiq bilan. */
    void permDeactConfirm(AppUser actor, long userId, long chatId, int msgId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null) return;
        if (!sup.canManage(actor, x)) { permCard(actor, userId, chatId, msgId); return; }
        if (x.getId().equals(actor.getId())) {
            sender.edit(chatId, msgId, "⚠️ O'zingizni faolsizlantira olmaysiz.",
                    inline(List.of(irow(sup.bk("a:prc:" + userId)))));
            return;
        }
        sender.edit(chatId, msgId, "⚠️ <b>" + esc(x.getFullName()) + "</b> ("
                + x.getRole() + ") faolsizlantirilsinmi?\n\nU botdan foydalana olmay qoladi. "
                + "Keyin kerak bo'lsa Sheets «Foydalanuvchilar» varag'ida Faol=TRUE qilib "
                + "qaytarish mumkin.", inline(List.of(
                irow(btn("✅ Ha, faolsizlantirilsin", "a:prxy:" + userId)),
                irow(btn("❌ Yo'q", "a:prc:" + userId)))));
    }


    /* ---------- huquq berish/olish: user yoki kassa kesimida ---------- */

    void permKassaList(long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn("🏪 " + k.getName(), "a:prk:" + k.getId())));
        rows.add(irow(sup.bk("a:prm")));
        sender.edit(chatId, msgId, "🏬 <b>Отдел кесимида ҳуқуқ</b>\n\n"
                + "Kassani tanlang — sozlama shu kassaning BARCHA kassirlariga amal qiladi\n"
                + "(user uchun alohida belgilangani bo'lsa, o'shanisi ustun):", inline(rows));
    }


    void permGrid(String subj, long id, long chatId, int msgId) {
        String who = subj.equals("user")
                ? userRepo.findById(id).map(AppUser::getFullName).orElse("#" + id)
                : names.owner(OwnerType.KASSA, id);
        List<String> all = LabelService.RENAMABLE;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        String cb = subj.equals("user") ? "a:prt:" : "a:prq:";
        for (int i = 0; i < all.size(); i += 2) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(permBtn(subj, id, all.get(i), cb + id + "." + i));
            if (i + 1 < all.size())
                r.add(permBtn(subj, id, all.get(i + 1), cb + id + "." + (i + 1)));
            rows.add(r);
        }
        rows.add(irow(sup.bk(subj.equals("user") ? "a:prc:" + id : "a:prko")));
        boolean configured = subj.equals("user") ? permSvc.userConfigured(id) : permSvc.kassaConfigured(id);
        sender.edit(chatId, msgId, "⚙️ <b>Бўлим ҳуқуқлари</b> — "
                + (subj.equals("user") ? "👤 " : "🏪 ") + esc(who) + "\n\n"
                + "Tugmani bosib holatni almashtiring:\n"
                + "🚫 — taqiqlangan · ✅ — ruxsat berilgan · belgisiz — "
                + (configured ? "TAQIQLANGAN (kamida bitta ✅/🚫 belgilangач, belgilanmaganlar ko'rinmaydi)"
                              : "umumiy holat (hali hech narsa belgilanmagan)")
                + "\n<i>O'zgarish foydalanuvchida /start yoki menyu qayta ochilganda ko'rinadi.</i>",
                inline(rows));
    }


    InlineKeyboardButton permBtn(String subj, long id, String canonical, String cb) {
        Boolean o = subj.equals("user") ? permSvc.userOverride(id, canonical)
                : permSvc.kassaOverride(id, canonical);
        String mark = o == null ? "" : (o ? "✅ " : "🚫 ");
        String label = mark + labelSvc.display(canonical);
        if (label.length() > 32) label = label.substring(0, 32);
        return btn(label, cb);
    }


    /** arg: "<id>.<idx>" — holat sikli: meros → 🚫 taqiq → ✅ ruxsat → meros. */
    void permToggle(AppUser admin, String subj, String arg, long chatId, int msgId) {
        int dot = arg.indexOf('.');
        if (dot <= 0) return;
        long id = Long.parseLong(arg.substring(0, dot));
        int idx = Integer.parseInt(arg.substring(dot + 1));
        if (idx < 0 || idx >= LabelService.RENAMABLE.size()) return;
        String canonical = LabelService.RENAMABLE.get(idx);
        Boolean cur = subj.equals("user") ? permSvc.userOverride(id, canonical)
                : permSvc.kassaOverride(id, canonical);
        Boolean next = cur == null ? Boolean.FALSE : (cur ? null : Boolean.TRUE);
        permSvc.set(subj, id, canonical, next);
        audit.log(admin.getId(), "HUQUQ_" + (next == null ? "MEROS" : next ? "RUXSAT" : "TAQIQ"),
                subj, id, canonical);
        permGrid(subj, id, chatId, msgId);
    }


    /** Foydalanuvchining roli+kassasiga qarab UI va huquqlar kartochkasi. */
    String permText(AppUser x) {
        String kassaName = x.getKassaId() == null ? null
                : names.owner(OwnerType.KASSA, x.getKassaId());
        Kassa kassa = x.getKassaId() == null ? null
                : kassaRepo.findById(x.getKassaId()).orElse(null);
        boolean msBound = kassa != null && kassa.getMoyskladGroupId() != null
                && !kassa.getMoyskladGroupId().isBlank();

        StringBuilder sb = new StringBuilder();
        sb.append("👁 <b>").append(esc(x.getFullName())).append("</b> — ")
          .append(sup.roleEmoji(x.getRole())).append(" ").append(x.getRole().name());
        if (kassaName != null) sb.append(" · 🏪 ").append(esc(kassaName));
        sb.append("\n📲 Telegram: ").append(x.getTelegramId() == null
                ? "❌ ulanmagan (menyularni ko'ra olmaydi, xabar olmaydi)" : "✅ ulangan");

        sb.append("\n\n<b>Bosh menyusida ko'radi:</b>\n");
        switch (x.getRole()) {
            case KASSIR -> sb.append("• ").append(esc(labelSvc.display("📊 КАССАМ")))
                    .append(" · ").append(esc(labelSvc.display("💰 БУГУНГИ ТУШУМ")))
                    .append("\n• ").append(esc(labelSvc.display("💸 Rasxod")))
                    .append(" · ").append(esc(labelSvc.display("🔁 O'tkazma")))
                    .append("\n• ").append(esc(labelSvc.display("📤 Hisobot topshirish")))
                    .append(" · ").append(esc(labelSvc.display("🤝 КОНТРАГЕНТ")));
            default -> sb.append("• ").append(esc(labelSvc.display("🏪 Кассалар")))
                    .append(" · ").append(esc(labelSvc.display("📥 Кутилаётганлар")))
                    .append(" · ").append(esc(labelSvc.display("📊 Ҳисоботлар")))
                    .append("\n• ").append(esc(labelSvc.display("🤝 КОНТРАГЕНТ")))
                    .append(" · ").append(esc(labelSvc.display("💰 Баланс")));
        }

        sb.append("\n\n<b>Qila oladi:</b>\n");
        switch (x.getRole()) {
            case KASSIR -> {
                sb.append("• Faqat O'Z kassasi")
                  .append(kassaName == null ? "" : " («" + esc(kassaName) + "»)")
                  .append(": balans, tushum, tarix, Excel\n");
                sb.append("• O'tkazma, hisobot topshirish\n");
                sb.append("• Kontragent qarz daftari: qidiruv, balans, eslatma qo'shish (o'ziniki)\n");
                if (x.getKassaId() != null)
                    sb.append("• 🤝 Настройка: otdeliga odam qo'shish (erkin); o'chirish/tahrir — "
                            + "SuperAdmin tasdig'i bilan\n");
            }
            case BUXGALTER -> {
                sb.append("• Barcha kassalar: holat, statistika, saldo, svod/Excel, tarix\n");
                sb.append("• Hisobot qabul qilish\n");
                sb.append("• Kassadan pul qabul qilish (sana tanlash bilan)\n");
                sb.append("• Kontragent qarz daftari (o'ziniki)\n");
            }
            case SUPERADMIN -> {
                sb.append("• Buxgalter qila oladigan HAMMASI\n");
                sb.append("• Foydalanuvchi/kassa qo'shish-o'chirish, rol o'zgartirish\n");
                sb.append("• Boshlang'ich qoldiq\n");
                sb.append("• Аудит (Excel bilan), tugma nomlari, MoySklad API kaliti\n");
                sb.append("• Kontragent: HAMMANING eslatmalarini ko'radi\n");
            }
        }

        sb.append("\n<b>Ko'rmaydi / qila olmaydi:</b>\n");
        switch (x.getRole()) {
            case KASSIR -> sb.append("• Boshqa kassalar, umumiy statistika, svod, "
                    + "buxgalteriya hisoboti\n• Sozlamalar, Аудит");
            case BUXGALTER -> sb.append("• ⚙️ Настройка (foydalanuvchi/kassa boshqaruvi, "
                    + "Аудит, tugma nomlari, MoySklad kaliti)\n• Boshqalarning qarz eslatmalari");
            case SUPERADMIN -> sb.append("• Cheklov yo'q");
        }

        if (x.getRole() == Role.KASSIR && kassa != null)
            sb.append("\n\nℹ️ Kassasi MoySklad otdeliga ").append(msBound
                    ? "BOG'LANGAN — kirim/chiqim avtomatik tushadi"
                    : "bog'lanMAGAN — MoySklad'dan avtomatik hech narsa kelmaydi, "
                      + "faqat bot orqali yuritiladi");

        sb.append("\n\n<b>Avtomatik xabarlar:</b> ");
        switch (x.getRole()) {
            case KASSIR -> sb.append("o'z kassasining kirim/chiqimi, "
                    + "qarz eslatmalari, 21:00 kunlik eslatma");
            case BUXGALTER, SUPERADMIN -> sb.append("MoySklad kirim/chiqim, STORNO/tuzatishlar, "
                    + "so'rovlar, qarz eslatmalari");
        }
        return sb.toString();
    }

}
