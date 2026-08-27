package com.tradinglabs.vidingest.core.knowledge.client.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.config.KnowledgeExtractionConfig;
import com.tradinglabs.vidingest.core.knowledge.dto.KnowledgeUnitDraft;
import com.tradinglabs.vidingest.core.knowledge.service.KnowledgeExtractionFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Mirrors the JDK-{@code HttpServer}-based pattern from
 * {@code DiarizationClientTest} / {@code PaddleOcrClientTest}. We stand up a fake Ollama
 * {@code /api/chat} endpoint and assert the client builds the right request body, parses
 * a strict-JSON response into typed drafts, and surfaces transport / parse errors as
 * {@link KnowledgeExtractionFailureException}.
 */
class OllamaKnowledgeChatClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void extractParsesUnitsWrapperResponse() throws Exception {
        // Inner content is itself a JSON string (Ollama with format=json) — escape carefully.
        startServer(200, """
                {
                  "model": "qwen2.5",
                  "message": {
                    "role": "assistant",
                    "content": "{\\"units\\": [\\n  {\\"type\\": \\"ENTITY\\", \\"title\\": \\"Apple Inc.\\", \\"content\\": \\"Tech company headquartered in Cupertino\\", \\"salience\\": 0.92, \\"source_segment_indices\\": [0, 1], \\"start_seconds\\": 0.0, \\"end_seconds\\": 30.0, \\"entity_type\\": \\"ORGANIZATION\\"},\\n  {\\"type\\": \\"SUMMARY\\", \\"title\\": \\"Overview\\", \\"content\\": \\"The segment covers Apple's product strategy.\\", \\"salience\\": 0.7, \\"source_segment_indices\\": [0], \\"start_seconds\\": 0.0, \\"end_seconds\\": 30.0}\\n]}"
                  },
                  "done": true
                }
                """);

        OllamaKnowledgeChatClient client = new OllamaKnowledgeChatClient(new ObjectMapper(), config(), restClient(baseUrl()));

        List<KnowledgeUnitDraft> drafts = client.extract("system prompt", "user prompt");

        assertThat(drafts).hasSize(2);
        KnowledgeUnitDraft entity = drafts.get(0);
        assertThat(entity.type()).isEqualTo(KnowledgeUnitType.ENTITY);
        assertThat(entity.title()).isEqualTo("Apple Inc.");
        assertThat(entity.content()).contains("Cupertino");
        assertThat(entity.salience()).isCloseTo(0.92, within(1e-6));
        assertThat(entity.sourceSegmentIndices()).containsExactly(0, 1);
        assertThat(entity.startSeconds()).isCloseTo(0.0, within(1e-9));
        assertThat(entity.endSeconds()).isCloseTo(30.0, within(1e-9));
        assertThat(entity.entityType()).isEqualTo("ORGANIZATION");

        KnowledgeUnitDraft summary = drafts.get(1);
        assertThat(summary.type()).isEqualTo(KnowledgeUnitType.SUMMARY);
        assertThat(summary.entityType()).isNull();  // not provided → null in the draft
    }

    @Test
    void extractToleratesBareArrayResponseFromSmallerModels() throws Exception {
        // Some models drop the outer {"units": [...]} wrapper despite the prompt. The
        // client should accept a bare top-level array as a fallback.
        startServer(200, """
                {
                  "message": {
                    "content": "[{\\"type\\": \\"TOPIC\\", \\"content\\": \\"Cosmology\\", \\"salience\\": 0.5}]"
                  }
                }
                """);

        OllamaKnowledgeChatClient client = new OllamaKnowledgeChatClient(new ObjectMapper(), config(), restClient(baseUrl()));

        List<KnowledgeUnitDraft> drafts = client.extract("sys", "user");

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).type()).isEqualTo(KnowledgeUnitType.TOPIC);
        assertThat(drafts.get(0).content()).isEqualTo("Cosmology");
    }

    @Test
    void extractDropsUnitsWithUnknownTypeOrBlankContent() throws Exception {
        startServer(200, """
                {
                  "message": {
                    "content": "{\\"units\\": [\\n  {\\"type\\": \\"NONSENSE\\", \\"content\\": \\"bad type\\"},\\n  {\\"type\\": \\"ENTITY\\", \\"content\\": \\"   \\"},\\n  {\\"type\\": \\"ENTITY\\", \\"content\\": \\"kept\\", \\"salience\\": 0.4}\\n]}"
                  }
                }
                """);

        OllamaKnowledgeChatClient client = new OllamaKnowledgeChatClient(new ObjectMapper(), config(), restClient(baseUrl()));

        List<KnowledgeUnitDraft> drafts = client.extract("sys", "user");

        // Unknown type + blank content drop; only the third survives.
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).content()).isEqualTo("kept");
    }

    @Test
    void extractReturnsEmptyForBlankUserPromptWithoutCallingServer() throws Exception {
        // Use a port that wouldn't bind even if we tried; if the client reaches the
        // network we'd get a connect-refused. Empty user prompt should short-circuit.
        OllamaKnowledgeChatClient client = new OllamaKnowledgeChatClient(
                new ObjectMapper(), config(), restClient("http://localhost:1"));

        assertThat(client.extract("sys", "")).isEmpty();
        assertThat(client.extract("sys", null)).isEmpty();
        assertThat(client.extract("sys", "   ")).isEmpty();
    }

    @Test
    void extractThrowsOnHttp500() throws Exception {
        startServer(500, "{\"error\":\"model not found\"}");

        OllamaKnowledgeChatClient client = new OllamaKnowledgeChatClient(new ObjectMapper(), config(), restClient(baseUrl()));

        assertThatThrownBy(() -> client.extract("sys", "user"))
                .isInstanceOf(KnowledgeExtractionFailureException.class)
                .hasMessageContaining("Knowledge LLM returned HTTP 500")
                .hasMessageContaining("model not found");
    }

    @Test
    void extractReturnsEmptyWhenInnerContentIsUnparseable() throws Exception {
        // A response envelope with garbage inner content should NOT throw — it should log
        // and return empty so the batch is just dropped.
        startServer(200, """
                {
                  "message": {
                    "content": "not-json{"
                  }
                }
                """);

        OllamaKnowledgeChatClient client = new OllamaKnowledgeChatClient(new ObjectMapper(), config(), restClient(baseUrl()));

        assertThat(client.extract("sys", "user")).isEmpty();
    }

    @Test
    void extractThrowsWhenEnvelopeIsMissingMessageContent() throws Exception {
        startServer(200, "{\"model\":\"qwen2.5\",\"done\":true}");

        OllamaKnowledgeChatClient client = new OllamaKnowledgeChatClient(new ObjectMapper(), config(), restClient(baseUrl()));

        assertThatThrownBy(() -> client.extract("sys", "user"))
                .isInstanceOf(KnowledgeExtractionFailureException.class)
                .hasMessageContaining("missing message.content");
    }

    @Test
    void extractSendsExpectedRequestBody() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        startServerWithCapture(200, """
                {"message": {"content": "{\\"units\\": []}"}}
                """, captured);

        KnowledgeExtractionConfig cfg = config();
        cfg.setChatModel("qwen2.5:14b-instruct");
        cfg.setTemperature(0.1);
        cfg.setMaxOutputTokens(2048);
        OllamaKnowledgeChatClient client = new OllamaKnowledgeChatClient(new ObjectMapper(), cfg, restClient(baseUrl()));

        client.extract("system says hi", "user says hello");

        String body = captured.get();
        assertThat(body).contains("\"model\":\"qwen2.5:14b-instruct\"");
        assertThat(body).contains("\"stream\":false");
        assertThat(body).contains("\"format\":{");
        assertThat(body).contains("\"units\"");
        // Ollama options block carries our knobs.
        assertThat(body).contains("\"temperature\":0.1");
        assertThat(body).contains("\"num_predict\":2048");
        // Both messages present in order.
        assertThat(body).contains("system says hi");
        assertThat(body).contains("user says hello");
    }

    /**
     * A read timeout used to surface as "Error while extracting response for type [byte[]] and
     * content type [application/octet-stream]" — the message converter's complaint, not the
     * clock's, which is exactly the wrong place to send whoever is reading the failure.
     */
    @Test
    void readTimeoutSaysSoAndNamesTheProperty() throws Exception {
        // Accept the request, send headers, then never send the body: the client blocks on read.
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 512);   // promise 512 bytes, deliver none
        });
        server.start();

        KnowledgeExtractionConfig cfg = config();
        cfg.setReadTimeout(Duration.ofMillis(600));
        OllamaKnowledgeChatClient client = new OllamaKnowledgeChatClient(
                new ObjectMapper(), cfg, shortReadRestClient(baseUrl()));

        assertThatThrownBy(() -> client.extract("sys", "user"))
                .isInstanceOf(KnowledgeExtractionFailureException.class)
                .hasMessageContaining("timed out")
                .hasMessageContaining("vidingest.knowledge.read-timeout");
    }

    private static RestClient shortReadRestClient(String baseUrl) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(2000);
        rf.setReadTimeout(600);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(rf).build();
    }

    // ----- HTTP test scaffolding -----

    private void startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/chat", exchange -> handle(exchange, status, body, null));
        server.start();
    }

    private void startServerWithCapture(int status, String body, AtomicReference<String> capture) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/chat", exchange -> handle(exchange, status, body, capture));
        server.start();
    }

    private static void handle(HttpExchange exchange, int status, String body, AtomicReference<String> capture) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        byte[] reqBytes = exchange.getRequestBody().readAllBytes();
        if (capture != null) {
            capture.set(new String(reqBytes, StandardCharsets.UTF_8));
        }
        byte[] respBytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, respBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(respBytes);
        }
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static KnowledgeExtractionConfig config() {
        KnowledgeExtractionConfig cfg = new KnowledgeExtractionConfig();
        cfg.setChatModel("qwen2.5:14b-instruct");
        cfg.setTemperature(0.2);
        cfg.setMaxOutputTokens(4096);
        return cfg;
    }

    private static RestClient restClient(String baseUrl) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Math.toIntExact(Duration.ofSeconds(2).toMillis()));
        rf.setReadTimeout(Math.toIntExact(Duration.ofSeconds(5).toMillis()));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(rf).build();
    }
}
