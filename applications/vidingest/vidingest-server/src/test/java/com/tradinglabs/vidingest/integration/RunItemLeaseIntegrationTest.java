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

    /** The in-JVM half of the double-submit guard: one claim per item until it is dropped. */
    @Test
    void claimIsExclusiveUntilUnclaimed() {
        UUID id = UUID.randomUUID();
        try {
            assertThat(leaseService.claim(id)).isTrue();
            assertThat(leaseService.claim(id)).isFalse();
            leaseService.unclaim(id);
            assertThat(leaseService.claim(id)).isTrue();
        } finally {
            // The service is a singleton shared across this class's tests; leave it clean.
            leaseService.unclaim(id);
        }
    }

    /**
     * The two-workers-one-video guard, database half. An instance that lost the enqueue race must
     * neither take the lease over the live owner nor — on its way out — clear the owner's lease
     * with its release. Both halves are asserted on the same row because they fail together: an
     * unconditional release after a refused acquire is exactly a theft of the winner's reap
     * protection.
     */
    @Test
    void acquireRefusesALiveForeignLeaseAndReleaseLeavesItUntouched() {
        PipelineRunItem item = staleItem();
        OffsetDateTime theirExpiry = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5);
        setLease(item, OTHER_INSTANCE, theirExpiry);

        assertThat(leaseService.acquire(item.getId()))
                .as("a live lease held elsewhere must refuse this instance")
                .isFalse();

        leaseService.release(item.getId());

        PipelineRunItem after = runItemRepository.findById(item.getId()).orElseThrow();
        assertThat(after.getLeaseOwner()).isEqualTo(OTHER_INSTANCE);
        assertThat(after.getLeaseExpiresAt()).isEqualTo(theirExpiry);
    }

    /** An expired lease is a dead owner; taking it over is the whole point of the TTL. */
    @Test
    void acquireTakesOverAnExpiredForeignLeaseAndReacquiresItsOwn() {
        PipelineRunItem item = staleItem();
        setLease(item, OTHER_INSTANCE, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));

        assertThat(leaseService.acquire(item.getId())).isTrue();
        assertThat(runItemRepository.findById(item.getId()).orElseThrow().getLeaseOwner())
                .isEqualTo(leaseService.owner());

        // Re-acquiring one's own live lease is a renewal, never a refusal.
        assertThat(leaseService.acquire(item.getId())).isTrue();
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

    /** Plants a lease directly — a fixture must not depend on the guarded acquire query. */
    private void setLease(PipelineRunItem item, String owner, OffsetDateTime expiresAt) {
        item.setLeaseOwner(owner);
        item.setLeaseExpiresAt(expiresAt);
        runItemRepository.saveAndFlush(item);
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
