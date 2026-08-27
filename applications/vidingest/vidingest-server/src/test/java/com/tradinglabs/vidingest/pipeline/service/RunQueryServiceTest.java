package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The sort whitelist, which is a trust boundary: {@code sortBy} arrives from the query string and
 * ends up as a JPA property name inside {@code Sort.by}.
 */
class RunQueryServiceTest {

    private PipelineRunRepository repository;
    private RunQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(PipelineRunRepository.class);
        service = new RunQueryService(repository);
        when(repository.findAll(any(Pageable.class))).thenReturn(emptyPage());
        when(repository.findByStatus(any(RunStatus.class), any(Pageable.class))).thenReturn(emptyPage());
    }

    private Page<PipelineRun> emptyPage() {
        return new PageImpl<>(List.of());
    }

    private Sort sortUsed() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(repository).findAll(pageable.capture());
        return pageable.getValue().getSort();
    }

    @Test
    void ordersByCreatedAtDescendingByDefault() {
        service.listPipelineRunsPage("ALL", 0, 20, "createdAt");
        assertThat(sortUsed()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Test
    void ordersByUpdatedAtWhenAsked() {
        service.listPipelineRunsPage("ALL", 0, 20, "updatedAt");
        assertThat(sortUsed()).isEqualTo(Sort.by(Sort.Direction.DESC, "updatedAt"));
    }

    @Test
    void fallsBackToTheDefaultRatherThanOrderingByAnArbitraryEntityField() {
        service.listPipelineRunsPage("ALL", 0, 20, "errorCode");
        assertThat(sortUsed()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Test
    void fallsBackWhenTheColumnIsMissingOrNonsense() {
        service.listPipelineRunsPage("ALL", 0, 20, null);
        assertThat(sortUsed()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Test
    void isCaseSensitive_soTheWhitelistCannotBeStepppedAroundByCasing() {
        service.listPipelineRunsPage("ALL", 0, 20, "UPDATEDAT");
        assertThat(sortUsed()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Test
    void appliesTheSameSortToAFilteredListing() {
        service.listPipelineRunsPage("FAILED", 0, 20, "updatedAt");

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(repository).findByStatus(org.mockito.ArgumentMatchers.eq(RunStatus.FAILED), pageable.capture());
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "updatedAt"));
    }
}
