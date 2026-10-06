package uz.kassa.service.adesk;

import org.junit.jupiter.api.Test;
import uz.kassa.service.adesk.AdeskClient.AdAccount;
import uz.kassa.service.adesk.AdeskMsReader.MsAccount;
import uz.kassa.service.adesk.AdeskMsReader.MsOrg;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 📒 Hisob takliflari — haqiqiy nomlar bilan (MoySklad 14 tashkilot, Adesk'da qo'lda ochilgan 25 hisob, 2026-10-05). */
class AdeskAccountMapperTest {

    final Map<String, MsOrg> orgs = new LinkedHashMap<>();
    final List<MsAccount> ms = new ArrayList<>();
    final List<AdAccount> ad = new ArrayList<>();

    void org(String id, String name, String... accounts) {
        orgs.put(id, new MsOrg(id, name, "", "", AdeskMsReader.shortName(name)));
        ms.add(new MsAccount(id + ":CASH", id, null, "Касса", "", "", true, false));
        for (int i = 0; i < accounts.length; i++) {
            String n = accounts[i];
            boolean def = n.startsWith("*");
            String raw = def ? n.substring(1) : n;
            ms.add(new MsAccount(id + ":" + i, id, id + "-" + i, raw, "", "", AdeskMsReader.looksCash(raw), def));
        }
    }

    void adesk(long id, String name, int type) {
        ad.add(new AdAccount(id, name, String.valueOf(type), 118024L, BigDecimal.ZERO, BigDecimal.ZERO, null, "open", "UZS"));
    }

    String nameOf(String msKey) {
        return ms.stream().filter(m -> m.key().equals(msKey)).findFirst().map(m -> m.orgId() + "/" + m.rawName()).orElse("?");
    }

    @Test
    void realNames() {
        org("001", "001 NSB New Star Bukhara МЧЖ 302037932", "NSB click Zufar", "NSB click Abdullo", "NSB click Samoyiddin", "NSB click Jasur",
                "NSB uzcard", "NSB humo", "NSB Clik Прочие", "NSB PAYME", "NSB \"CLIK\" AJ", "QR KOD", "*NSB р/с", "NSB Прочие Р.счет", "NSB UZUM BANK",
                "Клик Камера ", "NSB Корпоратив карта", "5344 NBU", "Kassa 1 Naqd UZS", "Kassa 1 Naqd USD", "Касса Доллар USD");
        org("002", "002 SHMF Shofirkon mega fayz МЧЖ 306691355", "*SHMF р/с: 20208000205119881001");
        org("003", "003 ITT INTER TECHNO TRUST MCHJ 308974032", "*р/с", "Uzcard", "Humo");
        org("004", "004 JOMS JAVOHIR ORGTEXNKA MEGA SERVIS MCHJ 308754718", "*JOMS р/с:");
        org("005", "005 SS-2020 Сохибов сервис 2020 МЧЖ 307867316", "*р/с 20208000105383409001");
        org("006", "006 ALOF Амирбек лола файз MCHJ 310008323", "*р/с");
        org("007", "007 \"BIZNES PLYUS SERVIS\" МЧЖ 310904174", "*20208000005713185001 ");
        org("008", "008 CBF City Boys fayz MCHJ 310954303", "*р/с");
        org("009", "009 NSB TECH \"NSB tech\" MCHJ 310955791", "*р/с");
        org("010", "010 YB \"YASHIN BUSINESMAN\" МЧЖ 310621622", "*р/с");
        org("011", "011 UAS Umidabonu Azizbek savdo MCHJ 302678934", "*р/с");
        org("012", "012 KKT ООО \"KOSON KOMP TRUST\" 309394857", "*р/с");
        org("013", "013 UD-2022 ULUG'BEK DIYORBEK-2022 MCHJ 309217172", "*р/с");
        org("014", "014 KOJ-SEVINCH NUR  300064197", "*KOJ р/с 20208000204561932002", "UZUM SELLER");

        String[][] adesk = {{"New Star Bukhara", "2"}, {"Shofirkon Mega Fayz", "2"}, {"Inter Techbo Trust", "2"}, {"Javohir Orgtexnika Mega Servis", "2"},
                {"Soxibov Servis 2020", "2"}, {"Amirbek Lola Omad Fayz", "2"}, {"Biznes Plus Servis", "2"}, {"City Boys Fayz", "2"}, {"NSB Tech", "2"},
                {"Yashin Businessman", "2"}, {"Umidabonu Azizbek Savdo", "2"}, {"Koson Komp Trust", "2"}, {"ulug'bek Diyorbek 2022", "2"},
                {"KOJ Servis Nur", "2"}, {"Karta Zufar aka", "1"}, {"Karta Bobomurot", "1"}, {"Karta Jasur aka", "1"}, {"Karta Ozod", "1"},
                {"Karta Abdullo aka", "1"}, {"Karta kamera do'kon", "2"}, {"Kassa", "1"}, {"Kassa Komp", "1"}, {"Kassa kamera", "1"},
                {"Kassa Servis 1", "1"}, {"Kassa Servis 2", "2"}};
        for (int i = 0; i < adesk.length; i++) adesk(315643 + i, adesk[i][0], Integer.parseInt(adesk[i][1]));

        Map<String, Long> s = AdeskAccountMapper.suggest(ms, orgs, ad);
        Map<String, String> got = new TreeMap<>();
        s.forEach((k, v) -> got.put(ad.stream().filter(a -> a.id() == v).findFirst().orElseThrow().name(), nameOf(k)));
        got.forEach((a, m) -> System.out.println(a + "  →  " + m));

        Map<String, String> want = new LinkedHashMap<>();
        want.put("New Star Bukhara", "001/NSB р/с");
        want.put("Shofirkon Mega Fayz", "002/SHMF р/с: 20208000205119881001");
        want.put("Inter Techbo Trust", "003/р/с");
        want.put("Javohir Orgtexnika Mega Servis", "004/JOMS р/с:");
        want.put("Soxibov Servis 2020", "005/р/с 20208000105383409001");
        want.put("Amirbek Lola Omad Fayz", "006/р/с");
        want.put("Biznes Plus Servis", "007/20208000005713185001 ");
        want.put("City Boys Fayz", "008/р/с");
        want.put("NSB Tech", "009/р/с");
        want.put("Yashin Businessman", "010/р/с");
        want.put("Umidabonu Azizbek Savdo", "011/р/с");
        want.put("Koson Komp Trust", "012/р/с");
        want.put("ulug'bek Diyorbek 2022", "013/р/с");
        want.put("KOJ Servis Nur", "014/KOJ р/с 20208000204561932002");
        want.put("Karta Zufar aka", "001/NSB click Zufar");
        want.put("Karta Jasur aka", "001/NSB click Jasur");
        want.put("Karta Abdullo aka", "001/NSB click Abdullo");
        want.put("Karta kamera do'kon", "001/Клик Камера ");
        want.put("Kassa", "001/Касса");
        want.forEach((a, m) -> assertEquals(m, got.get(a), a));
        // MoySklad'da aniq jufti yo'q — taklif qilinmaydi, SuperAdmin o'zi tanlaydi
        for (String a : List.of("Karta Bobomurot", "Karta Ozod", "Kassa Komp", "Kassa kamera", "Kassa Servis 1", "Kassa Servis 2"))
            assertFalse(got.containsKey(a), a);
        assertEquals(19, s.size());
    }

    @Test
    void exactNameWinsAndExistingLinksAreKept() {
        org("001", "001 NSB New Star Bukhara МЧЖ 302037932", "NSB click Samoyiddin", "NSB Clik Прочие", "NSB Прочие Р.счет", "*NSB р/с");
        org("003", "003 ITT INTER TECHNO TRUST MCHJ 308974032", "*р/с", "Humo");
        org("012", "012 KKT ООО \"KOSON KOMP TRUST\" 309394857", "*р/с");   // «р/с» takrorlanadi → «р/с · 003 ITT»
        adesk(1, "NSB Clik Прочие", 2);          // qo'lda, MoySklad nomi bilan ochilgan
        adesk(2, "NSB click Samoyiddin", 2);
        adesk(3, "Karta Ozod", 1);
        adesk(4, "NSB humo", 2);
        adesk(5, "Касса · 003 ITT", 1);           // bot oldingi yurishda yaratgan
        adesk(6, "р/с · 003 ITT", 2);
        adesk(7, "Inter Techbo Trust", 2);        // qo'lda ochilgan
        Map<String, Long> s = AdeskAccountMapper.suggest(ms, orgs, ad);
        assertEquals(5L, s.get("003:CASH"));
        assertEquals(6L, s.get("003:0"), "aynan nom firma nomidagi o'xshashlikdan ustun");
        assertEquals(1L, s.get("001:1"), "so'zsiz nom — aynan mos");
        assertEquals(2L, s.get("001:0"));
        assertFalse(s.containsKey("001:2"), "«NSB Прочие Р.счет» — boshqa hisob");
        assertFalse(s.containsValue(3L));
    }

    @Test
    void words() {
        assertEquals(Set.of("soxibov", "servis", "2020"), AdeskAccountMapper.tokens("Сохибов сервис 2020 МЧЖ 307867316"));
        assertEquals(Set.of("ulugbek", "diyorbek", "2022"), AdeskAccountMapper.tokens("ulug'bek Diyorbek 2022"));
        assertEquals(Set.of("kamera"), AdeskAccountMapper.tokens("Karta kamera do'kon"));
        assertTrue(AdeskAccountMapper.wordEq("techbo", "techno"));
        assertTrue(AdeskAccountMapper.wordEq("businessman", "businesman"));
        assertFalse(AdeskAccountMapper.wordEq("servis", "sevinch"));
        assertFalse(AdeskAccountMapper.wordEq("plus", "plyus"));
    }
}
