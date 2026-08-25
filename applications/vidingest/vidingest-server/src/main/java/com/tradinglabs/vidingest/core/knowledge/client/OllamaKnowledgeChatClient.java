package com.tradinglabs.vidingest.core.knowledge.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.config.KnowledgeExtractionConfig;
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
import java.util.Locale;
import java.util.Map;

/**
 * Ollama implementation of {@link KnowledgeChatClient}. Calls
 * {@code POST {base-url}/api/chat} with {@code format: json}, parses the JSON content of
 * the model's reply into {@link KnowledgeUnitDraft} records, and surfaces failures as
 * {@link KnowledgeExtractionFailureException}.
 *
 * <p>Active when {@code vidingest.knowledge.provider=ollama} (the default). Marked
 * {@code @Primary} so callers that just inject {@link KnowledgeChatClient} get this impl
 * without needing their own qualifier — future provider impls will be {@code @Conditional}
 * on their own property values.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "vidingest.knowledge", name = "provider", havingValue = "ollama", matchIfMissing = true)
@Slf4j
public class OllamaKnowledgeChatClient implements KnowledgeChatClient {

    private static final TypeReference<List<Map<String, Object>>> UNIT_LIST_TYPE = new TypeReference<>() {
    };

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
        log.info("Knowledge extraction LLM request: model={}, inputChars={}, format=json",
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
            log.warn("Knowledge LLM request failed: model={}, httpStatus={}, elapsedMs={}",
                    knowledgeConfig.getChatModel(), e.getStatusCode().value(), elapsedMs(startNs));
            throw new KnowledgeExtractionFailureException(
                    "Knowledge LLM returned HTTP " + e.getStatusCode().value() + ": " + safeBodySnippet(e), e);
        } catch (RestClientException e) {
            log.warn("Knowledge LLM request failed: model={}, elapsedMs={}, message={}",
                    knowledgeConfig.getChatModel(), elapsedMs(startNs), e.getMessage());
            throw new KnowledgeExtractionFailureException("Knowledge LLM request failed: " + e.getMessage(), e);
        }

        if (raw == null || raw.isBlank()) {
            throw new KnowledgeExtractionFailureException("Knowledge LLM returned an empty response");
        }

        List<KnowledgeUnitDraft> drafts = parseDrafts(raw);
        log.info("Knowledge extraction LLM response: model={}, elapsedMs={}, units={}",
                knowledgeConfig.getChatModel(), elapsedMs(startNs), drafts.size());
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
        body.put("format", unitsResponseSchema());

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
     * JSON Schema for the response envelope. Mirrors {@link KnowledgeUnitDraft} exactly.
     * Ollama enforces this server-side when passed as the {@code format} field — saves
     * the parser from heuristic guesswork on weak open models.
     */
    private static Map<String, Object> unitsResponseSchema() {
        List<String> typeEnum = new ArrayList<>();
        for (KnowledgeUnitType t : KnowledgeUnitType.values()) {
            typeEnum.add(t.name());
        }

        Map<String, Object> unitProps = new LinkedHashMap<>();
        unitProps.put("type", Map.of("type", "string", "enum", typeEnum));
        unitProps.put("title", Map.of("type", "string"));
        unitProps.put("content", Map.of("type", "string"));
        unitProps.put("salience", Map.of("type", "number", "minimum", 0, "maximum", 1));
        unitProps.put("source_segment_indices", Map.of(
                "type", "array",
                "items", Map.of("type", "integer", "minimum", 0)
        ));
        unitProps.put("start_seconds", Map.of("type", "number"));
        unitProps.put("end_seconds", Map.of("type", "number"));
        unitProps.put("entity_type", Map.of(
                "type", "string",
                "enum", List.of("PERSON", "ORGANIZATION", "LOCATION", "PRODUCT", "TICKER", "WORK", "OTHER")
        ));

        Map<String, Object> unitSchema = new LinkedHashMap<>();
        unitSchema.put("type", "object");
        unitSchema.put("properties", unitProps);
        // Only the bare minimum is required — we tolerate missing optional fields server-side.
        unitSchema.put("required", List.of("type", "content"));

        Map<String, Object> rootProps = new LinkedHashMap<>();
        rootProps.put("units", Map.of(
                "type", "array",
                "items", unitSchema
        ));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", rootProps);
        root.put("required", List.of("units"));
        return root;
    }

    /**
     * Two layers of JSON: the outer Ollama envelope, and the inner model-generated string
     * (which is itself JSON because we passed {@code format: "json"}). We extract
     * {@code message.content}, then parse it as our typed schema.
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
            return parseUnitsArray(content);
        } catch (IOException e) {
            throw new KnowledgeExtractionFailureException("Failed to parse Knowledge LLM envelope: " + e.getMessage(), e);
        }
    }

    /**
     * Parses the inner JSON the LLM emitted. Preferred shape is {@code {"units": [...]}}
     * (enforced via Ollama structured-output schema). Falls back to:
     * <ol>
     *   <li>any object key whose value is an array of objects with a {@code type} field</li>
     *   <li>a bare top-level array</li>
     * </ol>
     * Small open models occasionally bypass the schema and emit e.g. {@code {"knowledge_units": [...]}}
     * or {@code {"items": [...]}} — heuristics recover the units rather than dropping the batch.
     */
    private List<KnowledgeUnitDraft> parseUnitsArray(String content) {
        try {
            JsonNode tree = objectMapper.readTree(content);
            JsonNode unitsNode = locateUnitsArray(tree);
            if (unitsNode == null || !unitsNode.isArray()) {
                log.warn("Knowledge LLM emitted JSON without a recognisable units array; root keys={}, snippet={}",
                        keysOf(tree), snippet(content));
                return List.of();
            }

            List<Map<String, Object>> rawUnits = objectMapper.convertValue(unitsNode, UNIT_LIST_TYPE);
            List<KnowledgeUnitDraft> drafts = new ArrayList<>(rawUnits.size());
            for (Map<String, Object> u : rawUnits) {
                KnowledgeUnitDraft d = toDraft(u);
                if (d != null) drafts.add(d);
            }
            return drafts;
        } catch (IOException e) {
            // Soft failure: log + drop the batch rather than fail the whole video.
            log.warn("Knowledge LLM inner content was not parseable JSON; dropping batch. snippet={}",
                    snippet(content));
            return List.of();
        }
    }

    /**
     * Finds the array of unit objects in the LLM response tree. Preferred shape is
     * {@code {"units": [...]}}. Falls back to known alternate keys, then to any array of
     * objects with a recognised {@code type} field, then to a bare root array.
     */
    private static JsonNode locateUnitsArray(JsonNode tree) {
        if (tree == null || tree.isMissingNode() || tree.isNull()) return null;
        if (tree.isArray()) return tree;
        if (!tree.isObject()) return null;

        for (String key : List.of("units", "knowledge_units", "knowledgeUnits", "items", "data", "results")) {
            JsonNode candidate = tree.get(key);
            if (candidate != null && candidate.isArray()) {
                return candidate;
            }
        }

        // Last resort: scan every value and pick the first array of objects that look like units.
        var fields = tree.fields();
        while (fields.hasNext()) {
            JsonNode value = fields.next().getValue();
            if (value.isArray() && looksLikeUnitArray(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean looksLikeUnitArray(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) return false;
        JsonNode first = arr.get(0);
        return first != null && first.isObject() && first.has("type") && first.has("content");
    }

    /**
     * Map one LLM-emitted unit object to a typed {@link KnowledgeUnitDraft}. Missing or
     * malformed fields make the unit get dropped rather than throw — we want one bad unit
     * to be a no-op, not a pipeline failure.
     */
    private static KnowledgeUnitDraft toDraft(Map<String, Object> u) {
        if (u == null) return null;
        String typeStr = asString(u.get("type"));
        if (typeStr == null) return null;
        KnowledgeUnitType type;
        try {
            type = KnowledgeUnitType.valueOf(typeStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }

        String content = asString(u.get("content"));
        if (content == null || content.isBlank()) return null;

        String title = asString(u.get("title"));
        Double salience = asDouble(u.get("salience"));
        Double start = asDouble(firstNonNull(u.get("start_seconds"), u.get("startSeconds")));
        Double end = asDouble(firstNonNull(u.get("end_seconds"), u.get("endSeconds")));
        String entityType = asString(firstNonNull(u.get("entity_type"), u.get("entityType")));

        List<Integer> srcIndices = new ArrayList<>();
        Object src = firstNonNull(u.get("source_segment_indices"), u.get("sourceSegmentIndices"));
        if (src instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) srcIndices.add(n.intValue());
            }
        }

        return new KnowledgeUnitDraft(type, title, content.trim(), salience, srcIndices, start, end, entityType);
    }

    // ---- helpers ----

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    private static String asString(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s;
        return o.toString();
    }

    private static Double asDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String keysOf(JsonNode node) {
        if (!node.isObject()) return "<not-object>";
        StringBuilder sb = new StringBuilder("[");
        var it = node.fieldNames();
        boolean first = true;
        while (it.hasNext()) {
            if (!first) sb.append(", ");
            sb.append(it.next());
            first = false;
        }
        return sb.append(']').toString();
    }

    private static String snippet(String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }

    private static String safeBodySnippet(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        if (body == null) return "";
        String trimmed = body.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500) + "...";
    }
}
