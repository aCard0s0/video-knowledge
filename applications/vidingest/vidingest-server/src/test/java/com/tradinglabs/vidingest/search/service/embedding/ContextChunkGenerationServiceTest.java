package com.tradinglabs.vidingest.search.service.embedding;

import com.tradinglabs.vidingest.core.fusion.domain.MultimodalSegment;
import com.tradinglabs.vidingest.core.fusion.repo.MultimodalSegmentRepository;
import com.tradinglabs.vidingest.core.transcription.domain.Transcription;
import com.tradinglabs.vidingest.core.transcription.repo.TranscriptionRepository;
import com.tradinglabs.vidingest.search.domain.ContextChunk;
import com.tradinglabs.vidingest.search.repo.ContextChunkRepository;
import com.tradinglabs.vidingest.videos.domain.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ContextChunkGenerationService}. Two source paths matter:
 * <ul>
 *   <li>M7 multimodal — when {@code vidingest_multimodal_segments} exists, chunks include
 *       transcript + OCR fused per window, separated by the {@code [VISUAL]} marker.</li>
 *   <li>Pre-M7 transcript fallback — videos without multimodal segments produce the same
 *       chunk boundaries the service did before this milestone (regression guard).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ContextChunkGenerationServiceTest {

    @Mock private TranscriptionRepository transcriptionRepository;
    @Mock private ContextChunkRepository contextChunkRepository;
    @Mock private EmbeddingsClient embeddingsClient;
    @Mock private MultimodalSegmentRepository multimodalSegmentRepository;

    private ContextChunkGenerationService service;

    @BeforeEach
    void setUp() {
        service = new ContextChunkGenerationService(
                transcriptionRepository,
                contextChunkRepository,
                embeddingsClient,
                multimodalSegmentRepository
        );
        // saveAll just echoes whatever was passed in; declared lenient so tests that wipe
        // and return 0 (no saveAll call) don't fail Mockito's strict-stubbing check.
        lenient().when(contextChunkRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<ContextChunk> in = inv.getArgument(0);
            List<ContextChunk> out = new ArrayList<>();
            in.forEach(out::add);
            return out;
        });
    }

    // -------------------- formatSegment() (pure-function) --------------------

    @Test
    void formatSegmentJoinsTranscriptAndOcrWithVisualMarker() {
        MultimodalSegment seg = segment("spoken words here", "VISIBLE TEXT");
        assertThat(ContextChunkGenerationService.formatSegment(seg))
                .isEqualTo("spoken words here [VISUAL] VISIBLE TEXT");
    }

    @Test
    void formatSegmentOmitsMarkerWhenOcrIsAbsent() {
        MultimodalSegment seg = segment("only spoken", null);
        assertThat(ContextChunkGenerationService.formatSegment(seg))
                .isEqualTo("only spoken");
    }

    @Test
    void formatSegmentEmitsVisualPrefixWhenOnlyOcrIsPresent() {
        MultimodalSegment seg = segment(null, "ONLY VISIBLE");
        assertThat(ContextChunkGenerationService.formatSegment(seg))
                .isEqualTo("[VISUAL] ONLY VISIBLE");
    }

    @Test
    void formatSegmentReturnsEmptyForBlankOrNullSegment() {
        assertThat(ContextChunkGenerationService.formatSegment(null)).isEmpty();
        assertThat(ContextChunkGenerationService.formatSegment(segment(null, null))).isEmpty();
        assertThat(ContextChunkGenerationService.formatSegment(segment("  ", "  "))).isEmpty();
    }

    @Test
    void formatSegmentTrimsBothSignals() {
        MultimodalSegment seg = segment("   leading and trailing   ", "  OCR  ");
        assertThat(ContextChunkGenerationService.formatSegment(seg))
                .isEqualTo("leading and trailing [VISUAL] OCR");
    }

    // -------------------- chunksFromMultimodal() (pure-function) --------------------

    @Test
    void chunksFromMultimodalEmitsOneChunkPerSegmentWhenSmall() {
        List<MultimodalSegment> segs = List.of(
                segment("first transcript", "first ocr"),
                segment("second transcript", null),
                segment(null, "third ocr only")
        );

        List<String> chunks = ContextChunkGenerationService.chunksFromMultimodal(segs);

        assertThat(chunks).containsExactly(
                "first transcript [VISUAL] first ocr",
                "second transcript",
                "[VISUAL] third ocr only"
        );
    }

    @Test
    void chunksFromMultimodalSubChunksOversizedSegments() {
        // A single segment with >900+150 chars must be subdivided by the sliding-window
        // chunker rather than passed through whole.
        String huge = "word ".repeat(400);  // ~2000 chars
        MultimodalSegment seg = segment(huge.trim(), null);

        List<String> chunks = ContextChunkGenerationService.chunksFromMultimodal(List.of(seg));

        assertThat(chunks).hasSizeGreaterThan(1);
        for (String c : chunks) {
            assertThat(c.length()).isLessThanOrEqualTo(900 + 1);
        }
    }

    @Test
    void chunksFromMultimodalSkipsEmptySegments() {
        List<MultimodalSegment> segs = List.of(
                segment("real", null),
                segment(null, null),  // both signals blank
                segment("also real", null)
        );

        List<String> chunks = ContextChunkGenerationService.chunksFromMultimodal(segs);

        assertThat(chunks).containsExactly("real", "also real");
    }

    // -------------------- regenerateFor() multimodal path --------------------

    @Test
    void regenerateForUsesMultimodalSegmentsWhenAvailable() throws IOException {
        Video video = video();

        when(multimodalSegmentRepository.findByVideo_IdOrderBySegmentIndexAsc(video.getId()))
                .thenReturn(List.of(
                        segment("transcript A", "OCR A"),
                        segment("transcript B", null)
                ));

        when(embeddingsClient.embed(anyList())).thenAnswer(inv -> {
            List<String> in = inv.getArgument(0);
            List<float[]> vecs = new ArrayList<>();
            for (int i = 0; i < in.size(); i++) {
                vecs.add(new float[]{(float) i});
            }
            return vecs;
        });

        int persisted = service.regenerateFor(video);

        assertThat(persisted).isEqualTo(2);
        verify(transcriptionRepository, never()).findByVideoId(any());
        ArgumentCaptor<List<ContextChunk>> captor = listCaptor();
        verify(contextChunkRepository).saveAll(captor.capture());
        List<ContextChunk> saved = captor.getValue();
        assertThat(saved).extracting(ContextChunk::getContent).containsExactly(
                "transcript A [VISUAL] OCR A",
                "transcript B"
        );
    }

    @Test
    void regenerateForReplacesPriorChunksAtomically() throws IOException {
        Video video = video();
        when(multimodalSegmentRepository.findByVideo_IdOrderBySegmentIndexAsc(video.getId()))
                .thenReturn(List.of(segment("text", null)));
        when(embeddingsClient.embed(anyList())).thenReturn(List.of(new float[]{0.0f}));

        service.regenerateFor(video);

        var inOrder = org.mockito.Mockito.inOrder(contextChunkRepository);
        inOrder.verify(contextChunkRepository).deleteByVideoId(video.getId());
        inOrder.verify(contextChunkRepository).saveAll(any());
    }

    // -------------------- regenerateFor() transcript fallback --------------------

    @Test
    void regenerateForFallsBackToTranscriptWhenNoMultimodalSegments() throws IOException {
        Video video = video();

        when(multimodalSegmentRepository.findByVideo_IdOrderBySegmentIndexAsc(video.getId()))
                .thenReturn(List.of());

        Transcription tx = transcription(video, "this is the transcript full text");
        when(transcriptionRepository.findByVideoId(video.getId())).thenReturn(Optional.of(tx));
        when(embeddingsClient.embed(anyList())).thenReturn(List.of(new float[]{0.0f}));

        int persisted = service.regenerateFor(video);

        assertThat(persisted).isEqualTo(1);
        ArgumentCaptor<List<ContextChunk>> captor = listCaptor();
        verify(contextChunkRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getContent()).contains("this is the transcript");
    }

    @Test
    void regenerateForFallsBackToTranscriptWhenMultimodalSegmentsAreAllBlank() throws IOException {
        Video video = video();

        // Segments exist but produce no usable text — fall back to transcript.
        when(multimodalSegmentRepository.findByVideo_IdOrderBySegmentIndexAsc(video.getId()))
                .thenReturn(List.of(segment("  ", "  "), segment(null, null)));

        Transcription tx = transcription(video, "fallback transcript");
        when(transcriptionRepository.findByVideoId(video.getId())).thenReturn(Optional.of(tx));
        when(embeddingsClient.embed(anyList())).thenReturn(List.of(new float[]{0.0f}));

        int persisted = service.regenerateFor(video);

        assertThat(persisted).isEqualTo(1);
        ArgumentCaptor<List<ContextChunk>> captor = listCaptor();
        verify(contextChunkRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getContent()).isEqualTo("fallback transcript");
    }

    @Test
    void regenerateForReturnsZeroAndWipesWhenBothSourcesEmpty() throws IOException {
        Video video = video();
        when(multimodalSegmentRepository.findByVideo_IdOrderBySegmentIndexAsc(video.getId()))
                .thenReturn(List.of());
        when(transcriptionRepository.findByVideoId(video.getId())).thenReturn(Optional.empty());

        int persisted = service.regenerateFor(video);

        assertThat(persisted).isZero();
        verify(contextChunkRepository).deleteByVideoId(video.getId());
        verify(contextChunkRepository, never()).saveAll(any());
        verify(embeddingsClient, never()).embed(any());
    }

    @Test
    void regenerateForReturnsZeroAndWipesWhenTranscriptIsEmpty() throws IOException {
        Video video = video();
        when(multimodalSegmentRepository.findByVideo_IdOrderBySegmentIndexAsc(video.getId()))
                .thenReturn(List.of());
        when(transcriptionRepository.findByVideoId(video.getId()))
                .thenReturn(Optional.of(transcription(video, "   ")));

        int persisted = service.regenerateFor(video);

        assertThat(persisted).isZero();
        verify(contextChunkRepository).deleteByVideoId(video.getId());
        verify(contextChunkRepository, never()).saveAll(any());
    }

    // -------------------- validation --------------------

    @Test
    void regenerateForThrowsOnNullVideo() {
        assertThatThrownBy(() -> service.regenerateFor(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Video is missing id");
    }

    @Test
    void regenerateForThrowsOnNullVideoId() {
        assertThatThrownBy(() -> service.regenerateFor(new Video()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Video is missing id");
    }

    // -------------------- helpers --------------------

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<ContextChunk>> listCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }

    private static Video video() {
        Video v = new Video();
        v.setId(UUID.randomUUID());
        return v;
    }

    private static MultimodalSegment segment(String transcript, String ocr) {
        return MultimodalSegment.builder()
                .id(UUID.randomUUID())
                .segmentIndex(0)
                .startSeconds(0.0)
                .endSeconds(30.0)
                .transcriptText(transcript)
                .ocrText(ocr)
                .build();
    }

    private static Transcription transcription(Video v, String fullText) {
        Transcription t = new Transcription();
        t.setId(UUID.randomUUID());
        t.setVideo(v);
        t.setFullText(fullText);
        return t;
    }
}
