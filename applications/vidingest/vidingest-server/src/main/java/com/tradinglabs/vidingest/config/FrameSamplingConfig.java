package com.tradinglabs.vidingest.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration for the frame-sampling phase (M3).
 *
 * <p>{@code FrameSamplingService} invokes ffmpeg with a {@code select} filter that picks
 * keyframes by two heuristics merged into a single pass:
 * <ul>
 *   <li><b>Scene change</b> — frames whose {@code scene} metadata exceeds {@link #sceneChangeThreshold}.</li>
 *   <li><b>Fixed interval</b> — at least one frame every {@link #intervalSeconds} seconds.</li>
 * </ul>
 * The combined select keeps disk IO to a single read of the video file. The downstream
 * classification heuristic in {@code FrameSamplingService} tags each persisted frame with
 * {@code sampling_reason = SCENE_CHANGE} or {@code INTERVAL} based on its proximity to an
 * interval boundary.
 *
 * <p>Frames are written under the per-video folder at
 * {@code {videoPath}/{channelName}/{baseName}/{framesDirName}/NNNN.jpg} so they cascade
 * naturally on video delete (handled by {@code VideoDeleteService}).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "vidingest.frames")
public class FrameSamplingConfig {

    /**
     * Master switch. When false, {@code FrameSamplePhase.applies(ctx)} short-circuits.
     */
    private boolean enabled = false;

    /**
     * Minimum seconds between fixed-interval frames. Acts as a floor on temporal sampling
     * density regardless of how busy the scene is. {@code 0} disables interval sampling.
     */
    private double intervalSeconds = 10.0;

    /**
     * ffmpeg {@code scene} filter threshold (range 0–1). Higher = more aggressive scene
     * detection, fewer frames. {@code 0} disables scene-change sampling.
     */
    private double sceneChangeThreshold = 0.35;

    /**
     * Hard cap on persisted frames per video. ffmpeg is allowed to write more JPGs than
     * this — the service truncates and deletes the excess JPGs on disk so the row count
     * matches the disk state.
     */
    private int maxFramesPerVideo = 600;

    /**
     * Name of the frames subdirectory inside the per-video folder.
     * Example: {@code {channel}/{date.title}/frames/}.
     */
    private String framesDirName = "frames";

    /**
     * ffmpeg {@code -q:v} JPEG quality (1 = best, 31 = worst). 2 produces high-quality
     * frames that are still good for OCR (M4) and vision captioning (future M9).
     */
    private int jpegQuality = 2;

    /**
     * Tolerance (seconds) used by the {@code SCENE_CHANGE vs INTERVAL} classifier. A frame
     * whose timestamp falls within this tolerance of an exact interval boundary
     * ({@code k * intervalSeconds}) is tagged {@code INTERVAL}; otherwise {@code SCENE_CHANGE}.
     */
    private double intervalClassificationTolerance = 0.5;

    /**
     * Hard timeout for the ffmpeg process. Long videos on slow machines need generous
     * defaults; tune downward in tests or for short-form content.
     */
    private Duration ffmpegTimeout = Duration.ofMinutes(20);
}
