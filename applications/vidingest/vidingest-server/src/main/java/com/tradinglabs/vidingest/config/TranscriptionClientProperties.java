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

    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * Transcription can take a while for long inputs.
     */
    private Duration readTimeout = Duration.ofMinutes(30);
}
