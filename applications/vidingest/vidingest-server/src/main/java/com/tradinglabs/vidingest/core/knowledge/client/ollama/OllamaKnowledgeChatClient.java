package com.tradinglabs.vidingest.core.knowledge.client.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.config.KnowledgeExtractionConfig;
import com.tradinglabs.vidingest.core.knowledge.client.KnowledgeChatClient;
import com.tradinglabs.vidingest.core.knowledge.client.KnowledgeUnitJson;
import com.tradinglabs.vidingest.core.knowledge.dto.KnowledgeUnitDraft;
import com.tradinglabs.vidingest.core.knowledge.service.KnowledgeExtractionFailureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ollama-native implementation of {@link KnowledgeChatClient}. Calls
 * {@code POST {base-url}/api/chat} and reads the units out of {@code message.content}.
 *
 * <p>Active when {@code vidingest.knowledge.provider=ollama}, which is also the default —
 * {@code matchIfMissing = true}, so an absent property selects this one. The sibling
 * {@code OpenAiCompatibleKnowledgeChatClient} covers every runtime that speaks the OpenAI wire
 * format instead (LM Studio, llama.cpp, mlx-lm, vLLM, hosted APIs).
 *
 * <p>The class keeps its provider-specific name on purpose: the endpoint path, the
 * {@code options.num_predict} nesting and the {@code format} field are Ollama's own shape, not a
 * generic one. Everything above that split — the JSON schema and the unit parser — lives in
 * {@link KnowledgeUnitJson} and is shared with the OpenAI-compatible client.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "vidingest.knowledge", name = "provider", havingValue = "ollama", matchIfMissing = true)
@Slf4j
public class OllamaKnowledgeChatClient implements KnowledgeChatClient {

    private final ObjectMapper objectMapper;
    private final KnowledgeExtractionConfig knowledgeConfig;
    private final RestClient restClient;

    // Explicit constructor with @Qualifier on the parameter (same pattern as
    // WhisperAsrClient / DiarizationClient / PaddleOcrClient) — multiple RestClient beans
    // exist in the context and Lombok's field-level qualifier copying isn't reliable.
    public OllamaKnowledgeChatClient(
            ObjectMapper objectMapper,
            KnowledgeExtractionConfig knowledgeConfig,
            @Qualifier("knowledgeChatRestClient") RestClient restClient
    ) {
        this.objectMapper = objectMapper;
        this.knowledgeConfig = knowledgeConfig;
        this.restClient = restClient;
    }

    @Override
    public List<KnowledgeUnitDraft> extract(String systemPrompt, String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return List.of();
        }
        long startNs = System.nanoTime();

        Map<String, Object> body = buildRequestBody(systemPrompt, userPrompt);
        log.info("Knowledge extraction LLM request: provider=ollama, model={}, inputChars={}, format=json-schema",
                knowledgeConfig.getChatModel(), userPrompt.length());

        String raw;
        try {
            // Read as byte[] then UTF-8 decode rather than .body(String.class). Some Ollama
            // versions / proxies return `Content-Type: application/octet-stream`, which
            // Spring's default StringHttpMessageConverter refuses to read — bytes bypass
            // the content-type negotiation entirely. Mirrors the same fix applied to
            // OllamaEmbeddingsClient.
            byte[] bytes = restClient.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
            raw = bytes == null ? null : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (RestClientResponseException e) {
            log.warn("Knowledge LLM request failed: provider=ollama, model={}, httpStatus={}, elapsedMs={}",
                    knowledgeConfig.getChatModel(), e.getStatusCode().value(), KnowledgeUnitJson.elapsedMs(startNs));
            throw new KnowledgeExtractionFailureException(
                    "Knowledge LLM returned HTTP " + e.getStatusCode().value() + ": "
                            + KnowledgeUnitJson.bodySnippet(e.getResponseBodyAsString()), e);
        } catch (RestClientException e) {
            // A read timeout reaches this branch wrapped by the message converter, so
            // e.getMessage() reads "Error while extracting response for type [byte[]] and content
            // type [application/octet-stream]" — a decoding complaint for what is actually the
            // clock running out, which sends the reader looking at content negotiation. Say what
            // happened and name the knob that bounds it.
            if (KnowledgeUnitJson.isTimeout(e)) {
                log.warn("Knowledge LLM timed out: provider=ollama, model={}, elapsedMs={}, readTimeout={}",
                        knowledgeConfig.getChatModel(), KnowledgeUnitJson.elapsedMs(startNs), knowledgeConfig.getReadTimeout());
                throw new KnowledgeExtractionFailureException(
                        "Knowledge LLM timed out after " + knowledgeConfig.getReadTimeout()
                                + " (vidingest.knowledge.read-timeout), model "
                                + knowledgeConfig.getChatModel(), e);
            }
            log.warn("Knowledge LLM request failed: provider=ollama, model={}, elapsedMs={}, message={}",
                    knowledgeConfig.getChatModel(), KnowledgeUnitJson.elapsedMs(startNs), e.getMessage());
            throw new KnowledgeExtractionFailureException("Knowledge LLM request failed: " + e.getMessage(), e);
        }

        if (raw == null || raw.isBlank()) {
            throw new KnowledgeExtractionFailureException("Knowledge LLM returned an empty response");
        }

        List<KnowledgeUnitDraft> drafts = parseDrafts(raw);
        log.info("Knowledge extraction LLM response: provider=ollama, model={}, elapsedMs={}, units={}",
                knowledgeConfig.getChatModel(), KnowledgeUnitJson.elapsedMs(startNs), drafts.size());
        return drafts;
    }

    /**
     * Builds the Ollama {@code /api/chat} request body. {@code format: <json-schema>} uses
     * Ollama's structured-output support (0.5+) to constrain the response to the exact
     * shape we want — small open models (qwen2.5:7b etc.) drift to other root keys (we've
     * seen {@code {"transcript":...}}) when only {@code "json"} is requested. The schema
     * pins the root to {@code {"units": [...]}}. {@code stream: false} keeps the response
     * a single shot so we don't need streaming parsing.
     */
    private Map<String, Object> buildRequestBody(String systemPrompt, String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", knowledgeConfig.getChatModel());
        body.put("stream", false);
        body.put("format", KnowledgeUnitJson.unitsResponseSchema());

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", knowledgeConfig.getTemperature());
        if (knowledgeConfig.getMaxOutputTokens() > 0) {
            options.put("num_predict", knowledgeConfig.getMaxOutputTokens());
        }
        body.put("options", options);

        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));
        body.put("messages", messages);
        return body;
    }

    /**
     * Two layers of JSON: the outer Ollama envelope, and the inner model-generated string
     * (which is itself JSON because we passed a schema in {@code format}). We extract
     * {@code message.content}, then hand it to the shared parser.
     */
    private List<KnowledgeUnitDraft> parseDrafts(String raw) {
        try {
            JsonNode envelope = objectMapper.readTree(raw);
            JsonNode contentNode = envelope.path("message").path("content");
            if (contentNode.isMissingNode() || contentNode.isNull()) {
                throw new KnowledgeExtractionFailureException("Knowledge LLM response missing message.content");
            }
            String content = contentNode.asText();
            if (content == null || content.isBlank()) {
                return List.of();
            }
            return KnowledgeUnitJson.parseUnitsArray(objectMapper, content);
        } catch (IOException e) {
            throw new KnowledgeExtractionFailureException("Failed to parse Knowledge LLM envelope: " + e.getMessage(), e);
        }
    }
}
