package com.tradinglabs.vidingest.core.fusion.repo;

import com.tradinglabs.vidingest.core.fusion.domain.MultimodalSegment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link MultimodalSegment}. M6 (knowledge extraction) reads these in
 * timeline order to build LLM prompts; M7 (enhanced context chunking) uses the same rows
 * when present and falls back to raw transcripts otherwise.
 */
@Repository
public interface MultimodalSegmentRepository extends JpaRepository<MultimodalSegment, UUID> {

    List<MultimodalSegment> findByVideo_IdOrderBySegmentIndexAsc(UUID videoId);

    @Query("""
            select ms
            from MultimodalSegment ms
            where ms.video.id = :videoId
              and (:fromSeconds is null or (ms.endSeconds is not null and ms.endSeconds > :fromSeconds))
              and (:toSeconds is null or (ms.startSeconds is not null and ms.startSeconds < :toSeconds))
            order by ms.segmentIndex asc
            """)
    Page<MultimodalSegment> findPageByVideoIdWindowedOrderBySegmentIndex(
            @Param("videoId") UUID videoId,
            @Param("fromSeconds") Double fromSeconds,
            @Param("toSeconds") Double toSeconds,
            Pageable pageable
    );

    long countByVideo_Id(UUID videoId);

    /**
     * Bulk-delete all multimodal segments for a video. Explicit {@code @Modifying} query
     * with {@code @Transactional} on the repo method itself, so callers that aren't
     * already in a transaction (e.g. REST per-phase rerun) still work — Spring's derived
     * {@code deleteBy*} requires an ambient transaction supplied by the caller.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM MultimodalSegment ms WHERE ms.video.id = :videoId")
    int deleteByVideo_Id(@Param("videoId") UUID videoId);
}
