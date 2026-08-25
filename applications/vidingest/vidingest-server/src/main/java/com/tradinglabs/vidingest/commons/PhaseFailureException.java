package com.tradinglabs.vidingest.commons;

/**
 * Common supertype for the "this pipeline phase could not complete" exceptions thrown by the
 * phase services — a sidecar was unreachable, an external tool exited non-zero, a response
 * could not be parsed, or the resulting rows could not be persisted.
 *
 * <p>It exists so failure translation happens once per boundary rather than once per phase:
 * {@code PipelineErrorClassifier} maps the whole family to
 * {@code PipelineErrorCode.UPSTREAM_TOOL_FAILURE}, and {@code VidingestApiExceptionHandler}
 * maps it to HTTP 502. Before this type both tables listed phases individually and both had
 * fallen behind the set of phases that actually exist.
 *
 * <p>Subclasses stay phase-specific so callers that genuinely care about one phase (for
 * example {@code OcrService} swallowing a single bad frame) can still catch narrowly.
 */
public abstract class PhaseFailureException extends RuntimeException {

    protected PhaseFailureException(String message) {
        super(message);
    }

    protected PhaseFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
