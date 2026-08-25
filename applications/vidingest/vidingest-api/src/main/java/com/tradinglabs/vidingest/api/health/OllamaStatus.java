package com.tradinglabs.vidingest.api.health;

import java.util.List;

/**
 * Result of a probe against the configured Ollama server.
 *
 * <p>{@code reachable} reflects whether {@code GET /api/tags} responded successfully.
 * When unreachable, {@code error} contains a human-readable summary and the model
 * lists are empty.
 */
public record OllamaStatus(
        boolean reachable,
        String baseUrl,
        String embedModel,
        List<OllamaModel> runningModels,
        List<OllamaModel> installedModels,
        String error
) {
    public record OllamaModel(String name, String digest, Long sizeBytes, String expiresAt) {
    }
}
