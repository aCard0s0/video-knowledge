package com.tradinglabs.vidingest.api.videos;

/**
 * Response from {@code POST /api/v1/videos/{videoId}/phases/{phase}/run}.
 *
 * <p>Per-phase reruns let operators re-execute one pipeline phase against an already-ingested
 * video — useful for re-OCRing after a paddleocr-server upgrade, re-extracting knowledge with
 * a stronger LLM, etc. The endpoint executes synchronously and returns {@code status="OK"}
 * on success or {@code status="ERROR"} with the failure message.
 *
 * @param videoId       UUID as string
 * @param phase         the phase that was run, normalised to uppercase
 *                      (TRANSCRIBE | DIARIZE | FRAME_SAMPLE | OCR | FUSE | KNOWLEDGE | CONTEXT)
 * @param status        {@code OK} on success, {@code ERROR} on failure
 * @param message       failure detail when {@code status=ERROR}; {@code null} on success
 * @param elapsedMs     wall-clock milliseconds spent running the phase
 * @param rowsAffected  phase-specific count (frames sampled / OCR rows persisted / segments
 *                      fused / knowledge units persisted / context chunks generated).
 *                      {@code null} when not meaningful for the phase (TRANSCRIBE, DIARIZE).
 */
public record RunVideoPhaseResult(
        String videoId,
        String phase,
        String status,
        String message,
        long elapsedMs,
        Integer rowsAffected
) {
}
