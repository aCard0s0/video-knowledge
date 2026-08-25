package com.tradinglabs.vidingest.core.diarization.service;

import com.tradinglabs.vidingest.commons.PhaseFailureException;

/**
 * Thrown when the diarization phase cannot complete — audio extraction failed, the sidecar
 * returned non-2xx, the response could not be parsed, or persistence of the speaker rows
 * failed. Surfaced via {@code PipelineErrorClassifier} like other upstream-tool failures.
 */
public class DiarizationFailureException extends PhaseFailureException {

    public DiarizationFailureException(String message) {
        super(message);
    }

    public DiarizationFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
