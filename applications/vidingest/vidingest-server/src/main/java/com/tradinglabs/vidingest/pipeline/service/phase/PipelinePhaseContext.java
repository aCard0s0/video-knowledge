package com.tradinglabs.vidingest.pipeline.service.phase;

import com.tradinglabs.vidingest.videos.domain.Video;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.Map;
import java.util.UUID;

/**
 * Mutable carrier passed through {@link PipelinePhase} executions for a single run item.
 * Not thread-safe: only one phase runs against a given context at a time.
 *
 * <p>M1 of the knowledge-extraction expansion adds the {@code skipDiarize / skipFrames /
 * skipOcr / skipKnowledge} flags. The corresponding phases are wired up but no-op in M1
 * (always return {@code applies(ctx) = false}); the flags are plumbed end-to-end now so
 * later milestones can flip them on without further signature changes.
 */
@Getter
@RequiredArgsConstructor
public class PipelinePhaseContext {

    private final UUID runId;
    private final UUID itemId;
    private final String videoUrl;
    private final boolean skipTranscription;
    private final boolean skipContext;
    private final boolean skipDiarize;
    private final boolean skipFrames;
    private final boolean skipOcr;
    private final boolean skipKnowledge;

    @Setter
    private Map<String, Object> metadata;

    @Setter
    private String filePath;

    @Setter
    private Video video;

    /**
     * Convenience constructor for callers that only know about the original two skip flags.
     * Defaults the new enrichment skip flags to {@code true} (skip) so behaviour matches the
     * pre-M1 pipeline exactly.
     */
    public PipelinePhaseContext(UUID runId, UUID itemId, String videoUrl,
                                boolean skipTranscription, boolean skipContext) {
        this(runId, itemId, videoUrl, skipTranscription, skipContext, true, true, true, true);
    }
}
