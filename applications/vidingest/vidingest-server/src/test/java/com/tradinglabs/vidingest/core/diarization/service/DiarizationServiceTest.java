package com.tradinglabs.vidingest.core.diarization.service;

import com.tradinglabs.vidingest.config.DiarizationConfig;
import com.tradinglabs.vidingest.core.diarization.client.DiarizationClient;
import com.tradinglabs.vidingest.core.diarization.domain.Speaker;
import com.tradinglabs.vidingest.core.diarization.dto.DiarizationResult;
import com.tradinglabs.vidingest.core.diarization.dto.DiarizationSegment;
import com.tradinglabs.vidingest.core.diarization.dto.DiarizationSpeaker;
import com.tradinglabs.vidingest.core.diarization.repo.SpeakerRepository;
import com.tradinglabs.vidingest.core.transcription.domain.TranscriptionSegment;
import com.tradinglabs.vidingest.core.transcription.repo.TranscriptionRepository;
import com.tradinglabs.vidingest.core.transcription.repo.TranscriptionSegmentRepository;
import com.tradinglabs.vidingest.videos.domain.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the speaker → transcription-segment time-overlap assignment in
 * {@link DiarizationService#persistAndAssign(Video, java.util.UUID, DiarizationResult)}.
 *
 * <p>We don't run ffmpeg here — that's covered by the live-sidecar tests under
 * {@code DiarizationClientTest} and the integration tests. This file focuses on the
 * Java-side overlap logic that determines which speaker wins for each transcript segment.
 */
@ExtendWith(MockitoExtension.class)
class DiarizationServiceTest {

    @Mock private DiarizationClient diarizationClient;
    @Mock private SpeakerRepository speakerRepository;
    @Mock private TranscriptionRepository transcriptionRepository;
    @Mock private TranscriptionSegmentRepository transcriptionSegmentRepository;

    private DiarizationConfig config;
    private DiarizationService service;

    @BeforeEach
    void setUp() {
        config = new DiarizationConfig();
        config.setMinOverlapSeconds(0.25);
        service = new DiarizationService(
                diarizationClient,
                config,
                speakerRepository,
                transcriptionRepository,
                transcriptionSegmentRepository,
                TransactionOperations.withoutTransaction(),
                Duration.ofMinutes(20)
        );
    }

    @Test
    void assignsDominantSpeakerByTimeOverlap() {
        Video video = video();
        UUID transcriptionId = UUID.randomUUID();

        // Diarization windows: [0,5] = SPEAKER_00, [5,10] = SPEAKER_01.
        DiarizationResult diarResult = new DiarizationResult(
                List.of(
                        new DiarizationSegment(0.0f, 5.0f, "SPEAKER_00"),
                        new DiarizationSegment(5.0f, 10.0f, "SPEAKER_01")
                ),
                List.of(
                        new DiarizationSpeaker("SPEAKER_00", null),
                        new DiarizationSpeaker("SPEAKER_01", null)
                )
        );

        // Transcription segments:
        //   seg0 [0,2]   → wholly within SPEAKER_00
        //   seg1 [2,4.5] → wholly within SPEAKER_00 (4.5s overlap)
        //   seg2 [4,6]   → 1s overlap with SPEAKER_00, 1s with SPEAKER_01 (tie - whichever scans first wins)
        //   seg3 [6,9]   → wholly within SPEAKER_01
        TranscriptionSegment seg0 = ts(0.0f, 2.0f);
        TranscriptionSegment seg1 = ts(2.0f, 4.5f);
        TranscriptionSegment seg2 = ts(4.0f, 6.0f);
        TranscriptionSegment seg3 = ts(6.0f, 9.0f);

        when(transcriptionSegmentRepository.findByTranscriptionIdOrderByStartSecondsAsc(transcriptionId))
                .thenReturn(List.of(seg0, seg1, seg2, seg3));

        // Persist returns a Speaker with a generated id.
        Map<String, UUID> persistedIds = new HashMap<>();
        when(speakerRepository.save(any(Speaker.class))).thenAnswer(inv -> {
            Speaker s = inv.getArgument(0);
            UUID id = UUID.randomUUID();
            s.setId(id);
            persistedIds.put(s.getLabel(), id);
            return s;
        });

        service.persistAndAssign(video, transcriptionId, diarResult);

        // Cleared prior speakers for the video first.
        verify(speakerRepository, times(1)).deleteByVideo_Id(video.getId());

        // 2 speakers persisted.
        verify(speakerRepository, times(2)).save(any(Speaker.class));

        // Segments saved with assigned speaker_id.
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<TranscriptionSegment>> savedCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(transcriptionSegmentRepository).saveAll(savedCaptor.capture());

        List<TranscriptionSegment> saved = savedCaptor.getValue();
        assertThat(saved).hasSize(4);
        assertThat(saved.get(0).getSpeakerId()).isEqualTo(persistedIds.get("SPEAKER_00"));
        assertThat(saved.get(1).getSpeakerId()).isEqualTo(persistedIds.get("SPEAKER_00"));
        // seg2: SPEAKER_00 scans first and has equal overlap → wins because the scan stops
        // at "strictly greater" comparison. Documents the deterministic tie-break.
        assertThat(saved.get(2).getSpeakerId()).isEqualTo(persistedIds.get("SPEAKER_00"));
        assertThat(saved.get(3).getSpeakerId()).isEqualTo(persistedIds.get("SPEAKER_01"));
    }

    @Test
    void clearsSpeakerIdWhenNoOverlapMeetsThreshold() {
        Video video = video();
        UUID transcriptionId = UUID.randomUUID();
        UUID priorSpeakerId = UUID.randomUUID();

        DiarizationResult diarResult = new DiarizationResult(
                // Only one window, after the transcript ends.
                List.of(new DiarizationSegment(100.0f, 110.0f, "SPEAKER_00")),
                List.of(new DiarizationSpeaker("SPEAKER_00", null))
        );

        TranscriptionSegment seg = ts(0.0f, 5.0f);
        seg.setSpeakerId(priorSpeakerId);  // stale value from a previous diarization run

        when(transcriptionSegmentRepository.findByTranscriptionIdOrderByStartSecondsAsc(transcriptionId))
                .thenReturn(List.of(seg));
        when(speakerRepository.save(any(Speaker.class))).thenAnswer(inv -> {
            Speaker s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        service.persistAndAssign(video, transcriptionId, diarResult);

        // Stale speaker_id should be cleared since no new window overlaps.
        assertThat(seg.getSpeakerId()).isNull();
    }

    @Test
    void skipsSegmentsBelowMinOverlapThreshold() {
        Video video = video();
        UUID transcriptionId = UUID.randomUUID();
        config.setMinOverlapSeconds(1.0);  // require ≥1s of overlap

        DiarizationResult diarResult = new DiarizationResult(
                // SPEAKER_00 window only overlaps seg by 0.2s — below threshold.
                List.of(new DiarizationSegment(4.8f, 5.0f, "SPEAKER_00")),
                List.of(new DiarizationSpeaker("SPEAKER_00", null))
        );

        TranscriptionSegment seg = ts(0.0f, 5.0f);

        when(transcriptionSegmentRepository.findByTranscriptionIdOrderByStartSecondsAsc(transcriptionId))
                .thenReturn(List.of(seg));
        when(speakerRepository.save(any(Speaker.class))).thenAnswer(inv -> {
            Speaker s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        service.persistAndAssign(video, transcriptionId, diarResult);

        assertThat(seg.getSpeakerId()).isNull();
    }

    @Test
    void createsSpeakerRowsForLabelsThatOnlyAppearInSegments() {
        Video video = video();
        UUID transcriptionId = UUID.randomUUID();

        DiarizationResult diarResult = new DiarizationResult(
                List.of(
                        new DiarizationSegment(0.0f, 1.0f, "SPEAKER_99")  // not declared in `speakers`
                ),
                List.of()  // empty top-level speakers array
        );

        when(transcriptionSegmentRepository.findByTranscriptionIdOrderByStartSecondsAsc(transcriptionId))
                .thenReturn(List.of(ts(0.0f, 1.0f)));
        when(speakerRepository.save(any(Speaker.class))).thenAnswer(inv -> {
            Speaker s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        service.persistAndAssign(video, transcriptionId, diarResult);

        ArgumentCaptor<Speaker> spk = ArgumentCaptor.forClass(Speaker.class);
        verify(speakerRepository).save(spk.capture());
        assertThat(spk.getValue().getLabel()).isEqualTo("SPEAKER_99");
    }

    @Test
    void noSegmentsInResultMeansNoSegmentSave() {
        Video video = video();
        UUID transcriptionId = UUID.randomUUID();

        DiarizationResult diarResult = new DiarizationResult(
                List.of(),
                List.of(new DiarizationSpeaker("SPEAKER_00", null))
        );

        when(speakerRepository.save(any(Speaker.class))).thenAnswer(inv -> {
            Speaker s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        service.persistAndAssign(video, transcriptionId, diarResult);

        // Speaker was still saved (declared up-front), but transcript segments untouched.
        verify(speakerRepository, times(1)).save(any(Speaker.class));
        verify(transcriptionSegmentRepository, org.mockito.Mockito.never()).saveAll(anyList());
        verify(transcriptionSegmentRepository, org.mockito.Mockito.never())
                .findByTranscriptionIdOrderByStartSecondsAsc(eq(transcriptionId));
    }

    private static Video video() {
        Video v = new Video();
        v.setId(UUID.randomUUID());
        return v;
    }

    private static TranscriptionSegment ts(float start, float end) {
        return TranscriptionSegment.builder()
                .id(UUID.randomUUID())
                .startSeconds(start)
                .endSeconds(end)
                .text("seg")
                .build();
    }
}
