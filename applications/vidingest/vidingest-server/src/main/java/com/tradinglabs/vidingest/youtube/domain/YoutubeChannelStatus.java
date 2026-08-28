package com.tradinglabs.vidingest.youtube.domain;

/**
 * Lifecycle of a watched channel: {@code NEW → SYNCING → READY}, or {@code ERROR} with
 * {@code lastError} set.
 *
 * <p>There is no disabled state. A {@code DISABLED} constant existed and was never reachable —
 * nothing set it and no endpoint produced it — while two guards branched on it, so both were dead.
 * Stopping tracking is {@code DELETE /api/v1/youtube/channels/{channelId}}; see
 * {@code YoutubeChannelCommandService#deleteChannel}.
 */
public enum YoutubeChannelStatus {
    NEW,
    SYNCING,
    READY,
    ERROR
}

