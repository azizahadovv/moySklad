package uz.kassa.service.adesk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdeskClientTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** Sahifa: [from, to) oralig'idagi id'lar, recordsFiltered = total. */
    private static ObjectNode page(int from, int to, int total) {
        ObjectNode j = OM.createObjectNode();
        ArrayNode arr = j.putArray("transactions");
        for (int id = from; id < to; id++) {
            ObjectNode t = arr.addObject();
            t.put("id", id);
            t.put("type", 1);
            t.put("amount", "1.00");
            t.put("dateIso", "2026-10-09");
        }
        j.put("recordsFiltered", total);
        return j;
    }

    /** 2026-10-09: Adesk perevod juftini bitta sahifaga jamlaydi — sahifalar 99/101/52; birinchi «qisqa» sahifada to'xtamaslik kerak. */
    @Test
    void shortPageIsNotTreatedAsLastWhenTotalIsKnown() {
        AdeskHttp http = mock(AdeskHttp.class);
        when(http.get(eq("transactions"), anyMap())).thenAnswer(inv -> {
            Map<String, String> p = inv.getArgument(1);
            return switch (p.get("start")) {
                case "0" -> page(0, 99, 252);
                case "100" -> page(99, 200, 252);
                case "200" -> page(200, 252, 252);
                default -> page(0, 0, 252);
            };
        });
        List<AdeskClient.AdTx> txs = new AdeskClient(http).transactions(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 9));
        assertEquals(252, txs.size());
        assertEquals(252, txs.stream().map(AdeskClient.AdTx::id).distinct().count());
        verify(http, times(3)).get(eq("transactions"), anyMap());
    }

    /** Bir kunda bir xil summali ikki perevod: juft Adesk «pairedTransactionId» bo'yicha topiladi, sana+summa bo'yicha adashmaydi. */
    @Test
    void transferLegsArePairedByAdeskPairedId() {
        AdeskHttp http = mock(AdeskHttp.class);
        when(http.get(eq("transactions"), anyMap())).thenAnswer(inv -> {
            ObjectNode j = OM.createObjectNode();
            ArrayNode arr = j.putArray("transactions");
            long[][] legs = {{11, 2, 22}, {12, 2, 21}, {21, 1, 12}, {22, 1, 11}};   // id, type, pairedTransactionId
            for (long[] l : legs) {
                ObjectNode t = arr.addObject();
                t.put("id", l[0]); t.put("type", l[1]); t.put("amount", "100000.00"); t.put("dateIso", "2026-10-09");
                t.put("isTransfer", true); t.put("pairedTransactionId", l[2]);
            }
            j.put("recordsFiltered", 4);
            if (!"0".equals(((Map<?, ?>) inv.getArgument(1)).get("start"))) arr.removeAll();
            return j;
        });
        List<AdeskClient.AdTx> txs = new AdeskClient(http).transactions(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 9));
        assertEquals(22L, txs.get(0).pairedId());
        List<AdeskClient.AdTx> ins = txs.stream().filter(AdeskClient.AdTx::income).toList();
        assertEquals(22L, AdeskReverseService.pairOf(txs.get(0), ins).id());
        assertEquals(21L, AdeskReverseService.pairOf(txs.get(1), ins).id());

        // juft id'siz (eski javob) — sana + summa bo'yicha
        AdeskClient.AdTx out = new AdeskClient.AdTx(1, 2, new java.math.BigDecimal("5.00"), LocalDate.of(2026, 10, 9), 1L, null, null, "", true, false, "", "", "", null, "");
        AdeskClient.AdTx in = new AdeskClient.AdTx(2, 1, new java.math.BigDecimal("5.00"), LocalDate.of(2026, 10, 9), 2L, null, null, "", true, false, "", "", "", null, "");
        assertEquals(2L, AdeskReverseService.pairOf(out, List.of(in)).id());
    }

    @Test
    void reallyIncompleteListStillFails() {
        AdeskHttp http = mock(AdeskHttp.class);
        when(http.get(eq("transactions"), anyMap())).thenAnswer(inv ->
                "0".equals(((Map<?, ?>) inv.getArgument(1)).get("start")) ? page(0, 99, 2952) : page(0, 0, 2952));
        AdeskClient c = new AdeskClient(http);
        assertThrows(IllegalStateException.class, () -> c.transactions(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 10, 9)));
    }
}
