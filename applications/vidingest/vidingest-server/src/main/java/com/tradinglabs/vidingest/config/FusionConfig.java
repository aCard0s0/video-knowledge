package com.tradinglabs.vidingest.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the multi-modal fusion phase (M5).
 *
 * <p>{@code SegmentFusionService} walks the video timeline in fixed windows, merging
 * transcript segments (with their speaker labels from M2) and OCR detections (from M4)
 * into one {@code MultimodalSegment} row per window. The output is the uniform input
 * downstream phases (M6 KNOWLEDGE + the enhanced M7 CONTEXT) consume regardless of which
 * upstream phases actually ran.
 *
 * <p>This phase is pure Java with no external dependencies, so it defaults to
 * {@code enabled = true} — it's cheap to run and produces an empty result set when there
 * is nothing to fuse, never a hard failure.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "vidingest.fusion")
public class FusionConfig {

    /**
     * Master switch. When false the {@code FusePhase} short-circuits.
     */
    private boolean enabled = true;

    /**
     * Length of each fusion window in seconds. Larger windows give the M6 LLM more context
     * per row at the cost of less temporal precision in the knowledge units. 30s strikes a
     * reasonable balance for talking-head videos.
     */
    private double windowSeconds = 30.0;

    /**
     * How much consecutive windows overlap (in seconds). Overlap means a transcript
     * sentence near a window boundary appears in both windows, giving the LLM redundant
     * but useful context. Must be {@code < windowSeconds}.
     */
    private double windowOverlapSeconds = 5.0;

    /**
     * Minimum window duration (seconds) to bother persisting. Without this, a video whose
     * last frame falls just past a window boundary would produce a 0.5s tail window that
     * adds no information.
     */
    private double minWindowSeconds = 1.0;

    /**
     * Hard cap on persisted segments per video. Defensive — a misconfigured tiny window
     * could otherwise produce thousands of rows. Truncates the tail (later windows).
     */
    private int maxSegmentsPerVideo = 2_000;
}
