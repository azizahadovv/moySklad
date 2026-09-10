package uz.kassa.service.control;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.bot.TextUtil;
import uz.kassa.config.AppProps;
import uz.kassa.domain.AppUser;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.GuestRepo;
import uz.kassa.service.AuditService;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 🔗 Taklif (referal) havolasi — Telegram'ga ulanmagan xodim uchun: t.me/<bot>?start=inv_<token>.
 * Xodim havolani bosib 📱 kontakt yuborsa hech qanday tasdiqsiz ulanadi:
 *  - kartasida telefon bo'lsa — yuborilgan raqam AYNAN mos kelishi shart;
 *  - telefoni bo'sh bo'lsa (MoySklad'da ham yo'q) — havolaning o'zi kalit, raqam kartaga yoziladi.
 * Bir martalik (ulangach o'chadi), 24 soat amal qiladi; admin kartadan yangisini oladi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InviteService {

    public static final Duration TTL = Duration.ofHours(24);
    public static final String PREFIX = "inv_";
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";

    private final AppUserRepo userRepo;
    private final GuestRepo guestRepo;
    private final AuditService audit;
    private final AppProps props;
    private final EmployeeLinkService link;
    private final SecureRandom rnd = new SecureRandom();

    public enum Result { LINKED, PHONE_MISMATCH, EXPIRED, USED, BUSY }
    public record Outcome(Result result, AppUser user) {}

    /** Amaldagi havola (eskirmagan) yoki yangisi. */
    public String linkFor(AppUser u, Long actorId) {
        if (!valid(u)) return renew(u, actorId);
        return url(u.getInviteToken());
    }

    /** Eskisini bekor qilib yangi havola. */
    public String renew(AppUser u, Long actorId) {
        u.setInviteToken(newToken());
        u.setInviteExpiresAt(Instant.now().plus(TTL));
        userRepo.save(u);
        audit.log(actorId, "TAKLIF_HAVOLA", "user", u.getId(), u.getFullName() + " · 24 soat");
        return url(u.getInviteToken());
    }

    public boolean valid(AppUser u) {
        return u.getInviteToken() != null && u.getInviteExpiresAt() != null
                && u.getInviteExpiresAt().isAfter(Instant.now());
    }

    public String url(String token) {
        return "https://t.me/" + props.getBot().getUsername() + "?start=" + PREFIX + token;
    }

    /** Muddat matni (Toshkent vaqti), masalan «11.09 14:30». */
    public String expiresText(AppUser u) {
        if (u.getInviteExpiresAt() == null) return "—";
        return DateTimeFormatter.ofPattern("dd.MM HH:mm").format(u.getInviteExpiresAt().atZone(props.zoneId()));
    }

    /** /start parametri taklif havolasi bo'lsa — token, aks holda null. */
    public static String tokenOf(String startParam) {
        if (startParam == null) return null;
        String p = startParam.trim();
        if (!p.startsWith(PREFIX) || p.length() <= PREFIX.length() || p.length() > 60) return null;
        return p.substring(PREFIX.length());
    }

    /** Amaldagi token egasi (faol, eskirmagan). */
    public Optional<AppUser> byToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return userRepo.findFirstByInviteToken(token).filter(AppUser::isActive).filter(this::valid);
    }

    /**
     * Kontakt keldi: token bo'yicha ulash. Tasdiq so'ralmaydi — faqat telefon tekshiriladi
     * (kartada raqam bo'lsa). Ulangach token o'chadi (bir martalik).
     */
    public Outcome accept(String token, long tgId, String contactPhone) {
        AppUser u = userRepo.findFirstByInviteToken(token).orElse(null);
        if (u == null || !u.isActive()) return new Outcome(Result.EXPIRED, null);
        if (!valid(u)) return new Outcome(Result.EXPIRED, u);
        if (u.getTelegramId() != null && !u.getTelegramId().equals(tgId)) return new Outcome(Result.USED, u);
        if (userRepo.findByTelegramId(tgId).filter(o -> !o.getId().equals(u.getId())).isPresent())
            return new Outcome(Result.BUSY, u);
        String np = TextUtil.normPhone(contactPhone);
        boolean hasPhone = u.getPhone() != null && !TextUtil.normPhone(u.getPhone()).isEmpty();
        if (hasPhone && !TextUtil.phoneEq(u.getPhone(), np)) return new Outcome(Result.PHONE_MISMATCH, u);
        u.setTelegramId(tgId);
        if (!hasPhone && !np.isEmpty()) u.setPhone(np);
        u.setControlWelcomeAt(null);   // ochiq xatolari/qarzdorlari 2 daqiqada unga boradi
        u.setInviteToken(null);
        u.setInviteExpiresAt(null);
        userRepo.save(u);
        try { guestRepo.deleteById(tgId); } catch (Exception ignored) { }
        try { link.applyDepartment(u, null); }
        catch (Exception e) { log.warn("Otdel qo'llash ({}): {}", u.getFullName(), e.getMessage()); }
        audit.log(u.getId(), "TELEGRAM_ULANDI", "user", u.getId(), u.getFullName() + " taklif havolasi orqali tg="
                + tgId + (hasPhone ? " (telefon mos)" : " (telefon kartaga yozildi)"));
        return new Outcome(Result.LINKED, u);
    }

    private String newToken() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
