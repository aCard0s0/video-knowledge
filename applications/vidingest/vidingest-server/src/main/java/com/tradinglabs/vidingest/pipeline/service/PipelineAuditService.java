package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.domain.PipelineErrorCode;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItemEvent;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItemEventType;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PipelineAuditService {

    private final PipelineRunItemEventRepository eventRepository;

    public void recordCreated(PipelineRunItem item) {
        eventRepository.save(baseEvent(item, PipelineRunItemEventType.ITEM_CREATED)
                .phase(item.getPhase())
                .status(item.getStatus())
                .build());
    }

    public void recordPhaseEntered(PipelineRunItem item, PipelineRunPhase previousPhase) {
        eventRepository.save(baseEvent(item, PipelineRunItemEventType.ITEM_PHASE_ENTERED)
                .phase(item.getPhase())
                .previousPhase(previousPhase)
                .status(item.getStatus())
                .build());
    }

    /**
     * Marks a previously-entered phase as finished successfully. Emitted after
     * {@code phase.execute(ctx)} returns and before the next phase is entered, so the
     * item-history row for that phase flips from "In progress" to "Completed".
     */
    public void recordPhaseCompleted(PipelineRunItem item, PipelineRunPhase completedPhase) {
        eventRepository.save(baseEvent(item, PipelineRunItemEventType.ITEM_PHASE_COMPLETED)
                .phase(completedPhase)
                .status(item.getStatus())
                .videoId(item.getVideoId())
                .build());
    }

    public void recordVideoAttached(PipelineRunItem item, UUID videoId) {
        eventRepository.save(baseEvent(item, PipelineRunItemEventType.ITEM_VIDEO_ATTACHED)
                .phase(item.getPhase())
                .status(item.getStatus())
                .videoId(videoId)
                .build());
    }

    public void recordCompleted(PipelineRunItem item) {
        eventRepository.save(baseEvent(item, PipelineRunItemEventType.ITEM_COMPLETED)
                .phase(item.getPhase())
                .status(item.getStatus())
                .videoId(item.getVideoId())
                .build());
    }

    public void recordFailed(PipelineRunItem item, PipelineErrorCode code, String message) {
        eventRepository.save(baseEvent(item, PipelineRunItemEventType.ITEM_FAILED)
                .phase(item.getFailedPhase() != null ? item.getFailedPhase() : item.getPhase())
                .status(item.getStatus())
                .errorCode(code)
                .errorMessage(message)
                .videoId(item.getVideoId())
                .build());
    }

    public void recordCancelled(PipelineRunItem item, PipelineErrorCode code, String message) {
        eventRepository.save(baseEvent(item, PipelineRunItemEventType.ITEM_CANCELLED)
                .phase(item.getFailedPhase() != null ? item.getFailedPhase() : item.getPhase())
                .status(item.getStatus())
                .errorCode(code)
                .errorMessage(message)
                .videoId(item.getVideoId())
                .build());
    }

    public void recordRetryRequested(PipelineRunItem item,
                                     PipelineRunPhase prevFailedPhase,
                                     PipelineErrorCode prevErrorCode,
                                     String prevError,
                                     int newAttempt) {
        eventRepository.save(PipelineRunItemEvent.builder()
                .runItemId(item.getId())
                .pipelineRunId(item.getPipelineRun().getId())
                .eventType(PipelineRunItemEventType.ITEM_RETRY_REQUESTED)
                .attempt(newAttempt)
                .previousPhase(prevFailedPhase)
                .errorCode(prevErrorCode)
                .errorMessage(prevError)
                .build());
    }

    private PipelineRunItemEvent.PipelineRunItemEventBuilder baseEvent(PipelineRunItem item,
                                                                       PipelineRunItemEventType type) {
        return PipelineRunItemEvent.builder()
                .runItemId(item.getId())
                .pipelineRunId(item.getPipelineRun().getId())
                .eventType(type)
                .attempt(item.getAttempt() != null ? item.getAttempt() : 1);
    }
}
