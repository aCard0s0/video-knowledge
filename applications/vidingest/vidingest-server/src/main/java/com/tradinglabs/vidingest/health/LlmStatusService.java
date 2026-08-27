package com.tradinglabs.vidingest.health;

import com.tradinglabs.vidingest.api.health.LlmStatus;
import com.tradinglabs.vidingest.api.health.LlmStatus.LlmModel;
import com.tradinglabs.vidingest.config.VideoSearchConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Probes the model runtime behind the embeddings provider so the console rail can say whether it
 * is up and which models it holds.
 *
 * <p>Follows {@code vidingest.search.embeddings.provider} rather than assuming Ollama. It used to
 * read {@code …embeddings.ollama.base-url} unconditionally, so switching the provider to
 * {@code openai-compatible} painted a red "Missing Ollama base URL" chip while embeddings were in
 * fact working — a health check that reports the shape of its own config instead of the state of
 * the dependency.
 *
 * <p>Two probe shapes, because the runtimes genuinely differ:
 * <ul>
 *   <li>{@code ollama} — {@code GET /api/tags} for installed, {@code GET /api/ps} for loaded.</li>
 *   <li>anything else — {@code GET /models}, the OpenAI listing. It carries ids and nothing else,
 *       so digests and sizes come back null and {@code runningModels} stays empty: there is no
 *       portable way to ask an OpenAI-compatible server what it currently has in memory.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmStatusService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final String OLLAMA = "ollama";

    private final VideoSearchConfig searchConfig;

    public LlmStatus probe() {
        VideoSearchConfig.Embeddings cfg = searchConfig.getEmbeddings();
        if (cfg == null) {
            return new LlmStatus(false, null, "", null, List.of(), List.of(),
                    "Embeddings are not configured (vidingest.search.embeddings.*).");
        }

        String provider = cfg.getProvider() != null && !cfg.getProvider().isBlank()
                ? cfg.getProvider().trim()
                : OLLAMA;
        boolean isOllama = OLLAMA.equalsIgnoreCase(provider);

        VideoSearchConfig.Embeddings.Ollama ollama = cfg.getOllama();
        String baseUrl = isOllama
                ? (ollama != null ? ollama.getBaseUrl() : null)
                : cfg.getBaseUrl();
        String embedModel = isOllama
                ? (ollama != null ? ollama.getEmbedModel() : null)
                : cfg.getModel();

        if (baseUrl == null || baseUrl.isBlank()) {
            String key = isOllama
                    ? "vidingest.search.embeddings.ollama.base-url"
                    : "vidingest.search.embeddings.base-url";
            return new LlmStatus(false, provider, "", embedModel, List.of(), List.of(),
                    "Missing LLM base URL (" + key + ").");
        }

        RestClient client = buildClient(baseUrl);
        String listPath = isOllama ? "/api/tags" : "/models";

        List<LlmModel> installed;
        try {
            installed = isOllama ? fetchOllamaModels(client, listPath) : fetchOpenAiModels(client);
        } catch (Exception e) {
            log.warn("LLM probe {} failed (provider={}): {}", listPath, provider, e.getMessage());
            return new LlmStatus(false, provider, baseUrl, embedModel, List.of(), List.of(),
                    "LLM runtime unreachable: " + e.getMessage());
        }

        // Loaded-model reporting is Ollama-only; see the class javadoc.
        List<LlmModel> running = List.of();
        if (isOllama) {
            try {
                running = fetchOllamaModels(client, "/api/ps");
            } catch (Exception e) {
                log.warn("LLM probe /api/ps failed: {}", e.getMessage());
            }
        }

        return new LlmStatus(true, provider, baseUrl, embedModel, running, installed, null);
    }

    private RestClient buildClient(String baseUrl) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        rf.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(rf).build();
    }

    /**
     * {@code /api/tags} and {@code /api/ps} return the same {@code models[]} element shape, so one
     * reader covers both. {@code expires_at} is present only on {@code /api/ps}.
     */
    @SuppressWarnings("unchecked")
    private List<LlmModel> fetchOllamaModels(RestClient client, String uri) {
        Map<String, Object> body = client.get()
                .uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(Map.class);
        if (body == null) return List.of();
        Object models = body.get("models");
        if (!(models instanceof List<?> list)) return List.of();
        List<LlmModel> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            out.add(new LlmModel(
                    str(m.get("name")),
                    str(m.get("digest")),
                    longOrNull(m.get("size")),
                    str(m.get("expires_at"))
            ));
        }
        return out;
    }

    /**
     * OpenAI {@code GET /models} answers {@code {"data":[{"id":…}]}}. Only the id is portable —
     * LM Studio adds its own fields, llama.cpp adds different ones, and neither is worth guessing.
     */
    @SuppressWarnings("unchecked")
    private List<LlmModel> fetchOpenAiModels(RestClient client) {
        Map<String, Object> body = client.get()
                .uri("/models")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(Map.class);
        if (body == null) return List.of();
        Object models = body.get("data");
        if (!(models instanceof List<?> list)) return List.of();
        List<LlmModel> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            out.add(new LlmModel(str(m.get("id")), null, null, null));
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Long longOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
