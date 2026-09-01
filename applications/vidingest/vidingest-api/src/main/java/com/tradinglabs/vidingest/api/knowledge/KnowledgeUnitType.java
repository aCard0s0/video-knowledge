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
    /**
     * A method the video teaches, as ordered steps each pairing a trigger condition with an
     * action. Added Sep 2026, because its absence was the largest single cause of instructional
     * videos extracting to nothing usable: a tutorial's value is its sequence of rules, and with
     * no type for a sequence the model could only file one under SUMMARY, which is a type whose
     * whole job is to compress. {@code KnowledgeExtractionPrompt} carries the measurement.
     */
    PROCEDURE,
    /** An open question raised in the video (useful for follow-up agent prompts). */
    QUESTION
}
