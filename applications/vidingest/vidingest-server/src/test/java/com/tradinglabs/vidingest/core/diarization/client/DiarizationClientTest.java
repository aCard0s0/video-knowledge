package com.tradinglabs.vidingest.core.diarization.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tradinglabs.vidingest.config.DiarizationConfig;
import com.tradinglabs.vidingest.core.diarization.dto.DiarizationResult;
import com.tradinglabs.vidingest.core.diarization.dto.DiarizationSegment;
import com.tradinglabs.vidingest.core.diarization.service.DiarizationFailureException;
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

/**
 * Mirrors {@code WhisperAsrClientTest}: spins up a tiny JDK {@link HttpServer} that pretends
 * to be the diarize-asr sidecar and asserts the client parses canned responses and surfaces
 * transport errors as {@link DiarizationFailureException}. No external dependencies required.
 */
@ExtendWith(OutputCaptureExtension.class)
class DiarizationClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void diarizeParsesResponseAndEmitsProgressLogs(CapturedOutput output) throws Exception {
        startServer(200, """
                {
                  "segments": [
                    { "start": 0.0,  "end": 2.0, "speaker": "SPEAKER_00" },
                    { "start": 2.0,  "end": 4.5, "speaker": "SPEAKER_01" },
                    { "start": 4.5,  "end": 6.0, "speaker": "SPEAKER_00" }
                  ],
                  "speakers": [
                    { "label": "SPEAKER_00", "embedding": [0.1, 0.2, 0.3] },
                    { "label": "SPEAKER_01", "embedding": null }
                  ]
                }
                """);

        DiarizationClient client = new DiarizationClient(new ObjectMapper(), config(), restClient(baseUrl()));
        Path wav = Files.createTempFile("vidingest-diarize-test", ".wav");
        Files.write(wav, "fake".getBytes(StandardCharsets.UTF_8));

        DiarizationResult result = client.diarize(wav);

        assertThat(result.segments()).extracting(DiarizationSegment::speakerLabel)
                .containsExactly("SPEAKER_00", "SPEAKER_01", "SPEAKER_00");
        assertThat(result.segments().get(0).startSeconds()).isEqualTo(0.0f);
        assertThat(result.segments().get(0).endSeconds()).isEqualTo(2.0f);
        assertThat(result.speakers()).hasSize(2);
        assertThat(result.speakers().get(0).embeddingVoiceprint()).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(result.speakers().get(1).embeddingVoiceprint()).isNull();

        assertThat(output.getOut()).contains("Diarization request start");
        assertThat(output.getOut()).contains("Diarization response received");
    }

    @Test
    void diarizeThrowsTypedExceptionOnHttp500() throws Exception {
        startServer(500, "{\"detail\":\"hf token missing\"}");

        DiarizationClient client = new DiarizationClient(new ObjectMapper(), config(), restClient(baseUrl()));
        Path wav = Files.createTempFile("vidingest-diarize-test", ".wav");
        Files.write(wav, "fake".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> client.diarize(wav))
                .isInstanceOf(DiarizationFailureException.class)
                .hasMessageContaining("Diarization sidecar returned HTTP 500")
                .hasMessageContaining("hf token missing");
    }

    @Test
    void diarizeThrowsTypedExceptionOnInvalidResponseBody() throws Exception {
        startServer(200, "not-json{");

        DiarizationClient client = new DiarizationClient(new ObjectMapper(), config(), restClient(baseUrl()));
        Path wav = Files.createTempFile("vidingest-diarize-test", ".wav");
        Files.write(wav, "fake".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> client.diarize(wav))
                .isInstanceOf(DiarizationFailureException.class)
                .hasMessageContaining("Failed to parse diarization JSON response");
    }

    @Test
    void diarizeRejectsMissingAudioFile() {
        DiarizationClient client = new DiarizationClient(new ObjectMapper(), config(), restClient("http://localhost:1"));
        Path nonExistent = Path.of("/tmp/vidingest/this-file-does-not-exist.wav");

        assertThatThrownBy(() -> client.diarize(nonExistent))
                .isInstanceOf(DiarizationFailureException.class)
                .hasMessageContaining("Audio file does not exist");
    }

    private void startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/diarize", exchange -> handleDiarize(exchange, status, body));
        server.start();
    }

    private static void handleDiarize(HttpExchange exchange, int status, String body) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        // Drain multipart body; we don't actually parse it.
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

    private static DiarizationConfig config() {
        DiarizationConfig cfg = new DiarizationConfig();
        cfg.setMinSpeakers(null);
        cfg.setMaxSpeakers(6);
        return cfg;
    }

    private static RestClient restClient(String baseUrl) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Math.toIntExact(Duration.ofSeconds(2).toMillis()));
        rf.setReadTimeout(Math.toIntExact(Duration.ofSeconds(5).toMillis()));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(rf).build();
    }
}
