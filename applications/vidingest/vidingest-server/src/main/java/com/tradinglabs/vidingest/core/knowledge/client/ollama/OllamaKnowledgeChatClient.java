package com.tradinglabs.vidingest.core.knowledge.client.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.config.KnowledgeExtractionConfig;
import com.tradinglabs.vidingest.core.knowledge.client.AbstractKnowledgeChatClient;
import com.tradinglabs.vidingest.core.knowledge.client.KnowledgeChatClient;
import com.tradinglabs.vidingest.core.knowledge.client.KnowledgeUnitJson;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ollama-native implementation of {@link KnowledgeChatClient}. Calls
 * {@code POST {base-url}/api/chat} and reads the units out of {@code message.content}.
 *
 * <p>Selected by {@code KnowledgeChatClientRouter} when {@code vidingest.knowledge.provider} is
 * {@code ollama} (or absent). The sibling {@code OpenAiCompatibleKnowledgeChatClient} covers every
 * runtime that speaks the OpenAI wire format instead (oMLX, LM Studio, llama.cpp, mlx-lm, vLLM,
 * hosted APIs). Both are always registered as beans; the router chooses per call so the provider
 * can be changed at runtime.
 *
 * <p>The class keeps its provider-specific name on purpose: the endpoint path, the
 * {@code options.num_predict} nesting and the {@code format} field are Ollama's own shape, not a
 * generic one. Everything above that split lives in {@link AbstractKnowledgeChatClient} and
 * {@link KnowledgeUnitJson}.
 */
@Component
public class OllamaKnowledgeChatClient extends AbstractKnowledgeChatClient {

    // Explicit constructor with @Qualifier on the parameter (same pattern as
    // WhisperAsrClient / DiarizationClient / PaddleOcrClient) — multiple RestClient beans
    // exist in the context and Lombok's field-level qualifier copying isn't reliable.
    public OllamaKnowledgeChatClient(
            ObjectMapper objectMapper,
            KnowledgeExtractionConfig knowledgeConfig,
            @Qualifier("knowledgeChatRestClient") RestClient restClient
    ) {
        super(objectMapper, knowledgeConfig, restClient);
    }

    @Override
    protected String providerName() {
        return "ollama";
    }

    @Override
    protected String uri() {
        return "/api/chat";
    }

    @Override
    protected String contentPath() {
        return "message.content";
    }

    @Override
    protected JsonNode contentNode(JsonNode envelope) {
        return envelope.path("message").path("content");
    }

    /**
     * {@code format: <json-schema>} uses Ollama's structured-output support (0.5+) to constrain the
     * response to the exact shape we want — small open models (qwen2.5:7b etc.) drift to other root
     * keys (we've seen {@code {"transcript":...}}) when only {@code "json"} is requested. The schema
     * pins the root to {@code {"units": [...]}}. {@code stream: false} keeps the response a single
     * shot so we don't need streaming parsing.
     */
    @Override
    protected Map<String, Object> buildRequestBody(String systemPrompt, String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getChatModel());
        body.put("stream", false);
        body.put("format", KnowledgeUnitJson.unitsResponseSchema());

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", config.getTemperature());
        if (config.getMaxOutputTokens() > 0) {
            options.put("num_predict", config.getMaxOutputTokens());
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
}
