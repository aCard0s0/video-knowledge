package com.tradinglabs.vidingest.config;

import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for the LLM-driven knowledge-extraction phase (M6).
 *
 * <p>{@code KnowledgeExtractionService} reads {@code vidingest_multimodal_segments} (M5),
 * batches them under {@link #maxInputCharsPerBatch}, prompts a chat LLM (Ollama by default)
 * to emit a JSON array of typed knowledge units, embeds each unit's content via the
 * existing {@code EmbeddingsClient}, and persists with the ivfflat index for fast semantic
 * search (M8).
 *
 * <p>Provider is selected by {@link #provider}: {@code ollama} speaks Ollama's own
 * {@code /api/chat}, {@code openai-compatible} speaks {@code /chat/completions} and therefore
 * covers LM Studio, {@code llama-server}, mlx-lm, vLLM and hosted APIs alike. Both read the same
 * {@code base-url} / {@code chat-model} / {@code temperature} settings below, so switching runtime
 * is a property change and nothing else.
 *
 * <p>Defaults to {@code enabled = false} because the LLM call is the most expensive thing
 * in the pipeline — operators opt in once Ollama (or a cloud provider) is wired up and
 * has the configured chat model available.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "vidingest.knowledge")
public class KnowledgeExtractionConfig {

    /**
     * Master switch. When false the {@code KnowledgePhase} short-circuits regardless of the
     * run's own {@code skipPhases} opt-out.
     */
    private boolean enabled = false;

    /**
     * Wire-protocol selector, not a vendor name. Supported values:
     * <ul>
     *   <li>{@code ollama} (default) — Ollama's native {@code POST /api/chat}</li>
     *   <li>{@code openai-compatible} — {@code POST {base-url}/chat/completions}; point
     *       {@code base-url} at any server speaking that format, including a remote host</li>
     * </ul>
     * An unrecognised value leaves no {@code KnowledgeChatClient} bean and fails the context at
     * startup, which beats discovering the typo ten minutes into a run.
     */
    private String provider = "ollama";

    /**
     * Chat-model name passed to the provider. Default targets a model with strong
     * instruction following + JSON-mode support; bump for higher-quality extraction on
     * better hardware.
     */
    private String chatModel = "qwen2.5:14b-instruct";

    /**
     * Base URL of the model runtime. The default is the local Ollama daemon, which the
     * embeddings client also talks to — same daemon, different model. For
     * {@code provider=openai-compatible} this must include the API prefix the server exposes,
     * usually {@code /v1} (LM Studio: {@code http://localhost:1234/v1}).
     */
    private String baseUrl = "http://localhost:11434";

    /**
     * Optional bearer token, sent as {@code Authorization: Bearer <api-key>} by the
     * OpenAI-compatible client. Local runtimes ignore it; hosted endpoints need it. Unused by the
     * Ollama client, which has no auth.
     */
    private String apiKey = "";

    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * Large LLM calls are slow on CPU (tens of seconds to minutes for long inputs); use a
     * generous default and tune downward on GPU hosts.
     */
    private Duration readTimeout = Duration.ofMinutes(10);

    /**
     * Sampling temperature. Low values (0.0–0.3) yield more deterministic structured
     * output — essential when we mandate strict JSON.
     */
    private double temperature = 0.2;

    /**
     * Max tokens to generate per LLM call. Caps the output budget so a single rogue
     * response can't blow through the read timeout.
     */
    private int maxOutputTokens = 4096;

    /**
     * Per-batch input cap (characters). Roughly 4 chars ≈ 1 token, so 16k chars ≈ 4k token
     * input budget. Segments are appended until adding another would exceed this, then a
     * new batch starts. Independent batches share no LLM context.
     */
    private int maxInputCharsPerBatch = 16_000;

    /**
     * Knowledge unit types the LLM should produce. Order is documentary only; output rows
     * are not sorted on this. An empty list = "extract all supported types".
     */
    private List<KnowledgeUnitType> types = List.of(
            KnowledgeUnitType.ENTITY,
            KnowledgeUnitType.TOPIC,
            KnowledgeUnitType.SUMMARY,
            KnowledgeUnitType.CLAIM
    );

    /**
     * Hard cap on persisted rows per video. Truncates the tail of the accumulated drafts
     * across all batches once exceeded — defensive against a chatty model.
     */
    private int maxUnitsPerVideo = 300;

    /**
     * When true, every persisted {@code KnowledgeUnit.content} is embedded via the existing
     * {@code EmbeddingsClient} so the M8 {@code searchKnowledge} MCP tool can do pgvector
     * lookups. Disable to skip the embed cost (e.g. when running offline against an LLM
     * but no embedding model).
     */
    private boolean embedContent = true;

    /**
     * Minimum salience score (0–1) the LLM-emitted unit must meet to be persisted. Filters
     * out junky low-confidence "facts" before they pollute the table.
     */
    private double minSalience = 0.2;
}
