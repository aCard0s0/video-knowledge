package com.tradinglabs.vidingest.api.connections;

/**
 * The closed set of external runtimes VidIngest talks to, and therefore the keys of the
 * connection-settings API and of {@code vidingest_connections}.
 *
 * <p>Named for the role, not the vendor — {@link #TRANSCRIPTION} is whichever server answers
 * the TRANSCRIBE phase, whether that is a whisper-asr-webservice container or an
 * OpenAI-compatible host such as oMLX. The wire protocol is the connection's {@code provider},
 * a separate field.
 *
 * <p>Used as a {@code @PathVariable} type on purpose: Spring rejects an unknown name with
 * {@code MethodArgumentTypeMismatchException}, which {@code VidingestApiExceptionHandler}
 * already renders as a 400 ProblemDetail. No 404 branch is needed because there is no such
 * thing as a connection that does not exist — the five always do.
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

    /** PaddleOCR sidecar for the OCR phase. */
    OCR
}
