package com.tradinglabs.vidingest.api.connections;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The effective state of one connection: what the running server would use right now, whether
 * that came from the environment or from a stored override.
 *
 * <p>{@code apiKey} is deliberately absent. A secret that goes in never comes back out — the
 * console renders {@link #hasApiKey} instead, and an update that wants to keep the existing key
 * simply omits it.
 *
 * <p>{@code overridden} distinguishes "a row in {@code vidingest_connections} is driving this"
 * from "this is what the environment configured", which is what makes the reset button
 * meaningful. {@code updatedAt} is null when it is not overridden.
 *
 * <p>{@code supportedProviders}, {@code supportsModel} and {@code supportsEnabled} are served
 * rather than mirrored client-side. Which fields a connection actually has is a property of the
 * connection — a sidecar speaks one protocol, carries no model name and has a phase toggle; an
 * embeddings runtime is the other way round — and serving it is what stops the console rendering a
 * control the server would ignore.
 */
public record ConnectionSummary(
        ConnectionName name,
        String provider,
        String baseUrl,
        String model,
        boolean hasApiKey,
        boolean enabled,
        boolean overridden,
        OffsetDateTime updatedAt,
        List<String> supportedProviders,
        boolean supportsModel,
        boolean supportsEnabled
) {
}
