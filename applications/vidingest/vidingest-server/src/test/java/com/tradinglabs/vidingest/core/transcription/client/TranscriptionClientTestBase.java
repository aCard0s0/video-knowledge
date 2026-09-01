package com.tradinglabs.vidingest.core.transcription.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tradinglabs.vidingest.config.TranscriptionClientProperties;
import org.junit.jupiter.api.AfterEach;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared harness for the two transcription clients: a real {@link HttpServer} on port 0 answering
 * a canned body, so the multipart encoding and the response parsing are exercised end to end
 * rather than mocked.
 *
 * <p>Mirrors {@code KnowledgeChatClientTestBase}, and for the same reason: the two clients differ
 * only in the request they build, so anything they share belongs in one place where a fix to one
 * cannot fail to reach the other.
 */
public abstract class TranscriptionClientTestBase {

    private HttpServer server;

    /** The last request body the server received, so a test can assert on the multipart parts. */
    protected final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    protected void startServer(String path, int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(path, exchange -> handle(exchange, status, body));
        server.start();
    }

    /** Register a second path on the same server — used to prove the router switches between them. */
    protected HttpServer server() {
        return server;
    }

    protected void respond(HttpExchange exchange, String body) throws IOException {
        handle(exchange, 200, body);
    }

    private void handle(HttpExchange exchange, int status, String body) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    protected String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    protected TranscriptionClientProperties properties(String provider) {
        TranscriptionClientProperties properties = new TranscriptionClientProperties();
        properties.setProvider(provider);
        properties.setBaseUrl(baseUrl());
        return properties;
    }

    /**
     * No {@code .baseUrl(...)} — matches the production bean, which leaves the base to the client
     * so a settings change takes effect without recreating the transport.
     */
    protected static RestClient restClient() {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Math.toIntExact(Duration.ofSeconds(2).toMillis()));
        rf.setReadTimeout(Math.toIntExact(Duration.ofSeconds(5).toMillis()));
        return RestClient.builder().requestFactory(rf).build();
    }

    protected static Path tempWav() throws IOException {
        Path wav = Files.createTempFile("vidingest-test", ".wav");
        Files.write(wav, "fake".getBytes(StandardCharsets.UTF_8));
        return wav;
    }
}
