package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.domain.PipelineErrorCode;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import com.tradinglabs.vidingest.pipeline.exceptions.RunNotFoundException;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RunAggregationService {

    private final PipelineRunRepository pipelineRunRepository;
    private final RunItemLifecycleService runItemLifecycleService;

    @Transactional
    public void ensureRunInProgress(UUID runId, PipelineRunPhase phase) {
        PipelineRun run = pipelineRunRepository.getReferenceById(runId);
        if (run.getStatus() == RunStatus.PENDING) {
            run.setStatus(RunStatus.IN_PROGRESS);
        }
        if (phase != null) {
            run.setPhase(phase);
        }
        pipelineRunRepository.save(run);
    }

    /**
     * Condemns a run outright, bypassing the item-derived status. Only the startup reconciler
     * uses this: a run still {@code IN_PROGRESS} after {@link #refreshRunState} has re-derived
     * it has items that no live process owns, and no later event will move it.
     */
    @Transactional
    public void markFailed(UUID runId, PipelineErrorCode errorCode, String errorMessage) {
        PipelineRun run = pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new RunNotFoundException(runId));
        run.setStatus(RunStatus.FAILED);
        run.setError(errorMessage);
        run.setErrorCode(errorCode);
        run.setPhase(PipelineRunPhase.DONE);
        pipelineRunRepository.save(run);
    }

    @Transactional
    public void updateRunPhase(UUID runId, PipelineRunPhase phase) {
        PipelineRun run = pipelineRunRepository.getReferenceById(runId);
        if (phase != null) {
            run.setPhase(phase);
            pipelineRunRepository.save(run);
        }
    }

    /**
     * Recomputes the run's status from its items and returns the status the run now holds.
     *
     * <p>The lock is taken <b>before</b> the item read, and that ordering is the whole point:
     * two items finishing at once would otherwise each read the item list, and the one that
     * read first could write its stale conclusion over the other's. That stranded the run at
     * {@code IN_PROGRESS} with every item {@code COMPLETED}, permanently — nothing else
     * re-runs this once all items are terminal. Locking first serialises the finalisers, so
     * whichever thread runs second observes every transition the first committed.
     */
    @Transactional
    public RunStatus refreshRunState(UUID runId) {
        PipelineRun run = pipelineRunRepository.findWithLockById(runId)
                .orElseThrow(() -> new RunNotFoundException(runId));

        List<PipelineRunItem> items = runItemLifecycleService.listItems(runId);
        if (items.isEmpty()) {
            return run.getStatus();
        }

        boolean anyPendingOrInProgress = items.stream().anyMatch(i ->
                i.getStatus() == RunStatus.PENDING || i.getStatus() == RunStatus.IN_PROGRESS);

        if (anyPendingOrInProgress) {
            if (run.getStatus() != RunStatus.IN_PROGRESS) {
                run.setStatus(RunStatus.IN_PROGRESS);
                pipelineRunRepository.save(run);
            }
            return run.getStatus();
        }

        boolean anyFailed = items.stream().anyMatch(i -> i.getStatus() == RunStatus.FAILED);
        boolean allCancelled = items.stream().allMatch(i -> i.getStatus() == RunStatus.CANCELLED);

        if (anyFailed) {
            run.setStatus(RunStatus.FAILED);
            PipelineRunItem firstFailed = items.stream().filter(i -> i.getStatus() == RunStatus.FAILED).findFirst().orElse(null);
            run.setErrorCode(firstFailed != null ? firstFailed.getErrorCode() : PipelineErrorCode.UNEXPECTED);
            run.setError(firstFailed != null ? firstFailed.getError() : "one or more run items failed");
        } else if (allCancelled) {
            run.setStatus(RunStatus.CANCELLED);
            PipelineRunItem firstCancelled = items.stream().filter(i -> i.getStatus() == RunStatus.CANCELLED).findFirst().orElse(null);
            run.setErrorCode(firstCancelled != null ? firstCancelled.getErrorCode() : null);
            run.setError(firstCancelled != null ? firstCancelled.getError() : "all run items cancelled");
        } else {
            run.setStatus(RunStatus.COMPLETED);
            run.setErrorCode(null);
            run.setError(null);
        }

        run.setPhase(PipelineRunPhase.DONE);
        pipelineRunRepository.save(run);
        return run.getStatus();
    }
}

