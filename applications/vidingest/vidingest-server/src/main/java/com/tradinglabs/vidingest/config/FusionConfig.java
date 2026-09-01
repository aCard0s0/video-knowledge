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

    /**
     * Drop an OCR line from every window once it appears in at least this fraction of a video's
     * windows. {@code 1.0} disables the filter.
     *
     * <p>Interface chrome is <em>static</em> and content is not: a watermark, a browser tab title
     * or a "Subscribe" overlay is on screen for the whole video, while a price level or a
     * break-of-structure label is on screen for one window. Deduping within a window (which
     * {@code SegmentFusionService} already did) collapses N frames of the same watermark to one
     * line, but still repeats it once per window — so on a 3-minute short with eight windows, OCR
     * was <b>54% of the KNOWLEDGE phase's input</b> (5852 chars against 4938 of transcript),
     * carrying the sponsor's name 21 times and browser chrome like "Ask Gemini" seven times.
     *
     * <p><b>Measured: this does not improve extraction.</b> With both OCR filters applied the input
     * dropped 17% (11435 to 9448 chars, OCR from 54% to 43% of it) and rule recovery was
     * <em>unchanged</em> — 13.8 of 19 filtered against 14.8 unfiltered over four interleaved reps
     * each, a delta inside the harness's ±3-rule noise floor
     * ({@code scripts/eval-knowledge-prompt.py}). Reproduce with
     * {@code v3-baseline.txt@knowledge-fixture-ocr-filtered.json}.
     *
     * <p>So the justification is cost, not quality: a sixth off the prompt for no measured loss,
     * which matters most where {@code vidingest.knowledge.max-input-chars-per-batch} binds and a
     * long video would otherwise split into more batches. The theory that a smaller prompt would
     * recover more rules — plausible, since the model is demonstrably attention-limited — was
     * tested and did not hold. Set {@code 1.0} and {@code carriesMeaning} aside if you would rather
     * have the raw text.
     *
     * <p>0.6 rather than 1.0 because OCR is noisy: the same watermark reads as "LuxAlgo" in one
     * frame and "LuzAgoTrngndicatox" in the next, so an exact-match line rarely hits every window.
     * Lines that vary every frame survive this filter by construction — it removes the stable
     * chrome, not the garbling.
     */
    private double ocrChromeWindowRatio = 0.6;

    /**
     * Minimum window count before {@link #ocrChromeWindowRatio} applies at all.
     *
     * <p>With two windows, one legitimate on-screen label present in both is in 100% of them and
     * would be filtered as chrome. "Appears in most windows" only distinguishes chrome from content
     * once there are enough windows for "most" to mean something.
     */
    private int ocrChromeMinWindows = 4;
}
