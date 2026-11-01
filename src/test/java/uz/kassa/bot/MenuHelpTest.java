package uz.kassa.bot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** ℹ️ Menyu izohlari: har erkin menyu tugmasining izohi bor — kalit (emoji/harf) kanonik nomga aynan mos bo'lishi kerak. */
class MenuHelpTest {

    @Test
    void everyFreeMenuButtonHasHelpLine() {
        for (String ctx : List.of("stat", "sozlash", "soz.tashkilot", "soz.moliya", "soz.moysklad", "soz.interfeys")) {
            List<String> labels = MenuSchemaService.MENUS.get(ctx).defaults();
            String block = MenuHelp.block(ctx, labels, Function.identity());
            for (String l : labels)
                assertTrue(block.contains("• " + TextUtil.esc(l) + " — "), ctx + ": izoh yo'q — " + l);
        }
    }

    @Test
    void contextMenusHaveHelpAndUnknownContextIsEmpty() {
        assertTrue(MenuHelp.block("kassa", List.of("💵 Топширилмаган пул"), Function.identity()).contains("pul qabul"));
        assertTrue(MenuHelp.block("kassam", List.of("💰 Бугунги тушум"), Function.identity()).contains("bir xil"));
        assertEquals("", MenuHelp.block("yoq", List.of("💸 Расход"), Function.identity()));
    }
}
