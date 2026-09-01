package com.tradinglabs.vidingest.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration for the TRANSCRIBE phase's speech-to-text runtime.
 *
 * <p>Prefixed {@code vidingest.transcription} rather than {@code vidingest.whisper}: the phase is
 * no longer tied to the whisper-asr-webservice container, and a property named for one vendor
 * while pointing at another is the kind of dishonest surface this repo already ruled against for
 * the LLM settings. The provider-named classes below keep their names, because they really do
 * speak that one wire protocol.
 *
 * <p>{@link #baseUrl} and {@link #provider} are runtime-editable through
 * {@code PUT /api/v1/connections/TRANSCRIPTION}; the timeouts are not, because they are consumed
 * once when the request factory is built.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "vidingest.transcription")
public class TranscriptionClientProperties {

    /**
     * Wire-protocol selector, not a vendor name. Supported values:
     * <ul>
     *   <li>{@code whisper-asr} (default) — the whisper-asr-webservice shape,
     *       {@code POST {base-url}/asr?output=json} with an {@code audio_file} part</li>
     *   <li>{@code openai-compatible} — {@code POST {base-url}/audio/transcriptions} with
     *       {@code file} + {@code model} parts; covers oMLX, LM Studio, and the OpenAI API</li>
     * </ul>
     */
    private String provider = "whisper-asr";

    /**
     * Base URL of the transcription runtime. For {@code provider=openai-compatible} this must
     * include the API prefix the server exposes, usually {@code /v1}.
     */
    private String baseUrl = "http://localhost:9000";

    /**
     * Model name sent with the request. Required by {@code openai-compatible} servers, which have
     * no notion of a single preloaded model; ignored by whisper-asr-webservice, whose model is
     * fixed by the container's {@code ASR_MODEL}.
     */
    private String model = "whisper-1";

    /**
     * Optional bearer token, sent as {@code Authorization: Bearer <api-key>} whenever it is set.
     * Local runtimes ignore it; hosted endpoints and proxied local ones need it.
     */
    private String apiKey = "";

    /**
     * Domain-vocabulary hint sent as the OpenAI audio API's {@code prompt} part. Not an
     * instruction — it conditions the decoder, so it belongs in the same register as the speech:
     * a comma-separated list of the terms and spellings the audio is likely to contain.
     *
     * <p>Measured on a 3-minute trading short against {@code whisper-large-v3-turbo}, 3 runs per
     * arm, both fully deterministic: <b>9 wrong-form occurrences across 3 runs became 3</b>.
     * <ul>
     *   <li><em>break of structure</em> — fully fixed. "breaker structure" ×3 disappeared and the
     *       correct form went 12 → 15 occurrences.</li>
     *   <li>the garbled "where we can will close" → "where we can close" — fixed.</li>
     *   <li><em>fib retracement</em> — <b>not</b> fixed. "fiber tracement" became "fiber
     *       retracement": closer, still wrong, and still 3 occurrences. Listing the term in the
     *       hint did not make the decoder prefer the token "fib", so a shorter surface form may
     *       simply lose to a common English word. Unsolved.</li>
     * </ul>
     *
     * <p>This matters more than a transcript typo normally would, because the KNOWLEDGE phase's
     * prompt tells the model to reproduce the speaker's terminology <em>verbatim</em>
     * ({@code KnowledgeExtractionPrompt}, priority 3). A mangled domain term therefore propagates
     * by design into knowledge units, their embeddings and search — it was observed doing so.
     *
     * <p>Empty by default, and deliberately so: a whisper prompt is a documented hallucination
     * vector, since the decoder will happily continue a prompt that does not match the audio.
     * Set it per deployment to the vocabulary of the channels being ingested
     * ({@code VIDINGEST_TRANSCRIPTION_PROMPT}); do not set it to a sentence, and do not set it
     * to vocabulary the audio will not contain.
     *
     * <p>Sent only by the {@code openai-compatible} client. The whisper-asr sidecar's
     * {@code POST /asr} takes no equivalent part, so the value is ignored under that provider
     * rather than silently changing behaviour.
     */
    private String prompt = "";

    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * Transcription can take a while for long inputs.
     */
    private Duration readTimeout = Duration.ofMinutes(30);
}
