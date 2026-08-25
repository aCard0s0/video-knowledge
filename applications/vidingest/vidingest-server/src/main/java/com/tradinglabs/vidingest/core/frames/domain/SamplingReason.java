package com.tradinglabs.vidingest.core.frames.domain;

/**
 * Why a frame was retained by {@code FrameSamplingService} (M3). Stored as a string in
 * {@code vidingest_video_frames.sampling_reason} via JPA's {@code EnumType.STRING}.
 *
 * <p>Values reflect the union of the two heuristics used in the single ffmpeg pass:
 * scene-change detection (via the {@code scene} filter) and fixed-interval keyframing.
 * Classification is a best-effort heuristic based on temporal proximity to an interval
 * boundary — see {@code FrameSamplingConfig.intervalClassificationTolerance}.
 *
 * <p>{@code KEYFRAME} is reserved for future use (e.g. when we plug in {@code -skip_frame nokey}
 * for I-frame-only fast paths). M3 only produces {@code SCENE_CHANGE} and {@code INTERVAL}.
 */
public enum SamplingReason {
    INTERVAL,
    SCENE_CHANGE,
    KEYFRAME
}
