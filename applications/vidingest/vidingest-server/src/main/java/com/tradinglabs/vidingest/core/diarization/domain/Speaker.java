package com.tradinglabs.vidingest.core.diarization.domain;

import com.tradinglabs.vidingest.videos.domain.Video;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * One row per distinct speaker identified by the diarization sidecar for a given video.
 * The label (e.g. {@code SPEAKER_00}) comes directly from pyannote and is stable within a
 * video but not across videos. {@code displayName} is operator/user-editable for friendly
 * UI labels and is exposed via the (M8) {@code renameSpeaker} MCP tool.
 *
 * <p>Backed by {@code vidingest_speakers} (Liquibase changeset {@code 007-speakers.sql}).
 */
@Entity
@Table(name = "vidingest_speakers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Speaker {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id", nullable = false)
    private Video video;

    /** pyannote-assigned label, e.g. {@code SPEAKER_00}. Unique per video. */
    @Column(nullable = false, length = 64)
    private String label;

    /** Optional friendly name. Settable by users via the M8 rename MCP tool. */
    @Column(name = "display_name", length = 255)
    private String displayName;

    /**
     * Optional voiceprint embedding (e.g. pyannote x-vector, 192-d). Populated when the
     * sidecar returns one; used by the future cross-video speaker re-identification feature
     * (see Future Improvements in the plan).
     */
    @Column(name = "embedding_voiceprint", columnDefinition = "vector(192)")
    private float[] embeddingVoiceprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Speaker that = (Speaker) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
