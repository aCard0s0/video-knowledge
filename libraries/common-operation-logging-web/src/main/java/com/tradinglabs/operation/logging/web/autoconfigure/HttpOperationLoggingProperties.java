package com.tradinglabs.operation.logging.web.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "tradinglabs.http.logging")
public class HttpOperationLoggingProperties {

    private boolean enabled = false;

    private String eventName = "";

    /**
     * Whether to include sanitized JSON body summaries in the structured marker payload.
     *
     * <p>This does not affect {@link #debugBodiesOnError}, which logs raw (sanitized) bodies
     * at DEBUG level for error responses.
     */
    private boolean includeBodySummaries = true;

    /**
     * Whether to enable the controller AOP aspect that enriches the operation context with
     * summarized controller inputs/outputs.
     */
    private boolean includeControllerContext = true;

    private boolean debugBodiesOnError = false;

    private int maxBodyLength = 10_000;

    /**
     * Requests to these path prefixes are not logged.
     *
     * <p>These paths are matched after stripping the servlet context-path.
     */
    private List<String> excludedPathPrefixes = List.of(
            "/actuator",
            "/swagger-ui",
            "/v3/api-docs",
            "/swagger-ui.html",
            "/api-docs",
            "/favicon.ico"
    );

    /**
     * Order offset added to {@code Ordered.HIGHEST_PRECEDENCE}.
     *
     * <p>Default matches the existing per-app filters: {@code HIGHEST_PRECEDENCE + 20}.
     */
    private int filterOrderOffset = 20;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEventName() {
        return eventName;
    }

    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    public boolean isIncludeBodySummaries() {
        return includeBodySummaries;
    }

    public void setIncludeBodySummaries(boolean includeBodySummaries) {
        this.includeBodySummaries = includeBodySummaries;
    }

    public boolean isIncludeControllerContext() {
        return includeControllerContext;
    }

    public void setIncludeControllerContext(boolean includeControllerContext) {
        this.includeControllerContext = includeControllerContext;
    }

    public boolean isDebugBodiesOnError() {
        return debugBodiesOnError;
    }

    public void setDebugBodiesOnError(boolean debugBodiesOnError) {
        this.debugBodiesOnError = debugBodiesOnError;
    }

    public int getMaxBodyLength() {
        return maxBodyLength;
    }

    public void setMaxBodyLength(int maxBodyLength) {
        this.maxBodyLength = maxBodyLength;
    }

    public List<String> getExcludedPathPrefixes() {
        return excludedPathPrefixes;
    }

    public void setExcludedPathPrefixes(List<String> excludedPathPrefixes) {
        this.excludedPathPrefixes = excludedPathPrefixes;
    }

    public int getFilterOrderOffset() {
        return filterOrderOffset;
    }

    public void setFilterOrderOffset(int filterOrderOffset) {
        this.filterOrderOffset = filterOrderOffset;
    }
}

