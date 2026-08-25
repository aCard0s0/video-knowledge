package com.tradinglabs.vidingest.videos.repo;

import com.tradinglabs.vidingest.videos.domain.VideoStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lightweight projection of the fields needed to render a pipeline run-list preview and per-run video
 * count, without hydrating full {@code Video} entities (and their JSONB {@code metadata}) (#266).
 *
 * <p>{@code status} and {@code createdAt} drive preview selection; {@code videoId}/{@code channelName}/
 * {@code title} feed the run summary card.</p>
 */
public record RunVideoPreview(
        UUID runId,
        UUID videoId,
        String channelName,
        String title,
        VideoStatus status,
        LocalDateTime createdAt
) {
}
