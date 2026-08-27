package com.tradinglabs.vidingest.integration;

import com.tradinglabs.vidingest.pipeline.domain.PipelineErrorCode;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import com.tradinglabs.vidingest.pipeline.service.StuckItemReconciler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class StuckItemReconcilerIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private PipelineRunItemRepository runItemRepository;

    @Autowired
    private StuckItemReconciler stuckItemReconciler;

    @Test
    void reconcilerMarksStaleInProgressItemsAsFailed() {
        PipelineRun run = pipelineRunRepository.saveAndFlush(PipelineRun.builder()
                .status(RunStatus.IN_PROGRESS)
                .videoUrl("https://example.com/video")
                .phase(PipelineRunPhase.DOWNLOAD)
                .phaseUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2))
                .build());

        PipelineRunItem item = runItemRepository.saveAndFlush(PipelineRunItem.builder()
                .pipelineRun(run)
                .url("https://example.com/video")
                .status(RunStatus.IN_PROGRESS)
                .phase(PipelineRunPhase.DOWNLOAD)
                .phaseUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2))
                .build());

        stuckItemReconciler.reconcileStuckItems();

        PipelineRunItem updated = runItemRepository.findById(item.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(updated.getErrorCode()).isEqualTo(PipelineErrorCode.UNEXPECTED);
        assertThat(updated.getError()).contains("reconciler: stuck IN_PROGRESS");
    }
}

