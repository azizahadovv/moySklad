package uz.kassa.bot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-04: ⚖️ kunlik hisobot 4096 dan oshib har kuni 21:30 da 3 SuperAdmin'ga yetmay qolardi (MESSAGE_TOO_LONG). */
class TextUtilSplitTest {

    @Test
    void shortTextIsReturnedAsIs() {
        assertEquals(List.of("<b>Salom</b>"), TextUtil.splitHtml("<b>Salom</b>", 4000));
    }

    @Test
    void longTextIsSplitAtLineBoundariesWithoutLosingAnyLine() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) sb.append("Qator ").append(i).append(" · 12 000 so'm\n");
        List<String> parts = TextUtil.splitHtml(sb.toString().trim(), 1000);
        assertTrue(parts.size() > 1);
        assertTrue(parts.stream().allMatch(p -> p.length() <= 1000));
        assertEquals(sb.toString().trim(), String.join("\n", parts));
    }

    /** Teg bir necha qator davom etsa, har bo'lak o'z-o'zicha to'g'ri HTML bo'lsin — aks holda Telegram 400 beradi. */
    @Test
    void tagOpenAcrossBoundaryIsClosedAndReopened() {
        String text = "<i>birinchi qator\nikkinchi qator\nuchinchi qator</i>";
        List<String> parts = TextUtil.splitHtml(text, 25);
        assertTrue(parts.size() > 1);
        for (String p : parts) {
            assertEquals(count(p, "<i>"), count(p, "</i>"), p);
            assertTrue(p.length() <= 25, p);
        }
        assertEquals("birinchi qator\nikkinchi qator\nuchinchi qator", String.join("\n", parts).replace("<i>", "").replace("</i>", ""));
    }

    /** Bitta juda uzun qator ham limitdan oshmasin va HTML entity o'rtasidan kesilmasin. */
    @Test
    void singleOverlongLineIsHardCutOutsideEntities() {
        String text = "a".repeat(29) + "&amp;" + "b".repeat(40);
        List<String> parts = TextUtil.splitHtml(text, 30);
        assertTrue(parts.stream().allMatch(p -> p.length() <= 30));
        assertEquals(text, String.join("", parts));
        assertTrue(parts.stream().noneMatch(p -> p.endsWith("&") || p.endsWith("&a") || p.endsWith("&am") || p.endsWith("&amp")));
    }

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) n++;
        return n;
    }
}
