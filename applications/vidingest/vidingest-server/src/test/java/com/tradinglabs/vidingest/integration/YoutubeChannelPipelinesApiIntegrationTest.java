package com.tradinglabs.vidingest.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunResponse;
import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunResponse.ItemResult;
import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunResponse.ItemStatus;
import com.tradinglabs.vidingest.pipeline.service.PipelineIntakeService;
import com.tradinglabs.vidingest.youtube.discovery.YoutubeChannelDiscoveryResult;
import com.tradinglabs.vidingest.youtube.discovery.YoutubeChannelDiscoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YoutubeChannelPipelinesApiIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private YoutubeChannelDiscoveryService youtubeChannelDiscoveryService;

    @MockitoBean
    private PipelineIntakeService pipelineIntakeService;

    @Test
    void createPipelineRunExpandsSelectedVideosToWatchUrlsAndCallsIntakeService() throws Exception {
        when(youtubeChannelDiscoveryService.discover(anyString(), anyInt(), anyLong()))
                .thenReturn(new YoutubeChannelDiscoveryResult(
                        "https://www.youtube.com/@example",
                        "UC_TEST",
                        "Example Channel",
                        Map.of("channel_id", "UC_TEST"),
                        List.of(
                                new YoutubeChannelDiscoveryResult.YoutubeVideoCandidate(
                                        "vid001",
                                        "Video 1",
                                        OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                                        "https://www.youtube.com/watch?v=vid001",
                                        Map.of("id", "vid001", "title", "Video 1")
                                ),
                                new YoutubeChannelDiscoveryResult.YoutubeVideoCandidate(
                                        "vid002",
                                        "Video 2",
                                        null,
                                        "https://www.youtube.com/watch?v=vid002",
                                        Map.of("id", "vid002", "title", "Video 2")
                                )
                        )
                ));

        List<String> expectedUrls = List.of(
                "https://www.youtube.com/watch?v=vid001",
                "https://www.youtube.com/watch?v=vid002"
        );
        when(pipelineIntakeService.intake(eq(expectedUrls), anySet()))
                .thenReturn(new CreatePipelineRunResponse(
                        "run-123",
                        List.of(
                                new ItemResult(expectedUrls.get(0), ItemStatus.ACCEPTED, "item-1", null),
                                new ItemResult(expectedUrls.get(1), ItemStatus.ACCEPTED, "item-2", null)
                        )
                ));

        HttpClient client = HttpClient.newHttpClient();

        URI createUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/youtube/channels");
        String createBody = """
                {"url":"https://www.youtube.com/@example","displayName":"Example"}
                """;
        HttpResponse<String> createRes = client.send(
                HttpRequest.newBuilder(createUri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(createBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(createRes.statusCode()).isEqualTo(201);
        JsonNode created = objectMapper.readTree(createRes.body());
        String channelId = created.get("id").asText();
        assertThat(channelId).isNotBlank();

        URI syncUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/youtube/channels/" + channelId + "/sync");
        HttpResponse<String> syncRes = client.send(
                HttpRequest.newBuilder(syncUri)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(syncRes.statusCode()).isEqualTo(200);

        URI pipelinesUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/youtube/channels/" + channelId + "/pipelines");
        String pipelinesBody = """
                {"youtubeVideoIds":["vid001","vid002"],"skipPhases":["TRANSCRIBE","DIARIZE","FRAME_SAMPLE","OCR","KNOWLEDGE"]}
                """;
        HttpResponse<String> pipelinesRes = client.send(
                HttpRequest.newBuilder(pipelinesUri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(pipelinesBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(pipelinesRes.statusCode()).isEqualTo(202);

        JsonNode res = objectMapper.readTree(pipelinesRes.body());
        assertThat(res.get("runId").asText()).isEqualTo("run-123");
        assertThat(res.get("items").size()).isEqualTo(2);

        verify(pipelineIntakeService).intake(eq(expectedUrls), anySet());
    }

    @Test
    void createPipelineRunReturns400ForUnknownYoutubeVideoId() throws Exception {
        when(youtubeChannelDiscoveryService.discover(anyString(), anyInt(), anyLong()))
                .thenReturn(new YoutubeChannelDiscoveryResult(
                        "https://www.youtube.com/@example",
                        "UC_TEST",
                        "Example Channel",
                        Map.of("channel_id", "UC_TEST"),
                        List.of(
                                new YoutubeChannelDiscoveryResult.YoutubeVideoCandidate(
                                        "vid001",
                                        "Video 1",
                                        OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                                        "https://www.youtube.com/watch?v=vid001",
                                        Map.of("id", "vid001", "title", "Video 1")
                                )
                        )
                ));

        HttpClient client = HttpClient.newHttpClient();

        URI createUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/youtube/channels");
        String createBody = """
                {"url":"https://www.youtube.com/@example","displayName":"Example"}
                """;
        HttpResponse<String> createRes = client.send(
                HttpRequest.newBuilder(createUri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(createBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        String channelId = objectMapper.readTree(createRes.body()).get("id").asText();

        URI syncUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/youtube/channels/" + channelId + "/sync");
        client.send(HttpRequest.newBuilder(syncUri).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());

        URI pipelinesUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/youtube/channels/" + channelId + "/pipelines");
        String pipelinesBody = """
                {"youtubeVideoIds":["vid999"],"skipPhases":["DIARIZE","FRAME_SAMPLE","OCR","KNOWLEDGE"]}
                """;
        HttpResponse<String> pipelinesRes = client.send(
                HttpRequest.newBuilder(pipelinesUri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(pipelinesBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(pipelinesRes.statusCode()).isEqualTo(400);

        JsonNode pd = objectMapper.readTree(pipelinesRes.body());
        assertThat(pd.get("title").asText()).isEqualTo("Bad request");
        assertThat(pd.get("detail").asText()).contains("Unknown youtubeVideoIds");

        verify(pipelineIntakeService, never()).intake(anyList(), anySet());
    }

    @Test
    void createPipelineRunReturns400ValidationFailedForEmptyYoutubeVideoIds() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        URI createUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/youtube/channels");
        String createBody = """
                {"url":"https://www.youtube.com/@example","displayName":"Example"}
                """;
        HttpResponse<String> createRes = client.send(
                HttpRequest.newBuilder(createUri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(createBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        String channelId = objectMapper.readTree(createRes.body()).get("id").asText();

        URI pipelinesUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/youtube/channels/" + channelId + "/pipelines");
        String pipelinesBody = """
                {"youtubeVideoIds":[],"skipPhases":["DIARIZE","FRAME_SAMPLE","OCR","KNOWLEDGE"]}
                """;
        HttpResponse<String> pipelinesRes = client.send(
                HttpRequest.newBuilder(pipelinesUri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(pipelinesBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(pipelinesRes.statusCode()).isEqualTo(400);
        JsonNode pd = objectMapper.readTree(pipelinesRes.body());
        assertThat(pd.get("title").asText()).isEqualTo("Validation failed");
        assertThat(pd.get("fields").get("youtubeVideoIds").asText()).isNotBlank();
    }
}

