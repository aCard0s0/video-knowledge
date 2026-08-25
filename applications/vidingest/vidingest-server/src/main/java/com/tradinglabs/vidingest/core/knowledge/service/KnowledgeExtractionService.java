package com.tradinglabs.vidingest.core.knowledge.service;

import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.config.KnowledgeExtractionConfig;
import com.tradinglabs.vidingest.core.fusion.domain.MultimodalSegment;
import com.tradinglabs.vidingest.core.fusion.repo.MultimodalSegmentRepository;
import com.tradinglabs.vidingest.core.knowledge.client.KnowledgeChatClient;
import com.tradinglabs.vidingest.core.knowledge.domain.KnowledgeUnit;
import com.tradinglabs.vidingest.core.knowledge.dto.KnowledgeUnitDraft;
import com.tradinglabs.vidingest.core.knowledge.prompt.KnowledgeExtractionPrompt;
import com.tradinglabs.vidingest.core.knowledge.repo.KnowledgeUnitRepository;
import com.tradinglabs.vidingest.search.service.embedding.EmbeddingsClient;
import com.tradinglabs.vidingest.videos.domain.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
 *   <li>Wipe prior rows, save the new batch.</li>
 * </ol>
 *
 * <p>Per-batch LLM failures are logged and skipped so a single rogue response doesn't
 * fail the whole video; if <i>every</i> batch fails, the service throws
 * {@link KnowledgeExtractionFailureException} so the pipeline run is marked FAILED.
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

    /**
     * Extract-and-persist for one video. Returns the count of {@code KnowledgeUnit} rows
     * persisted. No-op (returns 0) when the video has no multimodal segments — common
     * when M5 fusion ran but found no signals to fuse.
     */
    public int extractKnowledge(Video video) {
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

        // Wipe upfront so a mid-run crash leaves the DB consistent (empty + FAILED run)
        // rather than interleaving stale + fresh rows.
        int wiped = wipePrior(video.getId());
        if (wiped > 0) {
            log.info("Knowledge extraction wiped {} prior rows for videoId={}", wiped, video.getId());
        }

        Set<KnowledgeUnitType> allowedTypes = allowedTypes();
        String systemPrompt = KnowledgeExtractionPrompt.systemMessage(config.getTypes());

        List<List<MultimodalSegment>> batches = batchByCharBudget(segments, config.getMaxInputCharsPerBatch());
        log.info("Knowledge extraction start: videoId={}, segments={}, batches={}, charBudget={}",
                video.getId(), segments.size(), batches.size(), config.getMaxInputCharsPerBatch());

        List<KnowledgeUnitDraft> allDrafts = new ArrayList<>();
        int batchesSucceeded = 0;
        int batchesFailed = 0;
        int batchIndex = 0;
        int globalStartIndex = 0;
        for (List<MultimodalSegment> batch : batches) {
            String userPrompt = KnowledgeExtractionPrompt.userMessage(batch, globalStartIndex);
            try {
                List<KnowledgeUnitDraft> drafts = chatClient.extract(systemPrompt, userPrompt);
                allDrafts.addAll(drafts);
                batchesSucceeded++;
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

        if (batchesSucceeded == 0 && batchesFailed > 0) {
            throw new KnowledgeExtractionFailureException(
                    "Knowledge extraction failed for every batch (count=" + batchesFailed + ")");
        }

        List<KnowledgeUnitDraft> kept = filterAndCap(allDrafts, allowedTypes);
        if (kept.isEmpty()) {
            log.info("Knowledge extraction completed with 0 surviving units for videoId={}", video.getId());
            return 0;
        }

        List<KnowledgeUnit> entities = mapToEntities(video, kept);
        if (config.isEmbedContent()) {
            embedContent(entities);
        }
        persist(entities);

        log.info("Knowledge extraction complete: videoId={}, draftsFromLlm={}, persisted={}, batchesFailed={}",
                video.getId(), allDrafts.size(), entities.size(), batchesFailed);
        return entities.size();
    }

    // Not transactional, and deliberately so: the wipe commits before the LLM batch loop and
    // the persist after it, because wrapping both would hold a pooled connection across every
    // chat round-trip. Each half is atomic on its own — the repository's bulk delete carries
    // its own @Transactional, and saveAll supplies its own.
    private int wipePrior(java.util.UUID videoId) {
        int n = knowledgeUnitRepository.deleteByVideo_Id(videoId);
        knowledgeUnitRepository.flush();
        return n;
    }

    private void persist(List<KnowledgeUnit> entities) {
        if (entities.isEmpty()) return;
        knowledgeUnitRepository.saveAll(entities);
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
                    .content(d.content())
                    .metadata(buildMetadata(d))
                    .startSeconds(d.startSeconds())
                    .endSeconds(d.endSeconds())
                    .build());
        }
        return out;
    }

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
