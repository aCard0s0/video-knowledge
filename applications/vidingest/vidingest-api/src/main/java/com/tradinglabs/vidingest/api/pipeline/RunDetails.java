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
        String updatedAt
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

