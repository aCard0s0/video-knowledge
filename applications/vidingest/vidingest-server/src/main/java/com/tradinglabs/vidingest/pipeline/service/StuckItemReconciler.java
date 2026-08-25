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
    private final PipelineMetrics pipelineMetrics;
    private final Duration staleAfter;

    public StuckItemReconciler(
            PipelineRunItemRepository runItemRepository,
            RunItemLifecycleService runItemLifecycleService,
            RunAggregationService runAggregationService,
            PipelineMetrics pipelineMetrics,
            @Value("${vidingest.reconciler.itemStaleAfter:PT1H}") Duration staleAfter
    ) {
        this.runItemRepository = runItemRepository;
        this.runItemLifecycleService = runItemLifecycleService;
        this.runAggregationService = runAggregationService;
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

        log.warn("Reconciler found {} pipeline run items stuck IN_PROGRESS for more than {}; marking FAILED", stuck.size(), staleAfter);
        for (PipelineRunItem item : stuck) {
            PipelineRunPhase phase = item.getPhase();
            String reason = "reconciler: stuck IN_PROGRESS in phase " + (phase != null ? phase.name() : "UNKNOWN")
                    + " since " + item.getPhaseUpdatedAt();
            try {
                runItemLifecycleService.markFailed(item.getId(), PipelineErrorCode.UNEXPECTED, reason);
                if (item.getPipelineRun() != null) {
                    runAggregationService.refreshRunState(item.getPipelineRun().getId());
                }
                pipelineMetrics.incrementFailed(PipelineErrorCode.UNEXPECTED);
            } catch (Exception e) {
                log.error("Failed to reconcile stuck item {}: {}", item.getId(), e.getMessage(), e);
            }
        }
        pipelineMetrics.refreshInflightGauge();
    }
}
