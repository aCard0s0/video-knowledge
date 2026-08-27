package com.tradinglabs.vidingest.videos.repo;

import com.tradinglabs.vidingest.videos.domain.VideoStatus;

import java.time.OffsetDateTime;
import java.util.Comparator;
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
        OffsetDateTime createdAt
) {

    /**
     * Which video represents a run: the most-progressed one, oldest first on a tie, then by
     * video id so the pick is stable across calls. Nulls sort last throughout.
     *
     * <p>Lives here because the run-list and run-details read paths both need it and used to
     * carry a copy each — one over this projection, one over the {@code Video} entity, with
     * two independent exhaustive {@code VideoStatus} switches to keep in step.
     */
    public static final Comparator<RunVideoPreview> PREVIEW_ORDER = Comparator
            .comparingInt((RunVideoPreview p) -> statusRank(p.status()))
            .thenComparing(RunVideoPreview::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(RunVideoPreview::videoId, Comparator.nullsLast(Comparator.naturalOrder()));

    private static int statusRank(VideoStatus status) {
        if (status == null) return 100;
        return switch (status) {
            case COMPLETED -> 0;
            case PROCESSING -> 1;
            case TRANSCRIBING -> 2;
            case DOWNLOADED -> 3;
            case DOWNLOADING -> 4;
            case EXTRACTING -> 5;
            case PENDING -> 6;
            case FAILED -> 7;
        };
    }
}
