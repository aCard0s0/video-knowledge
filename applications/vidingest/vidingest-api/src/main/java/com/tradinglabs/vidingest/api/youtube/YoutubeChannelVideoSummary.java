package com.tradinglabs.vidingest.api.youtube;

import java.time.OffsetDateTime;

public record YoutubeChannelVideoSummary(
        String id,
        String youtubeVideoId,
        String title,
        OffsetDateTime publishedAt,
        String watchUrl,
        boolean ingested
) {
}

