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
 * in the pipeline — operators opt in once the runtime is wired up and has the configured chat
 * model available. {@code provider}, {@code base-url}, {@code chat-model}, {@code api-key} and
 * {@code enabled} are all editable at runtime via {@code PUT /api/v1/connections/KNOWLEDGE};
 * the timeouts are not, because the request factory consumes them once.
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
     * Read per call by {@code KnowledgeChatClientRouter}, because this value is editable at
     * runtime through {@code PUT /api/v1/connections/KNOWLEDGE}. An unrecognised value therefore
     * fails the KNOWLEDGE phase rather than the context at startup — the API validates it against
     * the router's supported set on the way in, so the typo is still caught before any run.
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
     *
     * <p>Raised from 4096 with prompt v3, which asks for full coverage of every rule in a batch
     * and so writes considerably more per segment than v2's "prefer fewer units" did. That makes a
     * long reply normal rather than exceptional, and an overrun truncates the model's JSON
     * mid-object.
     *
     * <p>Such an overrun used to be <em>silent</em>: {@code KnowledgeUnitJson.parseUnitsArray}
     * answered unparseable content with an empty list, so the batch was recorded as a success that
     * extracted nothing. It now throws, and the message names this property and carries the content
     * length, so a too-low cap is diagnosable from the log line rather than by guessing. A cap set
     * too low therefore costs a failed run rather than coverage — the right way round.
     */
    private int maxOutputTokens = 8192;

    /**
     * Per-batch input cap (characters). Roughly 4 chars ≈ 1 token, so 16k chars ≈ 4k token
     * input budget. Segments are appended until adding another would exceed this, then a
     * new batch starts. Independent batches share no LLM context.
     */
    private int maxInputCharsPerBatch = 16_000;

    /**
     * Knowledge unit types the LLM should produce. An empty list = "extract all supported types".
     *
     * <p>Order here is documentary, and deliberately so. It sets only the order of the
     * allowed-types list in the prompt, which was measured to make no difference (11.3 vs 11.0
     * rules recovered with PROCEDURE moved to the front, 3 runs each). What does matter is the
     * order of the prompt's type <em>glossary</em>, and that is pinned by
     * {@code KnowledgeExtractionPrompt.GLOSSARY_ORDER} rather than read from here — so an operator
     * reordering this list cannot degrade extraction as a side effect.
     *
     * <p>QUESTION is omitted rather than unsupported: an open question the video never answers is
     * useful for follow-up prompting but is not knowledge the video delivered, and on monetised
     * content the model reliably mines the call-to-action for one. Add it per deployment if the
     * follow-up value is wanted.
     */
    private List<KnowledgeUnitType> types = List.of(
            KnowledgeUnitType.ENTITY,
            KnowledgeUnitType.TOPIC,
            KnowledgeUnitType.SUMMARY,
            KnowledgeUnitType.CLAIM,
            KnowledgeUnitType.PROCEDURE
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
