package com.tradinglabs.vidingest.api.videos;

/**
 * Response from {@code POST /api/v1/videos/{videoId}/phases/{phase}/run}.
 *
 * <p>Per-phase reruns let operators re-execute one pipeline phase against an already-ingested
 * video — useful for re-OCRing after a paddleocr-server upgrade, re-extracting knowledge with
 * a stronger LLM, etc. The endpoint executes synchronously and is only returned on success:
 * a phase that fails propagates and is rendered as an RFC 7807 {@code ProblemDetail} with the
 * same status codes as every other endpoint (502 for an upstream tool that did not deliver,
 * 404 for an unknown video, 400 for an unusable phase name).
 *
 * @param videoId       UUID as string
 * @param phase         the phase that was run, normalised to uppercase
 *                      (TRANSCRIBE | DIARIZE | FRAME_SAMPLE | OCR | FUSE | KNOWLEDGE | CONTEXT)
 * @param elapsedMs     wall-clock milliseconds spent running the phase
 * @param rowsAffected  phase-specific count (frames sampled / OCR rows persisted / segments
 *                      fused / knowledge units persisted / context chunks generated).
 *                      {@code null} when not meaningful for the phase (TRANSCRIBE, DIARIZE).
 */
public record RunVideoPhaseResult(
        String videoId,
        String phase,
        long elapsedMs,
        Integer rowsAffected
) {
}
