package com.tradinglabs.vidingest.pipeline.service.phase;

import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.videos.domain.Video;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable carrier passed through {@link PipelinePhase} executions for a single run item.
 * Not thread-safe: only one phase runs against a given context at a time.
 *
 * <p>{@code skipPhases} names the phases this run opts out of. It replaced one boolean per
 * optional phase, which had grown to six positional flags threaded through the REST records,
 * the MCP tools and the CLI — adding a phase meant editing all of them, and the flag order was
 * identical at every construction site, so inserting one silently reordered the rest.
 */
@Getter
@RequiredArgsConstructor
public class PipelinePhaseContext {

    private final UUID runId;
    private final UUID itemId;
    private final String videoUrl;
    private final Set<PipelineRunPhase> skipPhases;

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

    /** Whether this run opted out of {@code phase}. Non-optional phases can never be skipped. */
    public boolean skipped(PipelineRunPhase phase) {
        return phase != null && phase.isOptional() && skipPhases != null && skipPhases.contains(phase);
    }

    /**
     * Context for a single-phase rerun against an already-persisted video. There is no run, no
     * run item and no source URL — the phase is invoked directly rather than through
     * {@code applies(ctx)}, so nothing is skipped.
     */
    public static PipelinePhaseContext forRerun(Video video) {
        PipelinePhaseContext ctx = new PipelinePhaseContext(null, null, null, Set.of());
        ctx.setVideo(video);
        return ctx;
    }
}
