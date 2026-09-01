package com.tradinglabs.vidingest.search.service.embedding.openai;

import com.tradinglabs.vidingest.search.service.embedding.QueryEmbeddingProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * Query embedding provider that calls an OpenAI-compatible /embeddings endpoint.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenAiCompatibleQueryEmbeddingProvider implements QueryEmbeddingProvider {

    private final OpenAiCompatibleEmbeddingsClient embeddings;

    @Override
    public Optional<float[]> embed(String query) throws IOException {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        float[] vec = embeddings.embedOne(query.trim());
        return Optional.of(vec);
    }
}

