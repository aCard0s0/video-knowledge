package com.tradinglabs.vidingest.core.diarization.service;

import com.tradinglabs.vidingest.api.speakers.SpeakerDto;
import com.tradinglabs.vidingest.core.diarization.domain.Speaker;
import com.tradinglabs.vidingest.core.diarization.mapper.SpeakerMapper;
import com.tradinglabs.vidingest.core.diarization.repo.SpeakerRepository;
import com.tradinglabs.vidingest.core.transcription.repo.TranscriptionSegmentRepository;
import com.tradinglabs.vidingest.videos.service.VideoQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Speaker reads and the operator-supplied display-name override.
 *
 * <p>The pyannote {@code label} is immutable — it is how a speaker is identified across a
 * re-diarization. Only {@code displayName} is writable, and it is normalised to {@code null}
 * when blank so "cleared" is one value in the database rather than three.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpeakerService {

    private final SpeakerRepository speakerRepository;
    private final SpeakerMapper speakerMapper;
    private final TranscriptionSegmentRepository transcriptionSegmentRepository;
    private final VideoQueryService videoQueryService;

    @Transactional(readOnly = true)
    public List<SpeakerDto> listForVideo(UUID videoId) {
        videoQueryService.ensureExists(videoId);
        return speakerRepository.findByVideo_Id(videoId).stream()
                .map(s -> speakerMapper.toDto(s, transcriptionSegmentRepository.countBySpeakerId(s.getId())))
                .toList();
    }

    /**
     * @param displayName the override, or {@code null}/blank to clear it
     */
    @Transactional
    public SpeakerDto rename(UUID speakerId, String displayName) {
        Speaker speaker = speakerRepository.findById(speakerId)
                .orElseThrow(() -> new SpeakerNotFoundException(speakerId));

        String normalized = (displayName == null || displayName.isBlank()) ? null : displayName.trim();
        speaker.setDisplayName(normalized);
        speakerRepository.save(speaker);

        log.info("Speaker renamed: speakerId={}, displayName={}", speakerId, normalized);
        return speakerMapper.toDto(speaker, transcriptionSegmentRepository.countBySpeakerId(speakerId));
    }
}
