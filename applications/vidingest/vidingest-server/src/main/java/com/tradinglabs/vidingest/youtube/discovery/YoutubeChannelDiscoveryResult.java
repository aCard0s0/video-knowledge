package com.tradinglabs.vidingest.youtube.discovery;

import java.time.OffsetDateTime;
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
            OffsetDateTime publishedAt,
            String watchUrl,
            Map<String, Object> metadata
    ) {
    }
}

