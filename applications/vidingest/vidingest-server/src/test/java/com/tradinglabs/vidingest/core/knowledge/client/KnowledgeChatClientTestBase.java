package com.tradinglabs.vidingest.core.knowledge.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tradinglabs.vidingest.config.KnowledgeExtractionConfig;
import org.junit.jupiter.api.AfterEach;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared JDK-{@code HttpServer} scaffolding for the two {@link KnowledgeChatClient} tests.
 *
 * <p>The other sidecar client tests ({@code WhisperAsrClientTest}, {@code DiarizationClientTest},
 * {@code PaddleOcrClientTest}) each carry a private copy of these helpers, which was fine while
 * every client had exactly one test class. The knowledge clients come in a pair that must stay
 * behaviourally identical, so their fixtures are shared for the same reason
 * {@link AbstractKnowledgeChatClient} exists: a fixture improved for one path should not quietly
 * fail to exist for the other.
 *
 * <p>Subclasses supply only {@link #contextPath()} — the endpoint their protocol posts to.
 */
public abstract class KnowledgeChatClientTestBase {

    protected HttpServer server;

    /** The one path this client posts to; the fake server answers only this. */
    protected abstract String contextPath();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    protected void startServer(int status, String body) throws IOException {
        startServerWithCapture(status, body, null);
    }

    protected void startServerWithCapture(int status, String body, AtomicReference<String> capture) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(contextPath(), exchange -> handle(exchange, status, body, capture));
        server.start();
    }

    /**
     * Accept the request, promise a body in the headers, then never send one — the client blocks
     * on read until its own timeout fires.
     */
    protected void startStallingServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(contextPath(), exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 512);
        });
        server.start();
    }

    protected static void handle(HttpExchange exchange, int status, String body, AtomicReference<String> capture)
            throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        byte[] reqBytes = exchange.getRequestBody().readAllBytes();
        if (capture != null) {
            capture.set(new String(reqBytes, StandardCharsets.UTF_8));
        }
        byte[] respBytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, respBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(respBytes);
        }
    }

    protected String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    protected static KnowledgeExtractionConfig config() {
        KnowledgeExtractionConfig cfg = new KnowledgeExtractionConfig();
        cfg.setChatModel("qwen2.5:14b-instruct");
        cfg.setTemperature(0.2);
        cfg.setMaxOutputTokens(4096);
        return cfg;
    }

    protected static RestClient restClient(String baseUrl) {
        return client(baseUrl, Duration.ofSeconds(5));
    }

    /** Read timeout short enough that {@link #startStallingServer()} trips it inside a test. */
    protected static RestClient shortReadRestClient(String baseUrl) {
        return client(baseUrl, Duration.ofMillis(600));
    }

    private static RestClient client(String baseUrl, Duration readTimeout) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(2000);
        rf.setReadTimeout(Math.toIntExact(readTimeout.toMillis()));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(rf).build();
    }
}
