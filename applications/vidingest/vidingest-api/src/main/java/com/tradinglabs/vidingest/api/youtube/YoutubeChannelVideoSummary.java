package com.tradinglabs.vidingest.api.youtube;

import java.time.LocalDateTime;

public record YoutubeChannelVideoSummary(
        String id,
        String youtubeVideoId,
        String title,
        LocalDateTime publishedAt,
        String watchUrl,
        boolean ingested
) {
}

