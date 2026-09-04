package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.domain.*;
import com.tradinglabs.vidingest.pipeline.exceptions.RunNotFoundException;
import com.tradinglabs.vidingest.pipeline.exceptions.RunRetryNotAllowedException;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RunLifecycleService {

    private final PipelineRunRepository pipelineRunRepository;
    private final PipelineRunItemRepository pipelineRunItemRepository;

    @Transactional
    public PipelineRun createPipelineRun(String videoUrl, Set<PipelineRunPhase> skipPhases) {
        PipelineRun run = PipelineRun.builder()
                .status(RunStatus.PENDING)
                .phase(PipelineRunPhase.CREATED)
                .videoUrl(videoUrl)
                .skipPhases(skipPhases)
                .build();
        return pipelineRunRepository.save(run);
    }

    /**
     * Resets the run for another attempt and records the phase set that attempt will run with.
     *
     * <p>The set is written every time, not only when it changes: a retry that overrides it is the
     * run's configuration from then on, so the next retry that omits the field inherits the last
     * attempt and not the original one — and the run screen's phase picker, which seeds from this
     * field, keeps describing the attempt the operator is looking at.
     *
     * <p>Read under {@code FOR UPDATE}, because this is the serialising gate for every retry.
     * With a plain read, two concurrent retries of the same run both saw {@code FAILED} before
     * either committed, both passed, and both enqueued the same item — two workers wiping and
     * repopulating the same video side by side. The lock makes concurrent behave like sequential:
     * the second caller blocks, then reads {@code PENDING} and gets the same 409 it would have
     * got arriving a moment later. This is a cold operator path, nothing like the per-phase-
     * transition writes the run-row lock is deliberately kept off of.
     */
    @Transactional
    public PipelineRun prepareRetry(UUID runId, Set<PipelineRunPhase> skipPhases) {
        PipelineRun run = pipelineRunRepository.findWithLockById(runId)
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
        run.setSkipPhases(skipPhases);
        return pipelineRunRepository.save(run);
    }

    @Transactional(readOnly = true)
    public PipelineRun getPipelineRun(UUID runId) {
        return pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new RunNotFoundException(runId));
    }
}

