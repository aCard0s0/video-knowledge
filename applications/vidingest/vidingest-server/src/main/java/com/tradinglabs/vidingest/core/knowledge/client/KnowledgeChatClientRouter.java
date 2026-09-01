package com.tradinglabs.vidingest.core.knowledge.client;

import com.tradinglabs.vidingest.config.KnowledgeExtractionConfig;
import com.tradinglabs.vidingest.core.knowledge.client.ollama.OllamaKnowledgeChatClient;
import com.tradinglabs.vidingest.core.knowledge.client.openai.OpenAiCompatibleKnowledgeChatClient;
import com.tradinglabs.vidingest.core.knowledge.dto.KnowledgeUnitDraft;
import com.tradinglabs.vidingest.core.knowledge.service.KnowledgeExtractionFailureException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Picks the chat implementation per call from {@code vidingest.knowledge.provider}.
 *
 * <p>Per call, not per context: the provider is editable at runtime through the connections API,
 * which the {@code @ConditionalOnProperty} selection this replaced could not follow. The
 * trade-off is that a bad value now fails the KNOWLEDGE phase instead of the context at startup —
 * {@code PUT /api/v1/connections/KNOWLEDGE} validates against {@link #SUPPORTED_PROVIDERS} so a
 * typo is still rejected before any run reaches this.
 */
@Component
@Primary
@RequiredArgsConstructor
public class KnowledgeChatClientRouter implements KnowledgeChatClient {

    /** The provider values this router accepts, for validation and for the console's dropdown. */
    public static final List<String> SUPPORTED_PROVIDERS = List.of("ollama", "openai-compatible");

    private final KnowledgeExtractionConfig config;
    private final OllamaKnowledgeChatClient ollamaKnowledgeChatClient;
    private final OpenAiCompatibleKnowledgeChatClient openAiCompatibleKnowledgeChatClient;

    @Override
    public List<KnowledgeUnitDraft> extract(String systemPrompt, String userPrompt) {
        return delegate().extract(systemPrompt, userPrompt);
    }

    private KnowledgeChatClient delegate() {
        String provider = config.getProvider();
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "ollama", "" -> ollamaKnowledgeChatClient;
            case "openai-compatible", "openai" -> openAiCompatibleKnowledgeChatClient;
            default -> throw new KnowledgeExtractionFailureException(
                    "Unsupported vidingest.knowledge.provider '" + provider
                            + "'; expected one of " + SUPPORTED_PROVIDERS);
        };
    }
}
