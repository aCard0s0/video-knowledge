package com.tradinglabs.vidingest.core.knowledge.service;

import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.commons.ConflictException;
import com.tradinglabs.vidingest.config.KnowledgeExtractionConfig;
import com.tradinglabs.vidingest.core.fusion.domain.MultimodalSegment;
import com.tradinglabs.vidingest.core.fusion.repo.MultimodalSegmentRepository;
import com.tradinglabs.vidingest.core.knowledge.client.KnowledgeChatClient;
import com.tradinglabs.vidingest.core.knowledge.domain.KnowledgeUnit;
import com.tradinglabs.vidingest.core.knowledge.dto.KnowledgeUnitDraft;
import com.tradinglabs.vidingest.core.knowledge.repo.KnowledgeUnitRepository;
import com.tradinglabs.vidingest.search.service.embedding.EmbeddingsClient;
import com.tradinglabs.vidingest.videos.domain.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link KnowledgeExtractionService} without spawning the LLM by mocking the
 * {@link KnowledgeChatClient}. Covers batching, type / salience filtering, idempotency,
 * embedding wiring, and per-batch / total-failure escalation.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeExtractionServiceTest {

    @Mock private KnowledgeChatClient chatClient;
    @Mock private EmbeddingsClient embeddingsClient;
    @Mock private MultimodalSegmentRepository multimodalSegmentRepository;
    @Mock private KnowledgeUnitRepository knowledgeUnitRepository;

    private KnowledgeExtractionConfig config;
    private KnowledgeExtractionService service;

    @BeforeEach
    void setUp() {
        config = new KnowledgeExtractionConfig();
        config.setEnabled(true);
        config.setChatModel("qwen2.5:14b-instruct");
        config.setMaxInputCharsPerBatch(10_000);
        config.setMaxUnitsPerVideo(300);
        config.setMinSalience(0.2);
        config.setEmbedContent(true);
        config.setTypes(List.of(
                KnowledgeUnitType.ENTITY,
                KnowledgeUnitType.TOPIC,
                KnowledgeUnitType.SUMMARY
        ));
        service = new KnowledgeExtractionService(
                config, chatClient, embeddingsClient,
                multimodalSegmentRepository, knowledgeUnitRepository,
                TransactionOperations.withoutTransaction()
        );
        lenient().when(knowledgeUnitRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<KnowledgeUnit> in = inv.getArgument(0);
            List<KnowledgeUnit> out = new ArrayList<>();
            in.forEach(out::add);
            return out;
        });
    }

    @Test
    void returnsZeroAndSkipsLlmWhenNoSegments() {
        Video video = video();
        when(multimodalSegmentRepository.findByVideo_IdOrderBySegmentIndexAsc(video.getId()))
                .thenReturn(List.of());

        int persisted = service.extractKnowledge(video);

        assertThat(persisted).isZero();
        verify(chatClient, never()).extract(anyString(), anyString());
        verify(knowledgeUnitRepository, never()).deleteByVideo_Id(any());
        verify(knowledgeUnitRepository, never()).saveAll(any());
    }

    @Test
    void batchesSegmentsByCharBudget() {
        // Tiny budget forces multiple batches.
        config.setMaxInputCharsPerBatch(500);

        Video video = video();
        // 5 segments × ~300 chars each = ~1500 chars total → with overhead, three batches.
        List<MultimodalSegment> segs = List.of(
                segment(0, 0.0, 10.0, "a".repeat(300), null),
                segment(1, 10.0, 20.0, "b".repeat(300), null),
                segment(2, 20.0, 30.0, "c".repeat(300), null),
                segment(3, 30.0, 40.0, "d".repeat(300), null),
                segment(4, 40.0, 50.0, "e".repeat(300), null)
        );
        when(multimodalSegmentRepository.findByVideo_IdOrderBySegmentIndexAsc(video.getId()))
                .thenReturn(segs);
        when(chatClient.extract(anyString(), anyString())).thenReturn(List.of());

        service.extractKnowledge(video);

        verify(chatClient, atLeast(2)).extract(anyString(), anyString());
    }

    @Test
    void filterAndCapDropsBelowSalienceAndDisallowedTypes() {
        List<KnowledgeUnitDraft> drafts = List.of(
                draft(KnowledgeUnitType.ENTITY,  "good entity",   0.9),
                draft(KnowledgeUnitType.QUESTION, "filtered type",  0.9),  // type not in config
                draft(KnowledgeUnitType.SUMMARY,  "below salience", 0.05),
                draft(KnowledgeUnitType.TOPIC,    "kept topic",     0.8)
        );

        List<KnowledgeUnitDraft> kept = service.filterAndCap(drafts,
                java.util.EnumSet.copyOf(config.getTypes()));

        assertThat(kept).extracting(KnowledgeUnitDraft::content)
                .containsExactly("good entity", "kept topic");
    }

    @Test
    void filterAndCapEnforcesMaxUnitsPerVideo() {
        config.setMaxUnitsPerVideo(2);
        List<KnowledgeUnitDraft> drafts = List.of(
                draft(KnowledgeUnitType.ENTITY, "first", 0.9),
                draft(KnowledgeUnitType.ENTITY, "second", 0.9),
                draft(KnowledgeUnitType.ENTITY, "third (truncated)", 0.9)
        );

        List<KnowledgeUnitDraft> kept = service.filterAndCap(drafts,
                java.util.EnumSet.copyOf(config.getTypes()));

        assertThat(kept).hasSize(2);
        assertThat(kept).extracting(KnowledgeUnitDraft::content)
                .containsExactly("first", "second");
    }

    @Test
    void extractsEndToEndAndEmbedsContent() throws IOException {
        Video video = video();
        when(multimodalSegmentRepository.findByVideo_IdOrderBySegmentIndexAsc(video.getId()))
                .thenReturn(List.of(segment(0, 0.0, 10.0, "transcript", null)));

        when(chatClient.extract(anyString(), anyString())).thenReturn(List.of(
                draft(KnowledgeUnitType.ENTITY, "Apple Inc.", 0.9),
                draft(KnowledgeUnitType.SUMMARY, "Overview", 0.7)
        ));

        float[] vec1 = new float[]{0.1f, 0.2f, 0.3f};
        float[] vec2 = new float[]{0.4f, 0.5f, 0.6f};
        when(embeddingsClient.embed(any())).thenReturn(List.of(vec1, vec2));

        int persisted = service.extractKnowledge(video);

        assertThat(persisted).isEqualTo(2);

        ArgumentCaptor<List<KnowledgeUnit>> captor = listCaptor();
        verify(knowledgeUnitRepository).saveAll(captor.capture());
        List<KnowledgeUnit> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getContent()).isEqualTo("Apple Inc.");
        assertThat(saved.get(0).getEmbedding()).isEqualTo(vec1);
        assertThat(saved.get(0).getMetadata())
                .containsEntry("chat_model", "qwen2.5:14b-instruct")
                .containsEntry("prompt_version", 2);
        assertThat(saved.get(0).getMetadata()).containsKey("salience");
        assertThat(saved.get(1).getEmbedding()).isEqualTo(vec2);
    }

    @Test
    void persistsWithNullEmbeddingsWhenEmbeddingClientFails() throws IOException {
        Video video = video();
        when(multimodalSegmentRepository.findByVideo_IdOrderBySegmentIndexAsc(video.getId()))
                .thenReturn(List.of(segment(0, 0.0, 10.0, "transcript", null)));
        when(chatClient.extract(anyString(), anyString())).thenReturn(List.of(
                draft(KnowledgeUnitType.ENTITY, "X", 0.9)
        ));
        when(embeddingsClient.embed(any())).thenThrow(new IOException("embed offline"));

        int persisted = service.extractKnowledge(video);

        assertThat(persisted).isEqualTo(1);
        ArgumentCaptor<List<KnowledgeUnit>> captor = listCaptor();
        verify(knowledgeUnitRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getEmbedding()).isNull();
    }

    @Test
    void skipsEmbeddingsWhenEmbedContentDisabled() throws IOException {
        config.setEmbedContent(false);

        Video video = video();
        when(multimodalSegmentRepository.findByVideo_IdOrderBySegmentIndexAsc(video.getId()))
                .thenReturn(List.of(segment(0, 0.0, 10.0, "transcript", null)));
        when(chatClient.extract(anyString(), anyString())).thenReturn(List.of(
                draft(KnowledgeUnitType.ENTITY, "X", 0.9)
        ));

        service.extractKnowledge(video);

        verify(embeddingsClient, never()).embed(any());
    }

    /**
     * A partly-failed run holds partial coverage. It used to replace a complete extraction with
     * it and report success — 300 units becoming 12 with nothing above a per-batch warning to say
     * so. The rows on disk are worth more than one run's salvage, so a failed batch fails the
     * phase and leaves them alone.
     */
    @Test
    void aFailedBatchLeavesThePriorRowsInPlace() {
        // Two ~600-char segments + ~32 char overhead each → each takes >700 chars in the
        // prompt. With budget=700 we get exactly two batches of one segment each.
        config.setMaxInputCharsPerBatch(700);

        Video video = video();
        List<MultimodalSegment> segs = List.of(
                segment(0, 0.0, 10.0,  "a".repeat(700), null),
                segment(1, 10.0, 20.0, "b".repeat(700), null)
        );
        when(multimodalSegmentRepository.findByVideo_IdOrderBySegmentIndexAsc(video.getId()))
                .thenReturn(segs);

        // First batch fails, second batch succeeds with one draft.
        when(chatClient.extract(anyString(), anyString()))
                .thenThrow(new KnowledgeExtractionFailureException("batch 1 boom"))
                .thenReturn(List.of(draft(KnowledgeUnitType.ENTITY, "survivor", 0.9)));

        assertThatThrownBy(() -> service.extractKnowledge(video))
                .isInstanceOf(KnowledgeExtractionFailureException.class)
                .hasMessageContaining("failed for 1 of 2 batches");

        verify(knowledgeUnitRepository, never()).deleteByVideo_Id(any());
        verify(knowledgeUnitRepository, never()).saveAll(any());
    }

    @Test
    void allBatchesFailingEscalatesAsHardFailure() {
        config.setMaxInputCharsPerBatch(500);

        Video video = video();
        List<MultimodalSegment> segs = List.of(
                segment(0, 0.0, 10.0, "a".repeat(300), null),
                segment(1, 10.0, 20.0, "b".repeat(300), null)
        );
        when(multimodalSegmentRepository.findByVideo_IdOrderBySegmentIndexAsc(video.getId()))
                .thenReturn(segs);
        when(chatClient.extract(anyString(), anyString()))
                .thenThrow(new KnowledgeExtractionFailureException("LLM offline"));

        assertThatThrownBy(() -> service.extractKnowledge(video))
                .isInstanceOf(KnowledgeExtractionFailureException.class)
                .hasMessageContaining("failed for 2 of 2 batches");

        verify(knowledgeUnitRepository, never()).deleteByVideo_Id(any());
    }

    /**
     * The wipe now follows the LLM loop rather than preceding it, and shares a transaction with
     * the insert. Ordering is the whole point: everything that can fail has already failed by the
     * time any row is deleted.
     */
    @Test
    void wipesPriorRowsAfterTheLlmCallsAndAlongsideTheInsert() {
        Video video = video();
        when(multimodalSegmentRepository.findByVideo_IdOrderBySegmentIndexAsc(video.getId()))
                .thenReturn(List.of(segment(0, 0.0, 10.0, "x", null)));
        when(chatClient.extract(anyString(), anyString())).thenReturn(List.of(
                draft(KnowledgeUnitType.ENTITY, "kept", 0.9)
        ));

        service.extractKnowledge(video);

        var inOrder = org.mockito.Mockito.inOrder(chatClient, knowledgeUnitRepository);
        inOrder.verify(chatClient).extract(anyString(), anyString());
        inOrder.verify(knowledgeUnitRepository).deleteByVideo_Id(video.getId());
        inOrder.verify(knowledgeUnitRepository).flush();
        inOrder.verify(knowledgeUnitRepository).saveAll(any());
    }

    /**
     * An all-succeeded run that survives no filter is a real answer of "nothing salient here", so
     * it still clears the table — unlike a failed run, which must not.
     */
    @Test
    void anEmptyButCompleteResultStillClearsThePriorRows() {
        Video video = video();
        when(multimodalSegmentRepository.findByVideo_IdOrderBySegmentIndexAsc(video.getId()))
                .thenReturn(List.of(segment(0, 0.0, 10.0, "x", null)));
        // Below the 0.2 salience floor, so nothing survives filterAndCap.
        when(chatClient.extract(anyString(), anyString())).thenReturn(List.of(
                draft(KnowledgeUnitType.ENTITY, "noise", 0.01)
        ));

        int persisted = service.extractKnowledge(video);

        assertThat(persisted).isZero();
        verify(knowledgeUnitRepository).deleteByVideo_Id(video.getId());
        verify(knowledgeUnitRepository, never()).saveAll(any());
    }

    @Test
    void batchByCharBudgetFitsAllInOneWhenBudgetExceedsTotal() {
        List<MultimodalSegment> segs = List.of(
                segment(0, 0.0, 10.0, "short", null),
                segment(1, 10.0, 20.0, "also short", null)
        );

        List<List<MultimodalSegment>> batches =
                KnowledgeExtractionService.batchByCharBudget(segs, 100_000);

        assertThat(batches).hasSize(1);
        assertThat(batches.get(0)).hasSize(2);
    }

    @Test
    void batchByCharBudgetHonorsBudgetBoundary() {
        // Each segment ~32 char overhead + content; with a 100-char budget two of these
        // ought to land in batch 0, the third in batch 1.
        List<MultimodalSegment> segs = List.of(
                segment(0, 0, 1, "x".repeat(20), null),  // ~52 chars
                segment(1, 0, 1, "x".repeat(20), null),  // ~52 chars → batch 0 over budget on add
                segment(2, 0, 1, "x".repeat(20), null)
        );
        List<List<MultimodalSegment>> batches =
                KnowledgeExtractionService.batchByCharBudget(segs, 100);

        // Each segment > half-budget so we get one segment per batch.
        assertThat(batches).hasSize(3);
    }

    @Test
    void batchByCharBudgetHandlesEmptyInput() {
        assertThat(KnowledgeExtractionService.batchByCharBudget(null, 100)).isEmpty();
        assertThat(KnowledgeExtractionService.batchByCharBudget(List.of(), 100)).isEmpty();
    }

    // ---- helpers ----

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<KnowledgeUnit>> listCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }

    private static Video video() {
        Video v = new Video();
        v.setId(UUID.randomUUID());
        return v;
    }

    private static MultimodalSegment segment(int idx, double start, double end, String transcript, String ocr) {
        return MultimodalSegment.builder()
                .id(UUID.randomUUID())
                .segmentIndex(idx)
                .startSeconds(start)
                .endSeconds(end)
                .transcriptText(transcript)
                .ocrText(ocr)
                .build();
    }

    private static KnowledgeUnitDraft draft(KnowledgeUnitType type, String content, double salience) {
        return new KnowledgeUnitDraft(type, content, content, salience, List.of(0), 0.0, 30.0, null);
    }
    /**
     * The master switch used to be checked only by {@code KnowledgePhase.applies()}, so
     * {@code POST /videos/{id}/knowledge/regenerate} bypassed it and called the model anyway —
     * with a 14b model on CPU that meant hanging for the full 10-minute read timeout before
     * failing. The guard names the property, because a 409 that does not say which flag to flip
     * is a worse answer than the hang.
     */
    @Test
    void extractionIsRefusedWhileTheFeatureIsDisabled() {
        config.setEnabled(false);

        assertThatThrownBy(() -> service.extractKnowledge(video()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("vidingest.knowledge.enabled");

        verifyNoInteractions(chatClient);
    }

}
