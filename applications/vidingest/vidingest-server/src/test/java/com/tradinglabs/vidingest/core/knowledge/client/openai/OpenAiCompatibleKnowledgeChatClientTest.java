package com.tradinglabs.vidingest.core.knowledge.client.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.config.KnowledgeExtractionConfig;
import com.tradinglabs.vidingest.core.knowledge.client.KnowledgeChatClientTestBase;
import com.tradinglabs.vidingest.core.knowledge.dto.KnowledgeUnitDraft;
import com.tradinglabs.vidingest.core.knowledge.service.KnowledgeExtractionFailureException;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Same JDK-{@code HttpServer} pattern as {@code OllamaKnowledgeChatClientTest}, against a fake
 * OpenAI-compatible {@code /chat/completions} endpoint — the shape LM Studio,
 * {@code llama-server}, mlx-lm and vLLM all serve.
 *
 * <p>What is worth asserting here is only what differs from the Ollama client: the URI, the
 * {@code choices[0].message.content} envelope, {@code max_tokens} instead of
 * {@code options.num_predict}, the {@code response_format.json_schema} wrapper, and the bearer
 * header. The unit-level parsing is shared through {@code KnowledgeUnitJson}, so one
 * alternate-root-key case is enough to prove the fallbacks are actually wired in on this path
 * rather than tested only on the other one.
 */
class OpenAiCompatibleKnowledgeChatClientTest extends KnowledgeChatClientTestBase {

    private static final String EMPTY_UNITS = "{\"choices\": [{\"message\": {\"content\": \"{\\\"units\\\": []}\"}}]}";

    @Override
    protected String contextPath() {
        return "/chat/completions";
    }

    @Test
    void extractParsesUnitsOutOfChoicesEnvelope() throws Exception {
        startServer(200, """
                {
                  "id": "chatcmpl-1",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "{\\"units\\": [\\n  {\\"type\\": \\"ENTITY\\", \\"title\\": \\"Apple Inc.\\", \\"content\\": \\"Tech company headquartered in Cupertino\\", \\"salience\\": 0.92, \\"source_segment_indices\\": [0, 1], \\"start_seconds\\": 0.0, \\"end_seconds\\": 30.0, \\"entity_type\\": \\"ORGANIZATION\\"},\\n  {\\"type\\": \\"SUMMARY\\", \\"title\\": \\"Overview\\", \\"content\\": \\"The segment covers Apple's product strategy.\\", \\"salience\\": 0.7}\\n]}"
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
                """);

        OpenAiCompatibleKnowledgeChatClient client =
                new OpenAiCompatibleKnowledgeChatClient(new ObjectMapper(), config(baseUrl()), restClient());

        List<KnowledgeUnitDraft> drafts = client.extract("system prompt", "user prompt");

        assertThat(drafts).hasSize(2);
        KnowledgeUnitDraft entity = drafts.get(0);
        assertThat(entity.type()).isEqualTo(KnowledgeUnitType.ENTITY);
        assertThat(entity.title()).isEqualTo("Apple Inc.");
        assertThat(entity.content()).contains("Cupertino");
        assertThat(entity.salience()).isCloseTo(0.92, within(1e-6));
        assertThat(entity.sourceSegmentIndices()).containsExactly(0, 1);
        assertThat(entity.entityType()).isEqualTo("ORGANIZATION");
        assertThat(drafts.get(1).type()).isEqualTo(KnowledgeUnitType.SUMMARY);
    }

    /**
     * Proves the shared parser is reachable from this client too. {@code response_format} is only
     * advisory on some llama.cpp builds, so a server that ignores the schema and answers with its
     * own root key must still be recovered rather than dropped.
     */
    @Test
    void extractRecoversAlternateRootKeyWhenSchemaWasNotEnforced() throws Exception {
        startServer(200, """
                {
                  "choices": [
                    {"message": {"content": "{\\"knowledge_units\\": [{\\"type\\": \\"TOPIC\\", \\"content\\": \\"Cosmology\\", \\"salience\\": 0.5}]}"}}
                  ]
                }
                """);

        OpenAiCompatibleKnowledgeChatClient client =
                new OpenAiCompatibleKnowledgeChatClient(new ObjectMapper(), config(baseUrl()), restClient());

        List<KnowledgeUnitDraft> drafts = client.extract("sys", "user");

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).type()).isEqualTo(KnowledgeUnitType.TOPIC);
        assertThat(drafts.get(0).content()).isEqualTo("Cosmology");
    }

    @Test
    void requestUsesOpenAiFieldNamesAndCarriesTheSchema() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        startServerWithCapture(200, EMPTY_UNITS, captured);

        KnowledgeExtractionConfig cfg = config(baseUrl());
        cfg.setMaxOutputTokens(2048);
        cfg.setApiKey("sk-test-key");
        new OpenAiCompatibleKnowledgeChatClient(new ObjectMapper(), cfg, restClient())
                .extract("sys", "user");

        String body = captured.get();
        assertThat(body).contains("\"model\":\"qwen2.5:14b-instruct\"");
        // Top-level, not nested under "options" — that nesting is Ollama's.
        assertThat(body).contains("\"max_tokens\":2048");
        assertThat(body).contains("\"temperature\":0.2");
        assertThat(body).doesNotContain("num_predict");
        assertThat(body).contains("\"response_format\"");
        assertThat(body).contains("\"json_schema\"");
        assertThat(body).contains("\"units\"");
    }

    @Test
    void apiKeyIsSentAsBearerAndOmittedWhenBlank() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(contextPath(), exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            handle(exchange, 200, EMPTY_UNITS, null);
        });
        server.start();

        KnowledgeExtractionConfig withKey = config(baseUrl());
        withKey.setApiKey("sk-abc");
        new OpenAiCompatibleKnowledgeChatClient(new ObjectMapper(), withKey, restClient())
                .extract("sys", "user");
        assertThat(auth.get()).isEqualTo("Bearer sk-abc");

        auth.set(null);
        new OpenAiCompatibleKnowledgeChatClient(new ObjectMapper(), config(baseUrl()), restClient())
                .extract("sys", "user");
        assertThat(auth.get()).isNull();
    }

    @Test
    void missingContentSurfacesAsExtractionFailure() throws Exception {
        startServer(200, """
                {"choices": [{"finish_reason": "length"}]}
                """);

        OpenAiCompatibleKnowledgeChatClient client =
                new OpenAiCompatibleKnowledgeChatClient(new ObjectMapper(), config(baseUrl()), restClient());

        assertThatThrownBy(() -> client.extract("sys", "user"))
                .isInstanceOf(KnowledgeExtractionFailureException.class)
                .hasMessageContaining("choices[0].message.content");
    }

    @Test
    void httpErrorSurfacesStatusAndBodySnippet() throws Exception {
        startServer(404, "{\"error\": {\"message\": \"model not found\"}}");

        OpenAiCompatibleKnowledgeChatClient client =
                new OpenAiCompatibleKnowledgeChatClient(new ObjectMapper(), config(baseUrl()), restClient());

        assertThatThrownBy(() -> client.extract("sys", "user"))
                .isInstanceOf(KnowledgeExtractionFailureException.class)
                .hasMessageContaining("404")
                .hasMessageContaining("model not found");
    }

    /**
     * Same trap as the Ollama client: the converter wraps the {@code SocketTimeoutException} in a
     * complaint about decoding, so the message must name the clock and the property that bounds it.
     */
    @Test
    void readTimeoutSaysSoAndNamesTheProperty() throws Exception {
        startStallingServer();

        KnowledgeExtractionConfig cfg = config(baseUrl());
        cfg.setReadTimeout(Duration.ofMillis(600));
        OpenAiCompatibleKnowledgeChatClient client =
                new OpenAiCompatibleKnowledgeChatClient(new ObjectMapper(), cfg, shortReadRestClient());

        assertThatThrownBy(() -> client.extract("sys", "user"))
                .isInstanceOf(KnowledgeExtractionFailureException.class)
                .hasMessageContaining("timed out")
                .hasMessageContaining("vidingest.knowledge.read-timeout");
    }

    @Test
    void extractReturnsEmptyForBlankUserPromptWithoutCallingServer() {
        // No server started: a call would fail with connection refused.
        OpenAiCompatibleKnowledgeChatClient client = new OpenAiCompatibleKnowledgeChatClient(
                new ObjectMapper(), config("http://localhost:1"), restClient());

        assertThat(client.extract("sys", "   ")).isEmpty();
        assertThat(client.extract("sys", null)).isEmpty();
    }
}
