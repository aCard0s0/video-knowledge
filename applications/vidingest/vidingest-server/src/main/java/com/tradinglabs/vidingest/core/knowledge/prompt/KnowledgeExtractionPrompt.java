package com.tradinglabs.vidingest.core.knowledge.prompt;

import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.core.fusion.domain.MultimodalSegment;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Static prompt assembly for the M6 knowledge-extraction LLM call. Pure functions so the
 * exact text the LLM sees can be unit-tested in isolation — no Spring, no I/O.
 *
 * <p>One prompt asks the LLM for all configured types in a single JSON array, which:
 * <ul>
 *   <li>cuts round-trips (one LLM call per batch of segments, not one per type)</li>
 *   <li>lets the model decide what's salient rather than us hand-tuning per type</li>
 *   <li>keeps the prompt short enough that the input budget goes to the actual content</li>
 * </ul>
 *
 * <p>The output schema documented in the system prompt matches
 * {@code KnowledgeUnitDraft} field-for-field; any change here must update that record and
 * the parser in {@code OllamaKnowledgeChatClient} in lockstep.
 */
public final class KnowledgeExtractionPrompt {

    /**
     * Bump when the prompt or schema changes meaningfully — written to the persisted
     * {@code metadata.prompt_version} for offline auditing of extraction quality.
     */
    public static final int PROMPT_VERSION = 2;

    private KnowledgeExtractionPrompt() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * The system message that pins the LLM to our output contract. Stable for a given
     * {@link #PROMPT_VERSION} — keep it terse so the model isn't burning tokens parsing
     * it. The {@code allowedTypes} list controls which categories the LLM is permitted to
     * emit; we filter again after parsing as belt-and-suspenders.
     */
    public static String systemMessage(List<KnowledgeUnitType> allowedTypes) {
        String typesCsv = allowedTypes == null || allowedTypes.isEmpty()
                ? csvOf(List.of(KnowledgeUnitType.values()))
                : csvOf(allowedTypes);

        return """
                You are a knowledge-extraction assistant. Given timestamped multimodal segments \
                from a video (each segment has a transcript, optional on-screen OCR text, and a \
                time range), extract a list of self-contained knowledge units the user can later \
                query without re-watching the video.

                Output STRICTLY as a JSON object with EXACTLY ONE top-level key named "units" \
                whose value is an array. Do NOT use any other root key (no "transcript", \
                "summary", "items", "knowledge_units", etc.). Do NOT echo the transcript.

                Each element of the "units" array MUST be an object with these fields:
                  - type:                   one of [%s]
                  - title:                  short headline, ≤ 80 characters
                  - content:                self-contained body (1–4 sentences). Must stand alone — \
                                            do not say "as mentioned above"
                  - salience:               number in [0, 1] — how important / non-trivial this unit is
                  - source_segment_indices: array of 0-based segment indices this unit was drawn from
                  - start_seconds:          earliest segment.start_seconds you used
                  - end_seconds:            latest segment.end_seconds you used
                  - entity_type:            optional. Only set when type=ENTITY. One of \
                                            [PERSON, ORGANIZATION, LOCATION, PRODUCT, TICKER, WORK, OTHER]

                Example of the EXACT output shape (illustrative content, do not copy):
                {"units":[
                  {"type":"SUMMARY","title":"Bitcoin bull-market momentum analysis",\
                   "content":"The host argues on-chain indicators show weakening momentum despite price highs.",\
                   "salience":0.9,"source_segment_indices":[0,1],"start_seconds":0.0,"end_seconds":60.0},
                  {"type":"ENTITY","title":"Bitcoin","content":"The cryptocurrency under discussion throughout the segment.",\
                   "salience":0.8,"source_segment_indices":[0],"start_seconds":0.0,"end_seconds":30.0,"entity_type":"PRODUCT"}
                ]}

                Rules:
                  - Emit ONLY the JSON object — no prose, no markdown fence, no comments.
                  - Do NOT invent content. If the segments don't contain enough to extract a \
                    given type, omit units of that type rather than fabricate. Empty "units": [] is fine.
                  - Deduplicate aggressively. If two segments mention the same entity / topic, emit \
                    one unit with both segment indices in source_segment_indices.
                  - Prefer fewer high-salience units over many low-salience ones. Aim for ≤ 30 units \
                    per batch unless the material is unusually dense.
                """.formatted(typesCsv);
    }

    /**
     * User-message body for one batch of {@link MultimodalSegment} rows. Each segment gets
     * its own delimited block prefixed with its 0-based index — that index is what the
     * LLM references in {@code source_segment_indices}.
     */
    public static String userMessage(List<MultimodalSegment> batch, int startingIndex) {
        if (batch == null || batch.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Extract knowledge units from the following ").append(batch.size())
                .append(" segments. The 'index' field is the value to use in source_segment_indices.\n\n");

        for (int i = 0; i < batch.size(); i++) {
            MultimodalSegment seg = batch.get(i);
            int globalIndex = startingIndex + i;
            sb.append("---\nindex: ").append(globalIndex).append('\n');
            if (seg.getStartSeconds() != null && seg.getEndSeconds() != null) {
                sb.append("time: [").append(formatSeconds(seg.getStartSeconds()))
                        .append(", ").append(formatSeconds(seg.getEndSeconds())).append("]\n");
            }
            if (seg.getTranscriptText() != null && !seg.getTranscriptText().isBlank()) {
                sb.append("transcript: ").append(seg.getTranscriptText().trim()).append('\n');
            }
            if (seg.getOcrText() != null && !seg.getOcrText().isBlank()) {
                sb.append("on_screen_text: ").append(seg.getOcrText().trim()).append('\n');
            }
        }
        sb.append("---\n");
        return sb.toString();
    }

    /**
     * Conservative size estimate for batching. Counts characters in the rendered user
     * message; callers add a fixed overhead for the system prompt. Not exact (chars ≠
     * tokens) but stable enough to keep batches under
     * {@code vidingest.knowledge.max-input-chars-per-batch}.
     */
    public static int estimateCharCount(MultimodalSegment seg) {
        int n = 32;  // index / time scaffolding overhead
        if (seg.getTranscriptText() != null) n += seg.getTranscriptText().length();
        if (seg.getOcrText() != null) n += seg.getOcrText().length();
        return n;
    }

    private static String csvOf(List<KnowledgeUnitType> types) {
        return types.stream().map(Enum::name).collect(Collectors.joining(", "));
    }

    private static String formatSeconds(double s) {
        // Two decimals is plenty — sub-10ms precision is irrelevant for knowledge units.
        return String.format(java.util.Locale.ROOT, "%.2f", s);
    }
}
