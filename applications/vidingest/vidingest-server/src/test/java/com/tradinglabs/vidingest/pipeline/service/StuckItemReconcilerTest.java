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
import static org.mockito.ArgumentMatchers.anyCollection;
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
 * ownership set (blind to other instances, never wrong about this one) and the lease (sees
 * every instance, goes stale if this one stops heartbeating).
 *
 * <p>The sweep also covers PENDING, because an item whose owner died before it ever reached the
 * gate never becomes IN_PROGRESS — and nothing else in the system would look at it again.
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
        when(runItemRepository.findByStatusInAndPhaseUpdatedAtBefore(anyCollection(), any()))
                .thenReturn(List.of(item));
        when(pipelineService.isItemOwned(item.getId())).thenReturn(true);

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
        when(runItemRepository.findByStatusInAndPhaseUpdatedAtBefore(anyCollection(), any()))
                .thenReturn(List.of(item));
        when(pipelineService.isItemOwned(item.getId())).thenReturn(false);

        reconciler().reconcileStuckItems();

        verify(runItemLifecycleService, never()).markFailed(any(), any(), any());
    }

    @Test
    void reapsItemsWhoseLeaseHasExpired() {
        // An owner that stopped heartbeating is an owner that died.
        PipelineRunItem item = staleItem();
        item.setLeaseOwner("4242@other-host");
        item.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(30));
        when(runItemRepository.findByStatusInAndPhaseUpdatedAtBefore(anyCollection(), any()))
                .thenReturn(List.of(item));
        when(pipelineService.isItemOwned(item.getId())).thenReturn(false);

        reconciler().reconcileStuckItems();

        verify(runItemLifecycleService).markFailed(eq(item.getId()), eq(PipelineErrorCode.UNEXPECTED), any());
    }

    @Test
    void failsItemsAbandonedByAPreviousProcess() {
        PipelineRunItem item = staleItem();
        when(runItemRepository.findByStatusInAndPhaseUpdatedAtBefore(anyCollection(), any()))
                .thenReturn(List.of(item));
        when(pipelineService.isItemOwned(item.getId())).thenReturn(false);

        reconciler().reconcileStuckItems();

        verify(runItemLifecycleService).markFailed(eq(item.getId()), eq(PipelineErrorCode.UNEXPECTED), any());
        verify(runAggregationService).refreshRunState(item.getPipelineRun().getId());
    }

    @Test
    void reapsTheAbandonedItemEvenWhenALiveOneSharesTheSweep() {
        PipelineRunItem live = staleItem();
        PipelineRunItem abandoned = staleItem();
        when(runItemRepository.findByStatusInAndPhaseUpdatedAtBefore(anyCollection(), any()))
                .thenReturn(List.of(live, abandoned));
        when(pipelineService.isItemOwned(live.getId())).thenReturn(true);
        when(pipelineService.isItemOwned(abandoned.getId())).thenReturn(false);

        reconciler().reconcileStuckItems();

        verify(runItemLifecycleService, never()).markFailed(eq(live.getId()), any(), any());
        verify(runItemLifecycleService).markFailed(eq(abandoned.getId()), any(), any());
    }

    /**
     * The orphan case. A process that dies with items still queued leaves them PENDING forever:
     * they never reach IN_PROGRESS, so the old sweep never saw them, and retry refused anything
     * that was not FAILED. The run stayed IN_PROGRESS and those URLs were unrecoverable.
     */
    @Test
    void failsPendingItemsAbandonedBeforeTheyEverStarted() {
        PipelineRunItem queued = stalePendingItem();
        when(runItemRepository.findByStatusInAndPhaseUpdatedAtBefore(anyCollection(), any()))
                .thenReturn(List.of(queued));
        when(pipelineService.isItemOwned(queued.getId())).thenReturn(false);

        reconciler().reconcileStuckItems();

        verify(runItemLifecycleService).markFailed(eq(queued.getId()), eq(PipelineErrorCode.UNEXPECTED), any());
        verify(runAggregationService).refreshRunState(queued.getPipelineRun().getId());
    }

    /**
     * The other half of that: an item can sit PENDING for hours purely because it is queued behind
     * the concurrency gate. Reaping it would fail work that is about to run.
     */
    @Test
    void leavesPendingItemsAloneWhileThisJvmStillOwnsThem() {
        PipelineRunItem queued = stalePendingItem();
        when(runItemRepository.findByStatusInAndPhaseUpdatedAtBefore(anyCollection(), any()))
                .thenReturn(List.of(queued));
        when(pipelineService.isItemOwned(queued.getId())).thenReturn(true);

        reconciler().reconcileStuckItems();

        verify(runItemLifecycleService, never()).markFailed(any(), any(), any());
    }

    private static PipelineRunItem staleItem() {
        return staleItem(RunStatus.IN_PROGRESS, PipelineRunPhase.KNOWLEDGE);
    }

    private static PipelineRunItem stalePendingItem() {
        return staleItem(RunStatus.PENDING, PipelineRunPhase.CREATED);
    }

    private static PipelineRunItem staleItem(RunStatus status, PipelineRunPhase phase) {
        return PipelineRunItem.builder()
                .id(UUID.randomUUID())
                .pipelineRun(PipelineRun.builder().id(UUID.randomUUID()).build())
                .url("https://example.com/v")
                .status(status)
                .phase(phase)
                .phaseUpdatedAt(LocalDateTime.now().minusHours(2))
                .attempt(1)
                .build();
    }
}
