package com.tradinglabs.vidingest.search.service.embedding;

import java.io.IOException;
import java.util.Optional;

/**
 * Strategy interface used to convert user queries into embeddings.
 */
public interface QueryEmbeddingProvider {

    Optional<float[]> embed(String query) throws IOException;
}

