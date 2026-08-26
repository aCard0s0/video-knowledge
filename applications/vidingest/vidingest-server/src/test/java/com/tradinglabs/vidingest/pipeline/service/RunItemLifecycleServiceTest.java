package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.domain.PipelineErrorCode;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunItemLifecycleServiceTest {

    @Mock
    private PipelineRunRepository pipelineRunRepository;

    @Mock
    private PipelineRunItemRepository pipelineRunItemRepository;

    @Mock
    private PipelineAuditService pipelineAuditService;

    @Mock
    private VideoLifecycleService videoLifecycleService;

    private RunItemLifecycleService service;

    private UUID runId;
    private UUID itemId;
    private PipelineRun run;

    @BeforeEach
    void setup() {
        service = new RunItemLifecycleService(
                pipelineRunRepository, pipelineRunItemRepository, pipelineAuditService, videoLifecycleService);
        runId = UUID.randomUUID();
        itemId = UUID.randomUUID();
        run = PipelineRun.builder().status(RunStatus.PENDING).build();
        run.setId(runId);
    }

    private PipelineRunItem stubExistingItem() {
        PipelineRunItem item = PipelineRunItem.builder()
                .pipelineRun(run)
                .url("https://example.com/x")
                .status(RunStatus.PENDING)
                .phase(PipelineRunPhase.CREATED)
                .attempt(1)
                .build();
        item.setId(itemId);
        when(pipelineRunItemRepository.getReferenceById(itemId)).thenReturn(item);
        when(pipelineRunItemRepository.save(any(PipelineRunItem.class))).thenAnswer(inv -> inv.getArgument(0));
        return item;
    }

    @Test
    void createItemsRecordsItemCreatedPerItem() {
        when(pipelineRunRepository.getReferenceById(runId)).thenReturn(run);
        ArgumentCaptor<List<PipelineRunItem>> captor = ArgumentCaptor.forClass(List.class);
        when(pipelineRunItemRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<PipelineRunItem> items = inv.getArgument(0);
            int i = 0;
            for (PipelineRunItem it : items) {
                it.setId(UUID.randomUUID());
                i++;
            }
            return items;
        });

        List<PipelineRunItem> created = service.createItems(runId, List.of("u1", "u2"));

        assertThat(created).hasSize(2);
        verify(pipelineAuditService, times(2)).recordCreated(any(PipelineRunItem.class));
    }

    @Test
    void markInProgressCapturesPreviousPhaseAndEmitsEvent() {
        PipelineRunItem item = stubExistingItem();
        item.setPhase(PipelineRunPhase.CREATED);

        service.markInProgress(itemId, PipelineRunPhase.METADATA);

        assertThat(item.getStatus()).isEqualTo(RunStatus.IN_PROGRESS);
        assertThat(item.getPhase()).isEqualTo(PipelineRunPhase.METADATA);
        verify(pipelineAuditService).recordPhaseEntered(item, PipelineRunPhase.CREATED);
    }

    @Test
    void markPhaseEmitsPhaseEnteredWithPreviousPhase() {
        PipelineRunItem item = stubExistingItem();
        item.setPhase(PipelineRunPhase.DOWNLOAD);

        service.markPhase(itemId, PipelineRunPhase.PERSIST);

        assertThat(item.getPhase()).isEqualTo(PipelineRunPhase.PERSIST);
        verify(pipelineAuditService).recordPhaseEntered(item, PipelineRunPhase.DOWNLOAD);
    }

    @Test
    void markCompletedEmitsCompletedEvent() {
        PipelineRunItem item = stubExistingItem();
        item.setPhase(PipelineRunPhase.CONTEXT);
        item.setStatus(RunStatus.IN_PROGRESS);

        service.markCompleted(itemId);

        assertThat(item.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(item.getPhase()).isEqualTo(PipelineRunPhase.DONE);
        verify(pipelineAuditService).recordCompleted(item);
    }

    @Test
    void markFailedSetsFailedPhaseAndEmitsFailureEvent() {
        PipelineRunItem item = stubExistingItem();
        item.setPhase(PipelineRunPhase.TRANSCRIBE);
        item.setStatus(RunStatus.IN_PROGRESS);

        service.markFailed(itemId, PipelineErrorCode.TRANSCRIPTION_FAILURE, "asr down");

        assertThat(item.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(item.getFailedPhase()).isEqualTo(PipelineRunPhase.TRANSCRIBE);
        assertThat(item.getError()).isEqualTo("asr down");
        verify(pipelineAuditService).recordFailed(item, PipelineErrorCode.TRANSCRIPTION_FAILURE, "asr down");
    }

    /**
     * The reap path in {@code StuckItemReconciler} has no catch block of its own, so if the video
     * is not carried along from here a process that dies mid-phase leaves it TRANSCRIBING forever.
     */
    @Test
    void markFailedAlsoFailsTheItemsVideo() {
        PipelineRunItem item = stubExistingItem();
        item.setPhase(PipelineRunPhase.TRANSCRIBE);
        item.setStatus(RunStatus.IN_PROGRESS);
        UUID videoId = UUID.randomUUID();
        item.setVideoId(videoId);

        service.markFailed(itemId, PipelineErrorCode.UNEXPECTED, "reconciler: stuck");

        verify(videoLifecycleService).markFailedIfUnfinished(videoId);
    }

    @Test
    void markCancelledLeavesTheVideoAlone() {
        PipelineRunItem item = stubExistingItem();
        item.setPhase(PipelineRunPhase.METADATA);
        item.setStatus(RunStatus.IN_PROGRESS);
        item.setVideoId(UUID.randomUUID());

        service.markCancelled(itemId, PipelineErrorCode.DUPLICATE_VIDEO, "dup");

        // DUPLICATE_VIDEO cancels against a healthy existing video; failing it would be wrong.
        verify(videoLifecycleService, never()).markFailedIfUnfinished(any());
    }

    @Test
    void markCancelledRecordsCancellation() {
        PipelineRunItem item = stubExistingItem();
        item.setPhase(PipelineRunPhase.METADATA);
        item.setStatus(RunStatus.IN_PROGRESS);

        service.markCancelled(itemId, PipelineErrorCode.DUPLICATE_VIDEO, "dup");

        assertThat(item.getStatus()).isEqualTo(RunStatus.CANCELLED);
        verify(pipelineAuditService).recordCancelled(item, PipelineErrorCode.DUPLICATE_VIDEO, "dup");
    }

    @Test
    void attachVideoEmitsVideoAttachedEvent() {
        PipelineRunItem item = stubExistingItem();
        UUID videoId = UUID.randomUUID();

        service.attachVideo(itemId, videoId);

        assertThat(item.getVideoId()).isEqualTo(videoId);
        verify(pipelineAuditService).recordVideoAttached(item, videoId);
    }

    @Test
    void prepareRetrySnapshotsPriorFailureBeforeClearingAndIncrementsAttempt() {
        PipelineRunItem item = stubExistingItem();
        item.setStatus(RunStatus.FAILED);
        item.setPhase(PipelineRunPhase.DONE);
        item.setFailedPhase(PipelineRunPhase.DOWNLOAD);
        item.setError("net unreachable");
        item.setErrorCode(PipelineErrorCode.UPSTREAM_TOOL_FAILURE);
        item.setAttempt(1);

        service.prepareRetry(itemId);

        assertThat(item.getStatus()).isEqualTo(RunStatus.PENDING);
        assertThat(item.getPhase()).isEqualTo(PipelineRunPhase.CREATED);
        assertThat(item.getFailedPhase()).isNull();
        assertThat(item.getError()).isNull();
        assertThat(item.getErrorCode()).isNull();
        assertThat(item.getVideoId()).isNull();
        assertThat(item.getAttempt()).isEqualTo(2);
        verify(pipelineAuditService).recordRetryRequested(
                eq(item),
                eq(PipelineRunPhase.DOWNLOAD),
                eq(PipelineErrorCode.UPSTREAM_TOOL_FAILURE),
                eq("net unreachable"),
                eq(2)
        );
    }
}
