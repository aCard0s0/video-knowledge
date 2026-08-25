package com.tradinglabs.vidingest.pipeline.service.phase;

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
 *   <li>the run's own opt-out ({@code skipPhases} naming OCR)</li>
 *   <li>FRAME_SAMPLE not being skipped — there's nothing to OCR if frame sampling did not
 *       run, so we skip OCR too rather than working through an empty frame set</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class OcrPhase implements PipelinePhase {

    private final OcrService ocrService;
    private final OcrConfig ocrConfig;

    @Override
    public PipelineRunPhase phase() {
        return PipelineRunPhase.OCR;
    }

    @Override
    public boolean applies(PipelinePhaseContext ctx) {
        return ocrConfig.isEnabled()
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
