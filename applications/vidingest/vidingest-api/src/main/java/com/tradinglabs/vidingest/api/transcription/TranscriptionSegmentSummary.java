package com.tradinglabs.vidingest.api.transcription;

public record TranscriptionSegmentSummary(
        String id,
        Float startSeconds,
        Float endSeconds,
        String text,
        String createdAt
) {
}

