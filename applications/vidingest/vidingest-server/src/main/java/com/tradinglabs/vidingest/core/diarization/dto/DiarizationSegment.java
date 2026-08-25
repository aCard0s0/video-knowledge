package com.tradinglabs.vidingest.core.diarization.dto;

/**
 * Per-window output from pyannote: a half-open time interval and the speaker label that
 * dominates it. The {@code DiarizationService} maps each label to a persisted
 * {@code Speaker} row and assigns the speaker id to every {@code TranscriptionSegment}
 * whose interval overlaps this window by at least {@code minOverlapSeconds}.
 */
public record DiarizationSegment(
        float startSeconds,
        float endSeconds,
        String speakerLabel
) {
}
