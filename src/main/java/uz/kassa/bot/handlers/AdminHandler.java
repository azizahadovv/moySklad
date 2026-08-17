package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
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

/** SuperAdmin oqimlari (TZ 8.3): foydalanuvchi/kassa boshqaruvi, boshlang'ich qoldiqlar. */
@Component
@RequiredArgsConstructor
public class AdminHandler {

    private final Sender sender;
    private final NameService names;
    private final LedgerService ledger;
    private final AppUserRepo userRepo;
    private final KassaRepo kassaRepo;
    private final uz.kassa.repo.GuestRepo guestRepo;
    private final MoySkladClient msClient;
    private final uz.kassa.repo.DayRepo dayRepo;
    private final uz.kassa.repo.OperationRepo opRepo;
    private final uz.kassa.repo.DebtRepo debtRepo;
    private final NotificationService notify;
    private final uz.kassa.webapp.ExcelReportService excelReport;
    private final BuxgalterHandler bux;
    private final uz.kassa.service.moysklad.MoySkladSyncService syncService;

    private static final java.time.format.DateTimeFormatter DF =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /* ============================ MATN ============================ */

    public boolean onText(AppUser u, Session s, String text, long chatId) {
        if (u.getRole() != Role.SUPERADMIN) return false;

        switch (s.state) {
            case ADM_AU_TGID -> { auTgId(s, text, chatId); return true; }
            case ADM_AU_NAME -> { auName(s, text, chatId); return true; }
            case ADM_AK_NAME -> { akName(s, text, chatId); return true; }
            case ADM_AK_MSID -> { akFinish(s, text, chatId); return true; }
            case ADM_IB_NAQD -> { ibNaqd(s, text, chatId); return true; }
            case ADM_IB_KLIK -> { ibFinish(u, s, text, chatId); return true; }
            default -> { }
        }

        // Panel ichida bo'lsa — pastki menu tugmalari bo'yicha navigatsiya
        String nav = s.getStr("nav");
        if (nav != null && handleNav(u, s, nav, text, chatId)) return true;

        return switch (text) {
            case "👑 АДМИН ПАНЕЛ" -> {
                navTo(s, "panel", chatId, "👑 <b>АДМИН ПАНЕЛ</b>\n\nBo'limni tanlang:",
                        List.of("🏬 Отдел", "⚙️ Настройка", "📈 Статистика"));
                yield true;
            }
            case "💰 БУГУНГИ ТУШУМ" -> { tushumAll(chatId); yield true; }
            case "👥 Foydalanuvchi qo'shish" -> { auStart(s, chatId); yield true; }
            case "🏪 Kassa qo'shish" -> {
                s.reset(); s.state = Session.State.ADM_AK_NAME;
                sender.send(chatId, "🏪 <b>Yangi kassa</b>\n\nKassa nomini kiriting:", cancelOnly());
                yield true;
            }
            case "💼 Boshlang'ich qoldiq" -> { ibStart(s, chatId); yield true; }
            case "👤 Foydalanuvchilar" -> { listUsers(u, chatId); yield true; }
            default -> false;
        };
    }

    /* ============================ CALLBACK ============================ */

    public boolean onCallback(AppUser u, Session s, String data, long chatId, int msgId) {
        if (u.getRole() != Role.SUPERADMIN || !data.startsWith("a:")) return false;
        String[] p = data.split(":", 3);
        String cmd = p[1];
        String arg = p.length > 2 ? p[2] : "";

        switch (cmd) {
            case "p" -> panel(u, s, arg, chatId, msgId);
            case "gu" -> auPick(s, arg, chatId, msgId);
            case "rl" -> auRole(s, arg, chatId, msgId);
            case "ks" -> auKassa(s, arg, chatId, msgId);
            case "gr" -> akGroup(s, arg, chatId, msgId);
            case "ib" -> ibOwner(s, arg, chatId, msgId);
            case "ux" -> deactivate(u, Long.parseLong(arg), chatId, msgId);
            default -> { return false; }
        }
        return true;
    }

    /* ==================================================================
     * 👑 АДМИН ПАНЕЛ — PASTKI MENU TUGMALARI bilan ichma-ich navigatsiya.
     * Har daraja reply-keyboard'ni almashtiradi, «⬅️ Orqaga» bir pog'ona
     * yuqoriga qaytaradi (bosh sahifaga emas).
     * ================================================================== */

    private static final List<String> PERIODS =
            List.of("📆 Bugun", "Kecha", "7 kun", "30 kun", "Shu oy");
    private static final List<String> STAT_MENU =
            List.of("🧾 Карзлар реестр", "📜 История", "👥 Фойдаланувчилар умумий",
                    "🏦 Бухгалтерия", "💼 Салдо", "📊 Свод");
    private static final List<String> SOZUSER_MENU =
            List.of("➕ Фойдаланувчи қўшиш", "🔄 Рол ўзгартириш", "🚫 Фойдаланувчини ўчириш");

    private void navTo(Session s, String nav, long chatId, String title, List<String> labels) {
        s.data.put("nav", nav);
        sender.send(chatId, title, menuKb(labels));
    }

    private org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup
            menuKb(List<String> labels) {
        List<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow> rows =
                new ArrayList<>();
        for (int i = 0; i < labels.size(); i += 2) {
            var r = new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow();
            r.add(labels.get(i));
            if (i + 1 < labels.size()) r.add(labels.get(i + 1));
            rows.add(r);
        }
        var back = new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow();
        back.add("⬅️ Orqaga");
        rows.add(back);
        var m = new org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup();
        m.setKeyboard(rows);
        m.setResizeKeyboard(true);
        return m;
    }

    private List<String> kassaLabels() {
        List<String> out = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) out.add("🏪 " + k.getName());
        return out;
    }

    private Kassa kassaByLabel(String text) {
        String name = text.startsWith("🏪 ") ? text.substring(3).trim() : text.trim();
        return kassaRepo.findByActiveTrueOrderByIdAsc().stream()
                .filter(k -> k.getName().equals(name)).findFirst().orElse(null);
    }

    private String codeOf(String text) {
        return switch (text) {
            case "📆 Bugun" -> "t"; case "Kecha" -> "y";
            case "7 kun" -> "7"; case "30 kun" -> "30"; case "Shu oy" -> "m";
            default -> null;
        };
    }

    private long idOf(String nav) { return Long.parseLong(nav.substring(nav.indexOf(':') + 1)); }

    private boolean handleNav(AppUser u, Session s, String nav, String text, long chatId) {
        if (text.equals("⬅️ Orqaga")) { navBack(u, s, nav, chatId); return true; }
        String lvl = nav.split(":")[0];
        switch (lvl) {
            case "panel" -> {
                switch (text) {
                    case "🏬 Отдел" -> navTo(s, "otdel", chatId,
                            "🏬 <b>Отдел</b>\n\nKassani tanlang:", kassaLabels());
                    case "⚙️ Настройка" -> navTo(s, "sozlash", chatId, "⚙️ <b>Настройка</b>",
                            List.of("🏪 Касса", "👥 Фойдаланувчилар", "💼 Бошланғич қолдиқ"));
                    case "📈 Статистика" -> navTo(s, "stat", chatId, "📈 <b>Статистика</b>", STAT_MENU);
                    default -> { return false; }
                }
            }
            case "otdel" -> {
                Kassa k = kassaByLabel(text);
                if (k == null) return false;
                navTo(s, "kassa:" + k.getId(), chatId,
                        "🏪 <b>" + esc(k.getName()) + "</b>\n\nBo'limni tanlang:",
                        List.of("💰 Бугунги тушум", "💸 Расход", "📆 Давр танлаш"));
            }
            case "kassa" -> {
                long id = idOf(nav);
                switch (text) {
                    case "💰 Бугунги тушум" -> { syncService.syncIfStale(45); kassaTushum(id, chatId, 0); }
                    case "💸 Расход" -> { syncService.syncIfStale(45); kassaRasxod(id, chatId, 0); }
                    case "📆 Давр танлаш" -> navTo(s, "davr:" + id, chatId,
                            "📆 <b>Давр танлаш</b>\n\nDavrni tanlang:", PERIODS);
                    default -> { return false; }
                }
            }
            case "davr" -> {
                String code = codeOf(text);
                if (code == null) return false;
                syncService.syncIfStale(45);
                kassaPeriodStats(idOf(nav), code, chatId, 0);
            }
            case "sozlash" -> {
                switch (text) {
                    case "🏪 Касса" -> navTo(s, "sozkassa", chatId, "🏪 <b>Касса</b>",
                            List.of("➕ Касса қўшиш", "🚫 Касса ўчириш"));
                    case "👥 Фойдаланувчилар" -> navTo(s, "sozuser", chatId,
                            "👥 <b>Фойдаланувчилар</b>", SOZUSER_MENU);
                    case "💼 Бошланғич қолдиқ" -> { ibStart(s, chatId); s.data.put("nav", "sozlash"); }
                    default -> { return false; }
                }
            }
            case "sozkassa" -> {
                switch (text) {
                    case "➕ Касса қўшиш" -> {
                        s.state = Session.State.ADM_AK_NAME;
                        sender.send(chatId, "🏪 <b>Yangi kassa</b>\n\nKassa nomini kiriting:");
                    }
                    case "🚫 Касса ўчириш" -> navTo(s, "kassadel", chatId,
                            "🚫 <b>Касса ўчириш</b>\n\nQaysi kassani o'chirasiz?", kassaLabels());
                    default -> { return false; }
                }
            }
            case "kassadel" -> {
                Kassa k = kassaByLabel(text);
                if (k == null) return false;
                navTo(s, "kassadelc:" + k.getId(), chatId,
                        "⚠️ <b>" + esc(k.getName()) + "</b> kassasi o'chirilsinmi?\n\n"
                                + "Kassa faolsizlanadi — tarix saqlanadi.",
                        List.of("✅ Ha, o'chirilsin", "❌ Yo'q"));
            }
            case "kassadelc" -> {
                if (text.startsWith("✅")) {
                    long id = idOf(nav);
                    kassaRepo.findById(id).ifPresent(k -> { k.setActive(false); kassaRepo.save(k); });
                    navTo(s, "sozkassa", chatId, "🚫 Kassa faolsizlantirildi",
                            List.of("➕ Касса қўшиш", "🚫 Касса ўчириш"));
                } else if (text.startsWith("❌")) {
                    navTo(s, "sozkassa", chatId, "🏪 <b>Касса</b>",
                            List.of("➕ Касса қўшиш", "🚫 Касса ўчириш"));
                } else return false;
            }
            case "sozuser" -> {
                switch (text) {
                    case "➕ Фойдаланувчи қўшиш" -> { auStart(s, chatId); s.data.put("nav", "sozuser"); }
                    case "🔄 Рол ўзгартириш" -> navTo(s, "roluser", chatId,
                            "🔄 <b>Рол ўзгартириш</b>\n\nFoydalanuvchini tanlang:", userLabels());
                    case "🚫 Фойдаланувчини ўчириш" -> listUsers(u, chatId);
                    default -> { return false; }
                }
            }
            case "roluser" -> {
                AppUser x = userByLabel(text);
                if (x == null) return false;
                navTo(s, "rolpick:" + x.getId(), chatId,
                        "🔄 <b>" + esc(x.getFullName()) + "</b> (hozir: " + x.getRole()
                                + (x.getKassaId() == null ? "" :
                                   " · " + esc(names.owner(OwnerType.KASSA, x.getKassaId()))) + ")\n\n"
                                + "Yangi rolni tanlang:", roleLabels());
            }
            case "rolpick" -> {
                if (!applyRole(idOf(nav), text, chatId)) return false;
                navTo(s, "sozuser", chatId, "👥 <b>Фойдаланувчилар</b>", SOZUSER_MENU);
            }
            case "stat" -> {
                switch (text) {
                    case "🧾 Карзлар реестр" -> bux.debtsRegistry(chatId);
                    case "📜 История" -> bux.historyMenu(chatId);
                    case "👥 Фойдаланувчилар умумий" -> listUsers(u, chatId);
                    case "🏦 Бухгалтерия" -> buxReport(chatId);
                    case "💼 Салдо" -> {
                        List<String> ls = kassaLabels(); ls.add("🏦 Buxgalteriya");
                        navTo(s, "saldo", chatId, "💼 <b>Салдо</b>\n\nKassani tanlang:", ls);
                    }
                    case "📊 Свод" -> navTo(s, "svod", chatId, "📊 <b>Свод</b>\n\nExcel turini tanlang:",
                            List.of("📗 Умумий Excel", "📘 Даврий Excel", "📙 Отдел Excel"));
                    default -> { return false; }
                }
            }
            case "saldo" -> {
                if (text.equals("🏦 Buxgalteriya")) { syncService.syncIfStale(45); saldoKassa("B", chatId, 0); }
                else {
                    Kassa k = kassaByLabel(text);
                    if (k == null) return false;
                    syncService.syncIfStale(45);
                    saldoKassa(String.valueOf(k.getId()), chatId, 0);
                }
            }
            case "svod" -> {
                switch (text) {
                    case "📗 Умумий Excel" -> genExcel(chatId, 0, "m", null);
                    case "📘 Даврий Excel" -> navTo(s, "svoddavr", chatId,
                            "📘 <b>Даврий Excel</b>\n\nDavrni tanlang:", PERIODS);
                    case "📙 Отдел Excel" -> navTo(s, "svodotd", chatId,
                            "📙 <b>Отдел Excel</b>\n\nKassani tanlang:", kassaLabels());
                    default -> { return false; }
                }
            }
            case "svoddavr" -> {
                String code = codeOf(text);
                if (code == null) return false;
                genExcel(chatId, 0, code, null);
            }
            case "svodotd" -> {
                Kassa k = kassaByLabel(text);
                if (k == null) return false;
                navTo(s, "svodotdd:" + k.getId(), chatId,
                        "📙 <b>Отдел Excel</b> — " + esc(k.getName()) + "\n\nDavrni tanlang:", PERIODS);
            }
            case "svodotdd" -> {
                String code = codeOf(text);
                if (code == null) return false;
                genExcel(chatId, 0, code, idOf(nav));
            }
            default -> { return false; }
        }
        return true;
    }

    /* ---------- 🔄 ROL O'ZGARTIRISH ---------- */

    private List<String> userLabels() {
        List<String> out = new ArrayList<>();
        for (AppUser x : userRepo.findByActiveTrueOrderByRoleAscIdAsc())
            out.add("#" + x.getId() + " " + x.getFullName());
        return out;
    }

    private AppUser userByLabel(String text) {
        if (!text.startsWith("#")) return null;
        int sp = text.indexOf(' ');
        if (sp < 0) return null;
        try {
            return userRepo.findById(Long.parseLong(text.substring(1, sp))).orElse(null);
        } catch (NumberFormatException e) { return null; }
    }

    private List<String> roleLabels() {
        List<String> out = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            out.add("👤 Kassir — " + k.getName());
        out.add("🧮 Buxgalter");
        out.add("👑 SuperAdmin");
        return out;
    }

    private boolean applyRole(long userId, String text, long chatId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null) return false;

        Role newRole; Long kassaId = null;
        if (text.startsWith("👤 Kassir — ")) {
            Kassa k = kassaByLabel(text.substring("👤 Kassir — ".length()));
            if (k == null) return false;
            newRole = Role.KASSIR; kassaId = k.getId();
        } else if (text.equals("🧮 Buxgalter")) newRole = Role.BUXGALTER;
        else if (text.equals("👑 SuperAdmin")) newRole = Role.SUPERADMIN;
        else return false;

        // Oxirgi SuperAdmin'ni pasaytirib bo'lmaydi — tizim egasiz qolmasin
        if (x.getRole() == Role.SUPERADMIN && newRole != Role.SUPERADMIN
                && userRepo.findByRoleAndActiveTrue(Role.SUPERADMIN).size() <= 1) {
            sender.send(chatId, "⚠️ Bu oxirgi SuperAdmin — rolini o'zgartirib bo'lmaydi. "
                    + "Avval boshqa SuperAdmin tayinlang.");
            return true;
        }

        x.setRole(newRole);
        x.setKassaId(kassaId);
        userRepo.save(x);
        sender.send(chatId, "✅ <b>" + esc(x.getFullName()) + "</b> roli o'zgartirildi: <b>"
                + newRole + (kassaId == null ? "" : " · " + esc(names.owner(OwnerType.KASSA, kassaId)))
                + "</b>");
        notify.toUser(x.getTelegramId(),
                "🔄 Rolingiz o'zgartirildi. Yangi menyu uchun /start yozing.");
        return true;
    }

    /* ---------- 🏦 БУХГАЛТЕРИЯ HISOBOTI ---------- */

    private void buxReport(long chatId) {
        syncService.syncIfStale(45);
        var n = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.NAQD);
        var k = ledger.view(OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, MoneyType.KLIK);
        java.time.LocalDate from = ledger.today().withDayOfMonth(1);

        long kirim = 0, chiqim = 0, boshl = 0;
        List<String> rasxodLines = new ArrayList<>();
        for (Operation o : opRepo.byPeriod(from, ledger.today())) {
            boolean in = o.getToOwnerType() == OwnerType.BUXGALTERIYA;
            boolean out = o.getFromOwnerType() == OwnerType.BUXGALTERIYA;
            if (!in && !out) continue;
            if (o.getType() == OpType.BOSHLANGICH && in) boshl += o.getAmount();
            else if (in) kirim += o.getAmount();
            if (out) {
                chiqim += o.getAmount();
                if (rasxodLines.size() < 15)
                    rasxodLines.add("• " + o.getOpDate().format(DF) + " — <b>"
                            + fmt(o.getAmount()) + "</b> so'm"
                            + (o.getComment() == null || o.getComment().isEmpty()
                                ? "" : " — " + esc(o.getComment())));
            }
        }

        StringBuilder sb = new StringBuilder("🏦 <b>БУХГАЛТЕРИЯ ҲИСОБОТИ</b>\n📅 "
                + from.format(DF) + " — " + ledger.today().format(DF) + "\n\n"
                + "💵 Naqd balans: <b>" + fmt(n.getAmount()) + "</b> so'm\n"
                + "📲 Click balans: <b>" + fmt(k.getAmount()) + "</b> so'm\n\n"
                + "⚙️ Boshlang'ich qoldiq: <b>" + fmt(boshl) + "</b>\n"
                + "🟢 Kirimlar (shu oy): <b>" + fmt(kirim) + "</b>\n"
                + "🔴 Chiqimlar (shu oy): <b>" + fmt(chiqim) + "</b>\n");

        if (n.getAmount() < 0 || k.getAmount() < 0) {
            sb.append("\n⚠️ <b>Balans manfiy — bu QARZ EMAS.</b>\n");
            if (boshl == 0)
                sb.append("Sabab: boshlang'ich qoldiq kiritilmagan — tizim 0 dan boshlab "
                        + "hisoblayapti, MoySklad chiqimlari esa ayirilyapti.\n"
                        + "Yechim: ⚙️ Настройка → 💼 Бошланғич қолдиқ → Buxgalteriya.\n");
        }

        if (!rasxodLines.isEmpty())
            sb.append("\n💸 <b>Nimalarga chiqim bo'ldi</b> (oxirgi ")
              .append(rasxodLines.size()).append(" ta):\n")
              .append(String.join("\n", rasxodLines)).append("\n");

        List<Debt> oweTo = debtRepo.findByDebtorTypeAndDebtorIdAndStatus(
                OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, DebtStatus.OCHIQ);
        List<Debt> oweFrom = debtRepo.findByCreditorTypeAndCreditorIdAndStatus(
                OwnerType.BUXGALTERIYA, LedgerService.BUX_ID, DebtStatus.OCHIQ);
        sb.append("\n🧾 <b>Qarzlar registri bo'yicha:</b>\n");
        if (oweTo.isEmpty() && oweFrom.isEmpty())
            sb.append("Buxgalteriyaning hech kimga qarzi yo'q va hech kimdan haqi yo'q ✅");
        for (Debt d : oweTo)
            sb.append("🔴 KIMGA qarzdor: <b>")
              .append(esc(names.owner(d.getCreditorType(), d.getCreditorId())))
              .append("</b> — ").append(fmt(d.remain())).append(" so'm")
              .append(d.getReason() == null || d.getReason().isEmpty()
                      ? "" : " (" + esc(d.getReason()) + ")").append("\n");
        for (Debt d : oweFrom)
            sb.append("🟢 KIMDAN haqdor: <b>")
              .append(esc(names.owner(d.getDebtorType(), d.getDebtorId())))
              .append("</b> — ").append(fmt(d.remain())).append(" so'm")
              .append(d.getReason() == null || d.getReason().isEmpty()
                      ? "" : " (" + esc(d.getReason()) + ")").append("\n");

        sender.send(chatId, sb.toString());
    }

    /** «⬅️ Orqaga» — bir pog'ona yuqoriga. */
    private void navBack(AppUser u, Session s, String nav, long chatId) {
        String lvl = nav.split(":")[0];
        switch (lvl) {
            case "panel" -> {
                s.data.remove("nav");
                sender.send(chatId, "🏠 Bosh menyu", Keyboards.buxMenu(true));
            }
            case "otdel", "sozlash", "stat" -> navTo(s, "panel", chatId,
                    "👑 <b>АДМИН ПАНЕЛ</b>\n\nBo'limni tanlang:",
                    List.of("🏬 Отдел", "⚙️ Настройка", "📈 Статистика"));
            case "kassa" -> navTo(s, "otdel", chatId,
                    "🏬 <b>Отдел</b>\n\nKassani tanlang:", kassaLabels());
            case "davr" -> {
                long id = idOf(nav);
                navTo(s, "kassa:" + id, chatId,
                        "🏪 <b>" + esc(names.owner(OwnerType.KASSA, id)) + "</b>\n\nBo'limni tanlang:",
                        List.of("💰 Бугунги тушум", "💸 Расход", "📆 Давр танлаш"));
            }
            case "sozkassa", "sozuser" -> navTo(s, "sozlash", chatId, "⚙️ <b>Настройка</b>",
                    List.of("🏪 Касса", "👥 Фойдаланувчилар", "💼 Бошланғич қолдиқ"));
            case "roluser", "rolpick" -> navTo(s, "sozuser", chatId,
                    "👥 <b>Фойдаланувчилар</b>", SOZUSER_MENU);
            case "kassadel", "kassadelc" -> navTo(s, "sozkassa", chatId, "🏪 <b>Касса</b>",
                    List.of("➕ Касса қўшиш", "🚫 Касса ўчириш"));
            case "saldo", "svod" -> navTo(s, "stat", chatId, "📈 <b>Статистика</b>", STAT_MENU);
            case "svoddavr", "svodotd" -> navTo(s, "svod", chatId,
                    "📊 <b>Свод</b>\n\nExcel turini tanlang:",
                    List.of("📗 Умумий Excel", "📘 Даврий Excel", "📙 Отдел Excel"));
            case "svodotdd" -> navTo(s, "svodotd", chatId,
                    "📙 <b>Отдел Excel</b>\n\nKassani tanlang:", kassaLabels());
            default -> {
                s.data.remove("nav");
                sender.send(chatId, "🏠 Bosh menyu", Keyboards.buxMenu(true));
            }
        }
    }

    /* ==================================================================
     * 👑 АДМИН ПАНЕЛ (sxema bo'yicha):
     *   АДМИН ПАНЕЛ -> Отдел | Настройка | Статистика
     *   Отдел -> Касса N -> Бугунги тушум (Касса/Click) | Расход | Давр танлаш
     *   Настройка -> Касса (қўшиш/ўчириш) | Фойдаланувчилар (қўшиш/ўчириш)
     *   Статистика -> Карзлар реестр | История | Фойдаланувчилар | Салдо | Свод
     *   Салдо -> Касса N (Click/Касса) ; Свод -> Умумий/Даврий/Отдел Excel
     * ================================================================== */

    private void panel(AppUser u, Session s, String arg, long chatId, int msgId) {
        String[] a = arg.split(":");
        // Pul ko'rsatadigan sahifalar ochilganda avval MoySklad'dan yangilanadi
        switch (a[0]) {
            case "kt", "kr", "kpp", "sdk" -> syncService.syncIfStale(45);
            default -> { }
        }
        switch (a[0]) {
            case "main" -> panelMain(chatId, msgId);
            case "otd"  -> otdel(chatId, msgId);
            case "k"    -> kassaMenu(Long.parseLong(a[1]), chatId, msgId);
            case "kt"   -> kassaTushum(Long.parseLong(a[1]), chatId, msgId);
            case "kr"   -> kassaRasxod(Long.parseLong(a[1]), chatId, msgId);
            case "kd"   -> kassaDavr(Long.parseLong(a[1]), chatId, msgId);
            case "kpp"  -> kassaPeriodStats(Long.parseLong(a[1]), a[2], chatId, msgId);
            case "set"  -> settingsMenu(chatId, msgId);
            case "sk"   -> setKassa(chatId, msgId);
            case "skd"  -> kassaDeleteList(chatId, msgId);
            case "skx"  -> kassaDeleteConfirm(Long.parseLong(a[1]), chatId, msgId);
            case "sky"  -> kassaDeactivate(Long.parseLong(a[1]), chatId, msgId);
            case "sunew" -> { s.reset(); auStart(s, chatId); }
            case "sknew" -> {
                s.reset(); s.state = Session.State.ADM_AK_NAME;
                sender.edit(chatId, msgId, "🏪 <b>Yangi kassa</b>\n\nKassa nomini kiriting:");
            }
            case "su"   -> setUsers(chatId, msgId);
            case "st"   -> statMenu(chatId, msgId);
            case "dbt"  -> bux.debtsRegistry(chatId);
            case "his"  -> bux.historyMenu(chatId);
            case "usr"  -> listUsers(u, chatId);
            case "sd"   -> saldoList(chatId, msgId);
            case "sdk"  -> saldoKassa(a[1], chatId, msgId);
            case "sv"   -> svodMenu(chatId, msgId);
            case "xe"   -> excelFlow(a, chatId, msgId);
        }
    }

    private InlineKeyboardButton bk(String data) { return btn("⬅️ Orqaga", data); }

    private void show(long chatId, int msgId, String text,
                      List<List<InlineKeyboardButton>> rows) {
        if (msgId > 0) sender.edit(chatId, msgId, text, inline(rows));
        else sender.send(chatId, text, null);   // menu-rejimda inline tugmalarsiz
    }

    /* ---------- daraja 1 ---------- */

    private void panelMain(long chatId, int msgId) {
        show(chatId, msgId, "👑 <b>АДМИН ПАНЕЛ</b>\n\nBo'limni tanlang:", List.of(
                irow(btn("🏬 Отдел", "a:p:otd")),
                irow(btn("⚙️ Настройка", "a:p:set")),
                irow(btn("📈 Статистика", "a:p:st"))));
    }

    /* ---------- 🏬 ОТДЕЛ ---------- */

    private void otdel(long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn("🏪 " + k.getName(), "a:p:k:" + k.getId())));
        rows.add(irow(bk("a:p:main")));
        show(chatId, msgId, "🏬 <b>Отдел</b>\n\nKassani tanlang:", rows);
    }

    private void kassaMenu(long id, long chatId, int msgId) {
        String name = names.owner(OwnerType.KASSA, id);
        show(chatId, msgId, "🏪 <b>" + esc(name) + "</b>\n\nBo'limni tanlang:", List.of(
                irow(btn("💰 Бугунги тушум", "a:p:kt:" + id)),
                irow(btn("💸 Расход", "a:p:kr:" + id)),
                irow(btn("📆 Давр танлаш", "a:p:kd:" + id)),
                irow(bk("a:p:otd"))));
    }

    /** Бугунги тушум: Касса (naqd) va Click bo'lib ko'rsatiladi. */
    private void kassaTushum(long id, long chatId, int msgId) {
        DayRecord d = dayRepo.findByKassaIdAndDate(id, ledger.today()).orElse(null);
        long n = d == null ? 0 : d.getPrixodNaqd();
        long k = d == null ? 0 : d.getPrixodKlik();
        long t = d == null ? 0 : d.getPrixodTerminal();
        show(chatId, msgId, "💰 <b>Бугунги тушум</b> — "
                + esc(names.owner(OwnerType.KASSA, id)) + "\n📅 " + ledger.today().format(DF) + "\n\n"
                + "💵 Касса (нақд): <b>" + fmt(n) + "</b> so'm\n"
                + "📲 Click: <b>" + fmt(k) + "</b> so'm\n"
                + "💳 Terminal: <b>" + fmt(t) + "</b> so'm\n"
                + "➕ <b>Жами: " + fmt(n + k + t) + "</b> so'm",
                List.of(irow(bk("a:p:k:" + id))));
    }

    /** Расход: bugungi chiqimlar ro'yxati. */
    private void kassaRasxod(long id, long chatId, int msgId) {
        DayRecord d = dayRepo.findByKassaIdAndDate(id, ledger.today()).orElse(null);
        long rn = d == null ? 0 : d.getRasxodNaqd();
        long rk = d == null ? 0 : d.getRasxodKlik();
        StringBuilder sb = new StringBuilder("💸 <b>Расход</b> — "
                + esc(names.owner(OwnerType.KASSA, id)) + "\n📅 " + ledger.today().format(DF) + "\n\n"
                + "💵 Нақд: <b>" + fmt(rn) + "</b> · 📲 Click: <b>" + fmt(rk) + "</b>\n"
                + "➕ <b>Жами: " + fmt(rn + rk) + "</b> so'm\n");
        int shown = 0;
        for (Operation o : opRepo.byPeriod(ledger.today(), ledger.today())) {
            if (o.getType() != OpType.RASXOD) continue;
            if (o.getFromOwnerType() != OwnerType.KASSA || !Long.valueOf(id).equals(o.getFromOwnerId())) continue;
            if (shown++ >= 15) break;
            sb.append("\n• ").append(fmt(o.getAmount())).append(" so'm")
              .append(o.getComment() == null || o.getComment().isEmpty()
                      ? "" : " — " + esc(o.getComment()));
        }
        if (shown == 0) sb.append("\nBugun rasxod yo'q");
        show(chatId, msgId, sb.toString(), List.of(irow(bk("a:p:k:" + id))));
    }

    private void kassaDavr(long id, long chatId, int msgId) {
        show(chatId, msgId, "📆 <b>Давр танлаш</b> — "
                + esc(names.owner(OwnerType.KASSA, id)), List.of(
                irow(btn("Bugun", "a:p:kpp:" + id + ":t"), btn("Kecha", "a:p:kpp:" + id + ":y")),
                irow(btn("7 kun", "a:p:kpp:" + id + ":7"), btn("30 kun", "a:p:kpp:" + id + ":30"),
                     btn("Shu oy", "a:p:kpp:" + id + ":m")),
                irow(bk("a:p:k:" + id))));
    }

    private void kassaPeriodStats(long id, String code, long chatId, int msgId) {
        java.time.LocalDate[] p = periodOf(code);
        long kn = 0, kk = 0, rn = 0, rk = 0;
        for (Operation o : opRepo.byPeriod(p[0], p[1])) {
            boolean in = o.getToOwnerType() == OwnerType.KASSA && Long.valueOf(id).equals(o.getToOwnerId());
            boolean out = o.getFromOwnerType() == OwnerType.KASSA && Long.valueOf(id).equals(o.getFromOwnerId());
            if (o.getType() == OpType.PRIXOD && in) {
                if (o.getMoneyType() == MoneyType.KLIK) kk += o.getAmount(); else kn += o.getAmount();
            }
            if (o.getType() == OpType.RASXOD && out) {
                if (o.getMoneyType() == MoneyType.KLIK) rk += o.getAmount(); else rn += o.getAmount();
            }
        }
        show(chatId, msgId, "📆 <b>" + periodLabel(code) + "</b> — "
                + esc(names.owner(OwnerType.KASSA, id)) + "\n\n"
                + "🟢 Тушум: 💵 <b>" + fmt(kn) + "</b> · 📲 <b>" + fmt(kk) + "</b>\n"
                + "🔴 Расход: 💵 <b>" + fmt(rn) + "</b> · 📲 <b>" + fmt(rk) + "</b>\n"
                + "➕ <b>Фарқ: " + fmt(kn + kk - rn - rk) + "</b> so'm",
                List.of(
                    irow(btn("Bugun", "a:p:kpp:" + id + ":t"), btn("7 kun", "a:p:kpp:" + id + ":7"),
                         btn("30 kun", "a:p:kpp:" + id + ":30"), btn("Shu oy", "a:p:kpp:" + id + ":m")),
                    irow(bk("a:p:k:" + id))));
    }

    /* ---------- ⚙️ НАСТРОЙКА ---------- */

    private void settingsMenu(long chatId, int msgId) {
        show(chatId, msgId, "⚙️ <b>Настройка</b>", List.of(
                irow(btn("🏪 Касса", "a:p:sk")),
                irow(btn("👥 Фойдаланувчилар", "a:p:su")),
                irow(bk("a:p:main"))));
    }

    private void setKassa(long chatId, int msgId) {
        show(chatId, msgId, "🏪 <b>Касса</b>", List.of(
                irow(btn("➕ Касса қўшиш", "a:p:sknew")),
                irow(btn("🚫 Касса ўчириш", "a:p:skd")),
                irow(bk("a:p:set"))));
    }

    private void kassaDeleteList(long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn("🚫 " + k.getName(), "a:p:skx:" + k.getId())));
        rows.add(irow(bk("a:p:sk")));
        show(chatId, msgId, "🚫 <b>Касса ўчириш</b>\n\nQaysi kassani o'chirasiz?", rows);
    }

    private void kassaDeleteConfirm(long id, long chatId, int msgId) {
        show(chatId, msgId, "⚠️ <b>" + esc(names.owner(OwnerType.KASSA, id))
                + "</b> kassasi o'chirilsinmi?\n\n"
                + "Kassa faolsizlanadi — tarix saqlanadi, yangi operatsiyalar to'xtaydi.", List.of(
                irow(btn("✅ Ha, o'chirilsin", "a:p:sky:" + id), btn("❌ Yo'q", "a:p:skd"))));
    }

    private void kassaDeactivate(long id, long chatId, int msgId) {
        kassaRepo.findById(id).ifPresent(k -> { k.setActive(false); kassaRepo.save(k); });
        show(chatId, msgId, "🚫 Kassa faolsizlantirildi", List.of(irow(bk("a:p:sk"))));
    }

    private void setUsers(long chatId, int msgId) {
        show(chatId, msgId, "👥 <b>Фойдаланувчилар</b>", List.of(
                irow(btn("➕ Фойдаланувчи қўшиш", "a:p:sunew")),
                irow(btn("🚫 Фойдаланувчини ўчириш", "a:p:usr")),
                irow(bk("a:p:set"))));
    }

    /* ---------- 📈 СТАТИСТИКА ---------- */

    private void statMenu(long chatId, int msgId) {
        show(chatId, msgId, "📈 <b>Статистика</b>", List.of(
                irow(btn("🧾 Карзлар реестр", "a:p:dbt"), btn("📜 История", "a:p:his")),
                irow(btn("👥 Фойдаланувчилар умумий", "a:p:usr")),
                irow(btn("💼 Салдо", "a:p:sd"), btn("📊 Свод", "a:p:sv")),
                irow(bk("a:p:main"))));
    }

    private void saldoList(long chatId, int msgId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn("🏪 " + k.getName(), "a:p:sdk:" + k.getId())));
        rows.add(irow(btn("🏦 Buxgalteriya", "a:p:sdk:B")));
        rows.add(irow(bk("a:p:st")));
        show(chatId, msgId, "💼 <b>Салдо</b>\n\nKassani tanlang:", rows);
    }

    /** Салдо -> Касса: Click va Касса (naqd) balanslari. */
    private void saldoKassa(String who, long chatId, int msgId) {
        OwnerType ot = who.equals("B") ? OwnerType.BUXGALTERIYA : OwnerType.KASSA;
        Long id = who.equals("B") ? LedgerService.BUX_ID : Long.parseLong(who);
        var n = ledger.view(ot, id, MoneyType.NAQD);
        var k = ledger.view(ot, id, MoneyType.KLIK);
        String name = ot == OwnerType.BUXGALTERIYA ? "Buxgalteriya" : names.owner(ot, id);
        show(chatId, msgId, "💼 <b>Салдо</b> — " + esc(name) + "\n\n"
                + "💵 Касса (нақд): <b>" + fmt(n.getAmount()) + "</b> so'm"
                + (n.getReserved() > 0 ? " (band " + fmt(n.getReserved()) + ")" : "") + "\n"
                + "📲 Click: <b>" + fmt(k.getAmount()) + "</b> so'm"
                + (k.getReserved() > 0 ? " (band " + fmt(k.getReserved()) + ")" : "") + "\n"
                + "➕ <b>Жами: " + fmt(n.getAmount() + k.getAmount()) + "</b> so'm",
                List.of(irow(bk("a:p:sd"))));
    }

    /* ---------- 📊 СВОД (Excel) ---------- */

    private void svodMenu(long chatId, int msgId) {
        show(chatId, msgId, "📊 <b>Свод</b>\n\nExcel turini tanlang:", List.of(
                irow(btn("📗 Умумий Excel (шу ой)", "a:p:xe:all")),
                irow(btn("📘 Даврий Excel", "a:p:xe:per")),
                irow(btn("📙 Отдел Excel", "a:p:xe:otd")),
                irow(bk("a:p:st"))));
    }

    /** a = [xe, tur, ...]: all | per | perc:<code> | otd | otdk:<id> | otdp:<id>:<code> */
    private void excelFlow(String[] a, long chatId, int msgId) {
        switch (a[1]) {
            case "all" -> genExcel(chatId, msgId, "m", null);
            case "per" -> show(chatId, msgId, "📘 <b>Даврий Excel</b>\n\nDavrni tanlang:", List.of(
                    irow(btn("Bugun", "a:p:xe:perc:t"), btn("Kecha", "a:p:xe:perc:y")),
                    irow(btn("7 kun", "a:p:xe:perc:7"), btn("30 kun", "a:p:xe:perc:30"),
                         btn("Shu oy", "a:p:xe:perc:m")),
                    irow(bk("a:p:sv"))));
            case "perc" -> genExcel(chatId, msgId, a[2], null);
            case "otd" -> {
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
                    rows.add(irow(btn("🏪 " + k.getName(), "a:p:xe:otdk:" + k.getId())));
                rows.add(irow(bk("a:p:sv")));
                show(chatId, msgId, "📙 <b>Отдел Excel</b>\n\nKassani tanlang:", rows);
            }
            case "otdk" -> show(chatId, msgId, "📙 <b>Отдел Excel</b> — "
                    + esc(names.owner(OwnerType.KASSA, Long.parseLong(a[2])))
                    + "\n\nDavrni tanlang:", List.of(
                    irow(btn("Bugun", "a:p:xe:otdp:" + a[2] + ":t"),
                         btn("7 kun", "a:p:xe:otdp:" + a[2] + ":7")),
                    irow(btn("30 kun", "a:p:xe:otdp:" + a[2] + ":30"),
                         btn("Shu oy", "a:p:xe:otdp:" + a[2] + ":m")),
                    irow(bk("a:p:xe:otd"))));
            case "otdp" -> genExcel(chatId, msgId, a[3], Long.parseLong(a[2]));
        }
    }

    private void genExcel(long chatId, int msgId, String code, Long kassaId) {
        java.time.LocalDate[] p = periodOf(code);
        Kassa only = kassaId == null ? null : kassaRepo.findById(kassaId).orElse(null);
        String label = (only == null ? "Умумий" : only.getName()) + " · " + periodLabel(code);
        sender.edit(chatId, msgId, "⏳ Excel tayyorlanmoqda: <b>" + esc(label)
                + "</b>\nMoySklad so'ralmoqda, biroz kuting…");
        Kassa fOnly = only;
        new Thread(() -> {
            try {
                byte[] xlsx = excelReport.build(p[0], p[1], fOnly);
                sender.sendDocument(chatId, xlsx,
                        "hisobot_" + (fOnly == null ? "umumiy" : "kassa" + fOnly.getId())
                                + "_" + p[0] + "_" + p[1] + ".xlsx",
                        "📊 Excel: <b>" + esc(label) + "</b>");
            } catch (Exception e) {
                sender.send(chatId, "⚠️ Excel xatosi: " + esc(e.getMessage()));
            }
        }).start();
    }

    /* ---------- 💰 БУГУНГИ ТУШУМ (barcha kassalar) ---------- */

    private void tushumAll(long chatId) {
        syncService.syncIfStale(45);   // so'ralganda oxirgi ma'lumot kelsin
        StringBuilder sb = new StringBuilder("💰 <b>БУГУНГИ ТУШУМ</b>\n📅 "
                + ledger.today().format(DF) + "\n");
        long tn = 0, tk = 0, tt = 0;
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc()) {
            DayRecord d = dayRepo.findByKassaIdAndDate(k.getId(), ledger.today()).orElse(null);
            long n = d == null ? 0 : d.getPrixodNaqd();
            long kl = d == null ? 0 : d.getPrixodKlik();
            long t = d == null ? 0 : d.getPrixodTerminal();
            tn += n; tk += kl; tt += t;
            sb.append("\n<b>").append(esc(k.getName())).append("</b> — ")
              .append(fmt(n + kl + t)).append(" so'm\n")
              .append("  💵 ").append(fmt(n)).append(" · 📲 ").append(fmt(kl))
              .append(" · 💳 ").append(fmt(t)).append("\n");
        }
        sb.append("\n➕ <b>ЖАМИ: ").append(fmt(tn + tk + tt)).append("</b> so'm")
          .append("\n  💵 Нақд: ").append(fmt(tn))
          .append(" · 📲 Click: ").append(fmt(tk))
          .append(" · 💳 Terminal: ").append(fmt(tt));
        sender.send(chatId, sb.toString());
    }

    /* ---------- davr yordamchilari ---------- */

    private java.time.LocalDate[] periodOf(String code) {
        java.time.LocalDate t = ledger.today();
        return switch (code) {
            case "t" -> new java.time.LocalDate[]{t, t};
            case "y" -> new java.time.LocalDate[]{t.minusDays(1), t.minusDays(1)};
            case "7" -> new java.time.LocalDate[]{t.minusDays(6), t};
            case "30" -> new java.time.LocalDate[]{t.minusDays(29), t};
            default -> new java.time.LocalDate[]{t.withDayOfMonth(1), t};
        };
    }

    private String periodLabel(String code) {
        java.time.LocalDate[] p = periodOf(code);
        return p[0].equals(p[1]) ? p[0].format(DF) : p[0].format(DF) + " — " + p[1].format(DF);
    }

    /* ==================== 👥 FOYDALANUVCHI QO'SHISH ==================== */

    /** Botga yozgan (hali qo'shilmagan) odamlar ro'yxatini ko'rsatadi — tanlash oson. */
    private void auStart(Session s, long chatId) {
        s.reset();
        List<uz.kassa.domain.Guest> guests = guestRepo.findAllByOrderByLastSeenDesc().stream()
                .filter(g -> userRepo.findByTelegramId(g.getTelegramId()).isEmpty())
                .limit(10).toList();

        if (guests.isEmpty()) {
            s.state = Session.State.ADM_AU_TGID;
            sender.send(chatId, "👥 <b>Yangi foydalanuvchi</b>\n\n"
                    + "Hozircha botga yozgan yangi odam yo'q.\n"
                    + "Telegram ID sini qo'lda kiriting.\n"
                    + "<i>Foydalanuvchi botga /start yozsa, ro'yxatda avtomatik chiqadi.</i>",
                    cancelOnly());
            return;
        }

        s.state = Session.State.ADM_AU_PICK;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (uz.kassa.domain.Guest g : guests) {
            String label = (g.getName() == null || g.getName().isBlank()
                        ? String.valueOf(g.getTelegramId()) : g.getName())
                    + (g.getUsername() == null ? "" : " (@" + g.getUsername() + ")");
            if (label.length() > 40) label = label.substring(0, 40) + "…";
            rows.add(irow(btn("👤 " + label, "a:gu:" + g.getTelegramId())));
        }
        rows.add(irow(btn("✍️ ID qo'lda kiritish", "a:gu:m")));
        rows.add(irow(btn("❌ Bekor", "cx")));
        sender.send(chatId, "👥 <b>Yangi foydalanuvchi</b>\n\n"
                + "Botga yozgan odamlar — birini tanlang:", inline(rows));
    }

    private void auPick(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_AU_PICK) return;
        if (arg.equals("m")) {
            s.state = Session.State.ADM_AU_TGID;
            sender.edit(chatId, msgId, "Telegram ID sini kiriting.\n"
                    + "<i>Foydalanuvchi botga /start yozsa, bot unga ID sini ko'rsatadi.</i>");
            return;
        }
        long tgId;
        try { tgId = Long.parseLong(arg); } catch (NumberFormatException e) { return; }
        if (userRepo.findByTelegramId(tgId).isPresent()) {
            s.reset();
            sender.edit(chatId, msgId, "⚠️ Bu foydalanuvchi allaqachon tizimda mavjud");
            return;
        }
        s.data.put("tgid", tgId);
        String suggested = guestRepo.findById(tgId)
                .map(uz.kassa.domain.Guest::getName).orElse(null);
        s.state = Session.State.ADM_AU_NAME;
        if (suggested != null && !suggested.isBlank()) {
            s.data.put("suggName", suggested);
            sender.edit(chatId, msgId, "Tanlandi: <code>" + tgId + "</code>\n\n"
                    + "Ism-familiyasini kiriting, yoki «<b>-</b>» yuboring — "
                    + "<b>" + esc(suggested) + "</b> deb yoziladi:");
        } else {
            sender.edit(chatId, msgId, "Tanlandi: <code>" + tgId + "</code>\n\n"
                    + "Ism-familiyasini kiriting:");
        }
    }

    private void auTgId(Session s, String text, long chatId) {
        long tgId = parseAmount(text);
        if (tgId <= 0) { sender.send(chatId, "⚠️ Telegram ID — musbat raqam. Qaytadan kiriting:"); return; }
        if (userRepo.findByTelegramId(tgId).isPresent()) {
            sender.send(chatId, "⚠️ Bu ID bilan foydalanuvchi allaqachon mavjud");
            s.reset();
            return;
        }
        s.data.put("tgid", tgId);
        s.state = Session.State.ADM_AU_NAME;
        sender.send(chatId, "Ism-familiyasini kiriting:");
    }

    private void auName(Session s, String text, long chatId) {
        String sugg = s.getStr("suggName");
        s.data.put("name", text.equals("-") && sugg != null ? sugg : text);
        s.state = Session.State.ADM_AU_ROLE;
        sender.send(chatId, "Rolini tanlang:", inline(List.of(
                irow(btn("👤 Kassir", "a:rl:K")),
                irow(btn("🧮 Buxgalter", "a:rl:B"), btn("👑 SuperAdmin", "a:rl:S")),
                irow(btn("❌ Bekor", "cx")))));
    }

    private void auRole(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_AU_ROLE) return;
        switch (arg) {
            case "K" -> {
                List<Kassa> list = kassaRepo.findByActiveTrueOrderByIdAsc();
                if (list.isEmpty()) {
                    s.reset();
                    sender.edit(chatId, msgId, "⚠️ Avval kassa qo'shing (🏪 Kassa qo'shish)");
                    return;
                }
                s.data.put("role", Role.KASSIR);
                s.state = Session.State.ADM_AU_KASSA;
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                for (Kassa k : list) rows.add(irow(btn("🏪 " + k.getName(), "a:ks:" + k.getId())));
                rows.add(irow(btn("❌ Bekor", "cx")));
                sender.edit(chatId, msgId, "Qaysi kassaga biriktiriladi?", inline(rows));
            }
            case "B" -> saveUser(s, Role.BUXGALTER, null, chatId, msgId);
            case "S" -> saveUser(s, Role.SUPERADMIN, null, chatId, msgId);
        }
    }

    private void auKassa(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_AU_KASSA) return;
        saveUser(s, Role.KASSIR, Long.parseLong(arg), chatId, msgId);
    }

    private void saveUser(Session s, Role role, Long kassaId, long chatId, int msgId) {
        long tgId = s.getLong("tgid");
        String name = s.getStr("name");
        s.reset();
        userRepo.save(AppUser.builder()
                .telegramId(tgId).fullName(name).role(role).kassaId(kassaId).active(true)
                .build());
        guestRepo.deleteById(tgId);   // ro'yxatga olindi — mehmonlar ro'yxatidan chiqadi
        String where = kassaId == null ? "" : "\nKassa: " + esc(names.owner(OwnerType.KASSA, kassaId));
        sender.edit(chatId, msgId, "✅ Foydalanuvchi qo'shildi:\n<b>" + esc(name) + "</b> ("
                + role + ")" + where + "\nTelegram ID: <code>" + tgId + "</code>\n\n"
                + "Endi u botga <b>/start</b> yozsa — menyusi ochiladi.");
    }

    /* ==================== 🏪 KASSA QO'SHISH ==================== */

    private void akName(Session s, String text, long chatId) {
        s.data.put("kassaName", text);
        s.state = Session.State.ADM_AK_MSID;
        sender.send(chatId, "MoySklad savdo nuqtasi ID sini kiriting (UUID),\n"
                + "yoki hozircha yo'q bo'lsa «-» yuboring:");
    }

    private void akFinish(Session s, String text, long chatId) {
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

    private void akGroup(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_AK_GROUP) return;
        createKassa(s, arg.equals("-") ? null : arg, chatId, msgId);
    }

    private void createKassa(Session s, String groupId, long chatId, Integer msgId) {
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

    /* ==================== 💼 BOSHLANG'ICH QOLDIQ ==================== */

    private void ibStart(Session s, long chatId) {
        s.reset(); s.state = Session.State.ADM_IB_OWNER;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(btn("🏪 " + k.getName(), "a:ib:K" + k.getId())));
        rows.add(irow(btn("🏦 Buxgalteriya", "a:ib:B")));
        rows.add(irow(btn("❌ Bekor", "cx")));
        sender.send(chatId, "💼 <b>Boshlang'ich qoldiq</b>\n\nKimga kiritiladi?", inline(rows));
    }

    private void ibOwner(Session s, String arg, long chatId, int msgId) {
        if (s.state != Session.State.ADM_IB_OWNER) return;
        if (arg.equals("B")) {
            s.data.put("obT", OwnerType.BUXGALTERIYA);
            s.data.put("obId", LedgerService.BUX_ID);
        } else {
            s.data.put("obT", OwnerType.KASSA);
            s.data.put("obId", Long.parseLong(arg.substring(1)));
        }
        s.state = Session.State.ADM_IB_NAQD;
        sender.edit(chatId, msgId, "Tanlandi: <b>"
                + esc(names.owner((OwnerType) s.data.get("obT"), s.getLong("obId")))
                + "</b>\n\n💵 <b>NAQD</b> boshlang'ich qoldiqni kiriting (so'm, 0 mumkin):");
    }

    private void ibNaqd(Session s, String text, long chatId) {
        long v = text.equals("0") ? 0 : parseAmount(text);
        if (v < 0) { sender.send(chatId, "⚠️ 0 yoki musbat summa kiriting"); return; }
        s.data.put("ibNaqd", v);
        s.state = Session.State.ADM_IB_KLIK;
        sender.send(chatId, "📲 <b>CLICK</b> boshlang'ich qoldiqni kiriting (so'm, 0 mumkin):");
    }

    private void ibFinish(AppUser u, Session s, String text, long chatId) {
        long klik = text.equals("0") ? 0 : parseAmount(text);
        if (klik < 0) { sender.send(chatId, "⚠️ 0 yoki musbat summa kiriting"); return; }
        OwnerType ot = (OwnerType) s.data.get("obT");
        long oid = s.getLong("obId");
        long naqd = s.getLong("ibNaqd");
        s.reset();

        if (naqd == 0 && klik == 0) {
            sender.send(chatId, "Ikkala summa ham 0 — hech narsa yozilmadi.");
            return;
        }
        if (naqd > 0) ledger.postAdjustment(OpType.BOSHLANGICH, ot, oid, MoneyType.NAQD,
                naqd, "Boshlang'ich qoldiq", u.getId());
        if (klik > 0) ledger.postAdjustment(OpType.BOSHLANGICH, ot, oid, MoneyType.KLIK,
                klik, "Boshlang'ich qoldiq", u.getId());

        sender.send(chatId, "✅ Boshlang'ich qoldiq kiritildi — <b>"
                + esc(names.owner(ot, oid)) + "</b>\n"
                + "💵 Naqd: <b>" + fmt(naqd) + "</b> so'm\n"
                + "📲 Click: <b>" + fmt(klik) + "</b> so'm");
    }

    /* ==================== 👤 FOYDALANUVCHILAR ==================== */

    private void listUsers(AppUser me, long chatId) {
        List<AppUser> users = userRepo.findByActiveTrueOrderByRoleAscIdAsc();
        StringBuilder sb = new StringBuilder("👤 <b>Faol foydalanuvchilar</b>\n\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (AppUser x : users) {
            String where = x.getKassaId() == null ? "" :
                    " — " + names.owner(OwnerType.KASSA, x.getKassaId());
            sb.append("• <b>").append(esc(x.getFullName())).append("</b> (")
              .append(x.getRole()).append(esc(where)).append(") — <code>")
              .append(x.getTelegramId()).append("</code>\n");
            if (!x.getId().equals(me.getId()) && rows.size() < 12)
                rows.add(irow(btn("🚫 " + x.getFullName(), "a:ux:" + x.getId())));
        }
        sb.append("\nFaolsizlantirish uchun tugmani bosing:");
        sender.send(chatId, sb.toString(), rows.isEmpty() ? null : inline(rows));
    }

    private void deactivate(AppUser me, long userId, long chatId, int msgId) {
        AppUser x = userRepo.findById(userId).orElse(null);
        if (x == null || x.getId().equals(me.getId())) return;
        x.setActive(false);
        userRepo.save(x);
        sender.edit(chatId, msgId, "🚫 <b>" + esc(x.getFullName())
                + "</b> faolsizlantirildi. U endi botdan foydalana olmaydi.");
    }
}
