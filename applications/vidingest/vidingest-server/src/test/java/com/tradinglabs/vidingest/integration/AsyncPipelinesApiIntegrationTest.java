package com.tradinglabs.vidingest.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.core.download.service.VideoDownloadService;
import com.tradinglabs.vidingest.core.transcription.service.TranscriptionService;
import com.tradinglabs.vidingest.videos.domain.Video;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncPipelinesApiIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private VideoDownloadService videoDownloadService;

    @MockitoBean
    private TranscriptionService transcriptionService;

    @Test
    void createPipelineReturns202AndEventuallyCompletes() throws Exception {
        stubDownloadServiceForVideo("youtube", "abc123");

        String pipelineId = createPipeline("https://example.com/video");
        JsonNode details = awaitPipelineStatus(pipelineId, "COMPLETED", 2500);

        assertThat(details.get("id").asText()).isEqualTo(pipelineId);
        assertThat(details.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(details.get("phase").asText()).isEqualTo("DONE");
        assertThat(details.get("videoId").asText()).isNotBlank();
    }

    @Test
    void createPipelineWithTranscriptionEnabledReturns202AndEventuallyCompletes() throws Exception {
        stubDownloadServiceForVideo("youtube", "withtx001");
        doReturn(null).when(transcriptionService).transcribe(any(Video.class));

        String pipelineId = createPipeline("https://example.com/video", false);
        JsonNode details = awaitPipelineStatus(pipelineId, "COMPLETED", 2500);

        assertThat(details.get("id").asText()).isEqualTo(pipelineId);
        assertThat(details.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(details.get("phase").asText()).isEqualTo("DONE");

        verify(transcriptionService, times(1)).transcribe(any(Video.class));
    }

    @Test
    void duplicateVideoCancelsSecondPipelineRun() throws Exception {
        stubDownloadServiceForVideo("youtube", "dup001");

        String firstPipelineId = createPipeline("https://example.com/video");
        awaitPipelineStatus(firstPipelineId, "COMPLETED", 2500);

        String secondPipelineId = createPipeline("https://example.com/video");
        JsonNode details = awaitPipelineStatus(secondPipelineId, "CANCELLED", 2500);

        assertThat(details.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(details.get("errorCode").asText()).isEqualTo("DUPLICATE_VIDEO");
    }

    @Test
    void retryReturns409WhenPipelineIsNotFailed() throws Exception {
        stubDownloadServiceForVideo("youtube", "retry409");

        String pipelineId = createPipeline("https://example.com/video");
        awaitPipelineStatus(pipelineId, "COMPLETED", 2500);

        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/vidingest/api/v1/pipelines/" + pipelineId + "/retry");
        String body = """
                {"skipPhases":["TRANSCRIBE","CONTEXT","DIARIZE","FRAME_SAMPLE","OCR","KNOWLEDGE"]}
                """;

        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(409);

        JsonNode json = objectMapper.readTree(res.body());
        assertThat(json.get("title").asText()).isEqualTo("Conflict");
    }

    @Test
    void createBatchPartiallyAcceptsInvalidUrls() throws Exception {
        stubDownloadServiceForVideo("youtube", "batch001");

        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/vidingest/api/v1/pipelines");
        String body = """
                {"urls":["https://example.com/video","ftp://invalid"],"skipPhases":["TRANSCRIBE","CONTEXT","DIARIZE","FRAME_SAMPLE","OCR","KNOWLEDGE"]}
                """;

        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(202);

        JsonNode json = objectMapper.readTree(res.body());
        assertThat(json.get("runId").asText()).isNotBlank();
        assertThat(json.get("items")).hasSize(2);
        assertThat(json.get("items").get(0).get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(json.get("items").get(1).get("status").asText()).isEqualTo("REJECTED");
        assertThat(json.get("items").get(1).get("url").asText()).isEqualTo("ftp://invalid");
    }

    private void stubDownloadServiceForVideo(String extractor, String id) throws Exception {
        Map<String, Object> metadata = Map.of(
                "extractor", extractor,
                "id", id,
                "title", "Title",
                "description", "Desc",
                "channel", "Channel",
                "duration", 123,
                "upload_date", "20260429"
        );

        when(videoDownloadService.extractMetadata(anyString())).thenReturn(metadata);
        when(videoDownloadService.downloadVideoToDisk(anyString(), any(Map.class))).thenReturn("/tmp/20260429.Title.mp4");
    }

    private String createPipeline(String url) throws Exception {
        return createPipeline(url, true);
    }

    private String createPipeline(String url, boolean skipTranscription) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/vidingest/api/v1/pipelines");
        String body = """
                {"urls":["%s"],"skipPhases":[%s"CONTEXT","DIARIZE","FRAME_SAMPLE","OCR","KNOWLEDGE"]}
                """.formatted(url, skipTranscription ? "\"TRANSCRIBE\"," : "");

        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(202);

        JsonNode json = objectMapper.readTree(res.body());
        assertThat(json.get("runId").asText()).isNotBlank();
        return json.get("runId").asText();
    }

    private JsonNode awaitPipelineStatus(String pipelineId, String expectedStatus, long timeoutMillis) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/vidingest/api/v1/pipelines/" + pipelineId);

        AtomicReference<JsonNode> last = new AtomicReference<>();
        await()
                .atMost(Duration.ofMillis(timeoutMillis))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    HttpRequest req = HttpRequest.newBuilder(uri).GET().build();
                    HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                    assertThat(res.statusCode()).isEqualTo(200);
                    JsonNode node = objectMapper.readTree(res.body());
                    last.set(node);
                    assertThat(node.get("status").asText()).isEqualTo(expectedStatus);
                });
        return last.get();
    }
}

