package com.tradinglabs.vidingest.core.knowledge.service;

/**
 * Thrown when the knowledge-extraction phase cannot complete — LLM unreachable, response
 * malformed beyond recovery, or persistence error. Surfaced via
 * {@code PipelineErrorClassifier} like other upstream-tool failures.
 *
 * <p>Per-batch parse failures are not surfaced as this exception — they get logged and
 * the batch's drafts are dropped so a single rogue LLM response doesn't fail the whole
 * video. Only when <i>every</i> batch fails do we escalate.
 */
public class KnowledgeExtractionFailureException extends RuntimeException {

    public KnowledgeExtractionFailureException(String message) {
        super(message);
    }

    public KnowledgeExtractionFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
