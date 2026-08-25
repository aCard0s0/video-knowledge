package com.tradinglabs.vidingest.core.transcription.repo;

import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.core.transcription.domain.Transcription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Transcription entities
 * Part of the transcription feature
 */
@Repository
public interface TranscriptionRepository extends JpaRepository<Transcription, UUID> {

    Optional<Transcription> findByVideoId(UUID videoId);
}
