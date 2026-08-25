package com.tradinglabs.vidingest.core.ocr.service;

import com.tradinglabs.vidingest.config.OcrConfig;
import com.tradinglabs.vidingest.core.frames.domain.SamplingReason;
import com.tradinglabs.vidingest.core.frames.domain.VideoFrame;
import com.tradinglabs.vidingest.core.frames.repo.VideoFrameRepository;
import com.tradinglabs.vidingest.core.ocr.client.PaddleOcrClient;
import com.tradinglabs.vidingest.core.ocr.domain.OcrResult;
import com.tradinglabs.vidingest.core.ocr.dto.OcrLine;
import com.tradinglabs.vidingest.core.ocr.dto.OcrPageResult;
import com.tradinglabs.vidingest.core.ocr.repo.OcrResultRepository;
import com.tradinglabs.vidingest.videos.domain.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OcrService}. We mock the {@link PaddleOcrClient} and the JPA
 * repositories — the goal is to lock down the filter/skip/cap/idempotency rules without
 * spawning the Python sidecar or hitting a database.
 */
@ExtendWith(MockitoExtension.class)
class OcrServiceTest {

    @Mock
    private PaddleOcrClient paddleOcrClient;
    @Mock
    private VideoFrameRepository videoFrameRepository;
    @Mock
    private OcrResultRepository ocrResultRepository;

    private OcrConfig config;
    private OcrService service;

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() {
        config = new OcrConfig();
        config.setEnabled(true);
        config.setLanguages(List.of("en"));
        config.setMinConfidence(0.5);
        config.setMinLinesPerFrame(1);
        config.setMaxResultsPerVideo(10_000);
        service = new OcrService(config, paddleOcrClient, videoFrameRepository, ocrResultRepository);
    }

    @Test
    void returnsZeroAndSkipsClientWhenNoFrames() {
        Video video = video();
        when(videoFrameRepository.findByVideo_IdOrderByTimestampSecondsAsc(video.getId()))
                .thenReturn(List.of());

        int persisted = service.ocrAllFrames(video);

        assertThat(persisted).isZero();
        verify(paddleOcrClient, never()).ocr(any());
        verify(ocrResultRepository, never()).deleteByVideoId(any());
        verify(ocrResultRepository, never()).saveAll(any());
    }

    @Test
    void filtersLowConfidenceLinesAndPersistsSurvivors() throws Exception {
        Video video = video();
        Path jpg1 = writeFakeJpg(tmp.resolve("0001.jpg"));
        Path jpg2 = writeFakeJpg(tmp.resolve("0002.jpg"));

        VideoFrame f1 = frame(video, jpg1, 0);
        VideoFrame f2 = frame(video, jpg2, 1);
        when(videoFrameRepository.findByVideo_IdOrderByTimestampSecondsAsc(video.getId()))
                .thenReturn(List.of(f1, f2));

        when(paddleOcrClient.ocr(jpg1)).thenReturn(new OcrPageResult(List.of(
                new OcrLine("good line", 0.9f, List.of(List.of(0.0, 0.0)), "en"),
                new OcrLine("borderline", 0.49f, null, "en"),  // below threshold
                new OcrLine("  ", 0.99f, null, "en")            // blank text
        )));
        when(paddleOcrClient.ocr(jpg2)).thenReturn(new OcrPageResult(List.of(
                new OcrLine("frame2 line", 0.8f, null, "en")
        )));

        when(ocrResultRepository.deleteByVideoId(video.getId())).thenReturn(0);

        int persisted = service.ocrAllFrames(video);

        assertThat(persisted).isEqualTo(2);

        ArgumentCaptor<List<OcrResult>> captor = listCaptor();
        verify(ocrResultRepository).saveAll(captor.capture());
        List<OcrResult> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(OcrResult::getText).containsExactly("good line", "frame2 line");
        assertThat(saved).extracting(OcrResult::getConfidence).containsExactly(0.9f, 0.8f);
    }

    @Test
    void skipsFramesBelowMinLinesPerFrame() throws Exception {
        config.setMinLinesPerFrame(2);  // require at least 2 surviving lines per frame
        Video video = video();
        Path jpg1 = writeFakeJpg(tmp.resolve("0001.jpg"));
        Path jpg2 = writeFakeJpg(tmp.resolve("0002.jpg"));

        VideoFrame f1 = frame(video, jpg1, 0);
        VideoFrame f2 = frame(video, jpg2, 1);
        when(videoFrameRepository.findByVideo_IdOrderByTimestampSecondsAsc(video.getId()))
                .thenReturn(List.of(f1, f2));

        // Frame 1: only one good line — skip entire frame.
        when(paddleOcrClient.ocr(jpg1)).thenReturn(new OcrPageResult(List.of(
                new OcrLine("only one", 0.9f, null, "en")
        )));
        // Frame 2: two good lines — keep both.
        when(paddleOcrClient.ocr(jpg2)).thenReturn(new OcrPageResult(List.of(
                new OcrLine("first", 0.9f, null, "en"),
                new OcrLine("second", 0.8f, null, "en")
        )));

        int persisted = service.ocrAllFrames(video);

        assertThat(persisted).isEqualTo(2);
        ArgumentCaptor<List<OcrResult>> captor = listCaptor();
        verify(ocrResultRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(OcrResult::getText).containsExactly("first", "second");
    }

    @Test
    void enforcesMaxResultsCapAndTruncatesTail() throws Exception {
        config.setMaxResultsPerVideo(2);
        Video video = video();
        Path jpg1 = writeFakeJpg(tmp.resolve("0001.jpg"));
        Path jpg2 = writeFakeJpg(tmp.resolve("0002.jpg"));

        VideoFrame f1 = frame(video, jpg1, 0);
        VideoFrame f2 = frame(video, jpg2, 1);
        when(videoFrameRepository.findByVideo_IdOrderByTimestampSecondsAsc(video.getId()))
                .thenReturn(List.of(f1, f2));

        // Frame 1 produces 3 lines → only 2 fit under the cap; frame 2 should be skipped.
        when(paddleOcrClient.ocr(jpg1)).thenReturn(new OcrPageResult(List.of(
                new OcrLine("a", 0.9f, null, "en"),
                new OcrLine("b", 0.9f, null, "en"),
                new OcrLine("c", 0.9f, null, "en")
        )));

        int persisted = service.ocrAllFrames(video);

        assertThat(persisted).isEqualTo(2);
        verify(paddleOcrClient, times(1)).ocr(any());  // only frame 1 hit the sidecar
        ArgumentCaptor<List<OcrResult>> captor = listCaptor();
        verify(ocrResultRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(OcrResult::getText).containsExactly("a", "b");
    }

    @Test
    void skipsFramesWhoseFileIsMissing() throws Exception {
        Video video = video();
        Path jpg1 = writeFakeJpg(tmp.resolve("0001.jpg"));
        Path jpg2 = tmp.resolve("missing.jpg");   // never created

        VideoFrame f1 = frame(video, jpg1, 0);
        VideoFrame f2 = frame(video, jpg2, 1);
        when(videoFrameRepository.findByVideo_IdOrderByTimestampSecondsAsc(video.getId()))
                .thenReturn(List.of(f1, f2));

        when(paddleOcrClient.ocr(jpg1)).thenReturn(new OcrPageResult(List.of(
                new OcrLine("kept", 0.9f, null, "en")
        )));

        int persisted = service.ocrAllFrames(video);

        assertThat(persisted).isEqualTo(1);
        // Missing-file frame never hit the sidecar.
        verify(paddleOcrClient, times(1)).ocr(any());
    }

    @Test
    void singleFrameFailureContinuesProcessingOtherFrames() throws Exception {
        Video video = video();
        Path jpg1 = writeFakeJpg(tmp.resolve("0001.jpg"));
        Path jpg2 = writeFakeJpg(tmp.resolve("0002.jpg"));

        VideoFrame f1 = frame(video, jpg1, 0);
        VideoFrame f2 = frame(video, jpg2, 1);
        when(videoFrameRepository.findByVideo_IdOrderByTimestampSecondsAsc(video.getId()))
                .thenReturn(List.of(f1, f2));

        when(paddleOcrClient.ocr(jpg1)).thenThrow(new OcrFailureException("boom"));
        when(paddleOcrClient.ocr(jpg2)).thenReturn(new OcrPageResult(List.of(
                new OcrLine("survivor", 0.9f, null, "en")
        )));

        int persisted = service.ocrAllFrames(video);

        assertThat(persisted).isEqualTo(1);
    }

    @Test
    void allFramesFailingPropagatesAsHardFailure() throws Exception {
        Video video = video();
        Path jpg1 = writeFakeJpg(tmp.resolve("0001.jpg"));
        Path jpg2 = writeFakeJpg(tmp.resolve("0002.jpg"));

        VideoFrame f1 = frame(video, jpg1, 0);
        VideoFrame f2 = frame(video, jpg2, 1);
        when(videoFrameRepository.findByVideo_IdOrderByTimestampSecondsAsc(video.getId()))
                .thenReturn(List.of(f1, f2));

        when(paddleOcrClient.ocr(any())).thenThrow(new OcrFailureException("sidecar down"));

        assertThatThrownBy(() -> service.ocrAllFrames(video))
                .isInstanceOf(OcrFailureException.class)
                .hasMessageContaining("OCR failed for every frame");
    }

    @Test
    void wipesPriorResultsBeforePersistingNewOnes() throws Exception {
        Video video = video();
        Path jpg1 = writeFakeJpg(tmp.resolve("0001.jpg"));

        VideoFrame f1 = frame(video, jpg1, 0);
        when(videoFrameRepository.findByVideo_IdOrderByTimestampSecondsAsc(video.getId()))
                .thenReturn(List.of(f1));
        when(paddleOcrClient.ocr(jpg1)).thenReturn(new OcrPageResult(List.of(
                new OcrLine("new", 0.9f, null, "en")
        )));

        service.ocrAllFrames(video);

        // Wipe happens once, before the saveAll.
        var inOrder = org.mockito.Mockito.inOrder(ocrResultRepository);
        inOrder.verify(ocrResultRepository).deleteByVideoId(video.getId());
        inOrder.verify(ocrResultRepository).flush();
        inOrder.verify(ocrResultRepository).saveAll(any());
    }

    /**
     * The prior rows are wiped before the sidecar loop, so "no frame was readable" must fail
     * loudly. It used to return 0 with an HTTP 200 — every OCR row for the video destroyed and
     * nothing reporting it — because a missing JPG counted as a skip rather than a failure.
     */
    @Test
    void allFramesMissingTheirJpgPropagatesAsHardFailure() {
        Video video = video();
        VideoFrame f1 = frame(video, tmp.resolve("gone-1.jpg"), 0);   // never created
        VideoFrame f2 = frame(video, tmp.resolve("gone-2.jpg"), 1);   // never created
        when(videoFrameRepository.findByVideo_IdOrderByTimestampSecondsAsc(video.getId()))
                .thenReturn(List.of(f1, f2));

        assertThatThrownBy(() -> service.ocrAllFrames(video))
                .isInstanceOf(OcrFailureException.class)
                .hasMessageContaining("OCR failed for every frame")
                .hasMessageContaining("missingJpgs=2");

        verify(paddleOcrClient, never()).ocr(any());
        verify(ocrResultRepository, never()).saveAll(any());
    }

    /** The other side of that guard: no on-screen text is a legitimate zero, not a failure. */
    @Test
    void blankVideoWithNoOnScreenTextCompletesWithZeroRows() throws Exception {
        Video video = video();
        Path jpg1 = writeFakeJpg(tmp.resolve("0001.jpg"));
        Path jpg2 = writeFakeJpg(tmp.resolve("0002.jpg"));

        VideoFrame f1 = frame(video, jpg1, 0);
        VideoFrame f2 = frame(video, jpg2, 1);
        when(videoFrameRepository.findByVideo_IdOrderByTimestampSecondsAsc(video.getId()))
                .thenReturn(List.of(f1, f2));
        when(paddleOcrClient.ocr(any())).thenReturn(new OcrPageResult(List.of()));

        assertThat(service.ocrAllFrames(video)).isZero();

        verify(paddleOcrClient, times(2)).ocr(any());
        verify(ocrResultRepository, never()).saveAll(any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<OcrResult>> listCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }

    private static Video video() {
        Video v = new Video();
        v.setId(UUID.randomUUID());
        return v;
    }

    private static VideoFrame frame(Video v, Path filePath, int index) {
        return VideoFrame.builder()
                .id(UUID.randomUUID())
                .video(v)
                .frameIndex(index)
                .timestampSeconds((double) index * 10.0)
                .filePath(filePath.toString())
                .samplingReason(SamplingReason.INTERVAL)
                .build();
    }

    private static Path writeFakeJpg(Path p) throws IOException {
        Files.write(p, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        return p;
    }

    // Reference so unused-import strip-tools don't tag List<OcrLine> below.
    private static final List<OcrLine> UNUSED_SIGNAL = new ArrayList<>();
}
