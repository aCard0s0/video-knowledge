package com.tradinglabs.vidingest.api.knowledge;

import java.util.Map;

/**
 * Public shape of a {@code vidingest_knowledge_units} row. Times are strings (ISO-8601) so
 * clients in non-Java languages don't have to deal with locale-dependent number parsing
 * surprises; the embedding column is intentionally NOT exposed.
 *
 * @param id                    UUID as string
 * @param videoId               UUID of the parent video
 * @param type                  one of {@link KnowledgeUnitType}
 * @param title                 short headline (≤ 512 chars) — nullable
 * @param content               full body of the unit
 * @param metadata              free-form JSONB: salience, source segment indices,
 *                              entity_type (for ENTITY), prompt_version, chat_model
 * @param startSeconds          inclusive video offset of the spanned material — nullable
 * @param endSeconds            exclusive video offset of the spanned material — nullable
 * @param createdAt             ISO-8601 timestamp at which the row was persisted
 */
public record KnowledgeUnitDto(
        String id,
        String videoId,
        KnowledgeUnitType type,
        String title,
        String content,
        Map<String, Object> metadata,
        Double startSeconds,
        Double endSeconds,
        String createdAt
) {
}
