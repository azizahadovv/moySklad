package uz.kassa.gsheets;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.kassa.bot.NameService;
import uz.kassa.domain.*;
import uz.kassa.repo.*;
import uz.kassa.service.LedgerService;
import uz.kassa.service.moysklad.MoySkladClient;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Google Sheets bilan IKKI TOMONLAMA sinxron (har 5 daqiqada):
 *   BOT -> SHEETS: Operatsiyalar, Balanslar, Kunlar (jarayonning to'liq ko'rinishi)
 *   SHEETS -> BOT: Kassalar va Foydalanuvchilar varaqlaridagi tahrirlar
 *                  (nom, otdel, rol, kassa, faol) botga qo'llanadi — НАСТРОЙКА jadvaldan.
 * Yangi satr (ID bo'sh) -> yangi kassa / foydalanuvchi yaratiladi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SheetsSyncService {

    private final GoogleSheetsClient gs;

    private volatile boolean tabsReady = false;
    private final SheetsState st;
    private final SheetsPullService pull;
    private final SheetsPushService push;


    public void sync() {
        if (!gs.configured()) return;
        try {
            st.loadSnaps();
            ensureTabs();
            pull.pullKassalar();
            pull.pullUsers();
            pull.pullShablon();
            push.pushOperatsiyalar();
            push.pushBalanslar();
            push.pushKunlar();
            push.pushKassalar();
            push.pushUsers();
            push.pushShablon();
            push.pushSozlamalar();
        } catch (Exception e) {
            log.warn("Google Sheets sinxron xatosi: {}", e.getMessage());
        }
    }


    /** Tez sikl (har 1 daqiqa): faqat НАСТРОЙКА varaqlari — foydalanuvchi/kassa
     *  tahrirlari uzoq kutmasin. Og'ir push'lar to'liq siklda (5 daq) qoladi. */
    public void syncNastroyka() {
        if (!gs.configured()) return;
        try {
            st.loadSnaps();
            ensureTabs();
            pull.pullKassalar();
            pull.pullUsers();
            pull.pullShablon();
            push.pushKassalar();
            push.pushUsers();
            push.pushShablon();
        } catch (Exception e) {
            log.warn("Google Sheets tez sinxron xatosi: {}", e.getMessage());
        }
    }


    private void ensureTabs() throws Exception {
        if (tabsReady) return;
        gs.ensureTabs(List.of("Operatsiyalar", "Balanslar", "Kunlar",
                "Kassalar", "Foydalanuvchilar", "Shablon", "Sozlamalar"));
        tabsReady = true;
        log.info("Google Sheets ulandi");
    }

}
