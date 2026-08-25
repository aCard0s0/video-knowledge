package com.tradinglabs.vidingest.core.knowledge.service;

import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitDto;
import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.core.knowledge.mapper.KnowledgeUnitMapper;
import com.tradinglabs.vidingest.core.knowledge.repo.KnowledgeUnitRepository;
import com.tradinglabs.vidingest.videos.service.VideoQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read model over {@code vidingest_knowledge_units}. Ordered by {@code created_at} ascending so
 * callers see the units in the order the LLM emitted them.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeQueryService {

    private final KnowledgeUnitRepository knowledgeUnitRepository;
    private final KnowledgeUnitMapper knowledgeUnitMapper;
    private final VideoQueryService videoQueryService;

    @Transactional(readOnly = true)
    public List<KnowledgeUnitDto> listForVideo(UUID videoId, KnowledgeUnitType type) {
        videoQueryService.ensureExists(videoId);
        // Use the native projection that skips the `embedding vector(1536)` column —
        // entity-shape SELECTs trip the pgvector array-delimiter bug in the PostgreSQL
        // JDBC driver and 500 the call. See KnowledgeUnitRepository.findViewsByVideoId.
        return knowledgeUnitRepository.findViewsByVideoId(videoId, type == null ? null : type.name()).stream()
                .map(knowledgeUnitMapper::toDto)
                .toList();
    }
}
