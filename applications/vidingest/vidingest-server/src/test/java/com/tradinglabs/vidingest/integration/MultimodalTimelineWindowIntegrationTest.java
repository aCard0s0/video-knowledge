package com.tradinglabs.vidingest.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.core.fusion.domain.MultimodalSegment;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code fromSeconds}/{@code toSeconds} window is a JPQL predicate shared by the timeline
 * and its paged variant. It used to exist twice — once in SQL, once as a stream filter in the
 * controller — so it is worth pinning against a real database rather than a mocked repository.
 *
 * <p>Window semantics: a segment is included when it *overlaps* the window, so a segment that
 * starts before {@code fromSeconds} still counts if it runs past it.
 */
class MultimodalTimelineWindowIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private UUID videoId;

    @BeforeEach
    void seedTimeline() {
        Video video = videoRepository.saveAndFlush(Video.builder()
                .source("youtube")
                .sourceVideoId("window001")
                .title("Windowed video")
                .channelName("Channel")
                .filePath("/tmp/window001.mp4")
                .status(VideoStatus.COMPLETED)
                .build());
        videoId = video.getId();

        multimodalSegmentRepository.saveAllAndFlush(java.util.List.of(
                segment(video, 0, 0.0, 30.0, "first"),
                segment(video, 1, 30.0, 60.0, "middle"),
                segment(video, 2, 60.0, 90.0, "last")
        ));
    }

    @Test
    void returnsEverySegmentWhenNoWindowIsGiven() throws Exception {
        assertThat(transcriptTexts("")).containsExactly("first", "middle", "last");
    }

    @Test
    void includesSegmentsThatOverlapTheWindowEdges() throws Exception {
        // [25, 65] catches segment 0 (ends at 30 > 25) and segment 2 (starts at 60 < 65).
        assertThat(transcriptTexts("?fromSeconds=25&toSeconds=65"))
                .containsExactly("first", "middle", "last");
    }

    @Test
    void excludesSegmentsEntirelyOutsideTheWindow() throws Exception {
        assertThat(transcriptTexts("?fromSeconds=35&toSeconds=55")).containsExactly("middle");
    }

    @Test
    void appliesAOneSidedWindow() throws Exception {
        assertThat(transcriptTexts("?fromSeconds=60")).containsExactly("last");
        assertThat(transcriptTexts("?toSeconds=30")).containsExactly("first");
    }

    @Test
    void thePagedVariantWindowsIdentically() throws Exception {
        JsonNode body = getJson("/multimodal-timeline/page?fromSeconds=35&toSeconds=55&page=0&size=50");

        assertThat(body.get("total").asLong()).isEqualTo(1);
        assertThat(body.get("items").get(0).get("transcriptText").asText()).isEqualTo("middle");
    }

    private java.util.List<String> transcriptTexts(String query) throws Exception {
        JsonNode body = getJson("/multimodal-timeline" + query);
        java.util.List<String> texts = new java.util.ArrayList<>();
        body.forEach(node -> texts.add(node.get("transcriptText").asText()));
        return texts;
    }

    private JsonNode getJson(String suffix) throws Exception {
        URI uri = URI.create("http://localhost:" + port + "/vidingest/api/v1/videos/" + videoId + suffix);
        HttpResponse<String> res = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        return objectMapper.readTree(res.body());
    }

    private static MultimodalSegment segment(Video video, int index, double start, double end, String text) {
        return MultimodalSegment.builder()
                .video(video)
                .segmentIndex(index)
                .startSeconds(start)
                .endSeconds(end)
                .transcriptText(text)
                .build();
    }
}
