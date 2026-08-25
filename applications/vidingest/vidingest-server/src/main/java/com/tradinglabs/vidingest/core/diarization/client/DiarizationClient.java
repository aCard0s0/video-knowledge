package com.tradinglabs.vidingest.core.diarization.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.config.DiarizationConfig;
import com.tradinglabs.vidingest.core.diarization.dto.DiarizationResult;
import com.tradinglabs.vidingest.core.diarization.dto.DiarizationSegment;
import com.tradinglabs.vidingest.core.diarization.dto.DiarizationSpeaker;
import com.tradinglabs.vidingest.core.diarization.service.DiarizationFailureException;
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

/**
 * Thin RestClient wrapper around the {@code diarize-asr} sidecar. Mirrors
 * {@code WhisperAsrClient} in shape and logging: POST a 16kHz mono PCM WAV as multipart and
 * parse the JSON response into typed records.
 *
 * <p>Wire contract (defined by the sidecar at {@code package/vidingest/diarize-asr/}):
 * <pre>
 *   POST /diarize?min_speakers=N&amp;max_speakers=M  multipart audio_file=&lt;wav&gt;
 *   →  {
 *        "segments": [ { "start": 0.0, "end": 4.2, "speaker": "SPEAKER_00" }, ... ],
 *        "speakers": [ { "label": "SPEAKER_00", "embedding": [ ... 192 floats ... ] }, ... ]
 *      }
 * </pre>
 */
@Component
@Slf4j
public class DiarizationClient {

    private final ObjectMapper objectMapper;
    private final DiarizationConfig diarizationConfig;
    private final RestClient restClient;

    // Explicit constructor (not @RequiredArgsConstructor) so the @Qualifier sits on the
    // constructor parameter where Spring is guaranteed to honour it — Lombok's copying of
    // field-level @Qualifier annotations is unreliable when more than one matching bean
    // exists (we now have whisperRestClient + diarizationRestClient).
    public DiarizationClient(
            ObjectMapper objectMapper,
            DiarizationConfig diarizationConfig,
            @Qualifier("diarizationRestClient") RestClient restClient
    ) {
        this.objectMapper = objectMapper;
        this.diarizationConfig = diarizationConfig;
        this.restClient = restClient;
    }

    public DiarizationResult diarize(Path audioFile) {
        long startNs = System.nanoTime();

        if (audioFile == null) {
            throw new DiarizationFailureException("Audio file path is null");
        }
        if (!Files.exists(audioFile)) {
            throw new DiarizationFailureException("Audio file does not exist: " + audioFile);
        }

        long audioBytes = safeSize(audioFile);
        Integer minSpeakers = diarizationConfig.getMinSpeakers();
        Integer maxSpeakers = diarizationConfig.getMaxSpeakers();
        log.info(
                "Diarization request start: endpoint=/diarize, file={}, bytes={}, minSpeakers={}, maxSpeakers={}",
                audioFile.getFileName(),
                audioBytes >= 0 ? audioBytes : "unknown",
                minSpeakers,
                maxSpeakers
        );

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("audio_file", new FileSystemResource(audioFile.toFile()));

        String raw;
        try {
            raw = restClient.post()
                    .uri(uriBuilder -> {
                        var b = uriBuilder.path("/diarize");
                        if (minSpeakers != null) {
                            b = b.queryParam("min_speakers", minSpeakers);
                        }
                        if (maxSpeakers != null) {
                            b = b.queryParam("max_speakers", maxSpeakers);
                        }
                        return b.build();
                    })
                    .body(parts)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            long elapsedMs = elapsedMs(startNs);
            log.warn(
                    "Diarization request failed: endpoint=/diarize, file={}, bytes={}, httpStatus={}, elapsedMs={}",
                    audioFile.getFileName(),
                    audioBytes >= 0 ? audioBytes : "unknown",
                    e.getStatusCode().value(),
                    elapsedMs
            );
            throw new DiarizationFailureException(
                    "Diarization sidecar returned HTTP " + e.getStatusCode().value() + ": " + safeBodySnippet(e),
                    e
            );
        } catch (RestClientException e) {
            long elapsedMs = elapsedMs(startNs);
            log.warn(
                    "Diarization request failed: endpoint=/diarize, file={}, bytes={}, elapsedMs={}, message={}",
                    audioFile.getFileName(),
                    audioBytes >= 0 ? audioBytes : "unknown",
                    elapsedMs,
                    e.getMessage()
            );
            throw new DiarizationFailureException("Diarization request failed: " + e.getMessage(), e);
        }

        if (raw == null || raw.isBlank()) {
            throw new DiarizationFailureException("Diarization sidecar returned an empty response");
        }

        try {
            JsonNode root = objectMapper.readTree(raw);
            List<DiarizationSegment> segments = parseSegments(root.path("segments"));
            List<DiarizationSpeaker> speakers = parseSpeakers(root.path("speakers"));

            long elapsedMs = elapsedMs(startNs);
            log.info(
                    "Diarization response received: endpoint=/diarize, file={}, elapsedMs={}, rawChars={}, segments={}, speakers={}",
                    audioFile.getFileName(),
                    elapsedMs,
                    raw.length(),
                    segments.size(),
                    speakers.size()
            );

            return new DiarizationResult(segments, speakers);
        } catch (IOException e) {
            throw new DiarizationFailureException("Failed to parse diarization JSON response: " + e.getMessage(), e);
        }
    }

    private static List<DiarizationSegment> parseSegments(JsonNode segs) {
        List<DiarizationSegment> out = new ArrayList<>();
        if (segs == null || !segs.isArray()) {
            return out;
        }
        for (JsonNode seg : segs) {
            Float start = readFloat(seg, "start", "start_seconds", "startSeconds");
            Float end = readFloat(seg, "end", "end_seconds", "endSeconds");
            String speaker = readText(seg, "speaker", "speaker_label", "speakerLabel", "label");
            if (start == null || end == null || speaker == null || speaker.isBlank()) {
                continue;
            }
            out.add(new DiarizationSegment(start, end, speaker.trim()));
        }
        return out;
    }

    private static List<DiarizationSpeaker> parseSpeakers(JsonNode speakers) {
        List<DiarizationSpeaker> out = new ArrayList<>();
        if (speakers == null || !speakers.isArray()) {
            return out;
        }
        for (JsonNode sp : speakers) {
            String label = readText(sp, "label", "speaker", "id");
            if (label == null || label.isBlank()) {
                continue;
            }
            float[] embedding = parseEmbedding(sp.get("embedding"));
            out.add(new DiarizationSpeaker(label.trim(), embedding));
        }
        return out;
    }

    private static float[] parseEmbedding(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        float[] out = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            JsonNode v = node.get(i);
            if (v == null || !v.isNumber()) {
                return null;
            }
            out[i] = (float) v.asDouble();
        }
        return out;
    }

    private static Float readFloat(JsonNode node, String... keys) {
        if (node == null) return null;
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) continue;
            if (value.isNumber()) return (float) value.asDouble();
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

    private static String readText(JsonNode node, String... keys) {
        if (node == null) return null;
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) continue;
            String text = value.asText();
            if (text != null && !text.isBlank()) return text;
        }
        return null;
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
}
