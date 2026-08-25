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

    private final RunLifecycleService lifecycleService;
    private final RunQueryService queryService;

    @EventListener(ApplicationReadyEvent.class)
    void reconcileInProgressPipelineRuns() {
        var stuck = queryService.listPipelineRunsByStatus(RunStatus.IN_PROGRESS);
        if (stuck.isEmpty()) {
            return;
        }

        log.warn("Found {} pipeline runs stuck IN_PROGRESS; marking as FAILED", stuck.size());
        for (var run : stuck) {
            lifecycleService.markFailed(
                    run.getId(),
                    PipelineErrorCode.UNEXPECTED,
                    "Pipeline run was IN_PROGRESS during server startup; marking failed for operator review.");
        }
    }
}

