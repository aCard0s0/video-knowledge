package com.tradinglabs.vidingest.api.transcription;

/**
 * Lightweight transcription view for UI consumption.
 *
 * <p>We intentionally avoid returning the full transcript body by default (it can be large); the UI
 * can rely on segments for detailed viewing.</p>
 */
public record VideoTranscriptionDetails(
        boolean present,
        String id,
        String status,
        String provider,
        String language,
        String createdAt,
        String updatedAt,
        Integer fullTextChars,
        String snippet
) {
}

