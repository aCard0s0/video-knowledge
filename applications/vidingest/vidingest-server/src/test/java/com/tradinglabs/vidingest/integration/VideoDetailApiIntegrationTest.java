package com.tradinglabs.vidingest.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.core.diarization.domain.Speaker;
import com.tradinglabs.vidingest.core.diarization.repo.SpeakerRepository;
import com.tradinglabs.vidingest.core.frames.domain.SamplingReason;
import com.tradinglabs.vidingest.core.frames.domain.VideoFrame;
import com.tradinglabs.vidingest.core.frames.repo.VideoFrameRepository;
import com.tradinglabs.vidingest.core.fusion.domain.MultimodalSegment;
import com.tradinglabs.vidingest.core.fusion.repo.MultimodalSegmentRepository;
import com.tradinglabs.vidingest.core.knowledge.domain.KnowledgeUnit;
import com.tradinglabs.vidingest.core.knowledge.repo.KnowledgeUnitRepository;
import com.tradinglabs.vidingest.core.ocr.domain.OcrResult;
import com.tradinglabs.vidingest.core.ocr.repo.OcrResultRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

class VideoDetailApiIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SpeakerRepository speakerRepository;

    @Autowired
    private VideoFrameRepository videoFrameRepository;

    @Autowired
    private OcrResultRepository ocrResultRepository;

    @Autowired
    private MultimodalSegmentRepository multimodalSegmentRepository;

    @Autowired
    private KnowledgeUnitRepository knowledgeUnitRepository;

    @Test
    void getVideoDetailReturnsVideoTranscriptionAndCounts() throws Exception {
        Video video = Video.builder()
                .source("youtube")
                .sourceVideoId("detail001")
                .title("Detail video")
                .channelName("Channel")
                .filePath("/tmp/detail001.mp4")
                .status(VideoStatus.COMPLETED)
                .build();
        video = videoRepository.saveAndFlush(video);

        Transcription tx = Transcription.builder()
                .video(video)
                .language("en")
                .provider("whisper")
                .status(TranscriptionStatus.COMPLETED)
                .fullText("hello world")
                .build();
        tx = transcriptionRepository.saveAndFlush(tx);

        transcriptionSegmentRepository.saveAndFlush(TranscriptionSegment.builder()
                .transcription(tx)
                .startSeconds(0.0f)
                .endSeconds(1.0f)
                .text("hello")
                .build());
        transcriptionSegmentRepository.saveAndFlush(TranscriptionSegment.builder()
                .transcription(tx)
                .startSeconds(1.0f)
                .endSeconds(2.0f)
                .text("world")
                .build());

        speakerRepository.saveAndFlush(Speaker.builder()
                .video(video)
                .label("SPEAKER_00")
                .displayName("Speaker 0")
                .build());

        VideoFrame frame = videoFrameRepository.saveAndFlush(VideoFrame.builder()
                .video(video)
                .frameIndex(0)
                .timestampSeconds(0.5)
                .filePath("/tmp/detail001/frames/000.jpg")
                .samplingReason(SamplingReason.INTERVAL)
                .width(320)
                .height(180)
                .build());

        ocrResultRepository.saveAndFlush(OcrResult.builder()
                .frame(frame)
                .text("OCR line")
                .confidence(0.99f)
                .language("en")
                .build());

        multimodalSegmentRepository.saveAndFlush(MultimodalSegment.builder()
                .video(video)
                .segmentIndex(0)
                .startSeconds(0.0)
                .endSeconds(2.0)
                .transcriptText("hello world")
                .ocrText("OCR line")
                .build());

        knowledgeUnitRepository.saveAndFlush(KnowledgeUnit.builder()
                .video(video)
                .type(KnowledgeUnitType.SUMMARY)
                .title("Summary")
                .content("Some summary")
                .build());

        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/vidingest/api/v1/videos/" + video.getId() + "/detail");
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(res.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(res.body());
        assertThat(json.get("video").get("id").asText()).isEqualTo(video.getId().toString());
        assertThat(json.get("transcription").get("present").asBoolean()).isTrue();

        assertThat(json.get("counts").get("speakers").asLong()).isEqualTo(1);
        assertThat(json.get("counts").get("ocrFrames").asLong()).isEqualTo(1);
        assertThat(json.get("counts").get("multimodalSegments").asLong()).isEqualTo(1);
        assertThat(json.get("counts").get("transcriptionSegments").asLong()).isEqualTo(2);
        assertThat(json.get("counts").get("knowledgeUnits").asLong()).isEqualTo(1);
    }
}

