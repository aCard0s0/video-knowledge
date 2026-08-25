package com.tradinglabs.vidingest.health;

import com.tradinglabs.vidingest.api.health.OllamaStatus;
import com.tradinglabs.vidingest.api.health.OllamaStatus.OllamaModel;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class OllamaStatusService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final VideoSearchConfig searchConfig;

    public OllamaStatus probe() {
        VideoSearchConfig.Embeddings cfg = searchConfig.getEmbeddings();
        VideoSearchConfig.Embeddings.Ollama ollama = cfg != null ? cfg.getOllama() : null;
        String baseUrl = ollama != null ? ollama.getBaseUrl() : null;
        String embedModel = ollama != null ? ollama.getEmbedModel() : null;

        if (baseUrl == null || baseUrl.isBlank()) {
            return new OllamaStatus(false, "", embedModel, List.of(), List.of(),
                    "Missing Ollama base URL (vidingest.search.embeddings.ollama.base-url).");
        }

        RestClient client = buildClient(baseUrl);

        List<OllamaModel> installed;
        try {
            installed = fetchInstalled(client);
        } catch (Exception e) {
            log.warn("Ollama probe /api/tags failed: {}", e.getMessage());
            return new OllamaStatus(false, baseUrl, embedModel, List.of(), List.of(),
                    "Ollama unreachable: " + e.getMessage());
        }

        List<OllamaModel> running = List.of();
        try {
            running = fetchRunning(client);
        } catch (Exception e) {
            log.warn("Ollama probe /api/ps failed: {}", e.getMessage());
        }

        return new OllamaStatus(true, baseUrl, embedModel, running, installed, null);
    }

    private RestClient buildClient(String baseUrl) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        rf.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(rf).build();
    }

    @SuppressWarnings("unchecked")
    private List<OllamaModel> fetchInstalled(RestClient client) {
        Map<String, Object> body = client.get()
                .uri("/api/tags")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(Map.class);
        if (body == null) return List.of();
        Object models = body.get("models");
        if (!(models instanceof List<?> list)) return List.of();
        List<OllamaModel> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            out.add(new OllamaModel(
                    str(m.get("name")),
                    str(m.get("digest")),
                    longOrNull(m.get("size")),
                    null
            ));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<OllamaModel> fetchRunning(RestClient client) {
        Map<String, Object> body = client.get()
                .uri("/api/ps")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(Map.class);
        if (body == null) return List.of();
        Object models = body.get("models");
        if (!(models instanceof List<?> list)) return List.of();
        List<OllamaModel> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            out.add(new OllamaModel(
                    str(m.get("name")),
                    str(m.get("digest")),
                    longOrNull(m.get("size")),
                    str(m.get("expires_at"))
            ));
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
