package com.tradinglabs.vidingest.core.diarization.dto;

import java.util.List;

/**
 * Parsed response from the {@code diarize-asr} sidecar.
 *
 * @param segments per-window speaker assignments (ordered by start time)
 * @param speakers unique speakers identified in the audio (labels + optional voiceprints)
 */
public record DiarizationResult(
        List<DiarizationSegment> segments,
        List<DiarizationSpeaker> speakers
) {
}
