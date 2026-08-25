package com.tradinglabs.vidingest.core.knowledge.prompt;

import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.core.fusion.domain.MultimodalSegment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-function tests for {@link KnowledgeExtractionPrompt}. These pin down the prompt
 * contract so quietly changing it (and the LLM output it elicits) shows up in a code
 * review rather than at runtime.
 */
class KnowledgeExtractionPromptTest {

    @Test
    void systemMessageContainsSchemaContractAndAllowedTypes() {
        String sys = KnowledgeExtractionPrompt.systemMessage(List.of(
                KnowledgeUnitType.ENTITY,
                KnowledgeUnitType.SUMMARY
        ));

        // The output schema fields the parser depends on.
        assertThat(sys).contains("\"units\"");
        assertThat(sys).contains("type:");
        assertThat(sys).contains("title:");
        assertThat(sys).contains("content:");
        assertThat(sys).contains("salience:");
        assertThat(sys).contains("source_segment_indices:");
        assertThat(sys).contains("start_seconds:");
        assertThat(sys).contains("end_seconds:");
        assertThat(sys).contains("entity_type:");

        // Allowed-types CSV must reflect the caller-supplied subset.
        assertThat(sys).contains("ENTITY, SUMMARY");
        assertThat(sys).doesNotContain("TOPIC,");
        assertThat(sys).doesNotContain("CLAIM,");
    }

    @Test
    void systemMessageDefaultsToAllTypesWhenAllowListIsEmpty() {
        String sys = KnowledgeExtractionPrompt.systemMessage(List.of());
        for (KnowledgeUnitType t : KnowledgeUnitType.values()) {
            assertThat(sys).contains(t.name());
        }
    }

    @Test
    void userMessageRendersOneBlockPerSegmentWithGlobalIndexing() {
        MultimodalSegment s0 = segment(0.0, 10.0, "spoken text 0", "ocr 0");
        MultimodalSegment s1 = segment(10.0, 20.0, "spoken text 1", null);

        String user = KnowledgeExtractionPrompt.userMessage(List.of(s0, s1), 5);

        assertThat(user).contains("the following 2 segments");
        assertThat(user).contains("index: 5");
        assertThat(user).contains("index: 6");
        assertThat(user).contains("transcript: spoken text 0");
        assertThat(user).contains("on_screen_text: ocr 0");
        assertThat(user).contains("transcript: spoken text 1");
        // Segment 1 had null OCR — line should be omitted entirely.
        assertThat(user).doesNotContain("on_screen_text: \n");
        // Time scaffolding is rendered with two decimals.
        assertThat(user).contains("time: [0.00, 10.00]");
        assertThat(user).contains("time: [10.00, 20.00]");
    }

    @Test
    void userMessageReturnsEmptyForEmptyOrNullBatch() {
        assertThat(KnowledgeExtractionPrompt.userMessage(null, 0)).isEmpty();
        assertThat(KnowledgeExtractionPrompt.userMessage(List.of(), 0)).isEmpty();
    }

    @Test
    void estimateCharCountIsRoughlyProportionalToContent() {
        MultimodalSegment tiny = segment(0.0, 1.0, "x", null);
        MultimodalSegment big = segment(0.0, 1.0, "x".repeat(1000), "y".repeat(500));

        int tinyEst = KnowledgeExtractionPrompt.estimateCharCount(tiny);
        int bigEst = KnowledgeExtractionPrompt.estimateCharCount(big);

        // Tiny picks up a small fixed overhead; big should be ≥ the input chars
        // (1000 + 500 = 1500) plus the same overhead. ≥ 1500 is a robust check.
        assertThat(tinyEst).isLessThan(100);
        assertThat(bigEst).isGreaterThanOrEqualTo(1500);
    }

    @Test
    void promptVersionIsPositive() {
        // Bump the version when the schema changes; this just guards against accidental 0/-1.
        assertThat(KnowledgeExtractionPrompt.PROMPT_VERSION).isPositive();
    }

    private static MultimodalSegment segment(double start, double end, String transcript, String ocr) {
        return MultimodalSegment.builder()
                .id(UUID.randomUUID())
                .startSeconds(start)
                .endSeconds(end)
                .transcriptText(transcript)
                .ocrText(ocr)
                .segmentIndex(0)
                .build();
    }
}
