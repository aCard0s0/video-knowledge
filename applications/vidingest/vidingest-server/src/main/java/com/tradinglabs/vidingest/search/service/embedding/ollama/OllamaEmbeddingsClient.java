package com.tradinglabs.vidingest.search.service.embedding.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.config.VideoSearchConfig;
import com.tradinglabs.vidingest.search.service.embedding.EmbeddingsClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Ollama embeddings client.
 *
 * Uses POST /api/embed (preferred Ollama embedding endpoint).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OllamaEmbeddingsClient implements EmbeddingsClient {

    private static final int DEFAULT_MAX_BATCH = 64;
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final VideoSearchConfig searchConfig;
    private final ObjectMapper objectMapper;

    @Override
    public List<float[]> embed(List<String> inputs) throws IOException {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }

        VideoSearchConfig.Embeddings cfg = searchConfig.getEmbeddings();
        VideoSearchConfig.Embeddings.Ollama ollama = cfg.getOllama();

        String baseUrl = ollama != null ? ollama.getBaseUrl() : null;
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IOException("Missing Ollama base URL. Set vidingest.search.embeddings.ollama.base-url.");
        }

        String model = ollama != null && ollama.getEmbedModel() != null && !ollama.getEmbedModel().isBlank()
                ? ollama.getEmbedModel()
                : "rjmalagon/gte-qwen2-1.5b-instruct-embed-f16";

        boolean truncate = ollama == null || ollama.isTruncate();
        int expectedDims = Math.max(1, cfg.getExpectedDimensions());
        Duration readTimeout = cfg.getTimeout() != null ? cfg.getTimeout() : DEFAULT_READ_TIMEOUT;

        RestClient client = buildClient(baseUrl, readTimeout);

        List<float[]> out = new ArrayList<>(inputs.size());
        int i = 0;
        while (i < inputs.size()) {
            int end = Math.min(inputs.size(), i + DEFAULT_MAX_BATCH);
            List<String> batch = inputs.subList(i, end);

            EmbedResponse response = postEmbed(client, new EmbedRequest(model, batch, truncate));
            if (response == null || response.embeddings == null) {
                throw new IOException("Ollama /api/embed returned an empty response.");
            }
            if (response.embeddings.size() != batch.size()) {
                throw new IOException("Ollama /api/embed returned " + response.embeddings.size() + " vectors for " + batch.size() + " inputs.");
            }

            for (List<Double> vecDoubles : response.embeddings) {
                if (vecDoubles == null) {
                    throw new IOException("Ollama /api/embed returned a null vector.");
                }
                float[] vec = toFloatArray(vecDoubles);
                if (vec.length != expectedDims) {
                    throw new IOException("Embedding dimension mismatch: expected " + expectedDims + " floats, got " + vec.length + ". Check vidingest.search.embeddings.expected-dimensions and model.");
                }
                out.add(vec);
            }

            i = end;
        }

        return out;
    }

    @Override
    public float[] embedOne(String input) throws IOException {
        List<float[]> vectors = embed(List.of(input != null ? input : ""));
        if (vectors.isEmpty()) {
            throw new IOException("Ollama /api/embed returned no vectors.");
        }
        return vectors.getFirst();
    }

    private RestClient buildClient(String baseUrl, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int connectMs = (int) Math.min(Integer.MAX_VALUE, Math.max(1, CONNECT_TIMEOUT.toMillis()));
        int readMs = (int) Math.min(Integer.MAX_VALUE, Math.max(1, readTimeout.toMillis()));
        requestFactory.setConnectTimeout(connectMs);
        requestFactory.setReadTimeout(readMs);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Some Ollama versions / reverse proxies return {@code Content-Type: application/octet-stream}
     * instead of {@code application/json} for the embed endpoint, which makes Spring's
     * {@code RestClient.body(EmbedResponse.class)} fail with a converter mismatch error.
     * Read the body as raw bytes/String and decode via Jackson directly — content-type agnostic.
     */
    private EmbedResponse postEmbed(RestClient client, EmbedRequest req) throws IOException {
        String raw;
        try {
            raw = client.post()
                    .uri("/api/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(req)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.warn("Ollama embeddings request failed: {}", e.getMessage());
            throw new IOException("Ollama embeddings request failed: " + e.getMessage(), e);
        }
        if (raw == null || raw.isBlank()) {
            throw new IOException("Ollama /api/embed returned an empty body.");
        }
        try {
            return objectMapper.readValue(raw, EmbedResponse.class);
        } catch (Exception e) {
            throw new IOException("Failed to parse Ollama /api/embed response as JSON: " + e.getMessage(), e);
        }
    }

    private static float[] toFloatArray(List<Double> vec) {
        float[] out = new float[vec.size()];
        for (int i = 0; i < vec.size(); i++) {
            Double v = vec.get(i);
            out[i] = v != null ? v.floatValue() : 0.0f;
        }
        return out;
    }

    public static final class EmbedRequest {
        public final String model;
        public final Object input;
        public final boolean truncate;

        public EmbedRequest(String model, Object input, boolean truncate) {
            this.model = model;
            this.input = input;
            this.truncate = truncate;
        }
    }

    /**
     * Ollama returns the response with bookkeeping fields ({@code total_duration},
     * {@code load_duration}, {@code prompt_eval_count}, ...) alongside the embeddings.
     * {@code @JsonIgnoreProperties(ignoreUnknown = true)} stops Jackson from blowing up
     * on those — we only care about {@code model} + {@code embeddings} here.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class EmbedResponse {
        public String model;
        public List<List<Double>> embeddings;
    }
}

