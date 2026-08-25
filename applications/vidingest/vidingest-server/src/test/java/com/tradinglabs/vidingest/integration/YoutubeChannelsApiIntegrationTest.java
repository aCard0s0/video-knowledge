package com.tradinglabs.vidingest.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.youtube.discovery.YoutubeChannelDiscoveryResult;
import com.tradinglabs.vidingest.youtube.discovery.YoutubeChannelDiscoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class YoutubeChannelsApiIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private YoutubeChannelDiscoveryService youtubeChannelDiscoveryService;

    @Test
    void createSyncAndListVideosWork() throws Exception {
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
                                        LocalDateTime.of(2026, 1, 1, 0, 0),
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
        JsonNode synced = objectMapper.readTree(syncRes.body());
        assertThat(synced.get("status").asText()).isEqualTo("READY");

        URI listVideosUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/youtube/channels/" + channelId + "/videos?page=0&size=50");
        HttpResponse<String> listVideosRes = client.send(
                HttpRequest.newBuilder(listVideosUri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(listVideosRes.statusCode()).isEqualTo(200);
        JsonNode listVideos = objectMapper.readTree(listVideosRes.body());
        assertThat(listVideos.get("items").size()).isEqualTo(2);
        assertThat(listVideos.get("items").get(0).get("youtubeVideoId").asText()).isNotBlank();
    }
}

