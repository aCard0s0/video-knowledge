package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.domain.PipelineErrorCode;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
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

    @Transactional
    public void updateRunPhase(UUID runId, PipelineRunPhase phase) {
        PipelineRun run = pipelineRunRepository.getReferenceById(runId);
        if (phase != null) {
            run.setPhase(phase);
            pipelineRunRepository.save(run);
        }
    }

    @Transactional
    public void refreshRunState(UUID runId) {
        List<PipelineRunItem> items = runItemLifecycleService.listItems(runId);
        if (items.isEmpty()) {
            return;
        }

        boolean anyPendingOrInProgress = items.stream().anyMatch(i ->
                i.getStatus() == RunStatus.PENDING || i.getStatus() == RunStatus.IN_PROGRESS);

        PipelineRun run = pipelineRunRepository.getReferenceById(runId);
        if (anyPendingOrInProgress) {
            if (run.getStatus() != RunStatus.IN_PROGRESS) {
                run.setStatus(RunStatus.IN_PROGRESS);
                pipelineRunRepository.save(run);
            }
            return;
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
    }
}

