package com.tradinglabs.vidingest.search.service.embedding;

import com.tradinglabs.vidingest.config.VideoSearchConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DisabledEmbeddingsClient implements EmbeddingsClient {

    private final VideoSearchConfig searchConfig;

    @Override
    public List<float[]> embed(List<String> inputs) throws IOException {
        throw missingConfig();
    }

    @Override
    public float[] embedOne(String input) throws IOException {
        throw missingConfig();
    }

    private IOException missingConfig() {
        String provider = searchConfig != null && searchConfig.getEmbeddings() != null
                ? searchConfig.getEmbeddings().getProvider()
                : "<unknown>";

        log.debug("Embeddings client is disabled (provider={})", provider);
        return new IOException(
                "Embeddings client is disabled. Configure vidingest.search.embeddings.provider and its provider-specific settings.");
    }
}

