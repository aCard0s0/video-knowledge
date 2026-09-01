package com.tradinglabs.vidingest.api.connections;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/v1/connections/{name}}. A full replacement of the mutable fields,
 * with two deliberate exceptions.
 *
 * <p>{@code apiKey} is three-valued: null (or absent) keeps whatever key is already stored,
 * {@code ""} clears it, and anything else replaces it. Without that distinction a console that
 * cannot read the current key back could never save any other field without wiping it.
 *
 * <p>{@code enabled} is nullable so that omitting it leaves the phase toggle alone. Only
 * KNOWLEDGE, DIARIZATION, FRAME_SAMPLE and OCR have one; sending it for EMBEDDINGS or
 * TRANSCRIPTION is ignored, since those phases have no master switch of their own.
 *
 * <p>{@code baseUrl} carries no {@code @NotBlank}: FRAME_SAMPLE is a local process and has none.
 * For every other connection it is still required — {@code ConnectionSettingsService} rejects a
 * blank or non-absolute value, which it has to do anyway to catch a URL with no scheme.
 */
public record UpdateConnectionRequest(
        @NotBlank
        @Size(max = 64)
        String provider,

        @Size(max = 2000)
        String baseUrl,

        @Size(max = 255)
        String model,

        @Size(max = 4000)
        String apiKey,

        Boolean enabled
) {
}
