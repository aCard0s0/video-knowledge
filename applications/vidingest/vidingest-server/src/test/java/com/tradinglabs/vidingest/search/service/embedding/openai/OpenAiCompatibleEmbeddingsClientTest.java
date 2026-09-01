package com.tradinglabs.vidingest.search.service.embedding.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tradinglabs.vidingest.config.VideoSearchConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the request body, which is the part of this client with a rule in it.
 *
 * <p>Mirrors the sidecar client tests: a real {@link HttpServer} on port 0, so what is asserted is
 * the JSON that actually went on the wire rather than a mock's idea of it.
 */
class OpenAiCompatibleEmbeddingsClientTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void omitsDimensionsWhenUnset() throws Exception {
        startServer(1536);

        client(config(null)).embedOne("hello");

        JsonNode body = mapper.readTree(lastBody.get());
        // Not merely present-and-null: the field must not appear at all. `dimensions` is optional in
        // the OpenAI spec and some compatible servers reject an unknown field outright, so an unset
        // property has to leave the body byte-identical to what it was before this field existed.
        assertThat(body.has("dimensions")).isFalse();
        assertThat(body.get("model").asText()).isEqualTo("test-model");
        assertThat(body.get("encoding_format").asText()).isEqualTo("float");
    }

    @Test
    void sendsDimensionsWhenSet() throws Exception {
        startServer(1536);

        client(config(1536)).embedOne("hello");

        assertThat(mapper.readTree(lastBody.get()).get("dimensions").asInt()).isEqualTo(1536);
    }

    @Test
    void rejectsAVectorThatIsNotTheWidthTheColumnExpects() throws Exception {
        // The reason for asking in the first place: a server that ignores `dimensions`, or a model
        // whose native width differs, has to fail here rather than at the pgvector insert.
        startServer(2560);

        assertThatThrownBy(() -> client(config(1536)).embedOne("hello"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("1536");
    }

    private static VideoSearchConfig.Embeddings config(Integer dimensions) {
        VideoSearchConfig.Embeddings cfg = new VideoSearchConfig.Embeddings();
        cfg.setProvider("openai-compatible");
        cfg.setModel("test-model");
        cfg.setExpectedDimensions(1536);
        cfg.setDimensions(dimensions);
        return cfg;
    }

    private OpenAiCompatibleEmbeddingsClient client(VideoSearchConfig.Embeddings embeddings) {
        embeddings.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        VideoSearchConfig searchConfig = new VideoSearchConfig();
        searchConfig.setEmbeddings(embeddings);
        return new OpenAiCompatibleEmbeddingsClient(searchConfig);
    }

    private void startServer(int dimensions) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/embeddings", exchange -> respond(exchange, dimensions));
        server.start();
    }

    private void respond(HttpExchange exchange, int dimensions) throws IOException {
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        String vector = IntStream.range(0, dimensions).mapToObj(i -> "0.01").collect(Collectors.joining(","));
        byte[] bytes = ("{\"data\":[{\"embedding\":[" + vector + "],\"index\":0}]}")
                .getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
