package com.tradinglabs.vidingest.core.transcription.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.config.TranscriptionClientProperties;
import com.tradinglabs.vidingest.core.transcription.client.openai.OpenAiAudioTranscriptionClient;
import com.tradinglabs.vidingest.core.transcription.client.whisper.WhisperAsrClient;
import com.tradinglabs.vidingest.core.transcription.dto.WhisperAsrResult;
import com.tradinglabs.vidingest.core.transcription.service.TranscriptionFailureException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The router reads the provider on every call, which is the whole point: a value edited through
 * the connections API has to take effect without recreating any bean.
 */
class TranscriptionClientRouterTest extends TranscriptionClientTestBase {

    private static final String WHISPER_BODY = """
            {"text": "from whisper", "language": "en", "segments": []}
            """;

    @Test
    void followsTheProviderPropertyBetweenCalls() throws Exception {
        // One server, both paths — so the only thing deciding which client answers is the provider.
        startServer("/asr", 200, WHISPER_BODY);
        server().createContext("/audio/transcriptions", exchange -> respond(exchange,
                """
                {"text": "from openai", "language": "en", "segments": []}
                """));

        TranscriptionClientProperties properties = properties("whisper-asr");
        TranscriptionClientRouter router = new TranscriptionClientRouter(
                properties,
                new WhisperAsrClient(new ObjectMapper(), properties, restClient()),
                new OpenAiAudioTranscriptionClient(new ObjectMapper(), properties, restClient()));

        WhisperAsrResult first = router.transcribeToJson(tempWav());
        assertThat(first.text()).isEqualTo("from whisper");

        properties.setProvider("openai-compatible");

        WhisperAsrResult second = router.transcribeToJson(tempWav());
        assertThat(second.text()).isEqualTo("from openai");
    }

    @Test
    void rejectsAnUnknownProvider() throws Exception {
        startServer("/asr", 200, WHISPER_BODY);

        TranscriptionClientProperties properties = properties("wishper");
        TranscriptionClientRouter router = new TranscriptionClientRouter(
                properties,
                new WhisperAsrClient(new ObjectMapper(), properties, restClient()),
                new OpenAiAudioTranscriptionClient(new ObjectMapper(), properties, restClient()));

        assertThatThrownBy(() -> router.transcribeToJson(tempWav()))
                .isInstanceOf(TranscriptionFailureException.class)
                .hasMessageContaining("Unsupported vidingest.transcription.provider 'wishper'");
    }
}
