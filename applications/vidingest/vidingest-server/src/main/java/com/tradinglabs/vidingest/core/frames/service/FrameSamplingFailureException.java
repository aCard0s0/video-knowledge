package com.tradinglabs.vidingest.core.frames.service;

/**
 * Thrown when frame sampling cannot complete — ffmpeg missing/non-zero exit, output dir
 * inaccessible, parse failure, or persistence error. Surfaced via
 * {@code PipelineErrorClassifier} like other ffmpeg/upstream-tool failures.
 */
public class FrameSamplingFailureException extends RuntimeException {

    public FrameSamplingFailureException(String message) {
        super(message);
    }

    public FrameSamplingFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
