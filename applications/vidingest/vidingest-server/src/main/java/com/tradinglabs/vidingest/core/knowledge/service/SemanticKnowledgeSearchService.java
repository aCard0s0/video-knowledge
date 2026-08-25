package com.tradinglabs.vidingest.core.knowledge.service;

import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.api.knowledge.SearchKnowledgeHit;
import com.tradinglabs.vidingest.config.VideoSearchConfig;
import com.tradinglabs.vidingest.core.knowledge.repo.KnowledgeUnitRepository;
import com.tradinglabs.vidingest.search.exceptions.SemanticSearchUnavailableException;
import com.tradinglabs.vidingest.search.service.embedding.QueryEmbeddingProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Mirror of {@code SemanticSearchService} but against {@code vidingest_knowledge_units}.
 *
 * <p>Reuses the same {@link QueryEmbeddingProvider} the chunk search uses — knowledge
 * units are embedded with the same model + dimensions as context chunks, so query
 * embeddings round-trip without conversion. Configuration is shared too: the master
 * switch is {@code vidingest.search.semantic-enabled}, the same flag that gates chunk
 * search.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticKnowledgeSearchService {

    private final KnowledgeUnitRepository knowledgeUnitRepository;
    private final VideoSearchConfig searchConfig;
    private final QueryEmbeddingProvider queryEmbeddingProvider;

    @Transactional(readOnly = true)
    public List<SearchKnowledgeHit> searchKnowledge(String query, KnowledgeUnitType typeFilter, int limit)
            throws IOException {
        if (!searchConfig.isSemanticEnabled()) {
            throw new SemanticSearchUnavailableException(
                    "Semantic search is disabled. Set vidingest.search.semantic-enabled=true.");
        }
        if (query == null || query.isBlank()) {
            return List.of();
        }
        float[] queryEmbedding = queryEmbeddingProvider.embed(query)
                .orElseThrow(() -> new SemanticSearchUnavailableException(
                        "No query embedding provider is configured. Implement QueryEmbeddingProvider to enable search."));

        int normalizedLimit = Math.max(1, Math.min(limit, 50));
        String pgVector = toPgVectorLiteral(queryEmbedding);
        String typeName = typeFilter != null ? typeFilter.name() : null;

        return knowledgeUnitRepository
                .findSimilarKnowledgeProjections(pgVector, typeName, normalizedLimit)
                .stream()
                .map(this::toHit)
                .toList();
    }

    private SearchKnowledgeHit toHit(KnowledgeUnitRepository.SimilarKnowledgeProjection row) {
        String snippet = row.getContent() == null
                ? ""
                : row.getContent().substring(0, Math.min(220, row.getContent().length()));
        KnowledgeUnitType type;
        try {
            type = row.getType() != null ? KnowledgeUnitType.valueOf(row.getType()) : null;
        } catch (IllegalArgumentException e) {
            type = null;  // defensive — should never happen given the enum-constrained column
        }
        return new SearchKnowledgeHit(
                stringId(row.getKnowledgeUnitId()),
                stringId(row.getVideoId()),
                type,
                row.getTitle(),
                snippet,
                row.getVideoTitle(),
                row.getChannelName(),
                row.getStartSeconds(),
                row.getEndSeconds()
        );
    }

    private static String stringId(UUID id) {
        return id != null ? id.toString() : null;
    }

    private static String toPgVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
