package com.tradinglabs.operation.logging.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.common.logging.operation.OperationLogMarkers;
import com.tradinglabs.common.logging.operation.OperationLogSanitizer;
import com.tradinglabs.operation.logging.web.autoconfigure.HttpOperationLoggingProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.logstash.logback.marker.LogstashMarker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HttpOperationLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HttpOperationLoggingFilter.class);

    private static final String TRUNCATED = "... (truncated)";
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
    };

    private final HttpOperationLoggingProperties props;
    private final ObjectMapper objectMapper;
    private final RestOperationNameResolver operationNameResolver;
    private final OperationSummaryExtractor summaryExtractor;
    private final List<RestOperationInputEnricher> inputEnrichers;
    private final String defaultEventName;

    public HttpOperationLoggingFilter(
            HttpOperationLoggingProperties props,
            ObjectMapper objectMapper,
            RestOperationNameResolver operationNameResolver,
            OperationSummaryExtractor summaryExtractor,
            List<RestOperationInputEnricher> inputEnrichers,
            String defaultEventName
    ) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.operationNameResolver = operationNameResolver;
        this.summaryExtractor = summaryExtractor;
        this.inputEnrichers = inputEnrichers == null ? List.of() : List.copyOf(inputEnrichers);
        this.defaultEventName = (defaultEventName == null || defaultEventName.isBlank())
                ? "tradinglabs.operation"
                : defaultEventName.trim();
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String normalizedPath = normalizeUri(request);
        if (!props.isEnabled() || isExcluded(normalizedPath, props.getExcludedPathPrefixes())) {
            filterChain.doFilter(request, response);
            return;
        }

        String accept = request.getHeader("Accept");
        boolean isBinaryVideoArtifactRequest = normalizedPath.contains("/api/v1/videos/")
                && (normalizedPath.contains("/file") || normalizedPath.contains("/transcription/whisper"));

        boolean isStreamingRequest = normalizedPath.endsWith("/stream")
                || isBinaryVideoArtifactRequest
                || (accept != null && accept.contains("text/event-stream"));

        long startTimeMs = System.currentTimeMillis();

        if (isStreamingRequest) {
            try {
                filterChain.doFilter(request, response);
            } finally {
                long durationMs = System.currentTimeMillis() - startTimeMs;
                logCompletion(request, response, normalizedPath, durationMs, null, null);
            }
            return;
        }

        ContentCachingRequestWrapper wrappedRequest =
                new ContentCachingRequestWrapper(request, props.getMaxBodyLength());

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        Throwable thrown = null;
        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } catch (Throwable t) {
            thrown = t;
            throw t;
        } finally {
            long durationMs = System.currentTimeMillis() - startTimeMs;
            logCompletion(wrappedRequest, wrappedResponse, normalizedPath, durationMs, thrown, wrappedResponse);
            wrappedResponse.copyBodyToResponse();
        }
    }

    private void logCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            String normalizedPath,
            long durationMs,
            Throwable thrown,
            ContentCachingResponseWrapper maybeWrappedResponse
    ) {
        int status = response.getStatus();
        boolean isError = status >= 400 || thrown != null;

        String operation = resolveOperationName(request, normalizedPath);
        Map<String, Object> input = resolveInputSummary(request, operation, normalizedPath);
        Map<String, Object> output = resolveOutputSummary(request, operation, status, maybeWrappedResponse);

        LogstashMarker marker = OperationLogMarkers.restOperationCompleted(
                resolveEventName(request),
                operation,
                request.getMethod(),
                normalizedPath,
                status,
                durationMs,
                input,
                output,
                isError,
                thrown
        );

        if (status >= 500) {
            log.error(marker, "5xx REST operation completed");
        } else if (status >= 400) {
            log.warn(marker, "4xx REST operation completed");
        }

        if (props.isDebugBodiesOnError() && isError) {
            debugBodiesOnError(request, maybeWrappedResponse);
        }
    }

    private String resolveEventName(HttpServletRequest request) {
        String configured = props.getEventName();
        if (StringUtils.hasText(configured)) {
            return configured.trim();
        }
        return defaultEventName;
    }

    private void debugBodiesOnError(HttpServletRequest request, ContentCachingResponseWrapper response) {
        if (!(request instanceof ContentCachingRequestWrapper ccrw) || response == null) {
            return;
        }
        String reqBody = extractBody(ccrw.getContentAsByteArray(), ccrw.getContentType());
        String resBody = extractBody(response.getContentAsByteArray(), response.getContentType());
        if (reqBody != null) {
            log.debug("REST body (request). method={}, path={}, body={}", request.getMethod(), normalizeUri(request), reqBody);
        }
        if (resBody != null) {
            log.debug("REST body (response). method={}, path={}, status={}, body={}",
                    request.getMethod(), normalizeUri(request), response.getStatus(), resBody);
        }
    }

    private String extractBody(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        String raw = new String(bytes, StandardCharsets.UTF_8);
        String truncated = truncate(raw, props.getMaxBodyLength());
        if (contentType != null && contentType.contains(MediaType.APPLICATION_JSON_VALUE)) {
            return sanitizeJson(truncated);
        }
        return truncated;
    }

    private String sanitizeJson(String raw) {
        try {
            Map<String, Object> map = objectMapper.readValue(raw, JSON_MAP);
            Map<String, Object> sanitized = OperationLogSanitizer.sanitizeMap(map);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sanitized);
        } catch (Exception ignore) {
            return raw;
        }
    }

    private String resolveOperationName(HttpServletRequest request, String normalizedPath) {
        Object attr = request.getAttribute(OperationContextAttributes.OPERATION);
        if (attr instanceof String s && StringUtils.hasText(s)) {
            return s.trim();
        }
        return operationNameResolver.resolve(request, normalizedPath)
                .orElse(request.getMethod() + " " + normalizedPath);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveInputSummary(HttpServletRequest request, String operation, String normalizedPath) {
        Object attr = request.getAttribute(OperationContextAttributes.INPUT);
        if (attr instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) m);
            for (RestOperationInputEnricher enricher : inputEnrichers) {
                try {
                    out = new LinkedHashMap<>(enricher.enrich(request, out));
                } catch (Exception ignore) {
                    // keep logging resilient
                }
            }
            return OperationLogSanitizer.sanitizeMap(out);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, String[]> params = request.getParameterMap();
        if (params != null && !params.isEmpty()) {
            int added = 0;
            for (Map.Entry<String, String[]> e : params.entrySet()) {
                if (added++ >= 20) {
                    out.put("paramsTruncated", true);
                    break;
                }
                String key = e.getKey();
                String[] values = e.getValue();
                if (!StringUtils.hasText(key) || values == null || values.length == 0) {
                    continue;
                }
                String v = values[0];
                if (v != null) {
                    out.put(key, v.length() <= 200 ? v : v.substring(0, 200) + TRUNCATED);
                }
            }
        }

        String contentType = request.getContentType();
        if (props.isIncludeBodySummaries()
                && contentType != null
                && contentType.contains(MediaType.APPLICATION_JSON_VALUE)
                && request instanceof ContentCachingRequestWrapper ccrw) {
            String body = extractBody(ccrw.getContentAsByteArray(), contentType);
            Map<String, Object> bodySummary = summarizeJsonBody(operation, body);
            out.putAll(bodySummary);
        }

        if (out.isEmpty()) {
            return Map.of("path", normalizedPath);
        }
        return OperationLogSanitizer.sanitizeMap(out);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveOutputSummary(
            HttpServletRequest request,
            String operation,
            int status,
            ContentCachingResponseWrapper response
    ) {
        Object attr = request.getAttribute(OperationContextAttributes.OUTPUT);
        if (attr instanceof Map<?, ?> m) {
            return OperationLogSanitizer.sanitizeMap((Map<String, Object>) m);
        }

        if (response == null) {
            return Map.of("status", status);
        }

        String contentType = response.getContentType();
        if (props.isIncludeBodySummaries()
                && contentType != null
                && contentType.contains(MediaType.APPLICATION_JSON_VALUE)) {
            String body = extractBody(response.getContentAsByteArray(), contentType);
            Map<String, Object> bodySummary = summarizeJsonBody(operation, body);
            if (!bodySummary.isEmpty()) {
                bodySummary.put("status", status);
                return OperationLogSanitizer.sanitizeMap(bodySummary);
            }
        }
        return Map.of("status", status);
    }

    private Map<String, Object> summarizeJsonBody(String operation, String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(body, JSON_MAP);
            Map<String, Object> sanitized = OperationLogSanitizer.sanitizeMap(map);
            Map<String, Object> summary = new LinkedHashMap<>();
            pick(summary, sanitized, "id");
            pick(summary, sanitized, "name");
            pick(summary, sanitized, "status");
            pick(summary, sanitized, "message");
            pick(summary, sanitized, "error");
            pick(summary, sanitized, "path");
            pick(summary, sanitized, "correlationId");
            if (summary.isEmpty()) {
                summary.put("keys", sanitized.keySet().stream().limit(30).toList());
            }
            return summary;
        } catch (Exception ignore) {
            return Map.of("body", body.length() <= 200 ? body : body.substring(0, 200) + TRUNCATED);
        }
    }

    private static void pick(Map<String, Object> out, Map<String, Object> map, String key) {
        if (map.containsKey(key)) {
            out.put(key, map.get(key));
        }
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + TRUNCATED;
    }

    static boolean isExcluded(String normalizedPath, List<String> excludedPrefixes) {
        if (!StringUtils.hasText(normalizedPath) || excludedPrefixes == null || excludedPrefixes.isEmpty()) {
            return false;
        }
        for (String prefix : excludedPrefixes) {
            if (prefix == null || prefix.isBlank()) {
                continue;
            }
            if (normalizedPath.startsWith(prefix.trim())) {
                return true;
            }
        }
        return false;
    }

    public static String normalizeUri(HttpServletRequest request) {
        if (request == null) {
            return "/";
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return "/";
        }
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && uri.startsWith(contextPath)) {
            String stripped = uri.substring(contextPath.length());
            return stripped.isEmpty() ? "/" : stripped;
        }
        return uri;
    }
}

