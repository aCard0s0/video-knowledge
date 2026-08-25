package com.tradinglabs.vidingest.core.knowledge.controller;

import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitDto;
import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.api.knowledge.RegenerateKnowledgeResult;
import com.tradinglabs.vidingest.api.knowledge.SearchKnowledgeHit;
import com.tradinglabs.vidingest.api.paths.VidIngestApiPaths;
import com.tradinglabs.vidingest.core.knowledge.mapper.KnowledgeUnitMapper;
import com.tradinglabs.vidingest.core.knowledge.repo.KnowledgeUnitRepository;
import com.tradinglabs.vidingest.core.knowledge.service.KnowledgeExtractionService;
import com.tradinglabs.vidingest.core.knowledge.service.SemanticKnowledgeSearchService;
import com.tradinglabs.vidingest.videos.exceptions.VideoNotFoundException;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for knowledge extraction (M8). Combines the cross-video search endpoint
 * at {@code /api/v1/knowledge/search} with the per-video read / regenerate endpoints. We
 * keep them in one controller because they share the same mapper + service dependencies
 * and the paths are tightly related.
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "knowledge", description = "Knowledge-extraction APIs (M6/M8)")
public class KnowledgeController {

    private static final int DEFAULT_SEARCH_LIMIT = 10;
    private static final int MAX_SEARCH_LIMIT = 50;

    private final SemanticKnowledgeSearchService searchService;
    private final KnowledgeExtractionService extractionService;
    private final KnowledgeUnitRepository knowledgeUnitRepository;
    private final KnowledgeUnitMapper mapper;
    private final VideoRepository videoRepository;

    @GetMapping(VidIngestApiPaths.KNOWLEDGE_SEARCH)
    @Operation(summary = "Semantic search over knowledge units",
            description = "pgvector cosine-distance lookup over vidingest_knowledge_units.embedding. "
                    + "Requires vidingest.search.semantic-enabled and a configured query embedding provider.")
    public List<SearchKnowledgeHit> search(
            @RequestParam("query") String query,
            @RequestParam(name = "type", required = false) KnowledgeUnitType type,
            @RequestParam(name = "limit", required = false) Integer limit
    ) throws IOException {
        int normalized = limit != null
                ? Math.max(1, Math.min(limit, MAX_SEARCH_LIMIT))
                : DEFAULT_SEARCH_LIMIT;
        return searchService.searchKnowledge(query, type, normalized);
    }

    @GetMapping(VidIngestApiPaths.VIDEO_KNOWLEDGE)
    @Operation(summary = "List knowledge units for a video",
            description = "Returns all knowledge units for the given video, optionally filtered by type. "
                    + "Ordered by created_at ascending so callers see the same order in which the LLM emitted them.")
    public List<KnowledgeUnitDto> listForVideo(
            @PathVariable UUID videoId,
            @RequestParam(name = "type", required = false) KnowledgeUnitType type
    ) {
        ensureVideoExists(videoId);
        // Use the native projection that skips the `embedding vector(1536)` column —
        // entity-shape SELECTs trip the pgvector array-delimiter bug in the PostgreSQL
        // JDBC driver and 500 the call. See KnowledgeUnitRepository.findViewsByVideoId.
        var rows = knowledgeUnitRepository.findViewsByVideoId(videoId, type == null ? null : type.name());
        return rows.stream().map(mapper::toDto).toList();
    }

    @PostMapping(VidIngestApiPaths.VIDEO_KNOWLEDGE_REGENERATE)
    @Operation(summary = "Re-run knowledge extraction for a video",
            description = "Mirrors POST /videos/{id}/context/regenerate. Wipes existing knowledge units for the "
                    + "video and re-runs the M6 KnowledgeExtractionService against the current multimodal segments.")
    public RegenerateKnowledgeResult regenerate(@PathVariable UUID videoId) {
        var video = videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));
        int persisted = extractionService.extractKnowledge(video);
        log.info("REST regenerate knowledge: videoId={}, persisted={}", videoId, persisted);
        return new RegenerateKnowledgeResult(videoId.toString(), persisted);
    }

    private void ensureVideoExists(UUID videoId) {
        if (!videoRepository.existsById(videoId)) {
            throw new VideoNotFoundException(videoId);
        }
    }
}
