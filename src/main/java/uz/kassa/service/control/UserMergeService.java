package uz.kassa.service.control;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.kassa.bot.TextUtil;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.KassaHead;
import uz.kassa.domain.Role;
import uz.kassa.repo.*;
import uz.kassa.service.AuditService;
import uz.kassa.service.BusinessException;

import java.util.ArrayList;
import java.util.List;

/**
 * Dublikat foydalanuvchilar: aniqlash va birlashtirish.
 * Sabab: bitta odam ikki yo'ldan (qo'lda qo'shish + MoySklad avto-yaratish, Sheets) ikki marta
 * yaratilib qolgan — «Zulxumor» va «112 Зулхумор 01,06,2026» kabi. Birlashtirishda saqlanadigan
 * (keep) foydalanuvchiga bo'sh maydonlar to'ldiriladi, otgruzka/kontragent/operatsiya/hisobot
 * yozuvlari ko'chadi, rahbarliklar ko'chadi, ikkinchisi (drop) faolsizlanadi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserMergeService {

    private final AppUserRepo userRepo;
    private final ShipmentRepo shipmentRepo;
    private final AgentCheckRepo agentCheckRepo;
    private final KassaHeadRepo headRepo;
    private final OperationRepo opRepo;
    private final SubmissionRepo subRepo;
    private final AuditService audit;

    public record Dup(AppUser a, AppUser b, String why) {}

    /** Faol foydalanuvchilar orasida dublikat ehtimoli bo'lgan juftliklar (telefon, MoySklad, ism). */
    public List<Dup> duplicates() {
        List<AppUser> all = userRepo.findByActiveTrueOrderByRoleAscIdAsc();
        List<Dup> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                AppUser a = all.get(i), b = all.get(j);
                String why = why(a, b);
                if (why != null) out.add(new Dup(a, b, why));
                if (out.size() >= 20) return out;
            }
        }
        return out;
    }

    /** Ikki foydalanuvchi bir odam bo'lishi mumkinmi — sabab matni yoki null. */
    public String why(AppUser a, AppUser b) {
        if (notBlank(a.getPhone()) && notBlank(b.getPhone()) && TextUtil.phoneEq(a.getPhone(), b.getPhone()))
            return "telefon bir xil";
        if (notBlank(a.getMsEmployeeId()) && a.getMsEmployeeId().equals(b.getMsEmployeeId()))
            return "MoySklad xodimi bir xil";
        if (notBlank(a.getMsUid()) && a.getMsUid().equalsIgnoreCase(b.getMsUid()))
            return "MoySklad logini bir xil";
        if (EmployeeLinkService.nameScore(EmployeeLinkService.cleanName(a.getFullName()),
                EmployeeLinkService.cleanName(b.getFullName())) >= 2)
            return "ism-familiya o'xshash";
        return null;
    }

    /** Yangi nom/telefon/MoySklad id bilan mos keladigan mavjud FAOL foydalanuvchi (qo'shishda dublikat himoyasi). */
    public AppUser findExisting(String fullName, String phone, String msEmployeeId, String msUid) {
        for (AppUser u : userRepo.findByActiveTrueOrderByRoleAscIdAsc()) {
            if (notBlank(msEmployeeId) && msEmployeeId.equals(u.getMsEmployeeId())) return u;
            if (notBlank(msUid) && msUid.equalsIgnoreCase(u.getMsUid())) return u;
            if (notBlank(phone) && notBlank(u.getPhone()) && TextUtil.phoneEq(u.getPhone(), phone)) return u;
            if (notBlank(fullName) && EmployeeLinkService.nameScore(
                    EmployeeLinkService.cleanName(fullName), EmployeeLinkService.cleanName(u.getFullName())) >= 2) return u;
        }
        return null;
    }

    /** keep — qoladi, drop — faolsizlanadi. Qaytaradi: nima ko'chgani haqida qisqa matn. */
    @Transactional
    public String merge(long keepId, long dropId, Long actorId) {
        if (keepId == dropId) throw new BusinessException("Bir xil foydalanuvchi tanlandi");
        AppUser keep = userRepo.findById(keepId).orElseThrow(() -> new BusinessException("Foydalanuvchi topilmadi: #" + keepId));
        AppUser drop = userRepo.findById(dropId).orElseThrow(() -> new BusinessException("Foydalanuvchi topilmadi: #" + dropId));

        Long tg = drop.getTelegramId();
        String phone = drop.getPhone(), msId = drop.getMsEmployeeId(), uid = drop.getMsUid();
        Long kassa = drop.getKassaId();
        Role dropRole = drop.getRole();

        // Avval drop bo'shatiladi (telegram_id UNIQUE — keep'ga ko'chirishdan oldin)
        drop.setTelegramId(null);
        drop.setPhone(null);
        drop.setMsEmployeeId(null);
        drop.setMsUid(null);
        drop.setActive(false);
        drop.setFullName(drop.getFullName() + " ⇒ #" + keep.getId());
        userRepo.saveAndFlush(drop);

        List<String> took = new ArrayList<>();
        if (keep.getTelegramId() == null && tg != null) { keep.setTelegramId(tg); took.add("Telegram"); }
        if (!notBlank(keep.getPhone()) && notBlank(phone)) { keep.setPhone(phone); took.add("telefon"); }
        if (!notBlank(keep.getMsEmployeeId()) && notBlank(msId)) { keep.setMsEmployeeId(msId); took.add("MoySklad xodimi"); }
        if (!notBlank(keep.getMsUid()) && notBlank(uid)) { keep.setMsUid(uid); took.add("MoySklad login"); }
        if (keep.getKassaId() == null && kassa != null) { keep.setKassaId(kassa); took.add("otdel"); }
        if (rank(dropRole) > rank(keep.getRole())) { keep.setRole(dropRole); took.add("rol " + dropRole); }
        keep.setControlWelcomeAt(null);
        userRepo.save(keep);

        int heads = 0;
        for (KassaHead h : headRepo.findByUserId(drop.getId())) {
            if (headRepo.findByKassaIdAndUserId(h.getKassaId(), keep.getId()).isEmpty()) {
                h.setUserId(keep.getId());
                headRepo.save(h);
                heads++;
            } else headRepo.delete(h);
        }

        int n = 0;
        n += shipmentRepo.remapOwnerUser(drop.getId(), keep.getId());
        n += shipmentRepo.remapMasulUser(drop.getId(), keep.getId());
        n += agentCheckRepo.remapCreatorUser(drop.getId(), keep.getId());
        n += opRepo.remapCreatedBy(drop.getId(), keep.getId());
        n += opRepo.remapDecidedBy(drop.getId(), keep.getId());
        n += subRepo.remapSubmittedBy(drop.getId(), keep.getId());
        n += subRepo.remapDecidedBy(drop.getId(), keep.getId());

        String summary = "yozuvlar: " + n + " ta, rahbarlik: " + heads + " ta"
                + (took.isEmpty() ? "" : ", olindi: " + String.join(", ", took));
        audit.log(actorId, "XODIM_BIRLASHTIRILDI", "user", keep.getId(),
                "#" + drop.getId() + " → #" + keep.getId() + " · " + summary);
        log.info("Foydalanuvchi birlashtirildi: #{} → #{} ({})", drop.getId(), keep.getId(), summary);
        return summary;
    }

    private static int rank(Role r) {
        return r == Role.SUPERADMIN ? 3 : r == Role.BUXGALTER ? 2 : 1;
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
