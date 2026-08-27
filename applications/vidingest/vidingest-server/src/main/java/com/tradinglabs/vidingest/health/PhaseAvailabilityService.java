package com.tradinglabs.vidingest.health;

import com.tradinglabs.vidingest.api.health.PhaseAvailability;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.service.phase.PipelinePhaseContext;
import com.tradinglabs.vidingest.pipeline.service.phase.PipelinePhaseRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Which optional phases this deployment will execute.
 *
 * <p>The answer comes from {@link com.tradinglabs.vidingest.pipeline.service.phase.PipelinePhase
 * #applies} itself rather than from re-reading the {@code vidingest.<phase>.enabled} properties:
 * that gate is already the one thing that decides, and a second reader of the same six config
 * beans would drift the first time a phase grew a condition. Asking a phase with **nothing
 * skipped** isolates the deployment toggle from the run's own opt-outs, which is exactly the
 * distinction the console could not make.
 *
 * <p>Safe to call with a context carrying no run, item, url or video: every {@code applies}
 * override reads its config bean and {@code ctx.skipped(...)}, and nothing else.
 */
@Service
@RequiredArgsConstructor
public class PhaseAvailabilityService {

    private final PipelinePhaseRegistry registry;

    public PhaseAvailability availability() {
        PipelinePhaseContext probe = new PipelinePhaseContext(null, null, null, Set.of());
        Map<String, Boolean> phases = new LinkedHashMap<>();
        for (PipelineRunPhase phase : PipelineRunPhase.optionalPhases()) {
            phases.put(phase.name(), registry.byPhase(phase).map(p -> p.applies(probe)).orElse(false));
        }
        return new PhaseAvailability(phases);
    }
}
