package com.tradinglabs.vidingest.core.frames.repo;

import com.tradinglabs.vidingest.core.frames.domain.VideoFrame;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link VideoFrame}. One row per persisted keyframe; downstream phases
 * (M4 OCR, future M9 vision captioning) read these by video id ordered by timestamp.
 */
@Repository
public interface VideoFrameRepository extends JpaRepository<VideoFrame, UUID> {

    List<VideoFrame> findByVideo_IdOrderByTimestampSecondsAsc(UUID videoId);

    /**
     * Bulk-delete all sampled frames for a video. Explicit {@code @Modifying} query with
     * its own {@code @Transactional} so REST per-phase reruns (no ambient transaction)
     * succeed; derived {@code deleteBy*} would otherwise need a transactional caller.
     *
     * <p>Cascades drop the dependent {@code vidingest_ocr_results} rows (FK with ON DELETE
     * CASCADE in changeset 010-ocr-results.sql).
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM VideoFrame vf WHERE vf.video.id = :videoId")
    int deleteByVideo_Id(@Param("videoId") UUID videoId);
}
