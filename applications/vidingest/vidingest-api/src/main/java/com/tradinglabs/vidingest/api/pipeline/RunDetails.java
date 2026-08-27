package com.tradinglabs.vidingest.api.pipeline;

import java.util.List;

public record RunDetails(
        String id,
        String status,
        String phase,
        String phaseUpdatedAt,
        String errorCode,
        String error,
        String videoUrl,
        String videoId,
        String channelName,
        String videoTitle,
        int videoCount,
        List<RunItem> items,
        String createdAt,
        String updatedAt,
        // The optional phases this run is configured to skip, in pipeline order. Read it to seed a
        // retry: the lane cannot answer this, because a phase after the one that failed was never
        // reached and that is indistinguishable from skipped. A retry that omits skipPhases
        // entirely inherits this set server-side.
        List<String> skipPhases
) {

    public record RunItem(
            String itemId,
            String url,
            String status,
            String phase,
            String failedPhase,
            String phaseUpdatedAt,
            String errorCode,
            String error,
            String videoId,
            String channelName,
            String videoTitle,
            int attempt
    ) {
    }
}

