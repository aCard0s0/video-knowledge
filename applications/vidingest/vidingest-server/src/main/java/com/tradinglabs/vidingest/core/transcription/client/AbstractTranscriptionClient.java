package com.tradinglabs.vidingest.core.transcription.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.config.TranscriptionClientProperties;
import com.tradinglabs.vidingest.core.transcription.dto.WhisperAsrResult;
import com.tradinglabs.vidingest.core.transcription.dto.WhisperSegment;
import com.tradinglabs.vidingest.core.transcription.service.TranscriptionFailureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The half of a {@link TranscriptionClient} that does not depend on which runtime answers: guard
 * the input file, post the multipart, turn a transport failure into a message that names what went
 * wrong, and parse the response.
 *
 * <p>The parsing is shared rather than duplicated because the two envelopes are the same shape.
 * whisper-asr-webservice returns {@code {text, language, segments[{start, end, text}]}}, and so
 * does the OpenAI {@code verbose_json} response format; the tolerant field lookups below
 * (snake/camel variants, the {@code timestamps} array fallback) already covered both before the
 * second client existed.
 *
 * <p>Subclasses supply only what genuinely differs: the URI under the configured base, and the
 * multipart part names the server expects.
 */
@Slf4j
public abstract class AbstractTranscriptionClient implements TranscriptionClient {

    protected final ObjectMapper objectMapper;
    protected final TranscriptionClientProperties properties;
    private final RestClient restClient;

    protected AbstractTranscriptionClient(ObjectMapper objectMapper,
                                          TranscriptionClientProperties properties,
                                          RestClient restClient) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.restClient = restClient;
    }

    /** Value of {@code vidingest.transcription.provider} this client serves; used in log lines. */
    protected abstract String providerName();

    /**
     * Absolute request URI, built from the live base URL on every call so that a settings change
     * takes effect without recreating the client.
     */
    protected abstract URI uri(String baseUrl);

    /** The multipart body. Part names differ between the two protocols; the file does not. */
    protected abstract MultiValueMap<String, Object> buildParts(FileSystemResource audio);

    @Override
    public final WhisperAsrResult transcribeToJson(Path audioFile) {
        long startNs = System.nanoTime();

        if (audioFile == null) {
            throw new TranscriptionFailureException("Audio file path is null");
        }
        if (!Files.exists(audioFile)) {
            throw new TranscriptionFailureException("Audio file does not exist: " + audioFile);
        }

        String baseUrl = properties.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new TranscriptionFailureException(
                    "No transcription base URL configured (vidingest.transcription.base-url)");
        }

        URI uri = uri(baseUrl);
        long audioBytes = safeSize(audioFile);
        log.info("Transcription request start: provider={}, uri={}, file={}, bytes={}",
                providerName(), uri, audioFile.getFileName(), audioBytes >= 0 ? audioBytes : "unknown");

        MultiValueMap<String, Object> parts = buildParts(new FileSystemResource(audioFile.toFile()));

        String raw;
        try {
            RestClient.RequestBodySpec spec = restClient.post().uri(uri);

            // Sent whenever a key is configured, whatever the provider: a local runtime has no auth
            // of its own but is often fronted by a proxy that does. Same rule as the chat clients.
            String apiKey = properties.getApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                spec = spec.header("Authorization", "Bearer " + apiKey.trim());
            }

            raw = spec.body(parts).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            log.warn("Transcription request failed: provider={}, uri={}, file={}, bytes={}, httpStatus={}, elapsedMs={}",
                    providerName(), uri, audioFile.getFileName(),
                    audioBytes >= 0 ? audioBytes : "unknown", e.getStatusCode().value(), elapsedMs(startNs));
            throw new TranscriptionFailureException(
                    providerName() + " transcription returned HTTP " + e.getStatusCode().value()
                            + ": " + safeBodySnippet(e), e);
        } catch (RestClientException e) {
            log.warn("Transcription request failed: provider={}, uri={}, file={}, bytes={}, elapsedMs={}, message={}",
                    providerName(), uri, audioFile.getFileName(),
                    audioBytes >= 0 ? audioBytes : "unknown", elapsedMs(startNs), e.getMessage());
            throw new TranscriptionFailureException(
                    providerName() + " transcription request failed: " + e.getMessage(), e);
        }

        if (raw == null || raw.isBlank()) {
            throw new TranscriptionFailureException(
                    providerName() + " transcription returned an empty response");
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

            log.info("Transcription response received: provider={}, uri={}, file={}, elapsedMs={}, "
                            + "rawChars={}, language={}, segments={}, textChars={}",
                    providerName(), uri, audioFile.getFileName(), elapsedMs(startNs), raw.length(),
                    language != null ? language : "", segments.size(), text != null ? text.length() : 0);

            return new WhisperAsrResult(raw, text, language, segments);
        } catch (IOException e) {
            throw new TranscriptionFailureException(
                    "Failed to parse " + providerName() + " transcription JSON response: " + e.getMessage(), e);
        }
    }

    /**
     * Resolves a path against the configured base without swallowing a path the base already
     * carries — {@code http://host:8000/v1} + {@code /audio/transcriptions} has to keep the
     * {@code /v1}, which is what {@code RestClient.baseUrl} would have done and plain string
     * concatenation would get wrong on a trailing slash.
     */
    protected static URI resolve(String baseUrl, String path) {
        return UriComponentsBuilder.fromUriString(baseUrl.trim())
                .path(path)
                .build()
                .toUri();
    }

    protected static MultiValueMap<String, Object> parts() {
        return new LinkedMultiValueMap<>();
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
