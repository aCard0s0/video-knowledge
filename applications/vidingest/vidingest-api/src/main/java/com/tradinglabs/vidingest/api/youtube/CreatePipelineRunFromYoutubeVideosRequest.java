package com.tradinglabs.vidingest.api.youtube;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;

/**
 * Request payload for {@code POST /api/v1/youtube/channels/{channelId}/pipelines}.
 *
 * <p>Channel-driven pipeline runs honour the same {@code skipPhases} opt-out as direct runs so
 * the channel scheduler does not silently enable enrichment when global flags are flipped on.
 */
public record CreatePipelineRunFromYoutubeVideosRequest(
        @NotEmpty
        @Size(max = 100)
        List<String> youtubeVideoIds,
        Set<String> skipPhases
) {
}
