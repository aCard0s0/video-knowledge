package com.tradinglabs.vidingest.core.fusion.mapper;

import com.tradinglabs.vidingest.api.fusion.MultimodalSegmentDto;
import com.tradinglabs.vidingest.core.fusion.domain.MultimodalSegment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Entity → DTO mapper for {@link MultimodalSegment}. Speaker UUIDs flatten to strings so
 * non-Java clients don't have to construct UUID types.
 */
@Component
public class MultimodalSegmentMapper {

    public MultimodalSegmentDto toDto(MultimodalSegment seg) {
        if (seg == null) return null;
        UUID[] speakers = seg.getSpeakerIds();
        List<String> speakerIds = speakers == null
                ? List.of()
                : Arrays.stream(speakers).filter(java.util.Objects::nonNull).map(UUID::toString).toList();
        return new MultimodalSegmentDto(
                seg.getId() != null ? seg.getId().toString() : null,
                seg.getVideo() != null && seg.getVideo().getId() != null
                        ? seg.getVideo().getId().toString()
                        : null,
                seg.getSegmentIndex() != null ? seg.getSegmentIndex() : 0,
                seg.getStartSeconds() != null ? seg.getStartSeconds() : 0.0,
                seg.getEndSeconds() != null ? seg.getEndSeconds() : 0.0,
                seg.getTranscriptText(),
                seg.getOcrText(),
                speakerIds,
                seg.getCreatedAt() != null ? seg.getCreatedAt().toString() : null
        );
    }
}
