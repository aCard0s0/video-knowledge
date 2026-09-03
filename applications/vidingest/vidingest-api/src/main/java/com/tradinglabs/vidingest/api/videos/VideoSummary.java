package com.tradinglabs.vidingest.api.videos;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One row of the videos list. See {@code RunSummary} on the typing. */
public record VideoSummary(
        UUID id,
        UUID pipelineId,
        String title,
        String source,
        String sourceVideoId,
        String status,
        String filePath,
        String channelName,
        OffsetDateTime createdAt
) {
}
