package com.tradinglabs.vidingest.api.health;

import java.util.List;

/**
 * Result of a probe against the configured model runtime — Ollama, LM Studio,
 * {@code llama-server}, mlx-lm, vLLM, or a remote host serving any of those APIs.
 *
 * <p>{@code reachable} reflects whether the runtime answered its model-listing call. When
 * unreachable, {@code error} contains a human-readable summary and the model lists are empty.
 *
 * <p>{@code runningModels} is populated only for {@code provider=ollama}: {@code GET /api/ps} has
 * no OpenAI-compatible analogue, so for every other runtime the list is empty and says nothing
 * about what is loaded. {@code installedModels} is portable, but only {@code name} survives the
 * OpenAI shape — {@code digest} and {@code sizeBytes} come back null there.
 */
public record LlmStatus(
        boolean reachable,
        String provider,
        String baseUrl,
        String embedModel,
        List<LlmModel> runningModels,
        List<LlmModel> installedModels,
        String error
) {
    public record LlmModel(String name, String digest, Long sizeBytes, String expiresAt) {
    }
}
