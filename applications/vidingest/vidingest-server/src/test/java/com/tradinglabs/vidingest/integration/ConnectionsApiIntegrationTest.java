package com.tradinglabs.vidingest.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tradinglabs.vidingest.api.connections.ConnectionName;
import com.tradinglabs.vidingest.config.FrameSamplingConfig;
import com.tradinglabs.vidingest.config.OcrConfig;
import com.tradinglabs.vidingest.config.VideoSearchConfig;
import com.tradinglabs.vidingest.connections.repo.ConnectionRepository;
import com.tradinglabs.vidingest.connections.service.ConnectionSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the part that cannot be unit-tested: that a write through the API reaches the live
 * {@code @ConfigurationProperties} bean the clients read, and that a reset puts back what the
 * environment configured.
 */
class ConnectionsApiIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConnectionSettingsService connectionSettingsService;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private VideoSearchConfig searchConfig;

    @Autowired
    private OcrConfig ocrConfig;

    @Autowired
    private FrameSamplingConfig frameSamplingConfig;

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * The config beans are context-wide singletons, so an override left behind here would repoint
     * another test's embeddings client. Reset rather than just deleting rows: deleting the row
     * alone leaves the bean holding the overridden value.
     */
    @AfterEach
    void restoreConnections() {
        for (ConnectionName name : ConnectionName.values()) {
            connectionSettingsService.reset(name);
        }
    }

    @Test
    void listsEveryConnectionAndNeverReturnsAnApiKey() throws Exception {
        HttpResponse<String> response = send(request("").GET().build());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(ConnectionName.values().length);
        assertThat(response.body()).doesNotContain("apiKey");

        JsonNode embeddings = body.get(0);
        assertThat(embeddings.get("name").asText()).isEqualTo("EMBEDDINGS");
        // From BaseVidingestIntegrationTest's property overrides.
        assertThat(embeddings.get("provider").asText()).isEqualTo("disabled");
        assertThat(embeddings.get("overridden").asBoolean()).isFalse();
        assertThat(embeddings.get("supportedProviders")).isNotEmpty();
    }

    @Test
    void updateReachesTheLiveConfigBeanAndResetPutsTheDefaultBack() throws Exception {
        String original = ocrConfig.getBaseUrl();

        HttpResponse<String> updated = send(request("/OCR")
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"provider": "paddleocr", "baseUrl": "http://ocr.example.internal:8002", "enabled": true}
                        """))
                .header("Content-Type", "application/json")
                .build());

        assertThat(updated.statusCode()).isEqualTo(200);
        // The point of the whole feature: no restart, no context refresh.
        assertThat(ocrConfig.getBaseUrl()).isEqualTo("http://ocr.example.internal:8002");
        assertThat(ocrConfig.isEnabled()).isTrue();
        assertThat(objectMapper.readTree(updated.body()).get("overridden").asBoolean()).isTrue();
        assertThat(connectionRepository.findById(ConnectionName.OCR)).isPresent();

        HttpResponse<String> reset = send(request("/OCR").DELETE().build());

        assertThat(reset.statusCode()).isEqualTo(204);
        assertThat(ocrConfig.getBaseUrl()).isEqualTo(original);
        assertThat(connectionRepository.findById(ConnectionName.OCR)).isEmpty();
    }

    @Test
    void switchingTheEmbeddingsProviderWritesTheMatchingBaseUrlField() throws Exception {
        // The two providers keep their base URLs in different fields, because they are different
        // URL shapes. The API has one baseUrl, so the provider has to decide where it lands.
        send(request("/EMBEDDINGS")
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"provider": "ollama", "baseUrl": "http://ollama.example.internal:11434", "model": "nomic-embed-text"}
                        """))
                .header("Content-Type", "application/json")
                .build());

        assertThat(searchConfig.getEmbeddings().getOllama().getBaseUrl())
                .isEqualTo("http://ollama.example.internal:11434");
        assertThat(searchConfig.getEmbeddings().getOllama().getEmbedModel()).isEqualTo("nomic-embed-text");

        send(request("/EMBEDDINGS")
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"provider": "openai-compatible", "baseUrl": "http://mlx.example.internal:8000/v1", "model": "bge-m3"}
                        """))
                .header("Content-Type", "application/json")
                .build());

        assertThat(searchConfig.getEmbeddings().getBaseUrl()).isEqualTo("http://mlx.example.internal:8000/v1");
        assertThat(searchConfig.getEmbeddings().getModel()).isEqualTo("bge-m3");
    }

    @Test
    void anApiKeyIsStoredReportedAsAFlagAndKeptAcrossAnUpdateThatOmitsIt() throws Exception {
        send(request("/KNOWLEDGE")
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"provider": "openai-compatible", "baseUrl": "http://llm.example.internal:8000/v1",
                         "model": "qwen", "apiKey": "sk-secret-value"}
                        """))
                .header("Content-Type", "application/json")
                .build());

        HttpResponse<String> get = send(request("/KNOWLEDGE").GET().build());
        assertThat(get.body()).doesNotContain("sk-secret-value");
        assertThat(objectMapper.readTree(get.body()).get("hasApiKey").asBoolean()).isTrue();

        // Omitting apiKey must keep it — the console can never read it back, so without this it
        // could not save any other field without wiping the key.
        send(request("/KNOWLEDGE")
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"provider": "openai-compatible", "baseUrl": "http://other.example.internal:8000/v1"}
                        """))
                .header("Content-Type", "application/json")
                .build());

        assertThat(objectMapper.readTree(send(request("/KNOWLEDGE").GET().build()).body())
                .get("hasApiKey").asBoolean()).isTrue();

        // An explicit empty string clears it.
        send(request("/KNOWLEDGE")
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"provider": "openai-compatible", "baseUrl": "http://other.example.internal:8000/v1", "apiKey": ""}
                        """))
                .header("Content-Type", "application/json")
                .build());

        assertThat(objectMapper.readTree(send(request("/KNOWLEDGE").GET().build()).body())
                .get("hasApiKey").asBoolean()).isFalse();
    }

    @Test
    void rejectsAnUnknownNameAnUnsupportedProviderAndAMalformedBaseUrl() throws Exception {
        assertThat(send(request("/NOT_A_CONNECTION").GET().build()).statusCode()).isEqualTo(400);

        assertThat(send(request("/OCR")
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"provider": "tesseract", "baseUrl": "http://localhost:8002"}
                        """))
                .header("Content-Type", "application/json")
                .build()).statusCode()).isEqualTo(400);

        assertThat(send(request("/OCR")
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"provider": "paddleocr", "baseUrl": "localhost:8002"}
                        """))
                .header("Content-Type", "application/json")
                .build()).statusCode()).isEqualTo(400);

        // Neither rejection may leave the live bean holding a value the database does not have.
        assertThat(connectionRepository.findById(ConnectionName.OCR)).isEmpty();
    }

    /**
     * FRAME_SAMPLE is the one connection with no connection. It is on this API because OCR's
     * toggle is a trap without it — {@code OcrPhase} needs frames and has no way to produce them —
     * and the whole point is that it takes no base URL anywhere: not in the request, not in the
     * column, not in the probe.
     */
    @Test
    void theFrameSamplingToggleIsSavedWithNoBaseUrlAtAll() throws Exception {
        boolean original = frameSamplingConfig.isEnabled();

        HttpResponse<String> updated = send(request("/FRAME_SAMPLE")
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"provider": "ffmpeg", "enabled": true}
                        """))
                .header("Content-Type", "application/json")
                .build());

        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(frameSamplingConfig.isEnabled()).isTrue();

        JsonNode body = objectMapper.readTree(updated.body());
        // What tells the console not to render a base-URL box or a test button.
        assertThat(body.get("supportsBaseUrl").asBoolean()).isFalse();
        assertThat(body.get("supportsModel").asBoolean()).isFalse();
        assertThat(body.get("supportsEnabled").asBoolean()).isTrue();
        assertThat(body.get("baseUrl").isNull()).isTrue();
        assertThat(connectionRepository.findById(ConnectionName.FRAME_SAMPLE)
                .orElseThrow().getBaseUrl()).isNull();

        // Probing it is not an error and not "unreachable" — there is nothing there to reach.
        JsonNode probe = objectMapper.readTree(send(request("/FRAME_SAMPLE/test")
                .POST(HttpRequest.BodyPublishers.noBody()).build()).body());
        assertThat(probe.get("reachable").asBoolean()).isFalse();
        assertThat(probe.get("error").asText()).contains("no endpoint");

        assertThat(send(request("/FRAME_SAMPLE").DELETE().build()).statusCode()).isEqualTo(204);
        assertThat(frameSamplingConfig.isEnabled()).isEqualTo(original);
    }

    /** Dropping @NotBlank for FRAME_SAMPLE must not let a real connection lose its endpoint. */
    @Test
    void aConnectionThatIsReachedOverHttpStillCannotBeSavedWithoutOne() throws Exception {
        assertThat(send(request("/OCR")
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"provider": "paddleocr", "enabled": true}
                        """))
                .header("Content-Type", "application/json")
                .build()).statusCode()).isEqualTo(400);

        assertThat(connectionRepository.findById(ConnectionName.OCR)).isEmpty();
    }

    @Test
    void probingAnUnreachableHostAnswers200WithTheReason() throws Exception {
        // Port 1 on loopback: nothing listens, and the connect fails fast.
        send(request("/DIARIZATION")
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"provider": "diarize-asr", "baseUrl": "http://127.0.0.1:1", "enabled": true}
                        """))
                .header("Content-Type", "application/json")
                .build());

        HttpResponse<String> probe = send(request("/DIARIZATION/test")
                .POST(HttpRequest.BodyPublishers.noBody()).build());

        assertThat(probe.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(probe.body());
        assertThat(body.get("reachable").asBoolean()).isFalse();
        assertThat(body.get("error").asText()).isNotBlank();
        assertThat(body.get("probedUrl").asText()).isEqualTo("http://127.0.0.1:1/health");
    }

    /**
     * {@code openai} is a saveable value on exactly the three connections that reach a model
     * runtime. The routers have always understood the string — this pins the validation gate that
     * used to reject it, which is the whole of what stopped an operator pointing a card at
     * api.openai.com.
     */
    @Test
    void theOpenAiProviderIsAcceptedOnEveryLlmConnectionAndNowhereElse() throws Exception {
        record Case(String name, String model) {
        }
        for (Case c : new Case[]{
                new Case("KNOWLEDGE", "gpt-4.1"),
                new Case("EMBEDDINGS", "text-embedding-3-small"),
                new Case("TRANSCRIPTION", "whisper-1")}) {

            HttpResponse<String> saved = send(request("/" + c.name())
                    .PUT(HttpRequest.BodyPublishers.ofString("""
                            {"provider": "openai", "baseUrl": "https://api.openai.com/v1",
                             "model": "%s", "apiKey": "sk-test", "enabled": true}
                            """.formatted(c.model())))
                    .header("Content-Type", "application/json")
                    .build());

            assertThat(saved.statusCode()).as(c.name()).isEqualTo(200);
            JsonNode body = objectMapper.readTree(saved.body());
            assertThat(body.get("provider").asText()).isEqualTo("openai");
            assertThat(body.get("model").asText()).isEqualTo(c.model());
            // Served, never mirrored client-side — this is what puts the option in the dropdown.
            assertThat(body.get("supportedProviders").toString()).contains("\"openai\"");
        }

        // The sidecars speak one protocol each and must not gain the value.
        assertThat(send(request("/OCR")
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"provider": "openai", "baseUrl": "https://api.openai.com/v1"}
                        """))
                .header("Content-Type", "application/json")
                .build()).statusCode()).isEqualTo(400);
    }

    /**
     * The probe has to send the key. Without it "test" answers 401 against every hosted endpoint
     * while the phase itself works — a button that fails when the connection is fine is a worse
     * signal than no button at all.
     */
    @Test
    void theProbeSendsTheStoredApiKey() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/models", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] payload = "{\"data\": [{\"id\": \"gpt-4.1\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (var out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        stub.start();
        try {
            send(request("/KNOWLEDGE")
                    .PUT(HttpRequest.BodyPublishers.ofString("""
                            {"provider": "openai", "baseUrl": "http://127.0.0.1:%d",
                             "model": "gpt-4.1", "apiKey": "sk-probe-key", "enabled": true}
                            """.formatted(stub.getAddress().getPort())))
                    .header("Content-Type", "application/json")
                    .build());

            JsonNode probe = objectMapper.readTree(send(request("/KNOWLEDGE/test")
                    .POST(HttpRequest.BodyPublishers.noBody()).build()).body());

            assertThat(probe.get("reachable").asBoolean()).isTrue();
            assertThat(auth.get()).isEqualTo("Bearer sk-probe-key");
        } finally {
            stub.stop(0);
        }
    }

    private HttpRequest.Builder request(String suffix) {
        return HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/vidingest/api/v1/connections" + suffix));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
