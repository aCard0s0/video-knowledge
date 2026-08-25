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

class ReadinessAndOpenApiIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void readinessEndpointReturnsReadyTrue() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/vidingest/api/v1/health/ready");
        HttpRequest req = HttpRequest.newBuilder(uri).GET().build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(res.body());
        assertThat(json.get("ready").asBoolean()).isTrue();
        assertThat(json.get("checks").get("db").asText()).contains("ok");
    }

    @Test
    void openApiDocsEndpointIsServedUnderContextPath() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/vidingest/v3/api-docs");
        HttpRequest req = HttpRequest.newBuilder(uri).GET().build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(res.body());
        assertThat(json.get("openapi").asText()).isNotBlank();
        assertThat(json.get("paths")).isNotNull();

        JsonNode paths = json.get("paths");
        assertHasJsonResponse(paths, "/api/v1/health/ready", "get", "200");
        assertHasJsonResponse(paths, "/api/v1/videos", "get", "200");
        assertHasJsonResponse(paths, "/api/v1/pipelines", "get", "200");
        assertHasJsonResponse(paths, "/api/v1/pipelines/{runId}/items/{itemId}/retry", "post", "202");
        assertHasJsonResponse(paths, "/api/v1/youtube/channels", "get", "200");
    }

    @Test
    void unknownJobsEndpointReturnsNotFoundProblemDetail() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/vidingest/api/v1/jobs");
        HttpRequest req = HttpRequest.newBuilder(uri).GET().build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(404);

        JsonNode json = objectMapper.readTree(res.body());
        assertThat(json.get("status").asInt()).isEqualTo(404);
        assertThat(json.get("title").asText()).isEqualTo("Not found");
        assertThat(json.get("detail").asText()).contains("No static resource");
        assertThat(json.get("instance").asText()).isEqualTo("/vidingest/api/v1/jobs");
    }

    private static void assertHasJsonResponse(JsonNode paths, String path, String method, String responseCode) {
        JsonNode content = paths
                .path(path)
                .path(method)
                .path("responses")
                .path(responseCode)
                .path("content");

        assertThat(content.isMissingNode())
                .as("OpenAPI content node missing for %s %s (%s)", method.toUpperCase(), path, responseCode)
                .isFalse();

        assertThat(content.has("application/json"))
                .as("Expected application/json content for %s %s (%s), but got: %s",
                        method.toUpperCase(), path, responseCode, content.fieldNames())
                .isTrue();

        assertThat(content.has("*/*"))
                .as("Did not expect */* content for %s %s (%s)", method.toUpperCase(), path, responseCode)
                .isFalse();
    }
}

