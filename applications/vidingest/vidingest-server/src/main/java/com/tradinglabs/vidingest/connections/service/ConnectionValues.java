package com.tradinglabs.vidingest.connections.service;

/**
 * The five settings every connection has, in a shape that does not care which
 * {@code @ConfigurationProperties} bean they came from.
 *
 * <p>This is what lets {@code ConnectionSettingsService} treat an LLM and an OCR sidecar the same
 * way: each connection contributes one reader and one writer over this record, and the awkward
 * parts — the embeddings config keeping the Ollama base URL in a nested block, the two connections
 * with no {@code enabled} flag of their own — stay inside those two lambdas instead of leaking
 * into every operation.
 */
public record ConnectionValues(
        String provider,
        String baseUrl,
        String model,
        String apiKey,
        boolean enabled
) {
}
