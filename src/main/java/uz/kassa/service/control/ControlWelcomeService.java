package uz.kassa.service.control;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.bot.Sender;
import uz.kassa.domain.AgentCheck;
import uz.kassa.domain.AppUser;
import uz.kassa.repo.AppUserRepo;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import static uz.kassa.bot.TextUtil.esc;

/**
 * 🕵️ Назорат — xodim Telegram'ga ulanib botni ishlata boshlagach (chat ochiq), unga BELGILANGAN TARTIBDA,
 * xuddi jonli xabarlardek yuboriladi (kontakt, jadval, admin ulashi — qanday ulangan bo'lishidan qat'i nazar):
 *   1) har bir ochiq kontragent xatosi — alohida standart xabar + «🔗 MoySklad'da ochish» (eskidan yangiga,
 *      ko'pi bilan {@value #MAX_SINGLE} ta; qolgani bitta ro'yxat), eskalatsiya soati shu ondan qayta hisoblanadi;
 *   2) kamchilikli otgruzkalar — standart guruhlangan xabar;
 *   3) qarzdorlari — eski qarzdorlar ham, to'liq ro'yxat (kunlik jamlama ko'rinishida).
 * Ungacha bu xabarlar «xodim ulanmagan» belgisi bilan faqat SuperAdmin'ga ketgan bo'ladi.
 * users.control_welcome_at NULL — navbatda; yuborilgach vaqt yoziladi. Chat hali ochilmagan bo'lsa
 * (xodim /start bosmagan) — 1 soatdan keyin qayta uriniladi, hech narsa yo'qolmaydi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ControlWelcomeService {

    static final int MAX_SINGLE = 10;

    private final AppUserRepo userRepo;
    private final ShipmentControlService shipments;
    private final AgentCheckService agents;
    private final ControlConfig cfg;
    private final Sender sender;
    private final uz.kassa.service.NotifySwitches sw;

    /** userId -> keyingi urinish vaqti (chat topilmasa spam bo'lmasin). */
    private final Map<Long, Long> retryAt = new ConcurrentHashMap<>();

    /** Har 2 daqiqa (Jobs). */
    public void tick() {
        if (!cfg.enabled()) return;
        for (AppUser u : userRepo.findByActiveTrueAndTelegramIdIsNotNullAndControlWelcomeAtIsNull()) {
            Long next = retryAt.get(u.getId());
            if (next != null && next > System.currentTimeMillis()) continue;
            try {
                deliver(u);
                done(u);
            } catch (Exception e) {
                retryAt.put(u.getId(), System.currentTimeMillis() + 3600_000L);
                log.info("Nazorat xabarlari kutmoqda ({}): {}", u.getFullName(), e.getMessage());
            }
        }
    }

    private void done(AppUser u) {
        u.setControlWelcomeAt(Instant.now());
        userRepo.save(u);
        retryAt.remove(u.getId());
    }

    /** Birinchi xabar ketmasa (chat yo'q) — exception, hech narsa belgilanmaydi; keyin qayta uriniladi. */
    private void deliver(AppUser u) {
        long tg = u.getTelegramId();
        List<AgentCheck> open = agents.openFor(u);
        String issueText = shipments.pendingIssuesText(u);
        String debtText = shipments.debtorsDigest(u);
        if (open.isEmpty() && issueText == null && debtText == null) return;
        if (!sw.allow(uz.kassa.service.NotifySwitches.KG_XUSH, u)) return;   // 🔕 Хабарномалар: belgilanadi, xabar ketmaydi

        sender.send(tg, "👋 Xush kelibsiz, <b>" + esc(u.getFullName()) + "</b>! Sizga tegishli nazorat xabarlari quyida — "
                + "bundan keyin ular to'g'ridan-to'g'ri sizga keladi: avval sizga, " + cfg.esc1Min()
                + " daqiqada tuzatilmasa rahbarga, " + cfg.esc2Min() + " daqiqada admin'ga, har kuni " + cfg.dailyTime() + " da jamlama.", null);
        int n = 0;
        for (AgentCheck ac : open) {
            if (++n > MAX_SINGLE) break;
            sender.send(tg, agents.storedErrorMessage(ac), AgentCheckService.agentKb(ac.getAgentMsId()));
            agents.restartTimeline(ac);
        }
        if (open.size() > MAX_SINGLE) {
            List<AgentCheck> rest = open.subList(MAX_SINGLE, open.size());
            sender.send(tg, agents.openListText(rest), null);
            for (AgentCheck ac : rest) agents.restartTimeline(ac);
        }
        if (issueText != null) sender.send(tg, issueText, null);
        if (debtText != null) sender.send(tg, debtText, null);   // eski qarzdorlari ham — to'liq ro'yxat
        log.info("Nazorat xabarlari xodimga yetkazildi: {} (kontragent {}, otgruzka {})", u.getFullName(), open.size(),
                issueText == null ? 0 : 1);
    }
}
