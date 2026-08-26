package com.tradinglabs.vidingest.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.core.frames.domain.SamplingReason;
import com.tradinglabs.vidingest.core.frames.domain.VideoFrame;
import com.tradinglabs.vidingest.core.frames.repo.VideoFrameRepository;
import com.tradinglabs.vidingest.core.ocr.domain.OcrResult;
import com.tradinglabs.vidingest.core.ocr.repo.OcrResultRepository;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/v1/videos/{id}/ocr/frames} answered 500 for every video that actually had OCR
 * rows. The paging query was {@code select distinct f.id from OcrResult o join o.frame f ... order
 * by f.timestampSeconds}, which PostgreSQL rejects with SQLState 42P10: under {@code SELECT
 * DISTINCT} every {@code ORDER BY} expression must appear in the select list.
 *
 * <p>It escaped notice because {@code OcrQueryService.resultsByFramePage} short-circuits on a zero
 * frame count, so a video with no visual text returned an empty page and 200 without ever running
 * the broken statement. Only a video with OCR rows reaches it — hence seeding rows here, and hence
 * an integration test: the defect is in the generated SQL, so a mocked repository cannot see it.
 */
class OcrFramePageIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VideoFrameRepository videoFrameRepository;

    @Autowired
    private OcrResultRepository ocrResultRepository;

    private UUID videoId;

    @BeforeEach
    void seedFramesWithText() {
        ocrResultRepository.deleteAllInBatch();
        videoFrameRepository.deleteAllInBatch();

        Video video = videoRepository.saveAndFlush(Video.builder()
                .source("youtube")
                .sourceVideoId("ocrpage01")
                .title("Video with visual text")
                .channelName("Channel")
                .filePath("/tmp/ocrpage01.mp4")
                .status(VideoStatus.COMPLETED)
                .build());
        videoId = video.getId();

        // Three frames carrying text, plus one that carries none: the endpoint must page over
        // only the frames that have rows, so the barren frame must not appear or be counted.
        List<VideoFrame> frames = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            frames.add(videoFrameRepository.saveAndFlush(VideoFrame.builder()
                    .video(video)
                    .frameIndex(i)
                    .timestampSeconds(i * 10.0)
                    .filePath("/tmp/ocrpage01/frames/000" + i + ".jpg")
                    .samplingReason(SamplingReason.INTERVAL)
                    .build()));
        }

        List<OcrResult> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            // More than one row per frame, so a DISTINCT-free query still yields one id per frame.
            rows.add(ocrResult(frames.get(i), "line A on frame " + i));
            rows.add(ocrResult(frames.get(i), "line B on frame " + i));
        }
        ocrResultRepository.saveAllAndFlush(rows);
    }

    private OcrResult ocrResult(VideoFrame frame, String text) {
        return OcrResult.builder()
                .frame(frame)
                .text(text)
                .confidence(0.9f)
                .language("en")
                .build();
    }

    @Test
    void framePageIsOrderedByTimestampAndCountsOnlyFramesWithText() throws Exception {
        JsonNode body = objectMapper.readTree(get("/api/v1/videos/" + videoId + "/ocr/frames?page=0&size=2"));

        assertThat(body.get("total").asLong()).isEqualTo(3);
        assertThat(body.get("items")).hasSize(2);
        assertThat(body.get("items").get(0).get("timestampSeconds").asDouble()).isEqualTo(0.0);
        assertThat(body.get("items").get(1).get("timestampSeconds").asDouble()).isEqualTo(10.0);
    }

    @Test
    void secondPageContinuesFromTheFirst() throws Exception {
        JsonNode body = objectMapper.readTree(get("/api/v1/videos/" + videoId + "/ocr/frames?page=1&size=2"));

        assertThat(body.get("total").asLong()).isEqualTo(3);
        assertThat(body.get("items")).hasSize(1);
        assertThat(body.get("items").get(0).get("timestampSeconds").asDouble()).isEqualTo(20.0);
    }

    private String get(String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/vidingest" + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
