package com.tradinglabs.vidingest.core.knowledge.service;

import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitDto;
import com.tradinglabs.vidingest.api.knowledge.StaleKnowledgeReport;
import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.core.knowledge.mapper.KnowledgeUnitMapper;
import com.tradinglabs.vidingest.core.knowledge.prompt.KnowledgeExtractionPrompt;
import com.tradinglabs.vidingest.core.knowledge.repo.KnowledgeUnitRepository;
import com.tradinglabs.vidingest.videos.service.VideoQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read model over {@code vidingest_knowledge_units}. Ordered by {@code created_at} ascending so
 * callers see the units in the order the LLM emitted them.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeQueryService {

    private final KnowledgeUnitRepository knowledgeUnitRepository;
    private final KnowledgeUnitMapper knowledgeUnitMapper;
    private final VideoQueryService videoQueryService;

    @Transactional(readOnly = true)
    public List<KnowledgeUnitDto> listForVideo(UUID videoId, KnowledgeUnitType type) {
        videoQueryService.ensureExists(videoId);
        // Use the native projection that skips the `embedding vector(1536)` column —
        // entity-shape SELECTs trip the pgvector array-delimiter bug in the PostgreSQL
        // JDBC driver and 500 the call. See KnowledgeUnitRepository.findViewsByVideoId.
        return knowledgeUnitRepository.findViewsByVideoId(videoId, type == null ? null : type.name()).stream()
                .map(knowledgeUnitMapper::toDto)
                .toList();
    }

    /**
     * Which videos were extracted under an older prompt than this server now sends.
     *
     * <p>Capped rather than paged: the list shrinks to nothing as an operator works through it, so
     * a cursor would be machinery for a report whose natural end state is empty. {@code truncated}
     * says when the cap bit, because a silently short list reads as "finished".
     */
    public StaleKnowledgeReport findStale(int limit) {
        int current = KnowledgeExtractionPrompt.PROMPT_VERSION;
        int capped = Math.max(1, Math.min(limit, MAX_STALE_REPORT));

        // limit + 1, so a full page can be distinguished from a page that happens to end exactly at
        // the cap. Without it, `truncated` would be wrong precisely when it matters.
        List<KnowledgeUnitRepository.StalePromptVersionView> rows =
                knowledgeUnitRepository.findVideosBelowPromptVersion(current, capped + 1);

        boolean truncated = rows.size() > capped;
        List<StaleKnowledgeReport.StaleKnowledgeVideo> videos = rows.stream()
                .limit(capped)
                .map(r -> new StaleKnowledgeReport.StaleKnowledgeVideo(
                        r.getVideoId(),
                        r.getVideoTitle(),
                        r.getChannelName(),
                        r.getUnitCount(),
                        r.getMinPromptVersion(),
                        r.getMaxPromptVersion(),
                        r.getLastExtractedAt()))
                .toList();

        log.info("Stale knowledge report: currentPromptVersion={}, staleVideos={}, truncated={}",
                current, videos.size(), truncated);
        return new StaleKnowledgeReport(current, videos.size(), truncated, videos);
    }

    /** Hard ceiling on the report, whatever the caller asks for. */
    static final int MAX_STALE_REPORT = 500;
}
