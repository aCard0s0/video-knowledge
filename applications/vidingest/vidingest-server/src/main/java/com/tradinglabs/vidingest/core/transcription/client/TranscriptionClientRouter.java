package com.tradinglabs.vidingest.core.transcription.client;

import com.tradinglabs.vidingest.config.TranscriptionClientProperties;
import com.tradinglabs.vidingest.core.transcription.client.openai.OpenAiAudioTranscriptionClient;
import com.tradinglabs.vidingest.core.transcription.client.whisper.WhisperAsrClient;
import com.tradinglabs.vidingest.core.transcription.dto.WhisperAsrResult;
import com.tradinglabs.vidingest.core.transcription.service.TranscriptionFailureException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Picks the transcription implementation per call from {@code vidingest.transcription.provider}.
 *
 * <p>Per call, not per context: the provider is editable at runtime through the connections API,
 * so the {@code @ConditionalOnProperty} selection this replaced could not answer it — a bean
 * chosen at startup cannot change when the property does. The cost is that an unrecognised value
 * now fails the phase rather than the context; the API validates it on the way in so the typo is
 * still rejected before anything runs.
 */
@Component
@Primary
@RequiredArgsConstructor
public class TranscriptionClientRouter implements TranscriptionClient {

    /**
     * The provider values this router accepts, for validation and for the console's dropdown.
     *
     * <p>{@code openai} reaches the same client as {@code openai-compatible} and sends the same
     * multipart. It is a separate value so the settings screen can name the hosted case — and
     * because only {@code whisper-1} honours {@code response_format=verbose_json} there, which is
     * the only response shape this pipeline can use.
     */
    public static final java.util.List<String> SUPPORTED_PROVIDERS =
            java.util.List.of("whisper-asr", "openai-compatible", "openai");

    private final TranscriptionClientProperties properties;
    private final WhisperAsrClient whisperAsrClient;
    private final OpenAiAudioTranscriptionClient openAiAudioTranscriptionClient;

    @Override
    public WhisperAsrResult transcribeToJson(Path audioFile) {
        return delegate().transcribeToJson(audioFile);
    }

    private TranscriptionClient delegate() {
        String provider = properties.getProvider();
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "whisper-asr", "whisper", "" -> whisperAsrClient;
            case "openai-compatible", "openai" -> openAiAudioTranscriptionClient;
            default -> throw new TranscriptionFailureException(
                    "Unsupported vidingest.transcription.provider '" + provider
                            + "'; expected one of " + SUPPORTED_PROVIDERS);
        };
    }
}
