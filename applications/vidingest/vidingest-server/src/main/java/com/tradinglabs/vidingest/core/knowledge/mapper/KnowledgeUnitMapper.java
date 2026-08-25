package com.tradinglabs.vidingest.core.knowledge.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitDto;
import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.core.knowledge.domain.KnowledgeUnit;
import com.tradinglabs.vidingest.core.knowledge.repo.KnowledgeUnitRepository.KnowledgeUnitView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Entity → DTO mapper for {@link KnowledgeUnit}. We don't expose the embedding vector or
 * the parent {@code Video} entity over the wire — clients that need video metadata fetch
 * it separately via {@code /api/v1/videos/{id}}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KnowledgeUnitMapper {

    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public KnowledgeUnitDto toDto(KnowledgeUnit unit) {
        if (unit == null) return null;
        return new KnowledgeUnitDto(
                unit.getId() != null ? unit.getId().toString() : null,
                unit.getVideo() != null && unit.getVideo().getId() != null
                        ? unit.getVideo().getId().toString()
                        : null,
                unit.getType(),
                unit.getTitle(),
                unit.getContent(),
                unit.getMetadata(),
                unit.getStartSeconds(),
                unit.getEndSeconds(),
                unit.getCreatedAt() != null ? unit.getCreatedAt().toString() : null
        );
    }

    /**
     * Maps the embedding-free native projection used by the read-side knowledge endpoint.
     * Parses the raw JSONB {@code metadataJson} into a {@code Map} via Jackson; on parse
     * failure we log + emit {@code null} so a single corrupt row doesn't 500 the whole list.
     */
    public KnowledgeUnitDto toDto(KnowledgeUnitView view) {
        if (view == null) return null;
        return new KnowledgeUnitDto(
                view.getId(),
                view.getVideoId(),
                parseType(view.getType()),
                view.getTitle(),
                view.getContent(),
                parseMetadata(view.getMetadataJson()),
                view.getStartSeconds(),
                view.getEndSeconds(),
                view.getCreatedAt()
        );
    }

    private KnowledgeUnitType parseType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return KnowledgeUnitType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown knowledge unit type in DB: {}", raw);
            return null;
        }
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, METADATA_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse knowledge_units.metadata JSONB, returning null: {}", e.getMessage());
            return null;
        }
    }
}
