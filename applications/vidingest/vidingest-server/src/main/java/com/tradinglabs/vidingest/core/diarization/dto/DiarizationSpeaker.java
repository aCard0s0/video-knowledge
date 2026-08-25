package com.tradinglabs.vidingest.core.diarization.dto;

/**
 * One distinct speaker identified by the sidecar across the entire audio. The
 * {@code embeddingVoiceprint} is optional; when present, pyannote returns a 192-d x-vector
 * that we persist on {@code Speaker.embedding_voiceprint} for future cross-video speaker
 * re-identification.
 */
public record DiarizationSpeaker(
        String label,
        float[] embeddingVoiceprint
) {
}
