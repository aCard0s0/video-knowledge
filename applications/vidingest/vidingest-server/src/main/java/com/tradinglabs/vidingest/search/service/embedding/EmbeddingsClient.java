package com.tradinglabs.vidingest.search.service.embedding;

import java.io.IOException;
import java.util.List;

/**
 * Strategy interface used to convert text inputs into embeddings.
 */
public interface EmbeddingsClient {

    List<float[]> embed(List<String> inputs) throws IOException;

    float[] embedOne(String input) throws IOException;
}

