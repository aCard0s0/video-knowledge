package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who owns a run item, asked two ways.
 *
 * <p>{@code phase_updated_at} moves only on a phase <em>transition</em>, so a phase that
 * legitimately runs for hours is indistinguishable from work whose process died. PR #7 answered
 * that by asking {@code PipelineService} whether this JVM was executing the item — correct, but
 * per-process: a second instance sees none of the first instance's live work and reaps it, the
 * operator retries the run it just saw fail, and two workers wipe-and-repopulate the same video
 * side by side.
 *
 * <p>A lease is the same question asked of the database instead of a field: the owner writes its
 * identity and an expiry, a heartbeat pushes the expiry out while the work runs, and any
 * instance can tell a live item from an abandoned one. An expired lease means the owner stopped
 * heartbeating — it crashed, was killed, or lost the DB — which is exactly what should be reaped.
 *
 * <p>Both halves live here, and used to be split: the two in-memory sets and the heartbeat sat on
 * {@code PipelineService}, which then had to expose {@code isItemOwned} and {@code hasWorkInFlight}
 * so {@code StuckItemReconciler} could read a private field — the reconciler depended on the whole
 * orchestrator to ask one question that has nothing to do with orchestration. "Who owns this item?"
 * now has one home, and the reconciler asks it here.
 *
 * <p>The two sets are deliberately not one:
 * <ul>
 *   <li>{@link #claim}/{@link #unclaim} track everything this JVM has taken responsibility for,
 *       <em>including work still queued behind the ingestion gate</em>. That is what makes a
 *       {@code PENDING} item safe to reap: such an item is either waiting on our gate or was
 *       abandoned by a process that is gone, and only the owner can tell those apart —
 *       {@code phase_updated_at} is stamped at creation for both.</li>
 *   <li>{@link #acquire}/{@link #release} track the narrower set that is past the gate and holds a
 *       database lease. Only these can be heartbeaten; a queued item has no lease to renew, so
 *       merging the sets would make {@link #renewLeases} warn about every queued item.</li>
 * </ul>
 */
@Service
@Slf4j
public class RunItemLeaseService {

    private final PipelineRunItemRepository runItemRepository;
    private final String owner;
    private final Duration ttl;

    /**
     * Items this JVM has taken responsibility for — queued behind the ingestion gate as well as
     * executing. Wider than {@link #leased} on purpose; see the class javadoc.
     */
    private final Set<UUID> claimed = ConcurrentHashMap.newKeySet();

    /** Items past the gate, executing, and therefore holding a database lease to heartbeat. */
    private final Set<UUID> leased = ConcurrentHashMap.newKeySet();

    public RunItemLeaseService(
            PipelineRunItemRepository runItemRepository,
            @Value("${vidingest.lease.ttl:PT10M}") Duration ttl
    ) {
        this.runItemRepository = runItemRepository;
        // pid@host: unique per running process, and it deliberately does not survive a restart —
        // a new process must not inherit its predecessor's claims.
        this.owner = ManagementFactory.getRuntimeMXBean().getName();
        this.ttl = ttl;
        log.info("Run-item lease owner={} ttl={}", this.owner, ttl);
    }

    /** This instance's lease identity. */
    public String owner() {
        return owner;
    }

    /**
     * Take responsibility for an item, before it is handed to the executor. In-memory only: at this
     * point the item is {@code PENDING} with nothing running, which is precisely what the
     * reconciler reaps, and there is no lease to write until it is past the gate.
     *
     * @return {@code false} when this JVM already holds the claim — a concurrent retry got there
     *         first, and the caller must not submit the item a second time. Ignoring this return
     *         is what let two simultaneous retries of the same item run two workers over the same
     *         video.
     */
    public boolean claim(UUID itemId) {
        return claimed.add(itemId);
    }

    /** Drop the claim. Must cover every exit path, including a submit that was rejected. */
    public void unclaim(UUID itemId) {
        claimed.remove(itemId);
    }

    /**
     * Whether <em>this JVM</em> has taken responsibility for {@code itemId} — executing it, or
     * holding it queued behind the gate.
     *
     * <p>Narrower than the lease in one direction (it says nothing about other instances) and wider
     * in another (it covers items that have not started and hold no lease yet), so
     * {@link StuckItemReconciler} and the retry path consult both. An item is reaped only when
     * neither claims it.
     */
    public boolean isOwnedHere(UUID itemId) {
        return claimed.contains(itemId);
    }

    /**
     * Whether this instance is holding any ingestion work at all — running or queued.
     *
     * <p>The claim is dropped in the submitted task's outermost {@code finally}, after
     * {@link #release} has cleared the lease. That ordering is why this, and not a run's status, is
     * the honest answer to "is the pipeline finished writing?": {@code refreshRunState} makes a run
     * terminal <em>before</em> the release writes to {@code vidingest_pipeline_run_items} again, so
     * a caller that stops at COMPLETED is still racing a write. The integration tests wipe those
     * tables between methods and deadlocked against exactly that window.
     */
    public boolean hasClaimedWork() {
        return !claimed.isEmpty();
    }

    /**
     * Start heartbeating an item that is past the gate.
     *
     * <p>Two failure modes, two policies. A database <em>error</em> stays best-effort, and that
     * policy lives here rather than at the call site: losing the lease write costs reap
     * protection, not correctness, and failing the item over it would be strictly worse than
     * proceeding without it. But an update that succeeds and matches <em>zero rows</em> is not an
     * error — it is the database saying another instance holds a live lease on this item right
     * now (or the row is gone), and executing anyway is exactly the two-workers-one-video
     * collision the lease exists to prevent.
     *
     * @return {@code false} only in that zero-rows case — the caller must skip execution and
     *         leave the item to its owner.
     */
    public boolean acquire(UUID itemId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            if (runItemRepository.acquireLease(itemId, owner, now, now.plus(ttl)) == 0) {
                log.warn("Item {} is leased by another instance; skipping execution here", itemId);
                return false;
            }
        } catch (Exception e) {
            log.warn("Could not acquire lease for item {}: {}", itemId, e.getMessage());
        }
        leased.add(itemId);
        return true;
    }

    /**
     * Stop heartbeating and clear the lease. Best-effort for the same reason as {@link #acquire},
     * and owner-scoped in SQL, so calling it for an item whose lease this instance never won is a
     * no-op rather than a theft of the real owner's reap protection.
     */
    public void release(UUID itemId) {
        leased.remove(itemId);
        try {
            runItemRepository.releaseLease(itemId, owner);
        } catch (Exception e) {
            log.warn("Could not release lease for item {}: {}", itemId, e.getMessage());
        }
    }

    /** Extends only the leases this instance still owns; returns how many were renewed. */
    public int renew(Collection<UUID> itemIds) {
        return runItemRepository.renewLeases(itemIds, owner, OffsetDateTime.now(ZoneOffset.UTC).plus(ttl));
    }

    /**
     * Keeps this instance's leases alive while it is executing items. The interval must stay well
     * below {@code vidingest.lease.ttl}; the defaults leave a 5x margin, so several consecutive
     * misses are needed before live work looks abandoned.
     */
    @Scheduled(fixedDelayString = "${vidingest.lease.heartbeatMs:120000}",
            initialDelayString = "${vidingest.lease.heartbeatMs:120000}")
    public void renewLeases() {
        Set<UUID> live = Set.copyOf(leased);
        if (live.isEmpty()) {
            return;
        }
        try {
            int renewed = renew(live);
            if (renewed < live.size()) {
                // Someone else owns an item we think we are running, or the row is gone. Worth
                // seeing: it is the shape a split brain or a premature reap would take.
                log.warn("Lease heartbeat renewed {} of {} in-flight items", renewed, live.size());
            }
        } catch (Exception e) {
            // A heartbeat failure must not kill the scheduler thread; the next tick retries and
            // the TTL margin covers several misses.
            log.error("Lease heartbeat failed: {}", e.getMessage(), e);
        }
    }

    /** True while some item of the run is held by a live lease, whichever instance owns it. */
    public boolean runHasLiveLease(UUID runId) {
        return runItemRepository.existsLiveLeaseForRun(runId, OffsetDateTime.now(ZoneOffset.UTC));
    }

    public static boolean isLive(OffsetDateTime leaseExpiresAt) {
        return leaseExpiresAt != null && leaseExpiresAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC));
    }
}
