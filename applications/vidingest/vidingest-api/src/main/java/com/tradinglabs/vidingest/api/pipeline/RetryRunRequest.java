package com.tradinglabs.vidingest.api.pipeline;

import java.util.Set;

/**
 * Request payload for {@code POST /api/v1/pipelines/{runId}/retry}.
 *
 * <p>Mirrors {@link CreatePipelineRunRequest} so retries have the same toggle surface as fresh runs.
 */
public record RetryRunRequest(
        Set<String> skipPhases
) {
}
