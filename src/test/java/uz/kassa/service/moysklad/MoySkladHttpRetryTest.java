package uz.kassa.service.moysklad;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import uz.kassa.config.AppProps;
import uz.kassa.service.SettingsService;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 2026-11-04: MoySklad 503 bir daqiqalik uzilishda butun sinxronni yiqitardi (2 hafta ichida 85 marta): o'qish so'rovi qaytariladi. */
class MoySkladHttpRetryTest {

    HttpClient client;
    MoySkladHttp http;

    @BeforeEach
    void setUp() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.get("moysklad.token")).thenReturn(Optional.of("tok"));
        http = new MoySkladHttp(mock(AppProps.class), settings);
        client = mock(HttpClient.class);
        ReflectionTestUtils.setField(http, "http", client);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> resp(int code, String body) {
        HttpResponse<byte[]> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(code);
        when(r.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(r.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    void getIsRetriedAfterServiceUnavailable() throws Exception {
        HttpResponse<byte[]> bad = resp(503, "<html>503</html>"), good = resp(200, "{\"a\":1}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(bad, good);
        assertEquals(1, http.getJson("https://ms.test/entity/demand").path("a").asInt());
        verify(client, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    /** Yaratish so'rovi serverga yetgan bo'lishi mumkin — qaytarilsa MoySklad'da dublikat hujjat bo'ladi. */
    @Test
    @SuppressWarnings("unchecked")
    void postIsNeverRetried() throws Exception {
        HttpResponse<byte[]> bad = resp(503, "<html>503</html>");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(bad);
        assertThrows(IllegalStateException.class, () -> http.postJson("https://ms.test/entity/cashin", "{}"));
        verify(client, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
}
