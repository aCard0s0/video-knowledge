package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        when(repository.findPage(any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
    }

    private Sort sortUsed() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findPage(any(), any(), pageable.capture());
        return pageable.getValue().getSort();
    }

    @Test
    void ordersByCreatedAtDescendingByDefault() {
        service.listPipelineRunsPage("ALL", 0, 20, "createdAt", null);
        assertThat(sortUsed()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Test
    void ordersByUpdatedAtWhenAsked() {
        service.listPipelineRunsPage("ALL", 0, 20, "updatedAt", null);
        assertThat(sortUsed()).isEqualTo(Sort.by(Sort.Direction.DESC, "updatedAt"));
    }

    @Test
    void fallsBackToTheDefaultRatherThanOrderingByAnArbitraryEntityField() {
        service.listPipelineRunsPage("ALL", 0, 20, "errorCode", null);
        assertThat(sortUsed()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Test
    void fallsBackWhenTheColumnIsMissingOrNonsense() {
        service.listPipelineRunsPage("ALL", 0, 20, null, null);
        assertThat(sortUsed()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Test
    void appliesTheSameSortToAFilteredListing() {
        service.listPipelineRunsPage("FAILED", 0, 20, "updatedAt", null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findPage(eq(RunStatus.FAILED), eq(null), pageable.capture());
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "updatedAt"));
    }

    /**
     * "ALL" and "no lower bound" are both a {@code null} on the way to the query, and the query is
     * what turns each of them into a dropped predicate. The service must not re-branch on them.
     */
    @Test
    void passesBothFiltersThroughIncludingTheirAbsence() {
        OffsetDateTime since = OffsetDateTime.parse("2026-08-28T00:00:00+01:00");
        service.listPipelineRunsPage("ALL", 0, 20, "createdAt", since);

        verify(repository).findPage(eq(null), eq(since), any(Pageable.class));
    }
}
