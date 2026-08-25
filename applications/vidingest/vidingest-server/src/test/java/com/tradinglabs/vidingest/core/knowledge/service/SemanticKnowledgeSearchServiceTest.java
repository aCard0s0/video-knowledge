package com.tradinglabs.vidingest.core.knowledge.service;

import com.tradinglabs.vidingest.config.VideoSearchConfig;
import com.tradinglabs.vidingest.core.knowledge.repo.KnowledgeUnitRepository;
import com.tradinglabs.vidingest.search.exceptions.SemanticSearchUnavailableException;
import com.tradinglabs.vidingest.search.service.embedding.QueryEmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The hit-count default and cap moved here from {@code KnowledgeController}, which restated a
 * clamp this service already applied — same magic 50 in two places. The limit is nullable now,
 * so the null path is the one that would regress silently.
 */
@ExtendWith(MockitoExtension.class)
class SemanticKnowledgeSearchServiceTest {

    @Mock
    private KnowledgeUnitRepository knowledgeUnitRepository;

    @Mock
    private QueryEmbeddingProvider queryEmbeddingProvider;

    private SemanticKnowledgeSearchService service;

    @BeforeEach
    void setUp() throws Exception {
        VideoSearchConfig config = new VideoSearchConfig();
        config.setSemanticEnabled(true);
        service = new SemanticKnowledgeSearchService(knowledgeUnitRepository, config, queryEmbeddingProvider);
        lenient().when(queryEmbeddingProvider.embed(anyString())).thenReturn(Optional.of(new float[1536]));
        lenient().when(knowledgeUnitRepository.findSimilarKnowledgeProjections(anyString(), any(), anyInt()))
                .thenReturn(List.of());
    }

    @Test
    void defaultsToTenWhenNoLimitIsRequested() throws Exception {
        service.searchKnowledge("apple", null, null);
        assertThat(capturedLimit()).isEqualTo(10);
    }

    @Test
    void capsTheLimitAtFifty() throws Exception {
        service.searchKnowledge("apple", null, 999);
        assertThat(capturedLimit()).isEqualTo(50);
    }

    @Test
    void raisesANonPositiveLimitToOne() throws Exception {
        service.searchKnowledge("apple", null, 0);
        assertThat(capturedLimit()).isEqualTo(1);
    }

    @Test
    void returnsNothingForABlankQueryWithoutEmbeddingIt() throws Exception {
        assertThat(service.searchKnowledge("   ", null, 10)).isEmpty();
    }

    @Test
    void refusesWhenSemanticSearchIsDisabled() {
        VideoSearchConfig disabled = new VideoSearchConfig();
        disabled.setSemanticEnabled(false);
        var offline = new SemanticKnowledgeSearchService(knowledgeUnitRepository, disabled, queryEmbeddingProvider);

        assertThatThrownBy(() -> offline.searchKnowledge("apple", null, 10))
                .isInstanceOf(SemanticSearchUnavailableException.class);
    }

    private int capturedLimit() {
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(knowledgeUnitRepository).findSimilarKnowledgeProjections(anyString(), any(), limit.capture());
        return limit.getValue();
    }
}
