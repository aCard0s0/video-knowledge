package com.tradinglabs.vidingest.core.transcription.client.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.config.TranscriptionClientProperties;
import com.tradinglabs.vidingest.core.transcription.client.AbstractTranscriptionClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;

/**
 * Transcription against any server speaking the OpenAI audio API:
 * {@code POST {base}/audio/transcriptions} with {@code file} + {@code model} parts. Covers oMLX,
 * LM Studio, and the OpenAI API itself. {@code base-url} must include the API prefix, usually
 * {@code /v1}.
 *
 * <p>{@code response_format=verbose_json} is not optional. The default {@code json} returns the
 * transcript text and nothing else, and {@code TranscriptionService} persists one row per segment
 * — without timestamps the phase would store a single blob and FUSE would have nothing to align
 * OCR and diarization against.
 */
@Component
public class OpenAiAudioTranscriptionClient extends AbstractTranscriptionClient {

    // Explicit constructor with @Qualifier on the parameter — see WhisperAsrClient.
    public OpenAiAudioTranscriptionClient(
            ObjectMapper objectMapper,
            TranscriptionClientProperties properties,
            @Qualifier("transcriptionRestClient") RestClient restClient
    ) {
        super(objectMapper, properties, restClient);
    }

    @Override
    protected String providerName() {
        return "openai-compatible";
    }

    @Override
    protected URI uri(String baseUrl) {
        return resolve(baseUrl, "/audio/transcriptions");
    }

    @Override
    protected MultiValueMap<String, Object> buildParts(FileSystemResource audio) {
        MultiValueMap<String, Object> parts = parts();
        parts.add("file", audio);
        parts.add("model", properties.getModel());
        parts.add("response_format", "verbose_json");
        return parts;
    }
}
