package com.tradinglabs.vidingest.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.core.transcription.domain.Transcription;
import com.tradinglabs.vidingest.core.transcription.domain.TranscriptionStatus;
import com.tradinglabs.vidingest.search.service.embedding.EmbeddingsClient;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class ContextChunkRegenerateIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EmbeddingsClient embeddingsClient;

    @Test
    void regenerateContextPersistsChunks() throws Exception {
        when(embeddingsClient.embed(anyList())).thenAnswer(inv -> {
            // The embeddings call batches 2000 chunks into 32 sequential HTTP requests at up
            // to 180s each. Doing that while holding a pooled connection wedges the app, so
            // fail here rather than the day someone re-adds @Transactional to regenerateFor.
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("embeddings call must not hold a DB connection")
                    .isFalse();
            List<String> inputs = inv.getArgument(0);
            List<float[]> out = new ArrayList<>(inputs.size());
            for (int i = 0; i < inputs.size(); i++) {
                out.add(new float[1536]);
            }
            return out;
        });
        when(embeddingsClient.embedOne(anyString())).thenReturn(new float[1536]);

        Video video = Video.builder()
                .source("youtube")
                .sourceVideoId("ctx001")
                .title("Context video")
                .channelName("Channel")
                .filePath("/tmp/ctx001.mp4")
                .status(VideoStatus.COMPLETED)
                .build();
        video = videoRepository.saveAndFlush(video);

        transcriptionRepository.saveAndFlush(Transcription.builder()
                .video(video)
                .language("en")
                .provider("whisper")
                .status(TranscriptionStatus.COMPLETED)
                .fullText("hello world ".repeat(300))
                .build());

        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/vidingest/api/v1/videos/" + video.getId() + "/context/regenerate");
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder(uri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(res.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(res.body());
        int chunks = json.get("chunks").asInt();
        assertThat(chunks).isGreaterThan(0);
        assertThat(json.get("videoId").asText()).isEqualTo(video.getId().toString());

        assertThat(contextChunkRepository.count()).isEqualTo(chunks);
    }

    /**
     * A video with no transcript and no multimodal segments takes the early-return branch,
     * which wipes stale chunks and returns 0. That branch supplies no transaction of its own,
     * so it only works because the repository's bulk delete carries {@code @Transactional} —
     * and nothing else in the suite exercises it.
     */
    @Test
    void regenerateContextOnAVideoWithNoSourceTextWipesAndReturnsZero() throws Exception {
        Video video = videoRepository.saveAndFlush(Video.builder()
                .source("youtube")
                .sourceVideoId("ctx002")
                .title("No transcript")
                .channelName("Channel")
                .filePath("/tmp/ctx002.mp4")
                .status(VideoStatus.COMPLETED)
                .build());

        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/vidingest/api/v1/videos/" + video.getId() + "/context/regenerate");
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder(uri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(res.body()).get("chunks").asInt()).isZero();
        assertThat(contextChunkRepository.count()).isZero();
    }
}

