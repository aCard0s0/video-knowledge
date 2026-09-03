package com.tradinglabs.vidingest.search.service;

import com.tradinglabs.vidingest.api.search.SearchChunkResult;
import org.springframework.stereotype.Component;

@Component
public class SearchChunkResultMapper {

    public SearchChunkResult toResult(SemanticSearchService.SearchResult row) {
        return new SearchChunkResult(
                row.chunkId(),
                row.videoId(),
                row.chunkIndex(),
                row.snippet(),
                row.videoTitle(),
                row.channelName(),
                row.filePath()
        );
    }
}
