package com.tradinglabs.vidingest.integration;

import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import com.tradinglabs.vidingest.pipeline.service.RunItemLeaseService;
import com.tradinglabs.vidingest.pipeline.service.StuckItemReconciler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lease queries are bulk {@code @Modifying} JPQL, so they are only really tested against a
 * real database. These cases also pin the behaviour the lease exists for: an item held by
 * another instance survives a sweep, and the same item stops surviving once its owner stops
 * heartbeating.
 */
class RunItemLeaseIntegrationTest extends BaseVidingestIntegrationTest {

    private static final String OTHER_INSTANCE = "4242@other-host";

    @Autowired
    private PipelineRunItemRepository runItemRepository;
    @Autowired
    private RunItemLeaseService leaseService;
    @Autowired
    private StuckItemReconciler stuckItemReconciler;

    @Test
    void acquireThenReleaseRoundTripsThroughTheDatabase() {
        PipelineRunItem item = staleItem();

        leaseService.acquire(item.getId());

        PipelineRunItem leased = runItemRepository.findById(item.getId()).orElseThrow();
        assertThat(leased.getLeaseOwner()).isEqualTo(leaseService.owner());
        assertThat(leased.getLeaseExpiresAt()).isAfter(OffsetDateTime.now(ZoneOffset.UTC));

        leaseService.release(item.getId());

        PipelineRunItem released = runItemRepository.findById(item.getId()).orElseThrow();
        assertThat(released.getLeaseOwner()).isNull();
        assertThat(released.getLeaseExpiresAt()).isNull();
    }

    @Test
    void renewOnlyTouchesLeasesThisInstanceOwns() {
        PipelineRunItem mine = staleItem();
        PipelineRunItem theirs = staleItem();
        leaseService.acquire(mine.getId());
        setLease(theirs, OTHER_INSTANCE, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1));

        OffsetDateTime theirExpiryBefore = runItemRepository.findById(theirs.getId()).orElseThrow().getLeaseExpiresAt();

        int renewed = leaseService.renew(List.of(mine.getId(), theirs.getId()));

        // Scoped by owner: a heartbeat must never extend a lease another instance took over.
        assertThat(renewed).isEqualTo(1);
        assertThat(runItemRepository.findById(theirs.getId()).orElseThrow().getLeaseExpiresAt())
                .isEqualTo(theirExpiryBefore);
        assertThat(runItemRepository.findById(theirs.getId()).orElseThrow().getLeaseOwner())
                .isEqualTo(OTHER_INSTANCE);
    }

    @Test
    void reconcilerSpareTheItemWhileAnotherInstancesLeaseIsLiveAndReapsItAfterwards() {
        PipelineRunItem item = staleItem();
        setLease(item, OTHER_INSTANCE, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));

        stuckItemReconciler.reconcileStuckItems();

        assertThat(runItemRepository.findById(item.getId()).orElseThrow().getStatus())
                .as("a live lease means some process is still running it")
                .isEqualTo(RunStatus.IN_PROGRESS);

        // Same row, same stale phase_updated_at — only the lease has lapsed.
        setLease(item, OTHER_INSTANCE, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));

        stuckItemReconciler.reconcileStuckItems();

        assertThat(runItemRepository.findById(item.getId()).orElseThrow().getStatus())
                .as("an owner that stopped heartbeating is an owner that died")
                .isEqualTo(RunStatus.FAILED);
    }

    @Test
    void runHasLiveLeaseSeesAnyItemOfTheRun() {
        PipelineRunItem item = staleItem();
        UUID runId = item.getPipelineRun().getId();

        assertThat(leaseService.runHasLiveLease(runId)).isFalse();

        setLease(item, OTHER_INSTANCE, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        assertThat(leaseService.runHasLiveLease(runId)).isTrue();

        setLease(item, OTHER_INSTANCE, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        assertThat(leaseService.runHasLiveLease(runId)).isFalse();
    }

    private void setLease(PipelineRunItem item, String owner, OffsetDateTime expiresAt) {
        runItemRepository.acquireLease(item.getId(), owner, expiresAt);
    }

    private PipelineRunItem staleItem() {
        PipelineRun run = pipelineRunRepository.saveAndFlush(PipelineRun.builder()
                .status(RunStatus.IN_PROGRESS)
                .videoUrl("https://example.com/video")
                .phase(PipelineRunPhase.KNOWLEDGE)
                .phaseUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2))
                .build());

        return runItemRepository.saveAndFlush(PipelineRunItem.builder()
                .pipelineRun(run)
                .url("https://example.com/video-" + UUID.randomUUID())
                .status(RunStatus.IN_PROGRESS)
                .phase(PipelineRunPhase.KNOWLEDGE)
                .phaseUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2))
                .build());
    }
}
