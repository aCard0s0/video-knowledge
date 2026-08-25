package com.tradinglabs.vidingest.api.knowledge;

/**
 * Response from {@code POST /api/v1/videos/{videoId}/knowledge/regenerate}. Mirrors the
 * shape of {@code RegenerateContextResult} so the two regenerate endpoints feel uniform.
 *
 * @param videoId            UUID as string
 * @param knowledgeUnitCount how many rows the regenerated extraction produced
 */
public record RegenerateKnowledgeResult(
        String videoId,
        int knowledgeUnitCount
) {
}
