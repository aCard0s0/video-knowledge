package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.UUID;

/**
 * Ownership leases over in-flight run items.
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
 */
@Service
@Slf4j
public class RunItemLeaseService {

    private final PipelineRunItemRepository runItemRepository;
    private final String owner;
    private final Duration ttl;

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

    public void acquire(UUID itemId) {
        runItemRepository.acquireLease(itemId, owner, LocalDateTime.now().plus(ttl));
    }

    /** Extends only the leases this instance still owns; returns how many were renewed. */
    public int renew(Collection<UUID> itemIds) {
        return runItemRepository.renewLeases(itemIds, owner, LocalDateTime.now().plus(ttl));
    }

    public void release(UUID itemId) {
        runItemRepository.releaseLease(itemId);
    }

    /** True while some item of the run is held by a live lease, whichever instance owns it. */
    public boolean runHasLiveLease(UUID runId) {
        return runItemRepository.existsLiveLeaseForRun(runId, LocalDateTime.now());
    }

    public static boolean isLive(LocalDateTime leaseExpiresAt) {
        return leaseExpiresAt != null && leaseExpiresAt.isAfter(LocalDateTime.now());
    }
}
