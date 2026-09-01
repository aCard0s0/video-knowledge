package com.tradinglabs.vidingest.core.ocr.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.config.OcrConfig;
import com.tradinglabs.vidingest.core.ocr.dto.OcrLine;
import com.tradinglabs.vidingest.core.ocr.dto.OcrPageResult;
import com.tradinglabs.vidingest.core.ocr.service.OcrFailureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
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
import java.util.Locale;

/**
 * Thin {@link RestClient} wrapper around the {@code paddleocr-server} sidecar. Mirrors
 * {@code WhisperAsrClient} and {@code DiarizationClient} — POST a multipart image and parse
 * a JSON response into typed records.
 *
 * <p>Wire contract (defined by the sidecar at {@code package/vidingest/paddleocr-server/}):
 * <pre>
 *   POST /ocr?lang=en  multipart image=&lt;jpg&gt;
 *   →  {
 *        "lines": [
 *          {
 *            "text": "Hello world",
 *            "confidence": 0.94,
 *            "bbox": [[10,20],[110,20],[110,40],[10,40]],
 *            "language": "en"
 *          },
 *          ...
 *        ]
 *      }
 * </pre>
 */
@Component
@Slf4j
public class PaddleOcrClient {

    private final ObjectMapper objectMapper;
    private final OcrConfig ocrConfig;
    private final RestClient restClient;

    // Explicit constructor (not @RequiredArgsConstructor) so the @Qualifier sits on the
    // constructor parameter — three RestClient beans exist now and Lombok's field-level
    // copying isn't reliable enough to disambiguate.
    public PaddleOcrClient(
            ObjectMapper objectMapper,
            OcrConfig ocrConfig,
            @Qualifier("ocrRestClient") RestClient restClient
    ) {
        this.objectMapper = objectMapper;
        this.ocrConfig = ocrConfig;
        this.restClient = restClient;
    }

    /**
     * Run OCR on a single image file. The {@code lang} query parameter is built from the
     * configured language list — PaddleOCR accepts one primary language per request, so we
     * pass the first entry (typically the only one). Multi-language workflows are
     * out-of-scope for M4.
     */
    public OcrPageResult ocr(Path imageFile) {
        long startNs = System.nanoTime();

        if (imageFile == null) {
            throw new OcrFailureException("Image file path is null");
        }
        if (!Files.exists(imageFile)) {
            throw new OcrFailureException("Image file does not exist: " + imageFile);
        }

        long imageBytes = safeSize(imageFile);
        String lang = primaryLanguage();
        log.debug("OCR request start: endpoint=/ocr, file={}, bytes={}, lang={}",
                imageFile.getFileName(), imageBytes >= 0 ? imageBytes : "unknown", lang);

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("image", new FileSystemResource(imageFile.toFile()));

        String raw;
        try {
            raw = restClient.post()
                    .uri(ocrUri(lang))
                    .body(parts)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            long elapsedMs = elapsedMs(startNs);
            log.warn("OCR request failed: endpoint=/ocr, file={}, bytes={}, httpStatus={}, elapsedMs={}",
                    imageFile.getFileName(),
                    imageBytes >= 0 ? imageBytes : "unknown",
                    e.getStatusCode().value(),
                    elapsedMs);
            throw new OcrFailureException(
                    "PaddleOCR sidecar returned HTTP " + e.getStatusCode().value() + ": " + safeBodySnippet(e),
                    e
            );
        } catch (RestClientException e) {
            long elapsedMs = elapsedMs(startNs);
            log.warn("OCR request failed: endpoint=/ocr, file={}, bytes={}, elapsedMs={}, message={}",
                    imageFile.getFileName(),
                    imageBytes >= 0 ? imageBytes : "unknown",
                    elapsedMs,
                    e.getMessage());
            throw new OcrFailureException("OCR request failed: " + e.getMessage(), e);
        }

        if (raw == null || raw.isBlank()) {
            throw new OcrFailureException("PaddleOCR sidecar returned an empty response");
        }

        try {
            JsonNode root = objectMapper.readTree(raw);
            List<OcrLine> lines = parseLines(root.path("lines"), lang);
            long elapsedMs = elapsedMs(startNs);
            log.debug("OCR response received: endpoint=/ocr, file={}, elapsedMs={}, lines={}",
                    imageFile.getFileName(), elapsedMs, lines.size());
            return new OcrPageResult(lines);
        } catch (IOException e) {
            throw new OcrFailureException("Failed to parse OCR JSON response: " + e.getMessage(), e);
        }
    }

    private static List<OcrLine> parseLines(JsonNode arr, String defaultLanguage) {
        List<OcrLine> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode node : arr) {
            String text = readText(node, "text", "transcription");
            if (text == null || text.isBlank()) {
                continue;
            }
            Float confidence = readFloat(node, "confidence", "score");
            List<List<Double>> bbox = readBbox(node.get("bbox"));
            String language = readText(node, "language", "lang");
            if (language == null || language.isBlank()) {
                language = defaultLanguage;
            }
            out.add(new OcrLine(text.trim(), confidence, bbox, language));
        }
        return out;
    }

    private static List<List<Double>> readBbox(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        List<List<Double>> out = new ArrayList<>(node.size());
        for (JsonNode pt : node) {
            if (!pt.isArray() || pt.size() < 2) {
                return null;  // malformed — drop bbox rather than ship a half-shape
            }
            List<Double> corner = new ArrayList<>(pt.size());
            for (JsonNode coord : pt) {
                if (!coord.isNumber()) {
                    return null;
                }
                corner.add(coord.asDouble());
            }
            out.add(corner);
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
                    // try next key
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

    private String primaryLanguage() {
        List<String> langs = ocrConfig.getLanguages();
        if (langs == null || langs.isEmpty()) {
            return null;
        }
        return langs.get(0).toLowerCase(Locale.ROOT);
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

    /**
     * Built from the live {@code ocrConfig.getBaseUrl()} on every call rather than baked into the
     * {@code RestClient}, so repointing the OCR connection at runtime takes effect without a
     * restart.
     */
    private URI ocrUri(String lang) {
        String baseUrl = ocrConfig.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new OcrFailureException("No OCR base URL configured (vidingest.ocr.base-url)");
        }
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl.trim()).path("/ocr");
        if (lang != null && !lang.isBlank()) {
            b = b.queryParam("lang", lang);
        }
        return b.build().toUri();
    }
}
