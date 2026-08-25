package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.domain.PipelineErrorCode;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RunItemLifecycleService {

    private final PipelineRunRepository pipelineRunRepository;
    private final PipelineRunItemRepository pipelineRunItemRepository;
    private final PipelineAuditService pipelineAuditService;

    @Transactional
    public List<PipelineRunItem> createItems(UUID runId, List<String> urls) {
        var runRef = pipelineRunRepository.getReferenceById(runId);
        List<PipelineRunItem> items = urls.stream()
                .map(url -> PipelineRunItem.builder()
                        .pipelineRun(runRef)
                        .url(url)
                        .status(RunStatus.PENDING)
                        .phase(PipelineRunPhase.CREATED)
                        .attempt(1)
                        .build())
                .toList();
        List<PipelineRunItem> saved = pipelineRunItemRepository.saveAll(items);
        for (PipelineRunItem item : saved) {
            pipelineAuditService.recordCreated(item);
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<PipelineRunItem> listItems(UUID runId) {
        return pipelineRunItemRepository.findByPipelineRunIdOrdered(runId);
    }

    @Transactional
    public void prepareRetry(UUID itemId) {
        PipelineRunItem item = pipelineRunItemRepository.getReferenceById(itemId);
        PipelineRunPhase prevFailedPhase = item.getFailedPhase();
        PipelineErrorCode prevErrorCode = item.getErrorCode();
        String prevError = item.getError();
        int newAttempt = (item.getAttempt() != null ? item.getAttempt() : 1) + 1;

        item.setStatus(RunStatus.PENDING);
        item.setError(null);
        item.setErrorCode(null);
        item.setPhase(PipelineRunPhase.CREATED);
        item.setFailedPhase(null);
        item.setVideoId(null);
        item.setAttempt(newAttempt);
        PipelineRunItem saved = pipelineRunItemRepository.save(item);
        pipelineAuditService.recordRetryRequested(saved, prevFailedPhase, prevErrorCode, prevError, newAttempt);
    }

    @Transactional
    public void markInProgress(UUID itemId, PipelineRunPhase phase) {
        PipelineRunItem item = pipelineRunItemRepository.getReferenceById(itemId);
        PipelineRunPhase previousPhase = item.getPhase();
        item.setStatus(RunStatus.IN_PROGRESS);
        item.setError(null);
        item.setErrorCode(null);
        item.setPhase(phase);
        PipelineRunItem saved = pipelineRunItemRepository.save(item);
        pipelineAuditService.recordPhaseEntered(saved, previousPhase);
    }

    @Transactional
    public void markPhase(UUID itemId, PipelineRunPhase phase) {
        PipelineRunItem item = pipelineRunItemRepository.getReferenceById(itemId);
        PipelineRunPhase previousPhase = item.getPhase();
        item.setPhase(phase);
        PipelineRunItem saved = pipelineRunItemRepository.save(item);
        pipelineAuditService.recordPhaseEntered(saved, previousPhase);
    }

    @Transactional
    public void attachVideo(UUID itemId, UUID videoId) {
        PipelineRunItem item = pipelineRunItemRepository.getReferenceById(itemId);
        item.setVideoId(videoId);
        PipelineRunItem saved = pipelineRunItemRepository.save(item);
        pipelineAuditService.recordVideoAttached(saved, videoId);
    }

    /**
     * Audit-only: records that {@code completedPhase} finished successfully for this item.
     * Does NOT change item status (still IN_PROGRESS until the whole pipeline finishes).
     * API consumers read these events to render per-phase completion.
     */
    @Transactional
    public void recordPhaseCompleted(UUID itemId, PipelineRunPhase completedPhase) {
        PipelineRunItem item = pipelineRunItemRepository.getReferenceById(itemId);
        pipelineAuditService.recordPhaseCompleted(item, completedPhase);
    }

    @Transactional
    public void markCompleted(UUID itemId) {
        PipelineRunItem item = pipelineRunItemRepository.getReferenceById(itemId);
        item.setStatus(RunStatus.COMPLETED);
        item.setPhase(PipelineRunPhase.DONE);
        PipelineRunItem saved = pipelineRunItemRepository.save(item);
        pipelineAuditService.recordCompleted(saved);
    }

    @Transactional
    public void markFailed(UUID itemId, PipelineErrorCode errorCode, String errorMessage) {
        PipelineRunItem item = pipelineRunItemRepository.getReferenceById(itemId);
        item.setStatus(RunStatus.FAILED);
        item.setError(errorMessage);
        item.setErrorCode(errorCode);
        item.setFailedPhase(item.getPhase());
        item.setPhase(PipelineRunPhase.DONE);
        PipelineRunItem saved = pipelineRunItemRepository.save(item);
        pipelineAuditService.recordFailed(saved, errorCode, errorMessage);
    }

    @Transactional
    public void markCancelled(UUID itemId, PipelineErrorCode errorCode, String message) {
        PipelineRunItem item = pipelineRunItemRepository.getReferenceById(itemId);
        item.setStatus(RunStatus.CANCELLED);
        item.setError(message);
        item.setErrorCode(errorCode);
        item.setFailedPhase(item.getPhase());
        item.setPhase(PipelineRunPhase.DONE);
        PipelineRunItem saved = pipelineRunItemRepository.save(item);
        pipelineAuditService.recordCancelled(saved, errorCode, message);
    }
}
