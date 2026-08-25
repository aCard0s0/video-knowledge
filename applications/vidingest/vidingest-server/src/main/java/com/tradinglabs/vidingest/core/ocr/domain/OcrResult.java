package com.tradinglabs.vidingest.core.ocr.domain;

import com.tradinglabs.vidingest.core.frames.domain.VideoFrame;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One row per text line detected by {@code paddleocr-server} on a sampled keyframe.
 *
 * <p>Backed by {@code vidingest_ocr_results} (Liquibase changeset {@code 010-ocr-results.sql}).
 * Cascades to deletion via the FK on {@link #frame}, which in turn cascades when the video
 * is deleted. The {@code bbox} field is the raw PaddleOCR polygon stored as JSONB so callers
 * can rebuild the bounding box without imposing a fixed Java shape on it.
 */
@Entity
@Table(name = "vidingest_ocr_results")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OcrResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "frame_id", nullable = false)
    private VideoFrame frame;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column
    private Float confidence;

    /**
     * Bounding-box polygon from PaddleOCR — usually 4 corners as
     * {@code [[x1,y1],[x2,y2],[x3,y3],[x4,y4]]} but kept generic so PaddleOCR variants
     * that emit more corners still round-trip. Stored as JSONB.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<List<Double>> bbox;

    @Column(length = 10)
    private String language;

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
        OcrResult that = (OcrResult) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
