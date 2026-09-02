package com.tradinglabs.vidingest.core.knowledge.client.openai;

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
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI-compatible implementation of {@link KnowledgeChatClient}. Calls
 * {@code POST {base-url}/chat/completions} and reads the units out of
 * {@code choices[0].message.content}.
 *
 * <p>Selected by {@code KnowledgeChatClientRouter} when {@code vidingest.knowledge.provider} is
 * {@code openai-compatible}, which is the default in compose. This is the client for every local
 * runtime that is not Ollama — oMLX, LM Studio, {@code llama-server}, mlx-lm, vLLM,
 * text-generation-webui — as well as remote hosted APIs, because they all serve the same wire
 * format. {@code vidingest.knowledge.base-url} is expected to end in {@code /v1}, matching the
 * convention {@code OpenAiCompatibleEmbeddingsClient} already uses
 * ({@code http://host.docker.internal:8000/v1} is the compose default for a host oMLX).
 *
 * <p>Three differences from the Ollama sibling, all in the request: {@code temperature} sits at
 * the top level rather than under {@code options}, the output cap is {@code max_tokens} rather
 * than {@code options.num_predict}, and the schema is wrapped in
 * {@code response_format.json_schema} rather than passed bare as {@code format}.
 *
 * <p>Servers vary in how much of {@code response_format} they honour — LM Studio and vLLM enforce
 * the schema, some llama.cpp builds treat it as a hint. That is why the shared parser keeps its
 * alternate-root-key fallbacks rather than trusting the constraint.
 *
 * <p><b>This class serves two provider values.</b> {@code openai-compatible} is the body every
 * local runtime accepts; {@code openai} is the same body with three fields changed, because
 * api.openai.com rejects all three outright rather than ignoring them. Selecting the dialect from
 * the configured provider rather than from a second bean is deliberate — the router already maps
 * both strings here, and the delta is fifteen lines.
 */
@Component
public class OpenAiCompatibleKnowledgeChatClient extends AbstractKnowledgeChatClient {

    /** The provider value that selects the strict-OpenAI dialect of the body below. */
    private static final String OPENAI = "openai";

    // Explicit constructor with @Qualifier on the parameter — see OllamaKnowledgeChatClient for
    // why Lombok cannot do this once several RestClient beans are in the context.
    public OpenAiCompatibleKnowledgeChatClient(
            ObjectMapper objectMapper,
            KnowledgeExtractionConfig knowledgeConfig,
            @Qualifier("knowledgeChatRestClient") RestClient restClient
    ) {
        super(objectMapper, knowledgeConfig, restClient);
    }

    /** The configured value, not a constant: the log lines have to say which dialect ran. */
    @Override
    protected String providerName() {
        return strictOpenAi() ? OPENAI : "openai-compatible";
    }

    /**
     * True when the operator selected {@code openai} rather than {@code openai-compatible} — the
     * one place the dialect is decided. Read per call, like every other setting on this path,
     * because the provider is editable through {@code PUT /api/v1/connections/KNOWLEDGE}.
     */
    private boolean strictOpenAi() {
        String provider = config.getProvider();
        return provider != null && OPENAI.equals(provider.trim().toLowerCase(Locale.ROOT));
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
     *
     * <p>Under the {@code openai} dialect three fields change, each because api.openai.com answers
     * 400 rather than ignoring what it does not support:
     * <ul>
     *   <li>{@code max_completion_tokens} replaces {@code max_tokens} — "Unsupported parameter:
     *       'max_tokens' is not supported with this model".</li>
     *   <li>{@code temperature} is omitted — the reasoning models accept only the default.</li>
     *   <li>{@code strict} becomes false — OpenAI validates a strict schema and rejects this one:
     *       it requires every key of {@code properties} to appear in {@code required} and every
     *       object to carry {@code additionalProperties: false}, and the shared schema has eight
     *       properties against two required.</li>
     * </ul>
     */
    @Override
    protected Map<String, Object> buildRequestBody(String systemPrompt, String userPrompt) {
        boolean strictOpenAi = strictOpenAi();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getChatModel());
        body.put("stream", false);
        // ponytail: no temperature control at all under `openai`, so extraction runs at the
        // model default instead of the configured 0.2. The JSON schema is what constrains the
        // shape, so the cost is consistency, not validity. Upgrade path if a deployment needs it
        // back: retry once without the field on a 400, or keep a model allowlist here.
        if (!strictOpenAi) {
            body.put("temperature", config.getTemperature());
        }
        if (config.getMaxOutputTokens() > 0) {
            // On a reasoning model this cap covers reasoning tokens too, so a value that is
            // ample for the answer alone can be spent before any content is emitted — which
            // surfaces as the "empty content" failure in the shared parser, not as a truncation.
            body.put(strictOpenAi ? "max_completion_tokens" : "max_tokens", config.getMaxOutputTokens());
        }

        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", "knowledge_units");
        // ponytail: `strict: false` rather than a strict-compliant schema. Making
        // KnowledgeUnitJson.unitsResponseSchema() OpenAI-strict (all keys required,
        // additionalProperties false, optional types unioned with "null") would serve every
        // provider, but it also changes what Ollama and llama.cpp are handed, so it is not a
        // free swap. Do it if the units drift.
        jsonSchema.put("strict", !strictOpenAi);
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
