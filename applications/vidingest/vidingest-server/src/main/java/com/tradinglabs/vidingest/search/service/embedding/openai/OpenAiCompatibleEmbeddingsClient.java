package com.tradinglabs.vidingest.search.service.embedding.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * Minimal OpenAI-compatible embeddings client.
 *
 * Expects the OpenAI /v1/embeddings shape and returns float vectors.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenAiCompatibleEmbeddingsClient implements EmbeddingsClient {

    private static final int DEFAULT_MAX_BATCH = 64;

    private final VideoSearchConfig searchConfig;

    @Override
    public List<float[]> embed(List<String> inputs) throws IOException {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }

        VideoSearchConfig.Embeddings cfg = searchConfig.getEmbeddings();
        String baseUrl = cfg.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IOException("Missing embeddings base URL. Set vidingest.search.embeddings.base-url.");
        }

        int expectedDims = Math.max(1, cfg.getExpectedDimensions());
        String model = cfg.getModel() != null && !cfg.getModel().isBlank() ? cfg.getModel() : "text-embedding-3-small";
        Duration timeout = cfg.getTimeout() != null ? cfg.getTimeout() : Duration.ofSeconds(30);

        RestClient client = buildClient(baseUrl, timeout);

        List<float[]> out = new ArrayList<>(inputs.size());
        int i = 0;
        while (i < inputs.size()) {
            int end = Math.min(inputs.size(), i + DEFAULT_MAX_BATCH);
            List<String> batch = inputs.subList(i, end);

            EmbeddingsResponse response = postEmbeddings(client, cfg.getApiKey(), new EmbeddingsRequest(model, batch, "float", cfg.getDimensions()));
            if (response == null || response.data == null) {
                throw new IOException("Embeddings endpoint returned an empty response.");
            }
            if (response.data.size() != batch.size()) {
                throw new IOException("Embeddings endpoint returned " + response.data.size() + " vectors for " + batch.size() + " inputs.");
            }

            for (EmbeddingData d : response.data) {
                float[] vec = d != null ? d.embedding : null;
                if (vec == null) {
                    throw new IOException("Embeddings endpoint returned a null vector.");
                }
                if (vec.length != expectedDims) {
                    throw new IOException(
                            "Embedding dimension mismatch: expected " + expectedDims + " floats, got " + vec.length + ". Check vidingest.search.embeddings.model.");
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
            throw new IOException("Embeddings endpoint returned no vectors.");
        }
        return vectors.getFirst();
    }

    private RestClient buildClient(String baseUrl, Duration timeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int ms = (int) Math.min(Integer.MAX_VALUE, Math.max(1, timeout.toMillis()));
        requestFactory.setConnectTimeout(ms);
        requestFactory.setReadTimeout(ms);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    private EmbeddingsResponse postEmbeddings(RestClient client, String apiKey, EmbeddingsRequest req) throws IOException {
        try {
            RestClient.RequestBodySpec spec = client.post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON);

            if (apiKey != null && !apiKey.isBlank()) {
                spec = spec.header("Authorization", "Bearer " + apiKey.trim());
            }

            return spec
                    .body(req)
                    .retrieve()
                    .body(EmbeddingsResponse.class);
        } catch (Exception e) {
            log.warn("Embeddings request failed: {}", e.getMessage());
            throw new IOException("Embeddings request failed: " + e.getMessage(), e);
        }
    }

    /**
     * {@code dimensions} is {@code @JsonInclude(NON_NULL)} rather than always present: the field is
     * optional in the OpenAI spec and not every compatible server tolerates it, so an unset
     * {@code vidingest.search.embeddings.dimensions} must leave the body byte-identical to what it
     * was before this field existed.
     */
    public static final class EmbeddingsRequest {
        public final String model;
        public final Object input;
        public final String encoding_format;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public final Integer dimensions;

        public EmbeddingsRequest(String model, Object input, String encoding_format, Integer dimensions) {
            this.model = model;
            this.input = input;
            this.encoding_format = encoding_format;
            this.dimensions = dimensions;
        }
    }

    public static final class EmbeddingsResponse {
        public List<EmbeddingData> data;
    }

    public static final class EmbeddingData {
        public float[] embedding;
    }
}

