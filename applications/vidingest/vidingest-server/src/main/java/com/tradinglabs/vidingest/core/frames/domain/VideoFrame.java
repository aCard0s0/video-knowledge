package com.tradinglabs.vidingest.core.frames.domain;

import com.tradinglabs.vidingest.videos.domain.Video;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One sampled keyframe extracted by {@code FrameSamplingService} (M3).
 *
 * <p>Backed by {@code vidingest_video_frames} (Liquibase changeset {@code 009-video-frames.sql}).
 * The JPG itself lives on disk at {@link #filePath} inside the per-video folder's
 * {@code frames/} subdirectory; deleting the video cascades the row via the FK, and
 * {@code VideoDeleteService} removes the per-video folder (which contains it).
 *
 * <p>{@code frameIndex} is the 0-based position within the video's frame sequence, ordered
 * by {@code timestampSeconds}. {@code samplingReason} records why this frame was kept
 * (see {@link SamplingReason}) and is purely informational — downstream phases consume
 * frames irrespective of reason.
 */
@Entity
@Table(name = "vidingest_video_frames")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VideoFrame {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id", nullable = false)
    private Video video;

    @Column(name = "frame_index", nullable = false)
    private Integer frameIndex;

    @Column(name = "timestamp_seconds", nullable = false)
    private Double timestampSeconds;

    @Column(name = "file_path", nullable = false, columnDefinition = "TEXT")
    private String filePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "sampling_reason", nullable = false, length = 32)
    private SamplingReason samplingReason;

    @Column
    private Integer width;

    @Column
    private Integer height;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VideoFrame that = (VideoFrame) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
