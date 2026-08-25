package com.tradinglabs.vidingest.search.service;

import com.tradinglabs.vidingest.config.VideoSearchConfig;
import com.tradinglabs.vidingest.search.exceptions.SemanticSearchUnavailableException;
import com.tradinglabs.vidingest.search.repo.ContextChunkRepository;
import com.tradinglabs.vidingest.search.service.embedding.QueryEmbeddingProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticSearchService {

    private final ContextChunkRepository contextChunkRepository;
    private final VideoSearchConfig searchConfig;
    private final QueryEmbeddingProvider queryEmbeddingProvider;

    @Transactional(readOnly = true)
    public List<SearchResult> searchSimilarChunks(String query, int limit) throws IOException {
        if (!searchConfig.isSemanticEnabled()) {
            throw new SemanticSearchUnavailableException(
                    "Semantic search is disabled. Set vidingest.search.semantic-enabled=true and configure a QueryEmbeddingProvider.");
        }
        float[] queryEmbedding = queryEmbeddingProvider.embed(query)
                .orElseThrow(() -> new SemanticSearchUnavailableException(
                        "No query embedding provider is configured. Implement QueryEmbeddingProvider to enable search."));
        int normalizedLimit = Math.max(1, Math.min(limit, 50));
        String pgVector = toPgVectorLiteral(queryEmbedding);
        return contextChunkRepository.findSimilarChunkProjections(pgVector, normalizedLimit).stream()
                .map(this::toSearchResult)
                .toList();
    }

    private String toPgVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private SearchResult toSearchResult(ContextChunkRepository.SimilarChunkProjection row) {
        String snippet = row.getContent() == null
                ? ""
                : row.getContent().substring(0, Math.min(220, row.getContent().length()));
        return new SearchResult(
                row.getChunkId(),
                row.getVideoId(),
                row.getChunkIndex(),
                snippet,
                row.getVideoTitle(),
                row.getChannelName(),
                row.getFilePath());
    }

    public record SearchResult(
            UUID chunkId,
            UUID videoId,
            Integer chunkIndex,
            String snippet,
            String videoTitle,
            String channelName,
            String filePath) {
    }
}

