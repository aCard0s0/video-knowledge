package com.tradinglabs.vidingest.pipeline.service.phase;

import com.tradinglabs.vidingest.config.FrameSamplingConfig;
import com.tradinglabs.vidingest.config.OcrConfig;
import com.tradinglabs.vidingest.core.ocr.service.OcrService;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.videos.domain.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OCR phase (M4): runs PaddleOCR over the frames produced by {@link FrameSamplePhase} and
 * persists one {@code OcrResult} row per surviving text line.
 *
 * <p>Gates on:
 * <ul>
 *   <li>{@code vidingest.ocr.enabled} (operator master switch)</li>
 *   <li>{@code vidingest.frames.enabled} — the deployment toggle behind FRAME_SAMPLE</li>
 *   <li>the run's own opt-out ({@code skipPhases} naming OCR)</li>
 *   <li>FRAME_SAMPLE not being skipped — there's nothing to OCR if frame sampling did not
 *       run, so we skip OCR too rather than working through an empty frame set</li>
 * </ul>
 *
 * <p>FRAME_SAMPLE is checked <em>both</em> ways for one reason: a phase that did not run leaves no
 * frames whichever knob stopped it. Only the skip set was consulted, so a deployment with OCR on
 * and frames off ran OCR over an empty frame set and advertised it as runnable through
 * {@code /pipelines/capabilities}. The settings screen could reach that state in one click at the
 * time, because the connections API could flip OCR but had no entry for frame sampling. PR #49
 * closed the other half by adding {@code ConnectionName.FRAME_SAMPLE} — a connection with no
 * connection, carrying a phase toggle and no base URL — so the toggle this gate depends on is now
 * reachable from the same screen. The two changes only make sense together: this one stops OCR
 * pretending it can run, that one gives the operator the switch to fix it.
 *
 * <p>The console was <em>not</em> the thing that broke, which is worth knowing before "fixing" it
 * there too. Its phase picker already refused the combination on its own: {@code reasons()} in
 * {@code ui/phase-picker.ts} checks {@code disabledSet().has(requires)} beside the skip set, so
 * with frames off it greyed OCR out and said "OCR needs FRAME_SAMPLE, which is not running" while
 * the server was still claiming otherwise. And the per-phase re-run chips on the video screen
 * offer OCR regardless by design — {@code VideoPhaseRunnerService} bypasses {@code applies}
 * entirely, so this gate does not reach them and was never meant to. What the gate fixes is the
 * full pipeline run, and the honesty of the capabilities endpoint everything else reads.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class OcrPhase implements PipelinePhase {

    private final OcrService ocrService;
    private final OcrConfig ocrConfig;
    private final FrameSamplingConfig frameSamplingConfig;

    @Override
    public PipelineRunPhase phase() {
        return PipelineRunPhase.OCR;
    }

    @Override
    public boolean applies(PipelinePhaseContext ctx) {
        return ocrConfig.isEnabled()
                && frameSamplingConfig.isEnabled()
                && !ctx.skipped(PipelineRunPhase.OCR)
                && !ctx.skipped(PipelineRunPhase.FRAME_SAMPLE);
    }

    @Override
    public void execute(PipelinePhaseContext ctx) {
        Video video = ctx.getVideo();
        if (video == null) {
            log.warn("OcrPhase: ctx.video is null, skipping. runId={}, itemId={}",
                    ctx.getRunId(), ctx.getItemId());
            return;
        }
        ctx.setRowsAffected(ocrService.ocrAllFrames(video));
    }
}
