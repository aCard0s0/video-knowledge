package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.domain.PipelineErrorCode;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class StuckItemReconciler {

    private final PipelineRunItemRepository runItemRepository;
    private final RunItemLifecycleService runItemLifecycleService;
    private final RunAggregationService runAggregationService;
    private final PipelineService pipelineService;
    private final PipelineMetrics pipelineMetrics;
    private final Duration staleAfter;

    public StuckItemReconciler(
            PipelineRunItemRepository runItemRepository,
            RunItemLifecycleService runItemLifecycleService,
            RunAggregationService runAggregationService,
            PipelineService pipelineService,
            PipelineMetrics pipelineMetrics,
            @Value("${vidingest.reconciler.itemStaleAfter:PT1H}") Duration staleAfter
    ) {
        this.runItemRepository = runItemRepository;
        this.runItemLifecycleService = runItemLifecycleService;
        this.runAggregationService = runAggregationService;
        this.pipelineService = pipelineService;
        this.pipelineMetrics = pipelineMetrics;
        this.staleAfter = staleAfter;
    }

    @Scheduled(fixedDelayString = "${vidingest.reconciler.intervalMs:300000}",
            initialDelayString = "${vidingest.reconciler.initialDelayMs:60000}")
    public void reconcileStuckItems() {
        LocalDateTime threshold = LocalDateTime.now().minus(staleAfter);
        List<PipelineRunItem> stuck = runItemRepository.findByStatusAndPhaseUpdatedAtBefore(RunStatus.IN_PROGRESS, threshold);
        if (stuck.isEmpty()) {
            return;
        }

        int reaped = 0;
        int skippedLive = 0;
        int skippedLeased = 0;
        for (PipelineRunItem item : stuck) {
            // phase_updated_at only moves on a phase *transition*, so a phase that legitimately
            // runs for hours (KNOWLEDGE and DIARIZE both do on a long video) looks identical to
            // abandoned work. Failing a live item is not cosmetic: the operator sees FAILED and
            // retries, and a second worker then wipes-and-repopulates the same video alongside
            // the first.
            //
            // Two answers, because they fail in opposite directions. The lease sees every
            // instance's work but goes stale if this process stops heartbeating; the in-flight
            // set cannot see other instances but is never wrong about this one. An item is
            // abandoned only when neither claims it.
            if (pipelineService.isItemInFlight(item.getId())) {
                skippedLive++;
                continue;
            }
            if (RunItemLeaseService.isLive(item.getLeaseExpiresAt())) {
                skippedLeased++;
                continue;
            }

            PipelineRunPhase phase = item.getPhase();
            String reason = "reconciler: stuck IN_PROGRESS in phase " + (phase != null ? phase.name() : "UNKNOWN")
                    + " since " + item.getPhaseUpdatedAt();
            try {
                runItemLifecycleService.markFailed(item.getId(), PipelineErrorCode.UNEXPECTED, reason);
                if (item.getPipelineRun() != null) {
                    runAggregationService.refreshRunState(item.getPipelineRun().getId());
                }
                pipelineMetrics.incrementFailed(PipelineErrorCode.UNEXPECTED);
                reaped++;
            } catch (Exception e) {
                log.error("Failed to reconcile stuck item {}: {}", item.getId(), e.getMessage(), e);
            }
        }

        if (reaped > 0 || skippedLive > 0 || skippedLeased > 0) {
            log.warn("Reconciler swept {} items IN_PROGRESS for more than {}: {} marked FAILED, "
                            + "{} left alone because this JVM is still running them, "
                            + "{} because a live lease holds them",
                    stuck.size(), staleAfter, reaped, skippedLive, skippedLeased);
        }
        pipelineMetrics.refreshInflightGauge();
    }
}
