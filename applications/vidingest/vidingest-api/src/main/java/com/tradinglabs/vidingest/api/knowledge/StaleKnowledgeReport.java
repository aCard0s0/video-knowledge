package com.tradinglabs.vidingest.api.knowledge;

import java.util.List;

/**
 * Response from {@code GET /api/v1/knowledge/stale}: which videos were extracted under an older
 * prompt than the one this server now sends.
 *
 * <p>Exists because {@code metadata.prompt_version} was written on every knowledge unit and nothing
 * ever read it. A prompt upgrade changes what extraction <em>means</em> — v2 to v3 moved rule
 * recovery on the reference video from 4.3 of 19 to 14.7 — so rows from an older prompt are not
 * comparable with newer ones, and until this existed, finding them meant querying the database by
 * hand.
 *
 * <p>A report rather than a bulk re-extract. One video costs ~2.5 minutes of LLM time, so doing them
 * all in one request would hold it open for hours and need async machinery that
 * {@code POST /videos/{videoId}/knowledge/regenerate} already makes unnecessary: read this, then
 * drive that per video at whatever rate suits the deployment.
 *
 * @param currentPromptVersion what {@code KnowledgeExtractionPrompt.PROMPT_VERSION} is now
 * @param staleVideoCount      how many videos this report lists
 * @param truncated            true when {@code limit} cut the list short, so the caller knows to
 *                             come back rather than believing it is finished
 * @param videos               newest extraction first, so the most recently touched videos —
 *                             the ones an operator is most likely to care about — come first
 */
public record StaleKnowledgeReport(
        int currentPromptVersion,
        int staleVideoCount,
        boolean truncated,
        List<StaleKnowledgeVideo> videos
) {

    /**
     * One video needing re-extraction.
     *
     * @param videoId          UUID as string
     * @param videoTitle       for a human reading the report
     * @param channelName      nullable; videos ingested by URL have no channel
     * @param knowledgeUnits   how many rows would be replaced
     * @param minPromptVersion the oldest prompt version among this video's units, and the reason
     *                         it is listed
     * @param maxPromptVersion the newest. Differing from {@code minPromptVersion} means an
     *                         interrupted run left mixed versions behind — the phase is
     *                         wipe-then-repopulate, so that should not otherwise happen
     * @param lastExtractedAt  when the newest unit was written, ISO-8601
     */
    public record StaleKnowledgeVideo(
            String videoId,
            String videoTitle,
            String channelName,
            long knowledgeUnits,
            int minPromptVersion,
            int maxPromptVersion,
            String lastExtractedAt
    ) {
    }
}
