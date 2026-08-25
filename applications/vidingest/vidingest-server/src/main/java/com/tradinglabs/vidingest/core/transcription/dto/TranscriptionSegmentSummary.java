package com.tradinglabs.vidingest.core.transcription.dto;

public record TranscriptionSegmentSummary(
        String id,
        Float startSeconds,
        Float endSeconds,
        String text,
        String createdAt
) {
}

