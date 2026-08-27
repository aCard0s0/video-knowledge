package com.tradinglabs.vidingest.api.youtube;

import java.time.OffsetDateTime;

public record YoutubeChannelSummary(
        String id,
        String url,
        String displayName,
        String status,
        OffsetDateTime lastSyncSuccessAt,
        Long videoCount,
        String lastError
) {
}

