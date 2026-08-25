package com.tradinglabs.vidingest.core.transcription.whisper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.core.transcription.dto.WhisperAsrResult;
import com.tradinglabs.vidingest.core.transcription.dto.WhisperSegment;
import com.tradinglabs.vidingest.core.transcription.service.TranscriptionFailureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class WhisperAsrClient {

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    // Explicit constructor (not @RequiredArgsConstructor) so the @Qualifier sits on the
    // constructor parameter where Spring is guaranteed to honour it — M2 introduced a
    // second RestClient bean (diarizationRestClient) and Lombok's field-level @Qualifier
    // copying isn't reliable enough to disambiguate.
    public WhisperAsrClient(
            ObjectMapper objectMapper,
            @Qualifier("whisperRestClient") RestClient restClient
    ) {
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    public WhisperAsrResult transcribeToJson(Path audioFile) {
        long startNs = System.nanoTime();

        if (audioFile == null) {
            throw new TranscriptionFailureException("Audio file path is null");
        }
        if (!Files.exists(audioFile)) {
            throw new TranscriptionFailureException("Audio file does not exist: " + audioFile);
        }

        long audioBytes = safeSize(audioFile);
        log.info(
                "Whisper ASR request start: endpoint=/asr?output=json, file={}, bytes={}",
                audioFile.getFileName(),
                audioBytes >= 0 ? audioBytes : "unknown"
        );

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("audio_file", new FileSystemResource(audioFile.toFile()));

        String raw;
        try {
            raw = restClient.post()
                    .uri(uriBuilder -> uriBuilder.path("/asr").queryParam("output", "json").build())
                    .body(parts)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            long elapsedMs = elapsedMs(startNs);
            log.warn(
                    "Whisper ASR request failed: endpoint=/asr?output=json, file={}, bytes={}, httpStatus={}, elapsedMs={}",
                    audioFile.getFileName(),
                    audioBytes >= 0 ? audioBytes : "unknown",
                    e.getStatusCode().value(),
                    elapsedMs
            );
            throw new TranscriptionFailureException(
                    "Whisper returned HTTP " + e.getStatusCode().value() + ": " + safeBodySnippet(e),
                    e
            );
        } catch (RestClientException e) {
            long elapsedMs = elapsedMs(startNs);
            log.warn(
                    "Whisper ASR request failed: endpoint=/asr?output=json, file={}, bytes={}, elapsedMs={}, message={}",
                    audioFile.getFileName(),
                    audioBytes >= 0 ? audioBytes : "unknown",
                    elapsedMs,
                    e.getMessage()
            );
            throw new TranscriptionFailureException("Whisper request failed: " + e.getMessage(), e);
        }

        if (raw == null || raw.isBlank()) {
            throw new TranscriptionFailureException("Whisper returned an empty response");
        }

        try {
            JsonNode root = objectMapper.readTree(raw);

            String text = readText(root);
            String language = readOptionalText(root, "language");

            List<WhisperSegment> segments = new ArrayList<>();
            JsonNode segs = root.path("segments");
            if (segs.isArray()) {
                for (JsonNode seg : segs) {
                    WhisperSegment parsed = parseSegment(seg);
                    if (parsed != null) {
                        segments.add(parsed);
                    }
                }
            }

            long elapsedMs = elapsedMs(startNs);
            log.info(
                    "Whisper ASR response received: endpoint=/asr?output=json, file={}, elapsedMs={}, rawChars={}, language={}, segments={}, textChars={}",
                    audioFile.getFileName(),
                    elapsedMs,
                    raw.length(),
                    language != null ? language : "",
                    segments.size(),
                    text != null ? text.length() : 0
            );

            return new WhisperAsrResult(raw, text, language, segments);
        } catch (IOException e) {
            throw new TranscriptionFailureException("Failed to parse Whisper JSON response: " + e.getMessage(), e);
        }
    }

    private static long safeSize(Path file) {
        try {
            return Files.size(file);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }

    private static String safeBodySnippet(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        if (body == null) {
            return "";
        }
        String trimmed = body.trim();
        if (trimmed.length() <= 500) {
            return trimmed;
        }
        return trimmed.substring(0, 500) + "...";
    }

    private static String readText(JsonNode root) {
        String text = readOptionalText(root, "text");
        return text != null ? text : "";
    }

    private static String readOptionalText(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text != null && !text.isBlank() ? text : null;
    }

    private static WhisperSegment parseSegment(JsonNode seg) {
        if (seg == null || seg.isNull()) {
            return null;
        }

        Float start = readFloat(seg, "start", "start_seconds", "startSeconds");
        Float end = readFloat(seg, "end", "end_seconds", "endSeconds");

        if ((start == null || end == null) && seg.has("timestamps") && seg.get("timestamps").isArray()) {
            JsonNode ts = seg.get("timestamps");
            if (start == null && ts.size() >= 1) {
                start = (float) ts.get(0).asDouble();
            }
            if (end == null && ts.size() >= 2) {
                end = (float) ts.get(1).asDouble();
            }
        }

        String text = readOptionalText(seg, "text");
        if (text == null) {
            text = readOptionalText(seg, "transcript");
        }

        if (start == null || end == null || text == null) {
            return null;
        }

        return new WhisperSegment(start, end, text.trim());
    }

    private static Float readFloat(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                return (float) value.asDouble();
            }
            if (value.isTextual()) {
                try {
                    return Float.parseFloat(value.asText());
                } catch (NumberFormatException ignored) {
                    // try next
                }
            }
        }
        return null;
    }

}

