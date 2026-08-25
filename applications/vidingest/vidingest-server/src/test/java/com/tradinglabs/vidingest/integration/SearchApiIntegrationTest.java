package com.tradinglabs.vidingest.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SearchApiIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void searchReturns409WhenSemanticSearchDisabled() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/vidingest/api/v1/search?query=support%20zone&limit=5");
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(res.statusCode()).isEqualTo(409);

        JsonNode json = objectMapper.readTree(res.body());
        assertThat(json.get("title").asText()).isEqualTo("Conflict");
        assertThat(json.get("detail").asText()).contains("Semantic search is disabled");
        assertThat(json.get("instance").asText()).isEqualTo("/vidingest/api/v1/search");
    }
}

