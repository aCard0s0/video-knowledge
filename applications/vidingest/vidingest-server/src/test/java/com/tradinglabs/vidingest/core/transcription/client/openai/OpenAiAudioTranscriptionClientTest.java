package com.tradinglabs.vidingest.core.transcription.client.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.config.TranscriptionClientProperties;
import com.tradinglabs.vidingest.core.transcription.client.TranscriptionClientTestBase;
import com.tradinglabs.vidingest.core.transcription.dto.WhisperAsrResult;
import com.tradinglabs.vidingest.core.transcription.dto.WhisperSegment;
import com.tradinglabs.vidingest.core.transcription.service.TranscriptionFailureException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiAudioTranscriptionClientTest extends TranscriptionClientTestBase {

    /** Verbatim shape of oMLX's {@code AudioTranscriptionResponse} under verbose_json. */
    private static final String VERBOSE_JSON = """
            {
              "text": "hello world",
              "language": "en",
              "duration": 2.0,
              "segments": [
                { "id": 0, "seek": 0, "start": 0.0, "end": 1.0, "text": "hello" },
                { "id": 1, "seek": 0, "start": 1.0, "end": 2.0, "text": "world" }
              ]
            }
            """;

    private OpenAiAudioTranscriptionClient client(TranscriptionClientProperties properties) {
        return new OpenAiAudioTranscriptionClient(new ObjectMapper(), properties, restClient());
    }

    @Test
    void parsesVerboseJsonIntoTheSameResultShapeAsWhisper() throws Exception {
        startServer("/audio/transcriptions", 200, VERBOSE_JSON);

        WhisperAsrResult result = client(properties("openai-compatible")).transcribeToJson(tempWav());

        assertThat(result.language()).isEqualTo("en");
        assertThat(result.text()).isEqualTo("hello world");
        assertThat(result.segments()).extracting(WhisperSegment::text).containsExactly("hello", "world");
        assertThat(result.segments()).extracting(WhisperSegment::startSeconds).containsExactly(0.0f, 1.0f);
    }

    @Test
    void sendsTheOpenAiPartNamesAndForcesVerboseJson() throws Exception {
        startServer("/audio/transcriptions", 200, VERBOSE_JSON);

        TranscriptionClientProperties properties = properties("openai-compatible");
        properties.setModel("whisper-large-v3");
        client(properties).transcribeToJson(tempWav());

        String body = lastRequestBody.get();
        assertThat(body).contains("name=\"file\"");
        assertThat(body).contains("name=\"model\"").contains("whisper-large-v3");
        // Not cosmetic: the default response_format=json carries no segments at all, and the phase
        // persists one row per segment.
        assertThat(body).contains("name=\"response_format\"").contains("verbose_json");
        assertThat(body).doesNotContain("name=\"audio_file\"");
    }

    @Test
    void appendsThePathUnderABaseUrlThatAlreadyCarriesOne() throws Exception {
        // The realistic case: an OpenAI-compatible base URL ends in /v1, and the resolved request
        // must be /v1/audio/transcriptions rather than /audio/transcriptions.
        startServer("/v1/audio/transcriptions", 200, VERBOSE_JSON);

        TranscriptionClientProperties properties = properties("openai-compatible");
        properties.setBaseUrl(baseUrl() + "/v1");

        assertThat(client(properties).transcribeToJson(tempWav()).text()).isEqualTo("hello world");
    }

    @Test
    void throwsTypedExceptionOnHttpError() throws Exception {
        startServer("/audio/transcriptions", 400,
                "{\"error\":{\"message\":\"Model 'x' is not a transcription model\"}}");

        Path wav = tempWav();
        assertThatThrownBy(() -> client(properties("openai-compatible")).transcribeToJson(wav))
                .isInstanceOf(TranscriptionFailureException.class)
                .hasMessageContaining("openai-compatible transcription returned HTTP 400")
                .hasMessageContaining("is not a transcription model");
    }
}
