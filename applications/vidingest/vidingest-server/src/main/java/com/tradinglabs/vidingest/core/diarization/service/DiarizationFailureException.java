package com.tradinglabs.vidingest.core.diarization.service;

/**
 * Thrown when the diarization phase cannot complete — audio extraction failed, the sidecar
 * returned non-2xx, the response could not be parsed, or persistence of the speaker rows
 * failed. Surfaced via {@code PipelineErrorClassifier} like other upstream-tool failures.
 */
public class DiarizationFailureException extends RuntimeException {

    public DiarizationFailureException(String message) {
        super(message);
    }

    public DiarizationFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
