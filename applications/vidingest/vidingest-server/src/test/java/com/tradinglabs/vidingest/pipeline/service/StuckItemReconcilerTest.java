package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.domain.PipelineErrorCode;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code phase_updated_at} only moves on a phase *transition*, so a phase that legitimately
 * runs for hours — KNOWLEDGE and DIARIZE both do on a long video — is indistinguishable from
 * abandoned work by timestamp alone. Failing a live item is not cosmetic: the operator sees
 * FAILED, retries, and a second worker wipes-and-repopulates the same video alongside the
 * first. The reconciler therefore asks two questions that fail in opposite directions: the
 * in-flight set (blind to other instances, never wrong about this one) and the lease (sees
 * every instance, goes stale if this one stops heartbeating).
 */
@ExtendWith(MockitoExtension.class)
class StuckItemReconcilerTest {

    @Mock
    private PipelineRunItemRepository runItemRepository;
    @Mock
    private RunItemLifecycleService runItemLifecycleService;
    @Mock
    private RunAggregationService runAggregationService;
    @Mock
    private PipelineService pipelineService;
    @Mock
    private PipelineMetrics pipelineMetrics;

    private StuckItemReconciler reconciler() {
        return new StuckItemReconciler(runItemRepository, runItemLifecycleService,
                runAggregationService, pipelineService, pipelineMetrics, Duration.ofHours(1));
    }

    @Test
    void leavesStaleLookingItemsAloneWhileThisJvmIsStillRunningThem() {
        PipelineRunItem item = staleItem();
        when(runItemRepository.findByStatusAndPhaseUpdatedAtBefore(eq(RunStatus.IN_PROGRESS), any()))
                .thenReturn(List.of(item));
        when(pipelineService.isItemInFlight(item.getId())).thenReturn(true);

        reconciler().reconcileStuckItems();

        verify(runItemLifecycleService, never()).markFailed(any(), any(), any());
        verify(pipelineMetrics, never()).incrementFailed(any());
    }

    @Test
    void leavesItemsAloneWhileAnotherInstanceHoldsALiveLease() {
        // Not in *this* JVM's in-flight set — the whole point. Before the lease, a second
        // instance reaped the first instance's live work and the operator's retry then ran a
        // second worker over the same video.
        PipelineRunItem item = staleItem();
        item.setLeaseOwner("4242@other-host");
        item.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(runItemRepository.findByStatusAndPhaseUpdatedAtBefore(eq(RunStatus.IN_PROGRESS), any()))
                .thenReturn(List.of(item));
        when(pipelineService.isItemInFlight(item.getId())).thenReturn(false);

        reconciler().reconcileStuckItems();

        verify(runItemLifecycleService, never()).markFailed(any(), any(), any());
    }

    @Test
    void reapsItemsWhoseLeaseHasExpired() {
        // An owner that stopped heartbeating is an owner that died.
        PipelineRunItem item = staleItem();
        item.setLeaseOwner("4242@other-host");
        item.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(30));
        when(runItemRepository.findByStatusAndPhaseUpdatedAtBefore(eq(RunStatus.IN_PROGRESS), any()))
                .thenReturn(List.of(item));
        when(pipelineService.isItemInFlight(item.getId())).thenReturn(false);

        reconciler().reconcileStuckItems();

        verify(runItemLifecycleService).markFailed(eq(item.getId()), eq(PipelineErrorCode.UNEXPECTED), any());
    }

    @Test
    void failsItemsAbandonedByAPreviousProcess() {
        PipelineRunItem item = staleItem();
        when(runItemRepository.findByStatusAndPhaseUpdatedAtBefore(eq(RunStatus.IN_PROGRESS), any()))
                .thenReturn(List.of(item));
        when(pipelineService.isItemInFlight(item.getId())).thenReturn(false);

        reconciler().reconcileStuckItems();

        verify(runItemLifecycleService).markFailed(eq(item.getId()), eq(PipelineErrorCode.UNEXPECTED), any());
        verify(runAggregationService).refreshRunState(item.getPipelineRun().getId());
    }

    @Test
    void reapsTheAbandonedItemEvenWhenALiveOneSharesTheSweep() {
        PipelineRunItem live = staleItem();
        PipelineRunItem abandoned = staleItem();
        when(runItemRepository.findByStatusAndPhaseUpdatedAtBefore(eq(RunStatus.IN_PROGRESS), any()))
                .thenReturn(List.of(live, abandoned));
        when(pipelineService.isItemInFlight(live.getId())).thenReturn(true);
        when(pipelineService.isItemInFlight(abandoned.getId())).thenReturn(false);

        reconciler().reconcileStuckItems();

        verify(runItemLifecycleService, never()).markFailed(eq(live.getId()), any(), any());
        verify(runItemLifecycleService).markFailed(eq(abandoned.getId()), any(), any());
    }

    private static PipelineRunItem staleItem() {
        return PipelineRunItem.builder()
                .id(UUID.randomUUID())
                .pipelineRun(PipelineRun.builder().id(UUID.randomUUID()).build())
                .url("https://example.com/v")
                .status(RunStatus.IN_PROGRESS)
                .phase(PipelineRunPhase.KNOWLEDGE)
                .phaseUpdatedAt(LocalDateTime.now().minusHours(2))
                .attempt(1)
                .build();
    }
}
