package com.tradinglabs.vidingest.core.transcription.client.whisper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.config.TranscriptionClientProperties;
import com.tradinglabs.vidingest.core.transcription.client.TranscriptionClientTestBase;
import com.tradinglabs.vidingest.core.transcription.dto.WhisperAsrResult;
import com.tradinglabs.vidingest.core.transcription.dto.WhisperSegment;
import com.tradinglabs.vidingest.core.transcription.service.TranscriptionFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class WhisperAsrClientTest extends TranscriptionClientTestBase {

    private WhisperAsrClient client() {
        return new WhisperAsrClient(new ObjectMapper(), properties("whisper-asr"), restClient());
    }

    @Test
    void transcribeToJsonParsesResponseAndEmitsProgressLogs(CapturedOutput output) throws Exception {
        startServer("/asr", 200, """
                {
                  "text": "hello world",
                  "language": "en",
                  "segments": [
                    { "start": 0.0, "end": 1.0, "text": "hello" },
                    { "start": 1.0, "end": 2.0, "text": "world" }
                  ]
                }
                """);

        Path wav = tempWav();
        WhisperAsrResult result = client().transcribeToJson(wav);

        assertThat(result.language()).isEqualTo("en");
        assertThat(result.text()).isEqualTo("hello world");
        assertThat(result.segments()).extracting(WhisperSegment::text).containsExactly("hello", "world");

        // The part name is this service's own; the OpenAI sibling sends "file" instead.
        assertThat(lastRequestBody.get()).contains("name=\"audio_file\"");

        assertThat(output.getOut()).contains("Transcription request start");
        assertThat(output.getOut()).contains("provider=whisper-asr");
        assertThat(output.getOut()).contains("Transcription response received");
    }

    @Test
    void transcribeToJsonThrowsTypedExceptionOnHttp500() throws Exception {
        startServer("/asr", 500, "{\"detail\":\"boom\"}");

        Path wav = tempWav();
        assertThatThrownBy(() -> client().transcribeToJson(wav))
                .isInstanceOf(TranscriptionFailureException.class)
                .hasMessageContaining("whisper-asr transcription returned HTTP 500")
                .hasMessageContaining("boom");
    }

    /**
     * The whisper-asr sidecar's {@code POST /asr} has no prompt part, so a configured vocabulary
     * hint must be ignored here rather than sent under a name the sidecar does not read. Asserted
     * so that setting the property for an openai-compatible deployment cannot quietly alter
     * requests if the provider is switched back.
     */
    @Test
    void ignoresTheVocabularyHintBecauseTheSidecarHasNoPromptPart() throws Exception {
        startServer("/asr", 200, """
                { "text": "hello world", "language": "en", "segments": [] }
                """);

        TranscriptionClientProperties properties = properties("whisper-asr");
        properties.setPrompt("break of structure, fib retracement");
        new WhisperAsrClient(new ObjectMapper(), properties, restClient()).transcribeToJson(tempWav());

        String body = lastRequestBody.get();
        assertThat(body).contains("name=\"audio_file\"");
        assertThat(body).doesNotContain("name=\"prompt\"");
        assertThat(body).doesNotContain("break of structure");
    }
}
