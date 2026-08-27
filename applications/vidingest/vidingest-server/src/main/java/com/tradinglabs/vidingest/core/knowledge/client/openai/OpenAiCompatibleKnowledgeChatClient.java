package com.tradinglabs.vidingest.core.knowledge.client.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.config.KnowledgeExtractionConfig;
import com.tradinglabs.vidingest.core.knowledge.client.AbstractKnowledgeChatClient;
import com.tradinglabs.vidingest.core.knowledge.client.KnowledgeChatClient;
import com.tradinglabs.vidingest.core.knowledge.client.KnowledgeUnitJson;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
 * {@code response_format.json_schema} rather than passed bare as {@code format}.
 *
 * <p>Servers vary in how much of {@code response_format} they honour — LM Studio and vLLM enforce
 * the schema, some llama.cpp builds treat it as a hint. That is why the shared parser keeps its
 * alternate-root-key fallbacks rather than trusting the constraint.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "vidingest.knowledge", name = "provider", havingValue = "openai-compatible")
public class OpenAiCompatibleKnowledgeChatClient extends AbstractKnowledgeChatClient {

    // Explicit constructor with @Qualifier on the parameter — see OllamaKnowledgeChatClient for
    // why Lombok cannot do this once several RestClient beans are in the context.
    public OpenAiCompatibleKnowledgeChatClient(
            ObjectMapper objectMapper,
            KnowledgeExtractionConfig knowledgeConfig,
            @Qualifier("knowledgeChatRestClient") RestClient restClient
    ) {
        super(objectMapper, knowledgeConfig, restClient);
    }

    @Override
    protected String providerName() {
        return "openai-compatible";
    }

    @Override
    protected String uri() {
        return "/chat/completions";
    }

    @Override
    protected String contentPath() {
        return "choices[0].message.content";
    }

    @Override
    protected JsonNode contentNode(JsonNode envelope) {
        return envelope.path("choices").path(0).path("message").path("content");
    }

    /**
     * The schema goes in {@code response_format.json_schema} with {@code strict: true}; servers
     * that do not implement structured outputs ignore the field and the system prompt still asks
     * for the same shape, which the shared parser's fallbacks then recover.
     */
    @Override
    protected Map<String, Object> buildRequestBody(String systemPrompt, String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getChatModel());
        body.put("stream", false);
        body.put("temperature", config.getTemperature());
        if (config.getMaxOutputTokens() > 0) {
            body.put("max_tokens", config.getMaxOutputTokens());
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
}
