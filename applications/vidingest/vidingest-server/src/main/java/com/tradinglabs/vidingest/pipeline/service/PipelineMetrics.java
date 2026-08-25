package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.domain.PipelineErrorCode;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class PipelineMetrics {

    static final String METRIC_PREFIX = "vidingest.pipeline.";
    static final String METRIC_ITEMS_CREATED = METRIC_PREFIX + "items.created";
    static final String METRIC_ITEMS_COMPLETED = METRIC_PREFIX + "items.completed";
    static final String METRIC_ITEMS_FAILED = METRIC_PREFIX + "items.failed";
    static final String METRIC_ITEMS_CANCELLED = METRIC_PREFIX + "items.cancelled";
    static final String METRIC_PHASE_DURATION = METRIC_PREFIX + "phase.duration";
    static final String METRIC_ITEMS_INFLIGHT = METRIC_PREFIX + "items.inflight";

    private final MeterRegistry registry;
    private final PipelineRunItemRepository runItemRepository;
    private final Counter itemsCreated;
    private final Counter itemsCompleted;
    private final AtomicLong inflightSnapshot = new AtomicLong();

    public PipelineMetrics(MeterRegistry registry, PipelineRunItemRepository runItemRepository) {
        this.registry = registry;
        this.runItemRepository = runItemRepository;
        this.itemsCreated = registry.counter(METRIC_ITEMS_CREATED);
        this.itemsCompleted = registry.counter(METRIC_ITEMS_COMPLETED);
        registry.gauge(METRIC_ITEMS_INFLIGHT, inflightSnapshot, AtomicLong::doubleValue);
    }

    public void incrementCreated(int count) {
        itemsCreated.increment(count);
    }

    public void incrementCompleted() {
        itemsCompleted.increment();
    }

    public void incrementFailed(PipelineErrorCode errorCode) {
        registry.counter(METRIC_ITEMS_FAILED, List.of(Tag.of("errorCode", tagValue(errorCode)))).increment();
    }

    public void incrementCancelled(PipelineErrorCode errorCode) {
        registry.counter(METRIC_ITEMS_CANCELLED, List.of(Tag.of("errorCode", tagValue(errorCode)))).increment();
    }

    public Timer.Sample startPhaseTimer() {
        return Timer.start(registry);
    }

    public void stopPhaseTimer(Timer.Sample sample, PipelineRunPhase phase, boolean success) {
        Timer timer = Timer.builder(METRIC_PHASE_DURATION)
                .tag("phase", phase.name())
                .tag("outcome", success ? "success" : "failure")
                .register(registry);
        sample.stop(timer);
    }

    /**
     * Recomputes the in-flight gauge value from the database. Cheap (a single count query)
     * and intended to be called from a scheduler or after lifecycle transitions.
     */
    public void refreshInflightGauge() {
        long count = runItemRepository.countByStatusIn(EnumSet.of(RunStatus.PENDING, RunStatus.IN_PROGRESS));
        inflightSnapshot.set(count);
    }

    private static String tagValue(PipelineErrorCode errorCode) {
        return errorCode != null ? errorCode.name() : "NONE";
    }
}
