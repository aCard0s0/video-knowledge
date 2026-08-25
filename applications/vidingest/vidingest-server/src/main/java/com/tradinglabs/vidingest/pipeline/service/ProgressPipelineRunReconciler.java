package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.domain.PipelineErrorCode;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProgressPipelineRunReconciler {

    private final RunQueryService queryService;
    private final RunAggregationService runAggregationService;
    private final RunItemLeaseService runItemLeaseService;

    @EventListener(ApplicationReadyEvent.class)
    void reconcileInProgressPipelineRuns() {
        var stuck = queryService.listPipelineRunsByStatus(RunStatus.IN_PROGRESS);
        if (stuck.isEmpty()) {
            return;
        }

        log.warn("Found {} pipeline runs IN_PROGRESS at startup; re-deriving status from their items", stuck.size());
        for (var run : stuck) {
            // Re-derive before condemning it. A run whose items all reached a terminal status
            // is IN_PROGRESS only because the aggregation lost a race, and marking that FAILED
            // is the bug rather than the fix — so this also self-heals rows stranded before
            // the run-row lock landed.
            RunStatus derived;
            try {
                derived = runAggregationService.refreshRunState(run.getId());
            } catch (Exception e) {
                log.error("Could not re-derive status for pipeline run {}: {}", run.getId(), e.getMessage(), e);
                continue;
            }
            if (derived != RunStatus.IN_PROGRESS) {
                log.info("Pipeline run {} re-derived from its items as {}", run.getId(), derived);
                continue;
            }

            // This instance starting up says nothing about the others. A live lease means some
            // process is executing an item of this run right now, so failing it here would
            // condemn a peer's work — the multi-instance form of the bug #7 fixed within a JVM.
            // A crashed owner's lease outlives it by at most the TTL, after which
            // StuckItemReconciler reaps the items and the run resolves through the normal path.
            if (runItemLeaseService.runHasLiveLease(run.getId())) {
                log.info("Pipeline run {} left alone: an item is still held by a live lease", run.getId());
                continue;
            }

            runAggregationService.markFailed(
                    run.getId(),
                    PipelineErrorCode.UNEXPECTED,
                    "Pipeline run was IN_PROGRESS during server startup; marking failed for operator review.");
        }
    }
}
