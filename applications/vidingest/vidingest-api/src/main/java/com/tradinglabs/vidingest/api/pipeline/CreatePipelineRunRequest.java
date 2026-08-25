package com.tradinglabs.vidingest.api.pipeline;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;

/**
 * Request payload for {@code POST /api/v1/pipelines}.
 *
 * <p>{@code skipPhases} names the optional phases this run opts out of — TRANSCRIBE, DIARIZE,
 * FRAME_SAMPLE, OCR, FUSE, KNOWLEDGE, CONTEXT. Omit it or pass an empty list to run everything
 * the deployment has enabled; naming a mandatory phase (METADATA/DOWNLOAD/PERSIST) is rejected.
 */
public record CreatePipelineRunRequest(
        @NotEmpty
        @Size(max = 100)
        List<String> urls,
        Set<String> skipPhases
) {
}
