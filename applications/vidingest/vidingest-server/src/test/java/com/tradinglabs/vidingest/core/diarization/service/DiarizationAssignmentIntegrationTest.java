package com.tradinglabs.vidingest.core.diarization.service;

import com.tradinglabs.vidingest.core.diarization.dto.DiarizationResult;
import com.tradinglabs.vidingest.integration.BaseVidingestIntegrationTest;
import com.tradinglabs.vidingest.core.diarization.dto.DiarizationSegment;
import com.tradinglabs.vidingest.core.diarization.dto.DiarizationSpeaker;
import com.tradinglabs.vidingest.core.diarization.repo.SpeakerRepository;
import com.tradinglabs.vidingest.core.transcription.domain.Transcription;
import com.tradinglabs.vidingest.core.transcription.domain.TranscriptionSegment;
import com.tradinglabs.vidingest.core.transcription.domain.TranscriptionStatus;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Speaker assignment writes every transcript segment of a video. It used to run without a
 * transaction — the {@code @Transactional} sat on a self-invoked protected method, which
 * Spring ignores — so the segments came back detached and {@code saveAll} merged them: one
 * SELECT per row before the UPDATE. On a long transcript that is thousands of extra
 * round-trips.
 *
 * <p>This measures it rather than trusting the reading: with the write inside a transaction
 * the segments stay managed, so the query count must stay far below one-per-segment.
 *
 * <p>Lives beside {@code DiarizationService} rather than under {@code integration/} with its
 * siblings because {@code persistAndAssign} is package-visible — widening it for a test would
 * be the wrong trade.
 */
class DiarizationAssignmentIntegrationTest extends BaseVidingestIntegrationTest {

    private static final int SEGMENTS = 200;

    @Autowired
    private DiarizationService diarizationService;

    @Autowired
    private SpeakerRepository speakerRepository;

    @Autowired
    private TransactionOperations transactionOperations;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void assignsSpeakersWithoutASelectPerSegment() {
        Video video = videoRepository.saveAndFlush(Video.builder()
                .source("youtube")
                .sourceVideoId("diar001")
                .title("Diarized video")
                .channelName("Channel")
                .filePath("/tmp/diar001.mp4")
                .status(VideoStatus.COMPLETED)
                .build());

        Transcription transcription = transcriptionRepository.saveAndFlush(Transcription.builder()
                .video(video)
                .language("en")
                .provider("whisper")
                .status(TranscriptionStatus.COMPLETED)
                .fullText("long transcript")
                .build());

        List<TranscriptionSegment> segments = new ArrayList<>(SEGMENTS);
        List<DiarizationSegment> diarSegments = new ArrayList<>(SEGMENTS);
        for (int i = 0; i < SEGMENTS; i++) {
            float start = i * 10.0f;
            segments.add(TranscriptionSegment.builder()
                    .transcription(transcription)
                    .startSeconds(start)
                    .endSeconds(start + 9.0f)
                    .text("segment " + i)
                    .build());
            // Alternate speakers so every segment genuinely needs a speaker_id written.
            diarSegments.add(new DiarizationSegment(start, start + 9.0f, "SPEAKER_0" + (i % 2)));
        }
        transcriptionSegmentRepository.saveAllAndFlush(segments);

        DiarizationResult result = new DiarizationResult(
                diarSegments,
                List.of(new DiarizationSpeaker("SPEAKER_00", null), new DiarizationSpeaker("SPEAKER_01", null)));

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        stats.clear();
        try {
            transactionOperations.executeWithoutResult(
                    status -> diarizationService.persistAndAssign(video, transcription.getId(), result));

            // Measured on this fixture: 404 statements without the ambient transaction
            // (a merge SELECT plus an UPDATE per segment), 204 with it (the UPDATEs only —
            // still one apiece, since hibernate.jdbc.batch_size is deliberately unset). The
            // ceiling has slack for the handful of speaker/transcription statements; what it
            // catches is the SELECT-per-row coming back if the write escapes the transaction.
            assertThat(stats.getPrepareStatementCount())
                    .as("prepared statements for %d segments", SEGMENTS)
                    .isLessThanOrEqualTo(SEGMENTS + 20);
        } finally {
            stats.setStatisticsEnabled(wasEnabled);
        }

        assertThat(speakerRepository.countByVideo_Id(video.getId())).isEqualTo(2);
        assertThat(transcriptionSegmentRepository.findByTranscriptionIdOrderByStartSecondsAsc(transcription.getId()))
                .hasSize(SEGMENTS)
                .allSatisfy(seg -> assertThat(seg.getSpeakerId()).isNotNull());
    }
}
