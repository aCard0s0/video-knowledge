package com.tradinglabs.vidingest.core.knowledge.client.openai;

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
 * OpenAI-compatible implementation of {@link KnowledgeChatClient}. Calls
 * {@code POST {base-url}/chat/completions} and reads the units out of
 * {@code choices[0].message.content}.
 *
 * <p>Active when {@code vidingest.knowledge.provider=openai-compatible}. This is the client for
 * every local runtime that is not Ollama — LM Studio, {@code llama-server}, mlx-lm, vLLM,
 * text-generation-webui — as well as remote hosted APIs, because they all serve the same wire
 * format. {@code vidingest.knowledge.base-url} is expected to end in {@code /v1}, matching the
 * convention {@code OpenAiCompatibleEmbeddingsClient} already uses
 * ({@code http://localhost:1234/v1} is LM Studio's default).
 *
 * <p>Three differences from the Ollama sibling, all in the request: {@code temperature} sits at
 * the top level rather than under {@code options}, the output cap is {@code max_tokens} rather
 * than {@code options.num_predict}, and the schema is wrapped in
 * {@code response_format.json_schema} rather than passed bare as {@code format}. The schema itself
 * and the parsing of what comes back are shared through {@link KnowledgeUnitJson}.
 *
 * <p>Servers vary in how much of {@code response_format} they honour — LM Studio and vLLM enforce
 * the schema, some llama.cpp builds treat it as a hint. That is why the shared parser keeps its
 * alternate-root-key fallbacks rather than trusting the constraint.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "vidingest.knowledge", name = "provider", havingValue = "openai-compatible")
@Slf4j
public class OpenAiCompatibleKnowledgeChatClient implements KnowledgeChatClient {

    private final ObjectMapper objectMapper;
    private final KnowledgeExtractionConfig knowledgeConfig;
    private final RestClient restClient;

    // Explicit constructor with @Qualifier on the parameter — see OllamaKnowledgeChatClient for
    // why Lombok cannot do this once several RestClient beans are in the context.
    public OpenAiCompatibleKnowledgeChatClient(
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
        log.info("Knowledge extraction LLM request: provider=openai-compatible, model={}, inputChars={}, format=json-schema",
                knowledgeConfig.getChatModel(), userPrompt.length());

        String raw;
        try {
            // byte[] rather than String for the same reason the Ollama client does it: a server
            // answering `Content-Type: application/octet-stream` (or a proxy rewriting it) makes
            // StringHttpMessageConverter refuse the body. Bytes skip content-type negotiation.
            byte[] bytes = postChatCompletions(body);
            raw = bytes == null ? null : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (RestClientResponseException e) {
            log.warn("Knowledge LLM request failed: provider=openai-compatible, model={}, httpStatus={}, elapsedMs={}",
                    knowledgeConfig.getChatModel(), e.getStatusCode().value(), KnowledgeUnitJson.elapsedMs(startNs));
            throw new KnowledgeExtractionFailureException(
                    "Knowledge LLM returned HTTP " + e.getStatusCode().value() + ": "
                            + KnowledgeUnitJson.bodySnippet(e.getResponseBodyAsString()), e);
        } catch (RestClientException e) {
            if (KnowledgeUnitJson.isTimeout(e)) {
                log.warn("Knowledge LLM timed out: provider=openai-compatible, model={}, elapsedMs={}, readTimeout={}",
                        knowledgeConfig.getChatModel(), KnowledgeUnitJson.elapsedMs(startNs), knowledgeConfig.getReadTimeout());
                throw new KnowledgeExtractionFailureException(
                        "Knowledge LLM timed out after " + knowledgeConfig.getReadTimeout()
                                + " (vidingest.knowledge.read-timeout), model "
                                + knowledgeConfig.getChatModel(), e);
            }
            log.warn("Knowledge LLM request failed: provider=openai-compatible, model={}, elapsedMs={}, message={}",
                    knowledgeConfig.getChatModel(), KnowledgeUnitJson.elapsedMs(startNs), e.getMessage());
            throw new KnowledgeExtractionFailureException("Knowledge LLM request failed: " + e.getMessage(), e);
        }

        if (raw == null || raw.isBlank()) {
            throw new KnowledgeExtractionFailureException("Knowledge LLM returned an empty response");
        }

        List<KnowledgeUnitDraft> drafts = parseDrafts(raw);
        log.info("Knowledge extraction LLM response: provider=openai-compatible, model={}, elapsedMs={}, units={}",
                knowledgeConfig.getChatModel(), KnowledgeUnitJson.elapsedMs(startNs), drafts.size());
        return drafts;
    }

    private byte[] postChatCompletions(Map<String, Object> body) {
        RestClient.RequestBodySpec spec = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);

        String apiKey = knowledgeConfig.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            spec = spec.header("Authorization", "Bearer " + apiKey.trim());
        }

        return spec.body(body).retrieve().body(byte[].class);
    }

    /**
     * Builds the OpenAI {@code /chat/completions} request body. The schema goes in
     * {@code response_format.json_schema} with {@code strict: true}; servers that do not
     * implement structured outputs ignore the field and the system prompt still asks for the same
     * shape, which the shared parser's fallbacks then recover.
     */
    private Map<String, Object> buildRequestBody(String systemPrompt, String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", knowledgeConfig.getChatModel());
        body.put("stream", false);
        body.put("temperature", knowledgeConfig.getTemperature());
        if (knowledgeConfig.getMaxOutputTokens() > 0) {
            body.put("max_tokens", knowledgeConfig.getMaxOutputTokens());
        }

        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", "knowledge_units");
        jsonSchema.put("strict", true);
        jsonSchema.put("schema", KnowledgeUnitJson.unitsResponseSchema());
        body.put("response_format", Map.of("type", "json_schema", "json_schema", jsonSchema));

        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));
        body.put("messages", messages);
        return body;
    }

    /**
     * Two layers of JSON, same as the Ollama path but a different envelope: the units are the
     * string at {@code choices[0].message.content}.
     */
    private List<KnowledgeUnitDraft> parseDrafts(String raw) {
        try {
            JsonNode envelope = objectMapper.readTree(raw);
            JsonNode contentNode = envelope.path("choices").path(0).path("message").path("content");
            if (contentNode.isMissingNode() || contentNode.isNull()) {
                throw new KnowledgeExtractionFailureException(
                        "Knowledge LLM response missing choices[0].message.content");
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
