package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.domain.*;
import com.tradinglabs.vidingest.pipeline.exceptions.RunNotFoundException;
import com.tradinglabs.vidingest.pipeline.exceptions.RunRetryNotAllowedException;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RunLifecycleService {

    private final PipelineRunRepository pipelineRunRepository;
    private final PipelineRunItemRepository pipelineRunItemRepository;

    @Transactional
    public PipelineRun createPipelineRun(String videoUrl) {
        PipelineRun run = PipelineRun.builder()
                .status(RunStatus.PENDING)
                .phase(PipelineRunPhase.CREATED)
                .videoUrl(videoUrl)
                .build();
        return pipelineRunRepository.save(run);
    }

    @Transactional
    public PipelineRun prepareRetry(UUID runId) {
        PipelineRun run = pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new RunNotFoundException(runId));

        if (run.getStatus() != RunStatus.FAILED) {
            throw new RunRetryNotAllowedException("Only FAILED pipeline runs can be retried");
        }
        if (run.getVideoUrl() == null || run.getVideoUrl().isBlank()) {
            boolean hasItems = !pipelineRunItemRepository.findByPipelineRunIdOrdered(runId).isEmpty();
            if (!hasItems) {
                throw new RunRetryNotAllowedException("Pipeline run " + runId + " has no source URL or run items stored for retry");
            }
        }

        run.setStatus(RunStatus.PENDING);
        run.setError(null);
        run.setErrorCode(null);
        run.setPhase(PipelineRunPhase.CREATED);
        return pipelineRunRepository.save(run);
    }

    @Transactional(readOnly = true)
    public PipelineRun getPipelineRun(UUID runId) {
        return pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new RunNotFoundException(runId));
    }

    @Transactional
    public void markInProgress(UUID runId, PipelineRunPhase phase) {
        PipelineRun run = getPipelineRun(runId);
        run.setStatus(RunStatus.IN_PROGRESS);
        run.setError(null);
        run.setErrorCode(null);
        run.setPhase(phase);
        pipelineRunRepository.save(run);
    }

    @Transactional
    public void markPhase(UUID runId, PipelineRunPhase phase) {
        PipelineRun run = getPipelineRun(runId);
        run.setPhase(phase);
        pipelineRunRepository.save(run);
    }

    @Transactional
    public void markCompleted(UUID runId) {
        PipelineRun run = getPipelineRun(runId);
        run.setStatus(RunStatus.COMPLETED);
        run.setPhase(PipelineRunPhase.DONE);
        pipelineRunRepository.save(run);
    }

    @Transactional
    public void markFailed(UUID runId, PipelineErrorCode errorCode, String errorMessage) {
        PipelineRun run = getPipelineRun(runId);
        run.setStatus(RunStatus.FAILED);
        run.setError(errorMessage);
        run.setErrorCode(errorCode);
        run.setPhase(PipelineRunPhase.DONE);
        pipelineRunRepository.save(run);
    }

    @Transactional
    public void markCancelled(UUID runId, PipelineErrorCode errorCode, String message) {
        PipelineRun run = getPipelineRun(runId);
        run.setStatus(RunStatus.CANCELLED);
        run.setError(message);
        run.setErrorCode(errorCode);
        run.setPhase(PipelineRunPhase.DONE);
        pipelineRunRepository.save(run);
    }
}

