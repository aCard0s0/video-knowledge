package com.tradinglabs.vidingest.api.pipeline;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request payload for {@code POST /api/v1/pipelines}.
 *
 * <p>The {@code skipDiarize}, {@code skipFrames}, {@code skipOcr}, {@code skipKnowledge} flags
 * gate the multi-modal enrichment phases added in M1 of the knowledge-extraction expansion.
 * The phases themselves are no-ops in M1 (they always return {@code applies(ctx) = false}).
 */
public record CreatePipelineRunRequest(
        @NotEmpty
        @Size(max = 100)
        List<String> urls,
        @NotNull
        Boolean skipTranscription,
        @NotNull
        Boolean skipContext,
        @NotNull
        Boolean skipDiarize,
        @NotNull
        Boolean skipFrames,
        @NotNull
        Boolean skipOcr,
        @NotNull
        Boolean skipKnowledge
) {
}
