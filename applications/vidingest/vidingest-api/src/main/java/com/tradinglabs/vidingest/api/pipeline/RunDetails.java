package com.tradinglabs.vidingest.api.pipeline;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** The run-detail read model. See {@link RunSummary} on the typing. */
public record RunDetails(
        UUID id,
        String status,
        String phase,
        OffsetDateTime phaseUpdatedAt,
        String errorCode,
        String error,
        String videoUrl,
        UUID videoId,
        String channelName,
        String videoTitle,
        int videoCount,
        List<RunItem> items,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        // The optional phases this run is configured to skip, in pipeline order. Read it to seed a
        // retry: the lane cannot answer this, because a phase after the one that failed was never
        // reached and that is indistinguishable from skipped. A retry that omits skipPhases
        // entirely inherits this set server-side.
        List<String> skipPhases
) {

    public record RunItem(
            UUID itemId,
            String url,
            String status,
            String phase,
            String failedPhase,
            OffsetDateTime phaseUpdatedAt,
            String errorCode,
            String error,
            UUID videoId,
            String channelName,
            String videoTitle,
            int attempt
    ) {
    }
}
