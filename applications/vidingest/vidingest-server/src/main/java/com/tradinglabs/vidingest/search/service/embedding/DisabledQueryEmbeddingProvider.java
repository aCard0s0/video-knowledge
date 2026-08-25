package com.tradinglabs.vidingest.search.service.embedding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Default placeholder provider until a real embedding model is wired.
 */
@Component
@Slf4j
public class DisabledQueryEmbeddingProvider implements QueryEmbeddingProvider {

    @Override
    public Optional<float[]> embed(String query) {
        log.debug("No query embedding provider configured for query: {}", query);
        return Optional.empty();
    }
}

