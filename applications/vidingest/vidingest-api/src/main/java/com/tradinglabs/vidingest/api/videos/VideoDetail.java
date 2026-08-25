package com.tradinglabs.vidingest.api.videos;

import com.tradinglabs.vidingest.api.transcription.VideoTranscriptionDetails;

/**
 * Aggregate read model for the video detail page's eager region.
 */
public record VideoDetail(
        VideoSummary video,
        VideoTranscriptionDetails transcription,
        VideoArtifactCounts counts
) {
}

