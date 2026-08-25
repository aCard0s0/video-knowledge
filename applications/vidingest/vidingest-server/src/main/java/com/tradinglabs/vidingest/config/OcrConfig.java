package com.tradinglabs.vidingest.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for the OCR phase (M4).
 *
 * <p>The {@code paddleocr-server} sidecar (default port 8002) wraps PaddleOCR and receives
 * one JPG per request via multipart upload. The Java side reads {@code vidingest_video_frames}
 * for a video, POSTs each frame's JPG to the sidecar, filters detections below
 * {@link #minConfidence}, and persists the survivors as {@code OcrResult} rows.
 *
 * <p>Defaults to {@code enabled = false} so existing pipelines keep their current behaviour;
 * operators opt in once the sidecar is reachable and the {@code vidingest.frames.*} phase
 * is producing frames.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "vidingest.ocr")
public class OcrConfig {

    /**
     * Master switch. When false the {@code OcrPhase} short-circuits regardless of the
     * run's own {@code skipPhases} opt-out.
     */
    private boolean enabled = false;

    /**
     * OCR sidecar base URL (e.g. {@code http://localhost:8002} or
     * {@code http://paddleocr-server:8002} in Docker). Override via
     * {@code VIDINGEST_OCR_BASE_URL}.
     */
    private String baseUrl = "http://localhost:8002";

    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * OCR per-frame inference is generally fast (sub-second on GPU, a few seconds on CPU).
     * The read timeout here is per-frame, not per-video.
     */
    private Duration readTimeout = Duration.ofMinutes(2);

    /**
     * Detection languages passed to PaddleOCR. The sidecar applies these as the {@code lang}
     * model selector. Multiple languages run sequentially; keep this short unless you really
     * need multilingual OCR.
     */
    private List<String> languages = List.of("en");

    /**
     * Per-line confidence floor. Detections below this score are dropped before persistence.
     * PaddleOCR scores range [0,1]; 0.5 is a reasonable default for noisy YouTube frames.
     */
    private double minConfidence = 0.5;

    /**
     * Frames whose surviving line count is below this threshold get skipped entirely (no
     * rows persisted) — saves storage and downstream noise for frames that produced only
     * stray detections. Default 1: keep any frame with at least one good line.
     */
    private int minLinesPerFrame = 1;

    /**
     * Cap on total OCR rows persisted per video. Acts as a defensive limit when ffmpeg
     * over-sampled or PaddleOCR detects an unusually large number of lines per frame.
     * Truncates the tail (later frames) rather than uniformly subsampling.
     */
    private int maxResultsPerVideo = 10_000;
}
