package com.tradinglabs.vidingest.youtube.discovery;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record YoutubeChannelDiscoveryResult(
        String channelUrl,
        String channelId,
        String channelName,
        Map<String, Object> metadata,
        List<YoutubeVideoCandidate> videos
) {
    public record YoutubeVideoCandidate(
            String youtubeVideoId,
            String title,
            LocalDateTime publishedAt,
            String watchUrl,
            Map<String, Object> metadata
    ) {
    }
}

