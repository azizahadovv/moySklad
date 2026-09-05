package uz.kassa.bot.handlers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.Sender;
import uz.kassa.bot.Session;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Kassa;
import uz.kassa.domain.Notify;
import uz.kassa.domain.Role;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.KassaRepo;
import uz.kassa.service.AuditService;
import uz.kassa.service.SettingsService;
import uz.kassa.service.notify.NotifyService;
import uz.kassa.service.notify.TemplateService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static uz.kassa.bot.Keyboards.*;
import static uz.kassa.bot.TextUtil.esc;

/**
 * 🔔 Билдиришномалар — SuperAdmin paneli: shablonli, jadvalli xabarlar ro'yxati.
 * Callback'lar «a:nf…» prefiksi bilan AdminHandler orqali keladi.
 */
@Component
@RequiredArgsConstructor
public class NotifyAdminHandler {

    private final Sender sender;
    private final NotifyService svc;
    private final AppUserRepo userRepo;
    private final KassaRepo kassaRepo;
    private final SettingsService settings;
    private final AuditService audit;
    private final NotifyPresetHandler presetH;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final String[] DAY_NAMES = {"Dush", "Sesh", "Chor", "Pay", "Juma", "Shan", "Yak"};

    /* ============================ MATN (state'lar) ============================ */

    public boolean onText(AppUser u, Session s, String text, long chatId) {
        Session.State st = s.state;
        s.state = Session.State.IDLE;
        String v = text.trim();
        switch (st) {
            case ADM_NF_NAME -> {
                if (v.equals("-")) { sender.send(chatId, "❌ Bekor qilindi."); menu(s, chatId, 0); return true; }
                if (v.length() > 80 || v.contains("\n")) {
                    // Nom o'rniga shablon matnining o'zi yuborilgan — nomni birinchi satrdan olib,
                    // matnni darhol shablon sifatida saqlaymiz (foydalanuvchini qayta yozdirmaymiz)
                    String first = v.split("\n", 2)[0].replaceAll("<[^>]+>", "").replaceAll("\\{[^}]*\\}", "")
                            .replaceAll("\\s+", " ").trim();
                    if (first.length() > 40) first = first.substring(0, 40).trim() + "…";
                    if (first.isBlank()) first = "Bildirishnoma";
                    if (v.length() > 3800) {
                        s.state = st;
                        sender.send(chatId, "⚠️ Matn juda uzun (3800 belgigacha). Qisqartirib qayta yuboring yoki «-»:");
                        return true;
                    }
                    Notify n = svc.save(Notify.builder().name(first).template(v).schedule("times:09:00").build());
                    audit.log(u.getId(), "NOTIFY_YARATILDI", "notify", n.getId(), u.getFullName() + " — " + first);
                    TemplateService.Result r = svc.preview(n, chatId);
                    StringBuilder sb = new StringBuilder("✅ <b>" + esc(first) + "</b> yaratildi, yuborgan matningiz "
                            + "shablon sifatida saqlandi. Hozirgi ko'rinishi:\n\n").append(r.text());
                    if (!r.unknown().isEmpty())
                        sb.append("\n\n⚠️ Noma'lum o'rinbosarlar (o'zgarishsiz qoladi): <code>")
                          .append(esc(String.join(" ", r.unknown()))).append("</code>");
                    sb.append("\n\nEndi ⏰ Jadval va 👥 Kimga ni belgilang.");
                    sender.send(chatId, sb.toString());
                    card(n.getId(), chatId, 0);
                    return true;
                }
                Notify n = svc.save(Notify.builder().name(v).template("").schedule("times:09:00").build());
                audit.log(u.getId(), "NOTIFY_YARATILDI", "notify", n.getId(), u.getFullName() + " — " + v);
                sender.send(chatId, "✅ <b>" + esc(v) + "</b> yaratildi. Endi shablon matnini yuboring "
                        + "(o'rinbosarlar ro'yxati: 📖 tugmasi).\n\nBekor qilish uchun «-»:");
                s.state = Session.State.ADM_NF_TPL;
                s.data.put("nfId", n.getId());
                return true;
            }
            case ADM_NF_TPL -> {
                Notify n = current(s);
                if (n == null) { menu(s, chatId, 0); return true; }
                if (v.equals("-")) { sender.send(chatId, "❌ Shablon o'zgartirilmadi."); card(n.getId(), chatId, 0); return true; }
                if (v.length() > 3800) {
                    s.state = st;
                    sender.send(chatId, "⚠️ Juda uzun (Telegram limiti 4096, shablon 3800 belgigacha). "
                            + "Qisqartirib qayta yuboring yoki «-» bilan bekor qiling:");
                    return true;
                }
                n.setTemplate(v);
                svc.save(n);
                audit.log(u.getId(), "NOTIFY_SHABLON", "notify", n.getId(), u.getFullName() + " shablonni o'zgartirdi");
                TemplateService.Result r = svc.preview(n, chatId);
                StringBuilder sb = new StringBuilder("✅ Shablon saqlandi. Hozirgi ko'rinishi:\n\n").append(r.text());
                if (!r.unknown().isEmpty())
                    sb.append("\n\n⚠️ Noma'lum o'rinbosarlar (o'zgarishsiz qoladi): <code>")
                      .append(esc(String.join(" ", r.unknown()))).append("</code>");
                sender.send(chatId, sb.toString());
                card(n.getId(), chatId, 0);
                return true;
            }
            case ADM_NF_TIMES -> {
                Notify n = current(s);
                if (n == null) { menu(s, chatId, 0); return true; }
                if (v.equals("-")) { scheduleMenu(n.getId(), chatId, 0); return true; }
                List<String> times = new ArrayList<>();
                for (String p : v.split("[,;\\n]")) {
                    if (p.isBlank()) continue;
                    String t = NotifyService.normTime(p);
                    if (t == null) {
                        s.state = st;
                        sender.send(chatId, "⚠️ «" + esc(p.trim()) + "» vaqtga o'xshamaydi. Masalan: "
                                + "<code>09:00, 13:00, 18:30</code>. Qayta yuboring yoki «-» bilan bekor qiling:");
                        return true;
                    }
                    times.add(t);
                }
                if (times.isEmpty()) { s.state = st; sender.send(chatId, "⚠️ Kamida bitta vaqt kiriting:"); return true; }
                n.setSchedule("times:" + String.join(",", times));
                svc.save(n);
                audit.log(u.getId(), "NOTIFY_JADVAL", "notify", n.getId(), u.getFullName() + " vaqtlar: " + times);
                sender.send(chatId, "✅ Vaqtlar saqlandi: <b>" + String.join(", ", times) + "</b>");
                scheduleMenu(n.getId(), chatId, 0);
                return true;
            }
            case ADM_NF_CHAT -> {
                Notify n = current(s);
                if (n == null) { menu(s, chatId, 0); return true; }
                if (v.equals("-")) { recipientsMenu(n.getId(), chatId, 0); return true; }
                long gid;
                try { gid = Long.parseLong(v); }
                catch (NumberFormatException e) {
                    s.state = st;
                    sender.send(chatId, "⚠️ Bu raqamga o'xshamaydi. Chat ID sini yuboring "
                            + "(masalan -1001234567890) yoki «-» bilan bekor qiling:");
                    return true;
                }
                String botStat = sender.botStatusInChat(gid);
                boolean ok = "administrator".equals(botStat) || "member".equals(botStat) || "creator".equals(botStat);
                if (!ok) {
                    s.state = st;
                    sender.send(chatId, "⚠️ Bu ID (<code>" + gid + "</code>) bilan chat topilmadi yoki bot u yerga "
                            + "qo'shilmagan. Avval botni guruh/kanalga qo'shing, so'ng ID ni qayta yuboring "
                            + "yoki «-» bilan bekor qiling:");
                    return true;
                }
                Set<String> set = n.recipientSet();
                set.add("group:" + gid);
                n.setRecipientSet(set);
                svc.save(n);
                audit.log(u.getId(), "NOTIFY_KIMGA", "notify", n.getId(), u.getFullName() + " chat qo'shdi: " + gid);
                sender.send(chatId, "✅ Chat qo'shildi.");
                recipientsMenu(n.getId(), chatId, 0);
                return true;
            }
            case ADM_NF_BTN -> {   // 🔘 menyu tugmasi matni
                Notify n = current(s);
                if (n == null) { menu(s, chatId, 0); return true; }
                if (v.equals("-")) { buttonMenu(n.getId(), chatId, 0); return true; }
                String problem = NotifyService.buttonLabelProblem(v);
                if (problem != null) {
                    s.state = st;
                    sender.send(chatId, "⚠️ Tugma matni yaroqsiz: " + esc(problem) + ". Boshqa matn yuboring yoki «-»:");
                    return true;
                }
                n.setButtonLabel(v);
                if (n.buttonRoleSet().isEmpty()) n.setButtonRoleSet(java.util.EnumSet.of(Role.SUPERADMIN));
                svc.save(n);
                audit.log(u.getId(), "NOTIFY_TUGMA", "notify", n.getId(), u.getFullName() + " tugma: " + v);
                sender.send(chatId, "✅ Tugma saqlandi: <b>" + esc(v) + "</b>. Endi qaysi rollarga ko'rinishini belgilang. "
                        + "Foydalanuvchi /start bosganda yoki menyu yangilanganda tugma paydo bo'ladi.");
                buttonMenu(n.getId(), chatId, 0);
                return true;
            }
            case ADM_NF_ONCE -> {
                Notify n = current(s);
                if (n == null) { menu(s, chatId, 0); return true; }
                if (v.equals("-")) { scheduleMenu(n.getId(), chatId, 0); return true; }
                LocalDateTime at = NotifyService.parseOnceText(v, java.time.ZoneId.of("Asia/Tashkent"));
                if (at == null) {
                    s.state = st;
                    sender.send(chatId, "⚠️ Tushunmadim. Masalan: <code>05.09.2026 14:30</code>, <code>bugun 18:00</code>, "
                            + "<code>ertaga 09:00</code>. Qayta yuboring yoki «-» bilan bekor qiling:");
                    return true;
                }
                if (!at.isAfter(LocalDateTime.now(java.time.ZoneId.of("Asia/Tashkent")))) {
                    s.state = st;
                    sender.send(chatId, "⚠️ Bu vaqt o'tib ketgan. Kelajakdagi vaqtni yuboring yoki «-»:");
                    return true;
                }
                n.setSchedule("once:" + at.withSecond(0).withNano(0));
                n.setActive(true);
                svc.save(n);
                audit.log(u.getId(), "NOTIFY_JADVAL", "notify", n.getId(), u.getFullName() + " bir marta: " + at);
                sender.send(chatId, "✅ Bir marta yuboriladi: <b>" + at.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                        + "</b>. Yuborilgach bildirishnoma o'zi o'chadi (⚪).");
                card(n.getId(), chatId, 0);
                return true;
            }
            case ADM_NF_DEL -> {
                if (v.equals("-")) { menu(s, chatId, 0); return true; }
                int min;
                try { min = Integer.parseInt(v); } catch (NumberFormatException e) { min = -1; }
                if (min < 0 || min > 1440) {
                    s.state = st;
                    sender.send(chatId, "⚠️ 0 dan 1440 gacha daqiqa kiriting (0 — o'chirilmasin) yoki «-»:");
                    return true;
                }
                settings.set(NotifyService.CONFIRM_DELETE_KEY, String.valueOf(min));
                audit.log(u.getId(), "NOTIFY_TASDIQ_OCHIRISH", "settings", null,
                        u.getFullName() + " tasdiq xabari o'chirish: " + min + " min");
                sender.send(chatId, min == 0 ? "✅ Tasdiq xabarlari endi o'chirilmaydi."
                        : "✅ Guruhdagi «қабул қилинди» tasdiq xabari endi <b>" + min + " daqiqadan</b> keyin o'chiriladi.");
                menu(s, chatId, 0);
                return true;
            }
            default -> { s.state = st; return false; }
        }
    }

    private Notify current(Session s) {
        Object id = s.data.get("nfId");
        if (!(id instanceof Number num)) return null;
        return svc.find(num.longValue()).orElse(null);
    }

    /* ============================ CALLBACK ============================ */

    /** cmd — «nf…» (prefiks «a:» siz), arg — qolgan qism. */
    public boolean onCallback(AppUser u, Session s, String cmd, String arg, long chatId, int msgId) {
        if (cmd.startsWith("nfp")) {   // 📚 Namunalar — alohida handler; yaratilsa kartasi ochiladi
            Long created = presetH.onCallback(u, cmd, arg, chatId, msgId);
            if (created != null) card(created, chatId, 0);
            return true;
        }
        String[] a = arg.isEmpty() ? new String[0] : arg.split(":");
        long id = a.length > 0 && a[0].matches("-?\\d+") ? Long.parseLong(a[0]) : 0;
        Notify n = id > 0 ? svc.find(id).orElse(null) : null;
        switch (cmd) {
            case "nfm" -> menu(s, chatId, msgId);
            case "nfh" -> help(a.length > 0 ? Integer.parseInt(a[0]) : 1, chatId, msgId);
            case "nfn" -> {
                s.state = Session.State.ADM_NF_NAME;
                sender.edit(chatId, msgId, "🔔 <b>Yangi bildirishnoma</b>\n\n1-qadam: qisqa <b>NOM</b> yuboring "
                        + "(masalan «Ertalabki savdo»). Shablon matni keyingi qadamda so'raladi.\n"
                        + "<i>To'g'ridan-to'g'ri shablon matnini yuborsangiz ham bo'ladi — nom birinchi "
                        + "satridan olinadi.</i>\n\nBekor qilish uchun «-»:");
            }
            case "nfd" -> {
                s.state = Session.State.ADM_NF_DEL;
                sender.edit(chatId, msgId, "🗑 <b>Tasdiq xabarini avto-o'chirish</b>\n\nGuruhda karta qoldig'i "
                        + "qabul qilingandagi «✅ … қабул қилинди» xabari necha daqiqadan keyin o'chirilsin?\n"
                        + "Hozir: <b>" + svc.confirmDeleteMin() + " min</b>. «✏️ Tuzatish» bosilsa taymer to'xtaydi, "
                        + "tuzatish tugagach qaytadan boshlanadi.\n\nDaqiqani yuboring (0 — o'chirilmasin) yoki «-»:");
            }
            default -> {
                if (n == null) { menu(s, chatId, msgId); return true; }
                switch (cmd) {
                    case "nfc" -> card(id, chatId, msgId);
                    case "nft" -> {
                        s.state = Session.State.ADM_NF_TPL;
                        s.data.put("nfId", id);
                        sender.edit(chatId, msgId, "✍️ <b>" + esc(n.getName()) + " — shablon</b>\n\n"
                                + (n.getTemplate().isBlank() ? "Hozir: <i>bo'sh</i>"
                                    : "Hozir:\n<code>" + esc(n.getTemplate()) + "</code>")
                                + "\n\nYangi matnni yuboring (HTML va {o'rinbosarlar} ishlaydi, 📖 yordam menyuda). "
                                + "Bekor qilish uchun «-»:");
                    }
                    case "nfs" -> scheduleMenu(id, chatId, msgId);
                    case "nfsi" -> {   // interval rejimi: har N soat
                        int every = Integer.parseInt(a[1]);
                        int from = n.isIntervalMode() ? n.schedInt("from", 9, 0, 23) : 9;
                        int to = n.isIntervalMode() ? n.schedInt("to", 21, 0, 23) : 21;
                        int off = n.isIntervalMode() ? n.schedInt("off", 0, -59, 59) : 0;
                        n.setSchedule("every:" + every + ";from:" + from + ";to:" + to + ";off:" + off);
                        svc.save(n);
                        audit.log(u.getId(), "NOTIFY_JADVAL", "notify", id, u.getFullName() + " " + n.getSchedule());
                        scheduleMenu(id, chatId, msgId);
                    }
                    case "nfsw" -> {   // oyna from:to
                        n.setSchedule("every:" + n.schedInt("every", 1, 1, 24) + ";from:" + a[1] + ";to:" + a[2]
                                + ";off:" + n.schedInt("off", 0, -59, 59));
                        svc.save(n);
                        audit.log(u.getId(), "NOTIFY_JADVAL", "notify", id, u.getFullName() + " " + n.getSchedule());
                        scheduleMenu(id, chatId, msgId);
                    }
                    case "nfso" -> {   // minut siljishi
                        n.setSchedule("every:" + n.schedInt("every", 1, 1, 24) + ";from:" + n.schedInt("from", 0, 0, 23)
                                + ";to:" + n.schedInt("to", 23, 0, 23) + ";off:" + a[1]);
                        svc.save(n);
                        scheduleMenu(id, chatId, msgId);
                    }
                    case "nfs1" -> {   // bir marta: sana + vaqt (matn)
                        s.state = Session.State.ADM_NF_ONCE;
                        s.data.put("nfId", id);
                        sender.edit(chatId, msgId, "📅 <b>Bir marta yuborish</b>\n\nSana va vaqtni yuboring, masalan "
                                + "<code>05.09.2026 14:30</code>, <code>bugun 18:00</code>, <code>ertaga 09:00</code>.\n"
                                + "Yuborilgach bildirishnoma o'zi o'chadi.\n\nBekor qilish uchun «-»:");
                    }
                    case "nfsv" -> {   // aniq vaqtlar (matn)
                        s.state = Session.State.ADM_NF_TIMES;
                        s.data.put("nfId", id);
                        sender.edit(chatId, msgId, "⏰ <b>Aniq vaqtlar</b>\n\nVergul bilan yuboring, masalan "
                                + "<code>09:00, 13:00, 18:30</code>\n"
                                + (n.isIntervalMode() ? "" : "Hozir: <b>" + String.join(", ", svc.times(n)) + "</b>\n")
                                + "\nBekor qilish uchun «-»:");
                    }
                    case "nfsd" -> {   // hafta kuni toggle
                        Set<Integer> days = n.weekdaySet();
                        if (a[1].equals("all")) days.clear();
                        else if (a[1].equals("work")) days = new java.util.TreeSet<>(Set.of(1, 2, 3, 4, 5));
                        else {
                            int d = Integer.parseInt(a[1]);
                            if (days.isEmpty()) for (int i = 1; i <= 7; i++) days.add(i);
                            if (days.contains(d)) days.remove(d); else days.add(d);
                            if (days.size() == 7) days.clear();
                        }
                        n.setWeekdaySet(days);
                        svc.save(n);
                        scheduleMenu(id, chatId, msgId);
                    }
                    case "nfr" -> recipientsMenu(id, chatId, msgId);
                    case "nfrt" -> {   // qabul qiluvchi toggle (rol:X / user:N / kassa:N / karta_masul / …)
                        String tok = arg.substring(arg.indexOf(':') + 1);
                        Set<String> set = n.recipientSet();
                        if (!set.remove(tok)) set.add(tok);
                        n.setRecipientSet(set);
                        svc.save(n);
                        audit.log(u.getId(), "NOTIFY_KIMGA", "notify", id, u.getFullName() + " → " + n.getRecipients());
                        if (tok.startsWith("user:")) usersPick(id, chatId, msgId);
                        else if (tok.startsWith("kassa:")) kassaPick(id, chatId, msgId);
                        else recipientsMenu(id, chatId, msgId);
                    }
                    case "nfru" -> usersPick(id, chatId, msgId);
                    case "nfrk" -> kassaPick(id, chatId, msgId);
                    case "nfrg" -> {
                        s.state = Session.State.ADM_NF_CHAT;
                        s.data.put("nfId", id);
                        sender.edit(chatId, msgId, "👥 <b>Guruh/kanal qo'shish</b>\n\nBotni o'sha chatga qo'shing "
                                + "(kanalda ADMIN shart), so'ng chat ID sini yuboring (masalan -1001234567890).\n"
                                + "Bekor qilish uchun «-»:");
                    }
                    case "nfb" -> buttonMenu(id, chatId, msgId);
                    case "nfbl" -> {   // tugma matni (matn)
                        s.state = Session.State.ADM_NF_BTN;
                        s.data.put("nfId", id);
                        sender.edit(chatId, msgId, "🔘 <b>Menyu tugmasi matni</b>\n\nQisqa matn yuboring (40 belgigacha), "
                                + "masalan <code>📈 Oylik savdo</code>. Mavjud menyu tugmalari bilan bir xil bo'lmasin.\n"
                                + (n.getButtonLabel().isBlank() ? "" : "Hozir: <b>" + esc(n.getButtonLabel()) + "</b>\n")
                                + "\nBekor qilish uchun «-»:");
                    }
                    case "nfbr" -> {   // rol toggle
                        Role r = Role.valueOf(a[1]);
                        Set<Role> set = n.buttonRoleSet();
                        if (!set.remove(r)) set.add(r);
                        n.setButtonRoleSet(set);
                        svc.save(n);
                        audit.log(u.getId(), "NOTIFY_TUGMA", "notify", id, u.getFullName() + " rollar: " + n.getButtonRoles());
                        buttonMenu(id, chatId, msgId);
                    }
                    case "nfbx" -> {   // tugmani olib tashlash
                        n.setButtonLabel("");
                        svc.save(n);
                        audit.log(u.getId(), "NOTIFY_TUGMA", "notify", id, u.getFullName() + " tugmani olib tashladi");
                        card(id, chatId, msgId);
                    }
                    case "nfa" -> {   // avto-o'chirish: 0 → 5 → 10 → 30 → 60 → 180 → 0
                        int[] cyc = {0, 5, 10, 30, 60, 180};
                        int cur = n.getAutoDeleteMin(), next = 0;
                        for (int i = 0; i < cyc.length; i++) if (cyc[i] == cur) { next = cyc[(i + 1) % cyc.length]; break; }
                        n.setAutoDeleteMin(next);
                        svc.save(n);
                        card(id, chatId, msgId);
                    }
                    case "nfx" -> {   // 🧪 test — o'zimga
                        TemplateService.Result r = svc.preview(n, chatId);
                        String txt = r.text().isBlank() ? "<i>(shablon bo'sh)</i>" : r.text();
                        if (!r.unknown().isEmpty())
                            txt += "\n\n⚠️ Noma'lum: <code>" + esc(String.join(" ", r.unknown())) + "</code>";
                        sender.send(chatId, "🧪 <b>Test (faqat sizga)</b>\n\n" + txt);
                        card(id, chatId, msgId);
                    }
                    case "nfy" -> {   // 🚀 hozir yuborish (barcha qabul qiluvchilarga)
                        String err = svc.send(n);
                        n.setLastError(err);
                        n.setLastSent(LocalDateTime.now().withSecond(0).withNano(0).toString());
                        svc.save(n);
                        audit.log(u.getId(), "NOTIFY_YUBORILDI", "notify", id, u.getFullName() + " qo'lda yubordi"
                                + (err == null ? "" : " (xato: " + err + ")"));
                        sender.send(chatId, err == null ? "✅ Yuborildi." : "⚠️ Qisman yuborildi: " + esc(err));
                        card(id, chatId, msgId);
                    }
                    case "nfo" -> {
                        n.setActive(!n.isActive());
                        svc.save(n);
                        audit.log(u.getId(), n.isActive() ? "NOTIFY_YOQILDI" : "NOTIFY_OCHIRILDI", "notify", id, u.getFullName());
                        card(id, chatId, msgId);
                    }
                    case "nfz" -> sender.edit(chatId, msgId, "🗑 <b>" + esc(n.getName()) + "</b> — o'chirilsinmi?",
                            inline(List.of(irow(btn("✅ Ha, o'chirilsin", "a:nfzz:" + id), btn("⬅️ Yo'q", "a:nfc:" + id)))));
                    case "nfzz" -> {
                        svc.delete(id);
                        audit.log(u.getId(), "NOTIFY_OCHIRILDI_BUTUNLAY", "notify", id, u.getFullName() + " — " + n.getName());
                        menu(s, chatId, msgId);
                    }
                    default -> { return false; }
                }
            }
        }
        return true;
    }

    /* ============================ OYNALAR ============================ */

    public void menu(Session s, long chatId, int msgId) {
        List<Notify> all = svc.all();
        StringBuilder sb = new StringBuilder("🔔 <b>Билдиришномалар</b>\n\n"
                + "Shablonli xabarlar — belgilangan vaqtlarda userlar, guruhlar va kanallarga yuboriladi. "
                + "Shablonlar Google Sheets «Shablon» varag'ida ham tahrirlanadi.\n\n");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (all.isEmpty()) sb.append("<i>Hali bildirishnoma yo'q.</i>\n");
        for (Notify n : all) {
            sb.append(n.isActive() ? "🟢 " : "⚪ ").append("<b>").append(esc(n.getName())).append("</b> — ")
              .append(svc.describeSchedule(n)).append("\n");
            rows.add(irow(btn((n.isActive() ? "🟢 " : "⚪ ") + n.getName(), "a:nfc:" + n.getId())));
        }
        sb.append("\n🗑 Tasdiq xabari («қабул қилинди») o'chirish: <b>")
          .append(svc.confirmDeleteMin() == 0 ? "o'chirilmaydi" : svc.confirmDeleteMin() + " min").append("</b>");
        rows.add(irow(btn("➕ Yangi", "a:nfn"), btn("📖 O'rinbosarlar", "a:nfh:1")));
        rows.add(irow(btn("📚 Namunalar (tayyor hisobotlar)", "a:nfp")));
        rows.add(irow(btn("🗑 Tasdiq xabari o'chirish vaqti", "a:nfd")));
        rows.add(irow(btn("⬅️ Orqaga", "a:p:set")));
        show(chatId, msgId, sb.toString(), rows);
    }

    private void card(long id, long chatId, int msgId) {
        Notify n = svc.find(id).orElse(null);
        if (n == null) { sender.send(chatId, "⚠️ Topilmadi"); return; }
        LocalDateTime next = n.isActive() ? svc.nextRun(n) : null;
        String tpl = n.getTemplate().isBlank() ? "<i>bo'sh — ✍️ Shablon tugmasi bilan kiriting</i>"
                : "<code>" + esc(n.getTemplate().length() > 600 ? n.getTemplate().substring(0, 600) + "…" : n.getTemplate()) + "</code>";
        String text = "🔔 <b>" + esc(n.getName()) + "</b>  " + (n.isActive() ? "🟢 faol" : "⚪ o'chirilgan") + "\n\n"
                + "⏰ Jadval: <b>" + svc.describeSchedule(n) + "</b>\n"
                + (next == null ? "" : "▶️ Keyingi: <b>" + next.format(DTF) + "</b>\n")
                + "👥 Kimga: " + svc.describeRecipients(n) + "\n"
                + "🗑 Avto-o'chirish: <b>" + (n.getAutoDeleteMin() == 0 ? "yo'q" : n.getAutoDeleteMin() + " min") + "</b>\n"
                + "🔘 Menyu tugmasi: " + (n.getButtonLabel().isBlank() ? "<i>yo'q</i>"
                    : "<b>" + esc(n.getButtonLabel()) + "</b> — " + esc(NotifyService.rolesText(n))) + "\n"
                + (n.getLastSent() == null ? "" : "📤 Oxirgi: " + esc(n.getLastSent().replace('T', ' ')) + "\n")
                + (n.getLastError() == null ? "" : "⚠️ Xato: " + esc(n.getLastError()) + "\n")
                + "\n✍️ Shablon:\n" + tpl;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("✍️ Shablon", "a:nft:" + id), btn("⏰ Jadval", "a:nfs:" + id)));
        rows.add(irow(btn("👥 Kimga", "a:nfr:" + id),
                btn("🗑 Avto-o'chirish: " + (n.getAutoDeleteMin() == 0 ? "yo'q" : n.getAutoDeleteMin() + " min"), "a:nfa:" + id)));
        rows.add(irow(btn("🔘 Menyu tugmasi" + (n.getButtonLabel().isBlank() ? "" : ": " + n.getButtonLabel()), "a:nfb:" + id)));
        rows.add(irow(btn("🧪 Test (o'zimga)", "a:nfx:" + id), btn("🚀 Hozir yuborish", "a:nfy:" + id)));
        rows.add(irow(btn(n.isActive() ? "⏸ O'chirib turish" : "▶️ Yoqish", "a:nfo:" + id), btn("🗑 O'chirish", "a:nfz:" + id)));
        rows.add(irow(btn("⬅️ Ro'yxat", "a:nfm")));
        show(chatId, msgId, text, rows);
    }

    private void scheduleMenu(long id, long chatId, int msgId) {
        Notify n = svc.find(id).orElse(null);
        if (n == null) return;
        boolean iv = n.isIntervalMode();
        int every = n.schedInt("every", 1, 1, 24), from = n.schedInt("from", 9, 0, 23),
                to = n.schedInt("to", 21, 0, 23), off = n.schedInt("off", 0, -59, 59);
        String text = "⏰ <b>" + esc(n.getName()) + " — jadval</b>\n\nHozir: <b>" + svc.describeSchedule(n) + "</b>\n\n"
                + "Rejimlar: <b>har N soat</b> (oyna va siljish bilan), <b>aniq vaqtlar</b> ro'yxati yoki "
                + "<b>bir marta</b> (sana + vaqt, yuborilgach o'zi o'chadi). "
                + "Pastda hafta kunlari: ✅ — yuboriladi, ▫️ — yuborilmaydi (bosib almashtiring).";
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn((iv ? "✅ " : "") + "Har N soat:", "a:nfs:" + id)));
        rows.add(irow(ib(iv && every == 1, "1", "a:nfsi:" + id + ":1"), ib(iv && every == 2, "2", "a:nfsi:" + id + ":2"),
                ib(iv && every == 3, "3", "a:nfsi:" + id + ":3"), ib(iv && every == 4, "4", "a:nfsi:" + id + ":4"),
                ib(iv && every == 6, "6", "a:nfsi:" + id + ":6"), ib(iv && every == 12, "12", "a:nfsi:" + id + ":12")));
        if (iv) {
            rows.add(irow(ib(from == 0 && to == 23, "00–23", "a:nfsw:" + id + ":0:23"),
                    ib(from == 8 && to == 22, "08–22", "a:nfsw:" + id + ":8:22"),
                    ib(from == 9 && to == 21, "09–21", "a:nfsw:" + id + ":9:21"),
                    ib(from == 10 && to == 20, "10–20", "a:nfsw:" + id + ":10:20")));
            rows.add(irow(ib(off == -10, "-10 min", "a:nfso:" + id + ":-10"), ib(off == 0, "soat boshi", "a:nfso:" + id + ":0"),
                    ib(off == 15, "+15 min", "a:nfso:" + id + ":15"), ib(off == 30, "+30 min", "a:nfso:" + id + ":30")));
        }
        boolean once = n.isOnceMode();
        rows.add(irow(btn((iv || once ? "" : "✅ ") + "⏰ Aniq vaqtlar" + (iv || once ? "" : ": " + String.join(", ", svc.times(n))), "a:nfsv:" + id)));
        rows.add(irow(btn((once ? "✅ " : "") + "📅 Bir marta (sana + vaqt)", "a:nfs1:" + id)));
        Set<Integer> days = n.weekdaySet();
        // 7 ta tugma bitta qatorga sig'maydi (Telegram nomlarni «…» qilib kesadi) — 4 + 3 qator
        List<InlineKeyboardButton> dr1 = new ArrayList<>(), dr2 = new ArrayList<>();
        for (int d = 1; d <= 7; d++)
            (d <= 4 ? dr1 : dr2).add(btn((days.isEmpty() || days.contains(d) ? "✅ " : "▫️ ") + DAY_NAMES[d - 1],
                    "a:nfsd:" + id + ":" + d));
        rows.add(dr1);
        rows.add(dr2);
        boolean work = days.equals(Set.of(1, 2, 3, 4, 5));
        rows.add(irow(ib(days.isEmpty(), "Har kuni", "a:nfsd:" + id + ":all"),
                ib(work, "Ish kunlari (Du–Ju)", "a:nfsd:" + id + ":work")));
        rows.add(irow(btn("⬅️ Orqaga", "a:nfc:" + id)));
        show(chatId, msgId, text, rows);
    }

    /** 🔘 Menyu tugmasi sozlamasi: matn + rollar. Tugma tanlangan rollarning ASOSIY menyusi oxirida chiqadi. */
    private void buttonMenu(long id, long chatId, int msgId) {
        Notify n = svc.find(id).orElse(null);
        if (n == null) return;
        Set<Role> set = n.buttonRoleSet();
        String text = "🔘 <b>" + esc(n.getName()) + " — menyu tugmasi</b>\n\n"
                + "Shablon tanlangan rollarning asosiy menyusida tugma bo'lib chiqadi. Bosilganda shablon "
                + "shu foydalanuvchi uchun jonli render qilinadi (kassirga <code>{kassa:mening.…}</code> — o'z otdeli).\n"
                + "Mavjud bo'limlar o'zgarmaydi, tugma ular ostiga qo'shiladi.\n\n"
                + "Matn: " + (n.getButtonLabel().isBlank() ? "<i>belgilanmagan</i>" : "<b>" + esc(n.getButtonLabel()) + "</b>") + "\n"
                + "Kimga: <b>" + esc(NotifyService.rolesText(n)) + "</b>"
                + (n.isActive() ? "" : "\n\n⚠️ Bildirishnoma o'chirib turilgan (⚪) — tugma ko'rinmaydi, ▶️ Yoqish kerak.");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(btn("✍️ Tugma matni", "a:nfbl:" + id)));
        rows.add(irow(ib(set.contains(Role.KASSIR), "Kassirlar", "a:nfbr:" + id + ":KASSIR"),
                ib(set.contains(Role.BUXGALTER), "Buxgalterlar", "a:nfbr:" + id + ":BUXGALTER"),
                ib(set.contains(Role.SUPERADMIN), "SuperAdminlar", "a:nfbr:" + id + ":SUPERADMIN")));
        if (!n.getButtonLabel().isBlank()) rows.add(irow(btn("🗑 Tugmani olib tashlash", "a:nfbx:" + id)));
        rows.add(irow(btn("⬅️ Orqaga", "a:nfc:" + id)));
        show(chatId, msgId, text, rows);
    }

    private static InlineKeyboardButton ib(boolean on, String label, String data) {
        return btn((on ? "✅ " : "") + label, data);
    }

    private void recipientsMenu(long id, long chatId, int msgId) {
        Notify n = svc.find(id).orElse(null);
        if (n == null) return;
        Set<String> set = n.recipientSet();
        String text = "👥 <b>" + esc(n.getName()) + " — kimga</b>\n\nHozir: " + svc.describeRecipients(n) + "\n\n"
                + "Bir nechta turni birga tanlash mumkin; takror chatlarga bir marta yuboriladi.";
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(ib(set.contains("rol:KASSIR"), "Kassirlar", "a:nfrt:" + id + ":rol:KASSIR"),
                ib(set.contains("rol:BUXGALTER"), "Buxgalterlar", "a:nfrt:" + id + ":rol:BUXGALTER"),
                ib(set.contains("rol:SUPERADMIN"), "SuperAdminlar", "a:nfrt:" + id + ":rol:SUPERADMIN")));
        rows.add(irow(ib(set.contains("click_chats"), "📣 Click hisobot chatlari", "a:nfrt:" + id + ":click_chats")));
        rows.add(irow(ib(set.contains("karta_masul"), "💳 Karta mas'ullari", "a:nfrt:" + id + ":karta_masul"),
                ib(set.contains("mehmonlar"), "🙋 Mehmonlar", "a:nfrt:" + id + ":mehmonlar")));
        rows.add(irow(btn("👤 Xodim tanlash", "a:nfru:" + id), btn("🏪 Otdel kassirlari", "a:nfrk:" + id)));
        rows.add(irow(btn("➕ Guruh/kanal ID", "a:nfrg:" + id)));
        for (String r : set)
            if (r.startsWith("group:")) {
                long gid = Long.parseLong(r.substring(6));
                var chat = sender.getChat(gid);
                String name = chat == null ? null : (chat.getTitle() != null ? chat.getTitle() : chat.getUserName());
                rows.add(irow(btn("🗑 " + (name != null ? name : "chat " + gid), "a:nfrt:" + id + ":" + r)));
            }
        rows.add(irow(btn("⬅️ Orqaga", "a:nfc:" + id)));
        show(chatId, msgId, text, rows);
    }

    private void usersPick(long id, long chatId, int msgId) {
        Notify n = svc.find(id).orElse(null);
        if (n == null) return;
        Set<String> set = n.recipientSet();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<AppUser> users = userRepo.findByActiveTrueOrderByRoleAscIdAsc().stream()
                .filter(x -> x.getTelegramId() != null).toList();
        for (int i = 0; i < users.size(); i += 2) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            for (int j = i; j < Math.min(i + 2, users.size()); j++) {
                AppUser x = users.get(j);
                r.add(ib(set.contains("user:" + x.getId()), x.getFullName(), "a:nfrt:" + id + ":user:" + x.getId()));
            }
            rows.add(r);
        }
        rows.add(irow(btn("⬅️ Orqaga", "a:nfr:" + id)));
        show(chatId, msgId, "👤 <b>Xodim tanlash</b> — bosib belgilang (faqat Telegram ulanganlar):", rows);
    }

    private void kassaPick(long id, long chatId, int msgId) {
        Notify n = svc.find(id).orElse(null);
        if (n == null) return;
        Set<String> set = n.recipientSet();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Kassa k : kassaRepo.findByActiveTrueOrderByIdAsc())
            rows.add(irow(ib(set.contains("kassa:" + k.getId()), k.getName(), "a:nfrt:" + id + ":kassa:" + k.getId())));
        rows.add(irow(btn("⬅️ Orqaga", "a:nfr:" + id)));
        show(chatId, msgId, "🏪 <b>Otdel kassirlari</b> — tanlangan otdelga biriktirilgan barcha kassirlarga yuboriladi:", rows);
    }

    private void help(int page, long chatId, int msgId) {
        String text = switch (page) {
            case 2 -> TemplateService.HELP_2;
            case 3 -> TemplateService.HELP_3;
            default -> TemplateService.HELP_1;
        };
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(irow(ib(page == 1, "1", "a:nfh:1"), ib(page == 2, "2", "a:nfh:2"), ib(page == 3, "3", "a:nfh:3")));
        rows.add(irow(btn("⬅️ Orqaga", "a:nfm")));
        show(chatId, msgId, text, rows);
    }

    private void show(long chatId, int msgId, String text, List<List<InlineKeyboardButton>> rows) {
        InlineKeyboardMarkup kb = inline(rows);
        if (msgId > 0) sender.edit(chatId, msgId, text, kb);
        else sender.send(chatId, text, kb);
    }
}
