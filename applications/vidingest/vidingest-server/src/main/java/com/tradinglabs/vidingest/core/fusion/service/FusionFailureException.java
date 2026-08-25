package com.tradinglabs.vidingest.core.fusion.service;

/**
 * Thrown for unrecoverable misconfiguration in {@code SegmentFusionService} (bad window
 * settings, null video, etc.). Missing upstream signals are not failures — the fusion
 * phase produces an empty result set in that case rather than throwing.
 */
public class FusionFailureException extends RuntimeException {

    public FusionFailureException(String message) {
        super(message);
    }

    public FusionFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
