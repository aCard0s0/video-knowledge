package com.tradinglabs.vidingest.core.transcription.repo;

import com.tradinglabs.vidingest.core.transcription.domain.TranscriptionSegment;
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
 * Repository for TranscriptionSegment entities
 * Part of the transcription feature
 */
@Repository
public interface TranscriptionSegmentRepository extends JpaRepository<TranscriptionSegment, UUID> {

    Page<TranscriptionSegment> findByTranscriptionId(UUID transcriptionId, Pageable pageable);

    /**
     * Used by {@code DiarizationService} (M2) to bulk-load all segments for a transcription
     * and assign {@code speaker_id} by time-overlap. Ordered by start time for deterministic
     * iteration; transcripts rarely exceed a few thousand segments so an unpaged fetch is fine.
     */
    List<TranscriptionSegment> findByTranscriptionIdOrderByStartSecondsAsc(UUID transcriptionId);

    /**
     * Bulk-delete all transcription segments for a transcription. Explicit {@code @Modifying}
     * query with its own {@code @Transactional} so callers without an ambient transaction
     * (e.g. the per-phase REST rerun for TRANSCRIBE) succeed — Spring's derived
     * {@code deleteBy*} would otherwise need a transactional caller.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM TranscriptionSegment ts WHERE ts.transcription.id = :transcriptionId")
    int deleteByTranscriptionId(@Param("transcriptionId") UUID transcriptionId);

    /**
     * Count of transcript segments tagged with a given speaker. Used by the M8
     * {@code getSpeakers} endpoint to surface "who spoke the most" without forcing the
     * caller to do their own aggregation.
     */
    long countBySpeakerId(UUID speakerId);

    long countByTranscription_Video_Id(UUID videoId);
}
