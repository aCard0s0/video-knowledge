package com.tradinglabs.vidingest.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
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
import java.util.ArrayList;
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

    /**
     * The catalog comes back in discovery order — newest upload first.
     *
     * <p>Every candidate here has a null {@code publishedAt}, which is what a real
     * {@code --flat-playlist} discovery returns: yt-dlp emits no upload date for a channel tab.
     * That leaves the sort resting entirely on its fallbacks, and the old one (createdAt DESC)
     * reversed the batch — a 200-video catalog opened on its fifty *oldest* uploads.
     */
    @Test
    void channelVideosComeBackNewestFirst() throws Exception {
        // The order yt-dlp lists a channel tab in: newest at index 0.
        when(youtubeChannelDiscoveryService.discover(anyString(), anyInt(), anyLong()))
                .thenReturn(new YoutubeChannelDiscoveryResult(
                        "https://www.youtube.com/@order",
                        "UC_ORDER",
                        "Order Channel",
                        Map.of(),
                        List.of(candidate("newest"), candidate("middle"), candidate("oldest"))
                ));

        String channelId = createChannel("https://www.youtube.com/@order");
        assertThat(post("/youtube/channels/" + channelId + "/sync").statusCode()).isEqualTo(200);

        JsonNode listed = objectMapper.readTree(get("/youtube/channels/" + channelId + "/videos?page=0&size=50").body());
        List<String> ids = new ArrayList<>();
        listed.get("items").forEach(item -> ids.add(item.get("youtubeVideoId").asText()));
        assertThat(ids).containsExactly("newest", "middle", "oldest");
    }

    /**
     * A mistyped channel used to be permanent: nothing reaches the DISABLED status, so the
     * half-hour scheduler re-ran yt-dlp against a dead URL forever.
     */
    @Test
    void deleteRemovesTheChannelAndItsCatalog() throws Exception {
        when(youtubeChannelDiscoveryService.discover(anyString(), anyInt(), anyLong()))
                .thenReturn(new YoutubeChannelDiscoveryResult(
                        "https://www.youtube.com/@doomed", "UC_DOOMED", "Doomed", Map.of(),
                        List.of(candidate("gone"))
                ));

        String channelId = createChannel("https://www.youtube.com/@doomed");
        assertThat(post("/youtube/channels/" + channelId + "/sync").statusCode()).isEqualTo(200);

        HttpResponse<String> deleted = send(HttpRequest.newBuilder(uri("/youtube/channels/" + channelId)).DELETE());
        assertThat(deleted.statusCode()).isEqualTo(204);

        // The catalog goes with it via ON DELETE CASCADE; the channel itself is gone.
        assertThat(get("/youtube/channels/" + channelId).statusCode()).isEqualTo(404);
        assertThat(get("/youtube/channels/" + channelId + "/videos?page=0&size=50").statusCode()).isEqualTo(404);

        // Deleting something already gone is a 404, not a silent success.
        assertThat(send(HttpRequest.newBuilder(uri("/youtube/channels/" + channelId)).DELETE()).statusCode())
                .isEqualTo(404);
    }

    /**
     * `notIngestedOnly` filters before the page is cut, so the total describes what is shown.
     *
     * The console filtered its own page client-side, which left the pager reporting the
     * unfiltered count: two of three ingested and the screen rendered one row under "1–3 of 3".
     */
    @Test
    void notIngestedOnlyFiltersBeforeTheTotalIsCounted() throws Exception {
        when(youtubeChannelDiscoveryService.discover(anyString(), anyInt(), anyLong()))
                .thenReturn(new YoutubeChannelDiscoveryResult(
                        "https://www.youtube.com/@partial", "UC_PARTIAL", "Partial", Map.of(),
                        List.of(candidate("one"), candidate("two"), candidate("three"))
                ));

        String channelId = createChannel("https://www.youtube.com/@partial");
        assertThat(post("/youtube/channels/" + channelId + "/sync").statusCode()).isEqualTo(200);

        // "ingested" is a vidingest_videos row on the same (source, sourceVideoId) identity.
        videoRepository.save(Video.builder()
                .source("youtube").sourceVideoId("one").status(VideoStatus.COMPLETED).build());

        JsonNode all = objectMapper.readTree(get("/youtube/channels/" + channelId + "/videos?page=0&size=50").body());
        assertThat(all.get("total").asInt()).isEqualTo(3);

        JsonNode fresh = objectMapper.readTree(
                get("/youtube/channels/" + channelId + "/videos?page=0&size=50&notIngestedOnly=true").body());
        assertThat(fresh.get("total").asInt()).isEqualTo(2);
        List<String> ids = new ArrayList<>();
        fresh.get("items").forEach(item -> ids.add(item.get("youtubeVideoId").asText()));
        assertThat(ids).containsExactly("two", "three");
    }

    /** The picker showed four phases enabled that this deployment is configured to skip. */
    @Test
    void capabilitiesReportOnlyThePhasesThisServerWillRun() throws Exception {
        JsonNode caps = objectMapper.readTree(get("/pipelines/capabilities").body());

        List<String> enabled = new ArrayList<>();
        caps.get("enabledPhases").forEach(p -> enabled.add(p.asText()));

        // The base test turns semantic search off, so CONTEXT is off with it; the four opt-in
        // enrichment phases default to false and stay out.
        assertThat(enabled).contains("TRANSCRIBE", "FUSE");
        assertThat(enabled).doesNotContain("DIARIZE", "FRAME_SAMPLE", "OCR", "KNOWLEDGE", "CONTEXT");
        assertThat(caps.get("channelSyncLimit").asInt()).isPositive();
    }

    private static YoutubeChannelDiscoveryResult.YoutubeVideoCandidate candidate(String id) {
        return new YoutubeChannelDiscoveryResult.YoutubeVideoCandidate(
                id, "Video " + id, null, "https://www.youtube.com/watch?v=" + id, Map.of("id", id));
    }

    private String createChannel(String url) throws Exception {
        HttpResponse<String> res = send(HttpRequest.newBuilder(uri("/youtube/channels"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"" + url + "\"}")));
        assertThat(res.statusCode()).isEqualTo(201);
        return objectMapper.readTree(res.body()).get("id").asText();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + "/vidingest/api/v1" + path);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody()));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

