package com.tradinglabs.vidingest.core.knowledge.client;

import com.tradinglabs.vidingest.core.knowledge.dto.KnowledgeUnitDraft;

import java.util.List;

/**
 * Strategy interface for the LLM call that turns multimodal-segment text into typed
 * {@link KnowledgeUnitDraft} records.
 *
 * <p>One impl exists today ({@code OllamaKnowledgeChatClient}); the design leaves room for
 * an OpenAI / Anthropic impl behind {@code vidingest.knowledge.provider=openai-compatible}
 * without touching {@code KnowledgeExtractionService}.
 */
public interface KnowledgeChatClient {

    /**
     * Run one LLM call. Returns the parsed drafts in the order the model emitted them.
     * Implementations MUST NOT throw for "no units in this batch" — that's a valid result
     * (empty list). Throw only for transport / parse failures.
     *
     * @param systemPrompt fixed system message — typically the output schema and rules
     * @param userPrompt   batch content (segments) the model should reason over
     * @return list of drafts, possibly empty; never null
     */
    List<KnowledgeUnitDraft> extract(String systemPrompt, String userPrompt);
}
