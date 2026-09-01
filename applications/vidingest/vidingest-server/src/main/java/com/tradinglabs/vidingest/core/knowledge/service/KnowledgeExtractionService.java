package com.tradinglabs.vidingest.core.knowledge.service;

import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.config.KnowledgeExtractionConfig;
import com.tradinglabs.vidingest.core.fusion.domain.MultimodalSegment;
import com.tradinglabs.vidingest.core.fusion.repo.MultimodalSegmentRepository;
import com.tradinglabs.vidingest.core.knowledge.client.KnowledgeChatClient;
import com.tradinglabs.vidingest.core.knowledge.domain.KnowledgeUnit;
import com.tradinglabs.vidingest.core.knowledge.dto.KnowledgeUnitDraft;
import com.tradinglabs.vidingest.commons.ConflictException;
import com.tradinglabs.vidingest.core.knowledge.prompt.KnowledgeExtractionPrompt;
import com.tradinglabs.vidingest.core.knowledge.repo.KnowledgeUnitRepository;
import com.tradinglabs.vidingest.search.service.embedding.EmbeddingsClient;
import com.tradinglabs.vidingest.videos.domain.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LLM-driven extraction of typed knowledge units from the {@link MultimodalSegment} rows
 * produced by M5.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Load all multimodal segments for the video, ordered by segment_index.</li>
 *   <li>Build the fixed system prompt once.</li>
 *   <li>Batch segments under {@code maxInputCharsPerBatch} (rough char→token proxy) so we
 *       don't blow through the model's context window.</li>
 *   <li>Per batch: call the {@link KnowledgeChatClient}, accumulate drafts.</li>
 *   <li>Filter by allowed types + min salience; cap to {@code maxUnitsPerVideo}.</li>
 *   <li>Embed each surviving unit's content via the existing {@link EmbeddingsClient}
 *       (best-effort — embedding failures get logged + the unit still persists with a
 *       null embedding so the row remains queryable by type/time).</li>
 *   <li>Wipe prior rows and save the new batch, in one transaction.</li>
 * </ol>
 *
 * <p>A failed batch fails the whole video: what survives a partial run is partial coverage, and
 * replacing a complete extraction with it would drop the rest silently. The replace is atomic and
 * runs only once every batch has succeeded, so a failure leaves the previous units exactly as they
 * were and the pipeline run is marked FAILED.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeExtractionService {

    private final KnowledgeExtractionConfig config;
    private final KnowledgeChatClient chatClient;
    private final EmbeddingsClient embeddingsClient;
    private final MultimodalSegmentRepository multimodalSegmentRepository;
    private final KnowledgeUnitRepository knowledgeUnitRepository;
    private final TransactionOperations transactionOperations;

    /**
     * Extract-and-persist for one video. Returns the count of {@code KnowledgeUnit} rows
     * persisted. No-op (returns 0) when the video has no multimodal segments — common
     * when M5 fusion ran but found no signals to fuse.
     */
    public int extractKnowledge(Video video) {
        // The master switch, checked here rather than only in KnowledgePhase.applies(). The phase
        // gate never covered POST /videos/{id}/knowledge/regenerate, so with the feature off that
        // endpoint still called the chat model and hung for the full
        // vidingest.knowledge.read-timeout (10m by default) before failing. Same shape of guard as
        // SemanticKnowledgeSearchService uses for vidingest.search.semantic-enabled, and naming the
        // property is the point: a 409 that does not say which flag to flip is a worse answer than
        // the hang.
        if (!config.isEnabled()) {
            throw new ConflictException(
                    "Knowledge extraction is disabled. Set vidingest.knowledge.enabled=true.");
        }
        if (video == null) {
            throw new KnowledgeExtractionFailureException("Video is null");
        }
        if (video.getId() == null) {
            throw new KnowledgeExtractionFailureException("Video ID is null");
        }

        List<MultimodalSegment> segments =
                multimodalSegmentRepository.findByVideo_IdOrderBySegmentIndexAsc(video.getId());
        if (segments.isEmpty()) {
            log.warn("Knowledge extraction skipped: no multimodal segments for videoId={} "
                    + "(did FusePhase run?)", video.getId());
            return 0;
        }

        Set<KnowledgeUnitType> allowedTypes = allowedTypes();
        String systemPrompt = KnowledgeExtractionPrompt.systemMessage(config.getTypes());

        List<List<MultimodalSegment>> batches = batchByCharBudget(segments, config.getMaxInputCharsPerBatch());
        log.info("Knowledge extraction start: videoId={}, segments={}, batches={}, charBudget={}",
                video.getId(), segments.size(), batches.size(), config.getMaxInputCharsPerBatch());

        List<KnowledgeUnitDraft> allDrafts = new ArrayList<>();
        int batchesFailed = 0;
        int batchIndex = 0;
        int globalStartIndex = 0;
        for (List<MultimodalSegment> batch : batches) {
            String userPrompt = KnowledgeExtractionPrompt.userMessage(batch, globalStartIndex);
            try {
                List<KnowledgeUnitDraft> drafts = chatClient.extract(systemPrompt, userPrompt);
                allDrafts.addAll(drafts);
                log.info("Knowledge batch {} of {} returned {} drafts (videoId={})",
                        batchIndex + 1, batches.size(), drafts.size(), video.getId());
            } catch (KnowledgeExtractionFailureException e) {
                batchesFailed++;
                log.warn("Knowledge batch {} of {} failed (videoId={}): {}",
                        batchIndex + 1, batches.size(), video.getId(), e.getMessage());
            }
            batchIndex++;
            globalStartIndex += batch.size();
        }

        // Any failed batch aborts the replace. The rows on disk are a complete extraction; what
        // this run holds is a partial one, and swapping the first for the second silently drops
        // the coverage of every batch that failed while still reporting success. The phase is
        // idempotent, so a rerun costs LLM time — the alternative cost data.
        if (batchesFailed > 0) {
            throw new KnowledgeExtractionFailureException(
                    "Knowledge extraction failed for " + batchesFailed + " of " + batches.size()
                            + " batches; leaving the existing knowledge units in place");
        }

        List<KnowledgeUnitDraft> kept = filterAndCap(allDrafts, allowedTypes);
        List<KnowledgeUnit> entities = mapToEntities(video, kept);
        if (config.isEmbedContent() && !entities.isEmpty()) {
            embedContent(entities);
        }
        replaceAll(video.getId(), entities);

        log.info("Knowledge extraction complete: videoId={}, draftsFromLlm={}, persisted={}",
                video.getId(), allDrafts.size(), entities.size());
        return entities.size();
    }

    /**
     * Wipe-then-repopulate in one transaction. The wipe used to commit on its own <em>before</em>
     * the LLM loop, which meant any failure past that point — a dead ollama, a rogue response, a
     * filter that kept nothing — left the video with every prior unit destroyed and the phase
     * reporting a successful zero.
     *
     * <p>Moving the wipe here rather than wrapping the whole method keeps PR #5's constraint
     * intact: the batch loop above still holds no pooled connection, and this transaction spans
     * two statements against a 10-connection pool. An empty {@code entities} is a legitimate
     * result — the model was asked and found nothing salient — so it still clears the table.
     */
    private void replaceAll(java.util.UUID videoId, List<KnowledgeUnit> entities) {
        transactionOperations.executeWithoutResult(status -> {
            int wiped = knowledgeUnitRepository.deleteByVideo_Id(videoId);
            knowledgeUnitRepository.flush();
            if (wiped > 0) {
                log.info("Knowledge extraction replacing {} prior rows for videoId={}", wiped, videoId);
            }
            if (!entities.isEmpty()) {
                knowledgeUnitRepository.saveAll(entities);
            }
        });
    }

    /**
     * Greedy fixed-budget packing: accumulate segments into a batch until adding the next
     * one would push the running char count over {@code maxInputCharsPerBatch}, then start
     * a new batch. Visible for testing.
     */
    static List<List<MultimodalSegment>> batchByCharBudget(List<MultimodalSegment> segments, int budget) {
        List<List<MultimodalSegment>> batches = new ArrayList<>();
        if (segments == null || segments.isEmpty()) return batches;
        if (budget <= 0) budget = Integer.MAX_VALUE;

        List<MultimodalSegment> current = new ArrayList<>();
        int currentChars = 0;
        for (MultimodalSegment seg : segments) {
            int segChars = KnowledgeExtractionPrompt.estimateCharCount(seg);
            if (!current.isEmpty() && currentChars + segChars > budget) {
                batches.add(current);
                current = new ArrayList<>();
                currentChars = 0;
            }
            current.add(seg);
            currentChars += segChars;
        }
        if (!current.isEmpty()) batches.add(current);
        return batches;
    }

    private Set<KnowledgeUnitType> allowedTypes() {
        List<KnowledgeUnitType> configured = config.getTypes();
        if (configured == null || configured.isEmpty()) {
            return EnumSet.allOf(KnowledgeUnitType.class);
        }
        return EnumSet.copyOf(configured);
    }

    /**
     * Apply type whitelist + salience floor, then cap. Order-preserving so the resulting
     * rows reflect the LLM's emission order across batches.
     */
    List<KnowledgeUnitDraft> filterAndCap(List<KnowledgeUnitDraft> drafts, Set<KnowledgeUnitType> allowed) {
        if (drafts == null || drafts.isEmpty()) return List.of();
        double minSalience = config.getMinSalience();
        int cap = config.getMaxUnitsPerVideo();

        List<KnowledgeUnitDraft> out = new ArrayList<>(Math.min(drafts.size(), Math.max(cap, 16)));
        for (KnowledgeUnitDraft d : drafts) {
            if (d == null || d.type() == null || !allowed.contains(d.type())) continue;
            if (d.content() == null || d.content().isBlank()) continue;
            if (d.salience() != null && d.salience() < minSalience) continue;
            out.add(d);
            if (cap > 0 && out.size() >= cap) break;
        }
        return out;
    }

    private List<KnowledgeUnit> mapToEntities(Video video, List<KnowledgeUnitDraft> drafts) {
        List<KnowledgeUnit> out = new ArrayList<>(drafts.size());
        for (KnowledgeUnitDraft d : drafts) {
            out.add(KnowledgeUnit.builder()
                    .video(video)
                    .type(d.type())
                    .title(truncate(d.title(), 512))
                    .content(stripEmptySlotLines(d.content()))
                    .metadata(buildMetadata(d))
                    .startSeconds(d.startSeconds())
                    .endSeconds(d.endSeconds())
                    .build());
        }
        return out;
    }

    /**
     * Drop the rule-kind lines the model filled in with a non-answer.
     *
     * <p>A PROCEDURE's content is numbered steps followed by optional
     * {@code "Stop/abort: ..."}-style lines, and the prompt lists nine of those as a checklist of
     * rule kinds to look for. That list is what makes the extraction find the fallback path and the
     * settings — dropping it measured 4.7 fewer rules recovered per run — but handing a model nine
     * labels also invites it to answer all nine, so a video that never states a stop yields
     * {@code "Stop/abort: None specified"}.
     *
     * <p>Fixed here rather than in the prompt because it is deterministic and free: the instruction
     * that forbade it ("never write None specified") cost 2.7 recovered rules on its own, spending
     * the model's attention on formatting instead of on the material. A regex spends none. Only a
     * line whose entire value is a non-answer goes — a line naming a real rule is untouched, and a
     * unit that is nothing but such lines is left alone rather than emptied, since
     * {@code filterAndCap} has already accepted it and a blank body is worse than a useless line.
     */
    static String stripEmptySlotLines(String content) {
        if (content == null || content.isBlank() || content.indexOf(':') < 0) {
            return content;
        }
        String[] lines = content.split("\n", -1);
        StringBuilder kept = new StringBuilder(content.length());
        int dropped = 0;
        for (String line : lines) {
            if (EMPTY_SLOT_LINE.matcher(line).matches()) {
                dropped++;
                continue;
            }
            if (!kept.isEmpty()) kept.append('\n');
            kept.append(line);
        }
        if (dropped == 0) return content;
        String result = kept.toString().strip();
        return result.isEmpty() ? content : result;
    }

    /**
     * {@code "<Label>: <non-answer>"}, where the label is a word or two and the value says nothing.
     * Anchored on the whole line so a step mentioning "not specified" mid-sentence survives.
     */
    private static final java.util.regex.Pattern EMPTY_SLOT_LINE = java.util.regex.Pattern.compile(
            "\\s*[A-Za-z][A-Za-z/ ]{0,24}:\\s*"
                    + "(none|none specified|not specified|not stated|not mentioned|not given"
                    + "|n/?a|unspecified|unknown|none given|no|nothing)"
                    + "[.\\s]*",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Per-row metadata. Encoded as a {@code Map} so JPA stores it as JSONB via the
     * existing pattern used by {@code Video.metadata}. We keep things readable: salience
     * as a number, segment indices as an array, plus the prompt version and model name
     * so we can backfill / re-prompt later when we bump the version.
     */
    private Map<String, Object> buildMetadata(KnowledgeUnitDraft d) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (d.salience() != null) m.put("salience", d.salience());
        if (d.sourceSegmentIndices() != null && !d.sourceSegmentIndices().isEmpty()) {
            m.put("source_segment_indices", d.sourceSegmentIndices());
        }
        if (d.entityType() != null && !d.entityType().isBlank()) {
            m.put("entity_type", d.entityType());
        }
        m.put("prompt_version", KnowledgeExtractionPrompt.PROMPT_VERSION);
        m.put("chat_model", config.getChatModel());
        return m;
    }

    private void embedContent(List<KnowledgeUnit> entities) {
        if (entities.isEmpty()) return;
        List<String> inputs = new ArrayList<>(entities.size());
        for (KnowledgeUnit u : entities) {
            inputs.add(u.getContent() != null ? u.getContent() : "");
        }
        try {
            List<float[]> vectors = embeddingsClient.embed(inputs);
            if (vectors.size() != entities.size()) {
                log.warn("Embeddings client returned {} vectors for {} inputs; skipping embedding step",
                        vectors.size(), entities.size());
                return;
            }
            for (int i = 0; i < entities.size(); i++) {
                entities.get(i).setEmbedding(vectors.get(i));
            }
        } catch (IOException e) {
            // Soft failure: rows still get persisted (with null embedding) so they remain
            // queryable by type/time even when the embed model is offline.
            log.warn("Embedding step failed; persisting rows without embeddings: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
