package uz.kassa.service.adesk;

import org.junit.jupiter.api.Test;
import uz.kassa.domain.AdeskLink;
import uz.kassa.service.adesk.AdeskClient.AdTx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 📒 Solishtirish: ДДС qatorlari ikki tomonda bitta nom bilan yig'ilishi. */
class AdeskCheckServiceTest {

    static AdeskLink cat(String key, long adeskId, String status) {
        return AdeskLink.builder().kind(AdeskLink.CATEGORY).msKey(key).adeskId(adeskId).status(status).build();
    }

    static AdTx tx(long id, int type, Long categoryId, String categoryName, boolean transfer) {
        return new AdTx(id, type, new BigDecimal("100.00"), LocalDate.of(2026, 11, 7), 501L, categoryId, null, "",
                transfer, false, "", categoryName, "", null, "acc");
    }

    /**
     * 2026-11-05 dan: Adesk'da «Выручка» statyasi «Поступление от покупателей» deb qayta nomlangan. Bot pulni o'sha id'ga yozishda
     * davom etgan, lekin solishtirish MoySklad tomonini sozlama nomi, Adesk tomonini joriy nom bilan yig'gan — ikki qator bo'lib,
     * har kuni tushumga teng «farq» (8,35 → 8,99 mlrd) ko'rsatgan. Bot bog'lagan statya bot nomi bilan sanaladi.
     */
    @Test
    void renamedAdeskCategoryIsCountedOnTheSameRowAsMoySklad() {
        Map<Long, List<String>> bot = AdeskCheckService.botCategoryNames(List.of(
                cat("in:Выручка", 11, AdeskLink.OK),
                cat("out:Аренда", 21, AdeskLink.OK),
                cat("in:Eski", 99, AdeskLink.DELETED)));
        assertEquals(Map.of(11L, List.of("Выручка"), 21L, List.of("Аренда")), bot, "faqat amaldagi bog'lanishlar");

        Set<String> msIn = Set.of("Выручка", "Перемещение"), msOut = Set.of("Аренда", "Маркетинг");
        Map<Long, String> adName = Map.of(11L, "Поступление от покупателей", 21L, "Аренда офиса", 77L, "Маркетинг");
        assertEquals("Выручка", AdeskCheckService.adCatName(tx(1, 1, 11L, "Поступление от покупателей", false), bot, adName, "Перемещение", msIn));
        assertEquals("Аренда", AdeskCheckService.adCatName(tx(2, 2, 21L, "Аренда офиса", false), bot, adName, "Перемещение", msOut));
        assertEquals("Маркетинг", AdeskCheckService.adCatName(tx(3, 2, 77L, "Маркетинг", false), bot, adName, "Перемещение", msOut),
                "Adesk'da qo'lda ochilgan statya — Adesk nomi");
        assertEquals("Перемещение", AdeskCheckService.adCatName(tx(4, 1, null, "", true), bot, adName, "Перемещение", msIn));
        assertEquals("Статьясиз", AdeskCheckService.adCatName(tx(5, 2, null, "", false), bot, adName, "Перемещение", msOut));
        assertEquals("Связь", AdeskCheckService.adCatName(tx(6, 2, 55L, "Связь", false), bot, adName, "Перемещение", msOut),
                "ro'yxatda yo'q id — operatsiyadagi nom");
    }

    /** MoySklad'da statya qayta nomlansa bot bitta Adesk statyasiga ikki nom bilan bog'lanadi — MoySklad'dagi nom tanlanadi. */
    @Test
    void categoryLinkedUnderTwoNamesPicksTheOneMoySkladUses() {
        Map<Long, List<String>> bot = AdeskCheckService.botCategoryNames(List.of(
                cat("out:Коммунальные", 31, AdeskLink.OK),
                cat("out:Коммунальные (свет, вода, газ)", 31, AdeskLink.OK)));
        Map<Long, String> adName = Map.of(31L, "Коммунальные (свет, вода, газ)");
        assertEquals("Коммунальные (свет, вода, газ)", AdeskCheckService.adCatName(tx(1, 2, 31L, "", false), bot, adName, "Перемещение",
                Set.of("Коммунальные (свет, вода, газ)")), "Adesk nomi MoySklad'da bor — o'zgarmaydi");
        assertEquals("Коммунальные", AdeskCheckService.adCatName(tx(2, 2, 31L, "", false), bot, Map.of(31L, "Свет"), "Перемещение",
                Set.of("Коммунальные")));
    }
}
