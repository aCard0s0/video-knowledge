package com.tradinglabs.web;

import org.slf4j.MDC;

/**
 * Standard correlation identifiers for HTTP request tracing across apps.
 */
public final class CorrelationIds {

    public static final String HEADER = "X-Correlation-Id";
    public static final String LEGACY_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationIds() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }
}

