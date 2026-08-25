package com.tradinglabs.vidingest.api.videos;

/**
 * Lightweight per-video artifact counts used to populate collapsed UI panels without
 * forcing heavy eager loads.
 */
public record VideoArtifactCounts(
        long speakers,
        long ocrFrames,
        long multimodalSegments,
        long transcriptionSegments,
        long knowledgeUnits
) {
}

