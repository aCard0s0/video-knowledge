package com.tradinglabs.vidingest.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.config.VideoStorageConfig;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VideoArtifactsApiIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VideoStorageConfig storageConfig;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setupMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void downloadVideoFileAndWhisperArtifactsReturnBytesFromStorageRoot() throws Exception {
        Path root = Path.of(storageConfig.getVideoPath());
        Path dir = root.resolve("Channel");
        Files.createDirectories(dir);

        Path mp4 = dir.resolve("20260315.test-video.mp4");
        byte[] mp4Bytes = "fake-mp4-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(mp4, mp4Bytes);

        Path whisperTxt = dir.resolve("20260315.test-video.whisper.txt");
        Files.writeString(whisperTxt, "hello whisper", StandardCharsets.UTF_8);

        Path whisperJson = dir.resolve("20260315.test-video.whisper.json");
        Files.writeString(whisperJson, "{\"provider\":\"whisper\"}", StandardCharsets.UTF_8);

        Video stored = Video.builder()
                .source("youtube")
                .sourceVideoId("artifacts-001")
                .title("Artifacts")
                .channelName("Channel")
                .filePath(mp4.toString())
                .status(VideoStatus.COMPLETED)
                .build();
        stored = videoRepository.saveAndFlush(stored);

        mockMvc.perform(get("/api/v1/videos/{id}/file", stored.getId()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().bytes(mp4Bytes));

        mockMvc.perform(get("/api/v1/videos/{id}/transcription/whisper.txt", stored.getId()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/plain")))
                .andExpect(content().string(containsString("hello whisper")));

        mockMvc.perform(get("/api/v1/videos/{id}/transcription/whisper.json", stored.getId()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/json")))
                .andExpect(content().string(containsString("\"provider\"")));
    }

    @Test
    void missingWhisperArtifactReturns404ProblemDetail() throws Exception {
        Path root = Path.of(storageConfig.getVideoPath());
        Path dir = root.resolve("Channel");
        Files.createDirectories(dir);

        Path mp4 = dir.resolve("20260315.missing-artifact.mp4");
        Files.write(mp4, "fake".getBytes(StandardCharsets.UTF_8));

        Video stored = Video.builder()
                .source("youtube")
                .sourceVideoId("artifacts-404")
                .title("Artifacts 404")
                .channelName("Channel")
                .filePath(mp4.toString())
                .status(VideoStatus.COMPLETED)
                .build();
        stored = videoRepository.saveAndFlush(stored);

        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/vidingest/api/v1/videos/" + stored.getId() + "/transcription/whisper.json");
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(res.statusCode()).isEqualTo(404);

        JsonNode json = objectMapper.readTree(res.body());
        assertThat(json.get("title").asText()).isEqualTo("Not found");
        assertThat(json.get("status").asInt()).isEqualTo(404);
        assertThat(json.get("instance").asText()).contains("/vidingest/api/v1/videos/");
    }

    @Test
    void videoFileOutsideStorageRootReturns500AndDoesNotExposeFile() throws Exception {
        Path outside = Files.createTempFile("vidingest-it-outside-", ".mp4");
        Files.write(outside, "secret".getBytes(StandardCharsets.UTF_8));

        Video stored = Video.builder()
                .source("youtube")
                .sourceVideoId("artifacts-outside")
                .title("Outside")
                .channelName("Channel")
                .filePath(outside.toString())
                .status(VideoStatus.COMPLETED)
                .build();
        stored = videoRepository.saveAndFlush(stored);

        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/vidingest/api/v1/videos/" + stored.getId() + "/file");
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(res.statusCode()).isEqualTo(500);

        JsonNode json = objectMapper.readTree(res.body());
        assertThat(json.get("title").asText()).isEqualTo("Internal error");
        assertThat(json.get("detail").asText()).contains("Refusing to access video file outside storage root");
        assertThat(json.get("instance").asText()).endsWith("/vidingest/api/v1/videos/" + stored.getId() + "/file");
        assertThat(res.body()).doesNotContain("secret");
    }
}

