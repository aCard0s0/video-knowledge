package com.tradinglabs.vidingest.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration for the speaker-diarization phase (M2).
 *
 * <p>The diarization sidecar (default {@code diarize-asr} on port 9001) is a Python service
 * wrapping {@code pyannote.audio} 3.x. It receives a 16kHz mono PCM WAV (same shape we feed
 * to Whisper) and returns per-segment speaker labels which we assign to
 * {@code TranscriptionSegment.speaker_id} via time-overlap.
 *
 * <p>Defaults to {@code enabled = false} so the channel scheduler and existing callers don't
 * incur the GPU cost or HuggingFace dependency until an operator explicitly opts in.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "vidingest.diarization")
public class DiarizationConfig {

    /**
     * Master switch. When false the {@code DiarizePhase} short-circuits regardless of
     * the run's own {@code skipPhases} opt-out.
     */
    private boolean enabled = false;

    /**
     * Diarization sidecar base URL (e.g. {@code http://localhost:9001} or
     * {@code http://diarize-asr:9001} in Docker). Override via
     * {@code VIDINGEST_DIARIZATION_BASE_URL}.
     */
    private String baseUrl = "http://localhost:9001";

    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * Diarization is slower than transcription on CPU (often 1.5–3× audio duration).
     * Default read timeout is generous; tune down in tests or on GPU hosts.
     */
    private Duration readTimeout = Duration.ofMinutes(30);

    /**
     * Optional hint passed to pyannote. Null means "let the model decide".
     */
    private Integer minSpeakers;

    /**
     * Upper bound passed to pyannote to keep clustering tractable on long inputs.
     */
    private Integer maxSpeakers = 8;

    /**
     * Minimum segment-vs-speaker overlap (in seconds) required to assign a speaker_id to a
     * transcription segment. Prevents stray 50ms overlaps from polluting labels.
     */
    private double minOverlapSeconds = 0.25;
}
