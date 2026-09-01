package com.tradinglabs.vidingest.connections.service;

import com.tradinglabs.vidingest.api.connections.ConnectionName;
import com.tradinglabs.vidingest.api.connections.ConnectionTestResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

/**
 * One-shot reachability probe for a connection's currently effective settings.
 *
 * <p>Exists so that repointing a connection at a remote host is not a blind edit: without it the
 * operator learns the URL was wrong when a run fails a phase, minutes or hours later.
 *
 * <p>An unreachable dependency is a successful answer to "is it reachable?", so this never throws
 * — the failure is data in {@link ConnectionTestResult}. Timeouts are short and fixed rather than
 * the connection's own: a probe that inherits a 30-minute read timeout is not a probe. Same 2s/5s
 * pair {@code LlmStatusService} uses for the equivalent job.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConnectionProbeService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_DETAIL_CHARS = 500;

    private final ConnectionSettingsService settings;

    public ConnectionTestResult probe(ConnectionName name) {
        ConnectionValues values = settings.current(name);
        String baseUrl = values.baseUrl();

        // FRAME_SAMPLE is local ffmpeg: there is no endpoint, so "unreachable" would be a lie and
        // "no base URL configured" would read as something the operator forgot to fill in.
        if (!settings.hasBaseUrl(name)) {
            return new ConnectionTestResult(name, false, null, null, null,
                    name + " runs locally and has no endpoint to probe", 0);
        }

        if (baseUrl == null || baseUrl.isBlank()) {
            return new ConnectionTestResult(name, false, baseUrl, null, null,
                    "No base URL configured for " + name, 0);
        }

        String provider = values.provider() == null ? "" : values.provider().trim().toLowerCase(Locale.ROOT);
        if (provider.equals("disabled") || provider.equals("none")) {
            return new ConnectionTestResult(name, false, baseUrl, null, null,
                    "Provider is 'disabled'; nothing to probe", 0);
        }

        URI probeUri = UriComponentsBuilder.fromUriString(baseUrl.trim())
                .path(probePath(name, provider))
                .build()
                .toUri();

        long startNs = System.nanoTime();
        try {
            String body = client().get().uri(probeUri).retrieve().body(String.class);
            long elapsedMs = elapsedMs(startNs);
            log.info("Connection probe ok: name={}, uri={}, elapsedMs={}", name, probeUri, elapsedMs);
            return new ConnectionTestResult(name, true, baseUrl, probeUri.toString(),
                    truncate(body), null, elapsedMs);
        } catch (Exception e) {
            long elapsedMs = elapsedMs(startNs);
            log.warn("Connection probe failed: name={}, uri={}, elapsedMs={}, message={}",
                    name, probeUri, elapsedMs, e.getMessage());
            return new ConnectionTestResult(name, false, baseUrl, probeUri.toString(), null,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), elapsedMs);
        }
    }

    /**
     * The cheapest endpoint each protocol offers that proves the right service is answering.
     * Deliberately keyed on the provider rather than the connection for the LLM cases: the same
     * {@code openai-compatible} value means {@code /models} whether it is embeddings, chat or
     * transcription answering.
     */
    private static String probePath(ConnectionName name, String provider) {
        return switch (provider) {
            case "ollama" -> "/api/tags";
            case "openai-compatible", "openai" -> "/models";
            case "whisper-asr", "whisper" -> "/docs";
            // The two sidecars are ours and both expose /health.
            case "diarize-asr", "paddleocr" -> "/health";
            default -> throw new IllegalArgumentException(
                    "No probe defined for provider '" + provider + "' on connection " + name);
        };
    }

    private static RestClient client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.toIntExact(CONNECT_TIMEOUT.toMillis()));
        factory.setReadTimeout(Math.toIntExact(READ_TIMEOUT.toMillis()));
        return RestClient.builder().requestFactory(factory).build();
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }

    private static String truncate(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.trim();
        return trimmed.length() <= MAX_DETAIL_CHARS ? trimmed : trimmed.substring(0, MAX_DETAIL_CHARS) + "...";
    }
}
