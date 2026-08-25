package com.tradinglabs.vidingest.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.core.download.service.VideoDownloadService;
import com.tradinglabs.vidingest.core.transcription.service.TranscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

class PipelineAuditApiIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private VideoDownloadService videoDownloadService;

    @MockitoBean
    private TranscriptionService transcriptionService;

    @Test
    void auditTimelinePreservesFailureAfterRetry() throws Exception {
        when(videoDownloadService.extractMetadata(anyString())).thenThrow(new RuntimeException("boom"));

        HttpClient client = HttpClient.newHttpClient();
        URI createUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/pipelines");
        String createBody = """
                {"urls":["https://example.com/video"],"skipTranscription":true,"skipContext":true,"skipDiarize":true,"skipFrames":true,"skipOcr":true,"skipKnowledge":true}
                """;
        HttpResponse<String> createRes = client.send(
                HttpRequest.newBuilder(createUri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(createBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(createRes.statusCode()).isEqualTo(202);
        JsonNode created = objectMapper.readTree(createRes.body());
        String runId = created.get("runId").asText();
        String itemId = created.get("items").get(0).get("itemId").asText();

        awaitRunStatus(runId, "FAILED", Duration.ofSeconds(5));

        // Audit after failure
        JsonNode failedAudit = fetchItemAudit(runId, itemId);
        Set<String> typesAfterFailure = collectEventTypes(failedAudit);
        assertThat(typesAfterFailure).contains("ITEM_CREATED", "ITEM_FAILED");

        JsonNode firstFailedEvent = findFirstEventOfType(failedAudit, "ITEM_FAILED");
        assertThat(firstFailedEvent).isNotNull();
        assertThat(firstFailedEvent.get("error").asText()).contains("boom");
        assertThat(firstFailedEvent.get("attempt").asInt()).isEqualTo(1);

        // Make retry succeed
        Map<String, Object> metadata = Map.of(
                "extractor", "youtube",
                "id", "audit-ok-001",
                "title", "Title",
                "description", "Desc",
                "channel", "Channel",
                "duration", 123,
                "upload_date", "20260429"
        );
        doReturn(metadata).when(videoDownloadService).extractMetadata(anyString());
        doReturn("/tmp/20260429.Title.mp4").when(videoDownloadService).downloadVideoToDisk(anyString(), any(Map.class));
        doReturn(null).when(transcriptionService).transcribe(any());

        URI retryUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/pipelines/" + runId + "/items/" + itemId + "/retry");
        String retryBody = """
                {"skipTranscription":true,"skipContext":true,"skipDiarize":true,"skipFrames":true,"skipOcr":true,"skipKnowledge":true}
                """;
        HttpResponse<String> retryRes = client.send(
                HttpRequest.newBuilder(retryUri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(retryBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(retryRes.statusCode()).isEqualTo(202);

        awaitRunStatus(runId, "COMPLETED", Duration.ofSeconds(8));

        // Audit after successful retry: prior failure preserved, retry event present, attempt 2.
        JsonNode finalAudit = fetchItemAudit(runId, itemId);
        Set<String> finalTypes = collectEventTypes(finalAudit);
        assertThat(finalTypes).contains(
                "ITEM_CREATED",
                "ITEM_FAILED",
                "ITEM_RETRY_REQUESTED",
                "ITEM_COMPLETED"
        );

        // ITEM_FAILED from before retry still has its error message.
        JsonNode preservedFailure = findFirstEventOfType(finalAudit, "ITEM_FAILED");
        assertThat(preservedFailure).isNotNull();
        assertThat(preservedFailure.get("error").asText()).contains("boom");

        // Retry event carries the snapshot of the prior error.
        JsonNode retryEvent = findFirstEventOfType(finalAudit, "ITEM_RETRY_REQUESTED");
        assertThat(retryEvent).isNotNull();
        assertThat(retryEvent.get("attempt").asInt()).isEqualTo(2);
        assertThat(retryEvent.get("error").asText()).contains("boom");

        // Run-level audit returns the same events.
        JsonNode runAudit = fetchRunAudit(runId);
        assertThat(runAudit.get("items").size()).isEqualTo(finalAudit.get("items").size());
    }

    private JsonNode fetchItemAudit(String runId, String itemId) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/vidingest/api/v1/pipelines/" + runId + "/items/" + itemId + "/audit?page=0&size=200");
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(res.statusCode()).isEqualTo(200);
        return objectMapper.readTree(res.body());
    }

    private JsonNode fetchRunAudit(String runId) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/vidingest/api/v1/pipelines/" + runId + "/audit?page=0&size=200");
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(res.statusCode()).isEqualTo(200);
        return objectMapper.readTree(res.body());
    }

    private Set<String> collectEventTypes(JsonNode auditPage) {
        Set<String> types = new HashSet<>();
        JsonNode items = auditPage.get("items");
        for (int i = 0; i < items.size(); i++) {
            types.add(items.get(i).get("eventType").asText());
        }
        return types;
    }

    private JsonNode findFirstEventOfType(JsonNode auditPage, String type) {
        JsonNode items = auditPage.get("items");
        for (int i = 0; i < items.size(); i++) {
            if (type.equals(items.get(i).get("eventType").asText())) {
                return items.get(i);
            }
        }
        return null;
    }

    private void awaitRunStatus(String runId, String expectedStatus, Duration timeout) {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/vidingest/api/v1/pipelines/" + runId);
        AtomicReference<JsonNode> last = new AtomicReference<>();
        await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    HttpResponse<String> res = client.send(
                            HttpRequest.newBuilder(uri).GET().build(),
                            HttpResponse.BodyHandlers.ofString()
                    );
                    assertThat(res.statusCode()).isEqualTo(200);
                    JsonNode node = objectMapper.readTree(res.body());
                    last.set(node);
                    assertThat(node.get("status").asText()).isEqualTo(expectedStatus);
                });
        assertThat(last.get()).isNotNull();
    }
}
