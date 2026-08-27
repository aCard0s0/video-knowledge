package com.tradinglabs.vidingest.core.fusion.domain;

import com.tradinglabs.vidingest.videos.domain.Video;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * One window's worth of fused multi-modal content for a video — the input M6's knowledge
 * extraction and M7's enhanced context chunking will consume.
 *
 * <p>Backed by {@code vidingest_multimodal_segments} (Liquibase changeset
 * {@code 011-multimodal-segments.sql}). Cascades on video delete via the FK.
 *
 * <p>Fields are intentionally nullable so a window with only one signal (e.g. a silent
 * presentation with on-screen text but no transcript) still gets a row — downstream phases
 * see a uniform shape regardless of which upstream phases ran.
 */
@Entity
@Table(name = "vidingest_multimodal_segments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MultimodalSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id", nullable = false)
    private Video video;

    @Column(name = "segment_index", nullable = false)
    private Integer segmentIndex;

    @Column(name = "start_seconds", nullable = false)
    private Double startSeconds;

    @Column(name = "end_seconds", nullable = false)
    private Double endSeconds;

    /** Concatenated transcript segments overlapping this window. Null when none exist. */
    @Column(name = "transcript_text", columnDefinition = "TEXT")
    private String transcriptText;

    /** Deduplicated OCR text from frames in this window. Null when no OCR ran or matched. */
    @Column(name = "ocr_text", columnDefinition = "TEXT")
    private String ocrText;

    /**
     * Distinct speaker labels drawn from the transcript segments in this window. Stored as a
     * PostgreSQL {@code text[]} — Hibernate 6's {@link SqlTypes#ARRAY} maps it natively.
     * Null when no diarization ran or no transcript segments had a resolvable speaker.
     *
     * <p>Labels, not ids, and deliberately: DIARIZE is wipe-then-repopulate, so re-running it
     * alone deletes every {@code vidingest_speakers} row and recreates it under a new uuid. An
     * id array had no foreign key to catch that and went stale silently; the label is stable
     * across the re-run and {@code UNIQUE (video_id, label)} already makes it the natural key.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "speaker_labels", columnDefinition = "text[]")
    private String[] speakerLabels;

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
        MultimodalSegment that = (MultimodalSegment) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
