package com.tradinglabs.vidingest.api.knowledge;

/**
 * Type of a {@code KnowledgeUnit} produced by the {@code KNOWLEDGE} pipeline phase.
 *
 * <p>Introduced as a stub in M1 so DTOs in later milestones (M6/M8) can reference it without
 * additional API churn. The {@code KNOWLEDGE} phase itself is a no-op in M1.
 */
public enum KnowledgeUnitType {
    /** A named entity (person, place, organization, ticker, product, ...). */
    ENTITY,
    /** A topic discussed in the video (e.g. "options gamma squeeze"). */
    TOPIC,
    /** A condensed summary of a temporal window or the entire video. */
    SUMMARY,
    /** A factual or analytical claim made on-screen. */
    CLAIM,
    /** An open question raised in the video (useful for follow-up agent prompts). */
    QUESTION
}
