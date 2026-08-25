package com.tradinglabs.vidingest.core.fusion.service;

import com.tradinglabs.vidingest.config.FusionConfig;
import com.tradinglabs.vidingest.core.frames.domain.SamplingReason;
import com.tradinglabs.vidingest.core.frames.domain.VideoFrame;
import com.tradinglabs.vidingest.core.frames.repo.VideoFrameRepository;
import com.tradinglabs.vidingest.core.fusion.domain.MultimodalSegment;
import com.tradinglabs.vidingest.core.fusion.repo.MultimodalSegmentRepository;
import com.tradinglabs.vidingest.core.ocr.domain.OcrResult;
import com.tradinglabs.vidingest.core.ocr.repo.OcrResultRepository;
import com.tradinglabs.vidingest.core.transcription.domain.Transcription;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Java tests for {@link SegmentFusionService}. We use mocked JPA repos so we can pin
 * down the windowing/dedup/idempotency behaviour without standing up a database.
 */
@ExtendWith(MockitoExtension.class)
class SegmentFusionServiceTest {

    @Mock private TranscriptionRepository transcriptionRepository;
    @Mock private TranscriptionSegmentRepository transcriptionSegmentRepository;
    @Mock private VideoFrameRepository videoFrameRepository;
    @Mock private OcrResultRepository ocrResultRepository;
    @Mock private MultimodalSegmentRepository multimodalSegmentRepository;

    private FusionConfig config;
    private SegmentFusionService service;

    @BeforeEach
    void setUp() {
        config = new FusionConfig();
        config.setEnabled(true);
        config.setWindowSeconds(30.0);
        config.setWindowOverlapSeconds(5.0);
        config.setMinWindowSeconds(1.0);
        config.setMaxSegmentsPerVideo(2000);
        service = new SegmentFusionService(
                config,
                transcriptionRepository,
                transcriptionSegmentRepository,
                videoFrameRepository,
                ocrResultRepository,
                multimodalSegmentRepository,
                TransactionOperations.withoutTransaction()
        );
        // Lenient so the aggregate-only / validation tests that never reach the persistence
        // path don't fail Mockito's strict-stubbing check.
        lenient().when(multimodalSegmentRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<MultimodalSegment> in = inv.getArgument(0);
            java.util.List<MultimodalSegment> out = new java.util.ArrayList<>();
            in.forEach(out::add);
            return out;
        });
    }

    // -------------------- aggregate() (pure-function windowing) --------------------

    @Test
    void aggregateConcatenatesOverlappingTranscriptSegments() {
        UUID spk = UUID.randomUUID();
        List<TranscriptionSegment> segs = List.of(
                ts(0f, 5f, "Hello world", spk),
                ts(5f, 12f, "this is a test", spk),
                ts(40f, 45f, "later content", null)  // outside window
        );

        SegmentFusionService.WindowAggregation agg =
                SegmentFusionService.aggregate(0.0, 30.0, segs, List.of(), Map.of());

        assertThat(agg.transcriptText()).isEqualTo("Hello world this is a test");
        assertThat(agg.sortedSpeakerIds()).containsExactly(spk);
        assertThat(agg.ocrText()).isEmpty();
        assertThat(agg.isEmpty()).isFalse();
    }

    @Test
    void aggregateIncludesSegmentsTouchingTheWindowBoundary() {
        // Segment [28, 32] crosses the 30s boundary — should appear in both windows.
        TranscriptionSegment crossing = ts(28f, 32f, "near boundary", null);
        SegmentFusionService.WindowAggregation w0 =
                SegmentFusionService.aggregate(0.0, 30.0, List.of(crossing), List.of(), Map.of());
        SegmentFusionService.WindowAggregation w1 =
                SegmentFusionService.aggregate(25.0, 55.0, List.of(crossing), List.of(), Map.of());

        assertThat(w0.transcriptText()).contains("near boundary");
        assertThat(w1.transcriptText()).contains("near boundary");
    }

    @Test
    void aggregateExcludesSegmentsAtExactBoundaryEdge() {
        // Half-open intervals: a segment ending exactly at the window start should NOT
        // appear in that window (it belongs to the previous one).
        TranscriptionSegment endsAtStart = ts(20f, 30f, "previous", null);
        TranscriptionSegment startsAtEnd = ts(60f, 70f, "future", null);

        SegmentFusionService.WindowAggregation agg = SegmentFusionService.aggregate(
                30.0, 60.0, List.of(endsAtStart, startsAtEnd), List.of(), Map.of());

        assertThat(agg.transcriptText()).isEmpty();
    }

    @Test
    void aggregateDeduplicatesOcrLinesAcrossFrames() {
        // Two consecutive frames both containing the same subtitle line.
        UUID frameA = UUID.randomUUID();
        UUID frameB = UUID.randomUUID();
        VideoFrame fA = frame(frameA, 5.0);
        VideoFrame fB = frame(frameB, 12.0);

        OcrResult ocrA = ocr(fA, "Same subtitle");
        OcrResult ocrA2 = ocr(fA, "Brand new line");
        OcrResult ocrB = ocr(fB, "Same subtitle");  // duplicate

        Map<UUID, List<OcrResult>> ocrByFrame = new HashMap<>();
        ocrByFrame.put(frameA, List.of(ocrA, ocrA2));
        ocrByFrame.put(frameB, List.of(ocrB));

        SegmentFusionService.WindowAggregation agg = SegmentFusionService.aggregate(
                0.0, 30.0, List.of(), List.of(fA, fB), ocrByFrame);

        // "Same subtitle" should appear once even though it was detected on both frames.
        assertThat(agg.ocrText()).isEqualTo("Same subtitle Brand new line");
    }

    @Test
    void aggregateCollectsDistinctSpeakerIdsInOrder() {
        UUID s1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID s2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        List<TranscriptionSegment> segs = List.of(
                ts(0f, 5f, "line", s1),
                ts(5f, 10f, "line", s2),
                ts(10f, 15f, "line", s1)  // duplicate; should appear once
        );

        SegmentFusionService.WindowAggregation agg =
                SegmentFusionService.aggregate(0.0, 30.0, segs, List.of(), Map.of());

        // LinkedHashSet preserves first-seen order: [s1, s2].
        assertThat(agg.speakerIds()).containsExactly(s1, s2);
    }

    @Test
    void aggregateIsEmptyWhenAllSignalsAreNull() {
        SegmentFusionService.WindowAggregation agg =
                SegmentFusionService.aggregate(0.0, 30.0, List.of(), List.of(), Map.of());
        assertThat(agg.isEmpty()).isTrue();
        assertThat(agg.transcriptText()).isEmpty();
        assertThat(agg.ocrText()).isEmpty();
        assertThat(agg.speakerIds()).isEmpty();
    }

    // -------------------- computeMaxEnd() --------------------

    @Test
    void computeMaxEndPicksLatestSignal() {
        double max = SegmentFusionService.computeMaxEnd(
                videoWithDuration(20),
                List.of(ts(0f, 12.5f, "x", null)),
                List.of(frame(UUID.randomUUID(), 18.0)),
                List.of()
        );
        // Transcript at 12.5, frame at 18, duration 20 — max is 20 (the video duration).
        assertThat(max).isCloseTo(20.0, within(1e-9));
    }

    @Test
    void computeMaxEndFallsBackToVideoDurationWhenNoRowsPresent() {
        double max = SegmentFusionService.computeMaxEnd(
                videoWithDuration(45), List.of(), List.of(), List.of());
        assertThat(max).isCloseTo(45.0, within(1e-9));
    }

    @Test
    void computeMaxEndReturnsZeroWhenNothingAtAll() {
        double max = SegmentFusionService.computeMaxEnd(new Video(), List.of(), List.of(), List.of());
        assertThat(max).isZero();
    }

    // -------------------- fuse() (end-to-end) --------------------

    @Test
    void fuseProducesDenseSegmentIndexesAndSkipsEmptyWindows() {
        Video video = videoWithDuration(70);
        Transcription t = transcription(video);
        when(transcriptionRepository.findByVideoId(video.getId())).thenReturn(Optional.of(t));
        when(transcriptionSegmentRepository.findByTranscriptionIdOrderByStartSecondsAsc(t.getId()))
                .thenReturn(List.of(
                        ts(0f, 10f, "first window content", null),
                        // Gap: nothing in window 1 [25,55].
                        ts(56f, 60f, "third window content", null)
                ));
        when(videoFrameRepository.findByVideo_IdOrderByTimestampSecondsAsc(video.getId()))
                .thenReturn(List.of());
        when(ocrResultRepository.findByVideoIdOrderByFrameTimestamp(video.getId()))
                .thenReturn(List.of());

        service.fuse(video);

        ArgumentCaptor<List<MultimodalSegment>> captor = listCaptor();
        verify(multimodalSegmentRepository).saveAll(captor.capture());
        List<MultimodalSegment> saved = captor.getValue();

        // Window 0 [0,30] has the first segment, window 1 [25,55] is empty (skipped),
        // window 2 [50,70] gets the third segment — but it gets segment_index = 1 in the
        // dense numbering, not 2.
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getSegmentIndex()).isZero();
        assertThat(saved.get(0).getStartSeconds()).isCloseTo(0.0, within(1e-9));
        assertThat(saved.get(0).getEndSeconds()).isCloseTo(30.0, within(1e-9));
        assertThat(saved.get(0).getTranscriptText()).contains("first window content");

        assertThat(saved.get(1).getSegmentIndex()).isEqualTo(1);
        assertThat(saved.get(1).getStartSeconds()).isCloseTo(50.0, within(1e-9));
        assertThat(saved.get(1).getEndSeconds()).isCloseTo(70.0, within(1e-9));
        assertThat(saved.get(1).getTranscriptText()).contains("third window content");
    }

    @Test
    void fuseIsIdempotentWipingPriorRowsFirst() {
        Video video = videoWithDuration(10);
        Transcription t = transcription(video);
        when(transcriptionRepository.findByVideoId(video.getId())).thenReturn(Optional.of(t));
        when(transcriptionSegmentRepository.findByTranscriptionIdOrderByStartSecondsAsc(t.getId()))
                .thenReturn(List.of(ts(0f, 5f, "hello", null)));
        when(videoFrameRepository.findByVideo_IdOrderByTimestampSecondsAsc(video.getId()))
                .thenReturn(List.of());
        when(ocrResultRepository.findByVideoIdOrderByFrameTimestamp(video.getId()))
                .thenReturn(List.of());

        service.fuse(video);

        var inOrder = org.mockito.Mockito.inOrder(multimodalSegmentRepository);
        inOrder.verify(multimodalSegmentRepository).deleteByVideo_Id(video.getId());
        inOrder.verify(multimodalSegmentRepository).flush();
        inOrder.verify(multimodalSegmentRepository).saveAll(any());
    }

    @Test
    void fusePersistsEmptyAfterWipeWhenNoSignalsAndStillReturnsCleanly() {
        Video video = videoWithDuration(30);
        // No transcript, no frames, no OCR — but video.durationSeconds gives us a timeline.
        // None of the empty windows produce content, so the result list is empty but the
        // wipe still runs so stale rows don't survive.
        when(transcriptionRepository.findByVideoId(video.getId())).thenReturn(Optional.empty());
        when(videoFrameRepository.findByVideo_IdOrderByTimestampSecondsAsc(video.getId()))
                .thenReturn(List.of());
        when(ocrResultRepository.findByVideoIdOrderByFrameTimestamp(video.getId()))
                .thenReturn(List.of());

        List<MultimodalSegment> result = service.fuse(video);

        assertThat(result).isEmpty();
        verify(multimodalSegmentRepository).deleteByVideo_Id(video.getId());
        verify(multimodalSegmentRepository, never()).saveAll(any());
    }

    @Test
    void fuseHandlesShortVideoAsSingleWindow() {
        Video video = videoWithDuration(8);
        Transcription t = transcription(video);
        when(transcriptionRepository.findByVideoId(video.getId())).thenReturn(Optional.of(t));
        when(transcriptionSegmentRepository.findByTranscriptionIdOrderByStartSecondsAsc(t.getId()))
                .thenReturn(List.of(ts(0f, 7f, "short clip", null)));
        when(videoFrameRepository.findByVideo_IdOrderByTimestampSecondsAsc(video.getId()))
                .thenReturn(List.of());
        when(ocrResultRepository.findByVideoIdOrderByFrameTimestamp(video.getId()))
                .thenReturn(List.of());

        service.fuse(video);

        ArgumentCaptor<List<MultimodalSegment>> captor = listCaptor();
        verify(multimodalSegmentRepository).saveAll(captor.capture());
        List<MultimodalSegment> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getStartSeconds()).isCloseTo(0.0, within(1e-9));
        assertThat(saved.get(0).getEndSeconds()).isCloseTo(8.0, within(1e-9));
    }

    @Test
    void fuseEnforcesMaxSegmentsCap() {
        config.setMaxSegmentsPerVideo(2);
        config.setWindowSeconds(10.0);
        config.setWindowOverlapSeconds(0.0);  // step = 10

        Video video = videoWithDuration(60);
        Transcription t = transcription(video);
        when(transcriptionRepository.findByVideoId(video.getId())).thenReturn(Optional.of(t));
        // One transcript segment per window so each window has content.
        when(transcriptionSegmentRepository.findByTranscriptionIdOrderByStartSecondsAsc(t.getId()))
                .thenReturn(List.of(
                        ts(0f, 5f, "w0", null),
                        ts(10f, 15f, "w1", null),
                        ts(20f, 25f, "w2", null),
                        ts(30f, 35f, "w3", null)
                ));
        when(videoFrameRepository.findByVideo_IdOrderByTimestampSecondsAsc(video.getId()))
                .thenReturn(List.of());
        when(ocrResultRepository.findByVideoIdOrderByFrameTimestamp(video.getId()))
                .thenReturn(List.of());

        service.fuse(video);

        ArgumentCaptor<List<MultimodalSegment>> captor = listCaptor();
        verify(multimodalSegmentRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);  // capped at maxSegmentsPerVideo
    }

    @Test
    void fuseFusesAcrossAllThreeSignals() {
        UUID spk = UUID.randomUUID();
        UUID frameId = UUID.randomUUID();

        Video video = videoWithDuration(20);
        Transcription t = transcription(video);
        when(transcriptionRepository.findByVideoId(video.getId())).thenReturn(Optional.of(t));
        when(transcriptionSegmentRepository.findByTranscriptionIdOrderByStartSecondsAsc(t.getId()))
                .thenReturn(List.of(ts(0f, 10f, "spoken text", spk)));

        VideoFrame f = frame(frameId, 5.0);
        when(videoFrameRepository.findByVideo_IdOrderByTimestampSecondsAsc(video.getId()))
                .thenReturn(List.of(f));
        OcrResult ocrLine = ocr(f, "VISIBLE TEXT");
        when(ocrResultRepository.findByVideoIdOrderByFrameTimestamp(video.getId()))
                .thenReturn(List.of(ocrLine));

        service.fuse(video);

        ArgumentCaptor<List<MultimodalSegment>> captor = listCaptor();
        verify(multimodalSegmentRepository).saveAll(captor.capture());
        List<MultimodalSegment> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        MultimodalSegment seg = saved.get(0);
        assertThat(seg.getTranscriptText()).isEqualTo("spoken text");
        assertThat(seg.getOcrText()).isEqualTo("VISIBLE TEXT");
        assertThat(seg.getSpeakerIds()).containsExactly(spk);
    }

    @Test
    void fuseValidatesWindowConfig() {
        config.setWindowSeconds(0);
        Video video = videoWithDuration(10);
        assertThatThrownBy(() -> service.fuse(video))
                .isInstanceOf(FusionFailureException.class)
                .hasMessageContaining("window-seconds must be > 0");
    }

    @Test
    void fuseValidatesOverlapLessThanWindow() {
        config.setWindowSeconds(10);
        config.setWindowOverlapSeconds(10);  // equal to window → infinite loop hazard
        Video video = videoWithDuration(30);
        assertThatThrownBy(() -> service.fuse(video))
                .isInstanceOf(FusionFailureException.class)
                .hasMessageContaining("window-overlap-seconds must be in [0, window)");
    }

    // -------------------- helpers --------------------

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<MultimodalSegment>> listCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }

    private static Video videoWithDuration(int durationSeconds) {
        Video v = new Video();
        v.setId(UUID.randomUUID());
        v.setDurationSeconds(durationSeconds);
        return v;
    }

    private static Transcription transcription(Video video) {
        Transcription t = new Transcription();
        t.setId(UUID.randomUUID());
        t.setVideo(video);
        return t;
    }

    private static TranscriptionSegment ts(float start, float end, String text, UUID speakerId) {
        return TranscriptionSegment.builder()
                .id(UUID.randomUUID())
                .startSeconds(start)
                .endSeconds(end)
                .text(text)
                .speakerId(speakerId)
                .build();
    }

    private static VideoFrame frame(UUID id, double ts) {
        return VideoFrame.builder()
                .id(id)
                .timestampSeconds(ts)
                .filePath("/tmp/x.jpg")
                .frameIndex(0)
                .samplingReason(SamplingReason.INTERVAL)
                .build();
    }

    private static OcrResult ocr(VideoFrame f, String text) {
        return OcrResult.builder()
                .id(UUID.randomUUID())
                .frame(f)
                .text(text)
                .confidence(0.9f)
                .language("en")
                .build();
    }

    // Silence unused-import warnings used only in some test paths.
    @SuppressWarnings("unused") private static final Set<UUID> UNUSED = Set.of();
    @SuppressWarnings("unused") private static int unused() { times(0); return 0; }
}
