package com.tradinglabs.vidingest.api.speakers;

/**
 * Public shape of a {@code vidingest_speakers} row. The {@code embedding_voiceprint}
 * column is intentionally NOT exposed — voiceprints are large and only useful internally
 * for the future cross-video re-identification feature.
 *
 * @param id            UUID as string
 * @param videoId       UUID of the parent video
 * @param label         pyannote-assigned label (e.g. {@code SPEAKER_00}), stable per video
 * @param displayName   operator/user-supplied friendly name (nullable)
 * @param segmentCount  number of transcription_segments tagged with this speaker — useful
 *                      to spot the dominant speaker without a separate query
 * @param createdAt     ISO-8601 timestamp
 */
public record SpeakerDto(
        String id,
        String videoId,
        String label,
        String displayName,
        long segmentCount,
        String createdAt
) {
}
