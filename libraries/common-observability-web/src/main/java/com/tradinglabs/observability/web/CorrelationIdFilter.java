package com.tradinglabs.observability.web;

import com.tradinglabs.web.CorrelationIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

public final class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // Keep MDC correlation IDs across async dispatches.
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String previous = MDC.get(CorrelationIds.MDC_KEY);
        String correlationId = resolveCorrelationId(request);

        // Persist across async dispatches; OncePerRequestFilter clears MDC after initial dispatch.
        request.setAttribute(CorrelationIds.MDC_KEY, correlationId);

        MDC.put(CorrelationIds.MDC_KEY, correlationId);
        response.setHeader(CorrelationIds.HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(CorrelationIds.MDC_KEY);
            } else {
                MDC.put(CorrelationIds.MDC_KEY, previous);
            }
        }
    }

    private static String resolveCorrelationId(HttpServletRequest request) {
        Object attr = request.getAttribute(CorrelationIds.MDC_KEY);
        if (attr instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        String v = firstNonBlank(
                request.getHeader(CorrelationIds.HEADER),
                request.getHeader(CorrelationIds.LEGACY_HEADER)
        );
        return v != null ? v : UUID.randomUUID().toString();
    }

    private static String firstNonBlank(String a, String b) {
        String x = normalize(a);
        if (x != null) return x;
        return normalize(b);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}

