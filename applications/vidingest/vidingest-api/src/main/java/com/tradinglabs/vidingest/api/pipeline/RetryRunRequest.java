package com.tradinglabs.vidingest.api.pipeline;

import jakarta.validation.constraints.NotNull;

/**
 * Request payload for {@code POST /api/v1/pipelines/{runId}/retry}.
 *
 * <p>Mirrors {@link CreatePipelineRunRequest} so retries have the same toggle surface as fresh runs.
 */
public record RetryRunRequest(
        @NotNull Boolean skipTranscription,
        @NotNull Boolean skipContext,
        @NotNull Boolean skipDiarize,
        @NotNull Boolean skipFrames,
        @NotNull Boolean skipOcr,
        @NotNull Boolean skipKnowledge
) {
}
