package com.tradinglabs.vidingest.search.service;

import com.tradinglabs.vidingest.config.VideoSearchConfig;
import com.tradinglabs.vidingest.search.exceptions.SemanticSearchUnavailableException;
import com.tradinglabs.vidingest.search.repo.ContextChunkRepository;
import com.tradinglabs.vidingest.search.service.embedding.QueryEmbeddingProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticSearchServiceTest {

    @Test
    void searchThrows409StyleExceptionWhenDisabled() {
        ContextChunkRepository repo = mock(ContextChunkRepository.class);
        VideoSearchConfig cfg = new VideoSearchConfig();
        cfg.setSemanticEnabled(false);
        QueryEmbeddingProvider provider = mock(QueryEmbeddingProvider.class);

        SemanticSearchService svc = new SemanticSearchService(repo, cfg, provider);

        assertThatThrownBy(() -> svc.searchSimilarChunks("Hinduism", 8))
                .isInstanceOf(SemanticSearchUnavailableException.class)
                .hasMessageContaining("Semantic search is disabled");
    }

    @Test
    void searchThrowsWhenNoEmbeddingProviderConfigured() throws Exception {
        ContextChunkRepository repo = mock(ContextChunkRepository.class);
        VideoSearchConfig cfg = new VideoSearchConfig();
        cfg.setSemanticEnabled(true);
        QueryEmbeddingProvider provider = mock(QueryEmbeddingProvider.class);
        when(provider.embed("Hinduism")).thenReturn(Optional.empty());

        SemanticSearchService svc = new SemanticSearchService(repo, cfg, provider);

        assertThatThrownBy(() -> svc.searchSimilarChunks("Hinduism", 8))
                .isInstanceOf(SemanticSearchUnavailableException.class)
                .hasMessageContaining("No query embedding provider is configured");
    }

    @Test
    void searchReturnsMappedResultsWhenEnabled() throws Exception {
        ContextChunkRepository repo = mock(ContextChunkRepository.class);
        VideoSearchConfig cfg = new VideoSearchConfig();
        cfg.setSemanticEnabled(true);

        QueryEmbeddingProvider provider = mock(QueryEmbeddingProvider.class);
        when(provider.embed("Hinduism")).thenReturn(Optional.of(new float[1536]));

        ContextChunkRepository.SimilarChunkProjection row = mock(ContextChunkRepository.SimilarChunkProjection.class);
        when(row.getChunkId()).thenReturn(UUID.fromString("f0afeb11-2bd9-470f-88fc-caa620632bc4"));
        when(row.getVideoId()).thenReturn(UUID.fromString("eadf1978-f899-4f22-90ea-0929879f8253"));
        when(row.getChunkIndex()).thenReturn(3);
        when(row.getContent()).thenReturn("A".repeat(300));
        when(row.getVideoTitle()).thenReturn("Intro to Hinduism");
        when(row.getChannelName()).thenReturn("World Religions");
        when(row.getFilePath()).thenReturn("/videos/world/intro.mp4");

        when(repo.findSimilarChunkProjections(anyString(), anyInt())).thenReturn(List.of(row));

        SemanticSearchService svc = new SemanticSearchService(repo, cfg, provider);
        List<SemanticSearchService.SearchResult> results = svc.searchSimilarChunks("Hinduism", 8);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().videoTitle()).isEqualTo("Intro to Hinduism");
        assertThat(results.getFirst().snippet()).hasSize(220);
    }
}

