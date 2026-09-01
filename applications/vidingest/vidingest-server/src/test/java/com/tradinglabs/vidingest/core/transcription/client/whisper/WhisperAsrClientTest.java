package com.tradinglabs.vidingest.core.transcription.client.whisper;

import com.fasterxml.jackson.databind.ObjectMapper;
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
}
