package com.tradinglabs.vidingest.search.controller;

import com.tradinglabs.vidingest.api.paths.VidIngestApiPaths;
import com.tradinglabs.vidingest.api.search.SearchChunkResult;
import com.tradinglabs.vidingest.search.service.SearchChunkResultMapper;
import com.tradinglabs.vidingest.search.service.SemanticSearchService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping(value = VidIngestApiPaths.SEARCH, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Slf4j
@Validated
@Tag(name = "search", description = "Semantic search APIs")
public class SearchController {

    private final SemanticSearchService semanticSearchService;
    private final SearchChunkResultMapper searchChunkResultMapper;

    @GetMapping
    @Operation(operationId = "searchChunks", summary = "Search similar context chunks")
    public List<SearchChunkResult> search(
            @RequestParam("query") @NotBlank String query,
            @RequestParam(name = "limit", defaultValue = "5") @Min(1) @Max(50) int limit
    ) throws IOException {
        log.info("REST search: limit={}, query='{}'", limit, query);
        return semanticSearchService.searchSimilarChunks(query, limit).stream()
                .map(searchChunkResultMapper::toResult)
                .toList();
    }
}

