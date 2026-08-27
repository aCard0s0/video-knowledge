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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Component
@Slf4j
public class StuckItemReconciler {

    /**
     * Statuses a sweep may reap. PENDING is here because an item whose owner died while it was
     * still queued never reaches IN_PROGRESS — and before this, nothing ever looked at it again:
     * the run stayed IN_PROGRESS forever and retry refused every one of its items.
     */
    private static final Set<RunStatus> SWEEPABLE = EnumSet.of(RunStatus.PENDING, RunStatus.IN_PROGRESS);

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
        OffsetDateTime threshold = OffsetDateTime.now(ZoneOffset.UTC).minus(staleAfter);
        List<PipelineRunItem> stuck =
                runItemRepository.findByStatusInAndPhaseUpdatedAtBefore(SWEEPABLE, threshold);
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
            // instance's work but goes stale if this process stops heartbeating; the ownership
            // set cannot see other instances but is never wrong about this one. An item is
            // abandoned only when neither claims it.
            //
            // Ownership, not just in-flight: a PENDING item is queued behind this JVM's gate and
            // has neither started nor taken a lease, so those two would both say "abandoned" for
            // work that is merely waiting its turn.
            if (pipelineService.isItemOwned(item.getId())) {
                skippedLive++;
                continue;
            }
            if (RunItemLeaseService.isLive(item.getLeaseExpiresAt())) {
                skippedLeased++;
                continue;
            }

            PipelineRunPhase phase = item.getPhase();
            String reason = "reconciler: stuck " + item.getStatus()
                    + " in phase " + (phase != null ? phase.name() : "UNKNOWN")
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
            log.warn("Reconciler swept {} items PENDING or IN_PROGRESS for more than {}: {} marked FAILED, "
                            + "{} left alone because this JVM owns them, "
                            + "{} because a live lease holds them",
                    stuck.size(), staleAfter, reaped, skippedLive, skippedLeased);
        }
        pipelineMetrics.refreshInflightGauge();
    }
}
