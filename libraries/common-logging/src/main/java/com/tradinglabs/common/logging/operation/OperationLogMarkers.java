package com.tradinglabs.common.logging.operation;

import net.logstash.logback.marker.LogstashMarker;

import java.time.Instant;
import java.util.Map;

import static net.logstash.logback.marker.Markers.append;

public final class OperationLogMarkers {

    private OperationLogMarkers() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static LogstashMarker restOperationCompleted(
            String eventName,
            String operation,
            String httpMethod,
            String httpPath,
            int httpStatus,
            long durationMs,
            Map<String, ?> input,
            Map<String, ?> output,
            boolean isError,
            Throwable error
    ) {
        return base(eventName, operation, durationMs, input, output, isError, error)
                .and(append("layer", "rest"))
                .and(append("httpMethod", httpMethod))
                .and(append("httpPath", httpPath))
                .and(append("httpStatus", httpStatus));
    }

    public static LogstashMarker mcpOperationCompleted(
            String eventName,
            String operation,
            long durationMs,
            Map<String, ?> input,
            Map<String, ?> output,
            boolean isError,
            Throwable error
    ) {
        return base(eventName, operation, durationMs, input, output, isError, error)
                .and(append("layer", "mcp"));
    }

    private static LogstashMarker base(
            String eventName,
            String operation,
            long durationMs,
            Map<String, ?> input,
            Map<String, ?> output,
            boolean isError,
            Throwable error
    ) {
        LogstashMarker marker = append("event", eventName)
                .and(append("timestamp", Instant.now().toString()))
                .and(append("operation", operation))
                .and(append("durationMs", durationMs))
                .and(append("outcome", isError ? "error" : "success"));

        if (input != null && !input.isEmpty()) {
            marker = marker.and(append("input", OperationLogSanitizer.sanitizeMap(input)));
        }
        if (output != null && !output.isEmpty()) {
            marker = marker.and(append("output", OperationLogSanitizer.sanitizeMap(output)));
        }
        if (error != null) {
            marker = marker.and(append("errorType", error.getClass().getName()))
                    .and(append("errorMessage", safeErrorMessage(error)));
        }
        return marker;
    }

    private static String safeErrorMessage(Throwable error) {
        String msg = error.getMessage();
        if (msg == null || msg.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return msg.length() <= 500 ? msg : msg.substring(0, 500) + "... (truncated)";
    }
}

