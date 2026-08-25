package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.domain.PipelineErrorCode;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The startup reconciler used to fail every {@code IN_PROGRESS} run outright. A run whose
 * items all completed can be left {@code IN_PROGRESS} by a lost aggregation update, and
 * condemning that run is the bug rather than the fix — so it re-derives first.
 */
@ExtendWith(MockitoExtension.class)
class ProgressPipelineRunReconcilerTest {

    @Mock
    private RunQueryService queryService;
    @Mock
    private RunAggregationService runAggregationService;
    @Mock
    private RunItemLeaseService runItemLeaseService;

    @InjectMocks
    private ProgressPipelineRunReconciler reconciler;

    @Test
    void leavesRunsAloneWhenTheirItemsReDeriveToATerminalStatus() {
        UUID runId = UUID.randomUUID();
        when(queryService.listPipelineRunsByStatus(RunStatus.IN_PROGRESS)).thenReturn(List.of(run(runId)));
        when(runAggregationService.refreshRunState(runId)).thenReturn(RunStatus.COMPLETED);

        reconciler.reconcileInProgressPipelineRuns();

        verify(runAggregationService, never()).markFailed(any(), any(), any());
    }

    @Test
    void failsRunsThatAreStillGenuinelyInProgressAfterReDeriving() {
        UUID runId = UUID.randomUUID();
        when(queryService.listPipelineRunsByStatus(RunStatus.IN_PROGRESS)).thenReturn(List.of(run(runId)));
        when(runAggregationService.refreshRunState(runId)).thenReturn(RunStatus.IN_PROGRESS);

        reconciler.reconcileInProgressPipelineRuns();

        verify(runAggregationService).markFailed(eq(runId), eq(PipelineErrorCode.UNEXPECTED), any());
    }

    @Test
    void leavesRunsAloneWhileAnotherInstanceHoldsALiveLease() {
        // This instance's startup says nothing about the others: failing the run here would
        // condemn a peer's in-flight work.
        UUID runId = UUID.randomUUID();
        when(queryService.listPipelineRunsByStatus(RunStatus.IN_PROGRESS)).thenReturn(List.of(run(runId)));
        when(runAggregationService.refreshRunState(runId)).thenReturn(RunStatus.IN_PROGRESS);
        when(runItemLeaseService.runHasLiveLease(runId)).thenReturn(true);

        reconciler.reconcileInProgressPipelineRuns();

        verify(runAggregationService, never()).markFailed(any(), any(), any());
    }

    @Test
    void keepsGoingWhenOneRunCannotBeReDerived() {
        UUID broken = UUID.randomUUID();
        UUID stillRunning = UUID.randomUUID();
        when(queryService.listPipelineRunsByStatus(RunStatus.IN_PROGRESS))
                .thenReturn(List.of(run(broken), run(stillRunning)));
        when(runAggregationService.refreshRunState(broken)).thenThrow(new IllegalStateException("gone"));
        when(runAggregationService.refreshRunState(stillRunning)).thenReturn(RunStatus.IN_PROGRESS);

        reconciler.reconcileInProgressPipelineRuns();

        verify(runAggregationService, never()).markFailed(eq(broken), any(), any());
        verify(runAggregationService).markFailed(eq(stillRunning), eq(PipelineErrorCode.UNEXPECTED), any());
    }

    private static PipelineRun run(UUID id) {
        return PipelineRun.builder().id(id).status(RunStatus.IN_PROGRESS).build();
    }
}
