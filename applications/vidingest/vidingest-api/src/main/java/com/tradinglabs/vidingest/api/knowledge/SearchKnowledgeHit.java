package com.tradinglabs.vidingest.api.knowledge;

/**
 * One hit returned by the M8 {@code /api/v1/knowledge/search} endpoint. Compact projection
 * — clients pull the full {@link KnowledgeUnitDto} via {@code /api/v1/videos/{id}/knowledge}
 * when they want metadata.
 *
 * @param knowledgeUnitId UUID as string
 * @param videoId         UUID of the parent video
 * @param type            knowledge unit type
 * @param title           short headline (may be null)
 * @param snippet         first ~220 chars of the unit's content
 * @param videoTitle      denormalised for caller convenience
 * @param channelName     denormalised for caller convenience
 * @param startSeconds    nullable
 * @param endSeconds      nullable
 */
public record SearchKnowledgeHit(
        String knowledgeUnitId,
        String videoId,
        KnowledgeUnitType type,
        String title,
        String snippet,
        String videoTitle,
        String channelName,
        Double startSeconds,
        Double endSeconds
) {
}
