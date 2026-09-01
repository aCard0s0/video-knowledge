package com.tradinglabs.vidingest.api.connections;

/**
 * Result of a one-shot reachability probe against a connection's currently effective settings.
 *
 * <p>Deliberately not an error response: an unreachable dependency is a successful answer to
 * "is it reachable?", so this comes back 200 with {@code reachable = false} and the reason in
 * {@code error}. {@code detail} carries whatever the probe learned when it did answer — the
 * model list for an LLM, the health body for a sidecar — truncated to something loggable.
 */
public record ConnectionTestResult(
        ConnectionName name,
        boolean reachable,
        String baseUrl,
        String probedUrl,
        String detail,
        String error,
        long latencyMs
) {
}
