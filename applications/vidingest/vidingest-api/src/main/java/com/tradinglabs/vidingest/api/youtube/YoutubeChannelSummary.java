package com.tradinglabs.vidingest.api.youtube;

import java.time.LocalDateTime;

public record YoutubeChannelSummary(
        String id,
        String url,
        String displayName,
        String status,
        LocalDateTime lastSyncSuccessAt,
        Long videoCount,
        String lastError
) {
}

