package com.tradinglabs.vidingest.search.service;

import com.tradinglabs.vidingest.api.search.SearchChunkResult;
import org.springframework.stereotype.Component;

@Component
public class SearchChunkResultMapper {

    public SearchChunkResult toResult(SemanticSearchService.SearchResult row) {
        return new SearchChunkResult(
                row.chunkId() != null ? row.chunkId().toString() : "",
                row.videoId() != null ? row.videoId().toString() : "",
                row.chunkIndex(),
                safe(row.snippet()),
                safe(row.videoTitle()),
                safe(row.channelName()),
                safe(row.filePath())
        );
    }

    private String safe(String value) {
        return value != null ? value : "";
    }
}

