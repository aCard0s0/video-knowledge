package com.tradinglabs.vidingest.connections.service;

import com.tradinglabs.vidingest.api.connections.ConnectionName;
import com.tradinglabs.vidingest.api.connections.ConnectionSummary;
import com.tradinglabs.vidingest.api.connections.UpdateConnectionRequest;
import com.tradinglabs.vidingest.config.DiarizationConfig;
import com.tradinglabs.vidingest.config.KnowledgeExtractionConfig;
import com.tradinglabs.vidingest.config.OcrConfig;
import com.tradinglabs.vidingest.config.TranscriptionClientProperties;
import com.tradinglabs.vidingest.config.VideoSearchConfig;
import com.tradinglabs.vidingest.connections.domain.Connection;
import com.tradinglabs.vidingest.connections.repo.ConnectionRepository;
import com.tradinglabs.vidingest.core.knowledge.client.KnowledgeChatClientRouter;
import com.tradinglabs.vidingest.core.transcription.client.TranscriptionClientRouter;
import com.tradinglabs.vidingest.search.service.embedding.EmbeddingsClientRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runtime management of the five external-runtime connections.
 *
 * <p>The mechanism is deliberately unexciting: {@code @ConfigurationProperties} beans are
 * singletons with setters, and every client that matters now reads its base URL and provider from
 * them on each call rather than at bean creation. So "apply a setting" is a field write, and no
 * context refresh, {@code @RefreshScope} or restart is involved.
 *
 * <p>Startup order matters and is guaranteed by construction: this bean depends on all five config
 * beans, so Spring finishes binding them from the environment before {@link #applyStoredOverrides()}
 * runs. That method snapshots the environment values <em>first</em> — {@link #defaults} is the only
 * remaining record of what {@code .env} configured once a row has overwritten the bean, and it is
 * what makes reset possible.
 *
 * <p>Timeouts are absent from this API on purpose. They are consumed once when each transport's
 * request factory is built, so exposing them here would let the console report a value the running
 * client is not using.
 */
@Service
@Slf4j
public class ConnectionSettingsService {

    private final ConnectionRepository repository;
    private final Map<ConnectionName, Binding> bindings = new EnumMap<>(ConnectionName.class);
    private final Map<ConnectionName, ConnectionValues> defaults = new EnumMap<>(ConnectionName.class);

    public ConnectionSettingsService(ConnectionRepository repository,
                                     VideoSearchConfig searchConfig,
                                     KnowledgeExtractionConfig knowledgeConfig,
                                     TranscriptionClientProperties transcriptionProperties,
                                     DiarizationConfig diarizationConfig,
                                     OcrConfig ocrConfig) {
        this.repository = repository;

        // EMBEDDINGS is the awkward one: the Ollama base URL lives in a nested block, separate from
        // the OpenAI-compatible one, because the two are different URL shapes (a daemon root vs an
        // API prefix ending in /v1) and a deployment may well have both configured. The provider
        // decides which field this API is talking about, in these two lambdas and nowhere else.
        bindings.put(ConnectionName.EMBEDDINGS, new Binding(
                () -> {
                    VideoSearchConfig.Embeddings e = searchConfig.getEmbeddings();
                    boolean ollama = isOllama(e.getProvider());
                    return new ConnectionValues(
                            e.getProvider(),
                            ollama ? e.getOllama().getBaseUrl() : e.getBaseUrl(),
                            ollama ? e.getOllama().getEmbedModel() : e.getModel(),
                            e.getApiKey(),
                            true);
                },
                values -> {
                    VideoSearchConfig.Embeddings e = searchConfig.getEmbeddings();
                    e.setProvider(values.provider());
                    if (isOllama(values.provider())) {
                        e.getOllama().setBaseUrl(values.baseUrl());
                        if (values.model() != null) {
                            e.getOllama().setEmbedModel(values.model());
                        }
                    } else {
                        e.setBaseUrl(values.baseUrl());
                        if (values.model() != null) {
                            e.setModel(values.model());
                        }
                    }
                    e.setApiKey(values.apiKey() == null ? "" : values.apiKey());
                },
                EmbeddingsClientRouter.SUPPORTED_PROVIDERS,
                true,
                false));

        bindings.put(ConnectionName.KNOWLEDGE, new Binding(
                () -> new ConnectionValues(
                        knowledgeConfig.getProvider(),
                        knowledgeConfig.getBaseUrl(),
                        knowledgeConfig.getChatModel(),
                        knowledgeConfig.getApiKey(),
                        knowledgeConfig.isEnabled()),
                values -> {
                    knowledgeConfig.setProvider(values.provider());
                    knowledgeConfig.setBaseUrl(values.baseUrl());
                    if (values.model() != null) {
                        knowledgeConfig.setChatModel(values.model());
                    }
                    knowledgeConfig.setApiKey(values.apiKey() == null ? "" : values.apiKey());
                    knowledgeConfig.setEnabled(values.enabled());
                },
                KnowledgeChatClientRouter.SUPPORTED_PROVIDERS,
                true,
                true));

        bindings.put(ConnectionName.TRANSCRIPTION, new Binding(
                () -> new ConnectionValues(
                        transcriptionProperties.getProvider(),
                        transcriptionProperties.getBaseUrl(),
                        transcriptionProperties.getModel(),
                        transcriptionProperties.getApiKey(),
                        true),
                values -> {
                    transcriptionProperties.setProvider(values.provider());
                    transcriptionProperties.setBaseUrl(values.baseUrl());
                    if (values.model() != null) {
                        transcriptionProperties.setModel(values.model());
                    }
                    transcriptionProperties.setApiKey(values.apiKey() == null ? "" : values.apiKey());
                },
                TranscriptionClientRouter.SUPPORTED_PROVIDERS,
                true,
                false));

        // The two sidecars speak exactly one protocol each, so their provider is a constant. It is
        // still carried through the API rather than special-cased out of it: a uniform shape costs
        // nothing here and saves the console a second rendering path.
        bindings.put(ConnectionName.DIARIZATION, new Binding(
                () -> new ConnectionValues("diarize-asr", diarizationConfig.getBaseUrl(), null, null,
                        diarizationConfig.isEnabled()),
                values -> {
                    diarizationConfig.setBaseUrl(values.baseUrl());
                    diarizationConfig.setEnabled(values.enabled());
                },
                List.of("diarize-asr"),
                false,
                true));

        bindings.put(ConnectionName.OCR, new Binding(
                () -> new ConnectionValues("paddleocr", ocrConfig.getBaseUrl(), null, null,
                        ocrConfig.isEnabled()),
                values -> {
                    ocrConfig.setBaseUrl(values.baseUrl());
                    ocrConfig.setEnabled(values.enabled());
                },
                List.of("paddleocr"),
                false,
                true));
    }

    /**
     * Snapshot the environment-bound values, then let any stored row win.
     *
     * <p>The order is the point. After a row is applied the bean no longer holds what the
     * environment configured, and nothing else in the process remembers it — {@link #defaults} is
     * what makes {@link #reset} mean "back to what .env said".
     *
     * <p>On {@code ApplicationReadyEvent} rather than {@code @PostConstruct}: the table only exists
     * once Liquibase has run, which is not guaranteed at bean-init time.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void applyStoredOverrides() {
        for (ConnectionName name : ConnectionName.values()) {
            defaults.put(name, bindings.get(name).read().get());
        }

        List<Connection> stored = repository.findAll();
        for (Connection row : stored) {
            apply(row);
            log.info("Connection override applied at startup: name={}, provider={}, baseUrl={}",
                    row.getName(), row.getProvider(), row.getBaseUrl());
        }
        if (stored.isEmpty()) {
            log.info("No stored connection overrides; all {} connections use their configured values",
                    ConnectionName.values().length);
        }
    }

    @Transactional(readOnly = true)
    public List<ConnectionSummary> list() {
        Map<ConnectionName, Connection> stored = new EnumMap<>(ConnectionName.class);
        repository.findAll().forEach(row -> stored.put(row.getName(), row));

        return Arrays.stream(ConnectionName.values())
                .map(name -> summarize(name, Optional.ofNullable(stored.get(name))))
                .toList();
    }

    @Transactional(readOnly = true)
    public ConnectionSummary get(ConnectionName name) {
        return summarize(name, repository.findById(name));
    }

    /**
     * Store the override and apply it to the live config bean.
     *
     * <p>The write is validated first so a rejected request cannot leave the bean holding a value
     * the database does not have — the two must agree, or the next restart silently changes
     * behaviour.
     */
    @Transactional
    public ConnectionSummary update(ConnectionName name, UpdateConnectionRequest request) {
        Binding binding = bindings.get(name);

        String provider = normalize(request.provider());
        if (!binding.supportedProviders().contains(provider)) {
            throw new IllegalArgumentException(
                    "Unsupported provider '" + request.provider() + "' for connection " + name
                            + "; expected one of " + binding.supportedProviders());
        }
        String baseUrl = validateBaseUrl(request.baseUrl());

        Connection row = repository.findById(name).orElseGet(() -> Connection.builder().name(name).build());
        row.setProvider(provider);
        row.setBaseUrl(baseUrl);
        row.setModel(trimToNull(request.model()));

        // Three-valued on purpose: null keeps the stored key, "" clears it. The console can never
        // read the key back, so without this it could not save any other field without wiping it.
        if (request.apiKey() != null) {
            row.setApiKey(request.apiKey().isBlank() ? "" : request.apiKey().trim());
        }

        // Absent leaves the phase toggle alone, and connections without one are always enabled.
        row.setEnabled(!binding.hasEnabledFlag() || (request.enabled() == null
                ? currentEnabled(name)
                : request.enabled()));

        Connection saved = repository.save(row);
        apply(saved);
        log.info("Connection updated: name={}, provider={}, baseUrl={}, model={}, hasApiKey={}, enabled={}",
                name, saved.getProvider(), saved.getBaseUrl(), saved.getModel(),
                saved.getApiKey() != null && !saved.getApiKey().isBlank(), saved.isEnabled());

        return summarize(name, Optional.of(saved));
    }

    /** Drop the override and restore whatever the environment configured at startup. */
    @Transactional
    public void reset(ConnectionName name) {
        repository.deleteById(name);
        bindings.get(name).write().accept(defaults.get(name));
        log.info("Connection reset to its configured default: name={}, baseUrl={}",
                name, defaults.get(name).baseUrl());
    }

    /** The effective values, for the probe and for anything else that needs them uniformly. */
    public ConnectionValues current(ConnectionName name) {
        return bindings.get(name).read().get();
    }

    public List<String> supportedProviders(ConnectionName name) {
        return bindings.get(name).supportedProviders();
    }

    private void apply(Connection row) {
        Binding binding = bindings.get(row.getName());
        ConnectionValues fallback = defaults.get(row.getName());
        binding.write().accept(new ConnectionValues(
                row.getProvider() != null ? row.getProvider() : fallback.provider(),
                row.getBaseUrl(),
                row.getModel() != null ? row.getModel() : fallback.model(),
                row.getApiKey() != null ? row.getApiKey() : fallback.apiKey(),
                row.isEnabled()));
    }

    private boolean currentEnabled(ConnectionName name) {
        return bindings.get(name).read().get().enabled();
    }

    private ConnectionSummary summarize(ConnectionName name, Optional<Connection> stored) {
        ConnectionValues values = bindings.get(name).read().get();
        return new ConnectionSummary(
                name,
                values.provider(),
                values.baseUrl(),
                values.model(),
                values.apiKey() != null && !values.apiKey().isBlank(),
                values.enabled(),
                stored.isPresent(),
                stored.map(Connection::getUpdatedAt).orElse(null),
                bindings.get(name).supportedProviders(),
                bindings.get(name).hasModel(),
                bindings.get(name).hasEnabledFlag());
    }

    /**
     * Rejects anything that is not an absolute http(s) URL with a host. Worth doing at the boundary:
     * a base URL that parses but has no scheme fails later as a confusing "URI is not absolute"
     * from deep inside a phase, hours after the operator typed it.
     */
    private static String validateBaseUrl(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        // A trailing slash would make UriComponentsBuilder produce a double slash on resolve.
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                throw new IllegalArgumentException(
                        "Base URL must start with http:// or https://, got: " + raw);
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("Base URL has no host: " + raw);
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Base URL is not a valid URL: " + raw, e);
        }
        return trimmed;
    }

    private static boolean isOllama(String provider) {
        return "ollama".equals(normalize(provider));
    }

    private static String normalize(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * One connection's bridge to its config bean, plus what the connection actually supports.
     * {@code hasEnabledFlag} records whether the underlying phase has a master switch — EMBEDDINGS
     * and TRANSCRIPTION do not, so an {@code enabled} in their request body is ignored rather than
     * silently written somewhere adjacent. {@code hasModel} is false for the two sidecars, whose
     * model is fixed by their own container image.
     */
    private record Binding(
            Supplier<ConnectionValues> read,
            Consumer<ConnectionValues> write,
            List<String> supportedProviders,
            boolean hasModel,
            boolean hasEnabledFlag
    ) {
    }
}
