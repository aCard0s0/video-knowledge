package com.tradinglabs.vidingest.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration for semantic search behavior.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "vidingest.search")
public class VideoSearchConfig {

    /**
     * Enables semantic search using pgvector.
     * A query embedding provider must also be available.
     */
    private boolean semanticEnabled = false;

    /**
     * Configuration for embeddings (query embeddings + context chunk embeddings).
     */
    private Embeddings embeddings = new Embeddings();

    @Getter
    @Setter
    public static class Embeddings {
        /**
         * Embeddings provider selector.
         * Supported values: ollama, openai-compatible
         */
        private String provider = "ollama";

        /**
         * Base URL of an OpenAI-compatible server, typically ending with /v1.
         * Example: http://localhost:1234/v1 (LM Studio).
         */
        private String baseUrl = "";

        /**
         * Embedding model name passed to the OpenAI /embeddings endpoint.
         */
        private String model = "text-embedding-3-small";

        /**
         * Optional API key; if set, will be sent as Authorization: Bearer <apiKey>.
         */
        private String apiKey = "";

        /**
         * Read timeout for embeddings calls. The Ollama embedding model can take
         * 30s–2min on first call after container restart (cold-start of the 1.5B
         * params model loading weights into RAM/VRAM), so default to a generous
         * window. Connect timeout is fixed at 5s.
         */
        private Duration timeout = Duration.ofSeconds(180);

        /**
         * Expected embedding vector dimension. Must match the pgvector column definition.
         * Default schema uses VECTOR(1536).
         */
        private int expectedDimensions = 1536;

        /**
         * Configuration for Ollama embeddings.
         */
        private Ollama ollama = new Ollama();

        @Getter
        @Setter
        public static class Ollama {
            /**
             * Base URL of an Ollama server.
             * Example (docker): http://ollama:11434
             * Example (host): http://localhost:11434
             */
            private String baseUrl = "";

            /**
             * Ollama embedding model name.
             * Example: rjmalagon/gte-qwen2-1.5b-instruct-embed-f16
             */
            private String embedModel = "rjmalagon/gte-qwen2-1.5b-instruct-embed-f16";

            /**
             * When true, Ollama truncates inputs that exceed the context window.
             */
            private boolean truncate = true;
        }
    }
}

