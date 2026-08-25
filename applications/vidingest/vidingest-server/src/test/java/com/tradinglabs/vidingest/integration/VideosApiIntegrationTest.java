package com.tradinglabs.vidingest.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.core.download.service.DownloadService;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import com.tradinglabs.vidingest.videos.service.VideoDeleteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

class VideosApiIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DownloadService downloadService;

    @MockitoBean
    private VideoDeleteService videoDeleteService;

    @Test
    void downloadDiskOnlyReturnsPaths() throws Exception {
        when(downloadService.downloadToDisk(anyString(), anyBoolean()))
                .thenReturn(new String[]{"/data/videos/example.mp4", "/data/videos/example.metadata.json"});

        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/vidingest/api/v1/videos/download");
        String body = """
                {"url":"https://example.com/video","diskOnly":true,"progress":false}
                """;

        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(res.body());
        assertThat(json.get("video").isNull()).isTrue();
        assertThat(json.get("downloadToDisk").get("videoPath").asText()).isNotBlank();
        assertThat(json.get("downloadToDisk").get("metadataPath").asText()).isNotBlank();
    }

    @Test
    void downloadToDatabaseReturnsVideoSummary() throws Exception {
        Video video = Video.builder()
                .id(UUID.fromString("9b005067-0ec7-4d44-8871-bb43b1c1f420"))
                .source("youtube")
                .sourceVideoId("abc123")
                .title("Title")
                .channelName("Channel")
                .filePath("/data/videos/abc123.mp4")
                .downloadedAt(LocalDateTime.now())
                .metadata(Map.of("extractor", "youtube", "id", "abc123"))
                .status(VideoStatus.DOWNLOADED)
                .build();

        when(downloadService.downloadToDatabase(anyString(), anyBoolean()))
                .thenReturn(video);

        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/vidingest/api/v1/videos/download");
        String body = """
                {"url":"https://example.com/video","diskOnly":false,"progress":false}
                """;

        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(res.body());
        assertThat(json.get("downloadToDisk").isNull()).isTrue();
        assertThat(json.get("video").get("id").asText()).isNotBlank();
        assertThat(json.get("video").get("status").asText()).isEqualTo("DOWNLOADED");
        assertThat(json.get("video").get("filePath").asText()).isEqualTo("/data/videos/abc123.mp4");
    }

    @Test
    void listAndGetReturnStoredVideo() throws Exception {
        Video stored = Video.builder()
                .source("youtube")
                .sourceVideoId("list001")
                .title("Listed")
                .channelName("Channel")
                .filePath("/tmp/list001.mp4")
                .status(VideoStatus.DOWNLOADED)
                .build();
        stored = videoRepository.saveAndFlush(stored);

        HttpClient client = HttpClient.newHttpClient();

        URI listUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/videos?status=ALL&page=0&size=25");
        HttpResponse<String> listRes = client.send(
                HttpRequest.newBuilder(listUri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(listRes.statusCode()).isEqualTo(200);
        JsonNode listJson = objectMapper.readTree(listRes.body());
        assertThat(listJson.get("items").isArray()).isTrue();
        assertThat(listJson.get("total").asLong()).isGreaterThanOrEqualTo(1);

        URI getUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/videos/" + stored.getId());
        HttpResponse<String> getRes = client.send(
                HttpRequest.newBuilder(getUri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(getRes.statusCode()).isEqualTo(200);
        JsonNode getJson = objectMapper.readTree(getRes.body());
        assertThat(getJson.get("id").asText()).isEqualTo(stored.getId().toString());
        assertThat(getJson.get("status").asText()).isEqualTo("DOWNLOADED");
    }

    @Test
    void deleteReturnsDeletedResult() throws Exception {
        UUID id = UUID.fromString("1ad04d10-d31e-4a37-abab-e1ce103459d1");
        doNothing().when(videoDeleteService).deleteVideo(id);

        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/vidingest/api/v1/videos/" + id);

        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder(uri).DELETE().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode json = objectMapper.readTree(res.body());
        assertThat(json.get("status").asText()).isEqualTo("deleted");
        assertThat(json.get("videoId").asText()).isEqualTo(id.toString());
    }
}

