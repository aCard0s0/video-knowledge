package com.tradinglabs.vidingest.pipeline.service.phase;

import com.tradinglabs.vidingest.config.FusionConfig;
import com.tradinglabs.vidingest.core.fusion.service.SegmentFusionService;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.videos.domain.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Multi-modal fusion phase (M5). Walks the video timeline in fixed windows and aggregates
 * transcript segments + speakers + OCR detections into {@code vidingest_multimodal_segments}.
 *
 * <p>Unlike the other enrichment phases, fusion is pure-Java and defaults to <b>enabled</b>:
 * the cost is negligible, and downstream phases (M6 KNOWLEDGE, M7 CONTEXT) want a uniform
 * input regardless of which upstream extractors actually ran. The gating is therefore:
 * <ul>
 *   <li>{@code vidingest.fusion.enabled} (operator master switch, default true)</li>
 * </ul>
 *
 * <p>There is no per-run {@code skipFuse} flag — fusion is fast enough that a separate
 * REST/MCP/CLI knob would just be noise. To skip it entirely, operators flip the master
 * switch off in config.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class FusePhase implements PipelinePhase {

    private final SegmentFusionService segmentFusionService;
    private final FusionConfig fusionConfig;

    @Override
    public PipelineRunPhase phase() {
        return PipelineRunPhase.FUSE;
    }

    @Override
    public boolean applies(PipelinePhaseContext ctx) {
        return fusionConfig.isEnabled();
    }

    @Override
    public void execute(PipelinePhaseContext ctx) {
        Video video = ctx.getVideo();
        if (video == null) {
            log.warn("FusePhase: ctx.video is null, skipping. runId={}, itemId={}",
                    ctx.getRunId(), ctx.getItemId());
            return;
        }
        ctx.setRowsAffected(segmentFusionService.fuse(video).size());
    }
}
