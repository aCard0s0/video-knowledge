package com.tradinglabs.vidingest.core.diarization.mapper;

import com.tradinglabs.vidingest.api.speakers.SpeakerDto;
import com.tradinglabs.vidingest.core.diarization.domain.Speaker;
import org.springframework.stereotype.Component;

/**
 * Entity → DTO mapper for {@link Speaker}. {@code segmentCount} is supplied by the caller
 * (typically a service that aggregates over {@code transcription_segments.speaker_id})
 * rather than computed here — keeps the mapper a pure function.
 */
@Component
public class SpeakerMapper {

    public SpeakerDto toDto(Speaker speaker, long segmentCount) {
        if (speaker == null) return null;
        return new SpeakerDto(
                speaker.getId() != null ? speaker.getId().toString() : null,
                speaker.getVideo() != null && speaker.getVideo().getId() != null
                        ? speaker.getVideo().getId().toString()
                        : null,
                speaker.getLabel(),
                speaker.getDisplayName(),
                segmentCount,
                speaker.getCreatedAt() != null ? speaker.getCreatedAt().toString() : null
        );
    }
}
