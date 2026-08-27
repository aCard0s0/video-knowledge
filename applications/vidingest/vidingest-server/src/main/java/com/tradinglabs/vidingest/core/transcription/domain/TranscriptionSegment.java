package com.tradinglabs.vidingest.core.transcription.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "vidingest_transcription_segments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TranscriptionSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transcription_id", nullable = false)
    private Transcription transcription;

    @Column(name = "start_seconds", nullable = false)
    private Float startSeconds;

    @Column(name = "end_seconds", nullable = false)
    private Float endSeconds;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    /**
     * Optional reference to {@code vidingest_speakers.id}. Populated by {@code DiarizationService}
     * (M2) via time-overlap with pyannote's diarization windows. Nullable for transcripts that
     * were created before diarization existed and for runs where the phase is skipped.
     * ON DELETE SET NULL on the FK (see changeset {@code 008-segment-speaker.sql}).
     */
    @Column(name = "speaker_id")
    private UUID speakerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TranscriptionSegment that = (TranscriptionSegment) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
