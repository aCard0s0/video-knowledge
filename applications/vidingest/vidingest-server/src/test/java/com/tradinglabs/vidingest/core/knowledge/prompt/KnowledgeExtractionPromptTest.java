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

    /**
     * The glossary is filtered too, not just the CSV. A described-but-forbidden type is worse than
     * an undescribed one: the model spends output budget emitting units that {@code filterAndCap}
     * then discards, and on a small model those crowd out the types that were asked for.
     */
    @Test
    void systemMessageDescribesOnlyTheAllowedTypes() {
        String sys = KnowledgeExtractionPrompt.systemMessage(List.of(
                KnowledgeUnitType.PROCEDURE,
                KnowledgeUnitType.ENTITY
        ));

        assertThat(sys).contains("- PROCEDURE:");
        assertThat(sys).contains("- ENTITY:");
        assertThat(sys).doesNotContain("- TOPIC:");
        assertThat(sys).doesNotContain("- CLAIM:");
        assertThat(sys).doesNotContain("- SUMMARY:");
        assertThat(sys).doesNotContain("- QUESTION:");
    }

    /**
     * The three instructions v2 shipped that made an instructional video extract to nothing usable.
     * Re-adding any of them is the regression this test exists to catch, so it asserts their
     * absence by the exact wording rather than by outcome — the outcome needs a live LLM.
     */
    @Test
    void systemMessageDoesNotAskTheModelToCompress() {
        String sys = KnowledgeExtractionPrompt.systemMessage(List.of());

        assertThat(sys).doesNotContain("Prefer fewer");
        assertThat(sys).doesNotContain("Deduplicate aggressively");
        assertThat(sys).doesNotContain("1-4 sentences");
        assertThat(sys).doesNotContain("1–4 sentences");
    }

    /**
     * The instructions that replaced them. A prompt that permits PROCEDURE without demanding the
     * WHEN/THEN step shape gets prose back, and prose is what a rule gets summarised out of.
     */
    @Test
    void systemMessageAsksForMechanismCoverageAndVerbatimValues() {
        String sys = KnowledgeExtractionPrompt.systemMessage(List.of());

        assertThat(sys).contains("MECHANISM over description");
        assertThat(sys).contains("COVERAGE beats brevity");
        assertThat(sys).contains("FIDELITY");
        assertThat(sys).contains("WHEN <trigger condition> THEN <action>");
        assertThat(sys).contains("verbatim");
    }

    /**
     * The nine rule-kind slots stay an enumerated list. Folding them into a prose sentence measured
     * 4.7 fewer rules recovered per run: the list is a retrieval scaffold, not formatting, and the
     * model stops hunting for rule kinds it was not handed.
     *
     * <p>Deliberately no instruction here against filling them in with "None specified". Adding one
     * measured 2.7 rules worse, which is inside the harness's ~±3-rule noise floor and so is not an
     * established effect — but the alternative costs nothing either way, since
     * {@code KnowledgeExtractionService.stripEmptySlotLines} strips those lines with a regex rather
     * than with prompt budget. Absence is asserted so the weaker option is not reintroduced on the
     * assumption that it must help.
     */
    @Test
    void systemMessageEnumeratesTheRuleKindChecklist() {
        String sys = KnowledgeExtractionPrompt.systemMessage(List.of(KnowledgeUnitType.PROCEDURE));

        for (String slot : List.of("Preconditions:", "Confirmation:", "Entry:", "Stop/abort:",
                "Target:", "Settings:", "Invalidated if:", "Alternative:", "Tools:")) {
            assertThat(sys).contains(slot);
        }
        assertThat(sys).doesNotContain("None specified");
        // The general prohibition still stands; only the slot-specific restatement was removed.
        assertThat(sys).contains("Do NOT invent content");
    }

    /**
     * No illustrative output example, and this asserts its absence rather than its shape. v2 carried
     * one showing two units and a v3 variant that kept it returned exactly one unit in 3 of 3 runs —
     * a 14B anchors on the example's cardinality. The JSON schema in {@code KnowledgeUnitJson} is
     * what actually pins the shape, so the example bought nothing and cost most of the coverage.
     */
    @Test
    void systemMessageCarriesNoOutputExampleToAnchorUnitCount() {
        String sys = KnowledgeExtractionPrompt.systemMessage(List.of());

        assertThat(sys).doesNotContain("Example of the EXACT output shape");
        assertThat(sys).doesNotContain("{\"units\":[\n");
        // The contract itself must still be stated in prose — it is the only thing left saying it.
        assertThat(sys).contains("EXACTLY ONE top-level key named \"units\"");
    }

    /**
     * OCR on a monetised video is mostly sponsor overlay and garbled interface chrome, and under v2
     * the model made the sponsor the ENTITY and its ad copy the CLAIM. The exclusion has to survive
     * any future trimming of the rules block.
     */
    @Test
    void systemMessageExcludesPromotionalAndInterfaceNoise() {
        String sys = KnowledgeExtractionPrompt.systemMessage(List.of());

        assertThat(sys).contains("call to action");
        assertThat(sys).contains("sponsor plug");
        assertThat(sys).contains("watermark");
        assertThat(sys).contains("A product becomes an ENTITY only when");
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

    /**
     * Pinned, not merely positive: {@code metadata.prompt_version} is the only way to tell rows
     * extracted under different instructions apart, so a prompt edit that forgets the bump makes
     * old and new units indistinguishable after the fact.
     */
    @Test
    void promptVersionMatchesTheCurrentPromptGeneration() {
        assertThat(KnowledgeExtractionPrompt.PROMPT_VERSION).isEqualTo(3);
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
