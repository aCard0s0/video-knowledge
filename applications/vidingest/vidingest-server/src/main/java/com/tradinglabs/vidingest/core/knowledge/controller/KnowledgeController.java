package com.tradinglabs.vidingest.core.knowledge.controller;

import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitDto;
import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.api.knowledge.RegenerateKnowledgeResult;
import com.tradinglabs.vidingest.api.knowledge.StaleKnowledgeReport;
import com.tradinglabs.vidingest.api.knowledge.SearchKnowledgeHit;
import com.tradinglabs.vidingest.api.paths.VidIngestApiPaths;
import com.tradinglabs.vidingest.core.knowledge.service.KnowledgeExtractionService;
import com.tradinglabs.vidingest.core.knowledge.service.KnowledgeQueryService;
import com.tradinglabs.vidingest.core.knowledge.service.SemanticKnowledgeSearchService;
import com.tradinglabs.vidingest.videos.service.VideoQueryService;
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
@Tag(name = "knowledge", description = "Knowledge-extraction APIs")
public class KnowledgeController {

    private final SemanticKnowledgeSearchService searchService;
    private final KnowledgeQueryService knowledgeQueryService;
    private final KnowledgeExtractionService extractionService;
    private final VideoQueryService videoQueryService;

    @GetMapping(VidIngestApiPaths.KNOWLEDGE_SEARCH)
    @Operation(operationId = "searchKnowledge", summary = "Semantic search over knowledge units",
            description = "pgvector cosine-distance lookup over vidingest_knowledge_units.embedding. "
                    + "Requires vidingest.search.semantic-enabled and a configured query embedding provider.")
    public List<SearchKnowledgeHit> search(
            @RequestParam("query") String query,
            @RequestParam(name = "type", required = false) KnowledgeUnitType type,
            @RequestParam(name = "limit", required = false) Integer limit
    ) throws IOException {
        return searchService.searchKnowledge(query, type, limit);
    }

    @GetMapping(VidIngestApiPaths.VIDEO_KNOWLEDGE)
    @Operation(operationId = "listVideoKnowledge", summary = "List knowledge units for a video",
            description = "Returns all knowledge units for the given video, optionally filtered by type. "
                    + "Ordered by created_at ascending so callers see the same order in which the LLM emitted them.")
    public List<KnowledgeUnitDto> listForVideo(
            @PathVariable UUID videoId,
            @RequestParam(name = "type", required = false) KnowledgeUnitType type
    ) {
        return knowledgeQueryService.listForVideo(videoId, type);
    }

    @GetMapping(VidIngestApiPaths.KNOWLEDGE_STALE)
    @Operation(operationId = "getStaleKnowledge",
            summary = "Videos extracted under an older prompt than this server sends",
            description = "metadata.prompt_version is written on every knowledge unit; this reads it. "
                    + "A prompt upgrade changes what extraction means, so older rows are not comparable "
                    + "with newer ones. Pair with POST /videos/{videoId}/knowledge/regenerate per video: "
                    + "one video is minutes of LLM time, so this reports rather than re-extracts. "
                    + "`truncated` says the limit cut the list short.")
    public StaleKnowledgeReport stale(
            @RequestParam(name = "limit", defaultValue = "100") int limit
    ) {
        return knowledgeQueryService.findStale(limit);
    }

    @PostMapping(VidIngestApiPaths.VIDEO_KNOWLEDGE_REGENERATE)
    @Operation(operationId = "regenerateKnowledge", summary = "Re-run knowledge extraction for a video",
            description = "Mirrors POST /videos/{id}/context/regenerate. Wipes existing knowledge units for the "
                    + "video and re-runs the M6 KnowledgeExtractionService against the current multimodal segments.")
    public RegenerateKnowledgeResult regenerate(@PathVariable UUID videoId) {
        int persisted = extractionService.extractKnowledge(videoQueryService.getById(videoId));
        log.info("REST regenerate knowledge: videoId={}, persisted={}", videoId, persisted);
        return new RegenerateKnowledgeResult(videoId.toString(), persisted);
    }
}
