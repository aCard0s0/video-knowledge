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
     * Phase-specific row count (frames sampled, OCR rows persisted, segments fused, knowledge
     * units persisted, context chunks generated). Set by the phases that produce one; stays
     * {@code null} for phases where a count is not meaningful. Read back by the per-phase
     * rerun endpoint, which has no other way to see what a phase produced.
     */
    @Setter
    private Integer rowsAffected;

    /**
     * Context for a single-phase rerun against an already-persisted video. There is no run,
     * no run item and no source URL — the phase is invoked directly rather than through
     * {@code applies(ctx)}, so the skip flags are irrelevant and set to "do not skip".
     */
    public static PipelinePhaseContext forRerun(Video video) {
        PipelinePhaseContext ctx = new PipelinePhaseContext(
                null, null, null, false, false, false, false, false, false);
        ctx.setVideo(video);
        return ctx;
    }
}
