package com.tradinglabs.vidingest.core.transcription.dto;

public record WhisperSegment(
        Float startSeconds,
        Float endSeconds,
        String text
) {
}
