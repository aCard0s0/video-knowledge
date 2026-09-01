package com.tradinglabs.vidingest.api.connections;

/**
 * The closed set of runtimes VidIngest drives, and therefore the keys of the connection-settings
 * API and of {@code vidingest_connections}.
 *
 * <p>All but one are an external service reached over HTTP. {@link #FRAME_SAMPLE} is here anyway,
 * with no base URL, because what the settings screen actually manages is <em>which enrichment this
 * deployment performs</em> — and frame sampling is the one phase whose toggle had no home, which
 * made the OCR toggle beside it a trap: OCR cannot produce anything without frames, so enabling it
 * alone put the console one click from a run that entered the phase and read an empty frame set.
 *
 * <p>Named for the role, not the vendor — {@link #TRANSCRIPTION} is whichever server answers
 * the TRANSCRIBE phase, whether that is a whisper-asr-webservice container or an
 * OpenAI-compatible host such as oMLX. The wire protocol is the connection's {@code provider},
 * a separate field.
 *
 * <p>Used as a {@code @PathVariable} type on purpose: Spring rejects an unknown name with
 * {@code MethodArgumentTypeMismatchException}, which {@code VidingestApiExceptionHandler}
 * already renders as a 400 ProblemDetail. No 404 branch is needed because there is no such
 * thing as a connection that does not exist — every constant here always does.
 */
public enum ConnectionName {

    /** Embeddings for CONTEXT chunks, knowledge units and search queries. */
    EMBEDDINGS,

    /** Chat completion for the KNOWLEDGE extraction phase. */
    KNOWLEDGE,

    /** Speech-to-text for the TRANSCRIBE phase. */
    TRANSCRIPTION,

    /** Speaker diarization sidecar for the DIARIZE phase. */
    DIARIZATION,

    /**
     * Keyframe extraction for the FRAME_SAMPLE phase. Local ffmpeg, so it carries a toggle and
     * nothing else — no base URL, no model, and nothing to probe.
     */
    FRAME_SAMPLE,

    /** PaddleOCR sidecar for the OCR phase. */
    OCR
}
