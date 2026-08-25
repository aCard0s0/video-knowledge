package com.tradinglabs.vidingest.core.ocr.service;

import com.tradinglabs.vidingest.commons.PhaseFailureException;

/**
 * Thrown when the OCR phase cannot complete — missing frames, unreachable sidecar,
 * non-2xx response, JSON parse failure, or persistence error. Surfaced via
 * {@code PipelineErrorClassifier} like other upstream-tool failures.
 */
public class OcrFailureException extends PhaseFailureException {

    public OcrFailureException(String message) {
        super(message);
    }

    public OcrFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
