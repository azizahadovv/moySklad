package uz.kassa.service.control;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import uz.kassa.bot.Sender;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Kassa;
import uz.kassa.domain.KassaHead;
import uz.kassa.domain.Role;
import uz.kassa.domain.Shipment;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.KassaHeadRepo;
import uz.kassa.repo.KassaRepo;
import java.util.*;

/**
 * Nazorat xabarlari oluvchilari — OTDEL KESIMIDA:
 *   xodim (otgruzka egasi) · Масъул · shu otdel rahbarlari · SuperAdmin (hamma otdel) ·
 *   belgilangan xodimlar (control.recipients; otdelga biriktirilgan bo'lsa faqat o'z otdeli).
 * Boshqa otdel kassiri/rahbari hech narsa olmaydi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ControlNotifier {

    private final AppUserRepo userRepo;
    private final KassaHeadRepo headRepo;
    private final KassaRepo kassaRepo;
    private final Sender sender;
    private final ControlConfig cfg;

    public static final String MS_DEMAND_URL = "https://online.moysklad.ru/app/#demand/edit?id=";
    public static final String MS_AGENT_URL = "https://online.moysklad.ru/app/#company/edit?id=";

    /** Xodim + Масъул (+ rahbarlar, SuperAdmin, belgilanganlar — withAdmins). */
    public Set<AppUser> forShipment(Shipment s, boolean withAdmins) {
        Set<AppUser> out = new LinkedHashSet<>();
        if (s.getOwnerUserId() != null) userRepo.findById(s.getOwnerUserId()).ifPresent(out::add);
        if (s.getMasulUserId() != null) userRepo.findById(s.getMasulUserId()).ifPresent(out::add);
        out.addAll(heads(s.getKassaId()));
        if (withAdmins) {
            out.addAll(superadmins());
            out.addAll(designated(s.getKassaId()));
        }
        out.removeIf(u -> !u.isActive());
        return out;
    }

    /** Otdel rahbarlari (kassa_heads). */
    public Set<AppUser> heads(Long kassaId) {
        Set<AppUser> out = new LinkedHashSet<>();
        if (kassaId == null) return out;
        for (KassaHead h : headRepo.findByKassaId(kassaId))
            userRepo.findById(h.getUserId()).filter(AppUser::isActive).ifPresent(out::add);
        return out;
    }

    public boolean isHead(AppUser u, Long kassaId) {
        return kassaId != null && headRepo.findByKassaIdAndUserId(kassaId, u.getId()).isPresent();
    }

    /** Foydalanuvchi rahbar bo'lgan otdellar. */
    public Set<Long> headOf(AppUser u) {
        Set<Long> out = new LinkedHashSet<>();
        for (KassaHead h : headRepo.findByUserId(u.getId())) out.add(h.getKassaId());
        return out;
    }

    public Set<AppUser> superadmins() {
        return new LinkedHashSet<>(userRepo.findByRoleAndActiveTrue(Role.SUPERADMIN));
    }

    /** Belgilangan xodimlar: otdelga biriktirilganlar faqat o'z otdeli xabarini oladi. */
    public Set<AppUser> designated(Long kassaId) {
        Set<AppUser> out = new LinkedHashSet<>();
        for (Long id : cfg.recipientIds())
            userRepo.findById(id).filter(AppUser::isActive)
                    .filter(u -> u.getKassaId() == null || kassaId == null || u.getKassaId().equals(kassaId))
                    .ifPresent(out::add);
        return out;
    }

    /** Rahbarlar + SuperAdmin + belgilanganlar (xodimsiz) — eskalatsiya uchun. */
    public Set<AppUser> escalation(Long kassaId) {
        Set<AppUser> out = new LinkedHashSet<>(heads(kassaId));
        out.addAll(superadmins());
        out.addAll(designated(kassaId));
        return out;
    }

    /** Takror chatlarga bir marta; Telegram ulanmaganlar o'tkaziladi. */
    public void send(Collection<AppUser> users, String text, InlineKeyboardMarkup kb) {
        Set<Long> sent = new HashSet<>();
        for (AppUser u : users) {
            Long tg = u.getTelegramId();
            if (tg == null || !sent.add(tg)) continue;
            try { sender.send(tg, text, kb); }
            catch (Exception e) { log.warn("Nazorat xabari yuborilmadi ({}): {}", u.getFullName(), e.getMessage()); }
        }
    }

    public void sendOne(AppUser u, String text, InlineKeyboardMarkup kb) {
        if (u != null) send(List.of(u), text, kb);
    }

    public String kassaName(Long kassaId) {
        if (kassaId == null) return "—";
        return kassaRepo.findById(kassaId).map(Kassa::getName).orElse("#" + kassaId);
    }

    /** MoySklad otdeli (group UUID) → bot kassasi. */
    public Long kassaByGroup(String groupId) {
        if (groupId == null || groupId.isBlank()) return null;
        for (Kassa k : kassaRepo.findAll())
            if (groupId.equals(k.getMoyskladGroupId())) return k.getId();
        return null;
    }

    public String userName(Long userId) {
        if (userId == null) return "";
        return userRepo.findById(userId).map(AppUser::getFullName).orElse("#" + userId);
    }

    public static InlineKeyboardButton urlBtn(String text, String url) {
        return InlineKeyboardButton.builder().text(text).url(url).build();
    }
}
