package com.tradinglabs.vidingest.api.pipeline;

public record RunSummary(
        String id,
        String status,
        String phase,
        String errorCode,
        String error,
        String videoUrl,
        String videoId,
        String channelName,
        String videoTitle,
        int videoCount,
        String createdAt,
        String updatedAt
) {
}

