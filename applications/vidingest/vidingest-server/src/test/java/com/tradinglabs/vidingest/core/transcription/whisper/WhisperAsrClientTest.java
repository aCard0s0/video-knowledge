package com.tradinglabs.vidingest.core.transcription.whisper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.core.transcription.dto.WhisperAsrResult;
import com.tradinglabs.vidingest.core.transcription.dto.WhisperSegment;
import com.tradinglabs.vidingest.core.transcription.service.TranscriptionFailureException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class WhisperAsrClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void transcribeToJsonParsesResponseAndEmitsProgressLogs(CapturedOutput output) throws Exception {
        startServer(200, """
                {
                  "text": "hello world",
                  "language": "en",
                  "segments": [
                    { "start": 0.0, "end": 1.0, "text": "hello" },
                    { "start": 1.0, "end": 2.0, "text": "world" }
                  ]
                }
                """);

        WhisperAsrClient client = new WhisperAsrClient(new ObjectMapper(), restClient(baseUrl()));
        Path wav = Files.createTempFile("vidingest-test", ".wav");
        Files.write(wav, "fake".getBytes(StandardCharsets.UTF_8));

        WhisperAsrResult result = client.transcribeToJson(wav);

        assertThat(result.language()).isEqualTo("en");
        assertThat(result.text()).isEqualTo("hello world");
        assertThat(result.segments()).extracting(WhisperSegment::text).containsExactly("hello", "world");

        assertThat(output.getOut()).contains("Whisper ASR request start");
        assertThat(output.getOut()).contains("Whisper ASR response received");
    }

    @Test
    void transcribeToJsonThrowsTypedExceptionOnHttp500() throws Exception {
        startServer(500, "{\"detail\":\"boom\"}");

        WhisperAsrClient client = new WhisperAsrClient(new ObjectMapper(), restClient(baseUrl()));
        Path wav = Files.createTempFile("vidingest-test", ".wav");
        Files.write(wav, "fake".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> client.transcribeToJson(wav))
                .isInstanceOf(TranscriptionFailureException.class)
                .hasMessageContaining("Whisper returned HTTP 500")
                .hasMessageContaining("boom");
    }

    private void startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/asr", exchange -> handleAsr(exchange, status, body));
        server.start();
    }

    private static void handleAsr(HttpExchange exchange, int status, String body) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        // Consume request body (multipart). We don't need to parse it.
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

    private static RestClient restClient(String baseUrl) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Math.toIntExact(Duration.ofSeconds(2).toMillis()));
        rf.setReadTimeout(Math.toIntExact(Duration.ofSeconds(5).toMillis()));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(rf).build();
    }
}

