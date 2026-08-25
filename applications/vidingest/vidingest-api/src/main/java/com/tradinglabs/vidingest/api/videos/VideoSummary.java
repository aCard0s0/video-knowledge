package com.tradinglabs.vidingest.api.videos;

public record VideoSummary(
        String id,
        String pipelineId,
        String title,
        String source,
        String sourceVideoId,
        String status,
        String filePath,
        String channelName,
        String createdAt
) {
}

