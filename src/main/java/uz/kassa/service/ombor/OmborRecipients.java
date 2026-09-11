package uz.kassa.service.ombor;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.OmborKamchilik;
import uz.kassa.domain.OmborQoida;
import uz.kassa.domain.Role;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.service.control.ControlNotifier;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 🏬 Kamchilik oluvchilari — qoida to_role + do'kon kesimida (docs/OMBOR-TZ.md §6):
 *   XODIM → owner_user_id (yo'q bo'lsa rahbar) · ZAVSKLAD/ZAKUPSHIK/DIREKTOR → ombor.role.* sozlamasi
 *   (do'kon, keyin umumiy; bo'sh bo'lsa rahbar) · RAHBAR → kassa_heads · ADMIN → SuperAdmin.
 * Eskalatsiya: 1-bosqich rahbar (yo'q bo'lsa admin), 2-bosqich rahbar + admin + belgilanganlar (ControlNotifier).
 */
@Service
@RequiredArgsConstructor
public class OmborRecipients {

    private final OmborConfig cfg;
    private final ControlNotifier notifier;
    private final AppUserRepo userRepo;

    public Set<AppUser> primary(OmborQoida rule, OmborKamchilik k) {
        Set<AppUser> out = new LinkedHashSet<>();
        switch (rule.getToRole()) {
            case "XODIM" -> { if (k.getOwnerUserId() != null) userRepo.findById(k.getOwnerUserId()).ifPresent(out::add); }
            case "RAHBAR" -> out.addAll(notifier.heads(k.getKassaId()));
            case "ADMIN" -> out.addAll(notifier.superadmins());
            default -> { for (Long id : cfg.roleUsers(rule.getToRole(), k.getKassaId())) userRepo.findById(id).ifPresent(out::add); }
        }
        out.removeIf(u -> !u.isActive());
        if (out.isEmpty() && !rule.getToRole().equals("ADMIN")) out.addAll(notifier.heads(k.getKassaId()));
        if (out.isEmpty()) out.addAll(notifier.superadmins());
        return out;
    }

    public Set<AppUser> esc1(OmborKamchilik k) {
        Set<AppUser> h = notifier.heads(k.getKassaId());
        return h.isEmpty() ? notifier.superadmins() : h;
    }

    public Set<AppUser> esc2(OmborKamchilik k) { return notifier.escalation(k.getKassaId()); }

    /** Foydalanuvchi bu kamchilikni ko'ra oladimi: admin/bux — hammasi; kassir — o'z do'koni, rahbar bo'lgan do'konlari, o'ziga tegishli. */
    public boolean canSee(AppUser u, OmborKamchilik k) {
        if (u.getRole() != Role.KASSIR) return true;
        if (k.getOwnerUserId() != null && k.getOwnerUserId().equals(u.getId())) return true;
        if (k.getKassaId() == null) return cfg.roleUsers("ZAVSKLAD", null).contains(u.getId()) || cfg.roleUsers("ZAKUPSHIK", null).contains(u.getId());
        return k.getKassaId().equals(u.getKassaId()) || notifier.headOf(u).contains(k.getKassaId());
    }

    public ControlNotifier notifier() { return notifier; }
    public AppUserRepo userRepo() { return userRepo; }
}
