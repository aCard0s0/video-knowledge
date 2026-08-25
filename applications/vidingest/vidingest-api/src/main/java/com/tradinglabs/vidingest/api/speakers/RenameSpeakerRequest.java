package com.tradinglabs.vidingest.api.speakers;

import jakarta.validation.constraints.Size;

/**
 * Request payload for {@code PATCH /api/v1/speakers/{speakerId}}.
 * Updates the {@code displayName} only — speaker label and voiceprint are immutable from
 * the API. {@code null} or empty {@code displayName} clears the override and falls back
 * to the pyannote label.
 */
public record RenameSpeakerRequest(
        @Size(max = 255)
        String displayName
) {
}
