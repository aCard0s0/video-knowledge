package com.tradinglabs.vidingest.core.ocr.repo;

import com.tradinglabs.vidingest.core.ocr.domain.OcrResult;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Collection;
import java.util.UUID;

/**
 * Repository for {@link OcrResult}. Consumed by the M5 fusion phase, the M8 MCP tool
 * {@code getOcrResults}, and any future ranking/search work that wants to fold visual text
 * into the retrieval mix.
 */
@Repository
public interface OcrResultRepository extends JpaRepository<OcrResult, UUID> {

    List<OcrResult> findByFrame_IdOrderByCreatedAtAsc(UUID frameId);

    /**
     * Returns all OCR results for a video, ordered by the frame's timestamp. The join goes
     * through {@code VideoFrame} so callers don't need to load and traverse the frame list
     * themselves.
     *
     * <p>Uses {@code JOIN FETCH} so {@code OcrResult.frame} is fully initialised on return —
     * downstream consumers ({@code SegmentFusionService}) read {@code frame.timestampSeconds}
     * outside any transaction, which would otherwise trigger {@code LazyInitializationException}.
     */
    @Query("SELECT o FROM OcrResult o "
            + "JOIN FETCH o.frame f "
            + "WHERE f.video.id = :videoId "
            + "ORDER BY f.timestampSeconds ASC, o.createdAt ASC")
    List<OcrResult> findByVideoIdOrderByFrameTimestamp(@Param("videoId") UUID videoId);

    @Query("""
            select distinct f.id
            from OcrResult o
            join o.frame f
            where f.video.id = :videoId
            order by f.timestampSeconds asc
            """)
    List<UUID> findOcrFrameIdsByVideoIdOrderByTimestamp(@Param("videoId") UUID videoId, Pageable pageable);

    @Query("""
            select o
            from OcrResult o
            join fetch o.frame f
            where f.id in :frameIds
            order by f.timestampSeconds asc, o.createdAt asc
            """)
    List<OcrResult> findByFrameIdInOrderByFrameTimestamp(@Param("frameIds") Collection<UUID> frameIds);

    /**
     * Bulk-delete all OCR rows for a video so re-runs of the OCR phase converge cleanly.
     * Uses a single delete query rather than loading + cascading.
     *
     * <p>{@code @Transactional} is on the repository method itself rather than the caller —
     * {@link com.tradinglabs.vidingest.core.ocr.service.OcrService} calls this from within
     * its own non-transactional driver method ({@code ocrAllFrames}) so a method-level
     * annotation on a self-invoked helper is bypassed by the Spring CGLIB proxy.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM OcrResult o "
            + "WHERE o.frame.id IN (SELECT f.id FROM VideoFrame f WHERE f.video.id = :videoId)")
    int deleteByVideoId(@Param("videoId") UUID videoId);

    /**
     * Count of sampled frames that produced at least one OCR detection.
     *
     * <p>Used by UI aggregate read models that want a lightweight "frames with OCR" badge
     * without loading all OCR rows.
     */
    @Query("SELECT COUNT(DISTINCT f.id) FROM OcrResult o JOIN o.frame f WHERE f.video.id = :videoId")
    long countFramesWithOcrByVideoId(@Param("videoId") UUID videoId);
}
