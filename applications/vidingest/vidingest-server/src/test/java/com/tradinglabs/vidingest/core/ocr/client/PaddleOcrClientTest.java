package com.tradinglabs.vidingest.core.ocr.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tradinglabs.vidingest.config.OcrConfig;
import com.tradinglabs.vidingest.core.ocr.dto.OcrLine;
import com.tradinglabs.vidingest.core.ocr.dto.OcrPageResult;
import com.tradinglabs.vidingest.core.ocr.service.OcrFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Mirrors {@code WhisperAsrClientTest} / {@code DiarizationClientTest}: spins up a tiny JDK
 * {@link HttpServer} that impersonates the paddleocr-server sidecar and asserts the client
 * parses canned responses and surfaces transport errors as {@link OcrFailureException}.
 */
@ExtendWith(OutputCaptureExtension.class)
class PaddleOcrClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void ocrParsesResponseWithBboxAndConfidence(CapturedOutput output) throws Exception {
        startServer(200, """
                {
                  "lines": [
                    {
                      "text": "Hello world",
                      "confidence": 0.94,
                      "bbox": [[10, 20], [110, 20], [110, 40], [10, 40]],
                      "language": "en"
                    },
                    {
                      "text": "Subtitle line",
                      "confidence": 0.71,
                      "bbox": [[5, 100], [200, 100], [200, 130], [5, 130]]
                    }
                  ]
                }
                """);

        PaddleOcrClient client = new PaddleOcrClient(new ObjectMapper(), config(), restClient(baseUrl()));
        Path jpg = Files.createTempFile("vidingest-ocr-test", ".jpg");
        Files.write(jpg, fakeJpeg());

        OcrPageResult result = client.ocr(jpg);

        assertThat(result.lines()).hasSize(2);
        OcrLine first = result.lines().get(0);
        assertThat(first.text()).isEqualTo("Hello world");
        assertThat(first.confidence()).isCloseTo(0.94f, within(1e-3f));
        assertThat(first.language()).isEqualTo("en");
        assertThat(first.bbox()).containsExactly(
                List.of(10.0, 20.0),
                List.of(110.0, 20.0),
                List.of(110.0, 40.0),
                List.of(10.0, 40.0)
        );

        // Second line: no language in response — should fall back to config default ("en").
        OcrLine second = result.lines().get(1);
        assertThat(second.text()).isEqualTo("Subtitle line");
        assertThat(second.language()).isEqualTo("en");
        // Per-frame OCR logs are DEBUG (not INFO) so production logs stay quiet at scale —
        // CapturedOutput sees the test logback config which suppresses DEBUG by default, so
        // we don't assert on log content here. Coverage of the log paths is exercised by
        // the start/end debug calls being reachable without throwing.
    }

    @Test
    void ocrPassesLanguageQueryParam() throws Exception {
        AtomicReference<String> capturedQuery = new AtomicReference<>();
        startServerWithQueryCapture(200, "{\"lines\": []}", capturedQuery);

        OcrConfig cfg = config();
        cfg.setLanguages(List.of("fr"));
        PaddleOcrClient client = new PaddleOcrClient(new ObjectMapper(), cfg, restClient(baseUrl()));
        Path jpg = Files.createTempFile("vidingest-ocr-test", ".jpg");
        Files.write(jpg, fakeJpeg());

        client.ocr(jpg);

        assertThat(capturedQuery.get()).contains("lang=fr");
    }

    @Test
    void ocrThrowsTypedExceptionOnHttp500() throws Exception {
        startServer(500, "{\"detail\":\"engine load failed\"}");

        PaddleOcrClient client = new PaddleOcrClient(new ObjectMapper(), config(), restClient(baseUrl()));
        Path jpg = Files.createTempFile("vidingest-ocr-test", ".jpg");
        Files.write(jpg, fakeJpeg());

        assertThatThrownBy(() -> client.ocr(jpg))
                .isInstanceOf(OcrFailureException.class)
                .hasMessageContaining("PaddleOCR sidecar returned HTTP 500")
                .hasMessageContaining("engine load failed");
    }

    @Test
    void ocrThrowsTypedExceptionOnMalformedJson() throws Exception {
        startServer(200, "not-json{");

        PaddleOcrClient client = new PaddleOcrClient(new ObjectMapper(), config(), restClient(baseUrl()));
        Path jpg = Files.createTempFile("vidingest-ocr-test", ".jpg");
        Files.write(jpg, fakeJpeg());

        assertThatThrownBy(() -> client.ocr(jpg))
                .isInstanceOf(OcrFailureException.class)
                .hasMessageContaining("Failed to parse OCR JSON response");
    }

    @Test
    void ocrRejectsMissingImageFile() {
        PaddleOcrClient client = new PaddleOcrClient(new ObjectMapper(), config(), restClient("http://localhost:1"));
        Path doesNotExist = Path.of("/tmp/vidingest/this-file-does-not-exist.jpg");

        assertThatThrownBy(() -> client.ocr(doesNotExist))
                .isInstanceOf(OcrFailureException.class)
                .hasMessageContaining("Image file does not exist");
    }

    @Test
    void ocrSkipsLinesWithBlankTextAndDropsBboxOnNonNumericCoords() throws Exception {
        // PaddleOCR occasionally emits empty strings or junk bbox coords; the client filters
        // blank text entirely and nulls bbox when any coordinate is non-numeric. We don't
        // enforce a corner count — quad/poly variants both parse fine.
        startServer(200, """
                {
                  "lines": [
                    { "text": "",       "confidence": 0.9,  "bbox": [[0,0],[1,0],[1,1],[0,1]] },
                    { "text": "  ",     "confidence": 0.9 },
                    { "text": "twoCorners", "confidence": 0.8, "bbox": [[0,0],[1,0]] },
                    { "text": "fourCorners","confidence": 0.7, "bbox": [[0,0],[1,0],[1,1],[0,1]] },
                    { "text": "badCoord",   "confidence": 0.6, "bbox": [[0,0],[1,0],["x",1],[0,1]] }
                  ]
                }
                """);

        PaddleOcrClient client = new PaddleOcrClient(new ObjectMapper(), config(), restClient(baseUrl()));
        Path jpg = Files.createTempFile("vidingest-ocr-test", ".jpg");
        Files.write(jpg, fakeJpeg());

        OcrPageResult result = client.ocr(jpg);

        // Blank/whitespace text is filtered out; everything else survives.
        assertThat(result.lines()).extracting(OcrLine::text)
                .containsExactly("twoCorners", "fourCorners", "badCoord");

        // 2-corner bbox is parsed as-is (we don't impose a length constraint).
        assertThat(result.lines().get(0).bbox()).containsExactly(
                List.of(0.0, 0.0),
                List.of(1.0, 0.0)
        );

        // 4-corner bbox round-trips.
        assertThat(result.lines().get(1).bbox()).containsExactly(
                List.of(0.0, 0.0),
                List.of(1.0, 0.0),
                List.of(1.0, 1.0),
                List.of(0.0, 1.0)
        );

        // Non-numeric coordinate → entire bbox dropped to null, but text is still kept.
        assertThat(result.lines().get(2).bbox()).isNull();
    }

    private void startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ocr", exchange -> handleOcr(exchange, status, body, null));
        server.start();
    }

    private void startServerWithQueryCapture(int status, String body, AtomicReference<String> capture) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ocr", exchange -> handleOcr(exchange, status, body, capture));
        server.start();
    }

    private static void handleOcr(HttpExchange exchange, int status, String body, AtomicReference<String> capture) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        if (capture != null) {
            capture.set(exchange.getRequestURI().getQuery());
        }
        // Drain multipart body; we don't parse it.
        exchange.getRequestBody().readAllBytes();

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static OcrConfig config() {
        OcrConfig cfg = new OcrConfig();
        cfg.setLanguages(List.of("en"));
        cfg.setMinConfidence(0.0);  // client-side parsing doesn't filter — that's OcrService's job
        return cfg;
    }

    private static RestClient restClient(String baseUrl) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Math.toIntExact(Duration.ofSeconds(2).toMillis()));
        rf.setReadTimeout(Math.toIntExact(Duration.ofSeconds(5).toMillis()));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(rf).build();
    }

    private static byte[] fakeJpeg() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    }
}
