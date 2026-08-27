package com.tradinglabs.vidingest.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.core.transcription.domain.Transcription;
import com.tradinglabs.vidingest.core.transcription.domain.TranscriptionSegment;
import com.tradinglabs.vidingest.core.transcription.domain.TranscriptionStatus;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class VideoTranscriptionApiIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void transcriptionEndpointsReturnEmptyWhenNoTranscriptionExists() throws Exception {
        Video stored = Video.builder()
                .source("youtube")
                .sourceVideoId("tx-none-001")
                .title("Listed")
                .channelName("Channel")
                .filePath("/tmp/tx-none-001.mp4")
                .status(VideoStatus.DOWNLOADED)
                .downloadedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        stored = videoRepository.saveAndFlush(stored);

        HttpClient client = HttpClient.newHttpClient();

        URI detailsUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/videos/" + stored.getId() + "/transcription");
        HttpResponse<String> detailsRes = client.send(
                HttpRequest.newBuilder(detailsUri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(detailsRes.statusCode()).isEqualTo(200);
        JsonNode details = objectMapper.readTree(detailsRes.body());
        assertThat(details.get("present").asBoolean()).isFalse();
        assertThat(details.get("id").isNull()).isTrue();

        URI segmentsUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/videos/" + stored.getId() + "/transcription/segments?page=0&size=50");
        HttpResponse<String> segRes = client.send(
                HttpRequest.newBuilder(segmentsUri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(segRes.statusCode()).isEqualTo(200);
        JsonNode seg = objectMapper.readTree(segRes.body());
        assertThat(seg.get("items").isArray()).isTrue();
        assertThat(seg.get("items").size()).isEqualTo(0);
        assertThat(seg.get("total").asLong()).isEqualTo(0);
    }

    @Test
    void transcriptionAndSegmentsEndpointsReturnPersistedTranscriptionAndSortedSegments() throws Exception {
        Video stored = Video.builder()
                .source("youtube")
                .sourceVideoId("tx-yes-001")
                .title("Tx Video")
                .channelName("Channel")
                .filePath("/tmp/tx-yes-001.mp4")
                .status(VideoStatus.TRANSCRIBING)
                .downloadedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        stored = videoRepository.saveAndFlush(stored);

        Transcription tx = Transcription.builder()
                .video(stored)
                .provider("whisper")
                .language("en")
                .status(TranscriptionStatus.COMPLETED)
                .fullText("First sentence. Second sentence. Third sentence.")
                .build();
        tx = transcriptionRepository.saveAndFlush(tx);

        TranscriptionSegment s2 = TranscriptionSegment.builder()
                .transcription(tx)
                .startSeconds(12.0f)
                .endSeconds(15.0f)
                .text("Second")
                .build();
        TranscriptionSegment s1 = TranscriptionSegment.builder()
                .transcription(tx)
                .startSeconds(1.0f)
                .endSeconds(3.0f)
                .text("First")
                .build();
        transcriptionSegmentRepository.saveAndFlush(s2);
        transcriptionSegmentRepository.saveAndFlush(s1);

        HttpClient client = HttpClient.newHttpClient();

        URI detailsUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/videos/" + stored.getId() + "/transcription");
        HttpResponse<String> detailsRes = client.send(
                HttpRequest.newBuilder(detailsUri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(detailsRes.statusCode()).isEqualTo(200);
        JsonNode details = objectMapper.readTree(detailsRes.body());
        assertThat(details.get("present").asBoolean()).isTrue();
        assertThat(details.get("id").asText()).isEqualTo(tx.getId().toString());
        assertThat(details.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(details.get("provider").asText()).isEqualTo("whisper");
        assertThat(details.get("language").asText()).isEqualTo("en");
        assertThat(details.get("snippet").asText()).contains("First sentence");

        URI segmentsUri = URI.create("http://localhost:" + port + "/vidingest/api/v1/videos/" + stored.getId() + "/transcription/segments?page=0&size=50");
        HttpResponse<String> segRes = client.send(
                HttpRequest.newBuilder(segmentsUri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(segRes.statusCode()).isEqualTo(200);
        JsonNode seg = objectMapper.readTree(segRes.body());
        assertThat(seg.get("items").size()).isEqualTo(2);
        assertThat(seg.get("items").get(0).get("text").asText()).isEqualTo("First");
        assertThat(seg.get("items").get(0).get("startSeconds").asDouble()).isEqualTo(1.0);
        assertThat(seg.get("items").get(1).get("text").asText()).isEqualTo("Second");
        assertThat(seg.get("items").get(1).get("startSeconds").asDouble()).isEqualTo(12.0);
    }
}

