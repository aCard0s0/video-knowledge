package com.tradinglabs.vidingest.core.knowledge.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.config.KnowledgeExtractionConfig;
import com.tradinglabs.vidingest.core.knowledge.dto.KnowledgeUnitDraft;
import com.tradinglabs.vidingest.core.knowledge.service.KnowledgeExtractionFailureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The half of a {@link KnowledgeChatClient} that does not depend on which runtime answers: post
 * the request, turn a transport failure into a message that names what actually went wrong, and
 * hand the model's inner JSON to {@link KnowledgeUnitJson}.
 *
 * <p>Subclasses supply only what genuinely differs between wire protocols — the URI, the request
 * body, and where in the response envelope the content string sits. Everything else was copied
 * between the two clients before this class existed, which is the same drift the shared parser
 * exists to prevent: a timeout message improved on one path would silently not exist on the other.
 */
@Slf4j
public abstract class AbstractKnowledgeChatClient implements KnowledgeChatClient {

    protected final ObjectMapper objectMapper;
    protected final KnowledgeExtractionConfig config;
    private final RestClient restClient;

    protected AbstractKnowledgeChatClient(ObjectMapper objectMapper,
                                          KnowledgeExtractionConfig config,
                                          RestClient restClient) {
        this.objectMapper = objectMapper;
        this.config = config;
        this.restClient = restClient;
    }

    /** Value of {@code vidingest.knowledge.provider} this client serves; used in log lines. */
    protected abstract String providerName();

    /** Path appended to the configured base URL. */
    protected abstract String uri();

    /**
     * Resolved against {@code config.getBaseUrl()} on every call, so that repointing the
     * connection at runtime takes effect without a restart. {@code UriComponentsBuilder} rather
     * than concatenation because the base usually carries a path of its own ({@code /v1}) and a
     * trailing slash must not produce a double one.
     */
    private URI absoluteUri() {
        String baseUrl = config.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new KnowledgeExtractionFailureException(
                    "No knowledge chat base URL configured (vidingest.knowledge.base-url)");
        }
        return UriComponentsBuilder.fromUriString(baseUrl.trim()).path(uri()).build().toUri();
    }

    /** Where the content string sits in the envelope, for the "missing content" failure message. */
    protected abstract String contentPath();

    /** Navigate the parsed envelope to the node named by {@link #contentPath()}. */
    protected abstract JsonNode contentNode(JsonNode envelope);

    protected abstract Map<String, Object> buildRequestBody(String systemPrompt, String userPrompt);

    @Override
    public final List<KnowledgeUnitDraft> extract(String systemPrompt, String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return List.of();
        }
        long startNs = System.nanoTime();
        String provider = providerName();

        Map<String, Object> body = buildRequestBody(systemPrompt, userPrompt);
        log.info("Knowledge extraction LLM request: provider={}, model={}, inputChars={}, format=json-schema",
                provider, config.getChatModel(), userPrompt.length());

        String raw;
        try {
            byte[] bytes = post(body);
            raw = bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        } catch (RestClientResponseException e) {
            log.warn("Knowledge LLM request failed: provider={}, model={}, httpStatus={}, elapsedMs={}",
                    provider, config.getChatModel(), e.getStatusCode().value(), KnowledgeUnitJson.elapsedMs(startNs));
            throw new KnowledgeExtractionFailureException(
                    "Knowledge LLM returned HTTP " + e.getStatusCode().value() + ": "
                            + KnowledgeUnitJson.truncate(e.getResponseBodyAsString(), 500), e);
        } catch (RestClientException e) {
            // A read timeout reaches this branch wrapped by the message converter, so
            // e.getMessage() reads "Error while extracting response for type [byte[]] and content
            // type [application/octet-stream]" — a decoding complaint for what is actually the
            // clock running out, which sends the reader looking at content negotiation. Say what
            // happened and name the knob that bounds it.
            if (KnowledgeUnitJson.isTimeout(e)) {
                log.warn("Knowledge LLM timed out: provider={}, model={}, elapsedMs={}, readTimeout={}",
                        provider, config.getChatModel(), KnowledgeUnitJson.elapsedMs(startNs), config.getReadTimeout());
                throw new KnowledgeExtractionFailureException(
                        "Knowledge LLM timed out after " + config.getReadTimeout()
                                + " (vidingest.knowledge.read-timeout), model " + config.getChatModel(), e);
            }
            log.warn("Knowledge LLM request failed: provider={}, model={}, elapsedMs={}, message={}",
                    provider, config.getChatModel(), KnowledgeUnitJson.elapsedMs(startNs), e.getMessage());
            throw new KnowledgeExtractionFailureException("Knowledge LLM request failed: " + e.getMessage(), e);
        }

        if (raw == null || raw.isBlank()) {
            throw new KnowledgeExtractionFailureException("Knowledge LLM returned an empty response");
        }

        List<KnowledgeUnitDraft> drafts = parseDrafts(raw);
        log.info("Knowledge extraction LLM response: provider={}, model={}, elapsedMs={}, units={}",
                provider, config.getChatModel(), KnowledgeUnitJson.elapsedMs(startNs), drafts.size());
        return drafts;
    }

    /**
     * Read as {@code byte[]} then UTF-8 decode rather than {@code .body(String.class)}: a server
     * (or a proxy in front of it) answering {@code Content-Type: application/octet-stream} makes
     * Spring's default {@code StringHttpMessageConverter} refuse the body, and bytes bypass
     * content-type negotiation entirely. Mirrors the same fix applied to
     * {@code OllamaEmbeddingsClient}.
     *
     * <p>The bearer header is sent whenever an api-key is configured, whatever the provider —
     * Ollama has no auth of its own but is often fronted by a proxy that does.
     */
    private byte[] post(Map<String, Object> body) {
        RestClient.RequestBodySpec spec = restClient.post()
                .uri(absoluteUri())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);

        String apiKey = config.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            spec = spec.header("Authorization", "Bearer " + apiKey.trim());
        }
        return spec.body(body).retrieve().body(byte[].class);
    }

    /**
     * Two layers of JSON: the outer provider envelope, and the inner model-generated string, which
     * is itself JSON because the request constrained it to a schema.
     */
    private List<KnowledgeUnitDraft> parseDrafts(String raw) {
        try {
            JsonNode contentNode = contentNode(objectMapper.readTree(raw));
            if (contentNode.isMissingNode() || contentNode.isNull()) {
                throw new KnowledgeExtractionFailureException(
                        "Knowledge LLM response missing " + contentPath());
            }
            String content = contentNode.asText();
            if (content == null || content.isBlank()) {
                return List.of();
            }
            return KnowledgeUnitJson.parseUnitsArray(objectMapper, content);
        } catch (IOException e) {
            throw new KnowledgeExtractionFailureException("Failed to parse Knowledge LLM envelope: " + e.getMessage(), e);
        }
    }
}
