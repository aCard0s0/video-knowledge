package com.tradinglabs.vidingest.search.service.embedding;

import com.tradinglabs.vidingest.config.VideoSearchConfig;
import com.tradinglabs.vidingest.search.service.embedding.ollama.OllamaQueryEmbeddingProvider;
import com.tradinglabs.vidingest.search.service.embedding.openai.OpenAiCompatibleQueryEmbeddingProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * The query-side twin of {@link EmbeddingsClientRouter}, resolving the same property so a
 * provider switch moves ingestion and search together. See that class for why the choice is made
 * per call.
 */
@Component
@Primary
@RequiredArgsConstructor
public class QueryEmbeddingProviderRouter implements QueryEmbeddingProvider {

    private final VideoSearchConfig searchConfig;
    private final OllamaQueryEmbeddingProvider ollamaQueryEmbeddingProvider;
    private final OpenAiCompatibleQueryEmbeddingProvider openAiCompatibleQueryEmbeddingProvider;
    private final DisabledQueryEmbeddingProvider disabledQueryEmbeddingProvider;

    @Override
    public Optional<float[]> embed(String query) throws IOException {
        return delegate().embed(query);
    }

    private QueryEmbeddingProvider delegate() throws IOException {
        return switch (EmbeddingsClientRouter.normalizedProvider(searchConfig)) {
            case "ollama", "" -> ollamaQueryEmbeddingProvider;
            case "openai-compatible", "openai" -> openAiCompatibleQueryEmbeddingProvider;
            case "disabled", "none" -> disabledQueryEmbeddingProvider;
            default -> throw new IOException(
                    "Unsupported vidingest.search.embeddings.provider '"
                            + searchConfig.getEmbeddings().getProvider()
                            + "'; expected one of " + EmbeddingsClientRouter.SUPPORTED_PROVIDERS);
        };
    }
}
