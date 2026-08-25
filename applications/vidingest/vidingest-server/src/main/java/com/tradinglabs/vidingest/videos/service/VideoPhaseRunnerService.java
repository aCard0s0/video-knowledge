package com.tradinglabs.vidingest.videos.service;

import com.tradinglabs.vidingest.api.videos.RunVideoPhaseResult;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.service.phase.PipelinePhase;
import com.tradinglabs.vidingest.pipeline.service.phase.PipelinePhaseContext;
import com.tradinglabs.vidingest.pipeline.service.phase.PipelinePhaseRegistry;
import com.tradinglabs.vidingest.pipeline.util.SkipPhasesParser;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Dispatches per-phase reruns for a video that has already been ingested. Phases are looked up
 * in {@link PipelinePhaseRegistry} and executed through the same {@link PipelinePhase}
 * implementations the pipeline itself runs, so a rerun and an in-pipeline run cannot drift
 * apart. The phase services are idempotent (each wipes prior rows for the video before
 * re-populating), so this service adds only timing and status handling.
 *
 * <p>Failures are not swallowed into the response body: they propagate and are rendered by
 * {@code VidingestApiExceptionHandler} as a {@code ProblemDetail}, so a caller sees 502 for an
 * upstream tool that did not deliver and 500 for a genuine bug, the same as every other
 * endpoint.
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
 *
 * <p>{@code PipelinePhase.applies(ctx)} is deliberately <em>not</em> consulted: it mixes the
 * per-run skip flags (meaningless for a rerun) with the {@code vidingest.<phase>.enabled}
 * deployment toggles. This endpoint is the operator escape hatch — "re-OCR after a
 * paddleocr-server upgrade" — so it forces the phase regardless of the toggle, matching the
 * behaviour it has always had.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoPhaseRunnerService {

    private final VideoQueryService videoQueryService;
    private final VideoRepository videoRepository;
    private final PipelinePhaseRegistry pipelinePhaseRegistry;

    public RunVideoPhaseResult runPhase(UUID videoId, String phaseRaw) throws Exception {
        PipelineRunPhase phase = parsePhase(phaseRaw);
        PipelinePhase impl = pipelinePhaseRegistry.byPhase(phase)
                .orElseThrow(() -> new IllegalArgumentException("No implementation registered for phase: " + phase));

        Video video = videoQueryService.getById(videoId);
        VideoStatus statusBefore = video.getStatus();
        PipelinePhaseContext ctx = PipelinePhaseContext.forRerun(video);

        long startNs = System.nanoTime();
        try {
            impl.execute(ctx);
        } catch (Exception e) {
            // Logged here because the exception handler only sees the phase name via the URL,
            // and the elapsed time is worth having when a sidecar hangs before failing.
            log.warn("Per-phase rerun FAILED: videoId={} phase={} elapsedMs={} error={}",
                    videoId, phase, elapsedMs(startNs), e.getMessage());
            throw e;
        }

        // Phases flip the video into a working status (TRANSCRIBING / PROCESSING) and leave the
        // finalisation to PipelineService, which a single-phase rerun does not go through. Put
        // the video back where it was so a rerun does not park it mid-flight.
        restoreStatus(ctx.getVideo(), statusBefore);

        long ms = elapsedMs(startNs);
        log.info("Per-phase rerun OK: videoId={} phase={} elapsedMs={} rows={}",
                videoId, phase, ms, ctx.getRowsAffected());
        return new RunVideoPhaseResult(videoId.toString(), phase.name(), ms, ctx.getRowsAffected());
    }

    /**
     * Only writes when the phase actually moved the status, so a rerun of a phase that does not
     * touch the video row stays a pure read for the videos table.
     */
    private void restoreStatus(Video video, VideoStatus statusBefore) {
        if (video == null || video.getStatus() == statusBefore) {
            return;
        }
        video.setStatus(statusBefore);
        videoRepository.save(video);
    }

    private static PipelineRunPhase parsePhase(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("phase path variable is required");
        }
        // Same question the run opt-out list asks, answered by the same parser: an optional
        // phase is exactly one whose input is the persisted video row.
        return SkipPhasesParser.parseOptional(raw.trim());
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }
}
