package com.tradinglabs.vidingest.core.knowledge.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.core.knowledge.dto.KnowledgeUnitDraft;
import com.tradinglabs.vidingest.core.knowledge.service.KnowledgeExtractionFailureException;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything about the knowledge-unit JSON that is the same whatever runtime produced it.
 *
 * <p>The two {@link KnowledgeChatClient} implementations differ only in the <em>envelope</em>:
 * Ollama answers {@code {"message":{"content":"…"}}} on {@code /api/chat}, an OpenAI-compatible
 * server answers {@code {"choices":[{"message":{"content":"…"}}]}} on {@code /chat/completions}.
 * The string inside {@code content} is the same model-authored JSON in both cases, constrained by
 * the same schema, so the schema builder and the parser live here rather than being copied per
 * provider — a fallback added for one runtime would otherwise silently not exist for the other.
 *
 * <p>Also holds the small transport helpers both clients need to say the same thing about a
 * timeout, since the failure is identical and only the URL differs.
 */
@Slf4j
public final class KnowledgeUnitJson {

    private static final TypeReference<List<Map<String, Object>>> UNIT_LIST_TYPE = new TypeReference<>() {
    };

    private KnowledgeUnitJson() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * JSON Schema for the response envelope. Mirrors {@link KnowledgeUnitDraft} exactly.
     *
     * <p>Ollama enforces it server-side when passed as the {@code format} field; an
     * OpenAI-compatible server takes the same object nested under
     * {@code response_format.json_schema.schema}. Either way it saves the parser from heuristic
     * guesswork on weak open models, which drift to other root keys when asked only for "json".
     */
    public static Map<String, Object> unitsResponseSchema() {
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
     * Parses the inner JSON the LLM emitted. Preferred shape is {@code {"units": [...]}}
     * (enforced via the schema above). Falls back to:
     * <ol>
     *   <li>known alternate root keys</li>
     *   <li>any object key whose value is an array of objects with a {@code type} field</li>
     *   <li>a bare top-level array</li>
     * </ol>
     * Small open models occasionally bypass the schema and emit e.g. {@code {"knowledge_units": [...]}}
     * or {@code {"items": [...]}} — heuristics recover the units rather than dropping the batch.
     *
     * <p><b>An unrecoverable payload throws rather than answering empty.</b> This used to log a
     * warning and return {@code List.of()}, described as "drop the batch rather than fail the whole
     * video" — but {@code KnowledgeExtractionService} counts a batch as failed only when
     * {@code extract} <em>throws</em>. So a truncated or unparseable response contributed zero
     * drafts, left {@code batchesFailed} at zero, and the wipe-and-replace proceeded: the phase
     * reported success having extracted nothing, and on a multi-batch video replaced a complete
     * extraction with a partial one. That is the exact outcome the service's own policy exists to
     * prevent — "a batch is ~40 segments of coverage, not one frame, so salvaging the rest would
     * silently narrow the extraction".
     *
     * <p>The distinction that has to survive: {@code {"units": []}} is a <em>legitimate</em> empty
     * result — the model was asked and found nothing salient — and still returns empty. Only
     * content we could not read at all, or an array whose every element was malformed, fails.
     *
     * <p>The most likely trigger is the output cap truncating the JSON mid-object, so the failure
     * message carries the content length: {@code vidingest.knowledge.max-output-tokens} being too
     * low is otherwise indistinguishable from a broken model.
     */
    public static List<KnowledgeUnitDraft> parseUnitsArray(ObjectMapper objectMapper, String content) {
        JsonNode tree;
        try {
            tree = objectMapper.readTree(content);
        } catch (Exception e) {
            throw new KnowledgeExtractionFailureException(
                    "Knowledge LLM content was not parseable JSON (" + lengthOf(content)
                            + " chars). A truncated payload usually means "
                            + "vidingest.knowledge.max-output-tokens is too low for this prompt. Snippet: "
                            + truncate(content, 200), e);
        }

        JsonNode unitsNode = locateUnitsArray(tree);
        if (unitsNode == null || !unitsNode.isArray()) {
            throw new KnowledgeExtractionFailureException(
                    "Knowledge LLM emitted JSON with no recognisable units array (" + lengthOf(content)
                            + " chars, root keys=" + keysOf(tree) + "). Snippet: " + truncate(content, 200));
        }

        List<Map<String, Object>> rawUnits;
        try {
            rawUnits = objectMapper.convertValue(unitsNode, UNIT_LIST_TYPE);
        } catch (IllegalArgumentException e) {
            throw new KnowledgeExtractionFailureException(
                    "Knowledge LLM units array was not a list of objects (" + lengthOf(content)
                            + " chars). Snippet: " + truncate(content, 200), e);
        }

        List<KnowledgeUnitDraft> drafts = new ArrayList<>(rawUnits.size());
        for (Map<String, Object> u : rawUnits) {
            KnowledgeUnitDraft d = toDraft(u);
            if (d != null) drafts.add(d);
        }

        // Some elements being unusable is tolerable — one bad unit is not worth a batch. Every
        // element being unusable is the silent zero wearing a different hat.
        if (drafts.isEmpty() && !rawUnits.isEmpty()) {
            throw new KnowledgeExtractionFailureException(
                    "Knowledge LLM returned " + rawUnits.size() + " units and not one of them had a "
                            + "usable type and content (" + lengthOf(content) + " chars). Snippet: "
                            + truncate(content, 200));
        }
        if (drafts.size() < rawUnits.size()) {
            log.warn("Knowledge LLM: dropped {} of {} units as malformed", rawUnits.size() - drafts.size(),
                    rawUnits.size());
        }
        return drafts;
    }

    /** Content length for the failure messages, without risking an NPE on a null payload. */
    private static int lengthOf(String content) {
        return content == null ? 0 : content.length();
    }

    /**
     * Finds the array of unit objects in the LLM response tree. Preferred shape is
     * {@code {"units": [...]}}. Falls back to known alternate keys, then to any array of
     * objects with a recognised {@code type} field, then to a bare root array.
     */
    static JsonNode locateUnitsArray(JsonNode tree) {
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
    static KnowledgeUnitDraft toDraft(Map<String, Object> u) {
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

    // ---- transport helpers ----

    /**
     * Whether a failure is the read timeout rather than anything about the payload. Walks the
     * cause chain because the converter wraps it: the {@code SocketTimeoutException} sits under a
     * {@code RestClientException} whose own message talks about decoding.
     */
    public static boolean isTimeout(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.net.SocketTimeoutException
                    || c instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    public static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }

    /** Trimmed and capped at {@code max}, for putting a response body in a failure message. */
    public static String truncate(String s, int max) {
        if (s == null) return "";
        String trimmed = s.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "...";
    }

    // ---- value helpers ----

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
}
