package com.tradinglabs.vidingest.videos.service;

import com.tradinglabs.vidingest.api.videos.RunVideoPhaseResult;
import com.tradinglabs.vidingest.core.diarization.service.DiarizationService;
import com.tradinglabs.vidingest.core.frames.service.FrameSamplingService;
import com.tradinglabs.vidingest.core.fusion.service.SegmentFusionService;
import com.tradinglabs.vidingest.core.knowledge.service.KnowledgeExtractionService;
import com.tradinglabs.vidingest.core.ocr.service.OcrService;
import com.tradinglabs.vidingest.core.transcription.service.TranscriptionService;
import com.tradinglabs.vidingest.search.service.embedding.ContextChunkGenerationService;
import com.tradinglabs.vidingest.videos.domain.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.UUID;

/**
 * Dispatches per-phase reruns for a video that has already been ingested. The phase service
 * methods themselves are idempotent (each one wipes prior rows for the video before
 * re-populating), so this service is a thin router with timing and error normalisation.
 *
 * <p>Phases supported here are those whose inputs are derivable from already-persisted state:
 * <ul>
 *   <li>{@code TRANSCRIBE} — re-runs Whisper using the on-disk video file</li>
 *   <li>{@code DIARIZE} — re-runs pyannote against the existing transcription</li>
 *   <li>{@code FRAME_SAMPLE} — re-extracts keyframes from the on-disk file</li>
 *   <li>{@code OCR} — re-OCRs all current frames</li>
 *   <li>{@code FUSE} — re-fuses multimodal segments from the current upstream signals</li>
 *   <li>{@code KNOWLEDGE} — re-runs LLM extraction against the current multimodal segments</li>
 *   <li>{@code CONTEXT} — regenerates the search context chunks + embeddings</li>
 * </ul>
 * {@code METADATA}, {@code DOWNLOAD}, {@code PERSIST} are intentionally excluded — those
 * consume the video URL, not the video row, so the full pipeline run is the right tool.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoPhaseRunnerService {

    private final VideoQueryService videoQueryService;
    private final TranscriptionService transcriptionService;
    private final DiarizationService diarizationService;
    private final FrameSamplingService frameSamplingService;
    private final OcrService ocrService;
    private final SegmentFusionService segmentFusionService;
    private final KnowledgeExtractionService knowledgeExtractionService;
    private final ContextChunkGenerationService contextChunkGenerationService;

    public RunVideoPhaseResult runPhase(UUID videoId, String phaseRaw) {
        String phase = normalisePhase(phaseRaw);
        Video video = videoQueryService.getById(videoId);

        long startNs = System.nanoTime();
        try {
            Integer rows = switch (phase) {
                case "TRANSCRIBE" -> {
                    transcriptionService.transcribe(video);
                    yield null;
                }
                case "DIARIZE" -> {
                    diarizationService.diarize(video);
                    yield null;
                }
                case "FRAME_SAMPLE" -> frameSamplingService.sampleFrames(video).size();
                case "OCR" -> ocrService.ocrAllFrames(video);
                case "FUSE" -> segmentFusionService.fuse(video).size();
                case "KNOWLEDGE" -> knowledgeExtractionService.extractKnowledge(video);
                case "CONTEXT" -> {
                    try {
                        yield contextChunkGenerationService.regenerateFor(video);
                    } catch (java.io.IOException ioe) {
                        // Wrap the checked IOException so the surrounding catch can format it
                        throw new RuntimeException(ioe);
                    }
                }
                default -> throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unsupported phase: " + phaseRaw + ". Allowed: "
                                + "TRANSCRIBE, DIARIZE, FRAME_SAMPLE, OCR, FUSE, KNOWLEDGE, CONTEXT"
                );
            };
            long ms = (System.nanoTime() - startNs) / 1_000_000;
            log.info("Per-phase rerun OK: videoId={} phase={} elapsedMs={} rows={}",
                    videoId, phase, ms, rows);
            return new RunVideoPhaseResult(
                    videoId.toString(), phase, "OK", null, ms, rows
            );
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            long ms = (System.nanoTime() - startNs) / 1_000_000;
            log.warn("Per-phase rerun FAILED: videoId={} phase={} elapsedMs={} error={}",
                    videoId, phase, ms, e.getMessage());
            return new RunVideoPhaseResult(
                    videoId.toString(), phase, "ERROR", e.getMessage(), ms, null
            );
        }
    }

    private static String normalisePhase(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "phase path variable is required");
        }
        return raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }
}
