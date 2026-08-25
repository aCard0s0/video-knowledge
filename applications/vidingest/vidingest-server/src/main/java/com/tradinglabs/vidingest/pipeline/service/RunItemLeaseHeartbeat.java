package com.tradinglabs.vidingest.pipeline.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Keeps this instance's leases alive while it is executing run items.
 *
 * <p>Separate from both collaborators on purpose: {@code PipelineService} owns the set of items
 * this JVM is running and {@link RunItemLeaseService} owns the writes, so putting the schedule
 * on either one would make them depend on each other.
 *
 * <p>The interval must stay comfortably below {@code vidingest.lease.ttl} — the defaults leave a
 * 5× margin, so several consecutive heartbeats have to fail before a live item looks abandoned.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunItemLeaseHeartbeat {

    private final PipelineService pipelineService;
    private final RunItemLeaseService leaseService;

    @Scheduled(fixedDelayString = "${vidingest.lease.heartbeatMs:120000}",
            initialDelayString = "${vidingest.lease.heartbeatMs:120000}")
    public void renewLeases() {
        Set<UUID> inFlight = pipelineService.inFlightItemIds();
        if (inFlight.isEmpty()) {
            return;
        }
        try {
            int renewed = leaseService.renew(inFlight);
            if (renewed < inFlight.size()) {
                // Someone else owns an item we think we are running, or the row is gone. Worth
                // seeing: it is the shape a split brain or a premature reap would take.
                log.warn("Lease heartbeat renewed {} of {} in-flight items (owner={})",
                        renewed, inFlight.size(), leaseService.owner());
            } else {
                log.debug("Lease heartbeat renewed {} items", renewed);
            }
        } catch (Exception e) {
            // Never let a heartbeat failure kill the scheduler thread; the next tick retries and
            // the TTL margin covers several misses.
            log.error("Lease heartbeat failed (owner={}): {}", leaseService.owner(), e.getMessage(), e);
        }
    }
}
