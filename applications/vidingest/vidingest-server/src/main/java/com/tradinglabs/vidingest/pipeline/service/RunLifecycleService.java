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
}

