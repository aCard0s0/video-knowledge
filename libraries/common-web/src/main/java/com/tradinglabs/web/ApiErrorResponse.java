package com.tradinglabs.web;

import java.time.Instant;
import java.util.Map;

/**
 * Unified error response following RFC 7807 conventions.
 * All apps use this shape for consistent API error payloads.
 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String correlationId,
        Map<String, String> validationErrors
) {
    public static ApiErrorResponse of(int status, String error, String message, String path) {
        return new ApiErrorResponse(Instant.now(), status, error, message, path, CorrelationIds.current(), null);
    }

    public static ApiErrorResponse withValidation(int status, String error, String message, String path,
                                                   Map<String, String> validationErrors) {
        return new ApiErrorResponse(Instant.now(), status, error, message, path, CorrelationIds.current(), validationErrors);
    }
}
