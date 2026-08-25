package com.tradinglabs.vidingest.core.diarization.repo;

import com.tradinglabs.vidingest.core.diarization.domain.Speaker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Speaker}. One Speaker row per (video, pyannote label) pair.
 */
@Repository
public interface SpeakerRepository extends JpaRepository<Speaker, UUID> {

    List<Speaker> findByVideo_Id(UUID videoId);

    Optional<Speaker> findByVideo_IdAndLabel(UUID videoId, String label);

    long countByVideo_Id(UUID videoId);

    /**
     * Bulk-delete all speaker rows for a video. Explicit {@code @Modifying} + repo-level
     * {@code @Transactional} so callers without an ambient transaction (REST per-phase
     * rerun) succeed.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM Speaker s WHERE s.video.id = :videoId")
    int deleteByVideo_Id(@Param("videoId") UUID videoId);
}
