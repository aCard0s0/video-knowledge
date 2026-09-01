package com.tradinglabs.vidingest.core.knowledge.prompt;

import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.core.fusion.domain.MultimodalSegment;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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
 * the parser in {@code KnowledgeUnitJson} in lockstep.
 *
 * <p><b>The prompt optimises for mechanism, not for brevity.</b> Version 2 asked the model to
 * "prefer fewer high-salience units" and to "deduplicate aggressively", and capped {@code content}
 * at four sentences. On an instructional video that instruction is actively wrong: a 3-minute
 * trading tutorial stating a full entry/stop/target method came back as one sentence
 * ("explains a trend continuation trading strategy"), an ENTITY naming the video's <em>sponsor</em>
 * and a CLAIM quoting its <em>ad copy</em> — 6 of the 19 rules the transcript actually stated, and
 * zero of the ordered steps. Three causes, all fixed here rather than by changing model or provider:
 * <ol>
 *   <li>the type vocabulary had no slot for a method, so a procedure could only be squeezed into
 *       a SUMMARY — hence {@link KnowledgeUnitType#PROCEDURE};</li>
 *   <li>every guidance line pushed toward compression and none toward capturing rules, conditions
 *       or numbers — hence the priority list below, which puts coverage and verbatim fidelity
 *       above concision;</li>
 *   <li>nothing told the model that a sponsor plug or a garbled interface watermark is not
 *       knowledge, and on that video OCR was <b>54% of the input</b> (5852 chars against 4938 of
 *       transcript) carrying the brand name 21 times across four garbled spellings plus browser
 *       chrome — so the sponsor was, by volume, the most repeated thing in the prompt.</li>
 * </ol>
 * Measured by replaying that video's eight fused segments against the same
 * Qwen2.5-14B-Instruct-4bit, three runs per prompt, scored against a hand-listed ground truth of
 * the 19 rules the transcript states: v2 recovered <b>4.3/19 with 0 PROCEDURE units</b>, v3
 * recovers <b>14.7/19 with 3</b> — the entry, the stop placement, the fib variant, the settings
 * and the no-sweep fallback all being things v2 dropped in every run.
 *
 * <p>The sweep ran eleven prompt variants, and its most useful output is what turned out
 * <em>not</em> to be true. Each of these is a change an editor would plausibly make:
 * <ul>
 *   <li><b>No output-shape example.</b> v2 carried one showing two units, and a variant of v3 that
 *       kept it returned exactly one unit in 3 of 3 runs — a 14B anchors on the example's
 *       cardinality, and the JSON schema already pins the shape far more reliably than prose. An
 *       example is worth re-adding only if it shows as many units as a dense batch should yield.</li>
 *   <li><b>The nine-slot checklist stays enumerated.</b> Folding it into a prose sentence
 *       ("preconditions, confirmations, entry, exit, …") cost 4.7 rules per run: the list works as
 *       a retrieval scaffold, not as formatting, and the model stops looking for rule kinds it was
 *       not handed.</li>
 *   <li><b>Do not add an instruction against filling those slots in.</b> The obvious follow-up to
 *       the line above — the model answers all nine, so tell it not to write "None specified" —
 *       cost a further 2.7 rules, attention spent on formatting rather than on the material.
 *       {@code KnowledgeExtractionService.stripEmptySlotLines} deletes those lines instead, where
 *       it is deterministic and free.</li>
 *   <li><b>Glossary order matters; allowed-types order does not.</b> Moving PROCEDURE to the front
 *       of the allowed-types CSV changed nothing measurable (11.3 against 11.0). PROCEDURE leading
 *       the <em>glossary</em> is worth the gap between 14.7 and 11.0. Hence
 *       {@link #GLOSSARY_ORDER}, which is fixed here rather than read from
 *       {@code vidingest.knowledge.types}.</li>
 *   <li><b>The wording is a measured artifact, not prose.</b> A paraphrase that preserved every
 *       structural element above — same sections, same slots, same priorities, reworded headers and
 *       sentences — measured <b>11.3 against 14.7</b>. The text in this class is byte-identical to
 *       the variant that scored 14.7, and it should be edited only behind a fresh three-run
 *       comparison, not tidied.</li>
 *   <li><b>The glossary is filtered to the allow-list.</b> Describing a type the model is then
 *       forbidden to emit spends output budget on units {@code filterAndCap} throws away.</li>
 * </ul>
 *
 * <p>Two things v3 does not fix. The model never reads the instrument name off the chart OCR (0/3
 * on every variant), so a unit's ticker comes from the transcript or not at all. And it still
 * opens a SUMMARY or TOPIC with "The video …" perhaps a third of the time despite being told twice
 * not to — at this model size the instruction is a preference, not a constraint.
 */
public final class KnowledgeExtractionPrompt {

    /**
     * Bump when the prompt or schema changes meaningfully — written to the persisted
     * {@code metadata.prompt_version} for offline auditing of extraction quality. A bump is also
     * the signal to re-run KNOWLEDGE on already-ingested videos: rows carrying an older version
     * were extracted under different instructions and are not comparable with new ones.
     */
    public static final int PROMPT_VERSION = 3;

    private KnowledgeExtractionPrompt() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * The system message that pins the LLM to our output contract. Stable for a given
     * {@link #PROMPT_VERSION}. The {@code allowedTypes} list controls which categories the LLM is
     * permitted to emit <em>and</em> which ones the glossary describes; we filter again after
     * parsing as belt-and-suspenders.
     */
    public static String systemMessage(List<KnowledgeUnitType> allowedTypes) {
        List<KnowledgeUnitType> types = allowedTypes == null || allowedTypes.isEmpty()
                ? List.of(KnowledgeUnitType.values())
                : allowedTypes;

        return """
                You are a knowledge-extraction assistant. Given timestamped multimodal segments from a video (each segment has a transcript, optional on-screen OCR text, and a time range), extract self-contained knowledge units that let a reader REPRODUCE what the video teaches without re-watching it.

                Output STRICTLY as a JSON object with EXACTLY ONE top-level key named "units" whose value is an array. Do NOT use any other root key (no "transcript", "summary", "items", "knowledge_units", etc.). Do NOT echo the transcript.

                Each element of the "units" array MUST be an object with these fields:
                  - type:                   one of [%s]
                  - title:                  short headline, <= 80 characters
                  - content:                self-contained body. Must stand alone - never say "as mentioned above" or "in this video"
                  - salience:               number in [0, 1] - how important / non-trivial this unit is
                  - source_segment_indices: array of 0-based segment indices this unit was drawn from
                  - start_seconds:          earliest segment.start_seconds you used
                  - end_seconds:            latest segment.end_seconds you used
                  - entity_type:            optional. Only set when type=ENTITY. One of [PERSON, ORGANIZATION, LOCATION, PRODUCT, TICKER, WORK, OTHER]

                What each type is for:

                %s

                Extraction rules, in priority order:
                  1. MECHANISM over description. If the video shows how to do something, the how IS the
                     knowledge. Capture rules, conditions, thresholds and sequence - never merely report that
                     rules were discussed.
                  2. COVERAGE beats brevity. Every distinct rule, condition, step, threshold, level, ratio or
                     setting stated in the segments must appear in some unit. There is no reward for emitting
                     fewer units than the material supports. Before you emit anything, work through the segments
                     in order and list to yourself every method taught, every worked example walked through, and
                     every rule, level and ratio stated; then emit a unit for each item on that list. As a floor,
                     each 30 seconds of instructional material should yield at least one PROCEDURE or CLAIM unit.
                     Omitting a rule because an earlier unit is "close enough" is the single worst failure here.
                  3. FIDELITY. Reproduce the speaker's terminology, numbers and levels verbatim. Never
                     generalise a specific value into a category.

                Never emit a unit for a call to action (subscribe, comment, DM, link in bio, free code on
                request), a sponsor plug, a discount or price, a marketing promise ("performs well", "insane",
                "prints money", "no drawdown"), a brand that appears only as a watermark, garbled OCR fragments,
                or anything equally true of any other video on the subject. A product becomes an ENTITY only when
                the video says what it does. Read on_screen_text solely for information the transcript lacks -
                numbers, labels, level names.

                Other rules:
                  - Emit ONLY the JSON object - no prose, no markdown fence, no comments.
                  - Do NOT invent content. If the segments don't support a type, omit units of that type rather
                    than fabricate. Empty "units": [] is fine.
                  - Deduplicate by MEANING, not by keyword. Different steps of one method are different units;
                    two mentions of the same entity are one unit.
                  - Aim for <= 40 units per batch.
                """.formatted(csvOf(types), glossaryFor(types));
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

    /**
     * Fixed reading order for the glossary, independent of {@code vidingest.knowledge.types}.
     *
     * <p>The glossary order and the allowed-types CSV order are <b>different things and measured
     * differently</b>. Moving PROCEDURE to the front of the CSV changed nothing (11.3 vs 11.0
     * rules recovered, 3 runs each); PROCEDURE leading the <em>glossary</em> is what separates
     * 14.7 from 11.0. The glossary is where the instruction weight sits, so it is a property of
     * the prompt and not of the operator's config — an operator reordering {@code types} to
     * express a preference must not be able to degrade extraction as a side effect.
     *
     * <p>PROCEDURE then CLAIM before the three descriptive types is the order that measured best;
     * it was not derived from a theory and no theory here has survived contact with the numbers.
     * Change it only with a fresh three-run comparison.
     */
    private static final List<KnowledgeUnitType> GLOSSARY_ORDER = List.of(
            KnowledgeUnitType.PROCEDURE,
            KnowledgeUnitType.CLAIM,
            KnowledgeUnitType.ENTITY,
            KnowledgeUnitType.TOPIC,
            KnowledgeUnitType.SUMMARY,
            KnowledgeUnitType.QUESTION);

    /** One glossary block per permitted type, in {@link #GLOSSARY_ORDER}, blank-line separated. */
    private static String glossaryFor(List<KnowledgeUnitType> types) {
        Set<KnowledgeUnitType> allowed = types.isEmpty()
                ? EnumSet.allOf(KnowledgeUnitType.class)
                : EnumSet.copyOf(types);
        return GLOSSARY_ORDER.stream()
                .filter(allowed::contains)
                .map(t -> "  - " + t.name() + ": " + guidanceFor(t))
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * What one type means, in the model's terms. PROCEDURE carries the most instruction because it
     * is the type that decides whether an instructional video survives extraction at all, and
     * because "numbered WHEN/THEN steps" is the one output shape that a rule cannot be summarised
     * out of — prose invites the model to compress the sequence back into a description.
     *
     * <p>These strings are a <b>measured artifact, not prose to tidy</b>. Every paraphrase tried
     * during the v3 sweep lost coverage: rewording this block while keeping its structure cost 3.5
     * recovered rules per run. Re-run the three-run comparison before editing a sentence here.
     */
    private static String guidanceFor(KnowledgeUnitType type) {
        return switch (type) {
            case PROCEDURE -> """
                    a method the video teaches. This is the most valuable type whenever the video
                        demonstrates HOW to do something. Write "content" as numbered steps separated by newlines,
                        each step in the form "N. WHEN <trigger condition> THEN <action>". Use the speaker's own
                        terms. After the steps, add any of these lines that the video states, one per line:
                          "Preconditions: ..."   "Confirmation: ..."   "Entry: ..."   "Stop/abort: ..."
                          "Target: ..."          "Settings: ..."       "Invalidated if: ..."
                          "Alternative: ..."     "Tools: ..."
                        Copy every number, level, ratio, timeframe and instrument name exactly as spoken. A
                        PROCEDURE that omits a rule the video stated is a defect. Emit one PROCEDURE per distinct
                        method: the core method, each variant or fallback path, and each separate workflow the video
                        walks through (for example configuring or customising a tool). A worked example that adds a
                        rule not already captured is its own PROCEDURE.""";
            case CLAIM -> """
                    a falsifiable assertion about how something behaves or performs. Not a restatement of
                        a procedure step, and not a promise about a product.""";
            case ENTITY -> """
                    a named thing that carries meaning for the subject matter, stating what it is and
                        what role it plays.""";
            case TOPIC -> """
                    a subject area covered that is not itself a procedure or a claim.""";
            case SUMMARY -> """
                    emit EXACTLY ONE, covering the segments given. Name the method and its key steps in
                        one or two sentences. Never begin with "The video" or "This video" and never describe the
                        material instead of stating it - for a knife-sharpening video, "The video explains how to
                        sharpen a knife" is a failed SUMMARY and "A blade is ground at 20 degrees on a 1000-grit stone
                        until a burr forms along the full edge, then stropped to remove it" is a good one.""";
            case QUESTION -> """
                    an open question the video raises and does not answer, phrased as the
                        question itself.""";
        };
    }

    private static String csvOf(List<KnowledgeUnitType> types) {
        return types.stream().map(Enum::name).collect(Collectors.joining(", "));
    }

    private static String formatSeconds(double s) {
        // Two decimals is plenty — sub-10ms precision is irrelevant for knowledge units.
        return String.format(java.util.Locale.ROOT, "%.2f", s);
    }
}
