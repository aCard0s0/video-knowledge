package com.tradinglabs.vidingest.api.youtube;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request payload for {@code POST /api/v1/youtube/channels/{channelId}/pipelines}.
 *
 * <p>Channel-driven pipeline runs honour the same enrichment skip flags as direct runs so the
 * channel scheduler does not silently enable enrichment when global flags are flipped on.
 */
public record CreatePipelineRunFromYoutubeVideosRequest(
        @NotEmpty
        @Size(max = 100)
        List<String> youtubeVideoIds,
        @NotNull Boolean skipTranscription,
        @NotNull Boolean skipContext,
        @NotNull Boolean skipDiarize,
        @NotNull Boolean skipFrames,
        @NotNull Boolean skipOcr,
        @NotNull Boolean skipKnowledge
) {
}
