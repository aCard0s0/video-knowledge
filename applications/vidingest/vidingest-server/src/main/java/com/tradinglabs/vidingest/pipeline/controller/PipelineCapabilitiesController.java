package com.tradinglabs.vidingest.pipeline.controller;

import com.tradinglabs.vidingest.api.paths.VidIngestApiPaths;
import com.tradinglabs.vidingest.api.pipeline.PipelineCapabilities;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.service.phase.PipelinePhase;
import com.tradinglabs.vidingest.pipeline.service.phase.PipelinePhaseContext;
import com.tradinglabs.vidingest.pipeline.service.phase.PipelinePhaseRegistry;
import com.tradinglabs.vidingest.youtube.config.YoutubeSyncProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * What this deployment will actually run.
 *
 * <p>Asks the phases themselves rather than re-reading the five {@code enabled} properties: every
 * optional phase already answers {@code applies(ctx)} by combining its master switch with the
 * run's opt-outs, so a context that skips nothing isolates the switch. Duplicating those reads
 * here would be a second source of truth that drifts the first time a phase gains a dependency.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "pipelines", description = "Pipeline run APIs")
public class PipelineCapabilitiesController {

    /** Skips nothing, so `applies` reflects the deployment's configuration and only that. */
    private static final PipelinePhaseContext NOTHING_SKIPPED =
            new PipelinePhaseContext(null, null, null, Set.of());

    private final PipelinePhaseRegistry registry;
    private final YoutubeSyncProperties youtubeSyncProperties;

    @GetMapping(value = VidIngestApiPaths.PIPELINE_CAPABILITIES, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getPipelineCapabilities", summary = "Which optional phases this server will run",
            description = "Reports the optional phases enabled by configuration, plus the channel-sync limit.")
    public PipelineCapabilities get() {
        List<String> enabled = registry.phases().stream()
                .filter(phase -> phase.phase().isOptional())
                .filter(phase -> phase.applies(NOTHING_SKIPPED))
                .map(PipelinePhase::phase)
                .map(PipelineRunPhase::name)
                .toList();

        return new PipelineCapabilities(enabled, youtubeSyncProperties.getPlaylistLimit());
    }
}
