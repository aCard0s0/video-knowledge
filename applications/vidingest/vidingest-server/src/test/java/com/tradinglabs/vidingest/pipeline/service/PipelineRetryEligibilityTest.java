package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunResponse;
import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunResponse.ItemStatus;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import com.tradinglabs.vidingest.pipeline.exceptions.RunRetryNotAllowedException;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import com.tradinglabs.vidingest.pipeline.service.phase.PipelinePhaseRegistry;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which run items a retry may re-run, and what a retry that can re-run nothing is allowed to do
 * to the run row.
 *
 * <p>Both were wrong together. Retry accepted only FAILED items, so work abandoned as PENDING by a
 * dying process was unreachable — and {@code prepareRetry} ran <em>before</em> that check, so the
 * refusal still moved the run out of FAILED and the next attempt was rejected outright with
 * "Only FAILED pipeline runs can be retried". A restart mid-run could therefore strand a batch of
 * URLs with no route back to them at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PipelineRetryEligibilityTest {

    @Mock private VideoRepository videoRepository;
    @Mock private RunLifecycleService runLifecycle;
    @Mock private RunItemLifecycleService runItemLifecycleService;
    @Mock private RunAggregationService runAggregationService;
    @Mock private PipelineRunItemRepository pipelineRunItemRepository;
    @Mock private PipelinePhaseRegistry pipelinePhaseRegistry;
    @Mock private PipelineErrorClassifier pipelineErrorClassifier;
    @Mock private PipelineMetrics pipelineMetrics;
    @Mock private RunItemLeaseService runItemLeaseService;

    private ExecutorService executor;
    private PipelineService service;

    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(runLifecycle.getPipelineRun(runId))
                .thenReturn(PipelineRun.builder().id(runId).status(RunStatus.FAILED).build());
        // No phases registered: an enqueued item runs an empty pipeline and finishes immediately.
        // This test is about what gets enqueued, not what happens afterwards.
        when(pipelinePhaseRegistry.phases()).thenReturn(List.of());
        // Both gates open by default — a mock's false would silently reject every enqueue.
        // Individual tests close one to pin the refusal path.
        when(runItemLeaseService.claim(any())).thenReturn(true);
        when(runItemLeaseService.acquire(any())).thenReturn(true);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        service = new PipelineService(
                videoRepository, runLifecycle, runItemLifecycleService, runAggregationService,
                pipelineRunItemRepository, pipelinePhaseRegistry, pipelineErrorClassifier,
                pipelineMetrics, runItemLeaseService, executor, 4);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** The run-level gate is separate from the per-item one and still answers 409 first. */
    @Test
    void refusesOutrightWhenTheRunItselfIsNotFailed() {
        when(runLifecycle.getPipelineRun(runId))
                .thenReturn(PipelineRun.builder().id(runId).status(RunStatus.COMPLETED).build());
        when(runItemLifecycleService.listItems(runId)).thenReturn(List.of(item(RunStatus.FAILED)));

        assertThatThrownBy(() -> service.enqueueRetryBatch(runId, Set.of()))
                .isInstanceOf(RunRetryNotAllowedException.class);

        verify(runLifecycle, never()).prepareRetry(any(), any());
    }

    @Test
    void retriesAnItemAbandonedWhileStillPending() {
        PipelineRunItem pending = item(RunStatus.PENDING);
        when(runItemLifecycleService.listItems(runId)).thenReturn(List.of(pending));

        CreatePipelineRunResponse response = service.enqueueRetryBatch(runId, Set.of());

        assertThat(response.items()).singleElement()
                .extracting(CreatePipelineRunResponse.ItemResult::status)
                .isEqualTo(ItemStatus.ACCEPTED);
        verify(runItemLifecycleService).prepareRetry(pending.getId());
    }

    /**
     * The state-destroying half. Nothing is retryable here, so the run must be left exactly as it
     * was — still FAILED, and still retryable once the operator has dealt with the cause.
     */
    @Test
    void aRetryThatAcceptsNothingLeavesTheRunUntouched() {
        when(runItemLifecycleService.listItems(runId))
                .thenReturn(List.of(item(RunStatus.CANCELLED), item(RunStatus.COMPLETED)));

        CreatePipelineRunResponse response = service.enqueueRetryBatch(runId, Set.of());

        assertThat(response.items())
                .extracting(CreatePipelineRunResponse.ItemResult::status)
                .containsOnly(ItemStatus.REJECTED);
        assertThat(response.items())
                .extracting(CreatePipelineRunResponse.ItemResult::reason)
                .containsExactly("run item was cancelled", "run item already completed");
        verify(runLifecycle, never()).prepareRetry(any(), any());
        verify(runItemLifecycleService, never()).prepareRetry(any());
    }

    @Test
    void refusesAnItemAnotherInstanceHoldsALeaseOn() {
        PipelineRunItem leased = item(RunStatus.IN_PROGRESS);
        leased.setLeaseOwner("4242@some-other-host");
        leased.setLeaseExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        when(runItemLifecycleService.listItems(runId)).thenReturn(List.of(leased));

        CreatePipelineRunResponse response = service.enqueueRetryBatch(runId, Set.of());

        assertThat(response.items()).singleElement()
                .extracting(CreatePipelineRunResponse.ItemResult::reason)
                .isEqualTo("run item is already running");
        verify(runLifecycle, never()).prepareRetry(any(), any());
    }

    /**
     * An expired lease is the signature of an owner that stopped heartbeating — crashed, killed,
     * or cut off from the database. That is precisely the work a retry exists to recover.
     */
    @Test
    void retriesAnItemWhoseLeaseHasExpired() {
        PipelineRunItem stale = item(RunStatus.IN_PROGRESS);
        stale.setLeaseOwner("4242@a-host-that-is-gone");
        stale.setLeaseExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        when(runItemLifecycleService.listItems(runId)).thenReturn(List.of(stale));

        CreatePipelineRunResponse response = service.enqueueRetryBatch(runId, Set.of());

        assertThat(response.items()).singleElement()
                .extracting(CreatePipelineRunResponse.ItemResult::status)
                .isEqualTo(ItemStatus.ACCEPTED);
        verify(runLifecycle).prepareRetry(runId, Set.of());
    }

    @Test
    void retriesTheFailedItemsAndRejectsTheRestInOneBatch() {
        PipelineRunItem failed = item(RunStatus.FAILED);
        PipelineRunItem done = item(RunStatus.COMPLETED);
        when(runItemLifecycleService.listItems(runId)).thenReturn(List.of(failed, done));

        CreatePipelineRunResponse response = service.enqueueRetryBatch(runId, Set.of());

        assertThat(response.items())
                .extracting(CreatePipelineRunResponse.ItemResult::status)
                .containsExactly(ItemStatus.ACCEPTED, ItemStatus.REJECTED);
        verify(runLifecycle).prepareRetry(runId, Set.of());
        verify(runItemLifecycleService).prepareRetry(failed.getId());
        verify(runItemLifecycleService, never()).prepareRetry(done.getId());
    }

    /**
     * Absent is not empty. The runs board has no phase picker, so its retry sends no list at all —
     * and that has to mean "as the run was configured", not "run everything". Sending an empty list
     * turned OCR and KNOWLEDGE back on for a run created without them.
     */
    @Test
    void aRetryThatNamesNoPhasesInheritsTheRunsOwnSkipSet() {
        Set<PipelineRunPhase> configured = EnumSet.of(PipelineRunPhase.OCR, PipelineRunPhase.KNOWLEDGE);
        when(runLifecycle.getPipelineRun(runId)).thenReturn(PipelineRun.builder()
                .id(runId)
                .status(RunStatus.FAILED)
                .skipPhases(configured)
                .build());
        when(runItemLifecycleService.listItems(runId)).thenReturn(List.of(item(RunStatus.FAILED)));

        CreatePipelineRunResponse response = service.enqueueRetryBatch(runId, null);

        assertThat(response.items()).singleElement()
                .extracting(CreatePipelineRunResponse.ItemResult::status)
                .isEqualTo(ItemStatus.ACCEPTED);
        verify(runLifecycle).prepareRetry(runId, configured);
    }

    /** An explicit empty list is the operator asking for every enabled phase, and still wins. */
    @Test
    void anExplicitEmptyListOverridesTheRunsOwnSkipSet() {
        when(runLifecycle.getPipelineRun(runId)).thenReturn(PipelineRun.builder()
                .id(runId)
                .status(RunStatus.FAILED)
                .skipPhases(EnumSet.of(PipelineRunPhase.OCR))
                .build());
        when(runItemLifecycleService.listItems(runId)).thenReturn(List.of(item(RunStatus.FAILED)));

        service.enqueueRetryBatch(runId, Set.of());

        verify(runLifecycle).prepareRetry(runId, Set.of());
    }

    /**
     * The claim is the submission guard. Eligibility said yes for both concurrent retries — the
     * race the run-row lock closes upstream — so the last line of defence is the claim at
     * enqueue time: whoever loses it must answer REJECTED, never submit a second worker.
     */
    @Test
    void refusesToSubmitAnItemWhoseClaimIsAlreadyHeld() {
        PipelineRunItem failed = item(RunStatus.FAILED);
        when(runItemLifecycleService.listItems(runId)).thenReturn(List.of(failed));
        when(runItemLeaseService.claim(failed.getId())).thenReturn(false);

        CreatePipelineRunResponse response = service.enqueueRetryBatch(runId, Set.of());

        assertThat(response.items()).singleElement()
                .extracting(CreatePipelineRunResponse.ItemResult::status)
                .isEqualTo(ItemStatus.REJECTED);
        assertThat(response.items()).singleElement()
                .extracting(CreatePipelineRunResponse.ItemResult::reason)
                .isEqualTo("run item is already running");
    }

    /** Same guard on the single-item door. */
    @Test
    void singleItemRetryRefusesToSubmitWhenTheClaimIsAlreadyHeld() {
        PipelineRunItem failed = item(RunStatus.FAILED);
        when(pipelineRunItemRepository.findByIdAndPipelineRun_Id(failed.getId(), runId))
                .thenReturn(java.util.Optional.of(failed));
        when(runItemLeaseService.claim(failed.getId())).thenReturn(false);

        CreatePipelineRunResponse response = service.enqueueRetryItem(runId, failed.getId(), null);

        assertThat(response.items()).singleElement()
                .extracting(CreatePipelineRunResponse.ItemResult::status)
                .isEqualTo(ItemStatus.REJECTED);
    }

    /**
     * The cross-instance half of the same guard: the claim was won here, but by execution time
     * another instance holds a live database lease. The item must not run — no completion, no
     * aggregation — and the claim must still be dropped so the reconciler's view stays honest.
     */
    @Test
    void anItemWhoseLeaseAnotherInstanceHoldsIsNotExecuted() {
        PipelineRunItem failed = item(RunStatus.FAILED);
        when(runItemLifecycleService.listItems(runId)).thenReturn(List.of(failed));
        when(runItemLeaseService.acquire(failed.getId())).thenReturn(false);

        service.enqueueRetryBatch(runId, Set.of());

        // The submitted task ends by dropping the claim; waiting on that pins "ran to the end".
        verify(runItemLeaseService, org.mockito.Mockito.timeout(5000)).unclaim(failed.getId());
        verify(runItemLifecycleService, never()).markCompleted(any());
        verify(runAggregationService, never()).refreshRunState(any());
    }

    private PipelineRunItem item(RunStatus status) {
        return PipelineRunItem.builder()
                .id(UUID.randomUUID())
                .pipelineRun(PipelineRun.builder().id(runId).build())
                .url("https://www.youtube.com/watch?v=" + UUID.randomUUID())
                .status(status)
                .phase(PipelineRunPhase.CREATED)
                .attempt(1)
                .build();
    }
}
