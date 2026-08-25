package com.tradinglabs.vidingest.integration;

import com.tradinglabs.vidingest.core.fusion.domain.MultimodalSegment;
import com.tradinglabs.vidingest.core.fusion.service.SegmentFusionService;
import com.tradinglabs.vidingest.core.transcription.domain.Transcription;
import com.tradinglabs.vidingest.core.transcription.domain.TranscriptionSegment;
import com.tradinglabs.vidingest.core.transcription.domain.TranscriptionStatus;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fusion is covered by unit tests with mocked repositories, which cannot see what JPA
 * actually does. This runs it against real Postgres, because the persist now happens inside a
 * transaction and {@code MultimodalSegment.video} is a lazy {@code @ManyToOne} populated from
 * a detached {@link Video} — Hibernate resolves that FK at flush, and nothing else in the
 * suite exercises the path.
 */
class FusePhaseIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private SegmentFusionService segmentFusionService;

    @Test
    void fusePersistsSegmentsForAVideoWithATranscript() {
        Video video = seedVideoWithTranscript("fuse001");

        List<MultimodalSegment> fused = segmentFusionService.fuse(video);

        assertThat(fused).isNotEmpty();
        assertThat(multimodalSegmentRepository.countByVideo_Id(video.getId())).isEqualTo(fused.size());
        assertThat(multimodalSegmentRepository.findByVideo_IdOrderBySegmentIndexAsc(video.getId()))
                .allSatisfy(seg -> assertThat(seg.getTranscriptText()).isNotBlank());
    }

    /** Wipe-then-repopulate must converge, not accumulate — the whole point of the transaction. */
    @Test
    void fuseIsIdempotentAcrossRuns() {
        Video video = seedVideoWithTranscript("fuse002");

        long firstRun = segmentFusionService.fuse(video).size();
        long secondRun = segmentFusionService.fuse(video).size();

        assertThat(secondRun).isEqualTo(firstRun);
        assertThat(multimodalSegmentRepository.countByVideo_Id(video.getId())).isEqualTo(firstRun);
    }

    /** Three transcript segments across 90s, so the 30s/5s-overlap windowing yields several rows. */
    private Video seedVideoWithTranscript(String sourceVideoId) {
        Video video = videoRepository.saveAndFlush(Video.builder()
                .source("youtube")
                .sourceVideoId(sourceVideoId)
                .title("Fusion video")
                .channelName("Channel")
                .filePath("/tmp/" + sourceVideoId + ".mp4")
                .durationSeconds(90)
                .status(VideoStatus.COMPLETED)
                .build());

        Transcription transcription = transcriptionRepository.saveAndFlush(Transcription.builder()
                .video(video)
                .language("en")
                .provider("whisper")
                .status(TranscriptionStatus.COMPLETED)
                .fullText("first second third")
                .build());

        transcriptionSegmentRepository.saveAllAndFlush(List.of(
                segment(transcription, 0.0f, 20.0f, "first"),
                segment(transcription, 30.0f, 50.0f, "second"),
                segment(transcription, 60.0f, 85.0f, "third")
        ));

        return video;
    }

    private static TranscriptionSegment segment(Transcription t, float start, float end, String text) {
        return TranscriptionSegment.builder()
                .transcription(t)
                .startSeconds(start)
                .endSeconds(end)
                .text(text)
                .build();
    }
}
