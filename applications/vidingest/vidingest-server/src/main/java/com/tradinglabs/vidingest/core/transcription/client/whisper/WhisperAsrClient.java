package com.tradinglabs.vidingest.core.transcription.client.whisper;

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
 * Transcription against openai-whisper-asr-webservice: {@code POST {base}/asr?output=json} with a
 * single {@code audio_file} part.
 *
 * <p>Keeps its protocol-specific name for the same reason {@code OllamaEmbeddingsClient} does —
 * the endpoint path, the query parameter and the part name are that service's own shape, not a
 * generic one. Everything above that split lives in {@link AbstractTranscriptionClient}.
 *
 * <p>The service has no notion of a per-request model: the container's {@code ASR_MODEL} decides,
 * so {@code vidingest.transcription.model} is ignored here.
 */
@Component
public class WhisperAsrClient extends AbstractTranscriptionClient {

    // Explicit constructor with @Qualifier on the parameter (same pattern as DiarizationClient /
    // PaddleOcrClient) — several RestClient beans exist in the context and Lombok's field-level
    // qualifier copying isn't reliable enough to disambiguate.
    public WhisperAsrClient(
            ObjectMapper objectMapper,
            TranscriptionClientProperties properties,
            @Qualifier("transcriptionRestClient") RestClient restClient
    ) {
        super(objectMapper, properties, restClient);
    }

    @Override
    protected String providerName() {
        return "whisper-asr";
    }

    @Override
    protected URI uri(String baseUrl) {
        return URI.create(resolve(baseUrl, "/asr") + "?output=json");
    }

    @Override
    protected MultiValueMap<String, Object> buildParts(FileSystemResource audio) {
        MultiValueMap<String, Object> parts = parts();
        parts.add("audio_file", audio);
        return parts;
    }
}
