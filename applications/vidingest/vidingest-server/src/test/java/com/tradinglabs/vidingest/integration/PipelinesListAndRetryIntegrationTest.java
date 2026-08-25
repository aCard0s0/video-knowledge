package com.tradinglabs.vidingest.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.core.download.service.VideoDownloadService;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import com.tradinglabs.vidingest.core.transcription.service.TranscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class PipelinesListAndRetryIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private VideoDownloadService videoDownloadService;

    @MockitoBean
    private TranscriptionService transcriptionService;

    @Test
    void listReturnsAllRunsAndCanFilterByFailed() throws Exception {
        PipelineRun failed = pipelineRunRepository.saveAndFlush(PipelineRun.builder()
                .status(RunStatus.FAILED)
                .videoUrl("https://example.com/failed")
                .error("boom")
                .build());
        PipelineRun completed = pipelineRunRepository.saveAndFlush(PipelineRun.builder()
                .status(RunStatus.COMPLETED)
                .videoUrl("https://example.com/completed")
                .build());

        HttpClient client = HttpClient.newHttpClient();

        URI allUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/pipelines?status=ALL&page=0&size=50");
        HttpResponse<String> allRes = client.send(
                HttpRequest.newBuilder(allUri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(allRes.statusCode()).isEqualTo(200);
        JsonNode all = objectMapper.readTree(allRes.body());
        assertThat(all.get("items").size()).isEqualTo(2);
        List<String> ids = List.of(
                all.get("items").get(0).get("id").asText(),
                all.get("items").get(1).get("id").asText()
        );
        assertThat(ids).contains(failed.getId().toString(), completed.getId().toString());

        URI failedUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/pipelines?status=FAILED&page=0&size=50");
        HttpResponse<String> failedRes = client.send(
                HttpRequest.newBuilder(failedUri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(failedRes.statusCode()).isEqualTo(200);
        JsonNode onlyFailed = objectMapper.readTree(failedRes.body());
        assertThat(onlyFailed.get("items").size()).isEqualTo(1);
        assertThat(onlyFailed.get("items").get(0).get("id").asText()).isEqualTo(failed.getId().toString());
        assertThat(onlyFailed.get("items").get(0).get("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void retryHappyPathEventuallyCompletesAfterInitialFailure() throws Exception {
        // First attempt fails.
        when(videoDownloadService.extractMetadata(anyString())).thenThrow(new RuntimeException("boom"));

        HttpClient client = HttpClient.newHttpClient();
        URI createUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/pipelines");
        String createBody = """
                {"urls":["https://example.com/video"],"skipPhases":["TRANSCRIBE","CONTEXT","DIARIZE","FRAME_SAMPLE","OCR","KNOWLEDGE"]}
                """;
        HttpResponse<String> createRes = client.send(
                HttpRequest.newBuilder(createUri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(createBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(createRes.statusCode()).isEqualTo(202);
        String runId = objectMapper.readTree(createRes.body()).get("runId").asText();
        assertThat(runId).isNotBlank();

        awaitRunStatus(runId, "FAILED", Duration.ofSeconds(5));

        // Retry succeeds.
        Map<String, Object> metadata = Map.of(
                "extractor", "youtube",
                "id", "retry-ok-001",
                "title", "Title",
                "description", "Desc",
                "channel", "Channel",
                "duration", 123,
                "upload_date", "20260429"
        );
        doReturn(metadata).when(videoDownloadService).extractMetadata(anyString());
        doReturn("/tmp/20260429.Title.mp4").when(videoDownloadService).downloadVideoToDisk(anyString(), any(Map.class));
        doReturn(null).when(transcriptionService).transcribe(any());

        URI retryUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/pipelines/" + runId + "/retry");
        String retryBody = """
                {"skipPhases":["TRANSCRIBE","CONTEXT","DIARIZE","FRAME_SAMPLE","OCR","KNOWLEDGE"]}
                """;
        HttpResponse<String> retryRes = client.send(
                HttpRequest.newBuilder(retryUri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(retryBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(retryRes.statusCode()).isEqualTo(202);
        assertThat(objectMapper.readTree(retryRes.body()).get("runId").asText()).isEqualTo(runId);

        awaitRunStatus(runId, "COMPLETED", Duration.ofSeconds(8));
    }

    @Test
    void retryItemHappyPathEventuallyCompletesAfterInitialFailure() throws Exception {
        // First attempt fails.
        when(videoDownloadService.extractMetadata(anyString())).thenThrow(new RuntimeException("boom"));

        HttpClient client = HttpClient.newHttpClient();
        URI createUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/pipelines");
        String createBody = """
                {"urls":["https://example.com/video"],"skipPhases":["TRANSCRIBE","CONTEXT","DIARIZE","FRAME_SAMPLE","OCR","KNOWLEDGE"]}
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
        assertThat(runId).isNotBlank();
        String itemId = created.get("items").get(0).get("itemId").asText();
        assertThat(itemId).isNotBlank();

        awaitRunStatus(runId, "FAILED", Duration.ofSeconds(5));

        // Retry succeeds.
        Map<String, Object> metadata = Map.of(
                "extractor", "youtube",
                "id", "retry-item-ok-001",
                "title", "Title",
                "description", "Desc",
                "channel", "Channel",
                "duration", 123,
                "upload_date", "20260429"
        );
        doReturn(metadata).when(videoDownloadService).extractMetadata(anyString());
        doReturn("/tmp/20260429.Title.mp4").when(videoDownloadService).downloadVideoToDisk(anyString(), any(Map.class));
        doReturn(null).when(transcriptionService).transcribe(any());

        URI retryItemUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/pipelines/" + runId + "/items/" + itemId + "/retry");
        String retryBody = """
                {"skipPhases":["TRANSCRIBE","CONTEXT","DIARIZE","FRAME_SAMPLE","OCR","KNOWLEDGE"]}
                """;
        HttpResponse<String> retryRes = client.send(
                HttpRequest.newBuilder(retryItemUri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(retryBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(retryRes.statusCode()).isEqualTo(202);
        assertThat(objectMapper.readTree(retryRes.body()).get("runId").asText()).isEqualTo(runId);

        awaitRunStatus(runId, "COMPLETED", Duration.ofSeconds(8));
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

