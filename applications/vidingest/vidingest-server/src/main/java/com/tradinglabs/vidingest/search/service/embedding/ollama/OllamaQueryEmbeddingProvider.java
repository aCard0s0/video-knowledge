package com.tradinglabs.vidingest.search.service.embedding.ollama;

import com.tradinglabs.vidingest.search.service.embedding.QueryEmbeddingProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

@Component
@Primary
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "vidingest.search.embeddings", name = "provider", havingValue = "ollama")
public class OllamaQueryEmbeddingProvider implements QueryEmbeddingProvider {

    private final OllamaEmbeddingsClient embeddings;

    @Override
    public Optional<float[]> embed(String query) throws IOException {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        float[] vec = embeddings.embedOne(query.trim());
        return Optional.of(vec);
    }
}

