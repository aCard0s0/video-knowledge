package com.tradinglabs.vidingest.search.service.embedding;

import com.tradinglabs.vidingest.core.fusion.domain.MultimodalSegment;
import com.tradinglabs.vidingest.core.fusion.repo.MultimodalSegmentRepository;
import com.tradinglabs.vidingest.core.transcription.domain.Transcription;
import com.tradinglabs.vidingest.core.transcription.repo.TranscriptionRepository;
import com.tradinglabs.vidingest.search.domain.ContextChunk;
import com.tradinglabs.vidingest.search.repo.ContextChunkRepository;
import com.tradinglabs.vidingest.videos.domain.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Generates the {@code vidingest_context_chunks} rows used by semantic search.
 *
 * <p><b>M7 change:</b> when {@code vidingest_multimodal_segments} rows exist for the video
 * (produced by M5 fusion), chunks are built from the fused per-window text — transcript +
 * any on-screen OCR — so semantic search sees both the spoken and the visible content.
 * Videos with no multimodal segments (older content, or runs with FUSE disabled) fall
 * back to the original transcript-only chunking, preserving the pre-M7 behaviour exactly.
 *
 * <p>The {@code vidingest_context_chunks} schema is unchanged; only the text we put in
 * {@code content} is richer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContextChunkGenerationService {

    private static final int MAX_CHUNKS = 2_000;
    private static final int TARGET_CHARS = 900;
    private static final int OVERLAP_CHARS = 150;

    /**
     * Inline marker we drop between a window's transcript and OCR text. Keeps the two
     * signals visually separable in the persisted chunk so it's obvious what came from
     * where during debugging or human review of search hits.
     */
    static final String VISUAL_SEPARATOR = " [VISUAL] ";

    private final TranscriptionRepository transcriptionRepository;
    private final ContextChunkRepository contextChunkRepository;
    private final EmbeddingsClient embeddingsClient;
    private final MultimodalSegmentRepository multimodalSegmentRepository;

    /**
     * Regenerates all context chunks for a video. Source selection:
     * <ol>
     *   <li>M5 multimodal segments if any exist and at least one has non-blank content;</li>
     *   <li>otherwise the transcription's {@code full_text}.</li>
     * </ol>
     * Returns the number of chunks persisted. Returns 0 (after wiping stale rows) when
     * neither source has usable content — never throws on missing inputs, so manual
     * regenerate calls on partially-ingested videos behave predictably.
     */
    @Transactional
    public int regenerateFor(Video video) throws IOException {
        if (video == null || video.getId() == null) {
            throw new IllegalArgumentException("Video is missing id");
        }
        UUID videoId = video.getId();

        ChunkBuild build = buildChunks(videoId);
        if (build.chunks.isEmpty()) {
            log.info("Context generation skipped: no usable source text (videoId={})", videoId);
            contextChunkRepository.deleteByVideoId(videoId);
            return 0;
        }
        if (build.chunks.size() > MAX_CHUNKS) {
            throw new IllegalStateException("Refusing to generate " + build.chunks.size()
                    + " chunks (max=" + MAX_CHUNKS + ") for video " + videoId);
        }

        List<float[]> vectors = embeddingsClient.embed(build.chunks);
        if (vectors.size() != build.chunks.size()) {
            throw new IOException("Embeddings client returned " + vectors.size()
                    + " vectors for " + build.chunks.size() + " chunks.");
        }

        // Replace chunks atomically within the transaction.
        contextChunkRepository.deleteByVideoId(videoId);

        List<ContextChunk> entities = new ArrayList<>(build.chunks.size());
        for (int i = 0; i < build.chunks.size(); i++) {
            entities.add(ContextChunk.builder()
                    .video(video)
                    .chunkIndex(i)
                    .content(build.chunks.get(i))
                    .embedding(vectors.get(i))
                    .build());
        }
        contextChunkRepository.saveAll(entities);

        log.info("Context chunks generated: videoId={}, source={}, chunks={}",
                videoId, build.source, entities.size());
        return entities.size();
    }

    /**
     * Decides which source to chunk from and returns both the chunks and a short tag for
     * logging / metrics. Multimodal wins when it produces at least one non-empty chunk;
     * otherwise falls back to transcript-only.
     */
    private ChunkBuild buildChunks(UUID videoId) {
        List<MultimodalSegment> multimodal =
                multimodalSegmentRepository.findByVideo_IdOrderBySegmentIndexAsc(videoId);

        if (!multimodal.isEmpty()) {
            List<String> fused = chunksFromMultimodal(multimodal);
            if (!fused.isEmpty()) {
                return new ChunkBuild(fused, "multimodal(" + multimodal.size() + " segments)");
            }
        }

        Transcription tx = transcriptionRepository.findByVideoId(videoId).orElse(null);
        if (tx == null) {
            return new ChunkBuild(List.of(), "none");
        }
        String fullText = tx.getFullText();
        if (fullText == null || fullText.isBlank()) {
            return new ChunkBuild(List.of(), "transcript(empty)");
        }
        return new ChunkBuild(chunk(fullText), "transcript(" + fullText.length() + " chars)");
    }

    /**
     * Build chunks from multimodal segments. One chunk per segment when its fused text
     * fits in the target chunk size; oversized segments are sub-chunked with the same
     * sliding-window algorithm the transcript path uses, so the embedding model always
     * sees text of a predictable size.
     *
     * <p>The fused format — {@code "<transcript> [VISUAL] <ocr>"} — keeps the two signals
     * separable in the persisted text without inflating the chunk size. When only one
     * signal is present, the marker is omitted.
     *
     * <p>Package-private for tests.
     */
    static List<String> chunksFromMultimodal(List<MultimodalSegment> segments) {
        List<String> out = new ArrayList<>();
        for (MultimodalSegment seg : segments) {
            String fused = formatSegment(seg);
            if (fused.isEmpty()) {
                continue;
            }
            // TARGET_CHARS + OVERLAP_CHARS is the practical upper bound the windowed chunker
            // would emit; anything below it can pass through as a single chunk without
            // wasting overlap on a segment that wouldn't have been split anyway.
            if (fused.length() <= TARGET_CHARS + OVERLAP_CHARS) {
                out.add(fused);
            } else {
                out.addAll(chunk(fused));
            }
        }
        return out;
    }

    /**
     * Render a multimodal segment as a single text block: trimmed transcript, the
     * {@code [VISUAL]} marker, then trimmed OCR. Missing signals are dropped (no leading /
     * trailing marker) so a segment with only OCR or only transcript still produces a
     * well-formed chunk.
     */
    static String formatSegment(MultimodalSegment seg) {
        if (seg == null) return "";
        String transcript = blankToNull(seg.getTranscriptText());
        String ocr = blankToNull(seg.getOcrText());
        if (transcript != null && ocr != null) {
            return transcript.trim() + VISUAL_SEPARATOR + ocr.trim();
        }
        if (transcript != null) {
            return transcript.trim();
        }
        if (ocr != null) {
            return "[VISUAL] " + ocr.trim();
        }
        return "";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Sliding-window text chunker — preserved as-is from the pre-M7 implementation so
     * transcript-only videos see byte-identical chunk boundaries. Used both for the
     * transcript fallback and for sub-chunking oversized multimodal segments.
     */
    static List<String> chunk(String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return List.of();
        }

        List<String> out = new ArrayList<>();
        int pos = 0;
        while (pos < normalized.length()) {
            int end = Math.min(normalized.length(), pos + TARGET_CHARS);
            if (end < normalized.length()) {
                int backtrackMin = Math.max(pos + (TARGET_CHARS / 2), pos);
                int ws = normalized.lastIndexOf(' ', end);
                if (ws >= backtrackMin) {
                    end = ws;
                }
            }

            String chunk = normalized.substring(pos, end).trim();
            if (!chunk.isEmpty()) {
                out.add(chunk);
            }

            if (end >= normalized.length()) {
                break;
            }

            pos = Math.max(end - OVERLAP_CHARS, 0);
            if (pos == end) {
                // Safety: ensure forward progress.
                pos = end + 1;
            }
        }

        return out;
    }

    /**
     * Internal result of {@link #buildChunks(UUID)} — the chunks plus a short string we
     * log to mark which source produced them. Two callers, both within this class.
     */
    private record ChunkBuild(List<String> chunks, String source) {
    }
}
