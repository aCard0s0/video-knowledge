package com.tradinglabs.vidingest.search.service.embedding;

import com.tradinglabs.vidingest.config.VideoSearchConfig;
import com.tradinglabs.vidingest.search.service.embedding.ollama.OllamaEmbeddingsClient;
import com.tradinglabs.vidingest.search.service.embedding.openai.OpenAiCompatibleEmbeddingsClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Picks the embeddings implementation per call from
 * {@code vidingest.search.embeddings.provider}.
 *
 * <p>Per call, not per context. The provider is editable at runtime through the connections API,
 * and a bean selected by {@code @ConditionalOnProperty} at startup cannot follow a property that
 * changes afterwards — which is why that selection, and the base-URL-present condition that
 * propped it up, were replaced by this class.
 *
 * <p>{@code disabled} is a real, supported value, not a fallback: every integration test runs with
 * it. An unrecognised value throws rather than quietly resolving to it, because silently embedding
 * nothing looks exactly like "no results" at the far end of a semantic search.
 */
@Component
@Primary
@RequiredArgsConstructor
public class EmbeddingsClientRouter implements EmbeddingsClient {

    /** The provider values this router accepts, for validation and for the console's dropdown. */
    public static final List<String> SUPPORTED_PROVIDERS =
            List.of("ollama", "openai-compatible", "disabled");

    private final VideoSearchConfig searchConfig;
    private final OllamaEmbeddingsClient ollamaEmbeddingsClient;
    private final OpenAiCompatibleEmbeddingsClient openAiCompatibleEmbeddingsClient;
    private final DisabledEmbeddingsClient disabledEmbeddingsClient;

    @Override
    public List<float[]> embed(List<String> inputs) throws IOException {
        return delegate().embed(inputs);
    }

    @Override
    public float[] embedOne(String input) throws IOException {
        return delegate().embedOne(input);
    }

    private EmbeddingsClient delegate() throws IOException {
        return switch (normalizedProvider(searchConfig)) {
            case "ollama", "" -> ollamaEmbeddingsClient;
            case "openai-compatible", "openai" -> openAiCompatibleEmbeddingsClient;
            case "disabled", "none" -> disabledEmbeddingsClient;
            default -> throw new IOException(
                    "Unsupported vidingest.search.embeddings.provider '"
                            + searchConfig.getEmbeddings().getProvider()
                            + "'; expected one of " + SUPPORTED_PROVIDERS);
        };
    }

    /** Shared with {@link QueryEmbeddingProviderRouter} so the two can never disagree. */
    static String normalizedProvider(VideoSearchConfig config) {
        String provider = config.getEmbeddings().getProvider();
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }
}
