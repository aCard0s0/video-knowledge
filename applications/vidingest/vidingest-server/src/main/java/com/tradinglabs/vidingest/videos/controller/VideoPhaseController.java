package com.tradinglabs.vidingest.videos.controller;

import com.tradinglabs.vidingest.api.paths.VidIngestApiPaths;
import com.tradinglabs.vidingest.api.videos.RunVideoPhaseResult;
import com.tradinglabs.vidingest.videos.service.VideoPhaseRunnerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Per-phase rerun endpoint. Lets operators (or the UI) re-execute a single pipeline phase
 * against an already-ingested video without spinning up a full pipeline run. Particularly
 * useful after a sidecar / model upgrade where re-ingesting from scratch would waste the
 * 10+ minute Whisper + diarize runtime.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@Tag(name = "video-phases", description = "Re-run a single pipeline phase against an existing video")
public class VideoPhaseController {

    private final VideoPhaseRunnerService runner;

    @PostMapping(value = VidIngestApiPaths.VIDEO_PHASE_RUN, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "runVideoPhase",
            summary = "Re-run one pipeline phase for an existing video",
            description = "Synchronous. Allowed phase values (case-insensitive, '-' or '_' separated): "
                    + "TRANSCRIBE, DIARIZE, FRAME_SAMPLE, OCR, FUSE, KNOWLEDGE, CONTEXT. Each phase "
                    + "wipes its prior rows for the video before re-populating, so the call is idempotent. "
                    + "A phase that fails returns an RFC 7807 ProblemDetail (502 when an upstream tool "
                    + "did not deliver), not a 200 with an error body."
    )
    public RunVideoPhaseResult run(@PathVariable UUID videoId, @PathVariable String phase) throws Exception {
        log.info("REST run video phase: videoId={} phase={}", videoId, phase);
        return runner.runPhase(videoId, phase);
    }
}
