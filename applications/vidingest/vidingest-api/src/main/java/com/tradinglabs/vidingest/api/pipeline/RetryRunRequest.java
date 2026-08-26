package com.tradinglabs.vidingest.api.pipeline;

import java.util.Set;

/**
 * Request payload for {@code POST /api/v1/pipelines/{runId}/retry}.
 *
 * <p>Mirrors {@link CreatePipelineRunRequest} so retries have the same toggle surface as fresh runs,
 * with one difference: <b>absent is not empty</b>. A {@code null} {@code skipPhases} means "retry
 * this run the way it was configured" and the server reuses the set stored on the run; an empty set
 * means "run every enabled phase". A client with no phase picker should omit the field — sending an
 * empty set silently turned the enrichment phases back on for runs created without them.
 */
public record RetryRunRequest(
        Set<String> skipPhases
) {
}
