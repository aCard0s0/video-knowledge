package com.tradinglabs.vidingest.core.knowledge.dto;

import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;

import java.util.List;

/**
 * One knowledge unit as emitted by the LLM, before persistence. Maps 1:1 to the JSON shape
 * the prompt asks for so {@code KnowledgeChatClient} can parse it via Jackson without
 * custom converters.
 *
 * @param type                   the unit's category — see {@link KnowledgeUnitType}
 * @param title                  short headline (≤ 512 chars when persisted)
 * @param content                full body of the unit, embedded for semantic search
 * @param salience               0–1 model self-rated importance; rows below
 *                               {@code vidingest.knowledge.min-salience} are dropped
 * @param sourceSegmentIndices   indices into the {@code MultimodalSegment} list this unit
 *                               was derived from — kept for audit trail and the M8
 *                               multimodal-timeline tool
 * @param startSeconds           inclusive video offset of the spanned material
 * @param endSeconds             exclusive video offset of the spanned material
 * @param entityType             optional fine-grained entity category (PERSON,
 *                               ORGANIZATION, TICKER, ...) — only meaningful when
 *                               {@code type == ENTITY}, null otherwise
 */
public record KnowledgeUnitDraft(
        KnowledgeUnitType type,
        String title,
        String content,
        Double salience,
        List<Integer> sourceSegmentIndices,
        Double startSeconds,
        Double endSeconds,
        String entityType
) {
}
