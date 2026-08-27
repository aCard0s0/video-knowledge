package com.tradinglabs.vidingest.core.knowledge.client;

import com.tradinglabs.vidingest.core.knowledge.dto.KnowledgeUnitDraft;

import java.util.List;

/**
 * Strategy interface for the LLM call that turns multimodal-segment text into typed
 * {@link KnowledgeUnitDraft} records.
 *
 * <p>Two impls, picked by {@code vidingest.knowledge.provider}:
 * {@code OllamaKnowledgeChatClient} (Ollama's native {@code /api/chat}, the default) and
 * {@code OpenAiCompatibleKnowledgeChatClient} ({@code /chat/completions}, which is what LM Studio,
 * llama.cpp, mlx-lm, vLLM and hosted APIs all serve). They differ only in the request body and
 * the response envelope — the schema and the unit parsing are shared in {@code KnowledgeUnitJson},
 * so neither can drift into recovering a malformed response the other cannot.
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
